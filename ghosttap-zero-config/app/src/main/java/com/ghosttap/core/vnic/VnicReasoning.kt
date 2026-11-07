package com.ghosttap.core.vnic

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reasoning — provider-independent by design.
 *
 * The VNIC core owns its task state, knowledge, history, capability map,
 * plans and records. A reasoning provider only ever returns text
 * (hypotheses, plan suggestions); it never owns memory or control, and the
 * core may ignore it. The default is the local rule reasoner — no network,
 * no key, no dependency. The remote adapter is optional and explicit.
 */
interface VnicReasoningProvider {
    val name: String
    fun hypotheses(observation: String, knownFacts: List<String>): List<String>
    fun planFor(goal: String, capabilityIds: List<String>): List<String>

    /**
     * Enriched planning (Defect 2 repair): the goal plus the full capability
     * entries (id AND title/description), the known facts, and the fresh
     * research findings. Known facts / findings are supporting context only —
     * they never gate planning and never override the current observation.
     * Default delegates to the ids-only call, so existing providers keep working.
     */
    fun planFor(
        goal: String,
        capabilities: List<VnicCapabilityInfo>,
        knownFacts: List<String>,
        findings: List<VnicFinding>
    ): List<String> = planFor(goal, capabilities.map { it.id })

    /**
     * Plain-words reason the last remote call failed, or null when the last
     * call worked, the provider is local, or no remote call has run. Never
     * contains a key, an endpoint, or a response body.
     */
    val lastFailureReason: String? get() = null

    /**
     * True when the last hypotheses or planFor call fell back to the
     * built-in helper after the online helper failed.
     */
    val lastPlanFellBackToLocal: Boolean get() = false

    /**
     * Result-ingest (Gap 1): after a capability runs, its outcome goes
     * back through the reasoner so the next useful step is chosen from
     * what actually happened, not from the plan alone — the same
     * noteResult → refine pattern the older InvestigationLoop uses.
     * Returns plain-words next-step suggestions (possibly empty). The
     * default ignores the result, so existing providers keep working.
     */
    fun noteResult(capabilityId: String, outcome: VnicExecutionOutcome): List<String> = emptyList()
}

/**
 * Local rule reasoner. Keyword rules over the observation and the known
 * facts; plans are capability ids whose keywords match the goal. This is
 * the same honest approach as the existing RuleBasedReasoner: it says what
 * it can derive from the words in front of it, nothing more.
 *
 * Capability ids use short domain codes (ble, net, usb, nfc) while people
 * write the long words (bluetooth, wifi, serial). [SYNONYMS] bridges them
 * so "scan bluetooth devices" finds ble.scan.
 */
class LocalRuleReasoner : VnicReasoningProvider {
    companion object {
        val SYNONYMS = mapOf(
            "bluetooth" to "ble", "ble" to "bluetooth",
            "wifi" to "net", "wireless" to "net",
            "serial" to "usb", "uart" to "usb",
            "tag" to "nfc",
            "printer" to "print", "print" to "printer"
        )
    }

    override val name = "Built-in helper"

    override fun hypotheses(observation: String, knownFacts: List<String>): List<String> {
        val obs = observation.lowercase()
        val out = mutableListOf<String>()
        if ("bluetooth" in obs || "ble" in obs || "gatt" in obs) {
            out += "This looks like a Bluetooth device. Try finding Bluetooth devices, then connect and see what it can do."
        }
        if ("wifi" in obs || "ssid" in obs || "network" in obs || "ip" in obs) {
            out += "This looks network-related. Try checking the network first, before trying to control it."
        }
        if ("usb" in obs || "serial" in obs || "uart" in obs || "ch340" in obs) {
            out += "This looks like a device on a USB cable. Try the USB check first."
        }
        if ("nfc" in obs || "tag" in obs) {
            out += "This looks like a tap card. Try reading the card, then see what kind it is."
        }
        if ("printer" in obs || "print" in obs) {
            out += "This looks like a printer. Try finding out what kind it is, then a small test print only after you say yes."
        }
        if (knownFacts.isNotEmpty()) {
            out += "What we already know: ${knownFacts.take(3).joinToString("; ")}"
        }
        if (out.isEmpty()) {
            out += "Unknown device. No rule fits — look it up first, before trying to control it."
        }
        return out
    }

    override fun planFor(goal: String, capabilityIds: List<String>): List<String> {
        val words = goal.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return emptyList()
        val expanded = words + words.mapNotNull { SYNONYMS[it] }
        return capabilityIds.filter { id ->
            val idWords = id.lowercase().split(Regex("[._]")).toSet()
            idWords.any { it in expanded } || expanded.any { w -> id.lowercase().contains(w) }
        }.take(5)
    }

    /**
     * Enriched planning: match the goal words against id + title +
     * description, not the id alone — the registry descriptions are real
     * signal (e.g. "scan bluetooth" in a title matches "bluetooth" even
     * when the id is just "ble.scan").
     */
    override fun planFor(
        goal: String,
        capabilities: List<VnicCapabilityInfo>,
        knownFacts: List<String>,
        findings: List<VnicFinding>
    ): List<String> {
        val words = goal.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return emptyList()
        val expanded = words + words.mapNotNull { SYNONYMS[it] }
        return capabilities.filter { cap ->
            val hay = (cap.id + " " + cap.title + " " + cap.description).lowercase()
            val hayWords = hay.split(Regex("[\\W_]+")).filter { it.length > 2 }.toSet()
            hayWords.any { it in expanded } || expanded.any { w -> hay.contains(w) }
        }.map { it.id }.take(5)
    }

    /**
     * Result-ingest (Gap 1): rule-based evaluation of what just ran —
     * a step that worked means the next step can build on its result; a
     * step that failed means the next step should try a different check,
     * not the same one again.
     */
    override fun noteResult(capabilityId: String, outcome: VnicExecutionOutcome): List<String> = when (outcome.state) {
        VnicExecutionState.VERIFIED, VnicExecutionState.CONTROLLABLE ->
            listOf("$capabilityId worked — the device answered, so the next step can build on that result.")
        VnicExecutionState.CONNECTED ->
            listOf("$capabilityId connected — the next step can try reading from it.")
        VnicExecutionState.ATTEMPTED ->
            listOf("$capabilityId was tried but no answer came back — the next step should check the device is still there before trying again.")
        VnicExecutionState.FAILED ->
            listOf("$capabilityId did not work — the next step should try a different check, not the same one again.")
        else -> emptyList()
    }
}

data class VnicLlmConfig(
    /** OpenAI-compatible chat-completions endpoint. Blank/null defaults to OpenAI — see [DEFAULT_ENDPOINT]. */
    val endpoint: String? = null,
    /** Bearer key for the endpoint. Unset by default — set VNIC_LLM_API_KEY to enable. */
    val apiKey: String? = null,
    val model: String = "gpt-4o-mini",
    val connectTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 30000
) {
    companion object {
        /** Same default as the older investigation path (Reasoning.kt DEFAULT_ENDPOINT). */
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"

        /**
         * Build a config from the saved Thinking Engine settings
         * (IntelSettings): trims both values; a blank endpoint defaults to
         * OpenAI, so a key-only user gets remote reasoning; a blank key
         * stays unset (null). Never logs or stores the key anywhere else.
         */
        fun fromSaved(endpoint: String?, apiKey: String?): VnicLlmConfig {
            val key = apiKey?.trim().orEmpty()
            val ep = endpoint?.trim().orEmpty().ifBlank { DEFAULT_ENDPOINT }
            return VnicLlmConfig(endpoint = ep, apiKey = key.ifBlank { null })
        }
    }

    /** Key-only, mirroring the old path: a blank endpoint defaults to OpenAI, so it never blocks remote use. */
    val configured: Boolean get() = !apiKey.isNullOrBlank()

    /** The endpoint actually called: the configured one, or OpenAI when blank/unset. */
    val effectiveEndpoint: String get() = endpoint?.trim()?.ifBlank { DEFAULT_ENDPOINT } ?: DEFAULT_ENDPOINT
}

/**
 * Optional remote LLM adapter (OpenAI-compatible /v1/chat/completions).
 * Returns plain text only; the core decides what to do with it. Constructed
 * only when [VnicLlmConfig.configured] — a key is present; the endpoint is
 * optional and defaults to OpenAI ([VnicLlmConfig.DEFAULT_ENDPOINT]),
 * mirroring the older investigation path. The key is used only as the
 * Bearer header of the request itself; it is never logged, never put in a
 * diagnostic string, and never included in any exception or failure reason.
 */
class RemoteLlmReasoner(private val config: VnicLlmConfig) : VnicReasoningProvider {
    override val name = "Online helper"

    /**
     * What the diagnostic is allowed to know about this reasoner: the
     * endpoint it calls, the model it asks for, and whether a key is
     * present. The key value itself is never exposed.
     */
    val remoteSummary: VnicRemoteSummary
        get() = VnicRemoteSummary(config.effectiveEndpoint, config.model, config.configured)

    /** True when a key is saved — the same key-only rule as [VnicLlmConfig.configured]. */
    val configured: Boolean get() = config.configured

    /** The endpoint this reasoner actually calls (the saved one, or OpenAI when blank). */
    val effectiveEndpoint: String get() = config.effectiveEndpoint

    /** Plain-words reason the last remote call failed (null when it worked). Set by [complete]; never contains credentials. */
    override var lastFailureReason: String? = null
        private set

    /** True when the last hypotheses or planFor call fell back to the built-in helper after the online helper failed. */
    override var lastPlanFellBackToLocal: Boolean = false
        private set

    /**
     * The built-in helper this falls back to (Package 7 / E-13 / F-24).
     * The old fallback returned one sentence AS the hypothesis —
     * "The online helper isn't available — using the built-in helper
     * instead." — without ever consulting the built-in helper, so the
     * sentence masqueraded as a hypothesis. Now the fallback SAYS the
     * online brain didn't answer (first line, labelled as a status, not
     * a hypothesis) and THEN returns the built-in helper's real
     * hypotheses, so the user always gets genuine hypotheses.
     */
    private val localFallback = LocalRuleReasoner()

    override fun hypotheses(observation: String, knownFacts: List<String>): List<String> {
        // Gap 5b: a failed hypotheses call sets the fallback flag too, so a
        // caller reading it after this call sees the fallback honestly.
        // planFor resets the flag at its own start, so a later plan in the
        // same cycle is unaffected by this.
        lastPlanFellBackToLocal = false
        val prompt = buildString {
            append("You are helping identify an unknown device or subject observed by an Android app called Ghost Tap. ")
            append("Observation: ").append(observation).append("\n")
            if (knownFacts.isNotEmpty()) append("Known facts: ").append(knownFacts.joinToString("; ")).append("\n")
            append("Reply with 1-3 short hypotheses, one per line, about what this could be and what safe read-only check to try first. ")
            append("Never guess a device identity with confidence; say UNKNOWN when unsure.")
        }
        val text = complete(prompt)
        if (text == null) {
            lastPlanFellBackToLocal = true
            return listOf("The online brain didn't answer — using the built-in helper instead.") +
                localFallback.hypotheses(observation, knownFacts)
        }
        return text.lines().map { it.trim() }.filter { it.isNotBlank() }.take(3)
    }

    /**
     * Result-ingest (Gap 1): the execution result goes back through the
     * model for evaluation — same [complete] call as hypotheses/plans,
     * same failure handling. On a failed remote call the built-in
     * helper's evaluation is used and labelled, exactly like the
     * hypotheses fallback.
     */
    override fun noteResult(capabilityId: String, outcome: VnicExecutionOutcome): List<String> {
        val prompt = buildString {
            append("You are helping an Android app called Ghost Tap decide what to do next after a check just ran. ")
            append("Capability: ").append(capabilityId).append("\n")
            append("Outcome: ").append(outcome.state.name).append(" — ").append(outcome.evidence.take(300)).append("\n")
            append("Reply with 1-2 short next steps in plain words, one per line — what useful check to try next, given what just happened. ")
            append("Describe the check in plain words, never invent a capability id. If nothing useful follows, reply with nothing.")
        }
        val text = complete(prompt)
            ?: return listOf("The online brain didn't answer — using the built-in helper instead.") +
                localFallback.noteResult(capabilityId, outcome)
        return text.lines().map { it.trim() }.filter { it.isNotBlank() }.take(2)
    }

    override fun planFor(goal: String, capabilityIds: List<String>): List<String> =
        planFor(goal, capabilityIds.map { VnicCapabilityInfo(it, "", "", "", false) }, emptyList(), emptyList())

    /**
     * Enriched planning: the model sees the goal, every capability's id +
     * title + description, the known facts, and the fresh research findings
     * (even when there are none — an empty findings list is stated, not
     * hidden). The current observation takes precedence over older facts
     * if they disagree. Only ids from the list may be returned; the core
     * validates that again before anything runs.
     *
     * On a failed remote call this no longer returns a silent empty list:
     * it falls back to the built-in helper's plan and sets
     * [lastPlanFellBackToLocal] (+ [lastFailureReason]), so the core can
     * label the plan honestly as built-in, never as online.
     */
    override fun planFor(
        goal: String,
        capabilities: List<VnicCapabilityInfo>,
        knownFacts: List<String>,
        findings: List<VnicFinding>
    ): List<String> {
        lastPlanFellBackToLocal = false
        val capabilityIds = capabilities.map { it.id }
        val prompt = buildString {
            append("You are helping an Android app called Ghost Tap work out what safe read-only checks to run for an observation. ")
            append("Goal: ").append(goal).append("\n")
            append("Available capabilities (id — title: description):\n")
            for (cap in capabilities) {
                append("- ").append(cap.id).append(" — ").append(cap.title)
                if (cap.description.isNotBlank()) append(": ").append(cap.description.take(200))
                append("\n")
            }
            if (knownFacts.isNotEmpty()) {
                append("What we already know: ").append(knownFacts.take(5).joinToString("; ")).append("\n")
            }
            if (findings.isNotEmpty()) {
                append("Fresh research findings:\n")
                for (f in findings.take(5)) {
                    append("- ").append(f.title).append(" (").append(f.source).append("): ").append(f.snippet.take(200)).append("\n")
                }
            } else {
                append("Fresh research findings: none — research returned nothing usable.\n")
            }
            append("The current observation above takes precedence over older facts if they disagree. ")
            append("Reply with only the ids, comma-separated, in the order they should run (max 5). Use only ids from the list. ")
            append("If no capability fits, reply with nothing.")
        }
        val text = complete(prompt)
        if (text == null) {
            lastPlanFellBackToLocal = true
            return localFallback.planFor(goal, capabilities, knownFacts, findings)
        }
        val wanted = capabilityIds.toSet()
        return text.split(Regex("[,\\s]+")).map { it.trim() }.filter { it in wanted }.take(5)
    }

    /**
     * One chat-completions call. Accepts any successful HTTP 2xx response,
     * not just 200. Every failure records a plain-words reason in
     * [lastFailureReason] (classified from the real status / exception,
     * never guessed) and returns null — the caller then falls back
     * explicitly. The key is sent only in the Authorization header and
     * never appears in any reason, log, or exception.
     */
    /**
     * One chat-completions call. Accepts any successful HTTP 2xx response,
     * not just 200. Every failure records a plain-words reason in
     * [lastFailureReason] (classified from the real status / exception,
     * never guessed) and returns null — the caller then falls back
     * explicitly. The key is sent only in the Authorization header and
     * never appears in any reason, log, or exception.
     */
    private fun complete(prompt: String): String? {
        lastFailureReason = null
        if (!config.configured) {
            lastFailureReason = "Online helper not configured — no key saved in the Thinking Engine box."
            return null
        }
        val payload = "{\"model\":${esc(config.model)},\"messages\":[{\"role\":\"user\",\"content\":${esc(prompt)}}],\"temperature\":0.2,\"max_tokens\":400}"
        return when (val exchange = postChat(payload)) {
            is VnicChatExchange.Failed -> {
                lastFailureReason = exchange.reason
                null
            }
            is VnicChatExchange.Responded -> {
                if (exchange.code !in 200..299) {
                    lastFailureReason = httpFailureReason(exchange.code)
                    null
                } else when (val parsed = parseChatContentDetailed(exchange.body)) {
                    is VnicChatContent.Text -> parsed.text
                    VnicChatContent.Empty -> {
                        lastFailureReason = "Online helper answered but said nothing."
                        null
                    }
                    VnicChatContent.Malformed -> {
                        lastFailureReason = "Online helper answered, but the answer was in a shape Ghost Tap couldn't read."
                        null
                    }
                }
            }
        }
    }

    /**
     * The AI connection check probe (Part 2 diagnostic): sends exactly
     * [vnicDiagProbePayload] — the configured model, one user message
     * asking for the exact words GHOST_TAP_AI_CONNECTED, temperature 0 —
     * through the same [postChat] transport the real reasoning uses, and
     * reports the outcome stage by stage. No local fallback: a failure
     * is reported, never papered over with a built-in answer.
     */
    fun probeConnection(): VnicProbeOutcome {
        lastFailureReason = null
        if (!config.configured) {
            lastFailureReason = "Online helper not configured — no key saved in the Thinking Engine box."
            return VnicProbeOutcome(
                requestSent = false, httpCode = null, httpSuccess = false,
                parsedContent = null, malformed = false, failureReason = lastFailureReason
            )
        }
        return when (val exchange = postChat(vnicDiagProbePayload(config.model))) {
            is VnicChatExchange.Failed -> {
                lastFailureReason = exchange.reason
                VnicProbeOutcome(exchange.requestSent, null, false, null, false, exchange.reason)
            }
            is VnicChatExchange.Responded -> {
                if (exchange.code !in 200..299) {
                    val reason = httpFailureReason(exchange.code)
                    lastFailureReason = reason
                    VnicProbeOutcome(true, exchange.code, false, null, false, reason)
                } else when (val parsed = parseChatContentDetailed(exchange.body)) {
                    is VnicChatContent.Text -> VnicProbeOutcome(true, exchange.code, true, parsed.text, false, null)
                    VnicChatContent.Empty -> {
                        val reason = "Online helper answered but said nothing."
                        lastFailureReason = reason
                        VnicProbeOutcome(true, exchange.code, true, "", false, reason)
                    }
                    VnicChatContent.Malformed -> {
                        val reason = "Online helper answered, but the answer was in a shape Ghost Tap couldn't read."
                        lastFailureReason = reason
                        VnicProbeOutcome(true, exchange.code, true, null, true, reason)
                    }
                }
            }
        }
    }

    /**
     * One raw chat-completions HTTP exchange, shared by [complete] and
     * [probeConnection] so the check and the real reasoning use the same
     * transport, the same endpoint, the same key, and the same failure
     * classification. [VnicChatExchange.Failed.requestSent] is true once
     * the request bytes were written — a DNS/connect failure means the
     * request never went out; a failure after the write means it did.
     * The key is sent only in the Authorization header, never in any
     * reason string.
     */
    private fun postChat(payloadJson: String): VnicChatExchange {
        if (!config.configured) {
            return VnicChatExchange.Failed(
                requestSent = false,
                reason = "Online helper not configured — no key saved in the Thinking Engine box."
            )
        }
        val key = config.apiKey!!.trim()
        var conn: HttpURLConnection? = null
        var requestSent = false
        return try {
            conn = (URL(config.effectiveEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", VnicHttp.USER_AGENT)
                connectTimeout = config.connectTimeoutMs
                readTimeout = config.readTimeoutMs
                doOutput = true
            }
            conn.outputStream.use { it.write(payloadJson.toByteArray(Charsets.UTF_8)) }
            requestSent = true
            val code = conn.responseCode
            if (code !in 200..299) {
                VnicChatExchange.Responded(code, "")
            } else {
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                VnicChatExchange.Responded(code, body)
            }
        } catch (e: Exception) {
            VnicChatExchange.Failed(requestSent, classifyExchangeFailure(e))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Plain-words reason for a non-2xx HTTP status, from the real status — never guessed. */
    private fun httpFailureReason(code: Int): String = when {
        code == 401 || code == 403 -> "Online helper refused the saved key (HTTP $code) — check the key in the Thinking Engine box."
        code == 429 -> "Online helper is busy (HTTP 429) — try again shortly."
        code in 400..499 -> "Online helper refused the request (HTTP $code)."
        code in 500..599 -> "Online helper had a problem (HTTP $code) — try again later."
        else -> "Online helper answered HTTP $code."
    }

    /** Plain-words reason for a transport failure, from the real exception type — never guessed. */
    private fun classifyExchangeFailure(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "Online helper timed out — check the connection and try again."
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException -> "Could not reach the online helper — check the connection."
        is java.io.IOException -> "Could not reach the online helper — check the connection."
        else -> "Online helper answer could not be read."
    }

    /**
     * Extract choices[0].message.content from a chat-completions response.
     * Anchored at "choices" then "message" — the old code took the first
     * "content" anywhere in the body, which is only coincidentally the
     * message content for a standard OpenAI reply. A null / non-string
     * content (e.g. a tool-call reply) returns null, not a wrong string.
     *
     * Gap 3 (7D): the detailed form distinguishes a MALFORMED body (bad
     * JSON / missing choices / missing message / non-string content) from
     * a valid-but-EMPTY answer — they used to share one reason.
     */
    internal fun parseChatContentDetailed(body: String): VnicChatContent {
        val choicesIdx = body.indexOf("\"choices\"")
        if (choicesIdx < 0) return VnicChatContent.Malformed
        val messageIdx = body.indexOf("\"message\"", choicesIdx)
        if (messageIdx < 0) return VnicChatContent.Malformed
        val content = VnicJson.stringField(body, "content", messageIdx) ?: return VnicChatContent.Malformed
        return if (content.isBlank()) VnicChatContent.Empty else VnicChatContent.Text(content)
    }

    private fun parseChatContent(body: String): String? = when (val parsed = parseChatContentDetailed(body)) {
        is VnicChatContent.Text -> parsed.text
        VnicChatContent.Empty -> ""
        VnicChatContent.Malformed -> null
    }

    private fun esc(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

/** Outcome of one raw chat-completions HTTP exchange. Internal: callers map it to their own failure labels. */
internal sealed class VnicChatExchange {
    /** The request was written and an HTTP response arrived. `body` is the response body on 2xx, "" otherwise. */
    data class Responded(val code: Int, val body: String) : VnicChatExchange()
    /** The request could not be completed. `requestSent` is true once the request bytes were written. */
    data class Failed(val requestSent: Boolean, val reason: String) : VnicChatExchange()
}

/**
 * What a chat-completions body actually held: usable text, a valid-but-
 * empty answer, or a shape that cannot be read as a chat completion.
 */
internal sealed class VnicChatContent {
    data class Text(val text: String) : VnicChatContent()
    object Empty : VnicChatContent()
    object Malformed : VnicChatContent()
}

/**
 * Reasoner indirection (Defect 1 repair): the core holds this one object
 * for its whole life, while the engine swaps the live delegate underneath
 * it (local <-> remote) whenever the saved settings change. Swapping the
 * delegate never rebuilds the core, so mesh, memory, learning and
 * telemetry survive a reconfigure. Delegates every call and every
 * diagnostic label to the current delegate.
 */
class SwitchableReasoner(initial: VnicReasoningProvider) : VnicReasoningProvider {
    @Volatile
    var delegate: VnicReasoningProvider = initial

    override val name: String get() = delegate.name
    override val lastFailureReason: String? get() = delegate.lastFailureReason
    override val lastPlanFellBackToLocal: Boolean get() = delegate.lastPlanFellBackToLocal

    override fun hypotheses(observation: String, knownFacts: List<String>): List<String> =
        delegate.hypotheses(observation, knownFacts)

    override fun planFor(goal: String, capabilityIds: List<String>): List<String> =
        delegate.planFor(goal, capabilityIds)

    override fun planFor(
        goal: String,
        capabilities: List<VnicCapabilityInfo>,
        knownFacts: List<String>,
        findings: List<VnicFinding>
    ): List<String> = delegate.planFor(goal, capabilities, knownFacts, findings)

    override fun noteResult(capabilityId: String, outcome: VnicExecutionOutcome): List<String> =
        delegate.noteResult(capabilityId, outcome)
}
