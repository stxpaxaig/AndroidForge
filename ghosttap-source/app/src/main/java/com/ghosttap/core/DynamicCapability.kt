package com.ghosttap.core

import com.ghosttap.data.IntelDatabase
import com.ghosttap.data.Observation
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One step inside a runtime-authored capability: an existing registered
 * capability plus its arguments.
 *
 * Argument substitution (applied at run time):
 * - `${prev.data}` / `${prev.summary}` — the previous EXECUTED step's result.
 * - `${input.NAME}` — the value passed to the composite capability's own run.
 *
 * @param onFail "abort" stops the composite on failure; "continue" runs on.
 */
data class DynStep(
    val capabilityId: String,
    val args: Map<String, String> = emptyMap(),
    val onFail: String = "abort"
) {
    companion object {
        fun fromJson(o: JSONObject?): DynStep? {
            if (o == null) return null
            val id = o.optString("capabilityId", "")
            if (id.isBlank()) return null
            val args = mutableMapOf<String, String>()
            val jo = o.optJSONObject("args")
            if (jo != null) {
                for (k in jo.keys()) args[k] = jo.optString(k, "")
            }
            return DynStep(id, args, o.optString("onFail", "abort"))
        }

        fun toJson(s: DynStep): JSONObject = JSONObject().apply {
            put("capabilityId", s.capabilityId)
            put("args", JSONObject(s.args))
            put("onFail", s.onFail)
        }
    }
}

/**
 * A pass/fail check over the executed steps' results.
 * kinds: "ok" (every executed step ok), "dataContains", "summaryContains".
 */
data class DynAssertion(val kind: String, val value: String) {
    companion object {
        fun fromJson(o: JSONObject?): DynAssertion? {
            if (o == null) return null
            val kind = o.optString("kind", "")
            if (kind.isBlank()) return null
            return DynAssertion(kind, o.optString("value", ""))
        }

        fun toJson(a: DynAssertion): JSONObject = JSONObject().apply {
            put("kind", a.kind)
            put("value", a.value)
        }
    }
}

/**
 * A runtime-authored EXECUTABLE capability: a tested composition of existing
 * registered capabilities. This is the strongest honest self-extension the
 * phone supports — it cannot compile new Kotlin, so new functionality is
 * built from verified primitives and PROVEN by running them, never by
 * generating code and calling it done.
 */
data class DynamicCapabilityDef(
    val id: String,
    val title: String,
    val detail: String,
    val category: String = "DYNAMIC",
    /** Claimed risk; the runtime escalates it to the max of its parts. */
    val riskLevel: String,
    val inputs: List<String> = emptyList(),
    val steps: List<DynStep>,
    val assertions: List<DynAssertion> = emptyList(),
    val version: Int = 1,
    val origin: String = "ghost-intel"
) {
    companion object {
        fun fromJson(s: String): DynamicCapabilityDef? {
            return try {
                val o = JSONObject(s)
                val stepsArr = o.optJSONArray("steps") ?: return null
                val steps = (0 until stepsArr.length())
                    .mapNotNull { DynStep.fromJson(stepsArr.optJSONObject(it)) }
                val assArr = o.optJSONArray("assertions")
                val assertions = if (assArr != null) {
                    (0 until assArr.length())
                        .mapNotNull { DynAssertion.fromJson(assArr.optJSONObject(it)) }
                } else emptyList()
                DynamicCapabilityDef(
                    id = o.optString("id", ""),
                    title = o.optString("title", ""),
                    detail = o.optString("detail", ""),
                    category = o.optString("category", "DYNAMIC"),
                    riskLevel = o.optString("riskLevel", "READ"),
                    inputs = o.optJSONArray("inputs")?.let { a ->
                        (0 until a.length()).map { a.optString(it) }
                    } ?: emptyList(),
                    steps = steps,
                    assertions = assertions,
                    version = o.optInt("version", 1),
                    origin = o.optString("origin", "ghost-intel")
                ).takeIf { it.id.isNotBlank() && it.title.isNotBlank() && it.steps.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }

        fun toJson(def: DynamicCapabilityDef): String = JSONObject().apply {
            put("id", def.id)
            put("title", def.title)
            put("detail", def.detail)
            put("category", def.category)
            put("riskLevel", def.riskLevel)
            put("inputs", JSONArray(def.inputs))
            put("steps", JSONArray(def.steps.map { DynStep.toJson(it) }))
            put("assertions", JSONArray(def.assertions.map { DynAssertion.toJson(it) }))
            put("version", def.version)
            put("origin", def.origin)
        }.toString()
    }
}

/** What the test run proved. A report is evidence, not a promise. */
data class TestReport(
    val passed: Boolean,
    val stepResults: List<OpResult>,
    val notes: List<String>
)

/**
 * Validates, tests, and registers runtime-authored capabilities.
 *
 * SAFETY INVARIANTS (enforced here, not trusted to the author):
 * - A composite NEVER claims a lower risk than its most dangerous part —
 *   the risk is escalated to the max of its steps.
 * - Test runs execute READ steps only. Steps that change state are SKIPPED
 *   during the test (noted plainly); they run later only through the normal
 *   one-tap approval gate, because the composite carries the escalated risk.
 * - verified=true only when every step actually ran AND passed.
 */
object DynamicCapabilityRuntime {

    private val prevData = Regex("\\$\\{prev\\.data\\}")
    private val prevSummary = Regex("\\$\\{prev\\.summary\\}")
    private val inputRef = Regex("\\$\\{input\\.([A-Za-z0-9_]+)\\}")

    private fun riskRank(r: String): Int = when (r.uppercase()) {
        "READ" -> 0
        "CONFIGURE" -> 1
        "WRITE" -> 2
        "CONTROL" -> 3
        else -> -1
    }

    /** The risk this composite REALLY is: the max of its parts. */
    fun escalatedRisk(def: DynamicCapabilityDef): String {
        val ranks = def.steps.mapNotNull { s ->
            CapabilityRegistry.get(s.capabilityId)?.riskLevel?.let { riskRank(it) }
        }
        val max = (ranks + listOf(riskRank(def.riskLevel))).maxOrNull() ?: 0
        return when {
            max >= 3 -> "CONTROL"
            max == 2 -> "WRITE"
            max == 1 -> "CONFIGURE"
            else -> "READ"
        }
    }

    /**
     * Structural checks. Returns human-readable errors; empty = clean.
     * Risk under-claiming is NOT an error — it is escalated to the max.
     */
    fun validate(
        def: DynamicCapabilityDef,
        registry: CapabilityRegistry = CapabilityRegistry
    ): List<String> {
        val errors = mutableListOf<String>()
        if (def.id.isBlank()) errors.add("missing id")
        if (def.title.isBlank()) errors.add("missing title")
        if (def.steps.isEmpty()) errors.add("no steps")
        if (riskRank(def.riskLevel) < 0) {
            errors.add("riskLevel must be READ, CONFIGURE, WRITE, or CONTROL")
        }
        if (def.id.isNotBlank() && registry.get(def.id) != null) {
            errors.add("id '${def.id}' is already registered")
        }
        for ((i, s) in def.steps.withIndex()) {
            if (registry.get(s.capabilityId) == null) {
                errors.add("step ${i + 1}: unknown capability '${s.capabilityId}'")
            }
            if (s.onFail != "abort" && s.onFail != "continue") {
                errors.add("step ${i + 1}: onFail must be 'abort' or 'continue'")
            }
        }
        for ((i, a) in def.assertions.withIndex()) {
            if (a.kind != "ok" && a.kind != "dataContains" && a.kind != "summaryContains") {
                errors.add("assertion ${i + 1}: unknown kind '${a.kind}'")
            }
        }
        return errors
    }

    /**
     * Prove it: run the steps through the REAL engine, check the assertions.
     * Only READ steps execute — anything that changes state is skipped and
     * noted, and verified stays false until a human runs the full thing.
     */
    suspend fun test(def: DynamicCapabilityDef, engine: IntelEngine): TestReport {
        val notes = mutableListOf<String>()
        val results = mutableListOf<OpResult>()
        var prev: OpResult? = null
        var skipped = 0
        for ((i, step) in def.steps.withIndex()) {
            val cap = CapabilityRegistry.get(step.capabilityId)
            if (cap == null) {
                notes.add("step ${i + 1}: '${step.capabilityId}' vanished from the registry — aborting test")
                return TestReport(false, results, notes)
            }
            if (cap.riskLevel != "READ") {
                skipped++
                notes.add(
                    "step ${i + 1} ('${cap.title}') changes something — NOT run during the test. " +
                        "It will need your tap when the capability is used."
                )
                continue
            }
            val resolved = step.args.mapValues { (_, v) -> substitute(v, emptyMap(), prev) }
            val res = try {
                engine.run(step.capabilityId, resolved)
            } catch (e: Exception) {
                OpResult(false, "Crashed during test: ${e.message ?: "unknown error"}")
            }
            results.add(res)
            prev = res
            notes.add(
                "step ${i + 1} '${step.capabilityId}': " +
                    (if (res.ok) "OK" else "FAIL") + " — ${res.summary.take(120)}"
            )
            if (!res.ok && step.onFail != "continue") break
        }
        var passed = results.isNotEmpty() && results.all { it.ok }
        for (a in def.assertions) {
            val okA = when (a.kind) {
                "ok" -> results.all { it.ok }
                "dataContains" -> results.any { it.data.contains(a.value) }
                "summaryContains" -> results.any { it.summary.contains(a.value) }
                else -> {
                    notes.add("unknown assertion kind '${a.kind}' — ignored")
                    true
                }
            }
            if (!okA) {
                notes.add("assertion failed: ${a.kind} '${a.value.take(60)}'")
                passed = false
            }
        }
        if (results.isEmpty()) {
            notes.add("nothing was testable — no READ steps ran")
            passed = false
        }
        if (skipped > 0) {
            notes.add("$skipped step(s) skipped (need your tap) — verified stays false until run in full")
        }
        return TestReport(passed, results, notes)
    }

    /**
     * Register the definition as a REAL registry capability — but ONLY with a
     * fresh passing [report] in hand (avoids running real operations twice).
     * The composite carries the ESCALATED risk, so the loop's approval gate
     * treats it exactly as seriously as its most dangerous part.
     */
    suspend fun register(
        def: DynamicCapabilityDef,
        engine: IntelEngine,
        report: TestReport,
        db: IntelDatabase?
    ): Boolean {
        if (!report.passed) return false
        if (validate(def).isNotEmpty()) return false
        val risk = escalatedRisk(def)
        val fullyTested = report.stepResults.size == def.steps.size
        val registered = IntelCapability(
            id = def.id,
            title = def.title,
            detail = def.detail,
            category = def.category,
            inputs = def.inputs,
            outputs = listOf("composite result"),
            preconditions = listOf("Auto-built from existing tools; tested before registration."),
            readOnly = risk == "READ",
            riskLevel = risk,
            effects = def.detail +
                " (Auto-built composite of ${def.steps.size} existing tools; " +
                if (fullyTested) "tested end-to-end.)" else "partially tested — some steps need your tap.)",
            verified = fullyTested, // true ONLY when every step actually ran and passed
            sourceMethod = "DynamicCapabilityRuntime:${def.id} v${def.version}",
            run = { args ->
                runComposite(def, engine, args)
            }
        )
        CapabilityRegistry.register(registered)
        if (db != null) {
            try {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = "",
                        ts = System.currentTimeMillis(),
                        kind = "capability",
                        title = "Capability registered: ${def.id}",
                        detail = ("Tested ${report.stepResults.size}/${def.steps.size} steps, " +
                            "risk $risk, verified=$fullyTested. " +
                            report.notes.joinToString(" | ").take(900) +
                            "\nDefinition: " + DynamicCapabilityDef.toJson(def).take(900)).take(2000),
                        provenance = "OBSERVED",
                        confidence = 1.0
                    )
                )
            } catch (_: Exception) {
            }
        }
        return true
    }

    /** Execute a registered composite through the real engine. */
    private suspend fun runComposite(
        def: DynamicCapabilityDef,
        engine: IntelEngine,
        args: Map<String, String>
    ): OpResult {
        return try {
            var prev: OpResult? = null
            var ran = 0
            for (step in def.steps) {
                val cap = CapabilityRegistry.get(step.capabilityId)
                    ?: return OpResult(false, "Step '${step.capabilityId}' is no longer registered.")
                val resolved = step.args.mapValues { (_, v) -> substitute(v, args, prev) }
                val res = try {
                    engine.run(step.capabilityId, resolved)
                } catch (e: Exception) {
                    OpResult(false, "Step '${step.capabilityId}' crashed: ${e.message ?: "unknown error"}")
                }
                ran++
                prev = res
                if (!res.ok && step.onFail != "continue") {
                    return OpResult(
                        false,
                        "Stopped at step '$ran/${def.steps.size}' (${step.capabilityId}): ${res.summary}",
                        res.data.take(800)
                    )
                }
            }
            OpResult(true, "Composite '${def.title}' ran $ran step(s).", prev?.data.orEmpty().take(1200))
        } catch (e: Exception) {
            OpResult(false, "Composite '${def.id}' crashed: ${e.message ?: "unknown error"}")
        }
    }

    private fun substitute(
        raw: String,
        args: Map<String, String>,
        prev: OpResult?
    ): String {
        var s = raw
        s = prevData.replace(s, prev?.data.orEmpty())
        s = prevSummary.replace(s, prev?.summary.orEmpty())
        s = inputRef.replace(s) { m -> args[m.groupValues[1]].orEmpty() }
        return s
    }
}
