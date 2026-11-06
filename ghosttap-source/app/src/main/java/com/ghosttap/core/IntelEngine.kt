package com.ghosttap.core

import com.ghosttap.GhostTapViewModel
import com.ghosttap.NetworkToolsViewModel
import com.ghosttap.UsbUartViewModel
import com.ghosttap.VisualIntelViewModel
import com.ghosttap.data.IntelDatabase
import com.ghosttap.data.Observation
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Wires Ghost Tap's real viewmodels into the [CapabilityRegistry].
 *
 * Every capability below calls an ACTUAL viewmodel function — never a stub,
 * never a fake. Full autonomous mode: steps auto-run at every risk level
 * with no confirmation gates. Each step's riskLevel (READ / CONFIGURE /
 * WRITE / CONTROL) is recorded on the step and every auto-run action is
 * logged to the investigations timeline. Access controls are never bypassed:
 * pairing/password-gated operations fail honestly instead of running.
 *
 * To add a new tool: register() one [IntelCapability] here (see the contract
 * KDoc on [CapabilityRegistry.register]). Nothing else needs to change — the
 * planner, the investigations screen, and knowledge search pick it up.
 */
class IntelEngine(
    private val ble: GhostTapViewModel,
    private val net: NetworkToolsViewModel,
    private val usb: UsbUartViewModel,
    private val visual: VisualIntelViewModel,
    /** Optional: when present, proposed extensions are persisted for restore. */
    private val db: IntelDatabase? = null
) {

    init {
        registerVisual()
        registerBle()
        registerNetwork()
        registerUsb()
        registerNfc()
        registerSystem()
    }

    /** Runs one registered capability by id. Unknown ids come back as ok=false, never crash. */
    suspend fun run(id: String, args: Map<String, String>): OpResult {
        val cap = CapabilityRegistry.get(id)
            ?: return OpResult(false, "Unknown capability '$id' — not in the registry.")
        return try {
            cap.run(args)
        } catch (e: Exception) {
            OpResult(false, "Capability '$id' crashed: ${e.message ?: "unknown problem"}")
        }
    }

    /** Planner support: which of these ids are NOT registered. */
    fun validate(planIds: List<String>): List<String> =
        planIds.filter { CapabilityRegistry.get(it) == null }

    /**
     * Bring-up support: live GATT status text for a BLE target
     * (e.g. "CONNECTING…", "CONNECTED — discovering services…").
     */
    fun bleGattStatus(targetId: String): String = ble.gattStatusText(targetId)

    /**
     * Bring-up support: is a tap card still held to the phone right
     * now? True when the app has a tag on file (the last tag actually
     * tapped) — the same check the preparation library's tag-present
     * guard ([com.ghosttap.core.ops.ensureTagPresent]) uses.
     */
    fun nfcTagPresent(): Boolean = ble.lastNfcTag != null

    /**
     * Bring-up support: counts of controllable characteristics from the REAL
     * GATT discovery — never fabricated.
     */
    fun bleControlSummary(targetId: String): BleControlCounts {
        val services = ble.discoveredServices(targetId)
        val chars = services.flatMap { it.characteristics }
        return BleControlCounts(
            services = services.size,
            read = chars.count { "READ" in it.properties },
            write = chars.count {
                "WRITE" in it.properties || "WRITE_NO_RESPONSE" in it.properties ||
                    "SIGNED_WRITE" in it.properties
            },
            notify = chars.count {
                "NOTIFY" in it.properties || "INDICATE" in it.properties
            }
        )
    }

    /**
     * Bring-up support: handshake (C-11) — first readable characteristics
     * for a BLE target, in discovery order (max 3). Empty when nothing on
     * the device can be read: no safe probe exists for this device.
     */
    fun bleFirstReadables(targetId: String, max: Int = 3): List<Pair<String, String>> =
        firstReadableCharacteristics(ble.discoveredServices(targetId), max)

    /**
     * Bring-up support: handshake (C-11) — issue one BLE characteristic
     * read. Async: the answer lands in the endpoint history via the
     * normal read callback; use [bleReadAnswered] to check for it.
     */
    fun bleRead(targetId: String, serviceUuid: String, charUuid: String) =
        ble.bleRead(targetId, serviceUuid, charUuid)

    /**
     * Bring-up support: handshake (C-11) — did any of [charUuids] answer
     * a read at or after [sinceMs]? A stale value from an earlier read
     * never counts.
     */
    fun bleReadAnswered(targetId: String, charUuids: Set<String>, sinceMs: Long): Boolean =
        readAnsweredSince(ble.endpointHistoryFor(targetId), charUuids, sinceMs)

    /**
     * Bring-up support: handshake (C-11) — how much USB RX text has
     * arrived so far. Snapshot before listening, compare after: growth
     * means the board talked on its own, and listening sends nothing.
     */
    fun usbRxLength(): Int = usb.rxText.length

    /**
     * Bring-up support: handshake (C-11) — the board-test status line
     * ("The board answered — ready to load code" on success). The test
     * runs async, so poll this until it leaves "Checking…".
     */
    fun usbEsp32Status(): String = usb.esp32Status

    // ---------- helpers ----------

    /** Wait for the network-tools log to grow, then return the new lines. */
    private suspend fun captureNetLines(before: Int, timeoutMs: Long = 15000): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val cur = net.log
            if (cur.size > before) return cur.drop(before)
            delay(500)
        }
        return net.log.drop(before)
    }

    private fun reg(
        id: String, title: String, detail: String, category: String,
        riskLevel: String,
        inputs: List<String> = emptyList(),
        outputs: List<String> = emptyList(),
        preconditions: List<String> = emptyList(),
        effects: String = "",
        verified: Boolean = false,
        sourceMethod: String = "",
        run: suspend (Map<String, String>) -> OpResult
    ) {
        CapabilityRegistry.register(
            IntelCapability(
                id, title, detail, category, inputs, outputs, preconditions,
                readOnly = riskLevel == "READ",
                riskLevel = riskLevel, effects = effects,
                verified = verified, sourceMethod = sourceMethod, run = run
            )
        )
    }

    /** ViewModel instances behind the registry — used by [CapabilityScanner]. */
    fun viewModels(): List<Any> = listOf(ble, net, usb, visual)

    // ---------- VISUAL ----------

    private fun registerVisual() {
        reg(
            id = "visual.capture_analyze", title = "Take a photo and read it",
            riskLevel = "READ", verified = true,
            sourceMethod = "VisualIntelViewModel.captureAndAnalyze",
            effects = "Takes one photo and runs on-device text/barcode reading. Nothing leaves the phone; the photo is not saved, only the extracted text.",
            detail = "Takes a camera photo and reads its words, barcodes, and possible markings.",
            category = "VISUAL",
            outputs = listOf("finding: text, barcodes, markings"),
            preconditions = listOf("camera permission granted", "camera preview ready"),
            run = { _ ->
                try {
                    val beforeId = visual.lastFinding?.id
                    visual.captureAndAnalyze()
                    var found: com.ghosttap.VisualFinding? = null
                    val deadline = System.currentTimeMillis() + 25000
                    while (System.currentTimeMillis() < deadline) {
                        val f = visual.lastFinding
                        if (f != null && f.id != beforeId) {
                            found = f
                            break
                        }
                        delay(500)
                    }
                    if (found != null) {
                        val preview = found.fullText.trim().take(300)
                        OpResult(
                            true,
                            "Photo analyzed: ${found.barcodes.size} barcode(s), " +
                                "${found.markings.size} candidate marking(s).",
                            buildString {
                                append("TEXT:\n$preview")
                                if (found.barcodes.isNotEmpty()) {
                                    append("\nBARCODES:\n")
                                    append(found.barcodes.joinToString("\n") {
                                        "${it.formatName}: ${it.rawValue.take(120)}"
                                    })
                                }
                                if (found.markings.isNotEmpty()) {
                                    append("\nMARKINGS: ${found.markings.joinToString(", ")}")
                                }
                            }
                        )
                    } else {
                        OpResult(false, "No finding arrived — ${visual.statusLine}")
                    }
                } catch (e: Exception) {
                    OpResult(false, "Capture failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "visual.pick_gallery", title = "Read a photo from your gallery",
            riskLevel = "READ", verified = false,
            sourceMethod = "Manual: VISUAL INTEL screen (PICK FROM GALLERY)",
            effects = "Opens the photo picker — you choose the photo yourself. Ghost Tap analyzes only what you pick.",
            detail = "Lets you pick an existing photo to read its words and barcodes.",
            category = "VISUAL",
            preconditions = listOf("user picks the photo — needs one tap"),
            run = { _ ->
                OpResult(
                    false,
                    "Gallery pick needs a tap — use PICK FROM GALLERY on the VISUAL INTEL screen."
                )
            }
        )
        reg(
            id = "visual.copy_text", title = "Copy text to clipboard",
            riskLevel = "READ", verified = true,
            sourceMethod = "VisualIntelViewModel.copyToClipboard",
            effects = "Puts the given text on the phone clipboard so it can be pasted into any Ghost Tap tool. Nothing else changes.",
            detail = "Copies the given text so it can be pasted into any Ghost Tap tool.",
            category = "VISUAL",
            inputs = listOf("text"),
            run = { args ->
                try {
                    val text = args["text"].orEmpty()
                    if (text.isBlank()) {
                        OpResult(false, "Nothing to copy — text was empty.")
                    } else {
                        visual.copyToClipboard("intel", text)
                        OpResult(true, "Copied ${text.length} characters to clipboard.")
                    }
                } catch (e: Exception) {
                    OpResult(false, "Copy failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "visual.last_finding", title = "Read the last photo finding",
            riskLevel = "READ", verified = true,
            sourceMethod = "VisualIntelViewModel.lastFinding",
            effects = "Reads back the last photo analysis from memory. Nothing changes.",
            detail = "Returns the text, barcodes, and markings from the most recent photo analysis.",
            category = "VISUAL",
            outputs = listOf("finding summary"),
            run = { _ ->
                try {
                    val f = visual.lastFinding
                    if (f == null) {
                        OpResult(false, "No photo analyzed yet.")
                    } else {
                        OpResult(
                            true,
                            "Last finding (${f.source}): ${f.fullText.trim().take(200)}",
                            "MARKINGS: ${f.markings.joinToString(", ")}\n" +
                                "BARCODES: ${f.barcodes.joinToString("; ") { it.rawValue.take(80) }}"
                        )
                    }
                } catch (e: Exception) {
                    OpResult(false, "Read failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
    }

    // ---------- BLE ----------

    private fun registerBle() {
        reg(
            id = "ble.list_targets", title = "List Bluetooth devices found",
            riskLevel = "READ", verified = true,
            sourceMethod = "GhostTapViewModel.allTargets",
            effects = "Reads the list of Bluetooth devices already found. Nothing is sent or received.",
            detail = "Lists every Bluetooth device Ghost Tap has seen, with how strong the signal is.",
            category = "BLE",
            outputs = listOf("target list"),
            run = { _ ->
                try {
                    val ts = ble.allTargets
                    if (ts.isEmpty()) {
                        OpResult(true, "No devices yet — find devices first.", "")
                    } else {
                        OpResult(
                            true,
                            "${ts.size} device(s) known.",
                            ts.take(20).joinToString("\n") {
                                "${it.name} (${it.id.take(17)}) rssi=${it.rssi ?: "?"}"
                            }
                        )
                    }
                } catch (e: Exception) {
                    OpResult(false, "List failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "ble.start_scan", title = "Find Bluetooth devices",
            riskLevel = "READ", verified = true,
            sourceMethod = "GhostTapViewModel.startScan",
            effects = "Looks for Bluetooth devices for about 8 seconds and writes down what it finds. Does not connect to anything.",
            detail = "Scans for nearby Bluetooth devices for a few seconds.",
            category = "BLE",
            outputs = listOf("discovered targets"),
            preconditions = listOf("Bluetooth on", "location permission granted"),
            run = { _ ->
                try {
                    val before = ble.allTargets.size
                    ble.startScan()
                    delay(8000)
                    ble.stopScan()
                    val after = ble.allTargets
                    val fresh = after.drop(before)
                    OpResult(
                        true,
                        "Done looking: ${after.size} device(s) total (${fresh.size} new).",
                        fresh.take(10).joinToString("\n") {
                            "${it.name} (${it.id.take(17)}) rssi=${it.rssi ?: "?"}"
                        }
                    )
                } catch (e: Exception) {
                    OpResult(false, "Scan failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "ble.stop_scan", title = "Stop finding Bluetooth devices",
            riskLevel = "READ", verified = true,
            sourceMethod = "GhostTapViewModel.stopScan",
            effects = "Stops the Bluetooth scan. Nothing else changes.",
            detail = "Stops an in-progress Bluetooth scan.",
            category = "BLE",
            run = { _ ->
                try {
                    ble.stopScan()
                    OpResult(true, "Scan stopped.")
                } catch (e: Exception) {
                    OpResult(false, "Stop failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "ble.scan_status", title = "Read the current status",
            riskLevel = "READ", verified = true,
            sourceMethod = "GhostTapViewModel.scanStatus",
            effects = "Reads the current Bluetooth status line. Nothing changes.",
            detail = "Returns Ghost Tap's current Bluetooth status line.",
            category = "BLE",
            outputs = listOf("status text"),
            run = { _ ->
                try {
                    OpResult(true, ble.scanStatus)
                } catch (e: Exception) {
                    OpResult(false, "Status read failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "ble.connect", title = "Connect to a Bluetooth device",
            riskLevel = "CONFIGURE", verified = true,
            sourceMethod = "GhostTapViewModel.connectBle",
            effects = "Opens a live Bluetooth connection to the device. The device may notice the connection.",
            detail = "Opens a live Bluetooth link to the device. Needed before reading or sending data.",
            category = "BLE",
            inputs = listOf("targetId"),
            preconditions = listOf("target was seen in a scan"),
            run = { args ->
                try {
                    val id = args["targetId"].orEmpty()
                    if (id.isBlank()) {
                        OpResult(false, "No device was picked.")
                    } else {
                        ble.connectBle(id)
                        delay(6000)
                        OpResult(true, "Connect attempted: ${ble.gattStatusText(id)}")
                    }
                } catch (e: Exception) {
                    OpResult(false, "Connect failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "ble.disconnect", title = "Disconnect a Bluetooth device",
            riskLevel = "CONFIGURE", verified = true,
            sourceMethod = "GhostTapViewModel.disconnectBle",
            effects = "Closes the Bluetooth connection to the device.",
            detail = "Closes the Bluetooth link to the device.",
            category = "BLE",
            inputs = listOf("targetId"),
            run = { args ->
                try {
                    val id = args["targetId"].orEmpty()
                    if (id.isBlank()) {
                        OpResult(false, "No device was picked.")
                    } else {
                        ble.disconnectBle(id)
                        OpResult(true, "Disconnected: ${ble.gattStatusText(id)}")
                    }
                } catch (e: Exception) {
                    OpResult(false, "Disconnect failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
    }

    // ---------- NETWORK ----------

    private fun registerNetwork() {
        reg(
            id = "net.ping", title = "Check if a device answers",
            riskLevel = "READ", verified = true,
            sourceMethod = "NetworkToolsViewModel.ping",
            effects = "Sends a check to the device and reports the reply. The device sees the check.",
            detail = "Checks whether a host answers and how fast.",
            category = "NETWORK",
            inputs = listOf("host"),
            outputs = listOf("reply times or failure reason"),
            run = { args ->
                try {
                    val host = args["host"].orEmpty()
                    if (host.isBlank()) {
                        OpResult(false, "No host given.")
                    } else {
                        val before = net.log.size
                        net.ping(host)
                        val lines = captureNetLines(before)
                        OpResult(true, lines.lastOrNull() ?: "Ping finished with no output.", lines.joinToString("\n"))
                    }
                } catch (e: Exception) {
                    OpResult(false, "Ping failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "net.tcp_connect", title = "Connect and send a message",
            riskLevel = "READ", verified = true,
            sourceMethod = "NetworkToolsViewModel.tcpTest",
            effects = "Opens a connection, sends 'hello', reports the reply, then closes. The device sees a connection attempt.",
            detail = "Opens a connection, sends a message, and reports what came back.",
            category = "NETWORK",
            inputs = listOf("host", "port"),
            outputs = listOf("connection result"),
            run = { args ->
                try {
                    val host = args["host"].orEmpty()
                    val port = args["port"]?.toIntOrNull() ?: 0
                    if (host.isBlank() || port <= 0) {
                        OpResult(false, "Need a host and a valid port.")
                    } else {
                        val before = net.log.size
                        net.tcpTest(host, port, "hello")
                        val lines = captureNetLines(before)
                        OpResult(true, lines.lastOrNull() ?: "Connection test finished.", lines.joinToString("\n"))
                    }
                } catch (e: Exception) {
                    OpResult(false, "Connection test failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "net.udp_send", title = "Send a message and listen for a reply",
            riskLevel = "WRITE", verified = true,
            sourceMethod = "NetworkToolsViewModel.udpTest",
            effects = "Sends one short message ('hello') and listens briefly for a reply. The device sees the message.",
            detail = "Sends a short message and listens briefly for a reply.",
            category = "NETWORK",
            inputs = listOf("host", "port"),
            outputs = listOf("send/listen result"),
            run = { args ->
                try {
                    val host = args["host"].orEmpty()
                    val port = args["port"]?.toIntOrNull() ?: 0
                    if (host.isBlank() || port <= 0) {
                        OpResult(false, "Need a host and a valid port.")
                    } else {
                        val before = net.log.size
                        net.udpTest(host, port, "hello")
                        val lines = captureNetLines(before)
                        OpResult(true, lines.lastOrNull() ?: "Message test finished.", lines.joinToString("\n"))
                    }
                } catch (e: Exception) {
                    OpResult(false, "Message test failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "net.port_scan", title = "Check a device's connection points",
            riskLevel = "READ", verified = true,
            sourceMethod = "NetworkToolsViewModel.portScan",
            effects = "Checks the usual connection points on the device to see which answer. The device sees connection attempts.",
            detail = "Checks the usual connection points on a device to see which are open.",
            category = "NETWORK",
            inputs = listOf("host"),
            outputs = listOf("open ports"),
            run = { args ->
                try {
                    val host = args["host"].orEmpty()
                    if (host.isBlank()) {
                        OpResult(false, "No host given.")
                    } else {
                        val before = net.log.size
                        net.portScan(host)
                        val lines = captureNetLines(before, 20000)
                        OpResult(true, lines.lastOrNull() ?: "Port scan finished.", lines.joinToString("\n"))
                    }
                } catch (e: Exception) {
                    OpResult(false, "Port scan failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "net.http_fetch", title = "Fetch a web page",
            riskLevel = "READ", verified = true,
            sourceMethod = "NetworkToolsViewModel.httpGet",
            effects = "Downloads the page at the web address. The site sees a normal web request.",
            detail = "Downloads a page and reports whether it loaded and how big it is.",
            category = "NETWORK",
            inputs = listOf("url"),
            outputs = listOf("fetch result"),
            run = { args ->
                try {
                    val url = args["url"].orEmpty()
                    if (url.isBlank()) {
                        OpResult(false, "No URL given.")
                    } else {
                        val before = net.log.size
                        net.httpGet(url)
                        val lines = captureNetLines(before)
                        OpResult(true, lines.lastOrNull() ?: "Fetch finished.", lines.joinToString("\n"))
                    }
                } catch (e: Exception) {
                    OpResult(false, "Fetch failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
    }

    // ---------- USB ----------

    private fun registerUsb() {
        reg(
            id = "usb.refresh", title = "Look for USB cable devices",
            riskLevel = "READ", verified = true,
            sourceMethod = "UsbUartViewModel.refreshDevices",
            effects = "Asks the phone for USB cable devices plugged in and lists them. Nothing is opened.",
            detail = "Refreshes the list of USB cable adapters plugged into the phone.",
            category = "USB",
            outputs = listOf("device list / status"),
            preconditions = listOf("USB-C OTG adapter plugged in"),
            run = { _ ->
                try {
                    usb.refreshDevices()
                    delay(1500)
                    OpResult(true, usb.usbStatus)
                } catch (e: Exception) {
                    OpResult(false, "Refresh failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "usb.status", title = "Read the USB cable status",
            riskLevel = "READ", verified = true,
            sourceMethod = "UsbUartViewModel.usbStatus",
            effects = "Reads the USB cable state. Nothing changes.",
            detail = "Reports the USB cable state: devices found, picked, connected.",
            category = "USB",
            outputs = listOf("status text"),
            run = { _ ->
                try {
                    OpResult(
                        true,
                        "${usb.usbStatus} — connected=${usb.isConnected}, " +
                            "device=${usb.selectedDevice?.let { "attached" } ?: "none"}"
                    )
                } catch (e: Exception) {
                    OpResult(false, "Status read failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "usb.read_log", title = "Read USB message log",
            riskLevel = "READ", verified = true,
            sourceMethod = "UsbUartViewModel.copyLog",
            effects = "Reads the terminal log text. Nothing changes.",
            detail = "Shows the recent USB cable messages.",
            category = "USB",
            outputs = listOf("terminal text"),
            run = { _ ->
                try {
                    val tail = usb.copyLog().takeLast(1500)
                    OpResult(true, if (tail.isBlank()) "Terminal is empty." else "Last terminal traffic captured.", tail)
                } catch (e: Exception) {
                    OpResult(false, "Log read failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "usb.connect", title = "Connect the USB cable",
            riskLevel = "CONFIGURE", verified = true,
            sourceMethod = "UsbUartViewModel.connect",
            effects = "Opens the USB cable link to the device. The attached device sees the link open.",
            detail = "Opens the cable link to the picked USB device.",
            category = "USB",
            preconditions = listOf("USB device selected", "USB permission granted"),
            run = { _ ->
                try {
                    usb.connect()
                    delay(2000)
                    OpResult(true, usb.usbStatus)
                } catch (e: Exception) {
                    OpResult(false, "USB connect failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "usb.send", title = "Send text over the USB cable",
            riskLevel = "WRITE", verified = true,
            sourceMethod = "UsbUartViewModel.sendTx",
            effects = "Sends the given text down the cable to the attached device. The device receives it.",
            detail = "Sends a line of text down the USB cable.",
            category = "USB",
            inputs = listOf("text"),
            preconditions = listOf("USB serial connected"),
            run = { args ->
                try {
                    val text = args["text"].orEmpty()
                    if (text.isBlank()) {
                        OpResult(false, "Nothing to send — text was empty.")
                    } else {
                        usb.updateTxText(text)
                        usb.sendTx()
                        delay(1500)
                        OpResult(true, "Sent. Recent reply: ${usb.rxText.takeLast(300).ifBlank { "(nothing came back yet)" }}")
                    }
                } catch (e: Exception) {
                    OpResult(false, "Send failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "usb.test_esp32", title = "Test the board link",
            riskLevel = "CONTROL", verified = true,
            sourceMethod = "UsbUartViewModel.testEsp32",
            effects = "Sends a check signal down the cable. A connected board will answer; other devices may ignore it.",
            detail = "Sends a check signal and reports the board's answer.",
            category = "USB",
            preconditions = listOf("ESP32 wired to the USB serial adapter", "USB serial connected"),
            run = { _ ->
                try {
                    usb.testEsp32()
                    delay(4000)
                    OpResult(true, "ESP32 test: ${usb.esp32Status}")
                } catch (e: Exception) {
                    OpResult(false, "ESP32 test failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
    }

    // ---------- NFC ----------

    private fun registerNfc() {
        reg(
            id = "nfc.open_tools", title = "Open tap-card tools",
            riskLevel = "READ", verified = false,
            sourceMethod = "Manual: NFC TOOLS screen",
            effects = "Opens the tap-card tools — you tap a real card yourself. Ghost Tap cannot tap a card for you.",
            detail = "This needs a real card tap — Ghost Tap can't reach out and touch a card by itself.",
            category = "NFC",
            preconditions = listOf("NFC tag must be physically tapped to the phone", "open NFC TOOLS screen"),
            run = { _ ->
                OpResult(
                    true,
                    "Do it yourself: open the tap-card tools and tap a card — this needs a real tap.",
                    "manual"
                )
            }
        )
        reg(
            id = "nfc.read_summary", title = "Read what the last card said",
            riskLevel = "READ", verified = true,
            sourceMethod = "GhostTapViewModel.lastNfcReadSummary",
            effects = "Reads what the last tapped card said, from memory. Nothing changes.",
            detail = "Shows what the last tapped card reported.",
            category = "NFC",
            outputs = listOf("last tag summary"),
            run = { _ ->
                try {
                    OpResult(true, ble.lastNfcReadSummary)
                } catch (e: Exception) {
                    OpResult(false, "Read failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "nfc.emulation_status", title = "Check if the phone is pretending to be a card",
            riskLevel = "READ", verified = true,
            sourceMethod = "GhostTapViewModel.emulatedSummary",
            effects = "Reads whether the phone is pretending to be a card. Nothing changes.",
            detail = "Reports whether Ghost Tap is currently pretending to be a tap card.",
            category = "NFC",
            outputs = listOf("emulation state"),
            run = { _ ->
                try {
                    val state = if (ble.isEmulating) "ON" else "OFF"
                    OpResult(true, "Tag emulation is $state. ${ble.emulatedSummary()}")
                } catch (e: Exception) {
                    OpResult(false, "Read failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
    }

    // ---------- SYSTEM ----------

    private fun registerSystem() {
        reg(
            id = "intel.scan_candidates", title = "Check the code for things not switched on yet",
            riskLevel = "READ", verified = true,
            sourceMethod = "CapabilityScanner.scan",
            effects = "Looks through the app's own code and lists real things that aren't switched on yet. Nothing changes.",
            detail = "Shows which real Ghost Tap things exist in code but aren't switched on in the app's built-in brain yet — " +
                "so the app can see what's missing and suggest switching it on. Nothing is switched on by itself.",
            category = "SYSTEM",
            outputs = listOf("wiring candidates"),
            run = { _ ->
                try {
                    val report = CapabilityScanner.scan(viewModels(), CapabilityRegistry)
                    OpResult(
                        true,
                        "${report.candidates.size} unwired method(s) found " +
                            "(${report.registered.size} already wired).",
                        report.candidates.take(40).joinToString("\n").ifBlank { "(none — everything found is wired)" }
                    )
                } catch (e: Exception) {
                    OpResult(false, "Scan failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "sys.capabilities", title = "List everything the app can do",
            riskLevel = "READ", verified = true,
            sourceMethod = "CapabilityRegistry.all",
            effects = "Lists everything the app can do. Nothing changes.",
            detail = "Lists every job the app's built-in brain knows about.",
            category = "SYSTEM",
            outputs = listOf("capability list"),
            run = { _ ->
                try {
                    val all = CapabilityRegistry.all()
                    OpResult(
                        true,
                        "${all.size} capabilities registered.",
                        all.joinToString("\n") {
                            "${it.id} [${it.category} · ${it.riskLevel}]" +
                                "${if (it.verified) "" else " (unverified)"} — ${it.title}"
                        }
                    )
                } catch (e: Exception) {
                    OpResult(false, "List failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "sys.manual_step", title = "Do-it-yourself step",
            riskLevel = "READ", verified = true,
            sourceMethod = "IntelViewModel (manual instruction)",
            effects = "Shows you an instruction to do by hand. Ghost Tap performs nothing.",
            detail = "A step you do by hand — the app uses this for things it can't do alone.",
            category = "SYSTEM",
            inputs = listOf("instruction"),
            run = { args ->
                OpResult(true, args["instruction"] ?: "Do the manual step, then continue.", "manual")
            }
        )
        reg(
            id = "intel.propose_capability", title = "Build and test a new control",
            riskLevel = "READ", verified = true,
            sourceMethod = "DynamicCapabilityRuntime",
            effects = "Validates a drafted control definition, TESTS it by running its steps " +
                "through the real engine (look-only steps only — anything that changes state " +
                "is skipped and noted), and registers it only if the test passes. " +
                "Nothing untested is ever registered.",
            detail = "How Ghost Tap grows new things it can do: the built-in brain drafts a " +
                "control definition for a gap, and this control proves it — " +
                "validate, run the steps for real, check the assertions — before the new " +
                "control becomes visible to future case files. A failed test is an " +
                "honest failure, recorded on the timeline.",
            category = "SYSTEM",
            inputs = listOf("definitionJson"),
            outputs = listOf("test report", "registered control id"),
            run = { args ->
                try {
                    val json = args["definitionJson"].orEmpty()
                    val def = DynamicCapabilityDef.fromJson(json)
                        ?: return@reg OpResult(
                            false,
                            "That isn't a valid control definition — nothing was built."
                        )
                    val errors = DynamicCapabilityRuntime.validate(def)
                    if (errors.isNotEmpty()) {
                        return@reg OpResult(
                            false,
                            "Invalid definition, nothing built: " + errors.joinToString("; ")
                        )
                    }
                    val report = DynamicCapabilityRuntime.test(def, this@IntelEngine)
                    val registered =
                        DynamicCapabilityRuntime.register(def, this@IntelEngine, report, db)
                    if (registered) {
                        // Persist the full definition for restore-on-startup.
                        val dbRef = db
                        if (dbRef != null) {
                            try {
                                dbRef.observationDao().insert(
                                    Observation(
                                        id = UUID.randomUUID().toString(),
                                        investigationId = "",
                                        ts = System.currentTimeMillis(),
                                        kind = "dynamic_capability",
                                        title = "Capability saved: ${def.id}",
                                        detail = DynamicCapabilityDef.toJson(def).take(8000),
                                        provenance = "OBSERVED",
                                        confidence = 1.0
                                    )
                                )
                            } catch (_: Exception) {
                            }
                        }
                        OpResult(
                            true,
                            "Built and tested '${def.title}' (${def.id}) — " +
                                "${report.stepResults.size}/${def.steps.size} steps ran and passed. " +
                                "It's now in the registry as a ${DynamicCapabilityRuntime.escalatedRisk(def)}-risk capability.",
                            report.notes.joinToString("\n").take(1500)
                        )
                    } else {
                        OpResult(
                            false,
                            "Test failed for '${def.id}' — nothing was registered: " +
                                report.notes.joinToString("; ").take(500),
                            report.notes.joinToString("\n").take(1500)
                        )
                    }
                } catch (e: Exception) {
                    OpResult(false, "Propose failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
        reg(
            id = "intel.propose_script",
            title = "Write and test a scripted control",
            detail = "How Ghost Tap grows genuinely new tricks: a JavaScript draft runs in a " +
                "locked-down sandbox (no Java access, instruction budget) where it can " +
                "only call existing Ghost Tap tools through ghost.run. Validate, execute " +
                "the script for real, check its contract — only then is it registered. " +
                "This is the script way of adding something new. The other way is " +
                "building it from steps.",
            category = "SYSTEM",
            // Registering new executable logic is itself a WRITE-risk change.
            // Full autonomous mode: it auto-runs with no confirmation gate;
            // the WRITE risk is recorded and the action is logged.
            riskLevel = "WRITE",
            verified = true,
            sourceMethod = "ScriptRuntime",
            effects = "Checks the script is written right and keeps its promises, TESTS it by running it " +
                "it in a safe box (only look-only tools during the test — anything " +
                "that changes something is refused and noted), and saves it only if " +
                "the test passes. Nothing untested is ever saved.",
            inputs = listOf("title", "jsSource", "description", "risk"),
            outputs = listOf("test report", "registered control id"),
            run = { args ->
                try {
                    val title = args["title"].orEmpty().take(80).ifBlank { "Scripted probe" }
                    val js = args["jsSource"].orEmpty()
                    val risk = args["risk"].orEmpty().uppercase().ifBlank { "WRITE" }
                    if (js.isBlank()) {
                        return@reg OpResult(false, "No script source given — nothing was built.")
                    }
                    val def = ScriptDef(
                        id = "scr${System.currentTimeMillis() % 100000}",
                        title = title,
                        description = args["description"].orEmpty().take(500),
                        jsSource = js,
                        risk = risk,
                        origin = "ghost-intel/propose_script"
                    )
                    val errors = ScriptRuntime.validate(def.jsSource)
                    if (errors.isNotEmpty()) {
                        return@reg OpResult(
                            false,
                            "Invalid script, nothing built: " + errors.joinToString("; ")
                        )
                    }
                    val report = ScriptRuntime.test(def, this@IntelEngine, db)
                    val registered = ScriptRuntime.register(def, this@IntelEngine, db, report)
                    if (registered) {
                        OpResult(
                            true,
                            "Scripted '${def.title}' (script.${def.id}) tested and " +
                                "registered as a ${ScriptRuntime.effectiveRisk(def)}-risk capability.",
                            report.notes.joinToString("\n").take(1500)
                        )
                    } else {
                        OpResult(
                            false,
                            "Script test failed — nothing was registered: " +
                                report.notes.joinToString("; ").take(500),
                            report.notes.joinToString("\n").take(1500)
                        )
                    }
                } catch (e: Exception) {
                    OpResult(false, "Propose script failed: ${e.message ?: "unknown problem"}")
                }
            }
        )
    }
}
