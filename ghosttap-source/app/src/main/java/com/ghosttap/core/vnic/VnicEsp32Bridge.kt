package com.ghosttap.core.vnic

import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.IntelCapability
import com.ghosttap.core.OpResult
import com.ghosttap.core.esp32.VirtualEsp32Client
import kotlinx.coroutines.delay

/**
 * VirtualESP32 bridge — the VNIC's view of the Virtual ESP32 app.
 *
 * Registers the vesp32.* capabilities into [CapabilityRegistry] so the
 * planner, the investigations UI and the VNIC all see them, and implements
 * the ESP32 bring-up ([VnicBringUpPort]) through the REAL
 * [VirtualEsp32Client] IPC: every run calls a real client method and
 * builds its [OpResult] from REAL state (uartLog tail, targetInfo, GPIO
 * states). Nothing is simulated here — the UART/GPIO operations reach
 * the actual executing Xtensa firmware in the Virtual ESP32 app.
 *
 * `verified` is false on every capability (registry contract: true only
 * after a real-hardware test) and stays false until the ESP32 bring-up
 * is proven on a device with dev.virtualesp32 installed.
 *
 * The QEMU backend inside VirtualESP32 is NOT built (the JNI boundary is
 * deliberately unavailable) — this bridge never claims QEMU execution.
 */
class VnicEsp32Bridge(private val client: VirtualEsp32Client) : VnicBringUpPort {

    /** Register all vesp32.* capabilities. Safe to call once; re-registering replaces the same ids. */
    fun register() {
        reg(
            id = "vesp32.status", title = "VirtualESP32 status",
            detail = "Queries the Virtual ESP32 guest status and reports the real target info (status, CPU, backend, firmware).",
            category = "ESP32", riskLevel = "READ",
            sourceMethod = "VirtualEsp32Client.queryStatus",
            effects = "Asks the Virtual ESP32 app for its guest status. Nothing is changed.",
            run = {
                try {
                    client.queryStatus()
                    delay(1500)
                    val info = client.targetInfo.value
                    if (info == null) {
                        OpResult(false, "No status response from VirtualESP32 — is the app installed and the guest started?")
                    } else {
                        OpResult(
                            true,
                            "VirtualESP32: status=${info.status}, cpu=${info.cpu}, backend=${info.backend}, firmware=${info.firmware}.",
                            "status=${info.status} cpu=${info.cpu} backend=${info.backend} firmware=${info.firmware}"
                        )
                    }
                } catch (e: Exception) {
                    OpResult(false, "Status query failed: ${e.message ?: "unknown error"}")
                }
            }
        )
        reg(
            id = "vesp32.start_guest", title = "Start VirtualESP32 guest",
            detail = "Starts the guest firmware in the Virtual ESP32 app and waits for it to report running.",
            category = "ESP32", riskLevel = "CONFIGURE",
            sourceMethod = "VirtualEsp32Client.startGuest",
            effects = "Starts the emulated ESP32 guest. The Virtual ESP32 app begins executing its firmware.",
            run = {
                try {
                    client.startGuest()
                    delay(3000)
                    client.queryStatus()
                    delay(1500)
                    val info = client.targetInfo.value
                    val running = info?.status?.contains("run", ignoreCase = true) == true
                    if (running) {
                        OpResult(true, "VirtualESP32 guest running (status=${info!!.status}, firmware=${info.firmware}).", "status=${info.status}")
                    } else {
                        OpResult(
                            false,
                            "Guest did not report running (status=${info?.status ?: "no response"}). " +
                                "Check: VirtualESP32 bridge build installed, firmware imported in the VirtualESP32 app."
                        )
                    }
                } catch (e: Exception) {
                    OpResult(false, "Start guest failed: ${e.message ?: "unknown error"}")
                }
            }
        )
        reg(
            id = "vesp32.send_uart", title = "Send UART to VirtualESP32 guest",
            detail = "Sends text over UART to the running guest and reports the real reply from the UART log.",
            category = "ESP32", riskLevel = "WRITE",
            inputs = listOf("text"),
            sourceMethod = "VirtualEsp32Client.sendUart",
            effects = "Writes the given text to the guest over UART. The guest firmware receives it and may answer.",
            run = { args ->
                try {
                    val text = args["text"].orEmpty()
                    if (text.isBlank()) {
                        OpResult(false, "Nothing to send — text was empty.")
                    } else {
                        val before = client.uartLog.value.size
                        client.sendUart(text)
                        delay(1500)
                        val fresh = client.uartLog.value.drop(before)
                        val reply = fresh.filter { it.startsWith("RX:") }
                        if (reply.isNotEmpty()) {
                            OpResult(true, "UART reply: ${reply.joinToString(" ").take(300)}", reply.joinToString("\n"))
                        } else {
                            OpResult(true, "Sent '$text' — no UART reply arrived yet.", fresh.joinToString("\n"))
                        }
                    }
                } catch (e: Exception) {
                    OpResult(false, "UART send failed: ${e.message ?: "unknown error"}")
                }
            }
        )
        reg(
            id = "vesp32.read_gpio", title = "Read VirtualESP32 GPIO",
            detail = "Reads the guest's GPIO2 input state (the pin the client tracks live).",
            category = "ESP32", riskLevel = "READ",
            sourceMethod = "VirtualEsp32Client.readGpio2",
            effects = "Asks the guest for its GPIO2 level. Nothing is changed.",
            run = {
                try {
                    client.readGpio2()
                    delay(1000)
                    val gpio2 = client.gpio2State.value
                    if (gpio2 == null) {
                        OpResult(false, "GPIO2 read returned no value yet — guest may not have answered.")
                    } else {
                        OpResult(true, "GPIO2=${if (gpio2) "HIGH" else "LOW"} (esp32).", "gpio2=$gpio2")
                    }
                } catch (e: Exception) {
                    OpResult(false, "GPIO read failed: ${e.message ?: "unknown error"}")
                }
            }
        )
        reg(
            id = "vesp32.use_builtin_firmware", title = "Use VirtualESP32 built-in firmware",
            detail = "Tells the Virtual ESP32 app to run its built-in firmware, then reports what it actually loaded.",
            category = "ESP32", riskLevel = "CONFIGURE",
            sourceMethod = "VirtualEsp32Client.useBuiltinFirmware",
            effects = "Switches the guest to the Virtual ESP32 app's built-in firmware.",
            run = {
                try {
                    client.useBuiltinFirmware()
                    delay(1500)
                    val fw = client.firmwareInfo.value
                    if (fw == null) {
                        OpResult(false, "No firmware-loaded confirmation came back from VirtualESP32.")
                    } else {
                        OpResult(true, "Firmware: ${fw.fileName} (${fw.imageType}, ${fw.fileSize} bytes).", "firmware=${fw.fileName}")
                    }
                } catch (e: Exception) {
                    OpResult(false, "Built-in firmware switch failed: ${e.message ?: "unknown error"}")
                }
            }
        )
    }

    /** [VnicBringUpPort] for ESP32 targets — delegates to [bringUpEsp32]. */
    override suspend fun bringUp(target: VnicBringUpTarget, note: String): VnicBringUpResult =
        bringUpEsp32()

    /**
     * ESP32 bring-up: start_guest -> status -> send_uart("AT") ->
     * read_gpio -> final status check. CONTROLLABLE only when the guest is
     * running AND a UART response arrived; connection held = guest still
     * running at the final check. The guest is NOT stopped afterwards —
     * Ghost Tap holds the connection and the owner drives it from the
     * vesp32 screen.
     */
    suspend fun bringUpEsp32(): VnicBringUpResult {
        val log = mutableListOf<String>()
        fun say(s: String) = log.add(s)
        fun fail(reason: String): VnicBringUpResult {
            say("FAILED: $reason")
            return VnicBringUpResult(false, "", emptyList(), log.toList(), false, reason)
        }
        if (!client.isInstalled()) {
            return fail("VirtualESP32 app (dev.virtualesp32) is not installed on this phone — ESP32 bring-up needs it.")
        }
        say("Starting VirtualESP32 guest…")
        client.startGuest()
        delay(3000)
        client.queryStatus()
        delay(1500)
        var info = client.targetInfo.value
        var guestRunning = info?.status?.contains("run", ignoreCase = true) == true
        if (!guestRunning) {
            return fail(
                "VirtualESP32 guest did not report running (status=${info?.status ?: "no response"}). " +
                    "Check the VirtualESP32 app: bridge build installed, firmware imported."
            )
        }
        say("Guest running (${info!!.status}). Sending AT over UART…")
        val before = client.uartLog.value.size
        client.sendUart("AT")
        delay(1500)
        val fresh = client.uartLog.value.drop(before)
        val uartResponse = fresh.any { it.startsWith("RX:") }
        say(if (uartResponse) "UART answered: ${fresh.filter { it.startsWith("RX:") }.joinToString(" ").take(200)}" else "No UART reply arrived.")
        client.readGpio2()
        delay(1000)
        val gpio2 = client.gpio2State.value
        say("GPIO2=${gpio2?.let { if (it) "HIGH" else "LOW" } ?: "unknown"}. Final status check…")
        client.queryStatus()
        delay(1000)
        info = client.targetInfo.value
        guestRunning = info?.status?.contains("run", ignoreCase = true) == true
        if (!guestRunning) {
            return fail("Guest stopped during bring-up (status=${info?.status ?: "no response"}) — the link dropped before setup finished.")
        }
        if (!uartResponse) {
            return fail("Guest is running but the UART probe got no reply — the firmware may not answer AT, or the link is one-way.")
        }
        val controls = listOf("UART", "GPIO2", "GPIO4")
        say("Ready: guest running, UART answering, controls mapped (${controls.joinToString()}). Connection held.")
        return VnicBringUpResult(
            ready = true,
            route = "vesp32",
            controlLines = listOf(
                "VirtualESP32 guest running (${info!!.status}, firmware=${info.firmware}).",
                "WHAT YOU CAN CONTROL: UART terminal · GPIO2 (read) · GPIO4 (write)."
            ),
            log = log.toList(),
            connectionHeld = true,
            failReason = null
        )
    }

    private fun reg(
        id: String, title: String, detail: String, category: String,
        riskLevel: String,
        inputs: List<String> = emptyList(),
        sourceMethod: String = "",
        effects: String = "",
        run: suspend (Map<String, String>) -> OpResult
    ) {
        CapabilityRegistry.register(
            IntelCapability(
                id, title, detail, category, inputs, emptyList(), emptyList(),
                readOnly = riskLevel == "READ",
                riskLevel = riskLevel, effects = effects,
                verified = false, sourceMethod = sourceMethod, run = run
            )
        )
    }
}
