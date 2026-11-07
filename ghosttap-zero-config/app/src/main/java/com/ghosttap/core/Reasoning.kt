package com.ghosttap.core

import android.app.Application
import android.content.Context
import com.ghosttap.core.investigate.AiFailureReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One machine-readable belief the reasoning engine holds about the target.
 * Hypotheses are EVIDENCE — they update as results come in, and they are
 * never presented as verified facts. Confidence is 0.0..1.0.
 */
data class Hypothesis(
    val text: String,
    val confidence: Double,
    val supporting: List<String> = emptyList(),
    val contradicting: List<String> = emptyList()
)

/**
 * A reasoning provider refines investigation plans and learns from results.
 *
 * The phone has no on-device LLM. The default provider is [RuleBasedReasoner]:
 * an evidence-driven local reasoner (hypotheses with confidence, failure
 * tracking, identifier extraction) that works fully offline. It is NEVER
 * labeled as AI — its statusLine says exactly what it is.
 *
 * [RemoteLlmReasoner] is the optional first-class OpenAI-compatible engine:
 * your key, your endpoint. It can never invent capability ids, can never
 * flip a non-READ step to auto, and ANY failure falls back to the local
 * reasoner. The UI always shows which engine produced the plan.
 */
interface ReasoningProvider {
    val name: String
    val configured: Boolean

    /** One line describing exactly which engine is active. Always shown in UI. */
    val statusLine: String

    suspend fun refinePlan(
        target: IntelTarget,
        steps: List<PlanStep>,
        knowledge: String
    ): List<PlanStep>

    /** Feed an execution result back into the engine's evidence. */
    fun noteResult(capabilityId: String, result: OpResult) {}

    /**
     * Feed research findings into the engine's evidence (default: ignore).
     * Findings carry their own verified flag — unverified findings must stay
     * leads, never facts.
     */
    fun noteFindings(findings: List<Finding>) {}

    /**
     * Ask for background on identifiers (default: nothing). Only a configured
     * remote engine answers; the local reasoner has no outside source.
     */
    suspend fun backgroundOn(topic: String): String = ""

    /**
     * Draft a DynamicCapabilityDef JSON for a capability gap (default: none).
     * The draft is NEVER trusted — DynamicCapabilityRuntime validates it and
     * PROVES it by running its steps through the real engine before anything
     * is registered. An empty string means "no draft".
     */
    suspend fun draftCapabilityJson(
        gapDescription: String,
        available: List<IntelCapability>
    ): String = ""

    /**
     * Draft a JavaScript extension for a capability gap (default: none).
     * Returns JSON {"title": "...", "jsSource": "..."} or "".
     * The draft is NEVER trusted — ScriptRuntime validates the syntax and
     * contract and PROVES it by executing it in the locked-down sandbox
     * before anything is registered. An empty string means "no draft".
     */
    suspend fun draftScriptJson(
        gapDescription: String,
        available: List<IntelCapability>
    ): String = ""

    /** Current hypotheses, newest understanding first. */
    fun hypotheses(): List<Hypothesis> = emptyList()

    /** Free-text notes from the last refine (remote engine only). */
    fun lastNotes(): String = ""

    /** Clear per-investigation evidence when a new investigation starts. */
    fun reset() {}
}

/**
 * Evidence-driven local reasoner. More than keyword matching:
 * - tracks per-capability failures and deprioritizes repeat offenders,
 * - extracts identifiers (MAC/IP/host) from successful results into context,
 * - maintains hypotheses with confidence that strengthen/weaken on evidence,
 * - folds memory (knowledge string) into ordering.
 *
 * Fully offline. Labeled "Local reasoning (on-device rules)" — never AI.
 */
class RuleBasedReasoner : ReasoningProvider {
    override val name = "Local reasoning (on-device rules)"
    override val configured = true
    override val statusLine = "Local reasoning (on-device rules) — not an AI model."

    private val failures = mutableMapOf<String, Int>()
    private val failureNotes = mutableMapOf<String, MutableList<String>>()
    private val successes = mutableSetOf<String>()
    private val foundIdentifiers = mutableMapOf<String, String>() // id -> where
    private val hyps = mutableListOf<Hypothesis>()

    private val macRegex = Regex("(?i)\\b([0-9A-F]{2}[:-]){5}[0-9A-F]{2}\\b")
    private val ipv4Regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val hostRegex = Regex("(?i)\\b([a-z0-9-]+\\.)+(com|net|org|io|local|lan)\\b")

    override fun reset() {
        failures.clear()
        failureNotes.clear()
        successes.clear()
        foundIdentifiers.clear()
        hyps.clear()
    }

    override fun noteResult(capabilityId: String, result: OpResult) {
        val evidence = "${result.summary.take(160)}"
        if (result.ok) {
            successes.add(capabilityId)
            // Extract identifiers from real output into the target context.
            val hay = result.summary + "\n" + result.data
            macRegex.findAll(hay).map { it.value }.distinct().take(3).forEach {
                foundIdentifiers[it] = capabilityId
            }
            ipv4Regex.findAll(hay).map { it.value }.distinct().take(3).forEach {
                foundIdentifiers[it] = capabilityId
            }
            hostRegex.findAll(hay).map { it.value }.distinct().take(3).forEach {
                foundIdentifiers[it] = capabilityId
            }
            upsertHypothesis(
                "Evidence from $capabilityId looks usable on this target.",
                0.7, evidence, null
            )
        } else {
            val n = (failures[capabilityId] ?: 0) + 1
            failures[capabilityId] = n
            failureNotes.getOrPut(capabilityId) { mutableListOf() }.add(evidence)
            upsertHypothesis(
                "$capabilityId has failed $n time(s) on this target.",
                (0.5 + 0.15 * n).coerceAtMost(0.95),
                evidence, null
            )
            // A contradicting data point weakens any success hypothesis for it.
            weakenHypothesesContaining(capabilityId, "looks usable")
        }
    }

    override fun hypotheses(): List<Hypothesis> = hyps.toList()

    /**
     * Fold research findings into hypotheses. Verified findings (past
     * investigations, learned controls, the app's own tables) strengthen a
     * belief; unverified ones are capped at 0.5 and labeled as leads.
     */
    override fun noteFindings(findings: List<Finding>) {
        for (f in findings.take(10)) {
            val conf = if (f.verified) {
                (f.confidence + 0.1).coerceAtMost(1.0)
            } else {
                f.confidence.coerceAtMost(0.5)
            }
            upsertHypothesis(
                "Research: ${f.text.take(140)}",
                conf,
                "source: ${f.source}",
                if (!f.verified) "unverified — a lead, not a fact" else null
            )
        }
    }

    /**
     * Draft a capability for a gap by composing READ-only registry tools whose
     * titles match the gap's keywords, filling args from identifiers spotted
     * in the gap text. Best-effort: the runtime TEST proves it, and an empty
     * string here just means "no draft" — never a fake capability.
     */
    override suspend fun draftCapabilityJson(
        gapDescription: String,
        available: List<IntelCapability>
    ): String {
        val picked = pickGapTools(gapDescription, available)
        if (picked.isEmpty()) return ""
        val keywords = gapKeywords(gapDescription)
        val def = DynamicCapabilityDef(
            id = "dyn.gap" + (System.currentTimeMillis() % 100000),
            title = "Gap probe: ${keywords.take(3).joinToString(" ")}",
            detail = "Auto-composed probe for: ${gapDescription.take(200)}. " +
                "Each step is an existing read-only tool; the test run proves it.",
            riskLevel = "READ",
            steps = picked.map { (c, args) -> DynStep(c.id, args) },
            assertions = listOf(DynAssertion("ok", "true")),
            origin = "ghost-intel/rule-based"
        )
        return DynamicCapabilityDef.toJson(def)
    }

    /**
     * Draft a genuine JavaScript probe for a gap: a template script that runs
     * the same READ-only tools the composer would pick, but as real scripted
     * logic (loop, JSON shaping, findings) through the ghost.run host API.
     * The sandbox TEST proves it; an empty string means "no draft".
     */
    override suspend fun draftScriptJson(
        gapDescription: String,
        available: List<IntelCapability>
    ): String {
        val picked = pickGapTools(gapDescription, available).take(3)
        if (picked.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("// Auto-drafted probe for: ")
            .append(gapDescription.take(120).replace("\n", " ")).append("\n")
        sb.append("function main(args) {\n")
        sb.append("  var findings = [];\n")
        sb.append("  var data = {};\n")
        sb.append("  var allOk = true;\n")
        for ((i, pair) in picked.withIndex()) {
            val (cap, capArgs) = pair
            val argsJson = JSONObject(capArgs).toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            sb.append("  var r$i = JSON.parse(ghost.run(\"${cap.id}\", \"$argsJson\"));\n")
            sb.append("  data[\"${cap.id}\"] = r$i.summary;\n")
            sb.append("  if (!r$i.ok) { allOk = false; }\n")
            val safeTitle = cap.title.replace("\"", "'").take(80)
            sb.append("  findings.push({title: \"$safeTitle\", text: r$i.summary});\n")
        }
        sb.append("  ghost.log(\"probe done: \" + findings.length + \" tool(s) ran\");\n")
        sb.append("  return JSON.stringify({ok: allOk, findings: findings, data: data});\n")
        sb.append("}\n")
        val title = "Scripted probe: ${picked.first().first.title.take(40)}"
        return ScriptDraft.toJson(title, sb.toString())
    }

    private fun gapKeywords(gapDescription: String): List<String> =
        gapDescription.split(Regex("[^A-Za-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinct()
            .take(8)

    /**
     * READ-only registry tools matching the gap's keywords, with args filled
     * from identifiers spotted in the gap text. Shared by the composed and
     * scripted drafters.
     */
    private fun pickGapTools(
        gapDescription: String,
        available: List<IntelCapability>
    ): List<Pair<IntelCapability, Map<String, String>>> {
        val keywords = gapKeywords(gapDescription)
        val reads = available.filter { it.riskLevel == "READ" && !it.id.startsWith("intel.") }
        val picked = mutableListOf<IntelCapability>()
        fun takeMatch(pred: (IntelCapability) -> Boolean) {
            if (picked.size >= 4) return
            reads.firstOrNull { c -> c.id !in picked.map { it.id } && pred(c) }
                ?.let { picked.add(it) }
        }
        for (kw in keywords) {
            takeMatch { c ->
                c.title.contains(kw, ignoreCase = true) ||
                    c.detail.contains(kw, ignoreCase = true) ||
                    c.id.contains(kw, ignoreCase = true)
            }
        }
        if (picked.isEmpty()) {
            // Identifier-driven fallback: a MAC wants a scan, an IP wants a ping.
            val mac = macRegex.find(gapDescription)?.value
            val ip = ipv4Regex.find(gapDescription)?.value
            if (mac != null) takeMatch { it.id.contains("scan", ignoreCase = true) }
            if (ip != null) takeMatch { it.id.contains("ping", ignoreCase = true) }
        }
        return picked.map { c -> c to fillArgs(c, gapDescription) }
    }

    /** Best-effort arg fill from identifiers spotted in the gap text. */
    private fun fillArgs(cap: IntelCapability, gap: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val mac = macRegex.find(gap)?.value
        val ip = ipv4Regex.find(gap)?.value
        for (inp in cap.inputs) {
            val v = when {
                mac != null && (inp.contains("mac", ignoreCase = true) || inp == "hint") -> mac
                ip != null && (inp.contains("host", ignoreCase = true) || inp == "url") -> ip
                else -> null
            }
            if (v != null) out[inp] = v
        }
        return out
    }

    override suspend fun refinePlan(
        target: IntelTarget,
        steps: List<PlanStep>,
        knowledge: String
    ): List<PlanStep> {
        // Dedupe by capability id, keep first.
        val seen = mutableSetOf<String>()
        val deduped = steps.filter { seen.add(it.capabilityId) }
        // Memory-informed: knowledge mentioning past failures of an id counts
        // as one more strike against it.
        val memoryStrikes = mutableMapOf<String, Int>()
        if (knowledge.isNotBlank()) {
            for (s in deduped) {
                val id = s.capabilityId
                if (knowledge.contains(id, ignoreCase = true) &&
                    knowledge.contains("fail", ignoreCase = true)
                ) {
                    memoryStrikes[id] = (memoryStrikes[id] ?: 0) + 1
                }
            }
        }

        // Order: untried auto probes first, then untried proposed, then
        // previously-failed (demoted, auto cleared, reason noted).
        val fresh = mutableListOf<PlanStep>()
        val stale = mutableListOf<PlanStep>()
        for (s in deduped) {
            val strikes = (failures[s.capabilityId] ?: 0) + (memoryStrikes[s.capabilityId] ?: 0)
            if (strikes > 0) {
                stale.add(
                    s.copy(
                        why = s.why + " (failed ${strikes}x before — tap RUN to retry anyway)",
                        auto = false
                    )
                )
            } else {
                fresh.add(s)
            }
        }
        val ordered = (fresh.sortedByDescending { it.auto } +
            stale.sortedByDescending { it.auto })

        // Identifier follow-ups: if we extracted an identifier and no step
        // references it yet, note it — the planner's next planFor will pick
        // it up, and the UI shows it via hypotheses.
        return ordered
    }

    private fun upsertHypothesis(
        text: String, confidence: Double, support: String?, contradict: String?
    ) {
        val i = hyps.indexOfFirst { it.text == text }
        val clamped = confidence.coerceIn(0.0, 1.0)
        if (i >= 0) {
            val h = hyps[i]
            val sup = (h.supporting + listOfNotNull(support)).distinct().take(5)
            val con = (h.contradicting + listOfNotNull(contradict)).distinct().take(5)
            hyps[i] = h.copy(confidence = clamped, supporting = sup, contradicting = con)
        } else {
            hyps.add(
                0, Hypothesis(
                    text, clamped,
                    supporting = listOfNotNull(support).take(5),
                    contradicting = listOfNotNull(contradict).take(5)
                )
            )
        }
        if (hyps.size > 12) hyps.subList(12, hyps.size).clear()
    }

    private fun weakenHypothesesContaining(id: String, phrase: String) {
        for (i in hyps.indices) {
            val h = hyps[i]
            if (h.text.contains(id) && h.text.contains(phrase)) {
                hyps[i] = h.copy(confidence = (h.confidence - 0.2).coerceAtLeast(0.1))
            }
        }
    }
}

/**
 * First-class OpenAI-compatible reasoning engine. Your key, your endpoint
 * (defaults to OpenAI chat completions when you paste only a key).
 *
 * The model ONLY chooses from the registry JSON it is given. The validation
 * layer then enforces: unknown capability ids dropped; auto forced false for
 * any non-READ capability; confidence clamped to 0..1. ANY error — network,
 * bad status, unparseable body — returns the local reasoner's output.
 */
class RemoteLlmReasoner(
    endpoint: String,
    apiKey: String,
    private val fallback: RuleBasedReasoner = RuleBasedReasoner()
) : ReasoningProvider {

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val MODEL = "gpt-4o-mini"
    }

    // Trimmed defensively: a key or endpoint pasted with a stray
    // space or newline is rejected by the service (401) even though
    // it looks right. The Thinking Engine box already trims on save;
    // this covers any other path the values arrive by. A clean key
    // is unchanged by this.
    private val effectiveEndpoint: String =
        endpoint.trim().ifBlank { DEFAULT_ENDPOINT }
    private val trimmedApiKey: String = apiKey.trim()

    override val name = "Remote AI (your service)"
    override val configured = trimmedApiKey.isNotBlank()
    override val statusLine: String
        get() = if (configured) "Remote AI connected ($MODEL via your endpoint)"
        else "Remote AI not configured — using local reasoning"

    private var remoteHyps: List<Hypothesis> = emptyList()
    private var notes: String = ""

    /**
     * Why the last [backgroundOn] call failed, when it returned "".
     * Classified from the REAL failure — the actual exception or the
     * response code — never guessed. Null when the last call worked
     * or has not run yet. Read by the live-target card so it can say
     * in plain words which one it was.
     */
    var lastFailureReason: AiFailureReason? = null
        private set

    override fun reset() {
        fallback.reset()
        remoteHyps = emptyList()
        notes = ""
        lastFailureReason = null
    }

    override fun noteResult(capabilityId: String, result: OpResult) {
        // Evidence always accumulates locally, even when remote is active.
        fallback.noteResult(capabilityId, result)
    }

    override fun hypotheses(): List<Hypothesis> =
        (remoteHyps + fallback.hypotheses()).distinctBy { it.text }.take(12)

    override fun lastNotes(): String = notes

    /**
     * Plain-text background on identifiers for the research step. Never
     * invents capabilities; the caller labels everything from here as
     * unverified. Any failure returns "" — the loop continues without it.
     */
    override suspend fun backgroundOn(topic: String): String {
        if (!configured) return ""
        return try {
            withContext(Dispatchers.IO) { postBackground(topic) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun postBackground(topic: String): String {
        // Every failure path below records WHY it failed in
        // [lastFailureReason] before returning "" — a blank answer
        // with no reason is how the card used to hide the cause.
        // Success leaves the reason null.
        lastFailureReason = null
        var conn: HttpURLConnection? = null
        return try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put(
                            "content", "You are a hardware/Bluetooth research assistant. " +
                                "Give short plain-text background on the identifiers described. " +
                                "Say clearly what is well-established versus a guess. " +
                                "No JSON, just text."
                        )
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", topic)
                    })
                })
            }.toString()
            conn = (URL(effectiveEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $trimmedApiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                lastFailureReason = if (code == 401 || code == 403) {
                    AiFailureReason.KEY_REJECTED
                } else {
                    AiFailureReason.SERVER_REFUSED
                }
                return ""
            }
            val resp = conn.inputStream.bufferedReader().readText()
            val content = try {
                JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (content.isBlank()) {
                lastFailureReason = AiFailureReason.EMPTY_ANSWER
                return ""
            }
            content.take(800)
        } catch (e: Exception) {
            lastFailureReason = classifyFailure(e)
            ""
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Which plain-words failure a thrown exception is. From the REAL
     * exception type, never guessed: a timeout is a timeout, a DNS
     * or connect failure means the phone could not reach the
     * service at all, anything else that went wrong while reading
     * the answer means the answer came back unusable.
     */
    private fun classifyFailure(e: Exception): AiFailureReason = when (e) {
        is java.net.SocketTimeoutException -> AiFailureReason.TIMEOUT
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException -> AiFailureReason.NO_NETWORK
        is java.io.IOException -> AiFailureReason.NO_NETWORK
        else -> AiFailureReason.EMPTY_ANSWER
    }

    /**
     * Ask the model to draft a DynamicCapabilityDef JSON for the gap. The
     * schema is strict and the runtime validates + tests the draft — a bad
     * draft fails honestly, it never becomes a capability. Empty on any
     * failure or when unconfigured (falls back to the rule-based drafter).
     */
    override suspend fun draftCapabilityJson(
        gapDescription: String,
        available: List<IntelCapability>
    ): String {
        if (!configured) return fallback.draftCapabilityJson(gapDescription, available)
        return try {
            withContext(Dispatchers.IO) { postDraft(gapDescription, available) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun postDraft(gapDescription: String, available: List<IntelCapability>): String {
        var conn: HttpURLConnection? = null
        return try {
            val toolsJson = JSONArray(available.map { c ->
                JSONObject().apply {
                    put("id", c.id)
                    put("title", c.title)
                    put("inputs", JSONArray(c.inputs))
                    put("riskLevel", c.riskLevel)
                }
            }).toString()
            val schema = "Output STRICT JSON only, no markdown, no commentary: " +
                "{\"id\":\"dyn.<name>\",\"title\":\"\",\"detail\":\"\"," +
                "\"category\":\"DYNAMIC\",\"riskLevel\":\"READ\"," +
                "\"inputs\":[]," +
                "\"steps\":[{\"capabilityId\":\"\",\"args\":{},\"onFail\":\"abort\"}]," +
                "\"assertions\":[{\"kind\":\"ok\",\"value\":\"true\"}]," +
                "\"version\":1,\"origin\":\"ghost-intel/remote\"}. " +
                "Rules: use ONLY the capability ids listed below; " +
                "steps must be READ-risk only (anything else is dropped by validation); " +
                "args may use \${prev.data}, \${prev.summary}, \${input.NAME}; " +
                "id must start with \"dyn.\"."
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You draft executable capability definitions for Ghost Tap. $schema")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put(
                            "content", "Capability gap: $gapDescription\n\n" +
                                "Available tools:\n$toolsJson"
                        )
                    })
                })
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }.toString()
            conn = (URL(effectiveEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $trimmedApiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return ""
            val resp = conn.inputStream.bufferedReader().readText()
            val content = JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            val json = content
                .replace(Regex("```(?:json)?"), "")
                .trim()
                .let { t ->
                    val s = t.indexOf('{')
                    val e = t.lastIndexOf('}')
                    if (s >= 0 && e > s) t.substring(s, e + 1) else t
                }
            // Normalize through the real parser — unparseable means no draft.
            val def = DynamicCapabilityDef.fromJson(json) ?: return ""
            DynamicCapabilityDef.toJson(def)
        } catch (_: Exception) {
            ""
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Ask the model to draft a JavaScript probe for the gap, under the exact
     * ghost-host contract. The returned source is validated by ScriptRuntime
     * before it is even returned — an invalid draft is "", never a bad script.
     */
    override suspend fun draftScriptJson(
        gapDescription: String,
        available: List<IntelCapability>
    ): String {
        if (!configured) return fallback.draftScriptJson(gapDescription, available)
        return try {
            withContext(Dispatchers.IO) { postScriptDraft(gapDescription, available) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun postScriptDraft(
        gapDescription: String,
        available: List<IntelCapability>
    ): String {
        var conn: HttpURLConnection? = null
        return try {
            val toolsJson = JSONArray(available
                .filter { it.riskLevel == "READ" }
                .map { c ->
                    JSONObject().apply {
                        put("id", c.id)
                        put("title", c.title)
                        put("inputs", JSONArray(c.inputs))
                    }
                }).toString()
            val contract = "You write a JavaScript probe for Ghost Tap. " +
                "The script runs in a locked-down sandbox with ONE host object `ghost`: " +
                "ghost.run(capabilityId, argsJsonString) returns a JSON string " +
                "{ok: bool, summary: string, data: string}; " +
                "ghost.finding(title, text, source) records a finding; " +
                "ghost.log(msg) logs. No other APIs exist — no network, no Java, no files. " +
                "Output STRICT JSON only, no markdown: " +
                "{\"title\": \"...\", \"jsSource\": \"...\"}. " +
                "The jsSource MUST define: function main(args) { ... " +
                "return JSON.stringify({ok: bool, findings: [{title, text}], data: {}}); }. " +
                "Rules: call tools ONLY via ghost.run with a JSON-string second argument; " +
                "use ONLY the capability ids listed below."
            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", contract)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put(
                            "content", "Capability gap: $gapDescription\n\n" +
                                "Available READ-only tools:\n$toolsJson"
                        )
                    })
                })
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }.toString()
            conn = (URL(effectiveEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $trimmedApiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return ""
            val resp = conn.inputStream.bufferedReader().readText()
            val content = JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            val payload = try {
                JSONObject(content)
            } catch (_: Exception) {
                return ""
            }
            val title = payload.optString("title", "Scripted probe")
            val jsSource = payload.optString("jsSource", "")
            if (jsSource.isBlank()) return ""
            // Never hand the loop an invalid script — validate before returning.
            if (ScriptRuntime.validate(jsSource).isNotEmpty()) return ""
            ScriptDraft.toJson(title, jsSource)
        } catch (_: Exception) {
            ""
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun refinePlan(
        target: IntelTarget,
        steps: List<PlanStep>,
        knowledge: String
    ): List<PlanStep> {
        if (!configured) return fallback.refinePlan(target, steps, knowledge)
        return try {
            val refined = withContext(Dispatchers.IO) {
                postRefine(target, steps, knowledge)
            }
            refined ?: fallback.refinePlan(target, steps, knowledge)
        } catch (_: Exception) {
            fallback.refinePlan(target, steps, knowledge)
        }
    }

    private fun registryJson(): JSONArray = JSONArray(
        CapabilityRegistry.all().map { c ->
            JSONObject().apply {
                put("id", c.id)
                put("title", c.title)
                put("inputs", JSONArray(c.inputs))
                put("outputs", JSONArray(c.outputs))
                put("riskLevel", c.riskLevel)
                put("effects", c.effects)
                put("prerequisites", JSONArray(c.preconditions))
                put("readOnly", c.readOnly)
                put("verified", c.verified)
            }
        }
    )

    private fun postRefine(
        target: IntelTarget,
        steps: List<PlanStep>,
        knowledge: String
    ): List<PlanStep>? {
        var conn: HttpURLConnection? = null
        return try {
            val systemPrompt = "You are Ghost Tap's reasoning engine. " +
                "You ONLY choose from these capabilities (JSON follows). " +
                "Never invent capabilities. " +
                "Output strict JSON: {\"steps\":[{\"capabilityId\",\"args\":{},\"why\",\"auto\"}]," +
                "\"hypotheses\":[{\"text\",\"confidence\",\"supporting\":[],\"contradicting\":[]}]," +
                "\"notes\":\"\"}. " +
                "auto=true is allowed ONLY for READ-risk capabilities and only when the probe is safe. " +
                "Capabilities:\n" + registryJson().toString()

            val userContent = JSONObject().apply {
                put("target", JSONObject().apply {
                    put("kind", target.kind)
                    put("title", target.title)
                    put("text", target.text.take(4000))
                    put("markings", JSONArray(target.markings))
                    put("barcodes", JSONArray(target.barcodes))
                })
                put("currentSteps", JSONArray(steps.map { s ->
                    JSONObject().apply {
                        put("capabilityId", s.capabilityId)
                        put("args", JSONObject(s.args))
                        put("why", s.why)
                        put("auto", s.auto)
                        put("riskLevel", s.riskLevel)
                    }
                }))
                put("knowledge", knowledge.take(4000))
                put("hypotheses", JSONArray(fallback.hypotheses().map { h ->
                    JSONObject().apply {
                        put("text", h.text)
                        put("confidence", h.confidence)
                    }
                }))
            }.toString()

            val body = JSONObject().apply {
                put("model", MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
                    })
                })
                put("response_format", JSONObject().apply {
                    put("type", "json_object")
                })
            }.toString()

            conn = (URL(effectiveEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $trimmedApiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader().readText()
            parseResponse(resp, steps)
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** Lenient parse: OpenAI choices-shape first, then a bare {steps} root. */
    private fun parseResponse(resp: String, base: List<PlanStep>): List<PlanStep>? {
        return try {
            val root = JSONObject(resp)
            val payload: JSONObject = run {
                val choices = root.optJSONArray("choices")
                val content = choices?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")
                if (!content.isNullOrBlank()) {
                    try {
                        JSONObject(content)
                    } catch (_: Exception) {
                        null
                    }
                } else null
            } ?: root

            val arr = payload.optJSONArray("steps") ?: return null
            val known = CapabilityRegistry.all().associateBy { it.id }
            val baseById = base.associateBy { it.capabilityId }
            val out = mutableListOf<PlanStep>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("capabilityId", "")
                val cap = known[id] ?: continue // unknown id -> drop, never invent
                val args = mutableMapOf<String, String>()
                val jo = o.optJSONObject("args")
                if (jo != null) {
                    for (k in jo.keys()) args[k] = jo.optString(k, "")
                }
                val b = baseById[id]
                val why = o.optString("why", b?.why ?: cap.title).ifBlank { b?.why ?: cap.title }
                // Validation: auto survives ONLY for READ capabilities that were already auto.
                val wantAuto = o.optBoolean("auto", false)
                val auto = wantAuto && cap.riskLevel == "READ" && (b?.auto ?: false)
                out.add(PlanStep(id, args, why, auto, cap.riskLevel))
            }
            // Hypotheses from the model, confidence clamped.
            val hyps = mutableListOf<Hypothesis>()
            val harr = payload.optJSONArray("hypotheses")
            if (harr != null) {
                for (i in 0 until harr.length()) {
                    val h = harr.optJSONObject(i) ?: continue
                    val textRaw = h.optString("text", "")
                    if (textRaw.isBlank()) continue
                    val text = textRaw
                    hyps.add(
                        Hypothesis(
                            text = text,
                            confidence = h.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                            supporting = h.optJSONArray("supporting")?.let { a ->
                                (0 until a.length()).map { a.optString(it) }
                            } ?: emptyList(),
                            contradicting = h.optJSONArray("contradicting")?.let { a ->
                                (0 until a.length()).map { a.optString(it) }
                            } ?: emptyList()
                        )
                    )
                }
            }
            remoteHyps = hyps.take(12)
            notes = payload.optString("notes", "").take(1000)
            if (out.isEmpty()) null else out
        } catch (_: Exception) {
            null
        }
    }
}

/** Which reasoner the investigations screen should use. */
object ReasonerPicker {
    /**
     * Returns the remote engine when an API key is present (endpoint optional —
     * defaults to OpenAI), otherwise the SHARED local reasoner instance so
     * evidence accumulates across refine calls within an investigation.
     */
    fun pick(app: Application, local: RuleBasedReasoner): ReasoningProvider {
        val endpoint = IntelSettings.getEndpoint(app)
        val key = IntelSettings.getApiKey(app)
        val remote = RemoteLlmReasoner(endpoint, key, local)
        return if (remote.configured) remote else local
    }

    fun activeName(app: Application, local: RuleBasedReasoner): String =
        pick(app, local).name

    fun activeStatus(app: Application, local: RuleBasedReasoner): String =
        pick(app, local).statusLine
}

/** Optional remote-AI settings. Stored in SharedPreferences on this phone only. */
object IntelSettings {
    private const val PREFS = "intel_settings"
    private const val KEY_ENDPOINT = "remote_endpoint"
    private const val KEY_API_KEY = "remote_api_key"

    private fun prefs(app: Application) =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getEndpoint(app: Application): String =
        prefs(app).getString(KEY_ENDPOINT, "").orEmpty()

    fun getApiKey(app: Application): String =
        prefs(app).getString(KEY_API_KEY, "").orEmpty()

    fun setEndpoint(app: Application, v: String) {
        prefs(app).edit().putString(KEY_ENDPOINT, v).apply()
    }

    fun setApiKey(app: Application, v: String) {
        prefs(app).edit().putString(KEY_API_KEY, v).apply()
    }

    fun clear(app: Application) {
        prefs(app).edit().remove(KEY_ENDPOINT).remove(KEY_API_KEY).apply()
    }
}
