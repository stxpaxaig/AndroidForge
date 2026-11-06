package com.ghosttap.core.ops

import com.ghosttap.IntelViewModel
import com.ghosttap.core.BringUpKind
import com.ghosttap.core.BringUpState
import com.ghosttap.core.BringUpTarget
import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.ChannelRanker
import com.ghosttap.core.DeviceClassifier
import com.ghosttap.core.GhostIdentity
import com.ghosttap.core.OpResult
import com.ghosttap.core.PacketInterpreter
import com.ghosttap.core.PlanStep
import com.ghosttap.core.SigUuids
import com.ghosttap.core.describeRssi
import com.ghosttap.core.prettyIdentityKey
import com.ghosttap.core.translateIdentityValue
import com.ghosttap.data.Investigation
import com.ghosttap.data.Procedure
import com.ghosttap.data.ScanTarget
import com.ghosttap.data.TargetType
import kotlinx.coroutines.delay

/**
 * Package 6 (CORE ENGINES + INVESTIGATIONS) — every investigation outcome
 * as an Operation that runs through the [OperationRunner].
 *
 * Nothing here is a new engine. Every operation wraps a function that
 * already exists (InvestigationLoop, Planner, IntelViewModel, the
 * capability scanner, the procedure store) with preparation +
 * preconfiguration + verification through the runner. The only new
 * behaviour lives in the ViewModel helpers this file calls
 * (approvals queue, review step, unified counts) and in the two
 * two-step delete confirms below.
 *
 * Plain-words rule: every title, description, prompt, summary and piece
 * of data this file produces is in plain words. Operation ids are
 * machine names, never shown to the user.
 */

/** Stash for the two-step delete confirms (the runner re-runs the whole preparation on the second tap). */
object AreaIntelState {
    var stashedDeleteInvestigationId: String? = null
    var stashedDeleteProcedureId: String? = null
}

/** Plain-words title of a plan step, for choices, reports and the queue. */
private fun stepTitle(step: PlanStep): String =
    CapabilityRegistry.get(step.capabilityId)?.title ?: step.capabilityId

private fun channelName(uuid: String): String =
    SigUuids.characteristicName(uuid) ?: "Custom feature ${SigUuids.shortForm(uuid)}"

private fun serviceName(uuid: String): String =
    SigUuids.serviceName(uuid) ?: "Custom feature ${SigUuids.shortForm(uuid)}"

// ---------- shared preparation steps ----------

/** Pick a device from the scan list (or use the one already picked). */
private fun chooseTargetStep(
    label: String,
    filter: (ScanTarget) -> Boolean,
    emptyReason: String
): PreparationStep = PreparationStep(label) { context ->
    val ble = context.ble
        ?: return@PreparationStep PreparationOutcome.Failed("Can't check devices — the app isn't ready yet.")
    val candidates = ble.allTargets.filter(filter)
    if (candidates.isEmpty()) return@PreparationStep PreparationOutcome.Failed(emptyReason)
    val picked = context.pendingChoice
    if (picked != null && candidates.any { it.name == picked || it.id == picked }) {
        return@PreparationStep PreparationOutcome.Ready
    }
    PreparationOutcome.NeedsUserChoice(
        prompt = "Which one?",
        choices = candidates.map { it.name.ifBlank { it.id } }
    )
}

private fun chosenTarget(
    context: OperationContext,
    filter: (ScanTarget) -> Boolean
): ScanTarget? {
    val ble = context.ble ?: return null
    val candidates = ble.allTargets.filter(filter)
    val picked = context.pendingChoice
    val target = if (picked != null) {
        candidates.firstOrNull { it.name == picked || it.id == picked }
    } else {
        candidates.firstOrNull()
    }
    context.pendingChoice = null
    return target
}

/** Pick a case file (or use the one already open / picked). */
private fun chooseInvestigationStep(label: String): PreparationStep =
    PreparationStep(label) { context ->
        val intel = context.intel
            ?: return@PreparationStep PreparationOutcome.Failed("Can't look — the app isn't ready yet.")
        val choice = context.pendingChoice
        if (choice != null && intel.investigations.any { it.title == choice }) {
            return@PreparationStep PreparationOutcome.Ready
        }
        if (intel.activeInvestigation != null) return@PreparationStep PreparationOutcome.Ready
        val invs = intel.investigations
        if (invs.isEmpty()) {
            return@PreparationStep PreparationOutcome.NeedsUserAction(
                prompt = "No case files yet — make one on the INVESTIGATIONS screen first.",
                actionLabel = "Open INVESTIGATIONS",
                action = { context.navigate("investigations") }
            )
        }
        PreparationOutcome.NeedsUserChoice(
            prompt = "Which case file?",
            choices = invs.map { it.title }
        )
    }

private fun resolveInvestigation(context: OperationContext): Investigation? {
    val intel = context.intel ?: return null
    val choice = context.pendingChoice
    val inv = if (choice != null) {
        intel.investigations.firstOrNull { it.title == choice }
    } else {
        null
    } ?: intel.activeInvestigation
    context.pendingChoice = null
    return inv
}

/** Pick a saved plan (or use the one already picked). */
private fun chooseProcedureStep(label: String): PreparationStep =
    PreparationStep(label) { context ->
        val intel = context.intel
            ?: return@PreparationStep PreparationOutcome.Failed("Can't look — the app isn't ready yet.")
        val procs = intel.procedures
        if (procs.isEmpty()) {
            return@PreparationStep PreparationOutcome.Failed(
                "No saved plans yet — save one from a case file first."
            )
        }
        val choice = context.pendingChoice
        if (choice != null && procs.any { it.name == choice }) {
            return@PreparationStep PreparationOutcome.Ready
        }
        PreparationOutcome.NeedsUserChoice(
            prompt = "Which saved plan?",
            choices = procs.map { it.name }
        )
    }

private fun resolveProcedure(context: OperationContext): Procedure? {
    val intel = context.intel ?: return null
    val choice = context.pendingChoice
    val proc = if (choice != null) intel.procedures.firstOrNull { it.name == choice } else null
    context.pendingChoice = null
    return proc
}

/** Pick a plan step (or use the one already picked). */
private fun choosePlanStepStep(label: String): PreparationStep =
    PreparationStep(label) { context ->
        val intel = context.intel
            ?: return@PreparationStep PreparationOutcome.Failed("Can't look — the app isn't ready yet.")
        val choice = context.pendingChoice
        if (choice != null && intel.planSteps.any { stepTitle(it) == choice || it.capabilityId == choice }) {
            return@PreparationStep PreparationOutcome.Ready
        }
        if (intel.activeInvestigation == null) {
            return@PreparationStep PreparationOutcome.Failed("Open a case file first.")
        }
        if (intel.planSteps.isEmpty()) {
            return@PreparationStep PreparationOutcome.Failed(
                "No plan on this case file yet — tap MAKE A NEW PLAN on the investigations screen."
            )
        }
        PreparationOutcome.NeedsUserChoice(
            prompt = "Which step?",
            choices = intel.planSteps.map { stepTitle(it) }
        )
    }

private fun resolvePlanStep(context: OperationContext): PlanStep? {
    val intel = context.intel ?: return null
    val choice = context.pendingChoice
    val step = if (choice != null) {
        intel.planSteps.firstOrNull { stepTitle(it) == choice || it.capabilityId == choice }
    } else {
        null
    } ?: intel.approvalQueue.firstOrNull()
    context.pendingChoice = null
    return step
}

private fun requireActiveInvestigation(intel: IntelViewModel?): Investigation? =
    intel?.activeInvestigation

// ---------- C-03 wiring table ----------

/**
 * The candidate a method becomes an operation for (Package 6 / C-03).
 * The scanner reports candidates as "ClassName.methodName(...)" strings;
 * this table maps the verified ones to their operation id. Candidates
 * that are not in the table still need a developer-written reg(...) —
 * the runner reports them plainly instead of wiring them wrong.
 */
fun wiredOperationFor(candidate: String): String? {
    val method = candidate.substringBefore("(").trim()
    return WIRING_TABLE[method]
}

private val WIRING_TABLE: Map<String, String> = mapOf(
    // GhostTapViewModel
    "GhostTapViewModel.disconnectAll" to "ble.disconnect-all",
    "GhostTapViewModel.refreshPairedDevices" to "scan.refresh-paired",
    "GhostTapViewModel.bleDumpAll" to "ble.read-everything",
    "GhostTapViewModel.bleSubscribeAll" to "ble.listen-everything",
    "GhostTapViewModel.selectRawCharacteristic" to "ble.send-to-interesting",
    "GhostTapViewModel.bleRead" to "ble.read-with-answer",
    "GhostTapViewModel.connectSpp" to "spp.connect-checked",
    "GhostTapViewModel.startPrinterProbe" to "print.print-words",
    "GhostTapViewModel.reprobeOnePrinterLanguage" to "print.try-next-language",
    "GhostTapViewModel.startMapRound" to "ble.guided-map-round",
    "GhostTapViewModel.saveMapCaptureAsAction" to "ble.guided-map-round",
    "GhostTapViewModel.connectBleByAddressChecked" to "ble.connect-from-photo",
    "GhostTapViewModel.pairBle" to "ble.pair-then-connect",
    "GhostTapViewModel.saveDiscoveryAsAction" to "ble.guided-learn",
    "GhostTapViewModel.replayLearned" to "ble.guided-learn",
    "GhostTapViewModel.savePacketEventAsAction" to "ble.guided-learn",
    "GhostTapViewModel.summarizeFeed" to "ble.what-just-happened",
    // NetworkToolsViewModel
    "NetworkToolsViewModel.scanMyNetwork" to "net.find-on-wifi",
    "NetworkToolsViewModel.scanPortsForHost" to "net.check-host-ports",
    "NetworkToolsViewModel.discoverServices" to "net.whats-talking",
    "NetworkToolsViewModel.startGhostAp" to "net.start-hotspot",
    "NetworkToolsViewModel.stopGhostAp" to "net.stop-hotspot",
    "NetworkToolsViewModel.ping" to "net.ping-host",
    "NetworkToolsViewModel.tcpTest" to "net.talk-to-device",
    "NetworkToolsViewModel.httpGet" to "net.fetch-page",
    "NetworkToolsViewModel.portScan" to "net.port-scan",
    "NetworkToolsViewModel.udpTest" to "net.udp-send",
    // UsbUartViewModel
    "UsbUartViewModel.changeBaudRate" to "usb.set-speed",
    "UsbUartViewModel.autoBaud" to "usb.find-speed",
    "UsbUartViewModel.setDtr" to "usb.flip-reset-wire",
    "UsbUartViewModel.setRts" to "usb.flip-boot-wire",
    "UsbUartViewModel.requestPermission" to "usb.allow-access",
    "UsbUartViewModel.disconnect" to "usb.disconnect-board",
    // VisualIntelViewModel
    "VisualIntelViewModel.analyzeGalleryImage" to "visual.read-gallery-photo",
    "VisualIntelViewModel.saveCurrentAsProfile" to "visual.save-last-photo",
    "VisualIntelViewModel.deleteProfile" to "visual.delete-profile",
    "VisualIntelViewModel.renameProfile" to "visual.rename-profile"
)

// ---------- registration ----------

fun registerAreaIntelOperations() {
    // ----- "Look into this device" (B-074, B-077–B-078, C-05, C-06) -----
    OperationRegistry.register(
        Operation(
            id = "intel.look-into-device",
            title = "Look into this device",
            description = "Opens a case file, remembers what we already know, makes a plan, runs the steps that run on their own, and hands you the rest one tap at a time.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "CONTROL",
            preparation = listOf(
                PreparationStep("Picking a case file") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't look — the app isn't ready yet.")
                    val choice = context.pendingChoice
                    // A queued step choice means the case file is already open.
                    if (choice != null && intel.approvalQueue.any { stepTitle(it) == choice }) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (intel.activeInvestigation != null) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    val invs = intel.investigations
                    if (invs.isEmpty()) {
                        return@PreparationStep PreparationOutcome.NeedsUserAction(
                            prompt = "No case files yet — make one on the INVESTIGATIONS screen first.",
                            actionLabel = "Open INVESTIGATIONS",
                            action = { context.navigate("investigations") }
                        )
                    }
                    if (choice != null && invs.any { it.title == choice }) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which case file?",
                        choices = invs.map { it.title }
                    )
                },
                PreparationStep("Checking for steps that need your tap") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't look — the app isn't ready yet.")
                    val choice = context.pendingChoice
                    if (choice != null && intel.approvalQueue.any { stepTitle(it) == choice }) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (intel.approvalQueue.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "These steps need your tap, in order — pick the next one.",
                        choices = intel.approvalQueue.map { stepTitle(it) }
                    )
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't look — the app isn't ready yet.")
                val choice = context.pendingChoice
                // 1. A queued step was picked: run exactly that step.
                val queued = choice?.let { c ->
                    intel.approvalQueue.firstOrNull { stepTitle(it) == c }
                }
                if (queued != null) {
                    if (intel.activeInvestigation == null) {
                        return@Operation OpResult(
                            false,
                            "Open a case file first — the step is still waiting, but its case file is closed."
                        )
                    }
                    context.pendingChoice = null
                    val report = intel.runStepNow(queued)
                    return@Operation OpResult(true, report, "queue=${intel.approvalQueue.size}")
                }
                // 2. Otherwise resolve the case file (picked, or the open one).
                val inv = choice?.let { c ->
                    intel.investigations.firstOrNull { it.title == c }
                } ?: intel.activeInvestigation
                    ?: return@Operation OpResult(false, "Open a case file first.")
                context.pendingChoice = null
                if (intel.activeInvestigation?.id != inv.id) intel.openInvestigation(inv)
                if (intel.planSteps.isEmpty()) {
                    val replan = intel.replanNow()
                    if (replan.steps.isEmpty()) {
                        return@Operation if (replan.excluded.isNotEmpty()) {
                            OpResult(
                                false,
                                "Every step in the plan already failed twice on this case file — nothing left to try automatically."
                            )
                        } else {
                            OpResult(
                                false,
                                "No plan could be made for \"${inv.title}\" — add more detail to the case file and try again."
                            )
                        }
                    }
                }
                val report = intel.runAutoSteps()
                val waiting = intel.approvalQueue.size
                OpResult(
                    true,
                    report + if (waiting > 0) {
                        " $waiting step(s) still need your tap — run this again and pick the next one."
                    } else "",
                    "waiting=$waiting"
                )
            }
        )
    )

    // ----- AUTO INVESTIGATE controls (B-069, B-071) -----
    OperationRegistry.register(
        Operation(
            id = "intel.start-auto-investigate",
            title = "Start working on its own",
            description = "The brain works through the whole job itself — plans, runs, learns, and only stops when it runs out of ideas or finishes. Watch it on the CYCLE LOG.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "CONTROL",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't start — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else if (intel.loopActive) {
                        PreparationOutcome.Failed("Auto-investigate is already running.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't start — the app isn't ready yet.")
                val inv = intel.activeInvestigation
                    ?: return@Operation OpResult(false, "Open a case file first.")
                intel.startAutoLoop(inv.id)
                delay(3000)
                val state = intel.loopState
                if (intel.loopActive) {
                    OpResult(
                        true,
                        "Working on its own — ${intel.loopLog.size} log line(s) so far. Watch the CYCLE LOG on the investigations screen.",
                        "running"
                    )
                } else {
                    OpResult(
                        true,
                        "It finished quickly: ${state?.status ?: "done"} — ${state?.endReason?.take(200) ?: "see the timeline."}",
                        "finished:${state?.status ?: "?"}"
                    )
                }
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.stop-looking",
            title = "Stop the automatic work",
            description = "Stops the brain working on its own. Everything it found stays on the timeline.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "CONFIGURE",
            preparation = listOf(
                PreparationStep("Checking it is running") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't stop — the app isn't ready yet.")
                    if (intel.loopActive) PreparationOutcome.Ready
                    else PreparationOutcome.Failed("Nothing is running — it already stopped.")
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't stop — the app isn't ready yet.")
                intel.stopLoop()
                OpResult(true, "Stopped. Everything it found stays on the timeline.", "stopped")
            },
            verify = { context, _ ->
                if (context.intel?.loopActive == false) {
                    VerificationOutcome.Verified("Auto-investigate is stopped.")
                } else {
                    VerificationOutcome.NotVerified("It is still running — tap STOP again.")
                }
            }
        )
    )

    // ----- Plan steps (B-077, B-078, B-074, B-075, C-06, D-31) -----
    OperationRegistry.register(
        Operation(
            id = "intel.run-auto-checks",
            title = "Run the checks",
            description = "Runs every step in the plan that runs on its own, in order. Steps that need your tap are left for you to approve one at a time.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "CONTROL",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't run — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else if (intel.planSteps.isEmpty()) {
                        PreparationOutcome.Failed("No plan on this case file yet — tap MAKE A NEW PLAN on the investigations screen.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't run — the app isn't ready yet.")
                val hadAuto = intel.planSteps.any { it.auto }
                val report = intel.runAutoSteps()
                OpResult(hadAuto, report, "waiting=${intel.approvalQueue.size}")
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.approve-next-step",
            title = "Approve the next step",
            description = "Runs the next step that needs your tap. One tap approves exactly one step — never a blanket grant.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "CONTROL",
            preparation = listOf(choosePlanStepStep("Picking the next step")),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't run — the app isn't ready yet.")
                val step = resolvePlanStep(context)
                    ?: return@Operation OpResult(false, "Nothing waiting for your tap — run the automatic checks first.")
                val report = intel.runStepNow(step)
                OpResult(true, report, "queue=${intel.approvalQueue.size}")
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.skip-this-step",
            title = "Take a step off the plan",
            description = "Takes one bad step off the plan without re-planning the whole thing. The removal is written on the timeline.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(choosePlanStepStep("Picking the step to take off")),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't change the plan — the app isn't ready yet.")
                val step = resolvePlanStep(context)
                    ?: return@Operation OpResult(false, "Nothing on the plan to take off.")
                val title = stepTitle(step)
                intel.removePlanStep(step)
                OpResult(true, "Took \"$title\" off the plan.", "removed=${step.capabilityId}")
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.make-new-plan",
            title = "Make a new plan",
            description = "Rebuilds the plan from what we found so far. Steps that failed twice before are left out, not just moved to the end.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't re-plan — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't re-plan — the app isn't ready yet.")
                val result = intel.replanNow()
                if (result.steps.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "The new plan came back empty — add more detail to the case file and try again."
                    )
                }
                OpResult(
                    true,
                    "New plan: ${result.steps.size} step(s)." +
                        if (result.excluded.isNotEmpty()) {
                            " ${result.excluded.size} step(s) left out — they failed twice before."
                        } else "",
                    "steps=${result.steps.size} excluded=${result.excluded.size}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.show-plan",
            title = "Show the plan",
            description = "Shows the plan for the open case file — which steps run on their own, which need your tap, and how risky each one is.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't show the plan — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't show the plan — the app isn't ready yet.")
                val steps = intel.planSteps
                if (steps.isEmpty()) {
                    return@Operation OpResult(false, "No plan on the open case file — open a case file first.")
                }
                OpResult(
                    true,
                    steps.joinToString("\n") { step ->
                        "${if (step.auto) "Runs itself" else "Needs your tap"} [${step.riskLevel}] ${stepTitle(step)} — ${step.why}"
                    },
                    "steps=${steps.size}"
                )
            }
        )
    )

    // ----- Case files (B-076, E-40, D-31) -----
    OperationRegistry.register(
        Operation(
            id = "intel.open-saved-investigation",
            title = "Open a saved case file",
            description = "Opens a saved case file and makes a fresh plan for it from what we already know.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(chooseInvestigationStep("Picking a case file")),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't open — the app isn't ready yet.")
                val inv = resolveInvestigation(context)
                    ?: return@Operation OpResult(false, "Open a case file first.")
                if (intel.activeInvestigation?.id != inv.id) intel.openInvestigation(inv)
                if (intel.planSteps.isEmpty()) intel.replanNow()
                OpResult(
                    true,
                    "Opened \"${inv.title}\". ${intel.planSteps.size} step(s) on the plan.",
                    inv.id
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.close-case-file",
            title = "Close this case file",
            description = "Closes the open case file. Nothing is deleted — open it again any time.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't close — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("No case file is open.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't close — the app isn't ready yet.")
                val title = intel.activeInvestigation?.title ?: "this case file"
                intel.closeInvestigation()
                OpResult(
                    intel.activeInvestigation == null,
                    "Closed \"$title\".",
                    "closed"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.mark-case-done",
            title = "Mark this case file done",
            description = "Marks the open case file as finished. It stays saved, and you can open it again any time.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't mark it — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't mark it — the app isn't ready yet.")
                val title = intel.activeInvestigation?.title ?: "this case file"
                intel.markClosed()
                delay(400)
                OpResult(true, "Marked \"$title\" done.", "done")
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.delete-case-file",
            title = "Delete a case file",
            description = "Deletes a case file for good, timeline and all. You confirm it twice — there is no undo.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "WRITE",
            preparation = listOf(
                PreparationStep("Pick a case file") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't delete — the app isn't ready yet.")
                    val invs = intel.investigations
                    if (invs.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed("No case files to delete.")
                    }
                    val choice = context.pendingChoice
                    if (choice != null) {
                        val match = invs.firstOrNull { it.title == choice }
                        if (match != null) {
                            AreaIntelState.stashedDeleteInvestigationId = match.id
                            context.pendingChoice = null
                            return@PreparationStep PreparationOutcome.Ready
                        }
                        if (AreaIntelState.stashedDeleteInvestigationId != null &&
                            invs.any { it.id == AreaIntelState.stashedDeleteInvestigationId }
                        ) {
                            return@PreparationStep PreparationOutcome.Ready
                        }
                    }
                    if (AreaIntelState.stashedDeleteInvestigationId != null) {
                        AreaIntelState.stashedDeleteInvestigationId = null
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which case file?",
                        choices = invs.map { it.title }
                    )
                },
                PreparationStep("Confirm the delete") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't delete — the app isn't ready yet.")
                    val stashed = AreaIntelState.stashedDeleteInvestigationId
                        ?: return@PreparationStep PreparationOutcome.Failed("Pick a case file first.")
                    val target = intel.investigations.firstOrNull { it.id == stashed }
                        ?: return@PreparationStep PreparationOutcome.Failed("That case file is gone already.")
                    val choice = context.pendingChoice
                    if (choice == "Yes, delete it" || choice == "Keep it") {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Delete \"${target.title}\" for good? Everything on its timeline goes too.",
                        choices = listOf("Yes, delete it", "Keep it")
                    )
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't delete — the app isn't ready yet.")
                val choice = context.pendingChoice
                val id = AreaIntelState.stashedDeleteInvestigationId
                AreaIntelState.stashedDeleteInvestigationId = null
                context.pendingChoice = null
                if (choice == "Keep it" || id == null) {
                    return@Operation OpResult(true, "Kept it — nothing was deleted.", "kept")
                }
                val inv = intel.investigations.firstOrNull { it.id == id }
                    ?: return@Operation OpResult(true, "It was already gone.", "gone")
                intel.deleteInvestigation(inv)
                OpResult(true, "Deleted \"${inv.title}\".", inv.id)
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.list-case-files",
            title = "List the case files",
            description = "Lists every saved case file, with what kind of target it is about and whether it is finished.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't list — the app isn't ready yet.")
                val invs = intel.investigations
                if (invs.isEmpty()) {
                    return@Operation OpResult(false, "No case files yet — make one on the INVESTIGATIONS screen.")
                }
                OpResult(
                    true,
                    invs.take(10).joinToString("\n") { "${it.title} (${it.targetKind} · ${it.status})" },
                    "cases=${invs.size}"
                )
            }
        )
    )

    // ----- Procedures (C-18, F-45, B-080, E-42) -----
    OperationRegistry.register(
        Operation(
            id = "intel.save-this-plan",
            title = "Save this plan",
            description = "Saves the open case file's plan so you can run it again on a future case file.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't save — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else if (intel.planSteps.isEmpty()) {
                        PreparationOutcome.Failed("No plan on this case file — nothing to save.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't save — the app isn't ready yet.")
                val inv = intel.activeInvestigation
                    ?: return@Operation OpResult(false, "Open a case file first.")
                val name = "${inv.title} plan".take(80)
                val before = intel.procedures.size
                intel.saveAsProcedure(name)
                var found = false
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline && !found) {
                    delay(500)
                    found = intel.procedures.size > before
                }
                if (found) {
                    OpResult(true, "Saved \"$name\" — load it onto a future case file from PROCEDURES.", name)
                } else {
                    OpResult(false, "Couldn't confirm the save — check PROCEDURES on the investigations screen.")
                }
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.load-saved-plan",
            title = "Use a saved plan",
            description = "Loads a saved plan onto the open case file. A plan made for one kind of target will not load onto another — it tells you so instead.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(chooseProcedureStep("Picking a saved plan")),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't load — the app isn't ready yet.")
                val proc = resolveProcedure(context)
                    ?: return@Operation OpResult(false, "Couldn't find that saved plan any more.")
                val loaded = intel.loadProcedureNow(proc)
                if (loaded) {
                    OpResult(
                        true,
                        "Loaded \"${proc.name}\" (${intel.planSteps.size} steps) onto \"${intel.activeInvestigation?.title ?: ""}\".",
                        proc.id
                    )
                } else {
                    OpResult(
                        false,
                        intel.statusLine.ifBlank { "That plan doesn't fit this case file." }
                    )
                }
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.delete-saved-plan",
            title = "Delete a saved plan",
            description = "Deletes a saved plan for good. You confirm it twice — there is no undo.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "WRITE",
            preparation = listOf(
                PreparationStep("Pick a saved plan") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't delete — the app isn't ready yet.")
                    val procs = intel.procedures
                    if (procs.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed("No saved plans to delete.")
                    }
                    val choice = context.pendingChoice
                    if (choice != null) {
                        val match = procs.firstOrNull { it.name == choice }
                        if (match != null) {
                            AreaIntelState.stashedDeleteProcedureId = match.id
                            context.pendingChoice = null
                            return@PreparationStep PreparationOutcome.Ready
                        }
                        if (AreaIntelState.stashedDeleteProcedureId != null &&
                            procs.any { it.id == AreaIntelState.stashedDeleteProcedureId }
                        ) {
                            return@PreparationStep PreparationOutcome.Ready
                        }
                    }
                    if (AreaIntelState.stashedDeleteProcedureId != null) {
                        AreaIntelState.stashedDeleteProcedureId = null
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which saved plan?",
                        choices = procs.map { it.name }
                    )
                },
                PreparationStep("Confirm the delete") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't delete — the app isn't ready yet.")
                    val stashed = AreaIntelState.stashedDeleteProcedureId
                        ?: return@PreparationStep PreparationOutcome.Failed("Pick a saved plan first.")
                    val target = intel.procedures.firstOrNull { it.id == stashed }
                        ?: return@PreparationStep PreparationOutcome.Failed("That saved plan is gone already.")
                    val choice = context.pendingChoice
                    if (choice == "Yes, delete it" || choice == "Keep it") {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Delete \"${target.name}\" for good?",
                        choices = listOf("Yes, delete it", "Keep it")
                    )
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't delete — the app isn't ready yet.")
                val choice = context.pendingChoice
                val id = AreaIntelState.stashedDeleteProcedureId
                AreaIntelState.stashedDeleteProcedureId = null
                context.pendingChoice = null
                if (choice == "Keep it" || id == null) {
                    return@Operation OpResult(true, "Kept it — nothing was deleted.", "kept")
                }
                val proc = intel.procedures.firstOrNull { it.id == id }
                    ?: return@Operation OpResult(true, "It was already gone.", "gone")
                intel.deleteProcedure(proc)
                OpResult(true, "Deleted \"${proc.name}\".", proc.id)
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.save-what-worked",
            title = "Save what worked",
            description = "Saves the steps that actually worked in the finished (or stopped) run as a reusable plan.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a run finished") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't save — the app isn't ready yet.")
                    when (intel.loopState?.status) {
                        "RUNNING" -> PreparationOutcome.Failed("Still running — wait for it to finish or stop, then save what worked.")
                        null -> PreparationOutcome.Failed("Nothing has run yet — start the automatic work first.")
                        else -> PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't save — the app isn't ready yet.")
                val saved = intel.saveLearnedProcedureNow()
                if (saved != null) {
                    OpResult(true, "Saved \"${saved.name}\" — run it again from PROCEDURES any time.", saved.id)
                } else {
                    OpResult(false, "Nothing worth saving yet — the run needs at least 2 steps that worked.")
                }
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.list-saved-plans",
            title = "List the saved plans",
            description = "Lists every saved plan, from both places plans live — this app's list and the brain's own store — with one total count.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't list — the app isn't ready yet.")
                val total = intel.unifiedProcedureCount
                if (total == 0) {
                    return@Operation OpResult(false, "No saved plans yet — save one from a case file first.")
                }
                val text = buildString {
                    append("$total saved plan(s) in total. ")
                    if (intel.procedures.isNotEmpty()) {
                        append("Here: ${intel.procedures.take(10).joinToString(", ") { it.name }}. ")
                    }
                    if (intel.vnicProcedureCount > 0) {
                        append("Plus ${intel.vnicProcedureCount} saved by the brain itself.")
                    }
                }
                OpResult(true, text.trim(), "plans=$total")
            }
        )
    )

    // ----- History (E-41, E-43, F-10) -----
    OperationRegistry.register(
        Operation(
            id = "intel.show-all-history",
            title = "Show all history",
            description = "One view of everything: the open case file's timeline, the recent log, and the saved-plan count from both stores.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't show history — the app isn't ready yet.")
                val inv = intel.activeInvestigation
                val timelinePart = if (inv == null) {
                    "No case file open."
                } else {
                    intel.timeline.takeLast(5)
                        .joinToString("\n") { "[${it.kind}] ${it.title} (${it.provenance})" }
                        .ifBlank { "Timeline empty." }
                }
                val logPart = context.ble?.logs?.value?.take(5)
                    ?.joinToString("\n") { "[${it.category}] ${it.message.take(120)}" }
                    ?: "No log."
                val plansPart = "${intel.unifiedProcedureCount} saved plan(s) in total " +
                    "(${intel.procedures.size} here, ${intel.vnicProcedureCount} from the brain)."
                OpResult(
                    true,
                    "TIMELINE:\n$timelinePart\n\nRECENT LOG:\n$logPart\n\nSAVED PLANS:\n$plansPart",
                    "timeline=${intel.timeline.size} plans=${intel.unifiedProcedureCount}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.show-timeline",
            title = "Show the timeline",
            description = "Shows the open case file's timeline — every observation, result and note, with where it came from.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't show the timeline — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't show the timeline — the app isn't ready yet.")
                val items = intel.timeline.takeLast(10)
                if (items.isEmpty()) {
                    return@Operation OpResult(false, "Nothing recorded yet — run the checks or add a note.")
                }
                OpResult(
                    true,
                    items.joinToString("\n") { "[${it.kind.uppercase()} · ${it.provenance}] ${it.title}" },
                    "timeline=${intel.timeline.size}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.see-cycle-log",
            title = "See the cycle log",
            description = "Shows the cycle log — what the automatic work did, step by step, in the order it happened.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't show the log — the app isn't ready yet.")
                val log = intel.loopLog.takeLast(10)
                if (log.isEmpty()) {
                    return@Operation OpResult(false, "The cycle log is empty — start the automatic investigation and lines appear here as it works.")
                }
                OpResult(true, log.joinToString("\n"), "log=${intel.loopLog.size}")
            }
        )
    )

    // ----- Capability scan + wiring (C-01, C-02, C-03) -----
    OperationRegistry.register(
        Operation(
            id = "intel.check-what-else",
            title = "Check what else it can do",
            description = "Checks the code for tools that exist but aren't switched on yet, and wires the ones we know how to wire. The rest are named plainly so a person can wire them.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val report = intel.scanCapabilitiesNow()
                    ?: return@Operation OpResult(false, "The check failed — try again.")
                OperationRegistry.publishToCapabilityRegistry(context)
                var wired = 0
                val wiredNames = mutableListOf<String>()
                val stillNeedsPerson = mutableListOf<String>()
                for (candidate in report.candidates) {
                    val opId = wiredOperationFor(candidate)
                    if (opId != null && CapabilityRegistry.get(opId) != null) {
                        wired++
                        wiredNames.add("${candidate.substringBefore("(")} -> $opId")
                    } else {
                        stillNeedsPerson.add(candidate.substringBefore("("))
                    }
                }
                val text = buildString {
                    append("Checked the code: ${report.registered.size} thing(s) already switched on, ${report.candidates.size} still switched off. ")
                    append("Wired $wired of them through their operations. ")
                    if (stillNeedsPerson.isNotEmpty()) {
                        append("${stillNeedsPerson.size} still need a person to wire: ${stillNeedsPerson.take(5).joinToString(", ")}.")
                    }
                }
                OpResult(
                    true,
                    text.trim(),
                    "wired=${wiredNames.take(10).joinToString("; ")}; needs-person=${stillNeedsPerson.take(10).joinToString("; ")}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.list-everything-it-can-do",
            title = "List everything it can do",
            description = "Lists every control Ghost Tap has — what it can do, how risky each one is, and whether it has been checked on a real device.",
            area = OperationArea.SYSTEM,
            riskLevel = "READ",
            execute = { context ->
                val engine = context.engine
                    ?: return@Operation OpResult(false, "Can't list — the app isn't ready yet.")
                engine.run("sys.capabilities", emptyMap())
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.run-any-control",
            title = "Run any control",
            description = "Runs one control by name — any control Ghost Tap has, including ones the planner never picks on its own.",
            area = OperationArea.SYSTEM,
            riskLevel = "CONTROL",
            preparation = listOf(
                PreparationStep("Picking a control") { context ->
                    val caps = CapabilityRegistry.all()
                        .filter { !it.sourceMethod.startsWith("Operation:") }
                    if (caps.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed("No controls registered yet.")
                    }
                    val picked = context.pendingChoice
                    if (picked != null && caps.any { it.title == picked || it.id == picked }) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which control? (first 50 shown)",
                        choices = caps.take(50).map { it.title }
                    )
                }
            ),
            execute = { context ->
                val engine = context.engine
                    ?: return@Operation OpResult(false, "Can't run — the app isn't ready yet.")
                val picked = context.pendingChoice
                val cap = if (picked != null) {
                    CapabilityRegistry.all().firstOrNull { it.title == picked || it.id == picked }
                } else {
                    null
                } ?: return@Operation OpResult(false, "Pick a control first.")
                context.pendingChoice = null
                engine.run(cap.id, emptyMap())
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.how-controllable-is-this",
            title = "How controllable is this",
            description = "Counts what a connected device actually offers — how many parts it shared, how many values you can read, send to, or get live updates from.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    { it.type == TargetType.BLE && it.connected },
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val engine = context.engine
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.BLE && it.connected }
                    ?: return@Operation OpResult(false, "Pick a connected device first.")
                val status = engine.bleGattStatus(target.id)
                val counts = engine.bleControlSummary(target.id)
                OpResult(
                    true,
                    "$status — ${counts.services} part(s) shared: read ${counts.read} value(s), send to ${counts.write}, live updates from ${counts.notify}.",
                    target.id
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.how-to-add-a-control",
            title = "How to add a control",
            description = "The three steps a developer follows to add a new control to Ghost Tap — and what makes a control count as checked.",
            area = OperationArea.SYSTEM,
            riskLevel = "READ",
            execute = {
                OpResult(
                    true,
                    "Three steps: 1. Find the tool's real entry point — the actual function users tap today. Never wrap a stub. " +
                        "2. Add ONE registration next to the others in the engine, with a plain-words title, a risk level, and a run that calls the real function. " +
                        "3. Done — the planner, the investigations screen, knowledge search, and the code check pick it up. " +
                        "Risk levels: READ only looks, CONFIGURE opens or closes a link, WRITE sends data out, CONTROL drives hardware. " +
                        "A control counts as checked only after a real device test — a green build alone never counts.",
                    "doc"
                )
            }
        )
    )

    // ----- Device understanding (C-12–C-15, C-19, C-20, E-38) -----
    OperationRegistry.register(
        Operation(
            id = "intel.what-is-this",
            title = "What is this",
            description = "Says what a device probably is — best guess, how sure, and what backs it up. When it can't tell, it says so.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device",
                    { true },
                    "No devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val target = chosenTarget(context) { true }
                    ?: return@Operation OpResult(false, "Pick a device first.")
                val guess = if (target.likely.isNullOrBlank()) {
                    "Unknown device (${target.confidence ?: "Low"} confidence)."
                } else {
                    "Probably: ${target.likely} (${target.confidence ?: "Low"} confidence)."
                }
                val lines = ble.explainDeviceDeep(target.id)
                OpResult(
                    true,
                    "$guess\n" + lines.take(6).joinToString("\n"),
                    target.id
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.what-did-it-just-say",
            title = "What did it just say",
            description = "Reads the last message a connected device sent, in plain words — what it means, how sure, and what changed since the last message.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device to listen to",
                    { it.type == TargetType.BLE && it.connected },
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't listen — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.BLE && it.connected }
                    ?: return@Operation OpResult(false, "Pick a connected device first.")
                val feed = ble.packetFeedFor(target.id)
                if (feed.isEmpty()) {
                    return@Operation OpResult(false, "Nothing heard from this device yet — read a value or turn on live updates first.")
                }
                val latest = feed.last()
                val prev = feed.dropLast(1).lastOrNull()
                val bytes = latest.bytes.map { it.toByte() }.toByteArray()
                val prevBytes = prev?.bytes?.map { it.toByte() }?.toByteArray()
                val analysis = PacketInterpreter.analyze(bytes, prevBytes)
                val changed = if (analysis.changedBytes.isEmpty()) {
                    "Nothing changed since the last message."
                } else {
                    "Bytes that changed: ${analysis.changedBytes.joinToString(", ")}."
                }
                OpResult(
                    true,
                    "${analysis.interpretation} (${analysis.confidence} confidence). $changed" +
                        (analysis.whatWouldHelp?.let { " To learn more: $it" } ?: ""),
                    analysis.hex
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.best-part-to-try",
            title = "Best part to try",
            description = "Picks the most interesting part of a connected device — the one most worth reading from or sending to first.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    { it.type == TargetType.BLE && it.connected },
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.BLE && it.connected }
                    ?: return@Operation OpResult(false, "Pick a connected device first.")
                val services = ble.discoveredServices(target.id)
                var best: Triple<String, String, ChannelRanker.Rank>? = null
                for (svc in services) {
                    for (ch in svc.characteristics) {
                        val rank = ChannelRanker.rank(ch.properties)
                        if (best == null || rank.order > best.third.order) {
                            best = Triple(svc.uuid, ch.uuid, rank)
                        }
                    }
                }
                val picked = best
                    ?: return@Operation OpResult(false, "This device shared nothing to rank — connect it and let it share what it can do first.")
                OpResult(
                    true,
                    "${channelName(picked.second)}: ${picked.third.label}. ${picked.third.plainEnglish}",
                    "${picked.first}:${picked.second}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.what-can-i-do-with-this",
            title = "What can I do with this",
            description = "Lists everything you can do with a device right now — what works, what doesn't, and why.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device",
                    { true },
                    "No devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't list — the app isn't ready yet.")
                val target = chosenTarget(context) { true }
                    ?: return@Operation OpResult(false, "Pick a device first.")
                val actions = ble.deviceActions(target.id)
                if (actions.isEmpty()) {
                    return@Operation OpResult(false, "Nothing listed for this device — open its screen to see what it offers.")
                }
                OpResult(
                    true,
                    actions.joinToString("\n") { a ->
                        "${if (a.available) "Yes" else "No"} — ${a.label}: ${if (a.available) a.detail else a.unavailableWhy}"
                    },
                    "actions=${actions.size}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.name-these-parts",
            title = "Name these parts",
            description = "Names every part a connected device shared, in plain words — what each part is for, not just its code.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    { it.type == TargetType.BLE && it.connected },
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't list — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.BLE && it.connected }
                    ?: return@Operation OpResult(false, "Pick a connected device first.")
                val services = ble.discoveredServices(target.id)
                if (services.isEmpty()) {
                    return@Operation OpResult(false, "This device hasn't shared its parts yet — connect it first and let it share what it can do.")
                }
                val lines = services.flatMap { svc ->
                    listOf("${serviceName(svc.uuid)} (${SigUuids.shortForm(svc.uuid)})") +
                        svc.characteristics.map { ch ->
                            "  - ${channelName(ch.uuid)} (${SigUuids.shortForm(ch.uuid)})"
                        }
                }
                OpResult(true, lines.take(20).joinToString("\n"), "services=${services.size}")
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.signal-in-plain-words",
            title = "Signal in plain words",
            description = "Says how strong a device's signal is, in plain words — and what Ghost Tap knows about the device. Never a distance in metres.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device",
                    { true },
                    "No devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val target = chosenTarget(context) { true }
                    ?: return@Operation OpResult(false, "Pick a device first.")
                val band = describeRssi(target.rssi)
                val proximity = DeviceClassifier.signalProximity(target.rssi)
                val identityLines = target.identity.entries.take(6)
                    .joinToString("\n") { (k, v) -> "${prettyIdentityKey(k)}: ${translateIdentityValue(k, v)}" }
                OpResult(
                    true,
                    "Signal: $band ($proximity)." +
                        if (identityLines.isNotBlank()) "\n$identityLines" else "",
                    target.id
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.what-do-we-know-about-this",
            title = "What do we know about this",
            description = "Everything Ghost Tap knows about one device — what it probably is, how sure, why, and whether it is connected now.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device",
                    { true },
                    "No devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val target = chosenTarget(context) { true }
                    ?: return@Operation OpResult(false, "Pick a device first.")
                val text = buildString {
                    append("${target.name.ifBlank { target.id }} — type ${target.type}. ")
                    append(
                        if (target.likely.isNullOrBlank()) {
                            "Not named yet — Ghost Tap couldn't say what this is. "
                        } else {
                            "Probably: ${target.likely} (${target.confidence ?: "Low"} confidence). "
                        }
                    )
                    if (target.evidence.isNotEmpty()) {
                        append("Why: ${target.evidence.take(3).joinToString("; ")}. ")
                    }
                    append(if (target.connected) "Connected now." else "Not connected.")
                }
                OpResult(true, text.trim(), target.id)
            }
        )
    )

    // ----- Grow a new control (C-16, C-17, B-083, U-05) -----
    OperationRegistry.register(
        Operation(
            id = "intel.suggest-new-control",
            title = "Suggest a new control",
            description = "Asks the brain to design a new control for the gap the investigation hit. It only drafts — nothing is switched on until you review it.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a gap to build for") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't draft — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else if (intel.loopState == null) {
                        PreparationOutcome.Failed("Run the automatic investigation first — a gap it hits becomes the draft.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't draft — the app isn't ready yet.")
                val draft = intel.draftExtensionNow()
                if (draft != null) {
                    OpResult(
                        true,
                        "Drafted \"${draft.title}\" — it's waiting for your review on the investigations screen. Nothing is switched on yet.",
                        "draft:${draft.kind}"
                    )
                } else {
                    OpResult(
                        false,
                        "Couldn't draft a new control for this gap — the gap needs tools Ghost Tap doesn't have yet."
                    )
                }
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.review-new-control",
            title = "Review the new control",
            description = "Shows the new control waiting for review — what it would do, in plain words, and how risky it is.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a control is waiting") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't review — the app isn't ready yet.")
                    if (intel.pendingExtensionReview != null) PreparationOutcome.Ready
                    else PreparationOutcome.Failed("Nothing waiting for review — new controls appear here after a gap stops the investigation.")
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't review — the app isn't ready yet.")
                val draft = intel.pendingExtensionReview
                    ?: return@Operation OpResult(false, "Nothing waiting for review.")
                OpResult(true, draft.summary, "draft:${draft.kind}:${draft.title}")
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.approve-new-control",
            title = "Approve the new control",
            description = "Tests the new control for real and switches it on only if the test passes, then carries the investigation on.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "WRITE",
            preparation = listOf(
                PreparationStep("Checking a control is waiting") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't approve — the app isn't ready yet.")
                    if (intel.pendingExtensionReview != null) PreparationOutcome.Ready
                    else PreparationOutcome.Failed("Nothing waiting for review.")
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't approve — the app isn't ready yet.")
                val title = intel.pendingExtensionReview?.title ?: "the new control"
                intel.approveExtensionReview()
                val deadline = System.currentTimeMillis() + 15000
                while (System.currentTimeMillis() < deadline) {
                    when (intel.loopState?.status) {
                        "RUNNING" -> return@Operation OpResult(
                            true,
                            "Switched on \"$title\" — carrying on with the investigation.",
                            "approved"
                        )
                        "DEAD_END" -> return@Operation OpResult(
                            false,
                            intel.loopState?.endReason?.take(300)
                                ?: "The new control failed its test — nothing was switched on."
                        )
                        else -> delay(500)
                    }
                }
                OpResult(
                    false,
                    "Still testing after 15 seconds — check the status on the investigations screen."
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.discard-new-control",
            title = "Discard the new control",
            description = "Discards the new control waiting for review. Nothing is switched on, and the investigation stops here.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a control is waiting") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't discard — the app isn't ready yet.")
                    if (intel.pendingExtensionReview != null) PreparationOutcome.Ready
                    else PreparationOutcome.Failed("Nothing waiting for review.")
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't discard — the app isn't ready yet.")
                val title = intel.pendingExtensionReview?.title ?: "the new control"
                intel.discardExtensionReview()
                OpResult(true, "Discarded \"$title\". Nothing was switched on.", "discarded")
            },
            verify = { context, _ ->
                if (context.intel?.pendingExtensionReview == null) {
                    VerificationOutcome.Verified("No control is waiting for review.")
                } else {
                    VerificationOutcome.NotVerified("A control is still waiting for review.")
                }
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.list-grown-controls",
            title = "List the grown controls",
            description = "Lists every control Ghost Tap grew itself — the ones designed for a gap, reviewed, and switched on.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = {
                val grown = CapabilityRegistry.all()
                    .filter { it.category == "DYNAMIC" || it.category == "SCRIPT" }
                if (grown.isEmpty()) {
                    OpResult(
                        false,
                        "Ghost Tap hasn't grown any new controls yet — they appear here after a gap is reviewed and approved."
                    )
                } else {
                    OpResult(
                        true,
                        grown.joinToString("\n") {
                            "${it.title} [${it.riskLevel}]${if (it.verified) "" else " (not fully checked yet)"} — ${it.id}"
                        },
                        "grown=${grown.size}"
                    )
                }
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.check-saved-extras",
            title = "Check the saved extras",
            description = "Checks whether the extra controls saved last time came back when the app started — and names any that didn't.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val restored = intel.restoredExtensions
                val problems = intel.restoreProblems
                val text = buildString {
                    if (restored.isEmpty() && problems.isEmpty()) {
                        append("Nothing was saved from last time — no extra controls to restore.")
                    } else {
                        if (restored.isNotEmpty()) {
                            append("Restored ${restored.size}: ${restored.joinToString(", ")}. ")
                        }
                        if (problems.isNotEmpty()) {
                            append("${problems.size} problem(s): ${problems.first()}. Details are on the timeline.")
                        }
                    }
                }
                OpResult(
                    true,
                    text.trim(),
                    "restored=${restored.size} problems=${problems.size}"
                )
            }
        )
    )

    // ----- Thinking engine (C-07, C-08, B-064, B-063, C-22, B-065) -----
    OperationRegistry.register(
        Operation(
            id = "intel.what-does-it-think",
            title = "What does it think",
            description = "Shows the app's current guesses about the open case file — what it thinks, how sure, and what backs each guess up or goes against it.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val hyps = intel.hypotheses
                if (hyps.isEmpty()) {
                    return@Operation OpResult(false, "No guesses yet — run the checks and the app will make some.")
                }
                OpResult(
                    true,
                    hyps.take(8).joinToString("\n") { h ->
                        "${h.text} — ${(h.confidence * 100).toInt()}% sure (backs it up: ${h.supporting.size}, goes against it: ${h.contradicting.size})"
                    },
                    "hypotheses=${hyps.size}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.check-thinking-engine",
            title = "Check the thinking engine",
            description = "Says which brain is thinking right now — the built-in helper on this phone, or your own online AI service — and how it is doing.",
            area = OperationArea.SYSTEM,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                intel.refreshReasonerName()
                OpResult(
                    true,
                    "Thinking with: ${intel.reasonerName}. ${intel.engineStatus} Mode: ${intel.activeReasoningMode}.",
                    "reasoner=${intel.reasonerName}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.check-online-brain",
            title = "Check the online brain",
            description = "Says whether your own online AI service is set up — nothing is set up or bought for you here, it only reports what is already configured.",
            area = OperationArea.SYSTEM,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                if (intel.activeReasoningMode == "Online model") {
                    OpResult(
                        true,
                        "The online brain is connected — plans come from your own AI service. If it ever fails, Ghost Tap quietly goes back to the built-in helper.",
                        "online"
                    )
                } else {
                    OpResult(
                        true,
                        "The online brain is not set up — no key saved. The built-in helper is thinking (it works offline, no internet needed). Your key stays on this phone if you add one.",
                        "offline"
                    )
                }
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.ask-the-brain",
            title = "Ask the brain",
            description = "Asks the brain what it makes of the open case file — its phases, what it would do next, and how sure it is.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't ask — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't ask — the app isn't ready yet.")
                val inv = intel.activeInvestigation
                    ?: return@Operation OpResult(false, "Open a case file first.")
                val result = try {
                    intel.vnicObserve(inv.title, inv.targetText)
                } catch (e: Exception) {
                    return@Operation OpResult(false, "The brain hit a problem: ${e.message ?: "unknown problem"}")
                }
                OpResult(
                    true,
                    "The brain says: ${result.executionState} — sure about it at ${(result.confidence * 100).toInt()}%. ${result.executionEvidence.take(200)}",
                    result.phases.take(5).joinToString(" | ")
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.check-practice-board-link",
            title = "Check the practice board link",
            description = "Checks whether the practice board (Virtual ESP32) is attached to the brain, so it can be used for testing.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val attached = try {
                    intel.vnicEngine.capabilities().any { it.id.startsWith("vesp32.") }
                } catch (_: Exception) {
                    false
                }
                OpResult(
                    true,
                    if (attached) {
                        "The practice board link is attached — the brain can use the practice board."
                    } else {
                        "The practice board link is not attached — restart the app and it attaches when the app starts."
                    },
                    "vesp32=$attached"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.what-are-the-rules",
            title = "What are the rules",
            description = "Shows the one rule the brain never breaks — in plain words.",
            area = OperationArea.SYSTEM,
            riskLevel = "READ",
            execute = {
                OpResult(true, GhostIdentity.directive(), "rules")
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.check-status",
            title = "Check the status",
            description = "Shows the current status — what the app is doing right now and how the thinking engine is doing.",
            area = OperationArea.SYSTEM,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                OpResult(
                    true,
                    "${intel.statusLine}\n${intel.engineStatus}",
                    "status"
                )
            }
        )
    )

    // ----- Knowledge (C-09, C-10, B-073, C-21, B-072, B-082) -----
    OperationRegistry.register(
        Operation(
            id = "intel.what-do-we-already-know",
            title = "What do we already know",
            description = "Shows everything already known about the open case file — on-device clues and web findings, marked as checked facts or leads.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't check — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val findings = intel.researchNow()
                if (findings.isEmpty()) {
                    return@Operation OpResult(false, "Nothing found yet — add a photo, a scan result, or a few words describing the target.")
                }
                OpResult(
                    true,
                    findings.take(10).joinToString("\n") { f ->
                        "${if (f.verified) "Checked fact" else "Lead, not a fact"}: ${f.text.take(200)} (from ${f.source})"
                    },
                    "findings=${findings.size}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.search-the-web-for-this",
            title = "Search the web for this",
            description = "Searches the web for what the open case file is about — web findings only, marked as leads until a real test checks them.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't search — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't search — the app isn't ready yet.")
                val web = intel.researchNow().filter { it.source.startsWith("web:") }
                if (web.isEmpty()) {
                    return@Operation OpResult(false, "The web search came back empty — the on-device clues are all there is for now.")
                }
                OpResult(
                    true,
                    web.take(5).joinToString("\n") { f ->
                        "${if (f.verified) "Checked fact" else "Lead, not a fact"}: ${f.text.take(200)} (from ${f.source})"
                    },
                    "web-findings=${web.size}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.what-links-to-this",
            title = "What links to this",
            description = "Shows other case files that mention the same identifier — an address, a number, or a name — as the open case file.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't check — the app isn't ready yet.")
                    if (intel.activeInvestigation == null) {
                        PreparationOutcome.Failed("Open a case file first.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val links = intel.timeline.filter { it.kind == "link" }
                if (links.isEmpty()) {
                    return@Operation OpResult(false, "Nothing links to this case file — no identifier here appears in any other case file.")
                }
                OpResult(
                    true,
                    links.take(5).joinToString("\n") { "${it.title}: ${it.detail.take(200)}" },
                    "links=${links.size}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.what-just-happened",
            title = "What just happened",
            description = "The last few things that happened — the newest cycle-log lines and the newest timeline entries, in order.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val log = intel.loopLog.takeLast(5)
                val recent = intel.timeline.takeLast(3)
                if (log.isEmpty() && recent.isEmpty()) {
                    return@Operation OpResult(false, "Nothing recorded yet — events only appear once something has been looked into.")
                }
                OpResult(
                    true,
                    (log.map { "Log: $it" } + recent.map { "[${it.kind}] ${it.title}" }).joinToString("\n"),
                    "log=${log.size} timeline=${recent.size}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.refresh-lists",
            title = "Refresh the lists",
            description = "Re-reads the case files and saved plans — including the brain's own saved plans — so the counts on screen are fresh.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't refresh — the app isn't ready yet.")
                intel.refreshLists()
                delay(800)
                OpResult(
                    true,
                    "${intel.investigations.size} case file(s), ${intel.unifiedProcedureCount} saved plan(s) in total (${intel.procedures.size} here, ${intel.vnicProcedureCount} from the brain).",
                    "cases=${intel.investigations.size} plans=${intel.unifiedProcedureCount}"
                )
            }
        )
    )

    // ----- Bring-up (C-11, B-066, B-067) -----
    OperationRegistry.register(
        Operation(
            id = "intel.get-device-ready",
            title = "Get this device ready",
            description = "Makes a device ready to use — connects, checks the link is really open, and only then says it is ready.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "CONFIGURE",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device",
                    { it.type == TargetType.BLE || it.type == TargetType.NFC || it.type == TargetType.USB || it.type == TargetType.NETWORK },
                    "No devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't get it ready — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.BLE || it.type == TargetType.NFC || it.type == TargetType.USB || it.type == TargetType.NETWORK }
                    ?: return@Operation OpResult(false, "Pick a device first.")
                val kind = when (target.type) {
                    TargetType.BLE -> BringUpKind.BLE
                    TargetType.NFC -> BringUpKind.NFC
                    TargetType.USB -> BringUpKind.USB
                    TargetType.NETWORK -> BringUpKind.NETWORK
                    else -> return@Operation OpResult(false, "That kind of device can't be made ready this way.")
                }
                intel.bringUpTarget(
                    BringUpTarget(
                        kind = kind,
                        id = target.id,
                        title = target.name.ifBlank { target.id },
                        // Empty on purpose: only the NFC path reads detail,
                        // as a comma list of card techs, and a scan target
                        // does not carry them — empty reads as "unknown type".
                        detail = ""
                    ),
                    autoNavigate = true
                )
                delay(600)
                if (intel.bringUpTargetId != target.id) {
                    return@Operation OpResult(
                        false,
                        intel.bringUpStatus.ifBlank { "Still getting the last device ready — one at a time." }
                    )
                }
                val deadline = System.currentTimeMillis() + 45000
                while (System.currentTimeMillis() < deadline) {
                    when (intel.bringUpState) {
                        BringUpState.READY -> return@Operation OpResult(
                            true,
                            "Ready — ${intel.bringUpStatus}",
                            "ready:${target.id}"
                        )
                        BringUpState.FAILED -> return@Operation OpResult(
                            false,
                            intel.bringUpStatus.ifBlank { "Couldn't get it ready." }
                        )
                        else -> delay(500)
                    }
                }
                OpResult(
                    false,
                    "Still getting it ready after 45 seconds — check the banner at the bottom of the screen."
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.is-it-still-ready",
            title = "Is it still ready",
            description = "Checks the link is really open right now — not just that it was ready earlier.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val ready = intel.bringUpStillReady()
                OpResult(
                    ready,
                    if (ready) "Still ready — the link is really open right now."
                    else "Not ready any more — the link dropped or nothing was made ready.",
                    "ready=$ready"
                )
            }
        )
    )

    // ----- Learned / logs (E-38, E-39, E-45, E-43) -----
    OperationRegistry.register(
        Operation(
            id = "intel.what-did-we-learn-from-this",
            title = "What did we learn from this",
            description = "Shows the controls Ghost Tap learned from watching this device — what each one does and how sure it is.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device",
                    { true },
                    "No devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val target = chosenTarget(context) { true }
                    ?: return@Operation OpResult(false, "Pick a device first.")
                val actions = ble.learnedActionsFor(target.id)
                if (actions.isEmpty()) {
                    return@Operation OpResult(false, "Nothing learned from this device yet — use Learn mode on its screen while you use the device once.")
                }
                OpResult(
                    true,
                    actions.take(5).joinToString("\n") { a ->
                        "\"${a.name}\" (${a.confidence} confidence): ${a.reason.take(120)} — packet ${a.packetHex}"
                    },
                    "learned=${actions.size}"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "intel.show-recent-log",
            title = "Show the recent log",
            description = "Shows the most recent log lines — what Ghost Tap recorded, newest first.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't show the log — the app isn't ready yet.")
                val lines = ble.logs.value.take(8)
                if (lines.isEmpty()) {
                    return@Operation OpResult(false, "The log is empty — nothing has been recorded yet.")
                }
                OpResult(
                    true,
                    lines.joinToString("\n") { "[${it.category}] ${it.message.take(160)}" },
                    "log=${lines.size}"
                )
            }
        )
    )
}
