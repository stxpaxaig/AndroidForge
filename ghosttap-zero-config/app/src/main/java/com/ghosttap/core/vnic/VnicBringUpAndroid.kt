package com.ghosttap.core.vnic

import com.ghosttap.core.BringUpKind
import com.ghosttap.core.BringUpPipeline
import com.ghosttap.core.BringUpTarget
import com.ghosttap.core.IntelEngine

/**
 * Android bring-up port — the VNIC core's bridge to the REAL
 * [BringUpPipeline.bringUp] (the existing zero-tap bring-up: connect ->
 * enumerate -> handshake -> map-controls -> READY, with the final
 * still-connected honesty check). The VNIC drives THIS in production; it
 * never invents a parallel bring-up.
 *
 * ESP32 targets go to the [VnicEsp32Bridge] instead (its guest lifecycle
 * is not a BringUpPipeline kind); every other kind maps 1:1 onto the
 * pipeline's own kinds. `connectionHeld = ready`: the pipeline already
 * fails the bring-up when the link drops before setup finishes, so a
 * ready result means the link is still open right now. The pipeline's
 * handshake step (C-11) applies to every kind routed here: BLE reads a
 * characteristic, NFC requires a real summary read, network requires a
 * ping answer, USB listens then runs the board test.
 */
class AndroidVnicBringUp(
    private val engine: IntelEngine,
    private val esp32: VnicEsp32Bridge? = null
) : VnicBringUpPort {

    override suspend fun bringUp(target: VnicBringUpTarget, note: String): VnicBringUpResult {
        if (target.kind == VnicBringUpKind.ESP32) {
            val bridge = esp32
                ?: return VnicBringUpResult(
                    ready = false, route = "", controlLines = emptyList(),
                    log = listOf("No VirtualESP32 bridge attached — ESP32 bring-up unavailable."),
                    connectionHeld = false,
                    failReason = "No VirtualESP32 bridge attached — ESP32 bring-up needs the VirtualESP32 app connected."
                )
            return bridge.bringUpEsp32()
        }
        val kind = when (target.kind) {
            VnicBringUpKind.BLE -> BringUpKind.BLE
            VnicBringUpKind.NFC -> BringUpKind.NFC
            VnicBringUpKind.NETWORK -> BringUpKind.NETWORK
            VnicBringUpKind.USB -> BringUpKind.USB
            VnicBringUpKind.ESP32 -> error("unreachable — ESP32 handled above")
        }
        val result = BringUpPipeline.bringUp(
            BringUpTarget(kind, target.id, target.title, target.detail),
            engine
        )
        return VnicBringUpResult(
            ready = result.ready,
            route = result.descriptor?.route ?: "",
            controlLines = result.descriptor?.summaryLines ?: emptyList(),
            log = result.log,
            connectionHeld = result.ready,
            failReason = result.failReason
        )
    }
}
