package com.ghosttap.core.vnic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provider boundary for Ghost Tap's live reasoning core. The provider may
 * propose hypotheses and select only capabilities that Ghost Tap actually
 * registered. It never owns memory or executes tools itself.
 */
data class VnicPlannedAction(
    val capabilityId: String,
    val args: Map<String, String> = emptyMap(),
    val why: String = ""
)

interface VnicReasoningProvider {
    val name: String
    val lastError: String? get() = null
    suspend fun hypotheses(observation: String, knownFacts: List<String>): List<String>
    suspend fun planFor(
        goal: String,
        capabilities: List<VnicCapabilityInfo>,
        freshEvidence: List<String> = emptyList()
    ): List<VnicPlannedAction>
}

/** Offline fallback. It is deliberately named as rules, never represented as AI. */
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

    override val name = "Built-in helper (offline rules; not AI)"

    override suspend fun hypotheses(observation: String, knownFacts: List<String>): List<String> {
        val obs = observation.lowercase()
        val out = mutableListOf<String>()
        if ("bluetooth" in obs || "ble" in obs || "gatt" in obs) {
            out += "This looks like a Bluetooth device. Find nearby Bluetooth devices, then inspect the services it exposes."
        }
        if ("wifi" in obs || "ssid" in obs || "network" in obs || "ip" in obs) {
            out += "This looks network-related. Discover the endpoint and inspect its available services."
        }
        if ("usb" in obs || "serial" in obs || "uart" in obs || "ch340" in obs) {
            out += "This looks like a USB/serial device. Inspect the attached interface and identify its protocol."
        }
        if ("nfc" in obs || "tag" in obs) {
            out += "This looks NFC-related. Read the tag and inspect its technology and records."
        }
        if ("printer" in obs || "print" in obs) {
            out += "This may be a printer. Identify its transport and supported command language before sending data."
        }
        if (knownFacts.isNotEmpty()) {
            out += "Prior notes (clues only; not fresh verification): ${knownFacts.take(3).joinToString("; ")}"
        }
        if (out.isEmpty()) out += "Unknown subject. No local rule identifies it; fresh research is required."
        return out
    }

    override suspend fun planFor(
        goal: String,
        capabilities: List<VnicCapabilityInfo>,
        freshEvidence: List<String>
    ): List<VnicPlannedAction> {
        val words = goal.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return emptyList()
        val expanded = words + words.mapNotNull { SYNONYMS[it] }
        return capabilities.filter { cap ->
            val hay = "${cap.id} ${cap.title} ${cap.description}".lowercase()
            val idWords = cap.id.lowercase().split(Regex("[._]")).toSet()
            idWords.any { it in expanded } || expanded.any { w -> hay.contains(w) }
        }.take(5).map { VnicPlannedAction(it.id, why = "Matched the live observation against this registered capability.") }
    }
}

/** The same OpenAI-compatible endpoint and key configured in Ghost Tap's THINKING ENGINE. */
data class VnicLlmConfig(
    val endpoint: String? = DEFAULT_OPENAI_ENDPOINT,
    val apiKey: String? = null,
    val model: String = "gpt-4o-mini",
    val connectTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 30000
) {
    companion object {
        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    }
}

/**
 * Resolves the configured provider for every request, not just when the
 * ViewModel is first created. Saving/changing the existing API key therefore
 * takes effect immediately without rebuilding Ghost Tap or reopening it.
 */
class ConfiguredVnicReasoner(
    private val configProvider: () -> VnicLlmConfig,
    private val local: VnicReasoningProvider = LocalRuleReasoner()
) : VnicReasoningProvider {
    @Volatile private var current: VnicReasoningProvider = local
    @Volatile private var selectedConfig: VnicLlmConfig? = null

    @Synchronized
    private fun select(): VnicReasoningProvider {
        val config = runCatching { configProvider() }.getOrElse { VnicLlmConfig() }
        if (config == selectedConfig) return current
        val selected = if (!config.apiKey.isNullOrBlank()) RemoteLlmReasoner(config) else local
        selectedConfig = config
        current = selected
        return selected
    }

    override val name: String get() = select().name
    override val lastError: String? get() = current.lastError

    override suspend fun hypotheses(observation: String, knownFacts: List<String>): List<String> =
        select().hypotheses(observation, knownFacts)

    override suspend fun planFor(
        goal: String,
        capabilities: List<VnicCapabilityInfo>,
        freshEvidence: List<String>
    ) : List<VnicPlannedAction> = select().planFor(goal, capabilities, freshEvidence)
}

/**
 * Live OpenAI reasoning. A failed remote request is surfaced as a failure;
 * it is NOT silently replaced by the local rules or by a cached answer.
 */
class RemoteLlmReasoner(private val config: VnicLlmConfig) : VnicReasoningProvider {
    override val name: String = "OpenAI ${config.model.ifBlank { "gpt-4o-mini" }}"

    @Volatile
    override var lastError: String? = null
        private set

    override suspend fun hypotheses(observation: String, knownFacts: List<String>): List<String> {
        val system = """You are the live intelligence engine inside Ghost Tap. Investigate the CURRENT observation, not just stored history. Treat prior memories as unverified clues that must be checked against this observation and fresh evidence. Identify what is unfamiliar, what evidence is missing, and the best next read/inspection actions. Do not claim you have connected to or controlled anything; only the execution layer can prove that. Distinguish facts, hypotheses, and unknowns. Return 1-5 concise bullet points, with concrete next steps."""
        val user = buildString {
            append("FRESH OBSERVATION:\n").append(observation.take(5000)).append("\n\n")
            if (knownFacts.isNotEmpty()) {
                append("PRIOR MEMORY / HISTORY (clues only, not verified for this observation):\n")
                append(knownFacts.take(8).joinToString("\n").take(3000)).append("\n\n")
            }
            append("Analyze this observation independently. What should Ghost Tap discover or inspect next?")
        }
        val text = complete(system, user)
            ?: return listOf("OPENAI REQUEST FAILED: ${lastError ?: "no usable response"}. No history-based answer was substituted.")
        return text.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotBlank() }.take(5)
            .ifEmpty { listOf("OpenAI returned an empty analysis. The subject remains unidentified.") }
    }

    override suspend fun planFor(
        goal: String,
        capabilities: List<VnicCapabilityInfo>,
        freshEvidence: List<String>
    ): List<VnicPlannedAction> {
        if (capabilities.isEmpty()) return emptyList()
        val registry = JSONArray(capabilities.map { cap ->
            JSONObject().apply {
                put("id", cap.id)
                put("title", cap.title)
                put("description", cap.description)
                put("risk", cap.riskLevel)
                put("verified_on_hardware", cap.verified)
                put("inputs", JSONArray(cap.inputs))
                put("outputs", JSONArray(cap.outputs))
                put("preconditions", JSONArray(cap.preconditions))
                put("effects", cap.effects)
            }
        })
        val system = """You are Ghost Tap's action planner. The registry below is the COMPLETE set of actions Ghost Tap can invoke in this cycle. Choose the most useful sequence for investigating the current unfamiliar subject. Prefer discovery/inspection capabilities first, then connection/interaction capabilities when fresh evidence supports them. Return strict JSON only: {"actions":[{"capability_id":"exact.id","args":{"declaredInput":"observed value"},"why":"brief reason"}],"reason":"brief explanation"}. Use only exact capability IDs and exact input names from the registry. For operation-wrapper actions, targetId means the exact device/subject identifier from the current observation, and choice means an exact runtime option (device, case, service, or item) present in the observation or fresh evidence. Fill arguments only with values explicitly present or directly supported by the current observation or fresh research. Never invent identifiers, addresses, ports, credentials, or device facts. Leave unknown arguments out; do not execute a capability whose required input is missing. Return an empty actions array if no registered action is relevant."""
        val evidence = freshEvidence.take(12).joinToString("\n").take(5000)
        val user = "CURRENT GOAL / OBSERVATION:\n${goal.take(5000)}\n\nFRESH OBSERVATIONS, RESEARCH AND AI ANALYSIS:\n${evidence.ifBlank { "No additional evidence returned yet." }}\n\nLIVE GHOST TAP CAPABILITY REGISTRY:\n$registry"
        val text = complete(system, user, jsonOnly = true) ?: return emptyList()
        val parsed = runCatching { JSONObject(text) }.getOrNull()
        val actions = parsed?.optJSONArray("actions")
        val allowed = capabilities.associateBy { it.id }
        if (actions != null) {
            val out = mutableListOf<VnicPlannedAction>()
            for (i in 0 until actions.length()) {
                val entry = actions.optJSONObject(i) ?: continue
                val id = entry.optString("capability_id").ifBlank { entry.optString("id") }
                val cap = allowed[id] ?: continue
                val argObject = entry.optJSONObject("args")
                val args = linkedMapOf<String, String>()
                if (argObject != null) {
                    val keys = argObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key in cap.inputs) {
                            val value = argObject.optString(key).trim()
                            if (value.isNotBlank()) args[key] = value
                        }
                    }
                }
                out += VnicPlannedAction(id, args, entry.optString("why").take(240))
            }
            return out.distinctBy { it.capabilityId }.take(5)
        }
        // Compatibility with older model output: exact IDs only, with no arguments.
        val returned = parsed?.optJSONArray("capability_ids")
        if (returned != null) return (0 until returned.length()).mapNotNull { i ->
            val id = returned.optString(i)
            if (id in allowed) VnicPlannedAction(id) else null
        }.distinctBy { it.capabilityId }.take(5)
        return emptyList()
    }

    private suspend fun complete(system: String, user: String, jsonOnly: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            lastError = null
            val key = config.apiKey?.trim().orEmpty()
            if (key.isBlank()) {
                lastError = "No OpenAI API key is saved in Ghost Tap's existing THINKING ENGINE settings."
                return@withContext null
            }
            val endpoint = config.endpoint?.trim().takeUnless { it.isNullOrBlank() }
                ?: VnicLlmConfig.DEFAULT_OPENAI_ENDPOINT
            var connection: HttpURLConnection? = null
            try {
                val messages = JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                }
                val body = JSONObject().apply {
                    put("model", config.model.ifBlank { "gpt-4o-mini" })
                    put("messages", messages)
                    put("temperature", 0.2)
                    put("max_tokens", 900)
                    if (jsonOnly) put("response_format", JSONObject().put("type", "json_object"))
                }.toString()
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = config.connectTimeoutMs
                    readTimeout = config.readTimeoutMs
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", VnicHttp.USER_AGENT)
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val responseStream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBody = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = runCatching {
                        JSONObject(responseBody).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    lastError = "OpenAI returned HTTP $code${if (message.isNotBlank()) ": ${message.take(240)}" else ""}."
                    return@withContext null
                }
                val content = runCatching {
                    JSONObject(responseBody).optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content").orEmpty()
                }.getOrDefault("").trim()
                if (content.isBlank()) {
                    lastError = "OpenAI returned HTTP $code but no message content."
                    return@withContext null
                }
                content
            } catch (e: Exception) {
                lastError = when (e) {
                    is java.net.SocketTimeoutException -> "The OpenAI request timed out."
                    is java.net.UnknownHostException -> "The phone could not resolve the OpenAI host."
                    is java.net.ConnectException -> "The phone could not connect to the OpenAI endpoint."
                    else -> "OpenAI request failed: ${e.javaClass.simpleName}: ${e.message ?: "no details"}."
                }
                null
            } finally {
                connection?.disconnect()
            }
        }
}
