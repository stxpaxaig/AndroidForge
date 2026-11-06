package com.ghosttap.core.vnic

import kotlin.math.min

/**
 * VNIC cognition pipeline — faithful Kotlin port of the VNIC Python
 * `VirtualNeuralIntelligenceCore.process()` (vnic.py), extended with the
 * pieces the Python core did not have: live research, capability mapping,
 * procedure development, an execution bridge, and explicit execution states.
 *
 * The Python loop, as written:
 * observe -> interpret -> reason -> decide -> plan -> act -> evaluate -> learn
 * - interpret: tokens, 12 most-common keywords, intent (question if "?" is
 *   present; instruction if it matches build|create|find|investigate|
 *   analyze|explain|compare|run|remember; else statement).
 * - reason: mesh.activate(text); recall(keywords, 8); graph(keywords[:6], 5).
 * - recall supplies context, but does not gate a new observation's research;
 *   fresh user/device observations always use the live research and reasoning
 *   paths. Explicit context-only writes are the sole memory-only path.
 * - learn: LearningEngine.feedback(reward) — loss = 0.92*loss + 0.08*(1-reward),
 *   mesh.reinforce((reward-0.5)*2), episodic feedback record.
 * - every cycle ends with activation decay (*= 0.985).
 *
 * The VNIC extensions (directive requirements, clearly marked):
 * - research_required no longer dead-ends: the live [VnicResearcher] runs,
 *   findings are stored as semantic memories, and a finding-backed cycle
 *   becomes answer_from_context.
 * - every fresh observation is planned against the complete runtime
 *   capability map and, when an executor is attached, ACTs through the real
 *   [VnicExecutor].
 * - the full 13-phase loop OBSERVE -> IDENTIFY -> INTERPRET -> RESEARCH ->
 *   UNDERSTAND -> PLAN -> SELECT -> DEVELOP -> ACT -> VERIFY -> LEARN ->
 *   RETAIN -> IMPROVE is traced in [VnicCycleResult.phases].
 *
 * Honesty rules baked in:
 * - UNKNOWN stays UNKNOWN: when nothing is known and research finds
 *   nothing, the result says so at low confidence. No fabricated identity.
 * - A dispatched command is NOT proof of a response: [VnicExecutionState]
 *   tracks ATTEMPTED separately from CONNECTED and VERIFIED, and only a
 *   response whose content matches the expected outcome reaches VERIFIED.
 * - Research findings are leads (verified=false) until on-device evidence
 *   corroborates them.
 * - Reference-profile values ([VnicReferenceProfile]) are NEVER presented
 *   as measured telemetry. Runtime measurements live in [VnicTelemetry]
 *   and in [VnicLearning.metrics], collected from real runs only.
 */

/** Explicit execution states. A command that was sent but never answered stays ATTEMPTED. */
enum class VnicExecutionState {
    IDENTIFIED, RESEARCHED, UNDERSTOOD, ATTEMPTED, CONNECTED, VERIFIED, FAILED,
    /** The target is controllable AND the connection is still held — the closed-loop end state. */
    CONTROLLABLE
}

data class VnicObservation(
    val subject: String,
    val text: String,
    val source: String = "user",
    val confidence: Double = 0.5
)

data class VnicInterpretation(
    val tokens: List<String>,
    val keywords: List<String>,
    val intent: String,
    val length: Int
)

data class VnicCycleResult(
    val subject: String,
    val phases: List<String>,
    /** The Python decision action: store_context | answer_from_context | research_required. */
    val action: String,
    val confidence: Double,
    val hypotheses: List<String>,
    val findings: List<VnicFinding>,
    val plan: List<String>,
    val executionState: VnicExecutionState,
    val executionEvidence: String,
    val neuralActivity: VnicActivationResult?,
    val interpretation: VnicInterpretation?,
    val learned: List<String>,
    val durationMs: Long,
    /** Bring-up outcome when the cycle ran one (null when no bring-up was attempted). */
    val bringUp: VnicBringUpResult? = null,
    /** Controls the bring-up mapped (empty when no bring-up ran or none were mapped). */
    val controlsMapped: List<String> = emptyList(),
    /** AI-selected values, filtered to each capability's declared input names. */
    val actionArguments: Map<String, Map<String, String>> = emptyMap()
)

/**
 * The cognition pipeline. Pure Kotlin: all device access goes through the
 * injected [executor] and the injected capability list, so the pipeline
 * itself is unit-testable with fakes.
 *
 * Package 7 (E-11 / F-18) — the allow-list question, decided from the
 * code: the old VnicToolRegistry was constructed empty and `process()`
 * never called it, because execution already goes through the
 * allow-listed ports — [VnicExecutor] (which runs only ids registered
 * in the CapabilityRegistry) and [VnicBringUpPort]. The registry was
 * dead code duplicating that guarantee, so it was REMOVED, not
 * populated: the CapabilityRegistry is the one allow-list, and an
 * unregistered id resolves to null there and is refused by
 * [VnicExecution]/validation instead of improvised.
 */
/**
 * Reference profile, copied verbatim from `vnic-source/reference_analytics.json`.
 * REFERENCE DATA ONLY — the numbers describe the VNIC design reference
 * (8.7B neurons, 87.4B synapses, 42.6 TB memory, 99.7% performance index).
 * They are NOT measured telemetry of this device, this build, or any run.
 * Nothing in this codebase may present them as measured. Runtime numbers
 * come only from [VnicTelemetry] and [VnicLearning.metrics].
 */
object VnicReferenceProfile {
    const val STATUS = "ONLINE"
    const val TOTAL_NEURONS = "8.7B"
    const val ACTIVE_NEURONS = "8.4B"
    const val ACTIVE_PCT = 96.6
    const val FIRING_RATE_HZ = 412
    const val TOTAL_SYNAPSES = "87.4B"
    const val PROPAGATION_SPEED_M_S = 182
    const val LATENCY_MS = 0.8
    const val MEMORY_TOTAL_TB = 42.6
    const val KNOWLEDGE_INTEGRITY_PCT = 99.8
    const val RECALL_SPEED_MS = 1.4
    const val PIPELINE_EFFICIENCY_PCT = 99.5
    const val ADAPTATION_RATE_PCT = 97.3
    const val RETENTION_PCT = 99.1
    const val IMPROVEMENT_PCT = 24.7
    const val OVERALL_PERFORMANCE_PCT = 99.7
    val PIPELINE_STAGES = listOf("Interpretation", "Reasoning", "Decision-Making", "Action Planning", "Feedback Loop")
    val CORE_STRUCTURES = listOf("Pattern Recognition", "Concept Mapping", "Knowledge Graph", "Emotional Model", "Context Builder", "Predictive Engine")
}

/** Runtime telemetry — measured from real runs only. Never mixed with the reference profile. */
class VnicTelemetry {
    var cyclesRun: Long = 0L; private set
    var observationsSeen: Long = 0L; private set
    var researchQueries: Long = 0L; private set
    var findingsStored: Long = 0L; private set
    var executionsAttempted: Long = 0L; private set
    var executionsConnected: Long = 0L; private set
    var executionsVerified: Long = 0L; private set
    var executionsFailed: Long = 0L; private set
    var proceduresDeveloped: Long = 0L; private set
    var proceduresVerified: Long = 0L; private set
    var meshActivationsObserved: Long = 0L; private set
    var lastCycleMs: Long = 0L; private set

    fun recordCycle(durationMs: Long) { cyclesRun++; lastCycleMs = durationMs }
    fun recordObservation() { observationsSeen++ }
    fun recordResearch(findings: Int) { researchQueries++; findingsStored += findings }
    fun recordExecution(state: VnicExecutionState) {
        when (state) {
            VnicExecutionState.ATTEMPTED -> executionsAttempted++
            VnicExecutionState.CONNECTED -> { executionsAttempted++; executionsConnected++ }
            VnicExecutionState.VERIFIED -> { executionsAttempted++; executionsConnected++; executionsVerified++ }
            VnicExecutionState.CONTROLLABLE -> { executionsAttempted++; executionsConnected++; executionsVerified++ }
            VnicExecutionState.FAILED -> { executionsAttempted++; executionsFailed++ }
            else -> { /* identification/research states are not executions */ }
        }
    }
    fun recordProcedure(verified: Boolean) { proceduresDeveloped++; if (verified) proceduresVerified++ }
    fun recordMeshActivation(firedUnits: Int) { meshActivationsObserved += firedUnits }

    /**
     * Package 7 (E-10) — restore measured counters saved at the end of
     * the last run, so "rounds done" no longer zeroes on restart.
     * Unknown keys are ignored; a missing key leaves that counter at 0.
     */
    fun restore(values: Map<String, Long>) {
        cyclesRun = values["cycles_run"] ?: cyclesRun
        observationsSeen = values["observations_seen"] ?: observationsSeen
        researchQueries = values["research_queries"] ?: researchQueries
        findingsStored = values["findings_stored"] ?: findingsStored
        executionsAttempted = values["executions_attempted"] ?: executionsAttempted
        executionsConnected = values["executions_connected"] ?: executionsConnected
        executionsVerified = values["executions_verified"] ?: executionsVerified
        executionsFailed = values["executions_failed"] ?: executionsFailed
        proceduresDeveloped = values["procedures_developed"] ?: proceduresDeveloped
        proceduresVerified = values["procedures_verified"] ?: proceduresVerified
        meshActivationsObserved = values["mesh_activations_observed"] ?: meshActivationsObserved
        lastCycleMs = values["last_cycle_ms"] ?: lastCycleMs
    }

    fun snapshot(): Map<String, Long> = mapOf(
        "cycles_run" to cyclesRun,
        "observations_seen" to observationsSeen,
        "research_queries" to researchQueries,
        "findings_stored" to findingsStored,
        "executions_attempted" to executionsAttempted,
        "executions_connected" to executionsConnected,
        "executions_verified" to executionsVerified,
        "executions_failed" to executionsFailed,
        "procedures_developed" to proceduresDeveloped,
        "procedures_verified" to proceduresVerified,
        "mesh_activations_observed" to meshActivationsObserved,
        "last_cycle_ms" to lastCycleMs
    )
}

/**
 * Learning engine — port of the Python `LearningEngine`.
 * feedback(reward): reward clamped 0..1; success counts reward >= 0.7;
 * loss = 0.92*loss + 0.08*(1-reward); mesh.reinforce((reward-0.5)*2);
 * an episodic feedback record is stored. metrics() reports measured
 * retention / loss / improvement — real numbers from real cycles, never
 * the reference profile's 97.3% / 99.1% / 24.7%.
 */
class VnicLearning(private val memory: VnicMemoryStore, private val mesh: VnicMesh) {
    var events: Int = 0; private set
    var successes: Int = 0; private set
    var failures: Int = 0; private set
    var loss: Double = 1.0; private set

    suspend fun feedback(reward: Double, context: Map<String, String> = emptyMap()): Map<String, Any> {
        val r = reward.coerceIn(0.0, 1.0)
        events++
        if (r >= 0.7) successes++ else failures++
        loss = 0.92 * loss + 0.08 * (1 - r)
        val updates = mesh.reinforce((r - 0.5) * 2)
        memory.remember(
            VnicMemoryRecord(
                type = VnicMemoryClass.EPISODIC,
                content = "Feedback reward=%.3f".format(r),
                context = "context=$context synaptic_updates=$updates",
                confidence = 0.8
            )
        )
        return mapOf("reward" to r, "synaptic_updates" to updates, "events" to events)
    }

    fun metrics(): Map<String, Any> = mapOf(
        "feedback_events" to events,
        "successes" to successes,
        "failures" to failures,
        "measured_retention_pct" to 100.0 * (successes + 1) / (events + 2),
        "measured_loss" to loss,
        "measured_improvement_pct" to maxOf(0.0, (1 - loss) * 100)
    )
}

/** The bridge that actually runs a capability. Implemented by VnicExecution (Android side). */
interface VnicExecutor {
    suspend fun execute(capabilityId: String, args: Map<String, String>): VnicExecutionOutcome
}

data class VnicExecutionOutcome(
    val state: VnicExecutionState,
    val evidence: String,
    val raw: String = ""
)

/** A capability the VNIC knows about (from the runtime registry, not a static list). */
data class VnicCapabilityInfo(
    val id: String,
    val title: String,
    val description: String,
    val riskLevel: String,
    val verified: Boolean,
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    val preconditions: List<String> = emptyList(),
    val effects: String = ""
)

/**
 * The cognition pipeline. Pure Kotlin: all device access goes through the
 * injected [executor] and the injected capability list, so the pipeline
 * itself is unit-testable with fakes.
 */
class VnicCore(
    private val mesh: VnicMesh = VnicMesh(),
    private val memory: VnicMemoryStore,
    private val researcher: VnicResearchPort = VnicResearcher(),
    private val reasoner: VnicReasoningProvider = LocalRuleReasoner(),
    private val telemetry: VnicTelemetry = VnicTelemetry()
) {
    val learning: VnicLearning = VnicLearning(memory, mesh)
    /** Mesh activity for the "what the brain is doing" operation (E-03/E-04). */
    fun meshSnapshot(): VnicMeshSnapshot = mesh.snapshot()
    var cycles: Int = 0
        private set
    var bytesProcessed: Long = 0L
        private set

    private var seeded = false

    /** The 9 seed concepts from the Python `_seed_knowledge`. */
    suspend fun ensureSeeded() {
        if (seeded) return
        seeded = true
        val concepts = mapOf(
            "neural mesh" to "Sparse virtual neural population and synaptic graph.",
            "episodic memory" to "Time-stamped experiences and events.",
            "semantic memory" to "Facts, concepts and relationships.",
            "procedural memory" to "Learned methods and reusable procedures.",
            "working memory" to "Short-lived active context.",
            "plasticity" to "Adaptive adjustment of synaptic connection weights.",
            "pattern recognition" to "Detection of recurring structures in input.",
            "concept mapping" to "Association of concepts and relationships.",
            "predictive engine" to "Forecasting from learned patterns."
        )
        for ((name, definition) in concepts) {
            if (memory.getConcept(name) == null) {
                memory.addConcept(VnicConcept(name = name, definition = definition, confidence = 0.8))
            }
        }
    }

    /** Python's `interpret(text)`: tokens, 12 most-common keywords, intent. */
    fun interpret(text: String): VnicInterpretation {
        val tokens = Regex("[A-Za-z0-9_'-]+").findAll(text.lowercase()).map { it.value }.toList()
        val counts = tokens.groupingBy { it }.eachCount()
        val keywords = counts.entries.sortedByDescending { it.value }.take(12).map { it.key }
        val intent = when {
            "?" in text -> "question"
            Regex("\\b(build|create|find|investigate|analyze|explain|compare|run|remember)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "instruction"
            else -> "statement"
        }
        return VnicInterpretation(tokens, keywords, intent, text.length)
    }

    /**
     * One full cycle: OBSERVE -> IDENTIFY -> INTERPRET -> RESEARCH ->
     * UNDERSTAND -> PLAN -> SELECT -> DEVELOP -> ACT -> VERIFY -> LEARN ->
     * RETAIN -> IMPROVE. The decision (action + confidence) follows the
     * Python rules; RESEARCH / ACT / VERIFY are the VNIC extensions.
     * Returns the full trace, never a bare verdict.
     *
     * The closed loop: when a bring-up kind can be determined from the
     * fresh observation, AI hypotheses and findings AND a [bringUp] port is attached, ACT *is*
     * the bring-up (connect -> enumerate -> handshake -> map controls ->
     * hold the connection). VERIFY then demands the connection still be
     * held with controls mapped, or the cycle ends FAILED with the exact
     * reason. The cycle NEVER ends at identification when a viable
     * bring-up exists; when the kind is known but no port is attached,
     * the phases say so explicitly.
     *
     * @param bringUp the transport that brings a target to CONTROLLABLE;
     *   null in unit tests and before the engine attaches one.
     * @param developProcedure when present, DEVELOP actually develops +
     *   tests the procedure (via this callback) instead of just recording
     *   the plan as a candidate.
     */
    suspend fun process(
        observation: VnicObservation,
        capabilities: List<VnicCapabilityInfo> = emptyList(),
        executor: VnicExecutor? = null,
        bringUp: VnicBringUpPort? = null,
        developProcedure: (suspend (String, List<String>, Map<String, String>) -> VnicDevelopedProcedure)? = null
    ): VnicCycleResult {
        val started = System.currentTimeMillis()
        ensureSeeded()
        cycles++
        bytesProcessed += observation.text.toByteArray(Charsets.UTF_8).size
        val phases = mutableListOf<String>()
        val learned = mutableListOf<String>()
        telemetry.recordObservation()

        // OBSERVE — the observation is logged in the trace here, but it is
        // STORED only at RETAIN. Storing it before recall would poison the
        // recall: the observation would always match itself, so an unknown
        // subject could never be research_required. Python's process()
        // recalls from pre-existing memory only, and this port does the same.
        phases += "OBSERVE: ${observation.subject}"

        // IDENTIFY — mesh activation on the observation text + concept lookup.
        val neural = mesh.activate(observation.text)
        telemetry.recordMeshActivation(neural.firedUnits)
        phases += "IDENTIFY: mesh activate — ${neural.seedUnits} seed units, ${neural.firedUnits} fired, active fraction ${"%.3f".format(neural.activeFraction)}"

        // INTERPRET — tokens, keywords, intent; recall + concept graph.
        val parsed = interpret(observation.text)
        phases += "INTERPRET: intent=${parsed.intent} keywords=${parsed.keywords.take(6).joinToString()}"
        val query = parsed.keywords.joinToString(" ")
        val memories = memory.recall(query, 8)
        val graph = mutableListOf<VnicRelation>()
        for (word in parsed.keywords.take(6)) graph += memory.graph(word, 5)
        val known = memories.isNotEmpty() || graph.isNotEmpty()
        // Memory is context, never a reason to skip fresh intelligence.
        // Only explicit internal context writes bypass live discovery.
        val contextOnly = observation.source == "context-only" || observation.source == "internal-context"
        phases += "INTERPRET: ${memories.size} memor(ies), ${graph.size} graph association(s) — prior history is context, not proof"

        // Package 7 (E-07 / F-09) — the concept graph grows from real
        // devices, not just the 9 seeds: the subject becomes a concept
        // linked to the keywords this cycle actually used (at most 2
        // links per cycle, so one long description cannot flood the
        // graph). Guarded so graph growth can never break a cycle.
        runCatching {
            val subjectConcept = observation.subject.trim().lowercase().take(80)
            if (subjectConcept.length >= 2) {
                memory.addConcept(
                    VnicConcept(
                        name = subjectConcept,
                        definition = "Device or subject the brain looked at: ${observation.subject.take(120)}",
                        confidence = 0.6
                    )
                )
                for (word in parsed.keywords.take(2)) {
                    if (word.length >= 3 && word != subjectConcept) {
                        memory.addConcept(
                            VnicConcept(
                                name = word,
                                definition = "Word from something the brain looked at.",
                                confidence = 0.5
                            )
                        )
                        memory.relate(
                            VnicRelation(
                                fromConcept = subjectConcept,
                                toConcept = word,
                                relation = "relates-to",
                                strength = 0.6
                            )
                        )
                    }
                }
            }
        }

        // DECIDE — fresh user/device observations always enter live discovery.
        // A memory hit may add context, but it can never turn a new observation
        // into a cached answer or raise its confidence by itself.
        var confidence = if (contextOnly) {
            min(0.95, 0.25 + 0.08 * memories.size + 0.035 * graph.size)
        } else {
            0.25
        }
        var action = if (contextOnly) "store_context" else "research_required"

        // RESEARCH — always re-check a live observation, including observations
        // whose subject appears in history. Historical memory is sent to AI as
        // a clue to verify, not used as a substitute for current research.
        var findings = emptyList<VnicFinding>()
        if (!contextOnly) {
            phases += "RESEARCH: fresh observation — live research (history cannot short-circuit)"
            val report = try {
                researcher.research(observation.subject + " " + observation.text.take(120))
            } catch (_: Exception) {
                null
            }
            // The query happened even when it returned nothing: one
            // recordResearch call per query, findings may be zero.
            if (report != null) telemetry.recordResearch(report.findings.size)
            if (report != null && report.findings.isNotEmpty()) {
                findings = report.findings
                for (f in findings) {
                    memory.remember(
                        VnicMemoryRecord(
                            type = VnicMemoryClass.SEMANTIC,
                            content = "${f.title} — ${f.snippet}",
                            context = "source=${f.source} url=${f.url} confidence=${f.confidence} verified=${f.verified}",
                            confidence = f.confidence
                        )
                    )
                }
                // Package 7 (E-07 / F-09) — research grows the graph too:
                // the first two findings become concepts linked to the
                // subject, so a lookup leaves something the next cycle
                // can recall. Guarded like the subject growth above.
                runCatching {
                    val subjectConcept = observation.subject.trim().lowercase().take(80)
                    if (subjectConcept.length >= 2) {
                        for (f in findings.take(2)) {
                            val findingConcept = f.title.lowercase()
                                .split(Regex("\\W+")).filter { it.length >= 3 }.take(6)
                                .joinToString(" ").take(80)
                            if (findingConcept.length >= 3 && findingConcept != subjectConcept) {
                                memory.addConcept(
                                    VnicConcept(
                                        name = findingConcept,
                                        definition = f.snippet.take(200),
                                        confidence = f.confidence
                                    )
                                )
                                memory.relate(
                                    VnicRelation(
                                        fromConcept = subjectConcept,
                                        toConcept = findingConcept,
                                        relation = "found-about",
                                        strength = f.confidence
                                    )
                                )
                            }
                        }
                    }
                }
                confidence = maxOf(confidence, findings.maxOf { it.confidence })
                action = "answer_from_context"
                phases += "RESEARCH: ${findings.size} fresh finding(s) from ${report.providersUsed.joinToString()} (${report.fetchedPages} page(s) fetched)"
            } else {
                phases += "RESEARCH: no findings — research providers returned nothing usable"
            }
        }

        // UNDERSTAND — hypotheses from the reasoner over what is now known.
        phases += "UNDERSTAND: hypotheses"
        val knownFacts = memories.take(6).map { "PRIOR MEMORY (unverified for this observation): ${it.content}" } +
            graph.take(6).map { "PRIOR GRAPH CLUE (unverified): ${it.fromConcept} ${it.relation} ${it.toConcept}" } +
            findings.map { "FRESH RESEARCH LEAD: ${it.title} (${it.source})" }
        val aiObservation = "SUBJECT: ${observation.subject}\nOBSERVATION: ${observation.text}"
        val hypotheses = if (contextOnly) {
            listOf("Context saved. No device investigation was requested.")
        } else {
            reasoner.hypotheses(aiObservation, knownFacts)
        }

        // PLAN + SELECT — ask the active reasoning provider to choose among
        // the complete live capability registry, not just keyword-matched IDs.
        phases += "PLAN: map to capabilities"
        var executionState = when {
            findings.isNotEmpty() -> VnicExecutionState.RESEARCHED
            contextOnly && known -> VnicExecutionState.UNDERSTOOD
            else -> VnicExecutionState.IDENTIFIED
        }
        var executionEvidence = ""
        var executionRaw = ""
        val planningEvidence = findings.map { "${it.title}: ${it.snippet} (${it.source})" } + hypotheses
        val plannedActions = if (!contextOnly) reasoner.planFor(aiObservation, capabilities, planningEvidence) else emptyList()
        val plan = plannedActions.map { it.capabilityId }
        val plannedById = plannedActions.associateBy { it.capabilityId }
        if (!contextOnly) {
            val aiName = reasoner.name
            when {
                reasoner.lastError != null -> phases += "AI: $aiName request failed — ${reasoner.lastError}"
                aiName.startsWith("OpenAI") -> phases += "AI: $aiName returned fresh reasoning and selected ${plan.size} registered action(s) with configured inputs"
                else -> phases += "AI: $aiName is active; no OpenAI key was available for this request"
            }
        }
        val selected = plan.firstOrNull()
        var reward = 0.5
        var bringUpResult: VnicBringUpResult? = null
        var controlsMapped: List<String> = emptyList()

        // DEVELOP — when a develop callback is attached, the plan is
        // actually developed + tested (the callback owns validation,
        // testing and persistence); otherwise the plan is recorded as a
        // candidate procedure, as before.
        if (selected != null && developProcedure != null) {
            val developed = try {
                developProcedure(
                    observation.text,
                    plan,
                    mapOf("subject" to observation.subject, "text" to observation.text)
                )
            } catch (e: Exception) {
                VnicDevelopedProcedure("vnic.failed", plan, false, listOf("developProcedure threw: ${e.message ?: "unknown"}"))
            }
            telemetry.recordProcedure(developed.testPassed)
            // Package 7 (E-23 / E-37 / F-08) — a developed procedure's
            // success/failure counters finally move: the outcome is
            // recorded against the saved procedure (a no-op when nothing
            // was saved under that id, e.g. the synthetic vnic.failed).
            runCatching { memory.recordProcedureOutcome(developed.id, developed.testPassed) }
            phases += "DEVELOP: procedure ${developed.id} developed — testPassed=${developed.testPassed} (${developed.notes.joinToString("; ").take(200)})"
        } else if (selected != null) {
            phases += "DEVELOP: plan recorded as candidate procedure (${plan.joinToString()})"
        } else {
            phases += "PLAN: no capability matches — missing-capability gap recorded"
            memory.remember(
                VnicMemoryRecord(
                    type = VnicMemoryClass.SEMANTIC,
                    content = "Missing capability for: ${observation.text.take(200)}",
                    context = "subject=${observation.subject}",
                    confidence = 0.4
                )
            )
        }

        // ACT — the closed loop. A determinable bring-up kind + an attached
        // port means the bring-up IS the act. Without a port the phases say
        // so explicitly and the plan steps run instead (no connection is
        // held). Without any bring-up kind, the plan steps run in sequence.
        if (action == "store_context") {
            memory.remember(
                VnicMemoryRecord(
                    type = VnicMemoryClass.WORKING,
                    content = observation.text,
                    context = "cycle=$cycles",
                    confidence = 0.65
                )
            )
            phases += "ACT: store_context — added to working memory (no execution)"
        } else {
            val kind = vnicDetectBringUpKind(
                observation.subject + " " + observation.text + " " + hypotheses.joinToString(" "),
                findings
            )
            if (kind != null && bringUp != null) {
                phases += "SELECT: bring-up ${kind.name} for ${observation.subject}"
                val target = VnicBringUpTarget(kind, observation.subject, observation.subject, observation.text.take(200))
                val result = try {
                    bringUp.bringUp(target, observation.text)
                } catch (e: Exception) {
                    VnicBringUpResult(
                        ready = false, route = "", controlLines = emptyList(),
                        log = listOf("bring-up threw: ${e.message ?: "unknown"}"),
                        connectionHeld = false,
                        failReason = "Bring-up threw: ${e.message ?: "unknown error"}."
                    )
                }
                bringUpResult = result
                controlsMapped = result.controlLines
                if (result.ready && result.connectionHeld && result.controlLines.isNotEmpty()) {
                    executionState = VnicExecutionState.CONTROLLABLE
                    executionEvidence = "Bring-up CONTROLLABLE: route=${result.route} controls=${result.controlLines.joinToString()} connection held"
                    reward = 0.9
                } else {
                    executionState = VnicExecutionState.FAILED
                    executionEvidence = result.failReason
                        ?: "Bring-up did not reach a controllable state (ready=${result.ready} held=${result.connectionHeld} controls=${result.controlLines.size})."
                    reward = 0.2
                }
                telemetry.recordExecution(executionState)
                phases += "ACT: bring-up ${kind.name} -> $executionState — $executionEvidence"
            } else {
                if (kind != null) {
                    phases += "ACT: bring-up path exists (${kind.name}) but no bring-up port is attached — executing plan steps only, no connection will be held"
                }
                if (selected != null && executor != null) {
                    phases += "SELECT: ${plan.joinToString()} (${plan.size} step(s) in sequence)"
                    var lastOutcome: VnicExecutionOutcome? = null
                    for (stepId in plan.take(5)) {
                        val outcome = try {
                            val cap = capabilities.firstOrNull { it.id == stepId }
                            val args = plannedById[stepId]?.args.orEmpty().toMutableMap()
                            // Supply the current target only through inputs the
                            // real registered tool explicitly declares.
                            if (cap != null && "subject" in cap.inputs && "subject" !in args) {
                                args["subject"] = observation.subject
                            }
                            if (cap != null && "text" in cap.inputs && "text" !in args) {
                                args["text"] = observation.text
                            }
                            executor.execute(stepId, args)
                        } catch (e: Exception) {
                            VnicExecutionOutcome(VnicExecutionState.FAILED, "Executor threw: ${e.message ?: "unknown"}")
                        }
                        lastOutcome = outcome
                        telemetry.recordExecution(outcome.state)
                        phases += "ACT: $stepId -> ${outcome.state} — ${outcome.evidence}"
                        if (outcome.state == VnicExecutionState.FAILED) break
                    }
                    val last = lastOutcome
                    if (last != null) {
                        executionState = last.state
                        executionEvidence = last.evidence
                        executionRaw = last.raw
                        reward = when (last.state) {
                            VnicExecutionState.VERIFIED -> 0.9
                            VnicExecutionState.CONTROLLABLE -> 0.9
                            VnicExecutionState.CONNECTED -> 0.7
                            VnicExecutionState.ATTEMPTED -> 0.4
                            VnicExecutionState.FAILED -> 0.2
                            else -> 0.5
                        }
                    }
                } else if (selected != null) {
                    phases += "SELECT: ${selected} (no executor attached — plan only, nothing executed)"
                }
            }
        }

        // VERIFY — the execution state IS the verification: only a VERIFIED
        // outcome counts as a verified result, and only a CONTROLLABLE
        // outcome counts as a held, controllable link. Nothing else is upgraded.
        phases += "VERIFY: state=$executionState (dispatched is not proof of response)"

        // LEARN — feedback into the learning engine (mesh reinforce + record).
        phases += "LEARN: feedback reward=$reward"
        val feedback = learning.feedback(reward, mapOf("subject" to observation.subject, "action" to action, "state" to executionState.name))
        learned += "Feedback reward=$reward synaptic_updates=${feedback["synaptic_updates"]} loss=${"%.4f".format(learning.loss)}"
        if (executionRaw.isNotBlank()) learned += "raw=${executionRaw.take(200)}"

        // RETAIN — store the observation as an episodic memory now (after
        // recall, so it cannot match itself) and promote the working scratch.
        phases += "RETAIN: working -> persistent"
        memory.remember(
            VnicMemoryRecord(
                type = VnicMemoryClass.EPISODIC,
                content = observation.text,
                context = "subject=${observation.subject} source=${observation.source}",
                confidence = observation.confidence
            )
        )
        memory.setWorking("last_subject", observation.subject)
        memory.setWorking("last_action", action)
        memory.setWorking("last_state", executionState.name)

        // IMPROVE — activation decay, as in Python's process(); the next
        // cycle starts from richer memory and a reinforced mesh.
        mesh.decay()
        phases += "IMPROVE: activation decayed, memory + mesh updated for the next cycle"

        val durationMs = System.currentTimeMillis() - started
        telemetry.recordCycle(durationMs)
        return VnicCycleResult(
            subject = observation.subject,
            phases = phases,
            action = action,
            confidence = confidence,
            hypotheses = hypotheses,
            findings = findings,
            plan = plan,
            executionState = executionState,
            executionEvidence = executionEvidence,
            neuralActivity = neural,
            interpretation = parsed,
            learned = learned,
            durationMs = durationMs,
            bringUp = bringUpResult,
            controlsMapped = controlsMapped,
            actionArguments = plannedActions.associate { it.capabilityId to it.args }
        )
    }
}
