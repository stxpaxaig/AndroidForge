package com.ghosttap.core.vnic

/**
 * Bring-up types for the closed VNIC loop — pure Kotlin, no Android imports.
 *
 * The owner's end-state definition: "connect" means bringing the target to a
 * CONTROLLED / CONTROLLABLE state and STAYING connected — Ghost Tap holds
 * the connection, the controls are mapped, and the owner drives the target
 * himself through Ghost Tap. VNIC does the research, the bring-up, the
 * handshake and the control mapping; it never takes autonomous control and
 * never connects-then-disconnects.
 *
 * A bring-up port is the seam between the pure core ([VnicCore]) and the
 * real transport: on Android it is [AndroidVnicBringUp] (real
 * BringUpPipeline) / [VnicEsp32Bridge] (real VirtualESP32 client); in the
 * JVM demo it is the TCP-emulator-backed port in VnicClosedLoopTest.
 */

/** Target kinds the VNIC can bring to a controllable state. */
enum class VnicBringUpKind { BLE, NFC, NETWORK, USB, ESP32 }

/** One target to bring up — built from the observation + research findings. */
data class VnicBringUpTarget(
    val kind: VnicBringUpKind,
    /** Subject id: BLE device id, NFC tag key, host IP, "usb", or ESP32 node id. */
    val id: String,
    val title: String,
    val detail: String = ""
)

/** The outcome of a bring-up attempt. `ready` is true ONLY when the target is controllable AND the connection is still held. */
data class VnicBringUpResult(
    val ready: Boolean,
    /** Nav route the control interface lives on ("" when not ready). */
    val route: String,
    /** Plain-English lines describing what's controllable (empty when not ready). */
    val controlLines: List<String>,
    val log: List<String>,
    /** True only when the link is still open RIGHT NOW, not just during setup. */
    val connectionHeld: Boolean,
    /** Plain-English blocking reason when not ready. */
    val failReason: String?
)

/** The port the core drives a bring-up through. Implemented per transport. */
interface VnicBringUpPort {
    suspend fun bringUp(target: VnicBringUpTarget, note: String = ""): VnicBringUpResult
}

/** A procedure the VNIC developed for a goal: steps + whether its test passed. Pure data — the engine/bridge decides how to test and persist it. */
data class VnicDevelopedProcedure(
    val id: String,
    val steps: List<String>,
    val testPassed: Boolean,
    val notes: List<String>
)

/**
 * Determine the bring-up kind from observation text + research findings.
 * Check order is most-specific first: an ESP32 mention implies Wi-Fi/BLE
 * words too ("ESP32 Bluetooth Low Energy GATT"), so ESP32 must win over
 * BLE/NETWORK or the target would be mis-routed to a generic bring-up.
 * Returns null when no bring-up path can be determined — the cycle then
 * falls back to sequential plan execution and says so in its phases.
 */
fun vnicDetectBringUpKind(text: String, findings: List<VnicFinding> = emptyList()): VnicBringUpKind? {
    val hay = (text + " " + findings.joinToString(" ") { "${it.title} ${it.snippet}" }).lowercase()
    return when {
        "esp32" in hay || "espressif" in hay -> VnicBringUpKind.ESP32
        "bluetooth" in hay || "ble" in hay || "gatt" in hay -> VnicBringUpKind.BLE
        "nfc" in hay || "tag" in hay -> VnicBringUpKind.NFC
        "usb" in hay || "serial" in hay || "uart" in hay || "ch340" in hay -> VnicBringUpKind.USB
        "wifi" in hay || "ssid" in hay || "network" in hay || "ip " in hay || "host" in hay -> VnicBringUpKind.NETWORK
        else -> null
    }
}
