package com.ghosttap.core.vnic

import android.app.Application
import android.content.Context
import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.DynAssertion
import com.ghosttap.core.DynStep
import com.ghosttap.core.DynamicCapabilityDef
import com.ghosttap.core.DynamicCapabilityRuntime
import com.ghosttap.core.IntelEngine
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus
import com.ghosttap.core.IntelSettings
import com.ghosttap.core.LoopState
import com.ghosttap.core.esp32.VirtualEsp32Client
import com.ghosttap.data.VnicDriveOutcome
import com.ghosttap.data.VnicDrivePolicyState
import com.ghosttap.data.VnicSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The VNIC engine — the seam between the VNIC core and Ghost Tap's existing
 * intelligence layer. It owns one [VnicCore] (pure Kotlin), one persistent
 * [VnicMemoryStore] (Room `vnic.db`), the [VnicResearcher], the
 * [VnicCapabilityBridge] and the [VnicExecution] bridge.
 *
 * It does NOT replace anything: the existing InvestigationLoop, Planner,
 * ProcedureSynthesizer, RuleBasedReasoner and WebResearcher keep running
 * exactly as before. The VNIC observes the same rounds (via
 * [onLoopRound], called from IntelViewModel's run loop) and records them
 * into its own persistent memory, and it can run a full cognition cycle on
 * demand ([observe]) for any subject.
 *
 * Failure isolation: if the Room database cannot be opened, the engine
 * falls back to the in-memory store and keeps working (flagged in
 * [status]). The VNIC must never crash the app or block an investigation.
 */
data class VnicStatusSnapshot(
    val storeBackedByRoom: Boolean,
    val lastSubject: String,
    val lastAction: String,
    val lastState: String,
    val lastConfidence: Double,
    val lastCycleMs: Long,
    val telemetry: Map<String, Long>,
    val memoryCounts: Map<String, Int>,
    val capabilityCount: Int,
    val procedureCount: Int,
    val lastPhases: List<String>,
    val lastFindings: List<String>,
    /** Last bring-up outcome ("" / empty / false when no bring-up has run yet). */
    val lastBringUpRoute: String = "",
    val lastBringUpControls: List<String> = emptyList(),
    val lastBringUpHeld: Boolean = false
)

class VnicEngine(
    context: Context,
    private val engine: IntelEngine,
    researchConfig: VnicResearchConfig = VnicResearchConfig(),
    llmConfig: VnicLlmConfig = VnicLlmConfig()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge = VnicCapabilityBridge(engine)
    private val execution = VnicExecution(engine)
    /**
     * Package 7 (E-29, E-26, E-10, E-06) — small session state in
     * SharedPreferences (never Room): the last drive outcome, the
     * drive-policy pins/cooldowns, the telemetry counters and the
     * working-memory keys. Read once here, restored below.
     */
    private val sessionStore = VnicSessionStore(context)
    private val telemetry = VnicTelemetry().also { it.restore(sessionStore.loadTelemetry()) }
    /** ESP32 bridge once a VirtualESP32 client is attached (null until then — ESP32 bring-up is unavailable). */
    private var vesp32Bridge: VnicEsp32Bridge? = null
    /** Autonomous-drive policy: one drive at a time, 60s cooldown, never re-drive CONTROLLABLE. Restored from last run, not fresh. */
    private val drivePolicy = VnicDrivePolicy().also { policy ->
        sessionStore.loadDrivePolicy()?.let { saved ->
            policy.restore(saved.controllableKeys.toSet(), saved.lastDrivenAtMs)
        }
    }
    private var storeBackedByRoom = true
    private val memory: VnicMemoryStore = try {
        RoomVnicMemory(VnicDb.get(context).vnicMemoryDao())
    } catch (e: Exception) {
        storeBackedByRoom = false
        InMemoryVnicMemory()
    }
    private val researcher = VnicResearcher(researchConfig)
    /**
     * Defect 1 repair: the saved Thinking Engine settings (IntelSettings)
     * are the reasoner's config source. The core holds [reasonerSwitch]
     * for its whole life; the live delegate underneath it is swapped by
     * [refreshReasonerFromSettings] whenever the settings change, so a
     * key saved after the engine was built takes effect with no app
     * restart — the same re-read-per-use pattern as ReasonerPicker on
     * the older path. An explicitly passed key (constructor
     * [llmConfig]) wins at construction; refresh always re-reads the
     * saved settings. No key is ever logged or stored anywhere here.
     */
    private val app: Application? = (context.applicationContext as? Application)
    private val reasonerSwitch = SwitchableReasoner(resolveReasoner(llmConfig))
    private val reasoner: VnicReasoningProvider get() = reasonerSwitch

    /**
     * Build the live reasoner from an explicit config, falling back to
     * the saved Thinking Engine settings. Remote iff a key is present
     * (key-only — a blank endpoint defaults to OpenAI inside
     * [VnicLlmConfig]); otherwise the built-in helper.
     */
    private fun resolveReasoner(passed: VnicLlmConfig = VnicLlmConfig()): VnicReasoningProvider {
        val explicit = if (passed.configured) passed
            else app?.let { VnicLlmConfig.fromSaved(IntelSettings.getEndpoint(it), IntelSettings.getApiKey(it)) }
            ?: passed
        return if (explicit.configured) RemoteLlmReasoner(explicit) else LocalRuleReasoner()
    }

    /**
     * Re-read the saved Thinking Engine settings and swap the live
     * reasoner to match (remote when a key is saved, built-in when it
     * is cleared). Called before every [observe] cycle and from
     * [IntelViewModel.refreshReasonerName] when the user SAVEs or
     * CLEARs the settings. Never throws; returns the active reasoner
     * name. The core, mesh, memory, learning and telemetry are
     * untouched — only the reasoner delegate changes.
     */
    fun refreshReasonerFromSettings(): String {
        return runCatching {
            reasonerSwitch.delegate = resolveReasoner()
            reasonerSwitch.name
        }.getOrElse { reasonerSwitch.name }
    }

    private val core = VnicCore(
        mesh = VnicMesh(),
        memory = memory,
        researcher = researcher,
        reasoner = reasonerSwitch,
        telemetry = telemetry
    )

    /**
     * Package 7 (E-29 / F-26) — the last drive outcome, kept until the
     * user opens the controls or dismisses it. Survives screen changes
     * AND app restarts (backed by the session store). Null when nothing
     * has been driven yet, or after a dismiss.
     */
    private val _driveOutcome = MutableStateFlow(sessionStore.loadDriveOutcome())
    val driveOutcome: StateFlow<VnicDriveOutcome?> = _driveOutcome.asStateFlow()

    /** Name of the last procedure developed this run (for the "that was right/wrong" feedback). */
    var lastDevelopedProcedureName: String? = null
        private set

    init {
        // Working-memory keys are restored, not rebuilt: the last subject
        // / action / state from the previous run come back (E-06).
        scope.launch {
            runCatching {
                sessionStore.loadWorking().forEach { (key, value) -> memory.setWorking(key, value) }
            }
        }
    }

    private val _status = MutableStateFlow(
        VnicStatusSnapshot(
            storeBackedByRoom = storeBackedByRoom,
            lastSubject = "", lastAction = "", lastState = "",
            lastConfidence = 0.0, lastCycleMs = 0,
            telemetry = telemetry.snapshot(), memoryCounts = emptyMap(),
            capabilityCount = bridge.mapCapabilities().size, procedureCount = 0,
            lastPhases = emptyList(), lastFindings = emptyList()
        )
    )
    val status: StateFlow<VnicStatusSnapshot> = _status.asStateFlow()

    /** The capability map the VNIC currently sees (runtime discovery). */
    fun capabilities(): List<VnicCapabilityInfo> = bridge.mapCapabilities()

    /**
     * Attach the Virtual ESP32 client: registers the vesp32.* capabilities
     * (real IPC calls into the Virtual ESP32 app) and stores the bridge so
     * ESP32 bring-ups can run. Safe to call before any cycle; a second
     * call replaces the bridge. Never throws.
     */
    fun attachVesp32(client: VirtualEsp32Client) {
        val b = VnicEsp32Bridge(client)
        vesp32Bridge = b
        runCatching { b.register() }
        scope.launch { runCatching { refreshStatus() } }
    }

    /**
     * Called after each InvestigationLoop round. Records the round into
     * VNIC memory + telemetry, then hands the round to [driveLoop] — the
     * single call site for autonomous driving. Never throws — a VNIC
     * recording failure must never break the investigation loop.
     */
    fun onLoopRound(state: LoopState, events: List<String>) {
        scope.launch {
            runCatching {
                memory.remember(
                    VnicMemoryRecord(
                        type = VnicMemoryClass.EPISODIC,
                        content = "Loop round: status=${state.status} target=${state.target.text.take(120)} events=${events.joinToString("; ").take(400)}",
                        context = "investigation=${state.investigationId}",
                        confidence = 0.7
                    )
                )
                refreshStatus()
            }
            runCatching { driveLoop(state, events) }
        }
    }

    /**
     * Autonomous drive: when the policy says this round's target is worth
     * driving (unresolved, bring-up path exists, cooldown clear, not
     * already CONTROLLABLE, no drive running), run one full VNIC cycle on
     * it — research, develop, bring-up — and report the outcome back TO
     * Ghost Tap on the event bus (the timeline consumes ToolOpResult) and
     * into episodic memory + telemetry. Runs in the engine scope, never
     * throws, never blocks the loop.
     */
    fun driveLoop(state: LoopState, events: List<String>) {
        scope.launch {
            runCatching {
                val targetText = state.target.text
                if (!drivePolicy.shouldDrive(state.investigationId, targetText, state.status)) return@launch
                drivePolicy.markDriveStarted(state.investigationId, targetText)
                try {
                    val subject = state.target.title.ifBlank { targetText }
                    val result = observe(subject, targetText, source = "loop-drive")
                    val controllable = result.executionState == VnicExecutionState.CONTROLLABLE
                    drivePolicy.markDriveFinished(state.investigationId, targetText, controllable)
                    saveDrivePolicy()
                    val summary = if (controllable) {
                        "VNIC bring-up CONTROLLABLE: route=${result.bringUp?.route} controls=${result.controlsMapped.joinToString()} — connection held, drive it from the control screen."
                    } else {
                        "VNIC bring-up ${result.executionState}: ${result.executionEvidence}"
                    }
                    IntelEventBus.publish(IntelEvent.ToolOpResult("vnic.bring_up", controllable, summary))
                    memory.remember(
                        VnicMemoryRecord(
                            type = VnicMemoryClass.EPISODIC,
                            content = "Autonomous drive outcome for ${state.target.title}: ${result.executionState} — $summary",
                            context = "investigation=${state.investigationId}",
                            confidence = if (controllable) 0.9 else 0.5
                        )
                    )
                    refreshStatus(result)
                } catch (e: Exception) {
                    drivePolicy.markDriveFinished(state.investigationId, targetText, false)
                    saveDrivePolicy()
                    IntelEventBus.publish(
                        IntelEvent.ToolOpResult("vnic.bring_up", false, "VNIC drive failed safely: ${e.message ?: "unknown error"}")
                    )
                }
            }
        }
    }

    /**
     * Run one full VNIC cognition cycle on a subject, on demand. The cycle
     * gets the real bring-up port (Android BringUpPipeline for BLE/NFC/
     * NETWORK/USB, the ESP32 bridge for ESP32 targets) and the real
     * develop-procedure callback, so the loop can close end to end.
     */
    suspend fun observe(subject: String, text: String, source: String = "user"): VnicCycleResult {
        // Defect 1: a key saved after this engine was built takes effect
        // before this cycle reasons — no restart, no stale local fallback.
        runCatching { refreshReasonerFromSettings() }
        val result = core.process(
            VnicObservation(subject, text, source),
            capabilities = bridge.mapCapabilities(),
            executor = execution,
            bringUp = AndroidVnicBringUp(engine, vesp32Bridge),
            developProcedure = { goal, stepIds, args ->
                val developed = bridge.developProcedure(goal, stepIds, args)
                // Package 7 (E-30) — the step-list inconsistency, fixed:
                // the SAVED steps are the DEVELOPED def's steps (the ones
                // actually validated + tested), not the requested ids.
                // developAndSaveProcedure already saved the def's steps;
                // the observe callback saved the requested ones, so the
                // two paths could persist different lists. They agree now.
                val savedSteps = developed.def.steps.map { it.capabilityId }
                if (developed.testPassed) {
                    runCatching {
                        memory.saveProcedure(
                            VnicProcedure(
                                name = developed.def.id,
                                stepsJson = savedSteps.joinToString(",", "[", "]") { "\"$it\"" },
                                verified = true
                            )
                        )
                    }
                    lastDevelopedProcedureName = developed.def.id
                    // Package 7 (E-23 / E-37 / F-08) — a developed
                    // procedure is REGISTERED as a runnable capability,
                    // not just saved: it can run through the engine like
                    // any other capability from here on.
                    runCatching { registerDeveloped(developed) }
                }
                VnicDevelopedProcedure(developed.def.id, savedSteps, developed.testPassed, developed.notes)
            }
        )
        refreshStatus(result)
        saveDriveOutcomeFor(result)
        persistSession()
        return result
    }

    /** Develop + test a procedure for a goal, then persist it if it passed. */
    suspend fun developAndSaveProcedure(goal: String, stepIds: List<String>, args: Map<String, String> = emptyMap()): VnicCapabilityBridge.DevelopedProcedure {
        val developed = bridge.developProcedure(goal, stepIds, args)
        if (developed.testPassed) {
            val stepsJson = developed.def.steps.joinToString(",", "[", "]") { "\"${it.capabilityId}\"" }
            memory.saveProcedure(
                VnicProcedure(
                    name = developed.def.id,
                    stepsJson = stepsJson,
                    verified = true
                )
            )
            telemetry.recordProcedure(true)
            lastDevelopedProcedureName = developed.def.id
            // Package 7 (E-23 / E-37 / F-08) — registered, not just saved.
            runCatching { registerDeveloped(developed) }
        } else {
            telemetry.recordProcedure(false)
        }
        refreshStatus()
        persistSession()
        return developed
    }

    /** Live research only (no cycle). Findings are stored as semantic memories. */
    suspend fun research(query: String): VnicResearchReport {
        val report = researcher.research(query)
        for (f in report.findings) {
            memory.remember(
                VnicMemoryRecord(
                    type = VnicMemoryClass.SEMANTIC,
                    content = "${f.title} — ${f.snippet}",
                    context = "source=${f.source} url=${f.url} confidence=${f.confidence} verified=${f.verified}",
                    confidence = f.confidence
                )
            )
        }
        telemetry.recordResearch(report.findings.size)
        refreshStatus()
        persistSession()
        return report
    }

    // ---------- Package 7 — operations surface (E-01–E-37) ----------

    /** Dismiss the "ready for you" indicator (E-29): it stays cleared until the next drive. */
    fun dismissDriveOutcome() {
        _driveOutcome.value = null
        runCatching { sessionStore.saveDriveOutcome(null) }
    }

    /** Saved plans in the brain's own store (E-37), for the unified procedures view. */
    suspend fun listVnicProcedures(): List<VnicProcedure> =
        runCatching { memory.listProcedures() }.getOrElse { emptyList() }

    /** Record whether a saved plan worked when it was actually used (E-37 / F-08): the counters move now. */
    suspend fun recordProcedureOutcome(name: String, success: Boolean) {
        runCatching { memory.recordProcedureOutcome(name, success) }
        refreshStatus()
    }

    /**
     * Manual feedback (E-09 / E-33): the user saying "that was right /
     * wrong" — the Python CLI's `learn` verb, as an operation. Reward
     * 0.9 counts as a success, 0.2 as a failure, same scale as cycles.
     */
    suspend fun giveFeedback(reward: Double) {
        runCatching { core.learning.feedback(reward) }
        refreshStatus()
        persistSession()
    }

    /** Mesh activity, measured (E-03 / E-04). */
    fun meshSnapshot(): VnicMeshSnapshot = core.meshSnapshot()

    /** Concept graph, as plain lines (E-07): what the brain knows and how it links. */
    suspend fun graphSnapshot(limit: Int = 10): List<String> {
        return runCatching {
            memory.searchConcepts("", limit).map { concept ->
                val links = memory.relationsOf(concept.name).take(3)
                    .joinToString("; ") { "${it.relation} ${it.toConcept}" }
                if (links.isBlank()) "${concept.name} — ${concept.definition.take(120)}"
                else "${concept.name} — ${concept.definition.take(120)} (links: $links)"
            }
        }.getOrElse { emptyList() }
    }

    /** Most recent remembered events, newest first (E-06 / E-28). */
    suspend fun recentMemories(limit: Int = 10): List<VnicMemoryRecord> =
        runCatching { memory.recent(limit) }.getOrElse { emptyList() }

    /** Research providers actually active this run (E-14–E-17): free-first, keyed only when configured. */
    fun researchProviderNames(): List<String> = researcher.providers().map { it.name }

    /** Which reasoner is thinking: "Built-in helper" or "Online helper" (E-12 / E-13). Reads the live delegate, so it changes when settings change. */
    fun reasonerName(): String = reasonerSwitch.name

    /** Plain-words reason the last online-helper call failed, or null when it worked / the built-in helper is in use. Never contains credentials. */
    fun reasonerFailureReason(): String? = reasonerSwitch.lastFailureReason

    /**
     * Register a developed procedure as a runnable capability (E-23 /
     * E-37 / F-08). Only with its real passing test report in hand, and
     * only when the id is not already registered — never twice, never
     * with a fabricated report. No intel.db write (db = null): the
     * procedure itself stays in vnic.db; only the runtime registration
     * happens here.
     */
    private suspend fun registerDeveloped(developed: VnicCapabilityBridge.DevelopedProcedure) {
        val report = developed.report ?: return
        if (!developed.testPassed) return
        if (CapabilityRegistry.get(developed.def.id) != null) return
        runCatching { DynamicCapabilityRuntime.register(developed.def, engine, report, null) }
    }

    /**
     * Make a SAVED procedure runnable again (E-37 / F-08): rebuild its
     * definition from the vnic.db record, re-test it for real (READ
     * steps only), and register it when the test passes. Called when
     * the user loads the plan — never at startup, so starting the app
     * never re-runs device steps on its own. True when registered
     * (or already registered); false when the plan no longer passes
     * its check — the plan itself is still loaded, only the
     * capability registration is skipped.
     */
    suspend fun ensureProcedureRegistered(name: String): Boolean {
        if (CapabilityRegistry.get(name) != null) return true
        val stored = runCatching { memory.getProcedure(name) }.getOrNull() ?: return false
        val ids = stored.stepsJson.trim().removePrefix("[").removeSuffix("]").split(",")
            .map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return false
        val def = DynamicCapabilityDef(
            id = stored.name,
            title = "VNIC procedure: ${stored.name}",
            detail = "Saved by the brain; re-checked before switching on.",
            riskLevel = "READ",
            steps = ids.map { DynStep(it) },
            assertions = listOf(DynAssertion("ok", "")),
            origin = "vnic"
        )
        val report = runCatching { DynamicCapabilityRuntime.test(def, engine) }.getOrNull() ?: return false
        if (!report.passed) return false
        return runCatching { DynamicCapabilityRuntime.register(def, engine, report, null) }.getOrElse { false }
    }

    /** Persist the drive outcome after a cycle that ran a bring-up (E-29), plus the policy pins it changed. */
    private fun saveDriveOutcomeFor(result: VnicCycleResult) {
        val bringUp = result.bringUp ?: return
        val outcome = VnicDriveOutcome(
            subject = result.subject,
            state = result.executionState.name,
            route = bringUp.route,
            controls = bringUp.controlLines,
            evidence = result.executionEvidence,
            ts = System.currentTimeMillis()
        )
        _driveOutcome.value = outcome
        runCatching { sessionStore.saveDriveOutcome(outcome) }
        saveDrivePolicy()
    }

    /** Persist the drive-policy pins + cooldown stamps (E-26). */
    private fun saveDrivePolicy() {
        runCatching {
            sessionStore.saveDrivePolicy(
                VnicDrivePolicyState(
                    controllableKeys = drivePolicy.controllableKeysSnapshot().toList(),
                    lastDrivenAtMs = drivePolicy.lastDrivenSnapshot()
                )
            )
        }
    }

    /** Persist telemetry counters + working-memory keys after a cycle (E-10, E-06). */
    private suspend fun persistSession() {
        runCatching { sessionStore.saveTelemetry(telemetry.snapshot()) }
        runCatching {
            val working = mapOf(
                "last_subject" to (memory.getWorking("last_subject") ?: ""),
                "last_action" to (memory.getWorking("last_action") ?: ""),
                "last_state" to (memory.getWorking("last_state") ?: "")
            ).filterValues { it.isNotBlank() }
            sessionStore.saveWorking(working)
        }
    }

    private suspend fun refreshStatus(last: VnicCycleResult? = null) {
        val counts = runCatching { memory.counts() }.getOrElse { emptyMap() }
        val procedures = runCatching { memory.listProcedures() }.getOrElse { emptyList() }
        val learning = core.learning.metrics()
        val telemetryWithLearning = telemetry.snapshot() + mapOf(
            "learning_events" to (learning["feedback_events"] as Int).toLong(),
            "learning_loss_milli" to ((learning["measured_loss"] as Double) * 1000).toLong()
        )
        val cur = _status.value
        _status.value = cur.copy(
            storeBackedByRoom = storeBackedByRoom,
            lastSubject = last?.subject ?: cur.lastSubject,
            lastAction = last?.action ?: cur.lastAction,
            lastState = last?.executionState?.name ?: cur.lastState,
            lastConfidence = last?.confidence ?: cur.lastConfidence,
            lastCycleMs = last?.durationMs ?: cur.lastCycleMs,
            telemetry = telemetryWithLearning,
            memoryCounts = counts.mapKeys { it.key.name.lowercase() },
            capabilityCount = bridge.mapCapabilities().size,
            procedureCount = procedures.size,
            lastPhases = last?.phases ?: cur.lastPhases,
            lastFindings = last?.findings?.map { "${it.title} (${it.source}, ${it.confidence})" } ?: cur.lastFindings,
            lastBringUpRoute = last?.bringUp?.route ?: cur.lastBringUpRoute,
            lastBringUpControls = last?.bringUp?.controlLines ?: cur.lastBringUpControls,
            lastBringUpHeld = last?.bringUp?.connectionHeld ?: cur.lastBringUpHeld
        )
    }
}
