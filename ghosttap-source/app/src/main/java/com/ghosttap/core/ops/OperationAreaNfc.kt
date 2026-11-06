package com.ghosttap.core.ops

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ghosttap.GhostTapViewModel
import com.ghosttap.core.NAMED_NFC_COMMANDS
import com.ghosttap.core.NAMED_NFC_SCRIPTS
import com.ghosttap.core.OpResult
import com.ghosttap.core.buildNamedScript
import com.ghosttap.core.classicTrailerBlock
import com.ghosttap.core.namedCommandHex
import com.ghosttap.core.namedCommandTech
import com.ghosttap.core.namedScriptTech
import com.ghosttap.core.toHexString
import com.ghosttap.core.unknownCardNextStep
import com.ghosttap.data.ScanTarget
import com.ghosttap.data.TargetType
import kotlinx.coroutines.delay

/**
 * Package 4 — Area NFC (B-027–B-044 + D-32–D-48 + A-25) operations.
 *
 * Every outcome below wraps an EXISTING ViewModel function — read,
 * copy, write, erase, raw console, scripts, Mifare, IsoDep, emulation
 * — with preparation + preconfiguration + verification through the
 * [OperationRunner]. Nothing here re-implements what the ViewModel
 * already does. The one thing the operations add is the orchestration
 * the user used to do by hand: picking the deep read from the card
 * type, holding the "ready for the second card" state, checking
 * capacity before writing, confirming before erasing, building scripts
 * from named blocks, trying the usual passwords then reading what
 * opened, and turning a just-read card into the emulated tag.
 *
 * Free-text inputs (words, link, page/block numbers, passwords) are
 * staged in [AreaNfcState] by the NFC TOOLS panels as the user types
 * — the same live-staging pattern the pretend-to-be-a-tag panel
 * already uses for its text field, and the same stash pattern
 * [AreaBState.stashedPick] uses for two-step flows. An operation with
 * nothing staged fails plainly and says where to type it.
 *
 * Two operations already exist from Package 2 and are NOT re-registered
 * here: `nfc.try-next-command` (B-042 / D-47 — the guided "what to try
 * next" APDU check) and `nfc.emulation-status` (B-043 — whether
 * pretending is on). Package 4 reuses them and wires the same
 * "what to try next" list through the shared [com.ghosttap.core.nextApduCheck]
 * helper so panel and operation can never drift apart.
 *
 * The existing functions stay exactly as they are. Plain-words titles
 * here are entry-screen labels only; all 10 NFC panel titles keep
 * their original wording.
 */

/**
 * Small shared UI state owned by Area NFC: the free-text the user
 * typed on the NFC TOOLS panels (staged live, as they type), and the
 * two-tap copy flow's state (which card was copied from, so the copy
 * can never be written back onto the original itself).
 */
object AreaNfcState {
    /** Words typed in panel 3 ("Write words"), staged as they type. */
    var stagedText by mutableStateOf("")

    /** Link typed in panel 4 ("Write link"), staged as they type. */
    var stagedUrl by mutableStateOf("")

    /** Page number typed in panel 10, staged as they type. */
    var stagedPage by mutableStateOf("")

    /** 4 bytes of code typed in panel 10, staged as they type. */
    var stagedPageHex by mutableStateOf("")

    /** 4-byte password code typed in panel 10, staged as they type. */
    var stagedNtagPwd by mutableStateOf("")

    /** 16 bytes of code typed in panel 10, staged as they type. */
    var stagedMagicHex by mutableStateOf("")

    /** Block number typed in panel 9, staged as they type. */
    var stagedClassicBlock by mutableStateOf("")

    /** 16 bytes of code typed in panel 9, staged as they type. */
    var stagedClassicBlockHex by mutableStateOf("")

    /** True once card one has been copied and the flow waits for card two. */
    var copyReady by mutableStateOf(false)

    /** UID hex of the card the copy came from — the copy never goes back onto it. */
    var copiedFromUid: String? by mutableStateOf(null)

    /** Clears the copy flow so a later run starts from scratch. */
    fun clearCopyFlow() {
        copyReady = false
        copiedFromUid = null
    }
}

/** The most recently tapped NFC target, or null when none was tapped. */
private fun latestNfcTarget(ble: GhostTapViewModel): ScanTarget? =
    ble.allTargets.filter { it.type == TargetType.NFC }.maxByOrNull { it.lastSeenMs }

/** UID hex of the card held against the phone right now, or null. */
private fun currentTagUid(ble: GhostTapViewModel): String? =
    ble.lastNfcTag?.id?.toHexString()

/**
 * A preparation step that asks the user to pick one target from the
 * candidates matching [filter]. Same pattern as Area A/B: a pick the
 * entry screen already wrote passes; otherwise the user chooses.
 */
private fun chooseTargetStep(
    label: String,
    filter: (ScanTarget) -> Boolean,
    emptyReason: String
): PreparationStep = PreparationStep(label) { context ->
    val ble = context.ble
        ?: return@PreparationStep PreparationOutcome.Failed(
            "Can't check devices — the app isn't ready yet."
        )
    val candidates = ble.allTargets.filter(filter)
    if (candidates.isEmpty()) {
        return@PreparationStep PreparationOutcome.Failed(emptyReason)
    }
    val picked = context.pendingChoice
    if (picked != null &&
        candidates.any { it.name == picked || it.id == picked }
    ) {
        return@PreparationStep PreparationOutcome.Ready
    }
    PreparationOutcome.NeedsUserChoice(
        prompt = "Which one?",
        choices = candidates.map { it.name.ifBlank { it.id } }
    )
}

/** Peeks at the picked target without consuming the choice. */
private fun peekChosenTarget(
    context: OperationContext,
    filter: (ScanTarget) -> Boolean
): ScanTarget? {
    val ble = context.ble ?: return null
    val candidates = ble.allTargets.filter(filter)
    val picked = context.pendingChoice ?: return candidates.firstOrNull()
    return candidates.firstOrNull { it.name == picked || it.id == picked }
}

/** Resolves the picked target and consumes the choice. */
private fun chosenTarget(
    context: OperationContext,
    filter: (ScanTarget) -> Boolean
): ScanTarget? {
    val target = peekChosenTarget(context, filter)
    context.pendingChoice = null
    return target
}

/** Waits, bounded, for the Mifare key check / sector read to finish. */
private suspend fun waitForMifareIdle(
    ble: GhostTapViewModel,
    timeoutMs: Long = 65000L
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (!ble.mifareBusy) {
            // The key check hands off to the sector read through the
            // main thread — wait one beat and re-check, so a brief
            // idle gap between the two is not mistaken for done.
            delay(1000)
            if (!ble.mifareBusy) return true
        }
        delay(500)
    }
    return !ble.mifareBusy
}

/** Registers every Area NFC operation. Idempotent; safe to call again. */
fun registerNfcOperations() {
    val nfcFilter: (ScanTarget) -> Boolean = { it.type == TargetType.NFC }

    // ---- What kind of card is this? (D-32 / D-33 / D-34). ----
    // The facts Android reports, in plain words — plus a concrete next
    // step when the type came back UNKNOWN, instead of a dead end.
    OperationRegistry.register(
        Operation(
            id = "nfc.read-facts",
            title = "What kind of card is this?",
            description = "Says what the card against the phone is, what chip is inside it, and how much it can hold — and what to try next when it can't be named.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold a card against the back of the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val target = latestNfcTarget(ble)
                    ?: return@Operation OpResult(
                        false,
                        "No card tapped yet — hold a card against the phone first."
                    )
                val facts = ble.nfcFactsFor(target.id)
                    ?: return@Operation OpResult(
                        false,
                        "Tap the card again — its details didn't come through."
                    )
                val lines = buildString {
                    appendLine("Card: ${facts.likelyType} (ID ${facts.uidHex})")
                    facts.chipLine?.let { appendLine(it) }
                    facts.capacityLine?.let { appendLine("Memory: $it") }
                    appendLine(
                        if (facts.ndefWritable) "You can write to this card."
                        else "You can't write to this card."
                    )
                    appendLine("Things you can do: ${ble.nfcOpsFor(target.id).joinToString(", ")}")
                    unknownCardNextStep(facts.likelyType)?.let { appendLine(it) }
                }.trim()
                OpResult(true, lines, target.id)
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Read this card (B-027 / B-028 / D-36). ----
    // The deep read is picked from the card type itself: memory dump
    // for Ultralight/NTAG, block read for long-range cards, plain
    // re-read for everything else. The existing readers run unchanged.
    OperationRegistry.register(
        Operation(
            id = "nfc.read-card",
            title = "Read this card",
            description = "Reads the card against the phone — the right deep read is picked from what kind of card it is, so you never pick one by hand.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold a card against the back of the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't read — the app isn't ready yet.")
                val result = ble.nfcReadCardGuided()
                val ok = !looksLikeNfcReadFailure(result)
                OpResult(ok, result, "read")
            },
            verify = { context, result ->
                if (!result.ok) {
                    VerificationOutcome.NotVerified(
                        result.summary.ifBlank { "It didn't work." }
                    )
                } else if (context.ble?.lastNfcTag == null) {
                    VerificationOutcome.NotVerified(
                        "The card moved away while reading — hold it steady and try again."
                    )
                } else {
                    VerificationOutcome.Verified(result.summary.take(300))
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Copy this card onto that card (B-029 / D-37). ----
    // One guided two-tap flow, run twice: the first run copies card
    // one and reports "ready for the second card"; the second run
    // writes the copy onto card two — and refuses when card two is
    // the same card the copy came from.
    OperationRegistry.register(
        Operation(
            id = "nfc.copy-to-second-card",
            title = "Copy this card onto that card",
            description = "Copies the card against the phone, then waits for the second card — tap this again with the second card held to the phone and the copy is written onto it.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold the first card against the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't copy — the app isn't ready yet.")
                val uid = currentTagUid(ble)
                if (!ble.hasSavedNdef()) {
                    val copied = ble.nfcCopyContent()
                    if (!ble.hasSavedNdef()) {
                        return@Operation OpResult(false, copied)
                    }
                    AreaNfcState.copyReady = true
                    AreaNfcState.copiedFromUid = uid
                    OpResult(
                        true,
                        "$copied Ready for the second card — hold a DIFFERENT card against " +
                            "the phone and tap this again to write the copy onto it.",
                        "copy-ready"
                    )
                } else {
                    if (uid != null && uid == AreaNfcState.copiedFromUid) {
                        return@Operation OpResult(
                            false,
                            "That's the same card you copied from — hold a different card " +
                                "against the phone, then tap this again."
                        )
                    }
                    if (ble.nfcWriteSaved()) {
                        AreaNfcState.clearCopyFlow()
                        OpResult(
                            true,
                            "Saved data written — that's your copy.",
                            "copied"
                        )
                    } else {
                        OpResult(
                            false,
                            "Didn't work — the card may not be close enough or can't be written to."
                        )
                    }
                }
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Write these words, checked first (B-030 / D-38). ----
    // Capacity is checked BEFORE writing: too big refuses plainly
    // with both numbers. The verified writer runs unchanged.
    OperationRegistry.register(
        Operation(
            id = "nfc.write-words-checked",
            title = "Write these words onto the card",
            description = "Writes the words you typed on the NFC TOOLS screen onto the card — first checking they fit, then reading back to prove they landed.",
            area = OperationArea.NFC,
            prerequisites = listOf(
                "Type your words on the NFC TOOLS screen first",
                "Hold a card you can write to against the phone"
            ),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Checking your words fit") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val text = AreaNfcState.stagedText
                    if (text.isBlank()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Type your words on the NFC TOOLS screen first — the words box there."
                        )
                    }
                    ble.nfcFitsOnCard(text, false)?.let { tooBig ->
                        return@PreparationStep PreparationOutcome.Failed(tooBig)
                    }
                    PreparationOutcome.Ready
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't write — the app isn't ready yet.")
                val text = AreaNfcState.stagedText
                val result = ble.nfcWriteTextVerified(text)
                if ("verified" in result.lowercase()) {
                    AreaNfcState.stagedText = ""
                    OpResult(true, result, "written")
                } else {
                    OpResult(false, result)
                }
            },
            verify = { _, result -> verifyContent("verified", result.summary) },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Write this link, checked first (B-030 / D-38). ----
    OperationRegistry.register(
        Operation(
            id = "nfc.write-link-checked",
            title = "Write this link onto the card",
            description = "Writes the link you typed on the NFC TOOLS screen onto the card — first checking it fits, then reading back to prove it landed.",
            area = OperationArea.NFC,
            prerequisites = listOf(
                "Type your link on the NFC TOOLS screen first",
                "Hold a card you can write to against the phone"
            ),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Checking your link fits") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val url = AreaNfcState.stagedUrl
                    if (url.isBlank()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Type your link on the NFC TOOLS screen first — the link box there."
                        )
                    }
                    ble.nfcFitsOnCard(url, true)?.let { tooBig ->
                        return@PreparationStep PreparationOutcome.Failed(tooBig)
                    }
                    PreparationOutcome.Ready
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't write — the app isn't ready yet.")
                val url = AreaNfcState.stagedUrl
                val result = ble.nfcWriteUrlVerified(url)
                if ("verified" in result.lowercase()) {
                    AreaNfcState.stagedUrl = ""
                    OpResult(true, result, "written")
                } else {
                    OpResult(false, result)
                }
            },
            verify = { _, result -> verifyContent("verified", result.summary) },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Erase, with a confirm (B-031 / D-39). ----
    // The confirm is the new layer: the user picks "Yes, wipe it"
    // before anything is erased, and the read-back afterwards must
    // show nothing saved. A blank card that can be set up is set up
    // instead of erased.
    OperationRegistry.register(
        Operation(
            id = "nfc.erase-confirmed",
            title = "Wipe this card blank",
            description = "Wipes everything off the card against the phone — but only after you confirm, and it reads back afterwards to prove the card is blank.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold the card against the back of the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Confirming the wipe") { context ->
                    val picked = context.pendingChoice
                    if (picked == "Yes, wipe it") {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (picked == "No, keep it") {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Kept — nothing was erased."
                        )
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Wipe everything off this card? This can't be undone.",
                        choices = listOf("Yes, wipe it", "No, keep it")
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't wipe — the app isn't ready yet.")
                context.pendingChoice = null
                val target = latestNfcTarget(ble)
                val facts = target?.let { ble.nfcFactsFor(it.id) }
                val erased = when {
                    facts?.hasNdef == true -> ble.nfcErase()
                    facts?.canFormat == true -> ble.nfcFormat()
                    else -> ble.nfcErase()
                }
                if (!erased) {
                    return@Operation OpResult(
                        false,
                        "Didn't work — the card may not be close enough or is locked."
                    )
                }
                val back = target?.let { ble.nfcReadFull(it.id) } ?: ""
                if ("nothing saved" in back.lowercase() || "no readable" in back.lowercase()) {
                    OpResult(
                        true,
                        "Card erased — it's blank now. Checked afterwards: nothing saved on it.",
                        "erased"
                    )
                } else {
                    OpResult(
                        false,
                        "Erase sent, but reading it back still shows data — try again with the card held steady."
                    )
                }
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Set up a blank card (B-031 / D-39 — the format half). ----
    OperationRegistry.register(
        Operation(
            id = "nfc.set-up-blank-card",
            title = "Set up a blank card",
            description = "Sets up a blank card so it can hold your words or a link.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold a blank card against the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Checking it is a blank card") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val target = latestNfcTarget(ble)
                    val facts = target?.let { ble.nfcFactsFor(it.id) }
                    if (facts?.canFormat == true) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "This card isn't a blank card that can be set up — it already holds data, or it can't hold any."
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't set up — the app isn't ready yet.")
                if (!ble.nfcFormat()) {
                    return@Operation OpResult(
                        false,
                        "Couldn't set the card up — hold it steady against the phone and try again."
                    )
                }
                OpResult(true, "Blank card ready — it can hold your words or a link now.", "formatted")
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Send one named command (B-033 / D-41 / D-42). ----
    // The user picks what to do in plain words; the hex comes from
    // the named block. The existing raw console sends it unchanged.
    OperationRegistry.register(
        Operation(
            id = "nfc.send-named-command",
            title = "Send a named command to the card",
            description = "Sends one ready-made command to the card — read a page, check the version — without typing any codes.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold a card against the back of the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Picking what to send") { context ->
                    val picked = context.pendingChoice
                    if (picked != null && NAMED_NFC_COMMANDS.any { it.name == picked }) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "What should the card do?",
                        choices = NAMED_NFC_COMMANDS.map { it.name }
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't send — the app isn't ready yet.")
                val name = context.pendingChoice
                    ?: return@Operation OpResult(false, "Pick a command first.")
                context.pendingChoice = null
                val hex = namedCommandHex(name)
                    ?: return@Operation OpResult(false, "Couldn't find that command.")
                val tech = namedCommandTech(name) ?: "AUTO"
                val result = ble.nfcRawTransceiveTech(hex, tech)
                OpResult(!looksLikeNfcReadFailure(result), result, "sent")
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Run a named script on the next tap (B-034 / D-41 / D-42). ----
    // Scripts are built from named blocks; the one parser
    // (parseHexScript, inside the existing runner) parses them.
    OperationRegistry.register(
        Operation(
            id = "nfc.run-named-script",
            title = "Run a ready-made script on the card",
            description = "Runs a ready-made script — several commands in one connected session — on the card against the phone.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold a card against the back of the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Picking a script") { context ->
                    val picked = context.pendingChoice
                    if (picked != null && NAMED_NFC_SCRIPTS.any { it.name == picked }) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which script?",
                        choices = NAMED_NFC_SCRIPTS.map { it.name }
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't run — the app isn't ready yet.")
                val name = context.pendingChoice
                    ?: return@Operation OpResult(false, "Pick a script first.")
                context.pendingChoice = null
                val script = buildNamedScript(name)
                    ?: return@Operation OpResult(false, "Couldn't find that script.")
                val tech = namedScriptTech(name) ?: "AUTO"
                val result = ble.nfcRunScript(script, tech)
                // "0/N commands got an answer" means nothing answered.
                val ok = !looksLikeNfcReadFailure(result) && !result.trim().startsWith("0/")
                OpResult(ok, result, "ran")
            },
            verify = { _, result -> verifyContent("commands got an answer", result.summary) },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Arm a named script for the next tap (B-035 / D-41). ----
    // "Run this script on the next tap", built from named blocks.
    // Honest limit, stated in the description: it fires on ANY card
    // tapped next, and it is forgotten when the app closes (F-48).
    OperationRegistry.register(
        Operation(
            id = "nfc.arm-named-script",
            title = "Run a ready-made script on the next tap",
            description = "Saves a ready-made script and sets it to run by itself the next time you tap a card. It runs on any card you tap next, and it is forgotten when the app closes.",
            area = OperationArea.NFC,
            preparation = listOf(
                PreparationStep("Picking a script") { context ->
                    val picked = context.pendingChoice
                    if (picked != null && NAMED_NFC_SCRIPTS.any { it.name == picked }) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which script should run on the next tap?",
                        choices = NAMED_NFC_SCRIPTS.map { it.name }
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't set it — the app isn't ready yet.")
                val name = context.pendingChoice
                    ?: return@Operation OpResult(false, "Pick a script first.")
                context.pendingChoice = null
                val script = buildNamedScript(name)
                    ?: return@Operation OpResult(false, "Couldn't find that script.")
                val tech = namedScriptTech(name) ?: "AUTO"
                ble.saveNfcScript(name, script, tech)
                // armTapRun toggles: only arm when it is not already armed.
                if (ble.tapRunArmed != name) ble.armTapRun(name)
                if (ble.tapRunArmed == name) {
                    OpResult(
                        true,
                        "Ready — tap a card and '$name' runs by itself.",
                        "armed:$name"
                    )
                } else {
                    OpResult(false, "Couldn't set it ready — try again.")
                }
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Read the stored blocks off a long-range card (B-038 / D-43). ----
    // The range is picked from three plain choices instead of typed
    // numbers; the UID is filled in from the tapped card itself.
    OperationRegistry.register(
        Operation(
            id = "nfc.read-card-blocks",
            title = "Read the stored blocks off a long-range card",
            description = "Reads the stored blocks off a long-range card — pick which set of blocks, the card's own ID is filled in for you.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold a long-range card against the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Checking it is a long-range card") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val target = latestNfcTarget(ble)
                    val facts = target?.let { ble.nfcFactsFor(it.id) }
                    val isNfcV = facts?.techs?.any { t ->
                        t.substringAfterLast('.') == "NfcV"
                    } == true
                    if (isNfcV) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "Not a long-range card — these blocks only exist on long-range cards."
                    )
                },
                PreparationStep("Picking which blocks") { context ->
                    val picked = context.pendingChoice
                    if (picked != null && picked in BLOCK_RANGES) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which blocks?",
                        choices = BLOCK_RANGES
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't read — the app isn't ready yet.")
                val choice = context.pendingChoice ?: BLOCK_RANGES.first()
                context.pendingChoice = null
                val first = choice.substringAfter("Blocks ").substringBefore(" to").toIntOrNull() ?: 0
                val result = ble.nfcReadNfcVBlocks(first, 16)
                OpResult(!looksLikeNfcReadFailure(result), result, "blocks")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Which pages are safe to write? (B-036 / D-48). ----
    // The map the user was missing: pages 0–3 hold the card's ID and
    // lock settings, pages 4 and up are theirs. The write itself
    // stays in panel 10, verified by reading back.
    OperationRegistry.register(
        Operation(
            id = "nfc.show-safe-pages",
            title = "Show which pages are safe to write",
            description = "Reads the card's memory and says which pages hold its ID and lock settings (don't write there) and which pages are yours to write.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold an Ultralight or NTAG card against the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Checking it is an Ultralight card") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val target = latestNfcTarget(ble)
                    val facts = target?.let { ble.nfcFactsFor(it.id) }
                    if (facts?.mifareUltralightType != null) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "Not an Ultralight card — this map only works on Ultralight and NTAG cards."
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't read — the app isn't ready yet.")
                val dump = ble.nfcDumpMemory()
                val pages = dump.lines().count { it.startsWith("Page ") }
                if (pages == 0) {
                    return@Operation OpResult(false, dump)
                }
                OpResult(
                    true,
                    "This card has $pages page(s) read. Pages 0–3 hold the card's ID and " +
                        "lock settings — don't write there, or the card can break. " +
                        "Pages 4 and up are yours to write (card 10, Write page).",
                    "pages:$pages"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Write one block, with the key-area guard (B-037 / D-45). ----
    // The key check must already have opened the sector; writing the
    // last block of a section (its passwords) asks first (F-49).
    OperationRegistry.register(
        Operation(
            id = "nfc.classic-write-guided",
            title = "Write one block on a door card",
            description = "Writes the 16 bytes you typed on the NFC TOOLS screen to one block of an open section — and asks first when the block holds the section's passwords.",
            area = OperationArea.NFC,
            prerequisites = listOf(
                "Hold a Mifare Classic card against the phone",
                "Run 'Try the usual passwords' first, so a section is open",
                "Type the block number and 16 bytes on the NFC TOOLS screen first"
            ),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Checking a section is open") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    if (ble.mifareKeyResults.filterValues { it != null }.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "No open sections — run 'Try the usual passwords' first."
                        )
                    }
                    PreparationOutcome.Ready
                },
                PreparationStep("Checking the block") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val block = AreaNfcState.stagedClassicBlock.toIntOrNull()
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Type a block number on the NFC TOOLS screen first (card 9)."
                        )
                    if (AreaNfcState.stagedClassicBlockHex.isBlank()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Type the 16 bytes on the NFC TOOLS screen first (card 9)."
                        )
                    }
                    val tag = ble.lastNfcTag
                    val isTrailer = tag?.let { classicTrailerBlock(it, block) } == true
                    if (!isTrailer) return@PreparationStep PreparationOutcome.Ready
                    val picked = context.pendingChoice
                    if (picked == "Yes, write the key area") {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (picked == "No, pick another block") {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Kept — nothing was written. Pick a different block."
                        )
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Block $block holds this section's passwords — writing there can lock the section. Write it anyway?",
                        choices = listOf("Yes, write the key area", "No, pick another block")
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't write — the app isn't ready yet.")
                context.pendingChoice = null
                val block = AreaNfcState.stagedClassicBlock.toIntOrNull()
                    ?: return@Operation OpResult(false, "Type a block number first.")
                val result = ble.nfcWriteClassicBlock(block, AreaNfcState.stagedClassicBlockHex)
                OpResult("verified" in result.lowercase(), result, "block:$block")
            },
            verify = { _, result -> verifyContent("verified", result.summary) },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Try the usual passwords, then read what opened (B-041 / D-45). ----
    // One flow: the existing key check runs, the operation waits for
    // it, then the existing open-sector read runs on what opened.
    OperationRegistry.register(
        Operation(
            id = "nfc.try-usual-passwords",
            title = "Try the usual passwords, then read what opened",
            description = "Tries the usual factory passwords on every section of a door card, then reads the sections that opened — all in one go.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold a Mifare Classic card against the phone"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Checking it is a door card") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val target = latestNfcTarget(ble)
                    val facts = target?.let { ble.nfcFactsFor(it.id) }
                    if (facts?.mifareClassicSectors != null) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "Not a Mifare Classic card — these passwords only work on door and access cards."
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't try — the app isn't ready yet.")
                ble.mifareTryAndRead()
                if (!waitForMifareIdle(ble)) {
                    return@Operation OpResult(
                        false,
                        "Still checking passwords — watch the status on the NFC TOOLS screen."
                    )
                }
                val open = ble.mifareKeyResults.filterValues { it != null }
                val status = ble.mifareStatus
                if (open.isEmpty()) {
                    OpResult(
                        false,
                        "Checked every section — none opened with the usual passwords. " +
                            "This card doesn't use the factory passwords, so there's nothing more to read here."
                    )
                } else {
                    OpResult(
                        true,
                        "${open.size} section(s) opened. $status",
                        "open:${open.size}"
                    )
                }
            },
            verify = { context, result ->
                val ble = context.ble
                val open = ble?.mifareKeyResults?.filterValues { it != null }?.size ?: 0
                if (result.ok && open > 0) {
                    VerificationOutcome.Verified("$open section(s) opened: ${result.summary.take(200)}")
                } else {
                    VerificationOutcome.NotVerified(
                        result.summary.ifBlank { "No sections opened." }
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Unlock with your password (B-039 / D-44). ----
    // The password comes from the panel; the existing PWD_AUTH runs
    // unchanged, and the answer tells a wrong password from a card
    // that isn't password-protected.
    OperationRegistry.register(
        Operation(
            id = "nfc.unlock-with-password",
            title = "Unlock a password card",
            description = "Sends the 4-byte password you typed on the NFC TOOLS screen to a password-protected card and says whether it was accepted.",
            area = OperationArea.NFC,
            prerequisites = listOf(
                "Hold the card against the phone",
                "Type the 4-byte password code on the NFC TOOLS screen first"
            ),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Checking for a password") {
                    if (AreaNfcState.stagedNtagPwd.isBlank()) {
                        PreparationOutcome.Failed(
                            "Type the 4-byte password code on the NFC TOOLS screen first (card 10)."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't unlock — the app isn't ready yet.")
                val result = ble.nfcNtagPwdAuth(AreaNfcState.stagedNtagPwd)
                OpResult("accepted" in result.lowercase(), result, "auth")
            },
            verify = { _, result -> verifyContent("accepted", result.summary) },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Special write, with a retry (B-040 / D-44). ----
    // The three-step backdoor sequence runs unchanged inside the
    // existing writer; the runner retries once when the card answers
    // slowly (the gap was: no retry, failing at step 2 after the
    // backdoor was already open).
    OperationRegistry.register(
        Operation(
            id = "nfc.magic-write-guided",
            title = "Write the first block of a special copy card",
            description = "Writes the 16 bytes you typed on the NFC TOOLS screen to the first block of a special copy card — trying once more when the card answers slowly.",
            area = OperationArea.NFC,
            prerequisites = listOf(
                "Hold a special copy card against the phone",
                "Type the 16 bytes on the NFC TOOLS screen first"
            ),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) },
                PreparationStep("Checking for the 16 bytes") {
                    if (AreaNfcState.stagedMagicHex.isBlank()) {
                        PreparationOutcome.Failed(
                            "Type the 16 bytes on the NFC TOOLS screen first (card 10)."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't write — the app isn't ready yet.")
                val result = ble.nfcMagicWriteBlock0(AreaNfcState.stagedMagicHex)
                OpResult("written" in result.lowercase(), result, "magic")
            },
            verify = { _, result -> verifyContent("written", result.summary) },
            retry = RetryPolicy(maxAttempts = 2, delayMs = 1000L),
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Explain the last card answer (B-042 / D-46). ----
    // The APDU helpers as an outcome: the last command's answer,
    // decoded into plain words, with the command itself explained.
    // (The guided "what to try next" check itself already exists as
    // `nfc.try-next-command` from Package 2 and is not duplicated.)
    OperationRegistry.register(
        Operation(
            id = "nfc.explain-last-answer",
            title = "Explain the last card answer",
            description = "Explains the smart card's last answer in plain words — what was sent, what the card's result code means, and what each part of the command does.",
            area = OperationArea.NFC,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a smart card",
                    nfcFilter,
                    "No tap card found — hold a card against the phone first."
                ),
                PreparationStep("Checking it is a smart card") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val target = peekChosenTarget(context, nfcFilter)
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Couldn't find that card any more."
                        )
                    if (ble.nfcFactsFor(target.id)?.isIsoDep == true) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "That card isn't a smart card — these explanations only work on smart cards."
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't explain — the app isn't ready yet.")
                val target = chosenTarget(context) {
                    it.type == TargetType.NFC && ble.nfcFactsFor(it.id)?.isIsoDep == true
                } ?: return@Operation OpResult(false, "Couldn't find that card any more.")
                val last = ble.isoDepHistoryFor(target.id).lastOrNull()
                    ?: return@Operation OpResult(
                        false,
                        "No card commands sent yet — send one from the Card commands panel first."
                    )
                val breakdown = ble.isoDepDescribeCommand(last.commandHex)
                OpResult(
                    true,
                    "Last command ${last.commandHex}: ${last.statusMeaning}\n" +
                        breakdown.joinToString("\n"),
                    last.responseHex
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Pretend to be the card I just read (B-043 / D-40). ----
    // The NEW path: the last-read card's own words or link become the
    // emulated tag, and pretending is turned on through the existing
    // runtime switch. DONE only when pretending is actually on.
    OperationRegistry.register(
        Operation(
            id = "nfc.pretend-to-be-this-card",
            title = "Pretend to be the card I just read",
            description = "Makes the phone pretend to be the card against it — broadcasting that card's own words or link — and checks pretending is really on.",
            area = OperationArea.NFC,
            prerequisites = listOf("Hold the card against the phone so it can be read first"),
            preparation = listOf(
                PreparationStep("Checking for a card") { ensureTagPresent(it) }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't pretend — the app isn't ready yet.")
                val result = ble.nfcEmulateLastReadCard()
                OpResult(result.startsWith("Pretending"), result, "emulating")
            },
            verify = { context, result ->
                val ble = context.ble
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Can't check — the app isn't ready yet."
                    )
                if (result.ok && ble.isEmulating) {
                    VerificationOutcome.Verified(
                        "Pretending is on — broadcasting: ${ble.emulatedSummary()}."
                    )
                } else {
                    VerificationOutcome.NotVerified(
                        result.summary.ifBlank { "Pretending didn't turn on." }
                    )
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )
}

/** Block ranges the long-range card read offers, in plain words. */
private val BLOCK_RANGES = listOf(
    "Blocks 0 to 15",
    "Blocks 16 to 31",
    "Blocks 32 to 47"
)

/**
 * The NFC read/write functions answer in plain failure sentences on
 * failure ("No tag nearby…", "The tag didn't answer…"). Prefix-based,
 * mirroring the screen's `looksLikeNfcFailure` heuristic, so an
 * operation never reports DONE on a failure sentence — while a good
 * read that honestly reports one bad block ("Block 1: tag error …")
 * is not mistaken for a failure.
 */
private fun looksLikeNfcReadFailure(msg: String): Boolean {
    val m = msg.trim()
    return listOf(
        "No tag", "The tag didn't answer", "Couldn't", "This tag doesn't",
        "Not an ", "Not a ", "No readable", "No blocks", "Script is empty",
        "Too many commands", "Couldn't parse", "Didn't work", "No answer"
    ).any { m.startsWith(it) }
}
