package com.ghosttap.core

import com.ghosttap.data.IntelDatabase
import com.ghosttap.data.Observation
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.Function
import org.mozilla.javascript.Parser
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.ast.AstRoot
import org.mozilla.javascript.ast.FunctionNode
import java.util.UUID

/**
 * A runtime-authored EXECUTABLE script capability: genuine JavaScript logic
 * (loops, branching, string/data shaping) running in a locked-down Rhino
 * interpreter over the existing capability surface.
 *
 * This is the strongest honest self-extension the phone supports for NEW
 * logic: the device cannot compile code (see docs/BARRIER_AUDIT.md), so
 * new behavior arrives as interpreted JS, never as generated native code.
 *
 * SANDBOX (enforced here, never trusted to the script):
 * - Rhino in pure interpreter mode (optimizationLevel = -1): no bytecode
 *   is ever generated.
 * - `initSafeStandardObjects()`: the dangerous top-levels (Packages,
 *   java.*, getClass, …) are not even installed.
 * - ClassShutter blocks ALL Java class access — belt and braces.
 * - Instruction-count budget aborts runaway scripts.
 * - Exactly ONE host object, `ghost`, with exactly three functions:
 *   `run(capabilityId, argsJson)` → JSON string {ok, summary, data};
 *   `finding(title, text, source)` → records to IntelDb;
 *   `log(msg)` → goes to the test/run log.
 *
 * CONTRACT: the script must define `function main(args)` returning a JSON
 * string `{ok: bool, findings: [{title, text}], data: {...}}`.
 *
 * SAFETY INVARIANTS:
 * - validate(): syntax + contract, without executing anything.
 * - test(): executes in the sandbox with READ-only ghost.run — a script
 *   that wants a non-READ tool gets a refusal, not the tool.
 * - register(): only with a passing test report; risk is the max of the
 *   declared risk and every capability the script references — never
 *   de-escalated.
 */
data class ScriptDef(
    val id: String,
    val title: String,
    val description: String,
    val jsSource: String,
    /** Declared risk; registration escalates to the max referenced risk. */
    val risk: String,
    val version: Int = 1,
    val origin: String = "ghost-intel/script",
    val createdAt: Long = System.currentTimeMillis(),
    /** Plain-English report from the test run that proved this script. */
    val testReport: String = ""
) {
    companion object {
        fun toJson(d: ScriptDef): String = JSONObject().apply {
            put("id", d.id)
            put("title", d.title)
            put("description", d.description)
            put("jsSource", d.jsSource)
            put("risk", d.risk)
            put("version", d.version)
            put("origin", d.origin)
            put("createdAt", d.createdAt)
            put("testReport", d.testReport)
        }.toString()

        fun fromJson(s: String): ScriptDef? {
            return try {
                val o = JSONObject(s)
                ScriptDef(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    description = o.optString("description", ""),
                    jsSource = o.optString("jsSource"),
                    risk = o.optString("risk", "READ"),
                    version = o.optInt("version", 1),
                    origin = o.optString("origin", "ghost-intel/script"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    testReport = o.optString("testReport", "")
                ).takeIf { it.id.isNotBlank() && it.jsSource.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** The JSON shape the loop's reasoners produce when drafting a script. */
object ScriptDraft {
    fun parse(json: String): Pair<String, String>? {
        return try {
            val o = JSONObject(json)
            val src = o.optString("jsSource", "")
            if (src.isBlank()) null
            else o.optString("title", "Scripted probe").ifBlank { "Scripted probe" } to src
        } catch (_: Exception) {
            null
        }
    }

    fun toJson(title: String, jsSource: String): String = JSONObject().apply {
        put("title", title)
        put("jsSource", jsSource)
    }.toString()
}

/** What the sandbox test run proved. A report is evidence, not a promise. */
data class ScriptTestReport(
    val passed: Boolean,
    val notes: List<String>,
    val resultJson: String
)

/** Outcome of one sandboxed script execution. */
data class ScriptResult(
    val ok: Boolean,
    val resultJson: String,
    val logs: List<String>,
    val error: String?
)

object ScriptRuntime {

    /** Hard instruction budget per execution — runaway scripts die here. */
    const val MAX_INSTRUCTIONS = 200_000
    /** Scripts over this size are rejected at validation. */
    const val MAX_SOURCE_CHARS = 200_000

    /**
     * Investigation currently being worked, so script findings land on the
     * right timeline. Set by IntelViewModel around the loop; "" = global.
     */
    @Volatile
    var currentInvestigationId: String = ""

    private class SandboxContextFactory : ContextFactory() {
        override fun makeContext(): Context {
            return super.makeContext().apply {
                // Interpreter only: Rhino never generates bytecode.
                optimizationLevel = -1
                languageVersion = Context.VERSION_ES6
                // No Java access, period.
                setClassShutter(ClassShutter { false })
                instructionObserverThreshold = 20_000
            }
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (instructionCount > MAX_INSTRUCTIONS) {
                throw EvaluatorException(
                    "Script exceeded the $MAX_INSTRUCTIONS-instruction budget — stopped."
                )
            }
        }
    }

    private fun riskRank(r: String): Int = when (r.uppercase()) {
        "READ" -> 0
        "CONFIGURE" -> 1
        "WRITE" -> 2
        "CONTROL" -> 3
        else -> -1
    }

    /**
     * Syntax + contract check WITHOUT executing anything. Returns
     * human-readable errors; empty = clean. The definitive contract proof
     * still happens at test time (main must exist AND return valid JSON).
     */
    fun validate(jsSource: String): List<String> {
        val errors = mutableListOf<String>()
        if (jsSource.isBlank()) {
            errors.add("empty script")
            return errors
        }
        if (jsSource.length > MAX_SOURCE_CHARS) {
            errors.add("script too large (${jsSource.length} chars, cap $MAX_SOURCE_CHARS)")
            return errors
        }
        val root: AstRoot = try {
            val env = CompilerEnvirons().apply { languageVersion = Context.VERSION_ES6 }
            Parser(env).parse(jsSource, "<validate>", 1)
        } catch (e: Exception) {
            errors.add("syntax error: ${e.message?.take(200) ?: e.javaClass.simpleName}")
            return errors
        }
        var hasMain = false
        // Non-executing top-level scan: AstRoot's direct children are the
        // top-level statements (getFirstChild/getNext are the stable Node API).
        var child: org.mozilla.javascript.Node? = root.firstChild
        while (child != null) {
            if (child is FunctionNode && child.name == "main" &&
                child.functionType == FunctionNode.FUNCTION_STATEMENT
            ) {
                hasMain = true
                break
            }
            child = child.next
        }
        // Fallback: textual pre-check (the sandbox test is the real gate).
        if (!hasMain) {
            hasMain = Regex("""(?m)^\s*function\s+main\s*\(""").containsMatchIn(jsSource)
        }
        if (!hasMain) errors.add("contract: script must define a top-level function main(args)")
        // Belt and braces: the shutter blocks these anyway, but a script that
        // even mentions them is not welcome.
        for (banned in listOf("Packages", "JavaAdapter", "getClass(")) {
            if (jsSource.contains(banned)) errors.add("contract: '$banned' is not allowed in scripts")
        }
        return errors
    }

    /**
     * The risk this script REALLY is: the max of its declared risk and every
     * capability id it references via ghost.run("…"). Never de-escalated.
     */
    fun effectiveRisk(def: ScriptDef): String {
        val declared = riskRank(def.risk).coerceAtLeast(0)
        val referenced = Regex("""ghost\.run\(\s*["']([^"']+)["']""")
            .findAll(def.jsSource)
            .map { it.groupValues[1] }
            .mapNotNull { CapabilityRegistry.get(it)?.riskLevel }
            .maxOfOrNull { riskRank(it) } ?: 0
        return when (maxOf(declared, referenced)) {
            3 -> "CONTROL"
            2 -> "WRITE"
            1 -> "CONFIGURE"
            else -> "READ"
        }
    }

    /**
     * Prove it: execute the script in the sandbox and check the contract —
     * main(args) must return a JSON string containing an "ok" field.
     * ghost.run is READ-only during the test: non-READ tools are refused
     * with an honest message, never executed.
     */
    suspend fun test(
        def: ScriptDef,
        engine: IntelEngine,
        db: IntelDatabase?
    ): ScriptTestReport {
        val notes = mutableListOf<String>()
        val syntaxErrors = validate(def.jsSource)
        if (syntaxErrors.isNotEmpty()) {
            return ScriptTestReport(
                false,
                listOf("validate failed: " + syntaxErrors.joinToString("; ")),
                ""
            )
        }
        notes.add("validate: syntax + main(args) contract OK")
        val res = execute(
            def.jsSource, engine, db, currentInvestigationId, "{}", testMode = true
        )
        notes.addAll(res.logs.map { "log: $it" })
        if (res.error != null) {
            notes.add("execution failed: ${res.error}")
            return ScriptTestReport(false, notes, res.resultJson)
        }
        val parsed = try {
            JSONObject(res.resultJson)
        } catch (_: Exception) {
            null
        }
        if (parsed == null || !parsed.has("ok")) {
            notes.add("contract: main(args) must return a JSON string containing an 'ok' field")
            return ScriptTestReport(false, notes, res.resultJson)
        }
        val ok = parsed.optBoolean("ok", false)
        notes.add("executed in the sandbox; main returned ok=$ok")
        return ScriptTestReport(true, notes, res.resultJson)
    }

    /**
     * Register the script as a REAL registry capability — but ONLY with a
     * passing [report] in hand. The definition is persisted as an
     * observation (kind="script_def") so it survives restarts; pass
     * persist=false when re-registering an already-persisted definition.
     */
    suspend fun register(
        def: ScriptDef,
        engine: IntelEngine,
        db: IntelDatabase?,
        report: ScriptTestReport,
        persist: Boolean = true
    ): Boolean {
        if (!report.passed) return false
        if (validate(def.jsSource).isNotEmpty()) return false
        val regId = "script.${def.id}"
        if (CapabilityRegistry.get(regId) != null) return false
        val risk = effectiveRisk(def)
        val cap = IntelCapability(
            id = regId,
            title = def.title,
            detail = def.description.ifBlank { "Scripted capability." } +
                " JavaScript executed in a locked-down sandbox — it can only " +
                "call existing Ghost Tap tools through ghost.run, and cannot " +
                "touch Java or Android APIs.",
            category = "SCRIPT",
            inputs = listOf("argsJson"),
            outputs = listOf("script result JSON {ok, findings, data}"),
            preconditions = listOf("Tested in the sandbox before registration."),
            readOnly = risk == "READ",
            riskLevel = risk,
            effects = "Runs the scripted logic '${def.title}'. " +
                if (risk == "READ") "It only uses look-only tools."
                else "It may use $risk-risk tools — this was approved when the script was proposed.",
            verified = true, // ONLY reachable with a passing report in hand
            sourceMethod = "ScriptRuntime:$regId v${def.version}",
            run = { args ->
                val res = execute(
                    def.jsSource, engine, db, currentInvestigationId,
                    args["argsJson"] ?: args["args"] ?: "{}",
                    testMode = false
                )
                if (res.error != null) {
                    OpResult(
                        false,
                        "Script '$regId' failed: ${res.error}",
                        res.logs.joinToString("\n").take(800)
                    )
                } else {
                    OpResult(
                        res.ok,
                        "Script '${def.title}' finished (ok=${res.ok}).",
                        res.resultJson.take(1500)
                    )
                }
            }
        )
        CapabilityRegistry.register(cap)
        if (persist && db != null) {
            try {
                val withReport = def.copy(
                    testReport = report.notes.joinToString(" | ").take(900)
                )
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = "",
                        ts = System.currentTimeMillis(),
                        kind = "script_def",
                        title = "Script saved: $regId",
                        detail = ScriptDef.toJson(withReport).take(8000),
                        provenance = "OBSERVED",
                        confidence = 1.0
                    )
                )
            } catch (_: Exception) {
            }
        }
        return true
    }

    /**
     * Execute a script in the sandbox. testMode=true makes ghost.run
     * READ-only (non-READ tools are refused, never executed).
     */
    fun execute(
        jsSource: String,
        engine: IntelEngine,
        db: IntelDatabase?,
        investigationId: String,
        argsJson: String,
        testMode: Boolean,
        onLog: (String) -> Unit = {}
    ): ScriptResult {
        val logs = mutableListOf<String>()
        fun log(m: String) {
            logs.add(m.take(300))
            try {
                onLog(m)
            } catch (_: Exception) {
            }
        }
        val factory = SandboxContextFactory()
        val cx = factory.enterContext()
        return try {
            val scope = cx.initSafeStandardObjects()
            val ghost = GhostHost(engine, db, investigationId, testMode, ::log)
            (scope as ScriptableObject).defineProperty(
                "ghost", ghost, ScriptableObject.READONLY
            )
            ghost.parentScope = scope
            cx.evaluateString(scope, jsSource, "<ghost-script>", 1, null)
            val mainProp = scope.get("main", scope)
            if (mainProp !is Function) {
                return ScriptResult(
                    false, "", logs,
                    "contract: 'main' is not a function after evaluation"
                )
            }
            val raw = try {
                mainProp.call(cx, scope, scope, arrayOf<Any>(argsJson))
            } catch (e: Exception) {
                return ScriptResult(
                    false, "", logs,
                    "main(args) threw: ${e.message?.take(250) ?: e.javaClass.simpleName}"
                )
            }
            val resultStr = raw?.toString().orEmpty()
            val parsed = try {
                JSONObject(resultStr)
            } catch (_: Exception) {
                null
            }
            if (parsed == null || !parsed.has("ok")) {
                return ScriptResult(
                    false, resultStr, logs,
                    "contract: main(args) must return a JSON string containing an 'ok' field"
                )
            }
            // Findings the script returned ride along in the result JSON —
            // the caller (loop / capability run) records them.
            ScriptResult(parsed.optBoolean("ok", false), resultStr, logs, null)
        } catch (e: Exception) {
            ScriptResult(
                false, "", logs,
                "script error: ${e.message?.take(250) ?: e.javaClass.simpleName}"
            )
        } finally {
            Context.exit()
        }
    }

    /**
     * The single host object scripts can see. Exactly three functions;
     * everything else is unreachable (safe standard objects + ClassShutter).
     */
    private class GhostHost(
        private val engine: IntelEngine,
        private val db: IntelDatabase?,
        private val investigationId: String,
        private val testMode: Boolean,
        private val onLog: (String) -> Unit
    ) : ScriptableObject() {

        override fun getClassName(): String = "ghost"

        init {
            put("run", this, RunFn())
            put("finding", this, FindingFn())
            put("log", this, LogFn())
        }

        private fun arg(args: Array<out Any>, i: Int): String =
            args.getOrNull(i)?.toString().orEmpty()

        /** ghost.run(capabilityId, argsJson) → JSON string {ok, summary, data}. */
        private inner class RunFn : BaseFunction() {
            override fun call(
                cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>
            ): Any {
                val capabilityId = arg(args, 0)
                val rawArgs = arg(args, 1).ifBlank { "{}" }
                if (testMode) {
                    val risk = CapabilityRegistry.get(capabilityId)?.riskLevel
                    if (risk != null && risk != "READ") {
                        return JSONObject().apply {
                            put("ok", false)
                            put(
                                "summary",
                                "Skipped during the test — '$capabilityId' is $risk-risk. " +
                                    "The script was proven with look-only tools."
                            )
                            put("data", "")
                        }.toString()
                    }
                }
                val argMap = try {
                    val o = JSONObject(rawArgs)
                    buildMap<String, String> {
                        for (k in o.keys()) put(k, o.optString(k, ""))
                    }
                } catch (_: Exception) {
                    emptyMap()
                }
                val result = try {
                    runBlocking { engine.run(capabilityId, argMap) }
                } catch (e: Exception) {
                    OpResult(false, "ghost.run crashed: ${e.message ?: "unknown error"}")
                }
                return JSONObject().apply {
                    put("ok", result.ok)
                    put("summary", result.summary)
                    put("data", result.data)
                }.toString()
            }
        }

        /**
         * ghost.finding(title, text, source) — records a script finding.
         * Provenance is ASSUMED: it is the script's interpretation of real
         * tool output, not a verified fact.
         */
        private inner class FindingFn : BaseFunction() {
            override fun call(
                cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>
            ): Any {
                val title = arg(args, 0).take(120)
                val text = arg(args, 1).take(1500)
                val source = arg(args, 2).take(120)
                onLog("finding: ${title.ifBlank { "(untitled)" }}")
                val dbRef = db
                if (dbRef != null && (title.isNotBlank() || text.isNotBlank())) {
                    try {
                        runBlocking {
                            dbRef.observationDao().insert(
                                Observation(
                                    id = UUID.randomUUID().toString(),
                                    investigationId = investigationId,
                                    ts = System.currentTimeMillis(),
                                    kind = "note",
                                    title = "Script finding: ${title.ifBlank { "untitled" }}",
                                    detail = (if (source.isNotBlank()) "Source: $source\n" else "") + text,
                                    provenance = "ASSUMED",
                                    confidence = 0.7
                                )
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
                return true
            }
        }

        /** ghost.log(msg) — goes to the test/run log. */
        private inner class LogFn : BaseFunction() {
            override fun call(
                cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>
            ): Any {
                onLog(arg(args, 0).take(300))
                return ""
            }
        }
    }
}
