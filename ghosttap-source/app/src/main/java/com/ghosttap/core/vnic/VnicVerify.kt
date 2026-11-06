package com.ghosttap.core.vnic

import com.ghosttap.core.OpResult

/**
 * Content verification for capability execution — pure Kotlin.
 *
 * This is the verification logic formerly embedded in [VnicExecution],
 * extracted verbatim so the JVM demo can run the SAME production
 * verification code against registry dispatch (the demo's executor is
 * `VnicVerify.verify` over real [com.ghosttap.core.CapabilityRegistry]
 * runs). [VnicExecution] is now a thin wrapper: engine.run, then this.
 *
 * Verification is by RESPONSE CONTENT, not by dispatch:
 * - The capability returned ok=false, or threw -> FAILED.
 * - The capability returned ok=true but with an empty/uninformative
 *   response, or with a known "did nothing" marker -> ATTEMPTED (sent,
 *   no usable response — explicitly NOT proof of anything).
 * - The capability returned ok=true with a substantive response that
 *   mentions a live link, a real readout, or the expected outcome ->
 *   CONNECTED (a live response was observed).
 * - CONNECTED plus the response content matches the expected outcome for
 *   the requested capability -> VERIFIED.
 *
 * Known honesty markers (from the Phase 1 audit): several capabilities
 * return ok=true unconditionally (ble.connect, all net.* ops,
 * usb.test_esp32, nfc.open_tools, sys.manual_step — the last two do
 * nothing). An ok=true from one of those, with no substantive data, can
 * never reach VERIFIED. The [UNCONDITIONAL_OK] set names them; the check
 * is content-based, so the set is a floor, not a whitelist.
 */
object VnicVerify {

    /** Capabilities audited as returning ok=true unconditionally — their ok alone proves nothing. */
    val UNCONDITIONAL_OK = setOf(
        "ble.connect", "net.ping", "net.port_scan", "net.http_fetch",
        "net.tcp_connect", "net.udp_send", "usb.test_esp32",
        "nfc.open_tools", "sys.manual_step"
    )

    /** Phrases that mean "nothing actually happened" even when ok=true. */
    val NOOP_MARKERS = listOf(
        "manual step", "open the", "not implemented", "no-op", "placeholder",
        "not yet", "skipped", "nothing to do"
    )

    /** Verify one already-run capability result. Never throws. */
    fun verify(capabilityId: String, result: OpResult): VnicExecutionOutcome {
        if (!result.ok) {
            return VnicExecutionOutcome(
                VnicExecutionState.FAILED,
                "Capability '$capabilityId' failed: ${result.summary}",
                result.data
            )
        }
        val content = (result.summary + " " + result.data).trim()
        val lower = content.lowercase()
        if (content.isBlank() || NOOP_MARKERS.any { it in lower }) {
            return VnicExecutionOutcome(
                VnicExecutionState.ATTEMPTED,
                "Capability '$capabilityId' returned ok but no usable response (sent, not answered).",
                result.data
            )
        }
        // A substantive response was observed: a live link/readout exists.
        // VERIFIED needs the response to match the expected outcome for
        // this capability — see expectedEvidenceFor. The match is for the
        // PRESENCE of success-only evidence in the response, never for
        // the absence of an error sentence: an error sentence that
        // happens to contain the old generic word ("device", "reply",
        // "tag", "esp32") used to verify by accident.
        val expected = expectedEvidenceFor(capabilityId)
        return if (expected != null &&
            expected.anyOf.any { it in lower } &&
            expected.noneOf.none { it in lower }
        ) {
            VnicExecutionOutcome(
                VnicExecutionState.VERIFIED,
                "Capability '$capabilityId' verified: response contains expected evidence ('${expected.anyOf.joinToString("' or '")}').",
                result.data
            )
        } else if (capabilityId in UNCONDITIONAL_OK) {
            // Unconditional-ok capability with a response but no expected
            // evidence: it answered, but the answer proves nothing specific.
            VnicExecutionOutcome(
                VnicExecutionState.CONNECTED,
                "Capability '$capabilityId' answered with a response, but the response does not match a verifiable outcome.",
                result.data
            )
        } else {
            VnicExecutionOutcome(
                VnicExecutionState.CONNECTED,
                "Capability '$capabilityId' answered: $content",
                result.data
            )
        }
    }

    /**
     * The response evidence that would prove this capability actually
     * did its job.
     *
     * @param anyOf at least one phrase must appear in the response
     *   (lowercased before matching).
     * @param noneOf no phrase may appear — for capabilities whose
     *   success line and empty-result line share words (usb.send
     *   reports a reply, or "(nothing came back yet)").
     */
    data class ExpectedEvidence(
        val anyOf: List<String>,
        val noneOf: List<String> = emptyList()
    )

    /**
     * The response text that would prove this capability actually did its
     * job. Null means "any substantive response counts as CONNECTED, and
     * VERIFIED is decided by the caller" — used for capabilities whose
     * expected output varies by target, and for reads whose only honest
     * outcome is "the app answered" (ble.scan_status, sys.manual_step).
     *
     * Every phrase below was checked first-hand against the
     * capability's own success, failure, and empty-result lines in
     * [com.ghosttap.core.IntelEngine]: it appears ONLY in a genuine
     * success response. That is what the old table got wrong — one
     * generic word ("device", "reply", "tag", "text", "http", "esp32")
     * also appears in "No devices yet", "No tag tapped yet", and
     * "The board didn't answer", which used to verify by accident.
     * Genuinely earned VERIFIED verdicts are kept: a scan that lists
     * devices, a ping that got an answer, a fetch that got a page, a
     * board test the board answered. False ones are gone: an empty
     * device list, an unanswered ping, an unanswered board test, and
     * "No tag tapped yet" no longer verify.
     */
    fun expectedEvidenceFor(capabilityId: String): ExpectedEvidence? = when (capabilityId) {
        // VISUAL: the finding/copy actually happened.
        "visual.capture_analyze" -> ExpectedEvidence(listOf("photo analyzed"))
        "visual.copy_text" -> ExpectedEvidence(listOf("characters to clipboard"))
        "visual.last_finding" -> ExpectedEvidence(listOf("last finding"))
        // BLE: devices were actually listed / the scan ran to completion /
        // the link is up. "Not connected" never contains "connected —".
        "ble.list_targets" -> ExpectedEvidence(listOf("rssi="))
        "ble.start_scan" -> ExpectedEvidence(listOf("done looking"))
        "ble.stop_scan" -> ExpectedEvidence(listOf("scan stopped"))
        "ble.connect" -> ExpectedEvidence(listOf("connected —"))
        "ble.disconnect" -> ExpectedEvidence(listOf("disconnected:"))
        // NETWORK: the host actually answered / connected / had an open
        // port / gave an HTTP answer. "didn't answer", "couldn't find"
        // and "Open: none" never contain these phrases.
        "net.ping" -> ExpectedEvidence(listOf("answered in"))
        "net.tcp_connect" -> ExpectedEvidence(listOf("connected to"))
        "net.udp_send" -> ExpectedEvidence(listOf("bytes to"))
        "net.port_scan" -> ExpectedEvidence(listOf("is open"))
        "net.http_fetch" -> ExpectedEvidence(listOf("answer:"))
        // USB: a device was actually found / the cable is actually up /
        // the terminal actually had traffic / the board actually answered.
        // "No USB device found", "Connection failed" and "The board
        // didn't answer" never contain these phrases.
        "usb.refresh" -> ExpectedEvidence(listOf("tap connect", "tap one to pick"))
        "usb.status" -> ExpectedEvidence(listOf("connected=true"))
        "usb.read_log" -> ExpectedEvidence(listOf("last terminal traffic captured"))
        "usb.connect" -> ExpectedEvidence(listOf("connected — ready to use"))
        "usb.send" -> ExpectedEvidence(
            anyOf = listOf("recent reply:"),
            noneOf = listOf("(nothing came back yet)")
        )
        "usb.test_esp32" -> ExpectedEvidence(listOf("the board answered"))
        // NFC: a real card summary was read / emulation is actually on.
        // "No tag tapped yet." never contains "tag: ".
        "nfc.read_summary" -> ExpectedEvidence(listOf("tag: "))
        "nfc.emulation_status" -> ExpectedEvidence(listOf("tag emulation is on"))
        // SYSTEM: the scan / list / build genuinely ran and reported.
        "intel.scan_candidates" -> ExpectedEvidence(listOf("unwired method(s) found"))
        "sys.capabilities" -> ExpectedEvidence(listOf("capabilities registered"))
        "intel.propose_capability" -> ExpectedEvidence(listOf("built and tested"))
        "intel.propose_script" -> ExpectedEvidence(listOf("tested and registered"))
        else -> null
    }
}
