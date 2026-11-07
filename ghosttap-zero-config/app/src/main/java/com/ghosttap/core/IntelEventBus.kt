package com.ghosttap.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central event bus for the intelligence system: real things that happened
 * in the app, published once, consumed by [com.ghosttap.IntelViewModel].
 *
 * Every event on this bus is something the app ACTUALLY observed — a target
 * the user selected, a tag the phone really read, a host that answered a
 * ping. Nothing here is synthesized or guessed.
 *
 * Publishers: the three tool ViewModels, via minimal additive hooks.
 * Consumers: IntelViewModel subscribes and auto-creates investigations for
 * high-value events (unknown/low-confidence target, new NFC tag, new host).
 * The loop's own actions NEVER publish here (guard flag in the consumer),
 * and the same target id within 60 seconds never double-triggers.
 */
sealed class IntelEvent {
    /** The user picked a Bluetooth target in the scan list. */
    data class BleTargetSelected(
        val deviceId: String,
        val name: String,
        val rssi: Int?,
        /** Advertisement payload summary, if any. */
        val advertised: String,
        /** Classification label ("…", null = not yet classified). */
        val likely: String?,
        /** "High" / "Medium" / "Low" / null. */
        val confidence: String?
    ) : IntelEvent()

    /** A BLE GATT connection actually completed. */
    data class BleConnected(
        val deviceId: String,
        val name: String
    ) : IntelEvent()

    /** The phone really read an NFC tag (handleNfcTag completed). */
    data class NfcTagRead(
        val summary: String,
        val techList: List<String>
    ) : IntelEvent()

    /** SCAN MY NETWORK found a live host. */
    data class NetworkHostFound(
        val ip: String,
        val mac: String,
        val hostname: String
    ) : IntelEvent()

    /** A tool operation finished (from any engine-run capability). */
    data class ToolOpResult(
        val capabilityId: String,
        val ok: Boolean,
        val summary: String
    ) : IntelEvent()

    /** Something in the intelligence system went wrong. */
    data class IntelError(
        val source: String,
        val message: String
    ) : IntelEvent()

    /**
     * Visual Intel finished analyzing an image (camera, gallery, or upload).
     * Carries the extracted text, barcodes, and markings — the investigation
     * starts from these, never from the raw pixels.
     */
    data class VisualCapture(
        val source: String,
        val text: String,
        val barcodes: String,
        val markings: String
    ) : IntelEvent()

    /** A USB serial link opened or closed. */
    data class UsbState(
        val connected: Boolean,
        val detail: String
    ) : IntelEvent()
}

object IntelEventBus {
    private val _events = MutableSharedFlow<IntelEvent>(
        // Small buffer: a burst of scan events must never suspend a publisher.
        extraBufferCapacity = 64
    )
    val events: SharedFlow<IntelEvent> = _events.asSharedFlow()

    /**
     * Publish from any thread. Never suspends, never throws — a failed
     * publish is dropped rather than crashing the tool that observed it.
     */
    fun publish(event: IntelEvent) {
        try {
            _events.tryEmit(event)
        } catch (_: Exception) {
        }
    }
}
