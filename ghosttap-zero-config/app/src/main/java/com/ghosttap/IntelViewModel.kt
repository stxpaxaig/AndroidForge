package com.ghosttap

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghosttap.core.BringUpKind
import com.ghosttap.core.BringUpPhase
import com.ghosttap.core.BringUpPipeline
import com.ghosttap.core.BringUpState
import com.ghosttap.core.BringUpTarget
import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.CapabilityScanner
import com.ghosttap.core.DynamicCapabilityDef
import com.ghosttap.core.DynamicCapabilityRuntime
import com.ghosttap.core.ExtensionDraft
import com.ghosttap.core.Finding
import com.ghosttap.core.GhostIdentity
import com.ghosttap.core.Hypothesis
import com.ghosttap.core.IntelEngine
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus
import com.ghosttap.core.IntelTarget
import com.ghosttap.core.InvestigationLoop
import com.ghosttap.core.LoopState
import com.ghosttap.core.OpResult
import com.ghosttap.core.PlanStep
import com.ghosttap.core.Planner
import com.ghosttap.core.ProcedureKinds
import com.ghosttap.core.ProcedureSynthesizer
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigationInput
import com.ghosttap.core.investigate.InvestigationLedger
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.LiveTargetStore
import com.ghosttap.core.investigate.AiReasoningState
import com.ghosttap.core.investigate.aiPromptFor
import com.ghosttap.core.investigate.localReasoningText
import com.ghosttap.core.investigate.Investigator
import com.ghosttap.core.ReasonerPicker
import com.ghosttap.core.ReasoningProvider
import com.ghosttap.core.RemoteLlmReasoner
import com.ghosttap.core.Researcher
import com.ghosttap.core.RuleBasedReasoner
import com.ghosttap.core.ScanReport
import com.ghosttap.core.ScriptDef
import com.ghosttap.core.ScriptRuntime
import com.ghosttap.core.ScriptTestReport
import com.ghosttap.core.TestReport
import com.ghosttap.core.buildExtensionDraft
import com.ghosttap.core.esp32.VirtualEsp32Client
import com.ghosttap.core.vnic.VnicCycleResult
import com.ghosttap.core.vnic.VnicEngine
import com.ghosttap.core.vnic.VnicProcedure
import com.ghosttap.data.IntelDb
import com.ghosttap.data.Investigation
import com.ghosttap.data.LearnedStore
import com.ghosttap.data.Observation
import com.ghosttap.data.Procedure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Stored form of a plan step inside a saved procedure. */
@Serializable
data class StoredStep(
    val id: String,
    val args: Map<String, String> = emptyMap(),
    val why: String = "",
    val auto: Boolean = false
)

/**
 * Runs investigations: targets, plans, discovery execution, timeline, and the
 * persistent knowledge base. The planner proposes; this ViewModel executes.
 * Auto steps run on RUN DISCOVERY; proposed steps run only when tapped.
 */
class IntelViewModel(
    app: Application,
    val engine: IntelEngine
) : AndroidViewModel(app) {

    private val appRef: Application = app
    private val db = IntelDb.get(app)
    private val json = Json { ignoreUnknownKeys = true }
    /** Learned device controls, keyed by MAC — the research step reads these. */
    private val learnedStore = LearnedStore(appRef)

    /**
     * The VNIC neural core. Lazily built so a VNIC construction problem can
     * never break ViewModel init; the engine itself falls back to an
     * in-memory store if Room cannot be opened. It observes every
     * investigation round (see runLoop) and can run a full cognition cycle
     * on demand via [vnicObserve].
     */
    val vnicEngine: VnicEngine by lazy {
        VnicEngine(appRef, engine).also { e -> vesp32Client?.let { e.attachVesp32(it) } }
    }

    /** The VirtualESP32 client, when MainActivity has attached it (null until then). */
    private var vesp32Client: VirtualEsp32Client? = null

    /**
     * Attach the Virtual ESP32 client so the VNIC can register the vesp32.*
     * capabilities and drive ESP32 bring-ups. Safe to call before the lazy
     * engine exists — the engine picks the stored client up on creation.
     */
    fun attachVesp32(client: VirtualEsp32Client) {
        vesp32Client = client
        runCatching { vnicEngine.attachVesp32(client) }
    }

    /** Run one full VNIC cognition cycle on a subject, on demand. */
    suspend fun vnicObserve(subject: String, text: String): VnicCycleResult =
        vnicEngine.observe(subject, text)

    /**
     * Package 7 (E-31) — retry the last FAILED bring-up from the brain
     * panel, without the operations runner (standalone use — the app
     * passes an operation runner, which is preferred). No-op when the
     * brain has not looked at anything yet.
     */
    fun retryVnicBringUp() {
        viewModelScope.launch {
            val last = vnicEngine.status.value.lastSubject.ifBlank {
                vnicEngine.driveOutcome.value?.subject ?: ""
            }
            if (last.isNotBlank()) {
                runCatching { vnicObserve(last, "Try again: $last") }
            }
        }
    }

    /** Package 7 (E-29) — dismiss the "ready for you" note (standalone use; the runner path is preferred). */
    fun dismissVnicReady() {
        vnicEngine.dismissDriveOutcome()
    }

    val investigations: SnapshotStateList<Investigation> = mutableStateListOf()
    val procedures: SnapshotStateList<Procedure> = mutableStateListOf()
    var activeInvestigation by mutableStateOf<Investigation?>(null)
        private set
    var planSteps by mutableStateOf<List<PlanStep>>(emptyList())
        private set
    var timeline: SnapshotStateList<Observation> = mutableStateListOf()
    var searchResults by mutableStateOf<List<Observation>>(emptyList())
        private set
    var statusLine by mutableStateOf("No investigation open.")
        private set

    /** Lets the UI post a status message (e.g. form validation feedback). */
    fun setStatus(msg: String) {
        statusLine = msg
    }

    // ---------- autonomous-investigations findings ledger ----------

    /** Latest report from a manual investigation, shown under the manual entry. */
    var manualReport by mutableStateOf<FindingsReport?>(null)
        private set

    /**
     * Bumps whenever the findings ledger changes through this ViewModel, so
     * the timeline section re-reads InvestigationLedger.all(). The ledger
     * itself holds no Compose state, so screens read it fresh on this bump
     * (and fresh on every navigation to this screen).
     */
    var findingsVersion by mutableStateOf(0)
        private set

    /** Re-read the findings ledger — picks up reports added by other screens. */
    fun refreshFindings() {
        findingsVersion++
    }

    /**
     * Manual investigation entry point, reachable from any screen through
     * this ViewModel. Runs the local rule-based Investigator (no key
     * needed), adds the result to the shared InvestigationLedger, and
     * returns it — so a user-picked target (a typed name, a pasted code,
     * a reading entered by hand) gets the same full report treatment as
     * a target found by any other function. Never invents details: the
     * caller supplies exactly what was observed or typed, and the
     * Investigator keeps UNKNOWN unknown and never claims a handshake
     * that did not happen. Takes no control action — the user drives
     * from the report card.
     */
    fun investigateManually(input: InvestigationInput): FindingsReport {
        val report = Investigator.investigate(input)
        InvestigationLedger.add(report)
        LiveTargetStore.publish(report, InvestigationSource.MANUAL)
        manualReport = report
        findingsVersion++
        return report
    }

    // ---------- MASTER PROMPT: live targets + AI reasoning ----------

    /**
     * The answer from the last command tapped on a live target, in
     * plain words — shown under that target's command center. Empty
     * when nothing has been tapped yet. Set by the app shell
     * ([runLiveCommand] in GhostTapApp), which runs the command
     * through the existing OperationRunner and reports the outcome
     * here. A command that fails reports its failure here too —
     * never silence.
     */
    var lastCommandAnswer by mutableStateOf("")
        private set

    fun noteCommandResult(title: String, plainAnswer: String) {
        lastCommandAnswer = "$title — $plainAnswer".take(600)
    }

    fun clearCommandAnswer() {
        lastCommandAnswer = ""
    }

    // ZERO-CONFIG FIX (2026-11-06) — manual-disconnect memory: ids the
    // user deliberately disconnected, kept here in the ViewModel so the
    // choice survives navigation (a screen's `remember` resets every
    // time the screen is re-opened). Auto bring-up checks this before
    // connecting anything: a device/board the user disconnected is
    // NEVER auto-connected again. The entry is cleared the moment the
    // link is up again (by any connect path), so a later drop that was
    // NOT the user's choice still auto-reconnects. Nothing here
    // connects or disconnects anything itself.
    private var manuallyDisconnectedIds by mutableStateOf(setOf<String>())

    fun noteManualDisconnect(id: String) {
        if (id.isNotBlank()) manuallyDisconnectedIds = manuallyDisconnectedIds + id
    }

    fun clearManualDisconnect(id: String) {
        if (id in manuallyDisconnectedIds) manuallyDisconnectedIds = manuallyDisconnectedIds - id
    }

    fun wasManuallyDisconnected(id: String): Boolean = id in manuallyDisconnectedIds

    /**
     * Ask the thinking engine about one live target. Called once per
     * card (the card's LaunchedEffect); guarded — it only reasons
     * while the card is still WAITING, so a re-compose never
     * re-fires the AI call and a re-check with unchanged evidence
     * keeps its answer (see [LiveTargetStore.publish]).
     *
     * Async, never on the UI thread; the card shows the local report
     * immediately and this answer lands when it arrives. Honest
     * about which engine produced it:
     * - key set and the AI answers → [AiReasoningState.AI], the
     *   AI's own words (built only from what was really observed —
     *   see [aiPromptFor]).
     * - no key in the app → [AiReasoningState.LOCAL_NO_KEY], the
     *   app's own rules, clearly marked as such.
     * - key set but the AI cannot be reached, errors, or answers
     *   blank → [AiReasoningState.LOCAL_AI_FAILED], the app's own
     *   rules, clearly marked as such. An AI answer is NEVER faked.
     */
    fun reasonAboutLiveTarget(key: String) {
        val target = LiveTargetStore.get(key) ?: return
        if (target.aiState != AiReasoningState.WAITING) return
        viewModelScope.launch {
            val provider = ReasonerPicker.pick(appRef, localReasoner)
            currentProvider = provider
            updateReasoningMode(provider)
            reasonerName = provider.name
            engineStatus = provider.statusLine
            val localText = localReasoningText(target.report)
            if (provider is RemoteLlmReasoner && provider.configured) {
                val aiText = try {
                    provider.backgroundOn(aiPromptFor(target.report))
                } catch (_: Exception) {
                    ""
                }
                if (aiText.isNotBlank()) {
                    LiveTargetStore.setReasoning(key, aiText.trim(), AiReasoningState.AI)
                } else {
                    // The reason comes from the real failure the call
                    // just hit (see RemoteLlmReasoner.lastFailureReason)
                    // — the card shows it in plain words.
                    LiveTargetStore.setReasoning(
                        key,
                        localText,
                        AiReasoningState.LOCAL_AI_FAILED,
                        provider.lastFailureReason
                    )
                }
            } else {
                LiveTargetStore.setReasoning(key, localText, AiReasoningState.LOCAL_NO_KEY)
            }
            findingsVersion++
        }
    }
    /** One shared local reasoner per session — evidence accumulates across refines. */
    val localReasoner = RuleBasedReasoner()

    /** The provider that produced the current plan (remote wraps the local one). */
    private var currentProvider: ReasoningProvider = localReasoner

    var reasonerName by mutableStateOf(ReasonerPicker.activeName(app, localReasoner))
        private set
    var engineStatus by mutableStateOf(ReasonerPicker.activeStatus(app, localReasoner))
        private set
    /**
     * Which reasoning path is actually driving plans right now.
     * "On-phone rules (offline)" = the on-device rule engine (never labeled AI);
     * "Online model" = the user's own remote model via RemoteLlmReasoner.
     * Shown in the AUTO INVESTIGATE panel.
     */
    var activeReasoningMode by mutableStateOf("On-phone rules (offline)")
        private set

    /** Refresh which reasoning path the UI reports. */
    private fun updateReasoningMode(provider: ReasoningProvider) {
        activeReasoningMode =
            if (provider is RemoteLlmReasoner && provider.configured) "Online model"
            else "On-phone rules (offline)"
    }
    /** The intelligence's standing directive — shown in the UI. */
    val identityLine: String = "${GhostIdentity.NAME} — ${GhostIdentity.directive()}"
    var hypotheses by mutableStateOf<List<Hypothesis>>(emptyList())
        private set
    var scanReport by mutableStateOf<ScanReport?>(null)
        private set

    // ---------- auto-investigation loop ----------

    /** True while the autonomous cycle is running (not while waiting on you). */
    var loopActive by mutableStateOf(false)
        private set
    /** Latest loop state — status drives the UI (RUNNING / WAITING_USER / done). */
    var loopState by mutableStateOf<LoopState?>(null)
        private set
    /** Plain-English cycle log, newest last. */
    val loopLog: SnapshotStateList<String> = mutableStateListOf()
    /** The step that would need a tap if the loop ever halted (legacy — full autonomous mode never halts). */
    var pendingApprovalStep by mutableStateOf<PlanStep?>(null)
        private set

    /** Manual plan steps waiting for one-tap approval, in plan order (Package 6). */
    var approvalQueue by mutableStateOf<List<PlanStep>>(emptyList())
        private set

    /** A new control the loop drafted, waiting for review before it switches on (Package 6). */
    var pendingExtensionReview by mutableStateOf<ExtensionDraft?>(null)
        private set

    /** Saved extras restored at startup (Package 6 / B-083) — names, or empty. */
    var restoredExtensions by mutableStateOf<List<String>>(emptyList())
        private set

    /** Problems while restoring saved extras — empty when the restore was clean. */
    var restoreProblems by mutableStateOf<List<String>>(emptyList())
        private set

    /** Saved plans in the brain's own store (vnic_procedures). */
    var vnicProcedureCount by mutableStateOf(0)
        private set

    /** The brain's own saved plans, by name (Package 7 / E-37 / F-10). */
    val vnicProcedures: SnapshotStateList<VnicProcedure> = mutableStateListOf()

    /** One count for every saved plan, both stores together (Package 6 / F-10). */
    val unifiedProcedureCount: Int get() = procedures.size + vnicProcedureCount

    private var loopJob: Job? = null
    private val loopRunning = AtomicBoolean(false)
    /** Set by investigateAndAutoStart: start the loop once creation finishes. */
    private var autoStartPending = false

    /**
     * One-tap entry from any screen: create the investigation for this target,
     * and start the autonomous loop as soon as the investigation exists.
     * The caller navigates to the investigations screen right away.
     */
    fun investigateAndAutoStart(target: IntelTarget) {
        autoStartPending = true
        newInvestigation(target)
    }

    // ---------- zero-tap bring-up ----------

    /**
     * Zero-tap bring-up state. The user taps a target ONCE; Ghost Tap runs
     * the whole bring-up (connect → enumerate → map what's controllable)
     * and lands on the target's control screen. Then the USER drives.
     * The investigations screen stays as diagnostics — it is no longer
     * the destination.
     */
    var bringUpState by mutableStateOf(BringUpState.IDLE)
        private set
    /** Plain-English bring-up progress ("Connecting…", "Ready — you're in control."). */
    var bringUpStatus by mutableStateOf("")
        private set
    /** Target the current bring-up ran for (used for live honesty checks). */
    var bringUpTargetId by mutableStateOf("")
        private set
    /** Kind of the current bring-up target. */
    var bringUpTargetKind by mutableStateOf<BringUpKind?>(null)
        private set
    /**
     * Set on READY when bring-up should land the user on a control screen.
     * Observed by the app shell, which navigates once, then calls
     * [consumeBringUpDestination].
     */
    var bringUpDestination by mutableStateOf<String?>(null)
        private set

    fun consumeBringUpDestination() {
        bringUpDestination = null
    }

    /** Dismiss the bring-up banner (after READY or FAILED). */
    fun dismissBringUp() {
        bringUpJob?.cancel()
        bringUpJob = null
        bringUpState = BringUpState.IDLE
        bringUpStatus = ""
        bringUpTargetId = ""
        bringUpTargetKind = null
    }

    /**
     * Live honesty check: is the brought-up target ACTUALLY still in
     * controllable state right now? The banner uses this instead of trusting
     * the one-shot READY — a link that dropped after bring-up must never
     * keep saying "you're in control".
     */
    fun bringUpStillReady(): Boolean {
        if (bringUpState != BringUpState.READY) return false
        return when (bringUpTargetKind) {
            BringUpKind.BLE ->
                engine.bleGattStatus(bringUpTargetId).startsWith("CONNECTED")
            else -> true
        }
    }

    private var bringUpJob: Job? = null

    /**
     * Bring one target to controllable state with zero taps from the user.
     * Past bring-up outcomes for this target are read from memory first so
     * repeated failures are recognized; the outcome is recorded back to
     * memory so future bring-ups (and the planner) learn from it.
     */
    fun bringUpTarget(target: BringUpTarget, autoNavigate: Boolean = true) {
        if (bringUpJob?.isActive == true) {
            bringUpStatus = "Still getting the last device ready — one at a time."
            return
        }
        bringUpJob = viewModelScope.launch {
            bringUpTargetId = target.id
            bringUpTargetKind = target.kind
            bringUpState = BringUpState.CONNECTING
            bringUpStatus = "Connecting…"
            try {
                val past = pastBringUp(target.id)
                val pastFail: String? = past?.detail
                    ?.takeIf { it.startsWith("FAILED") }
                    ?.substringAfter("FAILED: ")
                    ?.take(160)
                if (past != null) {
                    loopLog.add(
                        "From last time: this device was " +
                            "${if (past.detail.startsWith("READY")) "set up fine" else "a problem — ${pastFail?.take(120)}"}."
                    )
                }
                val result = BringUpPipeline.bringUp(
                    target = target,
                    engine = engine,
                    pastFailure = pastFail,
                    phase = { p ->
                        bringUpState = when (p) {
                            BringUpPhase.CONNECTING -> BringUpState.CONNECTING
                            BringUpPhase.ENUMERATING -> BringUpState.ENUMERATING
                            BringUpPhase.HANDSHAKE -> BringUpState.ENUMERATING
                        }
                        bringUpStatus = when (p) {
                            BringUpPhase.CONNECTING -> "Connecting…"
                            BringUpPhase.ENUMERATING -> "Finding what it can do…"
                            BringUpPhase.HANDSHAKE -> "Checking it answers…"
                        }
                    },
                    note = { line -> loopLog.add("Getting it ready: $line") }
                )
                recordBringUp(target, result)
                if (result.ready) {
                    bringUpState = BringUpState.READY
                    bringUpStatus = "Ready — you're in control."
                    loopLog.add(
                        "Ready: \"${target.title}\" → ${result.descriptor?.route}."
                    )
                    val dest = result.descriptor
                    if (autoNavigate && dest?.autoNavigate == true) {
                        bringUpDestination = dest.route
                    }
                } else {
                    bringUpState = BringUpState.FAILED
                    bringUpStatus = result.failReason ?: "Couldn't get it ready."
                    // Let the user retry immediately.
                    lastAutoTriggerMs.remove(bringUpKey(target))
                    loopLog.add("Couldn't get it ready: \"${target.title}\" — ${result.failReason}")
                }
            } catch (e: Exception) {
                bringUpState = BringUpState.FAILED
                bringUpStatus = "Setting it up hit a problem: ${e.message ?: "unknown"}."
                lastAutoTriggerMs.remove(bringUpKey(target))
            }
        }
    }

    private fun bringUpKey(target: BringUpTarget): String =
        "bringup:${target.kind}:${target.id}"

    private suspend fun pastBringUp(targetId: String): Observation? =
        withContext(Dispatchers.IO) {
            runCatching {
                db.observationDao().byKind("bringup")
                    .lastOrNull { it.title.contains(targetId) }
            }.getOrNull()
        }

    private fun recordBringUp(target: BringUpTarget, result: com.ghosttap.core.BringUpResult) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = "bringup",
                        ts = System.currentTimeMillis(),
                        kind = "bringup",
                        title = "bringup:${target.kind}:${target.id}".take(120),
                        detail = (
                            if (result.ready) "READY"
                            else "FAILED: ${result.failReason ?: "unknown"}"
                            ).take(200) + " | " +
                            result.log.takeLast(6).joinToString(" | ").take(800),
                        provenance = "OBSERVED",
                        confidence = 1.0
                    )
                )
            }
        }
    }

    /**
     * Starts the autonomous Observe→…→Extend cycle on the open investigation.
     * Full autonomous mode: every planned step runs on its own at any risk
     * level — no confirmation gates. Each step's risk level is recorded and
     * every action is logged to the timeline. When the plan needs a
     * capability nothing registered can do, the loop tries to build and test
     * one from existing tools (EXTEND phase, max 6 per investigation).
     */
    fun startAutoLoop(investigationId: String) {
        val inv = activeInvestigation?.takeIf { it.id == investigationId } ?: run {
            statusLine = "Open the investigation first."
            return
        }
        if (loopJob?.isActive == true) {
            statusLine = "Auto-investigate is already running."
            return
        }
        val provider = ReasonerPicker.pick(appRef, localReasoner)
        currentProvider = provider
        updateReasoningMode(provider)
        val initial = LoopState(
            investigationId = inv.id,
            target = targetOf(inv),
            hypotheses = try {
                provider.hypotheses()
            } catch (_: Exception) {
                emptyList()
            }
        )
        loopLog.clear()
        pendingApprovalStep = null
        loopState = initial
        loopRunning.set(true)
        loopActive = true
        statusLine = "Auto-investigate running — looking, researching, planning, acting and learning. Every step runs on its own; each action is logged."
        loopJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runLoop(initial, provider)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Auto-investigate stopped: ${e.message ?: "unknown problem"}"
                    loopActive = false
                }
            }
        }
    }

    /**
     * You approved the waiting step: run exactly that one operation (recorded
     * as a user-approved tap), then resume the autonomous cycle.
     */
    fun approveAndContinue(step: PlanStep) {
        val inv = activeInvestigation ?: run {
            statusLine = "Open the investigation first."
            return
        }
        if (loopJob?.isActive == true) {
            statusLine = "It's still running — wait for it to pause."
            return
        }
        pendingApprovalStep = null
        loopRunning.set(true)
        loopActive = true
        statusLine = "Running your approved step…"
        loopJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // The approved step is the loop's action — no auto-triggers from it.
                eventMute.set(true)
                executeStep(inv.id, step, approved = true)
                eventMute.set(false)
                withContext(Dispatchers.Main) { loadTimeline(inv.id) }
                val resumed = (loopState?.copy(status = "RUNNING", pendingStep = null))
                    ?: LoopState(investigationId = inv.id, target = targetOf(inv))
                withContext(Dispatchers.Main) { loopState = resumed }
                runLoop(resumed, currentProvider)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Approved step failed: ${e.message ?: "unknown problem"}"
                    loopActive = false
                }
            }
        }
    }

    /** Stops the autonomous cycle. Already-recorded results stay on the timeline. */
    fun stopLoop() {
        loopRunning.set(false)
        loopJob?.cancel()
        loopJob = null
        loopActive = false
        statusLine = "Auto-investigate stopped. Everything found so far is on the timeline."
    }

    // ---------- event bus: auto-triggers ----------

    /** Never auto-trigger twice for the same target within 60 seconds. */
    private val lastAutoTriggerMs = mutableMapOf<String, Long>()
    /**
     * True while the loop itself is acting — its own probes must never
     * spawn new investigations or double-record their results.
     */
    private val eventMute = AtomicBoolean(false)

    private fun handleIntelEvent(event: IntelEvent) {
        if (eventMute.get()) return
        when (event) {
            is IntelEvent.BleTargetSelected -> {
                // Zero-tap bring-up: the user tapped the target ONCE — Ghost Tap
                // runs the whole bring-up (connect → enumerate → map controls)
                // and lands on the target's control screen. Then the USER drives.
                if (!dedupeTrigger("bringup:BLE:${event.deviceId}")) return
                bringUpTarget(
                    BringUpTarget(
                        kind = BringUpKind.BLE,
                        id = event.deviceId,
                        title = event.name.ifBlank { event.deviceId },
                        detail = event.advertised.take(400)
                    ),
                    autoNavigate = true
                )
            }
            is IntelEvent.NfcTagRead -> {
                // A real tag was physically tapped — bring up the NFC tools.
                val nfcTarget = BringUpTarget(
                    kind = BringUpKind.NFC,
                    id = "nfc:${event.summary.hashCode()}",
                    title = "NFC tag",
                    detail = event.techList.joinToString(", ")
                )
                if (!dedupeTrigger(bringUpKey(nfcTarget))) return
                bringUpTarget(nfcTarget, autoNavigate = true)
            }
            is IntelEvent.NetworkHostFound -> {
                // Scan-discovered host: run bring-up (ping + port scan) but
                // don't yank screens — the user never tapped this host.
                if (!dedupeTrigger("bringup:NETWORK:${event.ip}")) return
                bringUpTarget(
                    BringUpTarget(
                        kind = BringUpKind.NETWORK,
                        id = event.ip,
                        title = event.ip,
                        detail = buildString {
                            if (event.mac.isNotBlank()) append("Hardware address ${event.mac}. ")
                            if (event.hostname.isNotBlank()) append("Hostname ${event.hostname}.")
                        }.trim()
                    ),
                    autoNavigate = false
                )
            }
            is IntelEvent.VisualCapture -> {
                val key = "visual:${event.text.hashCode()}:${event.barcodes.hashCode()}"
                if (!dedupeTrigger(key)) return
                val evidence = buildString {
                    append("Image analyzed (${event.source}). ")
                    if (event.text.isNotBlank()) append("Text seen: ${event.text.take(600)} ")
                    if (event.barcodes.isNotBlank()) {
                        append("Barcodes: ${event.barcodes.take(300)} ")
                    }
                    if (event.markings.isNotBlank()) {
                        append("Markings: ${event.markings.take(300)}")
                    }
                }.trim().take(1500)
                triggerAutoInvestigation(
                    IntelTarget(
                        kind = "VISUAL",
                        title = "Photo (${event.source})",
                        text = evidence,
                        barcodes = event.barcodes.split(";")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .take(5)
                    )
                )
            }
            is IntelEvent.ToolOpResult -> {
                recordToolEvent(
                    title = "${if (event.ok) "OK" else "FAIL"} — ${event.capabilityId}",
                    detail = event.summary,
                    ok = event.ok
                )
                // Cross-tool: this result becomes context for the next tool,
                // even with no investigation open.
                correlateGlobally(event.summary, event.capabilityId)
            }
            is IntelEvent.IntelError -> {
                recordToolEvent(
                    title = "Error — ${event.source}",
                    detail = event.message,
                    ok = false
                )
                correlateGlobally(event.message, event.source)
            }
            is IntelEvent.UsbState -> {
                recordToolEvent(
                    title = if (event.connected) "USB connected" else "USB disconnected",
                    detail = event.detail,
                    ok = event.connected
                )
                // A real USB serial link opened — bring it to controllable state.
                if (event.connected) {
                    if (!dedupeTrigger("bringup:USB:usb")) return
                    bringUpTarget(
                        BringUpTarget(
                            kind = BringUpKind.USB,
                            id = "usb",
                            title = "USB serial",
                            detail = event.detail.take(300)
                        ),
                        autoNavigate = true
                    )
                }
            }
            // BleConnected: informational, no auto-trigger.
            else -> Unit
        }
    }

    /**
     * A tool somewhere in the app finished something. If an investigation is
     * open, the result lands on its timeline so every tool shares one
     * context — and any identifiers in it are checked against past
     * investigations (cross-tool correlation). With no open investigation
     * the event is dropped: the tool's own screen already showed the result.
     */
    private fun recordToolEvent(title: String, detail: String, ok: Boolean) {
        val inv = activeInvestigation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = inv.id,
                        ts = System.currentTimeMillis(),
                        kind = "result",
                        title = title.take(120),
                        detail = detail.take(1500),
                        provenance = "OBSERVED",
                        confidence = if (ok) 1.0 else 0.9
                    )
                )
                correlateIdentifiers(inv, detail)
                withContext(Dispatchers.Main) { loadTimeline(inv.id) }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Cross-tool correlation: when an identifier (MAC, IP, hostname) shows
     * up in one tool's results and past investigations mention the same
     * identifier, link them — a shared note on this investigation's
     * timeline, so findings from one tool inform work in another.
     */
    private suspend fun correlateIdentifiers(inv: Investigation, text: String) {
        try {
            val ids = extractIdentifiers(text)
            if (ids.isEmpty()) return
            val titles = try {
                db.investigationDao().listAll().associate { it.id to it.title }
            } catch (_: Exception) {
                emptyMap()
            }
            val linked = mutableListOf<String>()
            for (id in ids.take(4)) {
                val hits = try {
                    db.observationDao().search(id)
                } catch (_: Exception) {
                    emptyList()
                }.filter { it.investigationId != inv.id }.take(2)
                for (h in hits) {
                    val other = titles[h.investigationId] ?: "another investigation"
                    linked.add(
                        "“$id” also appears in “$other” " +
                            "([${h.kind}] ${h.title.take(80)})."
                    )
                }
            }
            if (linked.isNotEmpty()) {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = inv.id,
                        ts = System.currentTimeMillis(),
                        kind = "link",
                        title = "Linked: same identifier seen before",
                        detail = linked.distinct().take(6).joinToString("\n").take(1500),
                        provenance = "OBSERVED",
                        confidence = 0.9
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun extractIdentifiers(text: String): List<String> {
        val out = mutableListOf<String>()
        Regex("(?i)\\b([0-9A-F]{2}[:-]){5}[0-9A-F]{2}\\b").findAll(text)
            .forEach { out.add(it.value) }
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").findAll(text)
            .forEach { out.add(it.value) }
        Regex("(?i)\\b([a-z0-9-]+\\.)+(com|net|org|io|local|lan)\\b").findAll(text)
            .forEach { out.add(it.value) }
        return out.distinct().take(6)
    }

    /**
     * Cross-tool correlation that works WITHOUT an open investigation.
     * Tool results published to the bus are matched against everything the
     * engine has seen before (bring-ups, past investigations); hits are
     * written as link notes under the shared "bringup" context and logged,
     * so one tool's output becomes visible context for the next tool.
     */
    private fun correlateGlobally(text: String, source: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ids = extractIdentifiers(text)
                if (ids.isEmpty()) return@launch
                val linked = mutableListOf<String>()
                for (id in ids.take(4)) {
                    val hits = try {
                        db.observationDao().search(id)
                    } catch (_: Exception) {
                        emptyList()
                    }.filter { it.kind == "bringup" || it.kind == "result" }.take(2)
                    for (h in hits) {
                        linked.add(
                            "“$id” ($source) also appears in [${h.kind}] ${h.title.take(80)}."
                        )
                    }
                }
                if (linked.isNotEmpty()) {
                    db.observationDao().insert(
                        Observation(
                            id = UUID.randomUUID().toString(),
                            investigationId = "bringup",
                            ts = System.currentTimeMillis(),
                            kind = "link",
                            title = "Cross-tool link: same identifier seen before",
                            detail = linked.distinct().take(6).joinToString("\n").take(1500),
                            provenance = "OBSERVED",
                            confidence = 0.9
                        )
                    )
                    withContext(Dispatchers.Main) {
                        loopLog.add(
                            "Found a link: ${linked.distinct().take(2).joinToString(" ")}"
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun dedupeTrigger(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAutoTriggerMs[key] ?: 0L
        if (now - last < 60_000) return false
        lastAutoTriggerMs[key] = now
        return true
    }

    /**
     * Opens an investigation and starts the loop for diagnostics — used for
     * visual captures and the manual INVESTIGATE button. Target selections
     * (BLE/NFC/network/USB) no longer come here: they run zero-tap bring-up
     * and land on the target's control screen instead. The investigations
     * screen remains as the diagnostics/log view.
     * If the loop is already busy, say so honestly instead of piling on.
     */
    private fun triggerAutoInvestigation(target: IntelTarget) {
        if (loopRunning.get()) {
            statusLine =
                "Noticed \"${target.title}\" — the automatic investigation is already busy. " +
                "Tap INVESTIGATE on it when you're ready."
            return
        }
        loopLog.add("Noticed \"${target.title}\" — opening an investigation.")
        investigateAndAutoStart(target)
    }

    /**
     * Restore saved extensions (scripted + composed capabilities) from the
     * last session. Each definition is re-validated; scripts are re-registered
     * with their STORED test report (no re-execution at startup). Anything
     * that fails restore is recorded — never silent.
     */
    private fun restoreExtensions() {
        viewModelScope.launch(Dispatchers.IO) {
            val restored = mutableListOf<String>()
            val problems = mutableListOf<String>()
            try {
                for (obs in db.observationDao().byKind("script_def")) {
                    val def = try {
                        ScriptDef.fromJson(obs.detail)
                    } catch (_: Exception) {
                        null
                    }
                    if (def == null) {
                        problems.add("A saved script couldn't be read — skipped.")
                        continue
                    }
                    val regId = "script.${def.id}"
                    if (CapabilityRegistry.get(regId) != null) {
                        restored.add("$regId (already live)")
                        continue
                    }
                    val errors = ScriptRuntime.validate(def.jsSource)
                    if (errors.isNotEmpty()) {
                        problems.add(
                            "Script '${def.title}' failed its check again: " +
                                errors.joinToString("; ").take(160)
                        )
                        continue
                    }
                    val report = ScriptTestReport(
                        passed = true,
                        notes = listOf(
                            "Restored from the saved version; the original test notes were kept: " +
                                def.testReport.take(300)
                        ),
                        resultJson = ""
                    )
                    val ok = try {
                        ScriptRuntime.register(def, engine, db, report, persist = false)
                    } catch (e: Exception) {
                        problems.add(
                            "Script '${def.title}' crashed while loading again: " +
                                (e.message ?: "unknown problem")
                        )
                        false
                    }
                    if (ok) restored.add(regId)
                }
                for (obs in db.observationDao().byKind("dynamic_capability")) {
                    val def = try {
                        DynamicCapabilityDef.fromJson(obs.detail)
                    } catch (_: Exception) {
                        null
                    }
                    if (def == null) {
                        problems.add("A saved control couldn't be read — skipped.")
                        continue
                    }
                    if (CapabilityRegistry.get(def.id) != null) {
                        restored.add("${def.id} (already live)")
                        continue
                    }
                    val errors = DynamicCapabilityRuntime.validate(def)
                    if (errors.isNotEmpty()) {
                        problems.add(
                            "Saved control '${def.title}' failed its check again: " +
                                errors.joinToString("; ").take(160)
                        )
                        continue
                    }
                    val report = TestReport(
                        passed = true,
                        stepResults = emptyList(),
                        notes = listOf(
                            "Restored from a saved version that was tested before; " +
                                "run it again to check it still works."
                        )
                    )
                    val ok = try {
                        DynamicCapabilityRuntime.register(def, engine, report, null)
                    } catch (e: Exception) {
                        problems.add(
                            "Saved control '${def.title}' crashed while loading again: " +
                                (e.message ?: "unknown problem")
                        )
                        false
                    }
                    if (ok) restored.add(def.id)
                }
            } catch (e: Exception) {
                problems.add("Restoring saved extras failed: ${e.message ?: "unknown problem"}")
            }
            // Restoration problems go on the record — never silent.
            if (problems.isNotEmpty()) {
                try {
                    db.observationDao().insert(
                        Observation(
                            id = UUID.randomUUID().toString(),
                            investigationId = "",
                            ts = System.currentTimeMillis(),
                            kind = "note",
                            title = "Extension restore: ${problems.size} problem(s)",
                            detail = problems.joinToString("\n").take(1500),
                            provenance = "OBSERVED",
                            confidence = 1.0
                        )
                    )
                } catch (_: Exception) {
                }
            }
            withContext(Dispatchers.Main) {
                restoredExtensions = restored.toList()
                restoreProblems = problems.toList()
                if (restored.isNotEmpty() || problems.isNotEmpty()) {
                    statusLine = buildString {
                        if (restored.isNotEmpty()) {
                            append("Restored saved extras (${restored.size}). ")
                        }
                        if (problems.isNotEmpty()) {
                            append("${problems.size} problems while restoring — details are on the timeline.")
                        }
                    }.trim()
                    if (problems.isNotEmpty()) {
                        loopLog.add("Problems restoring: " + problems.first().take(200))
                    }
                }
            }
        }
    }

    /** The cycle driver: one full Observe→Learn round per iteration, plus EXTEND. */
    private suspend fun runLoop(initial: LoopState, provider: ReasoningProvider) {
        val inv = activeInvestigation ?: return
        val loop = InvestigationLoop(engine, provider, db, learnedStore)
        // Script findings land on this investigation's timeline while we run.
        ScriptRuntime.currentInvestigationId = inv.id
        var state = initial
        while (loopRunning.get() && (state.status == "RUNNING" || state.status == "EXTENDING")) {
            val events = mutableListOf<String>()
            // The loop's own probes must never auto-trigger new investigations.
            eventMute.set(true)
            state = try {
                if (state.status == "EXTENDING") {
                    events.add("Building the missing control…")
                    loop.tryExtend(state) { events.add(it) }
                } else {
                    loop.runCycle(state) { events.add(it) }
                }
            } catch (e: Exception) {
                events.add("That step failed safely: ${e.message ?: "unknown problem"}")
                state.copy(
                    status = "DEAD_END",
                    endReason = "A step crashed safely (${e.message ?: "unknown problem"}) — stopping."
                )
            }
            eventMute.set(false)
            // VNIC observes every round into its own persistent memory.
            // onLoopRound never throws and never blocks the loop.
            runCatching { vnicEngine.onLoopRound(state, events) }
            val done = state
            withContext(Dispatchers.Main) {
                events.forEach { loopLog.add(it) }
                loopState = done
                hypotheses = try {
                    provider.hypotheses()
                } catch (_: Exception) {
                    hypotheses
                }
                loadTimeline(inv.id)
            }
            if (done.status == "WAITING_USER") {
                val waiting = done
                withContext(Dispatchers.Main) {
                    pendingApprovalStep = waiting.pendingStep
                    loopActive = false
                    val title = waiting.pendingStep?.let {
                        CapabilityRegistry.get(it.capabilityId)?.title ?: it.capabilityId
                    } ?: "a step"
                    statusLine = "Paused on \"$title\" — waiting for your go-ahead."
                }
                loopRunning.set(false)
                return
            }
        }
        val final = state
        ScriptRuntime.currentInvestigationId = ""
        withContext(Dispatchers.Main) {
            loopActive = false
            when (final.status) {
                "COMPLETE" -> {
                    statusLine = "Auto-investigation complete. ${final.endReason}"
                    viewModelScope.launch(Dispatchers.IO) {
                        val saved = try {
                            ProcedureSynthesizer.maybeSaveProcedure(final, db)
                        } catch (_: Exception) {
                            null
                        }
                        withContext(Dispatchers.Main) {
                            if (saved != null) {
                                statusLine = "Auto-investigation complete. Saved \"${saved.name}\" so you can run it again any time."
                                loopLog.add("Saved \"${saved.name}\" — run it again from PROCEDURES any time.")
                            }
                            refreshLists()
                            loadTimeline(inv.id)
                        }
                    }
                }
                "DEAD_END" -> {
                    statusLine = "Auto-investigation stopped. ${final.endReason}"
                    // Package 6 (C-18 / F-45): a stopped run still proves its
                    // steps — keep them as a reusable plan, named so it says
                    // the run stopped short.
                    viewModelScope.launch(Dispatchers.IO) {
                        val saved = try { ProcedureSynthesizer.maybeSaveProcedure(final, db) } catch (_: Exception) { null }
                        withContext(Dispatchers.Main) {
                            if (saved != null) {
                                statusLine = "Auto-investigation stopped. Saved \"${saved.name}\" — the steps that worked are kept so you can run them again."
                                loopLog.add("Saved \"${saved.name}\" — run it again from PROCEDURES any time.")
                            }
                            refreshLists()
                            loadTimeline(inv.id)
                        }
                    }
                }
                "REVIEW" -> {
                    // Package 6 (C-16/C-17): the loop drafted a new control
                    // and stopped for review. Nothing is switched on until
                    // the user approves it.
                    pendingExtensionReview = final.pendingDraft
                    statusLine = "A new control is ready for you to check — nothing is switched on until you approve it."
                }
                else -> {
                    if (loopRunning.get()) {
                        statusLine = "Auto-investigate finished."
                    }
                }
            }
            refreshLists()
            loadTimeline(inv.id)
        }
    }

    private fun targetOf(inv: Investigation): IntelTarget = IntelTarget(
        kind = inv.targetKind,
        title = inv.title,
        text = inv.targetText,
        markings = inv.markings.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        barcodes = inv.barcodes.split("\u001F").map { it.trim() }.filter { it.isNotEmpty() }
    )

    init {
        refreshLists()
        updateReasoningMode(ReasonerPicker.pick(appRef, localReasoner))
        restoreExtensions()
        // Event bus: real things the app observed become investigations.
        viewModelScope.launch {
            try {
                IntelEventBus.events.collect { event ->
                    try {
                        handleIntelEvent(event)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun refreshLists() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val invs = db.investigationDao().listAll()
                val procs = db.procedureDao().listAll()
                // The brain's own saved plans live in a separate store
                // (Package 6 / F-10): count them too, so the two counts agree.
                // Its own try/catch — a brain problem must never block the lists.
                val vnicCount = try { vnicEngine.status.value.procedureCount } catch (_: Exception) { vnicProcedureCount }
                val vnicProcs = try { vnicEngine.listVnicProcedures() } catch (_: Exception) { emptyList() }
                withContext(Dispatchers.Main) {
                    investigations.clear()
                    investigations.addAll(invs)
                    procedures.clear()
                    procedures.addAll(procs)
                    vnicProcedureCount = vnicCount
                    vnicProcedures.clear()
                    vnicProcedures.addAll(vnicProcs)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun refreshReasonerName() {
        val provider = ReasonerPicker.pick(appRef, localReasoner)
        reasonerName = provider.name
        engineStatus = provider.statusLine
        updateReasoningMode(provider)
        // VNIC Defect 1: the VNIC engine holds its own reasoner — re-read
        // the just-saved settings into it too, so a saved key reaches the
        // VNIC with no app restart. (Forces the lazy engine to build on
        // SAVE/CLEAR, the same as attachVesp32 already does.)
        runCatching { vnicEngine.refreshReasonerFromSettings() }
    }

    /** Runs the reflection-based capability scan over the real ViewModels. */
    /** Delegates to [scanCapabilitiesNow] so the operations runner can wait for the same scan (Package 6). */
    fun runCapabilityScan() {
        viewModelScope.launch(Dispatchers.IO) { scanCapabilitiesNow() }
    }

    /**
     * Runs the capability scan now and waits for it (Package 6 / C-03).
     * Null when the scan itself failed.
     */
    suspend fun scanCapabilitiesNow(): ScanReport? {
        return try {
            val report = CapabilityScanner.scan(engine.viewModels(), CapabilityRegistry)
            withContext(Dispatchers.Main) { scanReport = report }
            report
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                statusLine = "Check of available controls failed: ${e.message ?: "unknown problem"}"
            }
            null
        }
    }

    /**
     * Runs on-device + web research for the open investigation now and
     * waits for it (Package 6 / C-09, C-10). Empty when there is no open
     * investigation or the research failed.
     */
    suspend fun researchNow(): List<Finding> {
        val inv = activeInvestigation ?: return emptyList()
        return try {
            Researcher.researchWithWeb(targetOf(inv), db, learnedStore, currentProvider, inv.id)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Asks the brain to draft a new control for the open investigation's
     * gap, and waits (Package 6 / C-16, C-17). The draft is only a draft —
     * it is shown for review and nothing is switched on until
     * [approveExtensionReview] runs. Null when there is no gap to draft for.
     */
    suspend fun draftExtensionNow(): ExtensionDraft? {
        val inv = activeInvestigation ?: return null
        val state = loopState ?: return null
        val draft = buildExtensionDraft(targetOf(inv), state.pendingGapIds, currentProvider)
        if (draft != null) {
            withContext(Dispatchers.Main) { pendingExtensionReview = draft }
        }
        return draft
    }

    /**
     * Approve the control waiting for review: test it for real, switch it
     * on only if the test passes, then carry the investigation on. The test
     * result is written on the timeline either way.
     */
    fun approveExtensionReview() {
        val draft = pendingExtensionReview ?: run {
            statusLine = "Nothing waiting for review."
            return
        }
        val inv = activeInvestigation ?: run {
            statusLine = "Open an investigation first."
            return
        }
        pendingExtensionReview = null
        loopRunning.set(true)
        loopActive = true
        statusLine = "Testing \"${draft.title}\"…"
        loopJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                eventMute.set(true)
                val result = try {
                    engine.run(draft.proposeId, draft.proposeArgs)
                } catch (e: Exception) {
                    OpResult(false, "Crashed while testing the new control: ${e.message ?: "unknown error"}")
                }
                eventMute.set(false)
                try { currentProvider.noteResult(draft.proposeId, result) } catch (_: Exception) { }
                try {
                    db.observationDao().insert(
                        Observation(
                            id = UUID.randomUUID().toString(), investigationId = inv.id,
                            ts = System.currentTimeMillis(), kind = "capability",
                            title = if (result.ok) "Capability built and tested (${draft.proposeId})"
                            else "Capability build failed (${draft.proposeId})",
                            detail = (result.summary +
                                if (result.data.isNotBlank()) "\n" + result.data.take(1200) else "")
                                .take(2000),
                            provenance = "OBSERVED",
                            confidence = if (result.ok) 1.0 else 0.4
                        )
                    )
                } catch (_: Exception) { }
                withContext(Dispatchers.Main) { loadTimeline(inv.id) }
                if (result.ok) {
                    val resumed = (loopState?.copy(
                        status = "RUNNING", pendingDraft = null,
                        pendingGapIds = emptySet(),
                        lastProgressCycle = loopState?.cycles ?: 0
                    )) ?: LoopState(investigationId = inv.id, target = targetOf(inv))
                    withContext(Dispatchers.Main) {
                        loopState = resumed
                        statusLine = "New control \"${draft.title}\" switched on — carrying on."
                    }
                    runLoop(resumed, currentProvider)
                } else {
                    withContext(Dispatchers.Main) {
                        loopActive = false
                        loopState = loopState?.copy(
                            status = "DEAD_END",
                            endReason = "The new control failed its test: ${result.summary.take(200)}",
                            pendingDraft = null
                        )
                        statusLine = "The new control failed its test — nothing was switched on. ${result.summary.take(200)}"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Approved control failed: ${e.message ?: "unknown problem"}"
                    loopActive = false
                }
            }
        }
    }

    /**
     * Discard the control waiting for review: nothing is switched on, the
     * investigation stops here, and the choice is written on the timeline.
     */
    fun discardExtensionReview() {
        val draft = pendingExtensionReview ?: return
        pendingExtensionReview = null
        loopRunning.set(false)
        loopActive = false
        loopState = loopState?.copy(
            status = "DEAD_END",
            endReason = "You discarded the new control \"${draft.title}\" — stopping here.",
            pendingDraft = null
        )
        statusLine = "Discarded \"${draft.title}\". Nothing was switched on."
        val inv = activeInvestigation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(), investigationId = inv.id,
                        ts = System.currentTimeMillis(), kind = "note",
                        title = "Discarded new control: ${draft.title}",
                        detail = "You chose not to switch on \"${draft.title}\". Nothing was registered.",
                        provenance = "OBSERVED", confidence = 1.0
                    )
                )
                withContext(Dispatchers.Main) { loadTimeline(inv.id) }
            } catch (_: Exception) { }
        }
    }

    /**
     * Saves the proven steps of the finished (or stopped) run as a reusable
     * plan, and waits (Package 6 / C-18, F-45). Null when there is nothing
     * worth saving.
     */
    suspend fun saveLearnedProcedureNow(): Procedure? {
        val state = loopState ?: return null
        val saved = try { ProcedureSynthesizer.maybeSaveProcedure(state, db) } catch (_: Exception) { null }
        if (saved != null) refreshLists()
        return saved
    }

    // ---------- investigations ----------

    fun newInvestigation(target: IntelTarget) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                localReasoner.reset()
                val now = System.currentTimeMillis()
                val inv = Investigation(
                    id = UUID.randomUUID().toString(),
                    title = target.title,
                    targetKind = target.kind,
                    targetText = target.text.take(2000),
                    createdMs = now,
                    status = "open",
                    markings = target.markings.joinToString(","),
                    barcodes = target.barcodes.joinToString("\u001F")
                )
                db.investigationDao().insert(inv)
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = inv.id,
                        ts = now,
                        kind = "target",
                        title = "Target: ${target.title}",
                        detail = buildString {
                            append("Kind: ${target.kind}\n")
                            append(target.text.take(1500))
                            if (target.markings.isNotEmpty()) {
                                append("\nMarkings: ${target.markings.joinToString(", ")}")
                            }
                            if (target.barcodes.isNotEmpty()) {
                                append("\nBarcodes: ${target.barcodes.take(5).joinToString("; ")}")
                            }
                        },
                        provenance = "OBSERVED",
                        confidence = 1.0
                    )
                )
                // Memory recall BEFORE planning: what did we try before with
                // these markings/identifiers?
                val memoryNotes = recallNotes(target)
                if (memoryNotes.isNotBlank()) {
                    db.observationDao().insert(
                        Observation(
                            id = UUID.randomUUID().toString(),
                            investigationId = inv.id,
                            ts = System.currentTimeMillis(),
                            kind = "memory",
                            title = "Memory: seen before",
                            detail = memoryNotes.take(1500),
                            provenance = "OBSERVED",
                            confidence = 0.9
                        )
                    )
                }
                val rawPlan = Planner.planFor(target, CapabilityRegistry)
                val provider = ReasonerPicker.pick(appRef, localReasoner)
                currentProvider = provider
                withContext(Dispatchers.Main) {
                    activeInvestigation = inv
                    planSteps = rawPlan
                    engineStatus = provider.statusLine
                    reasonerName = provider.name
                    updateReasoningMode(provider)
                    statusLine = "Plan drafted (${rawPlan.count { it.auto }} run on their own, " +
                        "${rawPlan.count { !it.auto }} need a tap). Working on it…"
                }
                val knowledge = knowledgeString() +
                    (if (memoryNotes.isNotBlank()) "\nPAST FINDINGS:\n$memoryNotes" else "")
                val refined = try {
                    provider.refinePlan(target, rawPlan, knowledge)
                } catch (_: Exception) {
                    rawPlan
                }
                val validated = refined.filter { CapabilityRegistry.get(it.capabilityId) != null }
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = inv.id,
                        ts = System.currentTimeMillis(),
                        kind = "plan",
                        title = "Plan (${validated.size} steps) — ${provider.name}",
                        detail = validated.joinToString("\n") {
                            "${if (it.auto) "[Runs by itself" else "[Needs a tap"}·${it.riskLevel}] ${it.capabilityId} — ${it.why}"
                        },
                        provenance = "OBSERVED",
                        confidence = 1.0
                    )
                )
                withContext(Dispatchers.Main) {
                    planSteps = validated
                    hypotheses = provider.hypotheses()
                    statusLine = "Plan ready. Tap RUN DISCOVERY for the automatic checks, " +
                        "or approve the suggested steps one by one."
                    refreshLists()
                    loadTimeline(inv.id)
                    if (autoStartPending) {
                        autoStartPending = false
                        startAutoLoop(inv.id)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Couldn't start investigation: ${e.message ?: "unknown problem"}"
                }
            }
        }
    }

    /**
     * Memory recall: search past observations for each marking/identifier on
     * the target. Returns human-readable "tried before" notes (empty if none).
     */
    private suspend fun recallNotes(target: IntelTarget): String {
        return try {
            val keys = (target.markings +
                "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b".toRegex().findAll(target.text).map { it.value }.toList())
                .map { it.trim() }
                .filter { it.length >= 4 }
                .distinct()
                .take(6)
            if (keys.isEmpty()) return ""
            val notes = mutableListOf<String>()
            for (k in keys) {
                val hits = db.observationDao().search(k).take(3)
                for (h in hits) {
                    notes.add("“$k” was tried before: [${h.kind}] ${h.title} — ${h.detail.take(140)}")
                }
            }
            notes.distinct().take(8).joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    /** Rebuild the plan mid-investigation, deprioritizing steps that failed. */
    fun replan() {
        val inv = activeInvestigation ?: run {
            statusLine = "Open an investigation first."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { statusLine = "Re-planning from evidence…" }
                replanNow()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Re-plan failed: ${e.message ?: "unknown problem"}"
                }
            }
        }
    }

    /** Result of rebuilding a plan: the kept steps, plus steps left out because they failed twice before. */
    data class ReplanResult(val steps: List<PlanStep>, val excluded: List<String>)

    /**
     * Rebuilds the plan now and waits for it (Package 6 / B-075). Same
     * planning as [replan], plus one fix: steps that failed twice or more
     * on this investigation are LEFT OUT, not just moved to the end — a
     * re-plan that keeps retrying the same failing step is not a new plan.
     * The left-out steps are named in the plan note and the status line.
     */
    suspend fun replanNow(): ReplanResult {
        val inv = activeInvestigation ?: return ReplanResult(emptyList(), emptyList())
        val target = IntelTarget(
            kind = inv.targetKind,
            title = inv.title,
            text = inv.targetText,
            markings = inv.markings.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            barcodes = inv.barcodes.split("\u001F").map { it.trim() }.filter { it.isNotEmpty() }
        )
        val observations = try { db.observationDao().forInvestigation(inv.id) } catch (_: Exception) { emptyList() }
        val failCounts = observations
            .filter { it.kind == "result" && it.title.startsWith("FAIL") }
            .mapNotNull { it.title.substringAfter("FAIL — ", "").ifBlank { null } }
            .groupingBy { it }.eachCount()
        val failedIds = failCounts.keys
        val rawPlan = Planner.planFor(target, CapabilityRegistry, failedIds)
        val provider = ReasonerPicker.pick(appRef, localReasoner)
        currentProvider = provider
        val knowledge = knowledgeString()
        val refined = try {
            provider.refinePlan(target, rawPlan, knowledge)
        } catch (_: Exception) {
            rawPlan
        }
        val validated = refined.filter { CapabilityRegistry.get(it.capabilityId) != null }
        val excluded = validated.filter { (failCounts[it.capabilityId] ?: 0) >= 2 }
        val kept = validated.filter { (failCounts[it.capabilityId] ?: 0) < 2 }
        db.observationDao().insert(
            Observation(
                id = UUID.randomUUID().toString(),
                investigationId = inv.id,
                ts = System.currentTimeMillis(),
                kind = "plan",
                title = "Re-plan (${kept.size} steps) — ${provider.name}",
                detail = (kept.joinToString("\n") {
                    "${if (it.auto) "[Runs by itself" else "[Needs a tap"}·${it.riskLevel}] ${it.capabilityId} — ${it.why}"
                } + if (excluded.isNotEmpty()) {
                    "\nLeft out (failed twice before): ${excluded.joinToString { it.capabilityId }}"
                } else ""),
                provenance = "OBSERVED",
                confidence = 1.0
            )
        )
        withContext(Dispatchers.Main) {
            planSteps = kept
            approvalQueue = kept.filter { !it.auto }
            hypotheses = provider.hypotheses()
            engineStatus = provider.statusLine
            updateReasoningMode(provider)
            statusLine = "New plan from what we found. Failed steps moved to the end." +
                if (excluded.isNotEmpty()) " ${excluded.size} step(s) left out — they failed twice before." else ""
            loadTimeline(inv.id)
        }
        return ReplanResult(kept, excluded.map { it.capabilityId })
    }

    fun openInvestigation(inv: Investigation) {
        activeInvestigation = inv
        planSteps = emptyList()
        approvalQueue = emptyList()
        pendingExtensionReview = null
        loadTimeline(inv.id)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val obs = db.observationDao().forInvestigation(inv.id)
                val planObs = obs.lastOrNull { it.kind == "plan" }
                withContext(Dispatchers.Main) {
                    statusLine = if (planObs != null) {
                        "Opened \"${inv.title}\". Plan is on the timeline below."
                    } else {
                        "Opened \"${inv.title}\"."
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun closeInvestigation() {
        activeInvestigation = null
        planSteps = emptyList()
        approvalQueue = emptyList()
        pendingExtensionReview = null
        timeline.clear()
        statusLine = "No investigation open."
    }

    fun deleteInvestigation(inv: Investigation) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.observationDao().deleteFor(inv.id)
                db.investigationDao().delete(inv.id)
                withContext(Dispatchers.Main) {
                    if (activeInvestigation?.id == inv.id) closeInvestigation()
                    refreshLists()
                }
            } catch (_: Exception) {
            }
        }
    }

    fun markClosed() {
        val inv = activeInvestigation ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.investigationDao().setStatus(inv.id, "closed")
                withContext(Dispatchers.Main) { refreshLists() }
            } catch (_: Exception) {
            }
        }
    }

    // ---------- execution ----------

    /**
     * Runs every AUTO step in order. Full autonomous mode: steps run at any
     * risk level with no confirmation gates; each step's risk is recorded and
     * the action is logged to the timeline. Delegates to [runAutoSteps] so
     * the operations runner can wait for the same run (Package 6).
     */
    fun runDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runAutoSteps()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Discovery stopped: ${e.message ?: "unknown problem"}"
                }
            }
        }
    }

    /**
     * Runs every AUTO step in order and waits for it, then fills the
     * approvals queue with the steps that still need a tap (Package 6).
     * Returns a plain-words report of what ran and what is still waiting.
     */
    suspend fun runAutoSteps(): String {
        val inv = activeInvestigation ?: run {
            withContext(Dispatchers.Main) { statusLine = "Open an investigation first." }
            return "Open an investigation first."
        }
        val auto = planSteps.filter { it.auto }
        if (auto.isEmpty()) {
            withContext(Dispatchers.Main) {
                statusLine = "Nothing in this plan runs on its own — every step needs a tap."
                approvalQueue = planSteps.filter { !it.auto }
            }
            return "Nothing in this plan runs on its own — every step needs a tap."
        }
        withContext(Dispatchers.Main) {
            statusLine = "Running the automatic checks (${auto.size})…"
        }
        auto.forEachIndexed { i, step ->
            withContext(Dispatchers.Main) {
                statusLine = "Check ${i + 1}/${auto.size}: ${step.capabilityId}…"
            }
            executeStep(inv.id, step, approved = false)
        }
        val manual = planSteps.filter { !it.auto }
        withContext(Dispatchers.Main) {
            approvalQueue = manual
            statusLine = "Discovery done. Look over the timeline, then approve the suggested steps or save a procedure."
            loadTimeline(inv.id)
        }
        return buildString {
            append("Ran ${auto.size} automatic check(s). ")
            if (manual.isEmpty()) append("Nothing left needs a tap.")
            else append("${manual.size} step(s) still need your tap — approve them one at a time.")
        }
    }

    /**
     * Runs one step now and waits for it (Package 6) — the approvals queue
     * calls this. Returns a plain-words report. Takes the step off the
     * approvals queue when it has run.
     */
    suspend fun runStepNow(step: PlanStep): String {
        val inv = activeInvestigation ?: return "Open an investigation first."
        val result = executeStep(inv.id, step, approved = true)
        var remaining = 0
        withContext(Dispatchers.Main) {
            approvalQueue = approvalQueue.filterNot { it.capabilityId == step.capabilityId }
            remaining = approvalQueue.size
            loadTimeline(inv.id)
        }
        val capTitle = CapabilityRegistry.get(step.capabilityId)?.title ?: step.capabilityId
        return if (result.ok) {
            "Did \"$capTitle\". " + if (remaining == 0) "No steps left waiting."
            else "$remaining step(s) still waiting for your tap."
        } else {
            "\"$capTitle\" didn't work: ${result.summary}"
        }
    }

    /**
     * Takes one step off the plan (Package 6 / B-074) — a bad step no longer
     * needs a whole re-plan to remove. Also takes it off the approvals
     * queue, and writes a note on the timeline saying it was removed.
     */
    fun removePlanStep(step: PlanStep) {
        val inv = activeInvestigation ?: run {
            statusLine = "Open an investigation first."
            return
        }
        planSteps = planSteps.filterNot { it.capabilityId == step.capabilityId }
        approvalQueue = approvalQueue.filterNot { it.capabilityId == step.capabilityId }
        val title = CapabilityRegistry.get(step.capabilityId)?.title ?: step.capabilityId
        statusLine = "Took \"$title\" off the plan."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(), investigationId = inv.id,
                        ts = System.currentTimeMillis(), kind = "note",
                        title = "Took a step off the plan: $title",
                        detail = "You took \"${step.capabilityId}\" off the plan.",
                        provenance = "OBSERVED", confidence = 1.0
                    )
                )
                withContext(Dispatchers.Main) { loadTimeline(inv.id) }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Runs one step the user explicitly approved (APPROVE & RUN / RUN tap).
     * The approval is recorded in the timeline — one tap approves exactly one
     * operation, never a blanket grant.
     */
    fun runStep(step: PlanStep) {
        val inv = activeInvestigation ?: run {
            statusLine = "Open an investigation first."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    statusLine = "Running ${step.capabilityId}…"
                }
                executeStep(inv.id, step, approved = true)
                withContext(Dispatchers.Main) {
                    statusLine = "Step done — see the timeline."
                    loadTimeline(inv.id)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Step failed: ${e.message ?: "unknown problem"}"
                }
            }
        }
    }

    private suspend fun executeStep(investigationId: String, step: PlanStep, approved: Boolean): OpResult {
        val cap = CapabilityRegistry.get(step.capabilityId)
        // Mute the event bus while the engine runs: the tool ViewModels now
        // publish their own result events, and the loop records results
        // itself — without the mute every step would be logged twice.
        eventMute.set(true)
        val result = if (cap == null) {
            com.ghosttap.core.OpResult(false, "Unknown control '${step.capabilityId}' — skipped.")
        } else {
            engine.run(step.capabilityId, step.args)
        }
        eventMute.set(false)
        // Feed the result back into the reasoning engine's evidence.
        try {
            currentProvider.noteResult(step.capabilityId, result)
        } catch (_: Exception) {
        }
        try {
            db.observationDao().insert(
                Observation(
                    id = UUID.randomUUID().toString(),
                    investigationId = investigationId,
                    ts = System.currentTimeMillis(),
                    kind = "result",
                    title = "${if (result.ok) "OK" else "FAIL"} — ${step.capabilityId}",
                    detail = buildString {
                        // An observed failure is itself an observed fact.
                        if (approved && (cap?.riskLevel ?: "READ") != "READ") {
                            append("You approved this tap.\n")
                        }
                        append(result.summary)
                        if (result.data.isNotBlank() && result.data != "manual") {
                            append("\n")
                            append(result.data.take(1500))
                        }
                    },
                    provenance = "OBSERVED",
                    confidence = if (result.ok) 1.0 else 0.9
                )
            )
        } catch (_: Exception) {
        }
        try {
            withContext(Dispatchers.Main) { hypotheses = currentProvider.hypotheses() }
        } catch (_: Exception) {
        }
        return result
    }

    // ---------- notes / procedures / search ----------

    fun addNote(text: String) {
        val inv = activeInvestigation ?: return
        if (text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.observationDao().insert(
                    Observation(
                        id = UUID.randomUUID().toString(),
                        investigationId = inv.id,
                        ts = System.currentTimeMillis(),
                        kind = "note",
                        title = "Note",
                        detail = text.take(2000),
                        provenance = "OBSERVED",
                        confidence = 1.0
                    )
                )
                withContext(Dispatchers.Main) { loadTimeline(inv.id) }
            } catch (_: Exception) {
            }
        }
    }

    fun saveAsProcedure(name: String) {
        if (name.isBlank() || planSteps.isEmpty()) {
            statusLine = "Need a name and a plan first."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stored = planSteps.map {
                    StoredStep(it.capabilityId, it.args, it.why, it.auto)
                }
                val stepsJson = json.encodeToString(ListSerializer(StoredStep.serializer()), stored)
                db.procedureDao().insert(
                    Procedure(
                        id = UUID.randomUUID().toString(),
                        name = name.take(80),
                        stepsJson = stepsJson,
                        createdMs = System.currentTimeMillis()
                    )
                )
                withContext(Dispatchers.Main) {
                    statusLine = "Procedure \"$name\" saved."
                    refreshLists()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Couldn't save: ${e.message ?: "unknown problem"}"
                }
            }
        }
    }

    /** Delegates to [loadProcedureNow] so the operations runner can wait for the same load (Package 6). */
    fun loadProcedure(p: Procedure) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                loadProcedureNow(p)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Couldn't load: ${e.message ?: "unknown problem"}"
                }
            }
        }
    }

    /**
     * Loads a saved plan onto the open investigation now, and waits
     * (Package 6 / B-080). False when it doesn't fit this case file: the
     * plan's kind is read off its steps, and a plan made for one kind of
     * target refuses to load onto another — with a plain-words refusal and
     * nothing loaded. True when the plan loaded.
     */
    suspend fun loadProcedureNow(p: Procedure): Boolean {
        val inv = activeInvestigation ?: run {
            withContext(Dispatchers.Main) {
                statusLine = "Open an investigation first, then load the procedure onto it."
            }
            return false
        }
        val stored: List<StoredStep> = try {
            json.decodeFromString(
                ListSerializer(StoredStep.serializer()),
                p.stepsJson
            )
        } catch (_: Exception) {
            emptyList()
        }
        val categories = stored.mapNotNull { CapabilityRegistry.get(it.id)?.category }
        if (!ProcedureKinds.matches(inv.targetKind, categories)) {
            val planKind = categories.firstOrNull { ProcedureKinds.isConcreteKind(it) }
            withContext(Dispatchers.Main) {
                statusLine = "That plan was made for ${ProcedureKinds.plainKindName(planKind ?: "")} — this case file is about ${ProcedureKinds.plainKindName(inv.targetKind)}. It won't fit here; make a new plan for this one instead."
            }
            return false
        }
        val rebuilt = stored.mapNotNull { s ->
            val cap = CapabilityRegistry.get(s.id) ?: return@mapNotNull null
            // Full autonomous mode: auto is kept as stored.
            PlanStep(s.id, s.args, s.why.ifBlank { cap.title }, s.auto, cap.riskLevel)
        }
        db.observationDao().insert(
            Observation(
                id = UUID.randomUUID().toString(),
                investigationId = inv.id,
                ts = System.currentTimeMillis(),
                kind = "plan",
                title = "Procedure loaded: ${p.name} (${rebuilt.size} steps)",
                detail = rebuilt.joinToString("\n") {
                    "${if (it.auto) "[Runs by itself" else "[Needs a tap"}·${it.riskLevel}] ${it.capabilityId} — ${it.why}"
                },
                provenance = "OBSERVED",
                confidence = 1.0
            )
        )
        withContext(Dispatchers.Main) {
            planSteps = rebuilt
            approvalQueue = rebuilt.filter { !it.auto }
            statusLine = "Procedure \"${p.name}\" loaded (${rebuilt.size} steps)."
            loadTimeline(inv.id)
        }
        return true
    }

    /**
     * Loads a plan the BRAIN saved (vnic_procedures) onto the open
     * investigation, through the SAME shared path as any other saved
     * plan (Package 7 / E-37 / F-10 — the unification decision: NO
     * data is migrated between the two stores; the brain's store stays
     * where it is and its plans become loadable here instead).
     *
     * The brain's steps are plain capability ids ("ble.scan",
     * "ble.connect", …); they are rebuilt as [StoredStep]s and handed
     * to [loadProcedureNow], so the kind-match refusal applies exactly
     * as for a plan saved here — a plan made for one kind of target
     * will not load onto another. The plan is also made runnable as a
     * capability first (best effort — a failed re-check never blocks
     * the load). The brain's record is never copied into intel.db and
     * never deleted: loading reads it, it stays where it was saved.
     */
    suspend fun loadVnicProcedureNow(name: String): Boolean {
        val stored = try {
            vnicEngine.listVnicProcedures().firstOrNull { it.name == name }
        } catch (_: Exception) {
            null
        } ?: run {
            withContext(Dispatchers.Main) {
                statusLine = "Couldn't find a brain-saved plan called \"$name\" any more."
            }
            return false
        }
        // Best effort: make it runnable as a capability too (E-23).
        // A failed re-check never blocks the load below.
        runCatching { vnicEngine.ensureProcedureRegistered(name) }
        val ids: List<String> = try {
            json.decodeFromString(ListSerializer(String.serializer()), stored.stepsJson)
        } catch (_: Exception) {
            emptyList()
        }
        if (ids.isEmpty()) {
            withContext(Dispatchers.Main) {
                statusLine = "That brain-saved plan has no steps in it — nothing to load."
            }
            return false
        }
        val stepsJson = json.encodeToString(
            ListSerializer(StoredStep.serializer()),
            ids.map { StoredStep(it, emptyMap(), "Step from the brain's saved plan.", false) }
        )
        return loadProcedureNow(
            Procedure(
                id = UUID.randomUUID().toString(),
                name = stored.name,
                stepsJson = stepsJson,
                createdMs = stored.updatedTs
            )
        )
    }

    /** Delegates to [loadVnicProcedureNow] so the operations runner can wait for the same load (Package 7). */
    fun loadVnicProcedure(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                loadVnicProcedureNow(name)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Couldn't load: ${e.message ?: "unknown problem"}"
                }
            }
        }
    }

    fun deleteProcedure(p: Procedure) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.procedureDao().delete(p.id)
                withContext(Dispatchers.Main) { refreshLists() }
            } catch (_: Exception) {
            }
        }
    }

    fun searchKnowledge(q: String) {
        if (q.isBlank()) {
            searchResults = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hits = db.observationDao().search(q)
                withContext(Dispatchers.Main) { searchResults = hits }
            } catch (_: Exception) {
            }
        }
    }

    // ---------- internals ----------

    private fun loadTimeline(iid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val obs = db.observationDao().forInvestigation(iid)
                withContext(Dispatchers.Main) {
                    timeline.clear()
                    timeline.addAll(obs)
                }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun knowledgeString(): String {
        return try {
            db.observationDao().latest(20)
                .joinToString("\n") { "${it.kind}: ${it.title}" }
                .take(3000)
        } catch (_: Exception) {
            ""
        }
    }
}
