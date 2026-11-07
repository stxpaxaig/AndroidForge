package com.ghosttap.core.ops

import com.ghosttap.IntelViewModel
import com.ghosttap.core.OpResult
import com.ghosttap.core.describeRssi
import com.ghosttap.core.vnic.VnicExecutionState
import com.ghosttap.data.ScanTarget
import com.ghosttap.data.TargetType

/**
 * Package 7 (VNIC AS A CAPABILITY — E-01–E-33, E-34–E-37) — every VNIC
 * outcome as an Operation that runs through the [OperationRunner].
 *
 * VNIC rides the same registry/runner and the same bring-up pipeline as
 * everything else: it gets no parallel bring-up and no parallel
 * execution path. Every operation wraps a function that already exists
 * ([IntelViewModel.vnicObserve], [com.ghosttap.core.vnic.VnicEngine]
 * research / develop / status) with preparation + preconfiguration +
 * verification through the runner.
 *
 * Entry points (E-30 / B-064 / F-07): "ask the brain about this device"
 * is reachable from a scan result (the choice list below), from a
 * device row (the "Ask the brain" button on the target screen, which
 * runs `vnic.ask-about-device` with the target's real id), and from the
 * Investigations screen (the brain panel's "Ask the brain" button,
 * which asks about the open case file). All three call the EXISTING
 * `vnicObserve` interface — no second observation path was built.
 *
 * Plain-words rule: every title, description, prompt, summary and piece
 * of data this file produces is in plain words. Operation ids are
 * machine names, never shown to the user. Risk levels are plain strings
 * ("READ" / "CONTROL"), matching Operation.riskLevel. (Package 6's file
 * used a `RiskLevel` symbol that is not defined anywhere in this tree,
 * so this file does not reference it.)
 */

/** What the brain is asked about: the real target id + the words describing it. */
private data class AskSubject(val subject: String, val text: String)

private fun targetChoiceLabel(target: ScanTarget): String =
    target.name.ifBlank { target.id }

private fun typeWords(type: TargetType): String = when (type) {
    TargetType.BLE -> "Bluetooth device"
    TargetType.BLUETOOTH_CLASSIC -> "Bluetooth device"
    TargetType.NFC -> "tap card"
    TargetType.USB -> "USB cable device"
    TargetType.NETWORK -> "network device"
    TargetType.SENSOR -> "sensor"
}

/**
 * The words the brain is given about a scan target (E-02 / E-12 /
 * E-19): the kind in words (so the bring-up router hears "Bluetooth"
 * instead of guessing from a bare name), the name, the "Probably"
 * verdict with its confidence, the evidence, and the signal as a plain
 * word (never a distance). The SUBJECT is the target's real id — the
 * bring-up pipeline resolves that id, not a friendly name.
 */
private fun askTextForTarget(target: ScanTarget): String = buildString {
    append(typeWords(target.type))
    append(" named ${target.name.ifBlank { target.id }}. ")
    target.likely?.takeIf { it.isNotBlank() }?.let {
        append("Probably: $it (${target.confidence ?: "unknown confidence"}). ")
    }
    if (target.evidence.isNotEmpty()) {
        append("Why: ${target.evidence.take(3).joinToString("; ")}. ")
    }
    append("Signal: ${describeRssi(target.rssi)}.")
}

/**
 * Shared preparation: pick what the brain should look at — a scan
 * result (by name), or the open case file. A pending choice that names
 * a scan target or a case file is accepted; otherwise the user picks
 * from the scan list, or the open case file is used when there is
 * nothing scanned yet.
 */
private fun chooseSubjectStep(label: String): PreparationStep =
    PreparationStep(label) { context ->
        val intel = context.intel
            ?: return@PreparationStep PreparationOutcome.Failed("Can't ask — the app isn't ready yet.")
        val targets = context.ble?.allTargets ?: emptyList()
        val choice = context.pendingChoice
        if (choice != null) {
            if (targets.any { it.id == choice || targetChoiceLabel(it) == choice }) {
                return@PreparationStep PreparationOutcome.Ready
            }
            val named = intel.investigations.firstOrNull { it.title == choice }
                ?: intel.activeInvestigation?.takeIf { it.title == choice }
            if (named != null) return@PreparationStep PreparationOutcome.Ready
        }
        if (targets.isNotEmpty()) {
            return@PreparationStep PreparationOutcome.NeedsUserChoice(
                prompt = "Which device?",
                choices = targets.map { targetChoiceLabel(it) }
            )
        }
        if (intel.activeInvestigation != null) return@PreparationStep PreparationOutcome.Ready
        PreparationOutcome.Failed(
            "Find a device first — tap Find devices on the home screen — " +
                "or open a case file on INVESTIGATIONS."
        )
    }

/** Resolve the picked subject (consumes the pending choice). Null when nothing is picked or open. */
private fun resolveSubject(context: OperationContext): AskSubject? {
    val intel = context.intel ?: return null
    val choice = context.pendingChoice
    val target = choice?.let { c ->
        context.ble?.allTargets?.firstOrNull { it.id == c || targetChoiceLabel(it) == c }
    }
    context.pendingChoice = null
    if (target != null) return AskSubject(target.id, askTextForTarget(target))
    val inv = choice?.let { c -> intel.investigations.firstOrNull { it.title == c } }
        ?: intel.activeInvestigation
    return inv?.let { AskSubject(it.title, it.targetText) }
}

/** Plain-words summary of one finished brain cycle, for the result line. */
private fun cycleSummary(result: com.ghosttap.core.vnic.VnicCycleResult): String = buildString {
    append("The brain says: ${result.executionState.name.lowercase()} — ")
    append("${(result.confidence * 100).toInt()}% sure. ")
    append(result.executionEvidence.take(200))
    if (result.hypotheses.isNotEmpty()) {
        append(" It thinks: ${result.hypotheses.first().take(160)}")
    }
    if (result.bringUp != null) {
        if (result.executionState == VnicExecutionState.CONTROLLABLE) {
            append(" It's ready — open its controls from the brain panel.")
        } else {
            append(" It couldn't get it ready — try again from the brain panel.")
        }
    }
}

fun registerAreaVnicOperations() {
    // ----- Ask the brain (E-01, E-02, E-12, E-14, E-19–E-21, E-24, E-25, E-27, E-30) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.ask-about-device",
            title = "Ask the brain about this device",
            description = "The brain looks at the device, checks what it already knows, looks it up on the internet if it doesn't, makes a plan from Ghost Tap's real tools, and tries to get it ready for you. You drive it afterwards.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "CONTROL",
            preparation = listOf(chooseSubjectStep("Picking what to ask about")),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't ask — the app isn't ready yet.")
                val ask = resolveSubject(context)
                    ?: return@Operation OpResult(false, "Open a case file first, or find a device on the home screen.")
                val result = try {
                    intel.vnicObserve(ask.subject, ask.text)
                } catch (e: Exception) {
                    return@Operation OpResult(false, "The brain hit a problem: ${e.message ?: "unknown problem"}")
                }
                OpResult(
                    result.executionState != VnicExecutionState.FAILED,
                    cycleSummary(result),
                    result.phases.takeLast(6).joinToString(" | ")
                )
            }
        )
    )

    // ----- Retry a failed bring-up (E-01, E-25, E-29, E-31, F-55) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.retry-bring-up",
            title = "Try getting it ready again",
            description = "Tries again to get the last device the brain looked at ready for you. Use it after a failed try — the brain runs the whole look, plan and bring-up again.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "CONTROL",
            preparation = listOf(
                PreparationStep("Checking what to try again") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't try again — the app isn't ready yet.")
                    val last = intel.vnicEngine.status.value.lastSubject.ifBlank {
                        intel.vnicEngine.driveOutcome.value?.subject ?: ""
                    }
                    if (last.isBlank()) {
                        PreparationOutcome.Failed("Nothing to try again yet — ask the brain about a device first.")
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't try again — the app isn't ready yet.")
                val last = intel.vnicEngine.status.value.lastSubject.ifBlank {
                    intel.vnicEngine.driveOutcome.value?.subject ?: ""
                }
                if (last.isBlank()) {
                    return@Operation OpResult(false, "Nothing to try again yet — ask the brain about a device first.")
                }
                val result = try {
                    intel.vnicObserve(last, "Try again: $last")
                } catch (e: Exception) {
                    return@Operation OpResult(false, "The brain hit a problem: ${e.message ?: "unknown problem"}")
                }
                OpResult(
                    result.executionState != VnicExecutionState.FAILED,
                    cycleSummary(result),
                    result.phases.takeLast(6).joinToString(" | ")
                )
            }
        )
    )

    // ----- Open the controls of a ready device (E-29, E-31, F-55) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.open-controls",
            title = "Open the controls",
            description = "Opens the controls of the device the brain just got ready for you. Only shows up when something is actually ready — it never opens a dead link.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking something is ready") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't open — the app isn't ready yet.")
                    val route = readyRoute(intel)
                    if (route == null) {
                        PreparationOutcome.Failed(
                            "Nothing is ready to open yet — ask the brain about a device first, and wait until it says it's ready for you."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't open — the app isn't ready yet.")
                val route = readyRoute(intel)
                    ?: return@Operation OpResult(false, "Nothing is ready to open yet — ask the brain about a device first.")
                context.navigate(route)
                OpResult(true, "Opening the controls — you're in charge from here.", route)
            }
        )
    )

    // ----- Dismiss the "ready for you" indicator (E-29, F-26) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.dismiss-ready",
            title = "Clear the ready-for-you note",
            description = "Clears the note that says a device is ready for you. It stays cleared until the brain gets another device ready.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't clear — the app isn't ready yet.")
                val had = intel.vnicEngine.driveOutcome.value != null
                intel.vnicEngine.dismissDriveOutcome()
                if (had) {
                    OpResult(true, "Cleared — it won't show again until the brain gets another device ready.", "dismissed")
                } else {
                    OpResult(false, "Nothing is waiting for you right now.")
                }
            }
        )
    )

    // ----- Look it up only (E-14, E-15, E-16, E-18, E-30) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.look-it-up",
            title = "Look this up on the internet",
            description = "The brain searches the web for this device and saves what it finds. A web find is a lead, never proof — only a real device answer proves anything.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(chooseSubjectStep("Picking what to look up")),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't look it up — the app isn't ready yet.")
                val ask = resolveSubject(context)
                    ?: return@Operation OpResult(false, "Open a case file first, or find a device on the home screen.")
                val report = try {
                    intel.vnicEngine.research(ask.text)
                } catch (e: Exception) {
                    return@Operation OpResult(false, "The lookup hit a problem: ${e.message ?: "unknown problem"}")
                }
                if (report.findings.isEmpty()) {
                    OpResult(
                        true,
                        "Nothing found on the web for that — the brain says so rather than guessing.",
                        "findings=0"
                    )
                } else {
                    OpResult(
                        true,
                        "Found ${report.findings.size} thing(s): " +
                            report.findings.take(3).joinToString("; ") { "${it.title} (${it.source})" },
                        report.findings.joinToString(" | ") { "${it.title} (${it.source}, ${it.confidence})" }
                    )
                }
            }
        )
    )

    // ----- Build a plan (E-23, E-30) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.build-a-plan",
            title = "Let the brain build a plan",
            description = "The brain turns the open case file's plan into a tested, saved plan you can reuse. Only plans that pass their test get saved.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Checking a case file is open") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't build — the app isn't ready yet.")
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
                    ?: return@Operation OpResult(false, "Can't build — the app isn't ready yet.")
                val inv = intel.activeInvestigation
                    ?: return@Operation OpResult(false, "Open a case file first.")
                val developed = try {
                    intel.vnicEngine.developAndSaveProcedure(
                        goal = inv.title,
                        stepIds = intel.planSteps.map { it.capabilityId }
                    )
                } catch (e: Exception) {
                    return@Operation OpResult(false, "Building the plan hit a problem: ${e.message ?: "unknown problem"}")
                }
                if (developed.testPassed) {
                    OpResult(
                        true,
                        "Saved \"${developed.def.id}\" — it passed its test, and you can use it from PROCEDURES any time.",
                        developed.def.id
                    )
                } else {
                    OpResult(
                        false,
                        "The plan didn't pass its test: ${developed.notes.joinToString("; ").take(300)}"
                    )
                }
            }
        )
    )

    // ----- Use a brain-saved plan (E-23, E-37, F-08, F-10) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.use-saved-plan",
            title = "Use a plan the brain saved",
            description = "Loads a plan the brain saved onto the open case file, through the same check as any saved plan — a plan made for one kind of target will not load onto another.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            preparation = listOf(
                PreparationStep("Picking a brain-saved plan") { context ->
                    val intel = context.intel
                        ?: return@PreparationStep PreparationOutcome.Failed("Can't load — the app isn't ready yet.")
                    val procs = intel.vnicProcedures
                    if (procs.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "The brain hasn't saved any plans yet — ask it about a device first."
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
            ),
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't load — the app isn't ready yet.")
                val name = context.pendingChoice
                context.pendingChoice = null
                val procName = name?.takeIf { n -> intel.vnicProcedures.any { it.name == n } }
                    ?: return@Operation OpResult(false, "Couldn't find that saved plan any more.")
                val loaded = intel.loadVnicProcedureNow(procName)
                if (loaded) {
                    OpResult(
                        true,
                        "Loaded \"$procName\" (${intel.planSteps.size} steps) onto \"${intel.activeInvestigation?.title ?: ""}\".",
                        procName
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

    // ----- List the brain's saved plans (E-37, F-10) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.list-saved-plans",
            title = "List the plans the brain saved",
            description = "Lists every plan the brain saved, with how many times each one was tried and how many times it worked.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't list — the app isn't ready yet.")
                val procs = intel.vnicProcedures
                if (procs.isEmpty()) {
                    return@Operation OpResult(false, "The brain hasn't saved any plans yet — ask it about a device first.")
                }
                OpResult(
                    true,
                    procs.joinToString("\n") { p ->
                        "${p.name} — tried ${p.successes + p.failures} time(s), worked ${p.successes} time(s)" +
                            if (p.verified) " (tested OK)" else ""
                    },
                    "plans=${procs.size}"
                )
            }
        )
    )

    // ----- What the brain knows (E-06, E-07, E-35, E-36) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.show-what-it-knows",
            title = "Show what the brain knows",
            description = "Shows the brain's map of ideas — what it knows about devices it has seen, and how those ideas link together.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't show — the app isn't ready yet.")
                val lines = intel.vnicEngine.graphSnapshot(10)
                if (lines.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "The brain only knows its 9 starting ideas so far — ask it about a real device and the map grows."
                    )
                }
                OpResult(true, lines.joinToString("\n"), "concepts=${lines.size}")
            }
        )
    )

    // ----- What the brain remembers (E-06, E-08, E-28, E-34) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.show-what-it-remembers",
            title = "Show what the brain remembers",
            description = "Shows the most recent things the brain remembered — past events, facts it found, and drive outcomes.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't show — the app isn't ready yet.")
                val mems = intel.vnicEngine.recentMemories(10)
                if (mems.isEmpty()) {
                    return@Operation OpResult(false, "Nothing remembered yet — ask the brain about something first.")
                }
                OpResult(
                    true,
                    mems.joinToString("\n") { "[${it.type.name.lowercase()}] ${it.content.take(140)}" },
                    "memories=${mems.size}"
                )
            }
        )
    )

    // ----- Brain activity (E-03, E-04, E-05) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.show-brain-activity",
            title = "Show the brain's activity",
            description = "Shows how the brain's practice patterns are firing right now — measured counts only, never the brochure numbers.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't show — the app isn't ready yet.")
                val s = intel.vnicEngine.meshSnapshot()
                OpResult(
                    true,
                    "Practice patterns: ${s.materializedUnits} units, ${s.materializedSparseSynapses} links, " +
                        "${s.activeMaterializedUnits} active right now, ${s.totalFiringEvents} firings so far, " +
                        "${s.plasticityUpdates} practice updates. The patterns start fresh when the app closes — " +
                        "what the brain remembers about devices stays saved.",
                    "plasticity=${s.plasticityUpdates}"
                )
            }
        )
    )

    // ----- Measured score (E-09, E-10, E-27) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.show-score",
            title = "Show the brain's score",
            description = "Shows what the brain has actually done on this phone — rounds, lookups, tries, connections, and checks that passed — counted from real runs only.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't show — the app isn't ready yet.")
                val st = intel.vnicEngine.status.value
                val t = st.telemetry
                OpResult(
                    true,
                    "Rounds done ${t["cycles_run"] ?: 0} · lookups ${t["research_queries"] ?: 0} " +
                        "(${t["findings_stored"] ?: 0} things found) · tries ${t["executions_attempted"] ?: 0} · " +
                        "connected ${t["executions_connected"] ?: 0} · checked OK ${t["executions_verified"] ?: 0} · " +
                        "failed ${t["executions_failed"] ?: 0}. Saved plans: ${st.procedureCount}. " +
                        "Memory saved on this phone: ${if (st.storeBackedByRoom) "yes" else "no — temporary only"}.",
                    "cycles=${t["cycles_run"] ?: 0}"
                )
            }
        )
    )

    // ----- Research + reasoner setup (E-13, E-17, F-11) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.show-research-options",
            title = "Show how the brain looks things up",
            description = "Says which search the brain uses and whether your own online brain is set up. Nothing is bought or signed up for you here — it only reports what is already set up.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't show — the app isn't ready yet.")
                val providers = intel.vnicEngine.researchProviderNames()
                val reasoner = intel.vnicEngine.reasonerName()
                OpResult(
                    true,
                    "Looking things up with: ${providers.joinToString(", ")}. " +
                        "Thinking with: $reasoner. " +
                        if (reasoner == "Built-in helper") {
                            "No online brain is set up — nothing is bought or signed up for you here, and the brain works offline."
                        } else {
                            "The online brain is set up with your own details."
                        },
                    "providers=${providers.size}"
                )
            }
        )
    )

    // ----- Manual feedback (E-09, E-33) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.say-that-was-right",
            title = "That was right",
            description = "Tells the brain its last answer was right, so it weighs that kind of answer more next time. Also marks the last plan it built as having worked.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't note that — the app isn't ready yet.")
                intel.vnicEngine.giveFeedback(0.9)
                val name = intel.vnicEngine.lastDevelopedProcedureName
                if (name != null) intel.vnicEngine.recordProcedureOutcome(name, true)
                OpResult(
                    true,
                    "Noted — that counted as a success, and the brain will weigh it more next time." +
                        if (name != null) " The plan \"$name\" is marked as having worked." else "",
                    "feedback=right"
                )
            }
        )
    )

    OperationRegistry.register(
        Operation(
            id = "vnic.say-that-was-wrong",
            title = "That was wrong",
            description = "Tells the brain its last answer was wrong, so it weighs that kind of answer less next time. Also marks the last plan it built as not having worked.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't note that — the app isn't ready yet.")
                intel.vnicEngine.giveFeedback(0.2)
                val name = intel.vnicEngine.lastDevelopedProcedureName
                if (name != null) intel.vnicEngine.recordProcedureOutcome(name, false)
                OpResult(
                    true,
                    "Noted — that counted as a miss, and the brain will weigh it less next time." +
                        if (name != null) " The plan \"$name\" is marked as not having worked." else "",
                    "feedback=wrong"
                )
            }
        )
    )

    // ----- Brain health (E-27, E-32) -----
    OperationRegistry.register(
        Operation(
            id = "vnic.check-brain-status",
            title = "Check the brain's status",
            description = "Says whether the brain's memory is saved on this phone, how many things it can do, how many plans it saved, and what it last looked at.",
            area = OperationArea.INVESTIGATIONS,
            riskLevel = "READ",
            execute = { context ->
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val st = intel.vnicEngine.status.value
                OpResult(
                    true,
                    "Brain: ${if (st.storeBackedByRoom) "memory saved on this phone" else "memory temporary only — saved memory not available"}. " +
                        "Things it can do: ${st.capabilityCount}. Saved plans: ${st.procedureCount}. " +
                        "Last thing it looked at: ${st.lastSubject.ifBlank { "nothing yet" }} — ${st.lastState.ifBlank { "not run yet" }}.",
                    "room=${st.storeBackedByRoom}"
                )
            }
        )
    )
}

/** The control-screen route when something is actually ready (persistent outcome first, then the last cycle). */
private fun readyRoute(intel: IntelViewModel): String? {
    val outcome = intel.vnicEngine.driveOutcome.value
    if (outcome != null && outcome.state == "CONTROLLABLE" && outcome.route.isNotBlank()) {
        return outcome.route
    }
    val st = intel.vnicEngine.status.value
    val route = st.lastBringUpRoute
    return if (route.isNotBlank() && st.lastBringUpHeld) route else null
}
