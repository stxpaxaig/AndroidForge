package com.ghosttap.core.vnic

/**
 * Part 2 — the AI connection check (diagnostic).
 *
 * Lives on the Investigations screen, next to the Thinking Engine box
 * where the key is saved. It tests the ACTUAL VNIC reasoning path — the
 * real VNIC [RemoteLlmReasoner] reached through the live engine — never
 * a second or demo client, never a separate API test.
 *
 * Seven stages, each PASS or FAIL with the actual reason on failure:
 * 1. KEY_PRESENT — a key is saved on this phone.
 * 2. CONFIG_RECEIVED — the VNIC picked the saved endpoint + credential up.
 * 3. REASONER_SELECTED — the online helper is the live reasoner.
 * 4. REQUEST_SENT — the probe request actually went out.
 * 5. HTTP_SUCCESS — a 2xx answer came back.
 * 6. RESPONSE_PARSED — the model answer was read and holds the exact
 *    check words.
 * 7. AI_REASONING_CONFIRMED — the returned words flow into a real
 *    reasoning call through the live reasoner delegate (the same object
 *    the VNIC core calls), and the stage passes only when the reasoning
 *    output mentions those words. A local-fallback answer never mentions
 *    them, so it can never pass as remote success. No cycle, no research,
 *    no plan execution and no bring-up run here: tapping the check never
 *    scans, connects, or acts on a device.
 *
 * A stage NEVER passes merely because the key exists, settings were
 * saved, the app initialized, a local rule answered, or a separate API
 * test succeeded. The check stops at the first failed stage; later
 * stages are reported as not run.
 *
 * KEY SAFETY: the key is never displayed, never logged, never put in a
 * diagnostic string or a failure reason — only whether a credential is
 * present and whether authentication succeeded. The diagnostic only ever
 * sees [VnicRemoteSummary] (endpoint + model + key-presence), never the
 * key value.
 */

/** The exact words the probe asks the model to reply with, and the exact words a good answer holds. */
const val VNIC_DIAG_EXPECTED_REPLY = "GHOST_TAP_AI_CONNECTED"

/**
 * The exact probe request: the configured model (gpt-4o-mini is the
 * default — [VnicLlmConfig] carries the model and a configured one is
 * preserved), one user message asking for the exact check words,
 * temperature 0. For the default model this is exactly:
 * {"model": "gpt-4o-mini", "messages": [{"role": "user", "content":
 * "Reply with exactly: GHOST_TAP_AI_CONNECTED"}], "temperature": 0}
 */
fun vnicDiagProbePayload(model: String): String {
    val safeModel = model.ifBlank { "gpt-4o-mini" }
        .replace("\\", "\\\\").replace("\"", "\\\"")
    return "{\"model\": \"$safeModel\", \"messages\": [{\"role\": \"user\", \"content\": \"Reply with exactly: $VNIC_DIAG_EXPECTED_REPLY\"}], \"temperature\": 0}"
}

/** One stage of the check, in run order, with its plain-words label for the panel. */
enum class VnicDiagStage(val plainLabel: String) {
    KEY_PRESENT("Key saved"),
    CONFIG_RECEIVED("Brain picked up the settings"),
    REASONER_SELECTED("Online helper picked"),
    REQUEST_SENT("Check message sent"),
    HTTP_SUCCESS("Service answered"),
    RESPONSE_PARSED("Answer read"),
    AI_REASONING_CONFIRMED("Brain used the answer")
}

/** One stage's outcome. `detail` is plain words; it never contains a key. */
data class VnicDiagStageResult(
    val stage: VnicDiagStage,
    val passed: Boolean,
    val detail: String
)

/** The full check report. Stages after the first failure are absent (not run). */
data class VnicDiagReport(
    val stages: List<VnicDiagStageResult>,
    val durationMs: Long
) {
    /** True only when every stage ran and passed. */
    val passed: Boolean get() = stages.size == VnicDiagStage.values().size && stages.all { it.passed }

    /** One plain-words line for the panel header. */
    val overallLine: String
        get() = if (passed) {
            "AI connection check passed — the online helper answered and the brain used its reply."
        } else {
            val failed = stages.firstOrNull { !it.passed }
            "AI connection check stopped at ${failed?.stage?.plainLabel ?: "the start"} — ${failed?.detail ?: "try again."}"
        }
}

/**
 * What the diagnostic is allowed to know about the live remote
 * reasoner: the endpoint it calls, the model it asks for, and whether a
 * key is present. Never the key value itself.
 */
data class VnicRemoteSummary(
    val endpoint: String,
    val model: String,
    val configured: Boolean
)

/**
 * Outcome of one probe request through the live remote reasoner,
 * staged so the check can report exactly where it stopped. `parsedContent`
 * holds the model's answer text (null when the body could not be read as
 * a chat completion); `malformed` marks a 2xx body that was not a usable
 * chat-completions reply, as opposed to a valid-but-empty answer.
 * `failureReason` is plain words for the first failed stage, null when
 * the probe itself succeeded. Never contains a key.
 */
data class VnicProbeOutcome(
    val requestSent: Boolean,
    val httpCode: Int?,
    val httpSuccess: Boolean,
    val parsedContent: String?,
    val malformed: Boolean,
    val failureReason: String?
)

/**
 * The seam the check needs from the engine — implemented by [VnicEngine];
 * fakes in tests. Every call here goes through the engine's live reasoner
 * delegate, the same object the VNIC core calls.
 */
interface VnicAiDiagnosticPort {
    /** Saved Thinking Engine settings as the VNIC sees them. */
    fun savedLlmConfig(): VnicLlmConfig

    /** Re-read the saved settings into the live reasoner; returns the active reasoner name. */
    fun refreshReasonerFromSettings(): String

    /** The live reasoner delegate the core is currently using. */
    fun currentReasoner(): VnicReasoningProvider

    /** Endpoint + model + key-presence of the live remote reasoner, or null when the built-in helper is live. */
    fun remoteSummary(): VnicRemoteSummary?

    /** Plain-words reason the last online-helper call failed, or null when it worked / the built-in helper is in use. */
    fun reasonerFailureReason(): String?

    /** True when the last hypotheses/plan call fell back to the built-in helper. */
    fun reasonerFellBackToLocal(): Boolean

    /** One probe request through the live remote reasoner, or null when the built-in helper is live. */
    fun probeConnection(): VnicProbeOutcome?

    /** One hypotheses call through the live reasoner delegate (same object the core calls). No cycle, no execution. */
    fun reasonerHypotheses(observation: String, knownFacts: List<String> = emptyList()): List<String>
}

/** Runs the seven-stage AI connection check against a [VnicAiDiagnosticPort]. */
class VnicAiDiagnostic(private val port: VnicAiDiagnosticPort) {

    fun run(): VnicDiagReport {
        val started = System.currentTimeMillis()
        val stages = mutableListOf<VnicDiagStageResult>()
        fun record(stage: VnicDiagStage, passed: Boolean, detail: String) {
            stages += VnicDiagStageResult(stage, passed, detail)
        }
        fun report() = VnicDiagReport(stages.toList(), System.currentTimeMillis() - started)

        // 1 — KEY_PRESENT: a key is saved on this phone.
        val saved = runCatching { port.savedLlmConfig() }.getOrElse { VnicLlmConfig() }
        if (saved.apiKey.isNullOrBlank()) {
            record(
                VnicDiagStage.KEY_PRESENT, false,
                "No key saved — paste your key in the Thinking Engine box and tap SAVE."
            )
            return report()
        }
        record(VnicDiagStage.KEY_PRESENT, true, "A key is saved on this phone.")

        // 2 — CONFIG_RECEIVED: the VNIC picked the saved endpoint + credential up.
        val remote = runCatching { port.refreshReasonerFromSettings(); port.remoteSummary() }.getOrNull()
        if (remote == null || !remote.configured || remote.endpoint != saved.effectiveEndpoint) {
            record(
                VnicDiagStage.CONFIG_RECEIVED, false,
                "The brain didn't pick up the saved key and web address — save them again in the Thinking Engine box."
            )
            return report()
        }
        record(VnicDiagStage.CONFIG_RECEIVED, true, "The brain picked up the saved key and web address.")

        // 3 — REASONER_SELECTED: the online helper is the live reasoner.
        val current = runCatching { port.currentReasoner() }.getOrNull()
        if (current !is RemoteLlmReasoner) {
            record(
                VnicDiagStage.REASONER_SELECTED, false,
                "The built-in helper is thinking instead — the online helper wasn't picked."
            )
            return report()
        }
        record(VnicDiagStage.REASONER_SELECTED, true, "The online helper is the one thinking — not the built-in helper.")

        // 4–6 — one real probe request through the live remote reasoner.
        val probe = runCatching { port.probeConnection() }.getOrNull()
        if (probe == null) {
            record(
                VnicDiagStage.REQUEST_SENT, false,
                "The check couldn't reach the online helper at all — try again."
            )
            return report()
        }
        if (!probe.requestSent) {
            record(
                VnicDiagStage.REQUEST_SENT, false,
                probe.failureReason ?: "The check message never went out — check the connection and try again."
            )
            return report()
        }
        record(VnicDiagStage.REQUEST_SENT, true, "The check message went out to the AI service.")
        if (!probe.httpSuccess) {
            record(
                VnicDiagStage.HTTP_SUCCESS, false,
                probe.failureReason ?: "The AI service didn't answer cleanly — try again in a bit."
            )
            return report()
        }
        record(VnicDiagStage.HTTP_SUCCESS, true, "The AI service answered.")
        val content = probe.parsedContent?.trim()
        if (probe.malformed) {
            record(
                VnicDiagStage.RESPONSE_PARSED, false,
                probe.failureReason ?: "The AI's answer came back in a shape Ghost Tap couldn't read."
            )
            return report()
        }
        if (content.isNullOrBlank()) {
            record(
                VnicDiagStage.RESPONSE_PARSED, false,
                "The AI answered but said nothing."
            )
            return report()
        }
        if (content != VNIC_DIAG_EXPECTED_REPLY) {
            record(
                VnicDiagStage.RESPONSE_PARSED, false,
                "The AI answered but didn't say the expected check words."
            )
            return report()
        }
        record(VnicDiagStage.RESPONSE_PARSED, true, "The AI answered with the exact check words.")

        // 7 — AI_REASONING_CONFIRMED: the returned words flow into a real
        // reasoning call through the live reasoner delegate, and the stage
        // passes only when the reasoning output mentions those words.
        val observation =
            "The online helper just answered the connection check. Its exact reply was: $VNIC_DIAG_EXPECTED_REPLY. What does that reply tell us?"
        val hypotheses = runCatching { port.reasonerHypotheses(observation) }.getOrElse { emptyList() }
        val fellBack = runCatching { port.reasonerFellBackToLocal() }.getOrElse(false)
        val failure = runCatching { port.reasonerFailureReason() }.getOrNull()
        val localAnswered = hypotheses.any { it.startsWith("The online brain didn't answer") }
        val mentionsCheckWords = hypotheses.any { it.contains(VNIC_DIAG_EXPECTED_REPLY) }
        when {
            localAnswered || fellBack -> record(
                VnicDiagStage.AI_REASONING_CONFIRMED, false,
                "The built-in helper answered instead of the online helper" +
                    (failure?.let { " — $it" } ?: ".")
            )
            !mentionsCheckWords -> record(
                VnicDiagStage.AI_REASONING_CONFIRMED, false,
                "The AI answered the check, but the brain's reply didn't mention the check words — run the check again."
            )
            else -> record(
                VnicDiagStage.AI_REASONING_CONFIRMED, true,
                "The brain's own reply mentioned the check words — the AI answer went through real thinking."
            )
        }
        return report()
    }
}
