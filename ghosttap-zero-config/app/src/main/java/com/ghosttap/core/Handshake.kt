package com.ghosttap.core

import com.ghosttap.data.DiscoveredService
import com.ghosttap.data.HistoryEntry

/**
 * Handshake step for zero-tap bring-up (C-11).
 *
 * After connection and after the existing honesty gates, every bring-up
 * path sends a real command the device is expected to answer — one that
 * proves the device understands a command — and requires an actual
 * answer before READY / CONTROLLABLE is declared.
 *
 * The probes are safe for an unknown device by design:
 * - BLE: a characteristic READ — it asks for a value and changes nothing.
 * - NFC: the summary read — the tap itself was the card answering real
 *   NFC commands, and the read must return real tag content.
 * - NETWORK: the ping — an echo request the host must answer. No payload
 *   is ever sent to an unknown service (the "hello" send is deliberately
 *   not used as a probe).
 * - USB: listen first (sends nothing), then the ESP32 sync — the app's
 *   own board test, which only an ESP32 in download mode answers.
 *
 * Everything in this file is pure Kotlin: no Android framework, no
 * ViewModels, no coroutines. The async wait/poll lives in
 * [BringUpPipeline]; the decisions live here so they can be unit-tested
 * on the JVM.
 */

/** What a handshake probe established. */
sealed class HandshakeOutcome {
    /** The device answered a real command. [evidence] says what answered, in plain words. */
    data class Answered(val evidence: String) : HandshakeOutcome()
    /** The device was asked and did not answer in time. */
    data class NoAnswer(val reason: String) : HandshakeOutcome()
    /** No safe probe exists for this device — honesty gates apply, said so plainly. */
    data class NoSafeProbe(val reason: String) : HandshakeOutcome()
    /** The device answered by demanding something setup cannot do (e.g. pairing). */
    data class Blocked(val reason: String) : HandshakeOutcome()
}

/**
 * First up-to-[max] readable characteristics, in discovery order.
 * Each pair is (serviceUuid, charUuid). Empty when nothing on the device
 * can be read — then no safe BLE probe exists for this device.
 */
fun firstReadableCharacteristics(
    services: List<DiscoveredService>,
    max: Int = 3
): List<Pair<String, String>> {
    if (max <= 0) return emptyList()
    val out = ArrayList<Pair<String, String>>()
    for (svc in services) {
        for (ch in svc.characteristics) {
            if ("READ" in ch.properties) {
                out.add(svc.uuid to ch.uuid)
                if (out.size >= max) return out
            }
        }
    }
    return out
}

/**
 * True when any READ entry for one of [charUuids] arrived at or after
 * [sinceMs] — i.e. the device answered a read issued after the handshake
 * started. A stale value from an earlier read never counts.
 */
fun readAnsweredSince(
    records: List<HistoryEntry>,
    charUuids: Set<String>,
    sinceMs: Long
): Boolean = records.any { it.kind == "READ" && it.charUuid in charUuids && it.timestampMs >= sinceMs }

/**
 * Ping success evidence: "answered in" appears only in a real ping
 * reply — "didn't answer" and "couldn't find" never contain it — so
 * this checks for the presence of a real answer, never for the absence
 * of an error.
 */
fun pingAnswered(summary: String): Boolean = "answered in" in summary

/**
 * USB link evidence: usb.status reports the live state as
 * "connected=true/false", so this re-checks the real link instead of
 * trusting the connect call's own words.
 */
fun usbConnected(summary: String): Boolean = "connected=true" in summary

/**
 * ESP32 board-test evidence: "answered" appears only in the success
 * line ("The board answered — ready to load code"). The failure line
 * ("The board didn't answer — …") does not contain it, and neither do
 * "Checking…", "Not checked yet", or any of the not-connected lines.
 */
fun esp32Answered(status: String): Boolean = "answered" in status

/**
 * NFC summary is usable when it carries real tag content. The stored
 * summary starts as "No tag tapped yet." and the read op returns ok
 * unconditionally, so content — not ok — is the gate.
 */
fun nfcSummaryUsable(summary: String): Boolean =
    summary.isNotBlank() && !summary.startsWith("No tag tapped yet")
