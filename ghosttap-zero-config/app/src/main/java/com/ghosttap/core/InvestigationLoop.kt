package com.ghosttap.core

import com.ghosttap.data.IntelDatabase
import com.ghosttap.data.LearnedStore
import com.ghosttap.data.Observation
import java.util.UUID

/**
 * The autonomous investigation cycle:
 * Observe -> Interpret -> Research -> Plan -> Act -> Evaluate -> Learn ->
 * Extend -> Continue.
 *
 * One [runCycle] call performs exactly ONE full cycle and returns the updated
 * state. The caller (IntelViewModel) repeats cycles while status is RUNNING
 * or EXTENDING; [tryExtend] performs the EXTEND phase when the loop stalls
 * on a capability gap.
 *
 * Full autonomous mode: the loop runs every planned step on its own, at any
 * risk level — there are no confirmation gates and no WAITING_USER halts.
 * Safety is observability, not permission prompts:
 * - Every step's risk level (READ/CONFIGURE/WRITE/CONTROL) is recorded on
 *   the step and every auto-run action is logged to the timeline.
 * - Access controls are never bypassed: pairing/password-gated operations
 *   fail honestly instead of running.
 * - Nothing is fabricated: conclusions come only from real OpResults.
 * - Steps the planner marked auto=false are skipped with a note.
 *
 * Conclusions come only from real OpResults. A plan is never treated as
 * proof; results are written to the timeline as OBSERVED records, and
 * hypotheses are stored as ASSUMED — the two are never mixed.
 */
/**
 * Progress-aware limits for the autonomous loop. The loop continues while it
 * is LEARNING — new evidence hashes appear OR the last cycle produced
 * progress — and stops with a logged plain-English reason on any of:
 * - repeated identical state 3x (the picture isn't changing),
 * - 2 consecutive cycles with no new evidence at all,
 * - the time budget is exceeded (default 10 minutes, configurable),
 * - the hard backstop (default 60 cycles / 6 extensions).
 *
 * No infinite loop is possible: the hard backstop always wins, and the time
 * budget wins even if evidence keeps trickling in.
 */
data class InvestigationPolicy(
    val maxCycles: Int = 60,
    val maxExtensions: Int = 6,
    val sameStateLimit: Int = 3,
    val noEvidenceLimit: Int = 2,
    val timeBudgetMs: Long = 10 * 60_000L
) {
    /** "10 min" style label for status lines. */
    fun budgetLabel(): String = "${timeBudgetMs / 60_000} min"
}

data class LoopState(
    val investigationId: String,
    val target: IntelTarget,
    val hypotheses: List<Hypothesis> = emptyList(),
    val findings: List<Finding> = emptyList(),
    /** Steps that actually ran AND returned ok, in order — the verified trail. */
    val executedSteps: List<PlanStep> = emptyList(),
    val history: List<String> = emptyList(),
    /** RUNNING | WAITING_USER | EXTENDING | REVIEW | DEAD_END | COMPLETE */
    val status: String = "RUNNING",
    val cycles: Int = 0,
    val lastProgressCycle: Int = 0,
    val lastFingerprint: String = "",
    /** Capability ids the plan needed but nothing registered can do. */
    val pendingGapIds: Set<String> = emptySet(),
    /** Extension attempts used (cap: policy.maxExtensions per investigation). */
    val extensionsTried: Int = 0,
    /** Set when status == WAITING_USER (legacy — full autonomous mode never halts). */
    val pendingStep: PlanStep? = null,
    /**
     * A new control the loop drafted, waiting for the user's review before
     * it switches on (Package 6 / C-16, C-17). Set when status == REVIEW.
     * The loop pauses here — the only gate in the whole loop, and it gates
     * registering new executable code, never investigation steps.
     */
    val pendingDraft: ExtensionDraft? = null,
    /** Plain-English reason when status is DEAD_END or COMPLETE. */
    val endReason: String = "",
    /** When this investigation's loop started (ms) — the time budget anchor. */
    val startMs: Long = System.currentTimeMillis(),
    /** Consecutive cycles with zero new evidence. */
    val consecutiveNoEvidence: Int = 0,
    /** Consecutive cycles with an identical state fingerprint. */
    val sameStateStreak: Int = 0
)

class InvestigationLoop(
    private val engine: IntelEngine,
    private val reasoner: ReasoningProvider,
    private val db: IntelDatabase,
    private val learned: LearnedStore,
    private val policy: InvestigationPolicy = InvestigationPolicy()
) {

    /**
     * Runs one full Observe->…->Learn cycle. onEvent receives plain-English
     * progress lines for the cycle log (called on the caller's thread).
     */
    suspend fun runCycle(state: LoopState, onEvent: (String) -> Unit): LoopState {
        val cycles = state.cycles + 1
        var history = state.history
        fun log(msg: String) {
            history = history + msg
            try {
                onEvent(msg)
            } catch (_: Exception) {
            }
        }

        log("Round $cycles — looking at \"${state.target.title}\"…")

        // ---- 1. OBSERVE: what do we already know about this investigation?
        val observations = try {
            db.observationDao().forInvestigation(state.investigationId)
        } catch (_: Exception) {
            emptyList()
        }

        // ---- 2. RESEARCH: on-device evidence first, then the real web.
        val findings = try {
            Researcher.researchWithWeb(
                state.target, db, learned, reasoner, state.investigationId
            )
        } catch (_: Exception) {
            emptyList()
        }
        val newFindings = findings.filter { f ->
            state.findings.none { it.text == f.text }
        }
        if (newFindings.isNotEmpty()) {
            log("Found ${newFindings.size} new clue(s): " +
                newFindings.take(2).joinToString("; ") { it.text.take(90) })
        }

        // ---- 3. INTERPRET: feed findings into the reasoner, read hypotheses.
        try {
            reasoner.noteFindings(findings)
        } catch (_: Exception) {
        }
        val hyps = try {
            reasoner.hypotheses()
        } catch (_: Exception) {
            state.hypotheses
        }
        val newHyps = hyps.filter { h -> state.hypotheses.none { it.text == h.text } }
        if (newHyps.isNotEmpty()) {
            log("New idea: ${newHyps.first().text.take(100)}")
        }

        // ---- 4. PLAN: draft, refine, validate.
        val failedIds = observations
            .filter { it.kind == "result" && it.title.startsWith("FAIL") }
            .mapNotNull { it.title.substringAfter("FAIL — ", "").ifBlank { null } }
            .toSet()
        var plan = try {
            Planner.planFor(state.target, CapabilityRegistry, failedIds)
        } catch (_: Exception) {
            emptyList()
        }
        val knowledge = try {
            observations.takeLast(20)
                .joinToString("\n") { "${it.kind}: ${it.title}" }
                .take(3000)
        } catch (_: Exception) {
            ""
        }
        plan = try {
            reasoner.refinePlan(state.target, plan, knowledge)
        } catch (_: Exception) {
            plan
        }
        val bad = try {
            engine.validate(plan.map { it.capabilityId }).toSet()
        } catch (_: Exception) {
            emptySet()
        }
        // Ids the plan wanted but nothing registered can do — a capability gap.
        val droppedIds: Set<String> = bad
        plan = plan.filter { it.capabilityId !in bad }
        if (plan.isEmpty()) {
            log("No usable plan this round.")
        }

        // ---- 5. ACT: run every auto step. Full autonomous mode — no
        // confirmation gates at any risk level. The risk level is logged
        // with the action so the timeline shows exactly what ran.
        val executed = state.executedSteps.toMutableList()
        var ranTotal = 0
        var ranOk = 0
        for (step in plan) {
            val cap = CapabilityRegistry.get(step.capabilityId) ?: continue
            if (!step.auto) {
                log("Skipping \"${cap.title}\" — the planner left it manual.")
                continue
            }
            log("Trying: ${cap.title} [${cap.riskLevel}]…")
            val result = try {
                engine.run(step.capabilityId, step.args)
            } catch (e: Exception) {
                OpResult(false, "Crashed before it could run: ${e.message ?: "unknown error"}")
            }
            ranTotal++
            try {
                reasoner.noteResult(step.capabilityId, result)
            } catch (_: Exception) {
            }
            if (result.ok) {
                ranOk++
                executed.add(step)
            }
            // ---- 6. EVALUATE: record the ACTUAL outcome, never the plan.
            try {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = state.investigationId,
                        ts = System.currentTimeMillis(),
                        kind = "result",
                        title = "${if (result.ok) "OK" else "FAIL"} — ${step.capabilityId}",
                        detail = (result.summary +
                            if (result.data.isNotBlank()) "\n" + result.data.take(1200) else "")
                            .take(2000),
                        provenance = "OBSERVED",
                        confidence = if (result.ok) 1.0 else 0.4
                    )
                )
            } catch (_: Exception) {
            }
            log(
                if (result.ok) "Worked: ${cap.title}."
                else "Didn't work: ${cap.title} — ${result.summary.take(120)}"
            )
        }

        // ---- 7. LEARN: hypotheses are beliefs — stored as ASSUMED, never facts.
        for (h in hyps.take(8)) {
            try {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = state.investigationId,
                        ts = System.currentTimeMillis(),
                        kind = "hypothesis",
                        title = h.text.take(120),
                        detail = buildString {
                            append("Confidence ${(h.confidence * 100).toInt()}%. ")
                            if (h.supporting.isNotEmpty()) {
                                append("Supporting: ${h.supporting.take(3).joinToString("; ")}. ")
                            }
                            if (h.contradicting.isNotEmpty()) {
                                append("Against: ${h.contradicting.take(3).joinToString("; ")}.")
                            }
                        }.take(800),
                        provenance = "ASSUMED",
                        confidence = h.confidence
                    )
                )
            } catch (_: Exception) {
            }
        }

        // ---- 8. CONTINUE: progress-aware limits. The loop keeps going while
        // it is learning — new evidence this cycle, or the picture changed.
        val fingerprint = (hyps.map { it.text } + findings.map { it.text }).joinToString("‖")
        val progressed = fingerprint != state.lastFingerprint
        val lastProgress = if (progressed) cycles else state.lastProgressCycle
        val evidenceThisCycle = newFindings.isNotEmpty() || newHyps.isNotEmpty() || ranOk > 0
        val noEvidenceStreak =
            if (evidenceThisCycle) 0 else state.consecutiveNoEvidence + 1
        val sameStateStreak =
            if (progressed) 0 else state.sameStateStreak + 1

        // (No WAITING_USER halt: full autonomous mode runs every auto step.)

        var status = "RUNNING"
        var endReason = ""
        val elapsedMs = System.currentTimeMillis() - state.startMs
        when {
            cycles >= policy.maxCycles -> {
                status = "DEAD_END"
                endReason = "Hit the hard backstop of ${policy.maxCycles} rounds — " +
                    "stopping so it can't spin forever."
            }
            elapsedMs > policy.timeBudgetMs -> {
                status = "DEAD_END"
                endReason = "Used up its ${policy.budgetLabel()} time budget — stopping."
            }
            sameStateStreak >= policy.sameStateLimit -> {
                status = "DEAD_END"
                endReason = "The picture hasn't changed for $sameStateStreak rounds — " +
                    "out of ideas on this target. " +
                    Researcher.missingInfo(state.target, findings).firstOrNull().orEmpty()
            }
            noEvidenceStreak >= policy.noEvidenceLimit -> {
                status = "DEAD_END"
                endReason = "Two rounds with nothing new learned — stopping honestly."
            }
            ranTotal > 0 && ranOk == ranTotal && !progressed && hyps.isNotEmpty() -> {
                status = "COMPLETE"
                endReason = "Every probe worked and the picture stopped changing — nothing new to learn."
            }
        }

        // ---- 8b. EXTEND: dead end caused by a capability gap? Try to build it.
        // (detect: plan steps dropped by validation, or repeated "unknown
        // capability" notes on the timeline)
        val unknownCapIds = observations
            .filter { it.kind == "result" && it.detail.contains("Unknown capability") }
            .mapNotNull { Regex("'([^']+)'").find(it.detail)?.groupValues?.get(1) }
            .toSet()
        val gapIds: Set<String> = droppedIds + unknownCapIds
        if (status == "DEAD_END" && gapIds.isNotEmpty() &&
            state.extensionsTried < policy.maxExtensions
        ) {
            log(
                "Round $cycles hit a wall: the plan needed " +
                    "${gapIds.joinToString(", ")} but nothing registered can do that."
            )
            return state.copy(
                hypotheses = hyps,
                findings = findings,
                executedSteps = executed,
                history = history,
                status = "EXTENDING",
                cycles = cycles,
                lastProgressCycle = lastProgress,
                lastFingerprint = fingerprint,
                pendingStep = null,
                endReason = "Capability gap: ${gapIds.joinToString(", ")}.",
                pendingGapIds = gapIds
            )
        }

        if (status != "RUNNING") {
            log(if (status == "COMPLETE") "Done: $endReason" else "Stopping: $endReason")
            try {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = state.investigationId,
                        ts = System.currentTimeMillis(),
                        kind = "note",
                        title = if (status == "COMPLETE") "Auto-investigation complete" else "Auto-investigation stopped",
                        detail = endReason.take(800),
                        provenance = "OBSERVED",
                        confidence = 1.0
                    )
                )
            } catch (_: Exception) {
            }
        }

        return state.copy(
            hypotheses = hyps,
            findings = findings,
            executedSteps = executed,
            history = history,
            status = status,
            cycles = cycles,
            lastProgressCycle = lastProgress,
            lastFingerprint = fingerprint,
            consecutiveNoEvidence = noEvidenceStreak,
            sameStateStreak = sameStateStreak,
            pendingStep = null,
            endReason = endReason
        )
    }

    /**
     * EXTEND phase. The plan needed capabilities nothing registered can do, so
     * the intelligence drafts a fix — either a composed DynamicCapabilityDef
     * (rule-based, or the remote model when configured) or a genuine script
     * (see ScriptCapability) — and PROVES it through the matching propose
     * capability (validate + real test run) before anything is registered.
     * A draft is never trusted; only a passing test registers.
     *
     * Both paths auto-run in full autonomous mode — validate, test for real,
     * register only on a passing test — with the proposal's risk level logged
     * on the timeline.
     *
     * Returns RUNNING (new capability visible, investigation resumes)
     * or DEAD_END with the honest reason. Bounded by the policy's extension cap.
     * (Legacy WAITING_USER halt removed — full autonomous mode never pauses.)
     */
    suspend fun tryExtend(state: LoopState, onEvent: (String) -> Unit): LoopState {
        var history = state.history
        fun log(msg: String) {
            history = history + msg
            try {
                onEvent(msg)
            } catch (_: Exception) {
            }
        }
        val tried = state.extensionsTried + 1
        log(
            "EXTEND — the plan needed " +
                (state.pendingGapIds.ifEmpty { setOf("something unregistered") }.joinToString()) +
                ". Trying to build it from existing tools…"
        )

        // Package 6 (C-16/C-17): the draft is BUILT here but NOT run. It is
        // handed back as status REVIEW with the draft attached, so the user
        // sees exactly what it would do and approves or discards it — the
        // only gate in the loop, and it gates registering new executable
        // code only, never investigation steps.
        val draft = buildExtensionDraft(state.target, state.pendingGapIds, reasoner)
        if (draft == null) {
            val reason =
                "Tried to design the missing capability but came up empty — stopping honestly."
            log("EXTEND — $reason")
            writeNote(state.investigationId, "Capability extension failed", reason, 0.4)
            return state.copy(
                status = "DEAD_END", endReason = reason,
                extensionsTried = tried, history = history, pendingGapIds = emptySet()
            )
        }
        log("EXTEND — drafted \"${draft.title}\". Waiting for your review before anything switches on.")
        return state.copy(
            status = "REVIEW", endReason = "A new control is ready for your review.",
            extensionsTried = tried, history = history,
            pendingGapIds = emptySet(), pendingDraft = draft
        )
    }

    private suspend fun writeNote(
        investigationId: String, title: String, detail: String, confidence: Double
    ) {
        try {
            db.observationDao().insert(
                Observation(
                    id = UUID.randomUUID().toString(),
                    investigationId = investigationId,
                    ts = System.currentTimeMillis(),
                    kind = "note",
                    title = title,
                    detail = detail.take(800),
                    provenance = "OBSERVED",
                    confidence = confidence
                )
            )
        } catch (_: Exception) {
        }
    }
}

/**
 * One new control waiting for the user's review before it switches on
 * (Package 6 / C-16, C-17). Built by [buildExtensionDraft], handed to the
 * user by the loop as status REVIEW, and only registered when the user
 * approves it. The summary is the whole review: what it would do, in
 * plain words, with the highest risk level of any step it would run.
 */
data class ExtensionDraft(
    /** "control" (composed workflow) or "script" (scripted probe). */
    val kind: String,
    val title: String,
    val summary: String,
    val riskLevel: String,
    /** Engine capability id that registers it ("intel.propose_capability" / "intel.propose_script"). */
    val proposeId: String,
    val proposeArgs: Map<String, String>
)

/**
 * Drafts a new control for a capability gap, without registering anything.
 * Tries a composed workflow from verified primitives first, then a
 * scripted probe. Returns null when the brain could not design anything
 * usable. Pure draft — nothing is tested or registered here; the caller
 * shows the draft for review and only runs [ExtensionDraft.proposeId]
 * after the user approves it.
 */
suspend fun buildExtensionDraft(
    target: IntelTarget,
    gapIds: Set<String>,
    reasoner: ReasoningProvider
): ExtensionDraft? {
    val gapDesc = buildString {
        append("Capability gap on target '${target.title}' (${target.kind}). ")
        if (gapIds.isNotEmpty()) {
            append("Needed but unregistered: ${gapIds.joinToString(", ")}. ")
        }
        append("Target text: ${target.text.take(300)}")
    }
    val defJson = try {
        reasoner.draftCapabilityJson(gapDesc, CapabilityRegistry.all())
    } catch (_: Exception) {
        ""
    }
    if (defJson.isNotBlank()) {
        val def = DynamicCapabilityDef.fromJson(defJson) ?: return null
        val stepLines = def.steps.mapIndexed { i, s ->
            val cap = CapabilityRegistry.get(s.capabilityId)
            "${i + 1}. ${cap?.title ?: s.capabilityId} [${cap?.riskLevel ?: "READ"}]"
        }
        val risk = DynamicCapabilityRuntime.escalatedRisk(def)
        val summary = buildString {
            append("A new control, \"${def.title}\". It would run these existing tools in order:\n")
            append(stepLines.joinToString("\n"))
            append("\nIt changes things at $risk level at most. It is tested before it switches on, and nothing is switched on until you approve it.")
        }
        return ExtensionDraft(
            kind = "control", title = def.title, summary = summary, riskLevel = risk,
            proposeId = "intel.propose_capability", proposeArgs = mapOf("definitionJson" to defJson)
        )
    }
    val scriptJson = try {
        reasoner.draftScriptJson(gapDesc, CapabilityRegistry.all())
    } catch (_: Exception) {
        ""
    }
    val draft = scriptJson.takeIf { it.isNotBlank() }?.let { ScriptDraft.parse(it) } ?: return null
    val (title, js) = draft
    val called = Regex("""ghost\.run\(\s*["']([^"']+)["']""")
        .findAll(js).map { it.groupValues[1] }.distinct().toList()
    val risk = try {
        ScriptRuntime.effectiveRisk(
            ScriptDef(id = "draft", title = title, description = "", jsSource = js, risk = "READ")
        )
    } catch (_: Exception) {
        "READ"
    }
    val summary = buildString {
        append("A new scripted control, \"$title\". It would run these existing tools: ")
        append(if (called.isEmpty()) "none it could name" else called.joinToString(", "))
        append(". It runs in a locked-down box that can only call Ghost Tap's own tools. It is tested before it switches on, and nothing is switched on until you approve it.")
    }
    return ExtensionDraft(
        kind = "script", title = title, summary = summary, riskLevel = risk,
        proposeId = "intel.propose_script", proposeArgs = mapOf("title" to title, "jsSource" to js)
    )
}
