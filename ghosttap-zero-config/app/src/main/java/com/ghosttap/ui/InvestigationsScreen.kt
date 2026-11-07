package com.ghosttap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.IntelViewModel
import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.IntelSettings
import com.ghosttap.core.IntelTarget
import com.ghosttap.core.investigate.InvestigationInput
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.LiveTargetStore
import com.ghosttap.core.investigate.bringUpTargetFor
import com.ghosttap.core.investigate.liveTargetKey
// FindingsReportCard lives in this same package (com.ghosttap.ui),
// supplied with the shared framework — no import needed, never redeclared here.
import com.ghosttap.core.vnic.VnicStatusPanel
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * INVESTIGATIONS — Ghost Tap's thinking screen.
 *
 * Plain-English contract of this screen:
 * - WHAT IT IS: a workspace where Ghost Tap investigates a target for you —
 *   something you photographed, a Bluetooth device, or anything you describe.
 * - WHAT IT DOES: drafts a plan of real Ghost Tap operations and runs every
 *   step itself at any risk level — no confirmation taps. Each step's risk
 *   is badged and every action is logged. Everything it learns lands on a
 *   timeline you can read.
 * - WHAT IT UNLOCKS: repeatable investigations — save a plan as a procedure
 *   and replay it on the next target; search everything it ever learned.
 * - WHAT TO DO NEXT: open an investigation (or start one below), tap
 *   RUN DISCOVERY, read the timeline, then tap RUN on any proposed step.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InvestigationsScreen(
    vm: IntelViewModel,
    onBack: () -> Unit,
    /**
     * Runs an operation id through the shared runner (Package 6 / D-31).
     * Null = call the ViewModel directly (standalone use, e.g. previews).
     */
    onRunOperation: ((String, String?) -> Unit)? = null,
    // MASTER PROMPT: commands from a live target's COMMAND CENTER run
    // through this runner (it reports the answer back to the
    // ViewModel); onOpenRoute opens a target's own function page.
    // Both fall back gracefully when null (standalone use).
    onRunLiveCommand: ((String, String?) -> Unit)? = null,
    onOpenRoute: ((String) -> Unit)? = null,
    onOpenWhatToDo: (() -> Unit)? = null,
    // ZERO-CONFIG — the intent box ("what I'm tryna do"): type it and
    // Ghost Tap routes it to a real capability and runs it. This is
    // separate from the case-file boxes below, which describe a thing
    // to investigate rather than an action to do. Null = no intent box.
    onIntent: ((String) -> Unit)? = null,
    intentAnswer: String = "",
    /**
     * DISCOVER (Investigations): the ViewModels the "Check everything
     * for me" button can reach, for a no-typing check across sections.
     * All optional — null when this screen is used standalone. What is
     * NOT reachable from here, by design: pictures (the camera/gallery
     * flow lives on the Visual screen), the practice board (its check
     * is screen-local on the Virtual screen), and tap cards (a tag must
     * be physically tapped). Those are said plainly on the button tap.
     */
    bleVm: com.ghosttap.GhostTapViewModel? = null,
    usbVm: com.ghosttap.UsbUartViewModel? = null,
    netVm: com.ghosttap.NetworkToolsViewModel? = null
) {
    // ZERO-CONFIG FIX (2026-11-06): the intent answer line is shared
    // across screens — clear it when this screen opens so a stale
    // outcome from another screen never shows here.
    LaunchedEffect(Unit) { vm.clearCommandAnswer() }
    val active = vm.activeInvestigation
    val panelScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // HOLD THE CONNECTION: read the Bluetooth link's tick so this
        // whole screen re-reads when a link changes state — a dropped
        // link hides its card's commands instead of leaving them up.
        @Suppress("UNUSED_VARIABLE")
        val bleTick = bleVm?.uiTick
        TerminalButton("BACK", onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "INVESTIGATIONS",
            color = Accent,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace
        )
        onOpenWhatToDo?.let { TerminalButton("What do you want to do?", it) }
        // MASTER PROMPT — INVESTIGATE THE UNFAMILIAR: one tap checks
        // across the sections this screen can reach — Bluetooth, USB,
        // and networks — with no typing. Findings land as LIVE TARGETS
        // below: live things to connect to, not a history list. What
        // it does NOT do is said plainly in the status line on tap:
        // pictures need the camera screen, the practice board needs
        // its own screen, and tap cards need a physical tap. Nothing
        // connects or controls anything — investigation only.
        TerminalButton("Investigate the unfamiliar") {
            vm.refreshFindings()
            bleVm?.discoverBluetooth()
            usbVm?.discoverUsb()
            netVm?.discoverNetwork()
            vm.setStatus(
                "Checking Bluetooth, USB, and networks now — live targets land below. " +
                    "Pictures, the practice board, and tap cards can't be checked from here: " +
                    "use the camera screen for a picture, the practice-board screen for the board, " +
                    "and hold a tag to the phone for a tap card. Tap Check for new targets below to see what lands."
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        ExplainerCard(
            "A case file for one thing — a device, a label, an address.",
            "The app makes a plan from its real tools and runs the steps itself. Every action is written down on the timeline.",
            "Everything it finds lands below as live targets you can connect to — not a history list."
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Ghost Tap looks into a device for you: it makes a plan from its real " +
                "tools, runs the checks itself, and writes every action on the " +
                "timeline so you can see exactly what ran. Save good plans and reuse them later.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ZERO-CONFIG — the intent box: type what you're trying to do
        // and Ghost Tap does it, through its real tools.
        IntentBox(onIntent = onIntent, answer = intentAnswer)
        Spacer(modifier = Modifier.height(8.dp))

        PanelBox("STATUS") {
            Text(
                vm.statusLine,
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        PanelBox("THINKING ENGINE") {
            Text(
                vm.engineStatus,
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        VnicStatusPanel(
            vm.vnicEngine,
            // Package 7 (E-30 / E-31 / F-07 / F-55) — the panel acts:
            // ask about the open case file (only when one is open, so
            // the button always names something concrete), retry a
            // failed bring-up, open the controls of a ready device, and
            // dismiss the ready note. Each runs through the shared
            // operations runner when one is wired in; the ViewModel
            // fallbacks cover standalone use.
            onAskBrain = active?.let { inv ->
                {
                    onRunOperation?.invoke("vnic.ask-about-device", inv.title)
                        ?: panelScope.launch { runCatching { vm.vnicObserve(inv.title, inv.targetText) } }
                }
            },
            onRetryBringUp = {
                onRunOperation?.invoke("vnic.retry-bring-up", null)
                    ?: vm.retryVnicBringUp()
            },
            onOpenControls = onRunOperation?.let { runOp -> { runOp("vnic.open-controls", null) } },
            onDismissReady = {
                onRunOperation?.invoke("vnic.dismiss-ready", null)
                    ?: vm.dismissVnicReady()
            }
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (active == null) {
            InvestigationsList(vm, onRunOperation)
        } else {
            InvestigationDetail(vm, onBack, onRunOperation)
        }

        Spacer(modifier = Modifier.height(8.dp))
        AiSection(vm)
        Spacer(modifier = Modifier.height(8.dp))
        ManualInvestigateSection(vm, onRunLiveCommand ?: onRunOperation, onOpenRoute)
        Spacer(modifier = Modifier.height(8.dp))
        LiveTargetsSection(vm, onRunLiveCommand ?: onRunOperation, onOpenRoute, bleTick = bleVm?.uiTick ?: 0)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InvestigationsList(vm: IntelViewModel, onRunOperation: ((String, String?) -> Unit)? = null) {
    PanelBox("START A NEW INVESTIGATION") {
        Text(
            "Describe anything — a device, a label you read, an address — and Ghost Tap " +
                "will make a plan from its real tools.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        var text by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Name it (for example: \"Router label\")") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What do you know about it?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton("CREATE CASE FILE") {
            if (title.isNotBlank() && text.isNotBlank()) {
                vm.setStatus("Making your case file…")
                vm.newInvestigation(
                    IntelTarget(
                        kind = "MANUAL",
                        title = title.trim(),
                        text = text.trim()
                    )
                )
                title = ""
                text = ""
            } else {
                vm.setStatus("Fill in BOTH boxes first — a name and what you know about it — then tap CREATE CASE FILE again.")
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    PanelBox("SAVED INVESTIGATIONS (${vm.investigations.size})") {
        if (vm.investigations.isEmpty()) {
            Text(
                "None yet. Make one above, or tap LOOK INTO THIS DEVICE on the camera screen.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            vm.investigations.forEach { inv ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            inv.title,
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${inv.targetKind} · ${inv.status}",
                            color = Dim,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TerminalButton("OPEN") {
                        onRunOperation?.invoke("intel.open-saved-investigation", inv.title)
                            ?: vm.openInvestigation(inv)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    ProceduresSection(vm, showLoad = false, onRunOperation = onRunOperation)
    Spacer(modifier = Modifier.height(8.dp))
    KnowledgeSearchSection(vm)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InvestigationDetail(vm: IntelViewModel, onBack: () -> Unit, onRunOperation: ((String, String?) -> Unit)? = null) {
    val inv = vm.activeInvestigation ?: return
    PanelBox("TARGET") {
        Text(inv.title, color = Accent, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            inv.targetText,
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())
        )
    }
    Spacer(modifier = Modifier.height(8.dp))

    PanelBox("PLAN (${vm.planSteps.size} STEPS)") {
        Text(
            "Ghost Tap runs the planned steps for you when you tap RUN THE CHECKS below, " +
                "or works through the whole job itself with AUTO INVESTIGATE. " +
                "Each step shows how risky it is, and every action is written on the timeline below.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (vm.planSteps.isEmpty()) {
            Text(
                "No plan on this case file yet.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            vm.planSteps.forEach { step ->
                val cap = remember(step.capabilityId) { CapabilityRegistry.get(step.capabilityId) }
                val needsApproval = step.riskLevel != "READ"
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (step.auto) "[RUNS ITSELF]" else "[NEEDS YOUR TAP]",
                            color = if (step.auto) Accent else Color(0xFFFFAA44),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            " ${step.riskLevel}",
                            color = if (needsApproval) Color(0xFFFFAA44) else Dim,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (cap != null && !cap.verified) {
                            Text(
                                " (not checked yet)",
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            Text(
                                step.capabilityId,
                                color = Accent,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                step.why,
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TerminalButton(if (needsApproval) "APPROVE & RUN" else "RUN") {
                            onRunOperation?.invoke(
                                "intel.approve-next-step",
                                cap?.title ?: step.capabilityId
                            ) ?: vm.runStep(step)
                        }
                    }
                    if (needsApproval && cap != null) {
                        if (cap.effects.isNotBlank()) {
                            Text(
                                "What it does: ${cap.effects}",
                                color = Color(0xFFFFAA44),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (cap.preconditions.isNotEmpty()) {
                            Text(
                                "Needs first: ${cap.preconditions.joinToString("; ")}",
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("RUN THE CHECKS") {
                onRunOperation?.invoke("intel.run-auto-checks", null) ?: vm.runDiscovery()
            }
            TerminalButton("MAKE A NEW PLAN") {
                onRunOperation?.invoke("intel.make-new-plan", null) ?: vm.replan()
            }
            TerminalButton("CLOSE THIS CASE") {
                onRunOperation?.invoke("intel.close-case-file", null) ?: vm.closeInvestigation()
            }
            TerminalButton("MARK AS DONE") {
                onRunOperation?.invoke("intel.mark-case-done", null) ?: vm.markClosed()
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    PanelBox("AUTO INVESTIGATE") {
        Text(
            "Ghost Tap works through the whole job itself — look, research, test, learn — and only " +
                "stops when it runs out of ideas or finishes. Every step is written on the " +
                "timeline so you can see exactly what ran.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            vm.identityLine,
            color = Accent,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "How it's thinking: ${vm.activeReasoningMode}",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.loopActive) {
                TerminalButton("STOP") {
                    onRunOperation?.invoke("intel.stop-looking", null) ?: vm.stopLoop()
                }
            } else {
                TerminalButton("START WORKING ON ITS OWN") {
                    onRunOperation?.invoke("intel.start-auto-investigate", null)
                        ?: vm.activeInvestigation?.let { vm.startAutoLoop(it.id) }
                }
            }
        }
        val pending = vm.pendingApprovalStep
        if (pending != null) {
            Spacer(modifier = Modifier.height(6.dp))
            val cap = remember(pending.capabilityId) { CapabilityRegistry.get(pending.capabilityId) }
            Text(
                "Waiting for your OK: ${cap?.title ?: pending.capabilityId}",
                color = Color(0xFFFFAA44),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                pending.why,
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            if (!cap?.effects.isNullOrBlank()) {
                Text(
                    "What it does: ${cap?.effects}",
                    color = Color(0xFFFFAA44),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TerminalButton("APPROVE & RUN") { vm.approveAndContinue(pending) }
        }
        Spacer(modifier = Modifier.height(6.dp))
        val loopStatus = vm.loopState
        if (loopStatus != null && loopStatus.status != "RUNNING") {
            Text(
                "How it's going: ${loopStatus.status}" +
                    if (loopStatus.endReason.isNotBlank()) " — ${loopStatus.endReason}" else "",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (vm.loopLog.isEmpty()) {
            Text(
                if (vm.loopActive) "Running…" else "Not running yet — tap START WORKING ON ITS OWN.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                "CYCLE LOG",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
            ) {
                vm.loopLog.forEach { line ->
                    Text(
                        "• $line",
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    // Package 6 (C-16/C-17, D-31): a new control the loop drafted, waiting
    // for review. Nothing is switched on until the user approves it.
    val draft = vm.pendingExtensionReview
    if (draft != null) {
        PanelBox("CHECK THIS NEW CONTROL") {
            Text(
                "Ghost Tap designed a new control for a gap it hit. Nothing is switched on until you say so.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                draft.summary,
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Risk: ${draft.riskLevel}",
                color = Color(0xFFFFAA44),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("APPROVE & SWITCH ON") { vm.approveExtensionReview() }
                TerminalButton("DISCARD") { vm.discardExtensionReview() }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    HypothesesSection(vm)
    Spacer(modifier = Modifier.height(8.dp))
    DiscoveredCapabilitiesSection(vm, onRunOperation)
    Spacer(modifier = Modifier.height(8.dp))

    PanelBox("TIMELINE (${vm.timeline.size})") {
        if (vm.timeline.isEmpty()) {
            Text(
                "Nothing recorded yet — run the checks or add a note.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Column(
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
            ) {
                vm.timeline.forEach { obs ->
                    Text(
                        "[${obs.kind.uppercase()}] ${obs.title}",
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        obs.detail.take(600),
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        var note by remember { mutableStateOf("") }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Add a note to the timeline") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton("ADD NOTE") {
            vm.addNote(note.trim())
            note = ""
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    PanelBox("SAVE THIS PLAN") {
        Text(
            "Turn the current plan into one you can reuse on future devices.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        var name by remember { mutableStateOf("") }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Plan name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton("SAVE AS A PLAN") {
            vm.saveAsProcedure(name.trim())
            name = ""
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    ProceduresSection(vm, showLoad = true, onRunOperation = onRunOperation)
    Spacer(modifier = Modifier.height(8.dp))
    KnowledgeSearchSection(vm)
}

@Composable
private fun HypothesesSection(vm: IntelViewModel) {
    PanelBox("HYPOTHESES (${vm.hypotheses.size})") {
        Text(
            "What the app's built-in brain currently thinks, and how sure it is. These are " +
                "guesses from what it found — not checked facts. They change as new results come in.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (vm.hypotheses.isEmpty()) {
            Text(
                "No guesses yet — run the checks and the app will make some.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            vm.hypotheses.forEach { h ->
                val pct = (h.confidence * 100).toInt()
                Text(
                    "${h.text} — $pct%",
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "backs it up: ${h.supporting.size} · goes against it: ${h.contradicting.size}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun DiscoveredCapabilitiesSection(vm: IntelViewModel, onRunOperation: ((String, String?) -> Unit)? = null) {
    PanelBox("DISCOVERED CAPABILITIES") {
        Text(
            "Ghost Tap looks through its own code for things it can do that aren't " +
                "switched on yet. These are real things it found — not promises, " +
                "just leads for switching them on.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton("CHECK WHAT ELSE IT CAN DO") {
            onRunOperation?.invoke("intel.check-what-else", null) ?: vm.runCapabilityScan()
        }
        Spacer(modifier = Modifier.height(6.dp))
        val report = vm.scanReport
        if (report == null) {
            Text(
                "Not checked yet — tap CHECK WHAT ELSE IT CAN DO.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                "${report.registered.size} thing(s) switched on and ready to use.",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "CANDIDATES (${report.candidates.size}) — code that exists but isn't wired in yet:",
                color = Color(0xFFFFAA44),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            if (report.candidates.isEmpty()) {
                Text(
                    "None — everything found is already switched on.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Column(
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                ) {
                    report.candidates.take(60).forEach { c ->
                        Text(
                            c,
                            color = Dim,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (report.candidates.size > 60) {
                        Text(
                            "…and ${report.candidates.size - 60} more",
                            color = Dim,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProceduresSection(vm: IntelViewModel, showLoad: Boolean, onRunOperation: ((String, String?) -> Unit)? = null) {
    // One count for every saved plan, both stores together (Package 6 / F-10).
    PanelBox("PROCEDURES (${vm.unifiedProcedureCount})") {
        Text(
            "Saved plans. Load one onto the open case file to run it again." +
                if (vm.vnicProcedureCount > 0) {
                    " That count covers both places plans live: ${vm.procedures.size} here, plus ${vm.vnicProcedureCount} saved by the brain itself."
                } else "",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (vm.procedures.isEmpty()) {
            Text(
                if (vm.vnicProcedureCount > 0) {
                    "None saved here — but the brain saved ${vm.vnicProcedureCount} plan(s) of its own."
                } else {
                    "None saved yet."
                },
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            vm.procedures.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        p.name,
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (showLoad) {
                        TerminalButton("USE THIS PLAN") {
                            onRunOperation?.invoke("intel.load-saved-plan", p.name)
                                ?: vm.loadProcedure(p)
                        }
                    }
                    TerminalButton("DELETE") { vm.deleteProcedure(p) }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        // Package 7 (E-37 / F-10) — the brain's own saved plans, listed
        // beside the plans saved here so the two stores read as one
        // system: same USE THIS PLAN action, same kind-match check on
        // load. The brain's plans stay in its own store — loading reads
        // them, it never moves or deletes them.
        if (vm.vnicProcedures.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Saved by the brain itself:",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            vm.vnicProcedures.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${p.name} — tried ${p.successes + p.failures}, worked ${p.successes}" +
                            if (p.verified) " (tested OK)" else "",
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (showLoad) {
                        TerminalButton("USE THIS PLAN") {
                            onRunOperation?.invoke("vnic.use-saved-plan", p.name)
                                ?: vm.loadVnicProcedure(p.name)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun KnowledgeSearchSection(vm: IntelViewModel) {
    PanelBox("KNOWLEDGE SEARCH") {
        Text(
            "Search everything Ghost Tap ever saved — past devices, results, and notes.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        var q by remember { mutableStateOf("") }
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            label = { Text("Search past findings") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton("SEARCH") { vm.searchKnowledge(q.trim()) }
        Spacer(modifier = Modifier.height(6.dp))
        vm.searchResults.take(10).forEach { obs ->
            Text(
                "[${obs.kind.uppercase()}] ${obs.title}",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                obs.detail.take(200),
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSection(vm: IntelViewModel) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as android.app.Application }
    PanelBox("THINKING ENGINE") {
        Text(
            "Right now Ghost Tap thinks with the helper built into the app (it works offline, " +
                "no internet needed). If you want, you can connect your own AI service below — " +
                "paste just a key to use OpenAI, or add your own web address. Your key stays on " +
                "this phone, and if the service ever fails, Ghost Tap quietly goes back to the " +
                "built-in helper. The plan always shows which helper made it.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Helper in use: ${vm.engineStatus}",
            color = Accent,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        var endpoint by remember { mutableStateOf(IntelSettings.getEndpoint(app)) }
        var apiKey by remember { mutableStateOf(IntelSettings.getApiKey(app)) }
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("AI service web address (optional — leave blank for OpenAI)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Your key (optional, stored on this phone only)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("SAVE") {
                IntelSettings.setEndpoint(app, endpoint.trim())
                IntelSettings.setApiKey(app, apiKey.trim())
                vm.refreshReasonerName()
            }
            TerminalButton("CLEAR") {
                IntelSettings.clear(app)
                endpoint = ""
                apiKey = ""
                vm.refreshReasonerName()
            }
        }
    }
}

/**
 * Manual investigation entry (new, additive — the existing case-file
 * creator above is untouched). No FindingsReport-producing manual entry
 * existed before, so this small section is the new one: one text field,
 * one button, and the full report below it.
 */
@Composable
private fun ManualInvestigateSection(
    vm: IntelViewModel,
    onRunLiveCommand: ((String, String?) -> Unit)? = null,
    onOpenRoute: ((String) -> Unit)? = null
) {
    PanelBox("INVESTIGATE SOMETHING") {
        Text(
            "Type in anything — a device name, a code from a label, a reading " +
                "you saw — and Ghost Tap will check what it can tell you about " +
                "it. It only uses what you type, nothing made up, and it " +
                "changes nothing on its own.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        var text by remember { mutableStateOf("") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What do you want checked?") },
            // Explicit colors: with no Compose MaterialTheme set, the
            // field's typed text and label default to near-black on this
            // app's near-black background and cannot be read.
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Dim,
                unfocusedTextColor = Dim,
                focusedLabelColor = Accent,
                unfocusedLabelColor = Dim,
                cursorColor = Accent,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Dim
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton("Check this for me") {
            val typed = text.trim()
            if (typed.isBlank()) {
                vm.setStatus("Type something first, then tap Check this for me.")
            } else {
                // MANUAL, with no invented details: only the typed text as
                // the name, no details, no readings, no handshake claim.
                // The Investigator keeps UNKNOWN unknown from this alone.
                vm.investigateManually(
                    InvestigationInput(
                        source = InvestigationSource.MANUAL,
                        rawName = typed,
                        rawDetails = emptyMap(),
                        readableValues = emptyList(),
                        answeredHandshake = false,
                        signalText = null
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        val report = vm.manualReport
        if (report != null) {
            Text(
                "Here is what it found:",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            // MASTER PROMPT: the typed thing is a LIVE TARGET too — the
            // same card as every other finding, with the thinking
            // engine's answer and its commands. A typed thing has no
            // device to connect to, so CONNECT only shows when the
            // report really carries a connectable address.
            val manualLive = LiveTargetStore.get(liveTargetKey(report, InvestigationSource.MANUAL))
            if (manualLive != null) {
                val manualTarget = bringUpTargetFor(report)
                LiveTargetCard(
                    target = manualLive,
                    intelVm = vm,
                    // HOLD THE CONNECTION: a typed thing has no link
                    // until its bring-up reports READY — and the
                    // commands only stay while the link is actually up.
                    isConnected = vm.bringUpState == com.ghosttap.core.BringUpState.READY &&
                        manualTarget != null &&
                        vm.bringUpTargetId == manualTarget.id &&
                        vm.bringUpStillReady(),
                    onConnect = manualTarget?.let { t ->
                        { vm.bringUpTarget(t, autoNavigate = true) }
                    },
                    onControl = null,
                    onRunCommand = onRunLiveCommand,
                    onOpenRoute = onOpenRoute,
                    commandAnswer = vm.lastCommandAnswer
                )
            }
        }
    }
}

/**
 * MASTER PROMPT — LIVE TARGETS, not a history log. Every finding
 * from every function lands here as a live card: what the thinking
 * engine says about it, what it can do with it, a CONNECT button
 * that runs the existing bring-up and lands on the target's own
 * page, and — once connected — the commands to tap on it. The
 * ledger underneath stays as internal storage/dedup only; nothing
 * here is a history to look back at. Nothing here runs on its own
 * — the user taps a button to do anything.
 */
@Composable
private fun LiveTargetsSection(
    vm: IntelViewModel,
    onRunLiveCommand: ((String, String?) -> Unit)? = null,
    onOpenRoute: ((String) -> Unit)? = null,
    // HOLD THE CONNECTION: the Bluetooth link's state bumps this
    // whenever it changes (including a drop), so the cards re-read
    // the live link instead of trusting a stale READY.
    bleTick: Int = 0
) {
    // Re-read the live targets whenever the ViewModel bumps its
    // version (a new finding, or a thinking-engine answer landing),
    // or the Bluetooth link changes state. Navigating to this screen
    // also composes fresh, so targets published by other screens are
    // already here on arrival.
    val targets = remember(vm.findingsVersion, bleTick) {
        LiveTargetStore.all()
    }
    PanelBox("LIVE TARGETS — TAP ONE TO CONNECT") {
        Text(
            "Live things Ghost Tap found right now and can connect to — " +
                "not a history. Tap CONNECT on one and it connects, checks " +
                "it answers, and lands you on its page with the commands " +
                "you can tap on it. Nothing here changes a device on its " +
                "own — you tap a button to do anything.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton("Check for new targets") { vm.refreshFindings() }
        Spacer(modifier = Modifier.height(6.dp))
        if (targets.isEmpty()) {
            Text(
                "Nothing here yet — tap INVESTIGATE THE UNFAMILIAR above, " +
                    "or use any other screen and what it finds will land here as a live target.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            targets.forEach { live ->
                val connectTarget = bringUpTargetFor(live.report)
                LiveTargetCard(
                    target = live,
                    intelVm = vm,
                    // HOLD THE CONNECTION: commands only while the
                    // bring-up is READY and the link is actually up
                    // right now — a dropped link hides the commands
                    // instead of offering them on a dead connection.
                    isConnected = vm.bringUpState == com.ghosttap.core.BringUpState.READY &&
                        connectTarget != null &&
                        vm.bringUpTargetId == connectTarget.id &&
                        vm.bringUpStillReady(),
                    onConnect = connectTarget?.let { t ->
                        { vm.bringUpTarget(t, autoNavigate = true) }
                    },
                    onControl = null,
                    onRunCommand = onRunLiveCommand,
                    onOpenRoute = onOpenRoute,
                    commandAnswer = vm.lastCommandAnswer
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
