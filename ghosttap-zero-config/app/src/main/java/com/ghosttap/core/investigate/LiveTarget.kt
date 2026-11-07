package com.ghosttap.core.investigate

import com.ghosttap.core.BringUpKind
import com.ghosttap.core.BringUpTarget

/**
 * MASTER PROMPT — LIVE TARGETS, NOT HISTORY.
 *
 * A [LiveTarget] is one thing Ghost Tap found RIGHT NOW that it can
 * connect to — a live, connectable target, not a history entry. The
 * [InvestigationLedger] stays underneath as internal storage/dedup
 * only (upsert by target id); it is never what the user sees or uses.
 * What the user sees is [LiveTargetStore]: the live list, newest
 * first, each card carrying what the thinking engine says about it,
 * what it can do with it, a CONNECT button, and — once connected —
 * the commands he can tap on it.
 *
 * Nothing here connects, reads, writes, or sends anything. The store
 * only holds reports; connection and commands run through the
 * existing bring-up and operation paths, only when he taps.
 *
 * Pure Kotlin — no Android framework imports — so this file is
 * unit-testable on the JVM.
 */

/**
 * Which engine produced the reasoning shown on a live target card.
 * The card shows this in plain words, every time — an answer from
 * the app's own rules is NEVER presented as the AI's.
 */
enum class AiReasoningState {
    /** The thinking engine has not answered yet. */
    WAITING,
    /** The AI (his key, remote model) answered. */
    AI,
    /** No AI key is in the app, so the app's own rules answered. */
    LOCAL_NO_KEY,
    /** The AI key is in the app but the AI could not be reached, so the app's own rules answered. */
    LOCAL_AI_FAILED
}

/** Plain-words line for which engine produced the reasoning. */
fun AiReasoningState.plainLabel(): String = when (this) {
    AiReasoningState.WAITING -> "Asking the thinking engine…"
    AiReasoningState.AI -> "Checked by your AI (your key)"
    AiReasoningState.LOCAL_NO_KEY ->
        "Checked by the app's own rules — your AI key isn't in the app yet. Add it under THINKING ENGINE on the Investigations screen."
    AiReasoningState.LOCAL_AI_FAILED ->
        "Your AI couldn't be reached — checked by the app's own rules instead. Check the key under THINKING ENGINE."
}

/**
 * Why the AI call failed, when the key is in the app but the AI
 * could not be reached. Classified from the REAL failure — the
 * actual exception or the response code — never guessed. The card
 * shows [plainWords] so he always knows which one it was.
 */
enum class AiFailureReason {
    /** The phone could not reach the AI service at all (DNS/connect). */
    NO_NETWORK,
    /** The call started but took too long to answer. */
    TIMEOUT,
    /** The service answered 401/403 — the key is not accepted. */
    KEY_REJECTED,
    /** The service answered with some other refusal. */
    SERVER_REFUSED,
    /** The service answered 429 — too many requests right now. */
    TOO_BUSY,
    /** The service answered 5xx — a problem on its side. */
    SERVER_ERROR,
    /** The service answered but said nothing usable. */
    EMPTY_ANSWER,
    /** The service answered, but the answer couldn't be read. */
    UNREADABLE_ANSWER
}

/** Plain-words line for why the AI call failed. No codes, no jargon. */
fun AiFailureReason.plainWords(): String = when (this) {
    AiFailureReason.NO_NETWORK ->
        "the phone couldn't reach the AI service — check the connection and try again"
    AiFailureReason.TIMEOUT ->
        "the AI took too long to answer"
    AiFailureReason.KEY_REJECTED ->
        "OpenAI said the key isn't accepted — check the key under THINKING ENGINE"
    AiFailureReason.SERVER_REFUSED ->
        "the AI service said no — try again in a bit"
    AiFailureReason.TOO_BUSY ->
        "the AI service is busy right now — try again in a bit"
    AiFailureReason.SERVER_ERROR ->
        "the AI service had a problem on its side — try again later"
    AiFailureReason.EMPTY_ANSWER ->
        "the AI answered but said nothing useful"
    AiFailureReason.UNREADABLE_ANSWER ->
        "the AI's answer came back but couldn't be read — try again"
}

/**
 * One live, connectable target.
 *
 * [key] is stable per target (the report's targetId when it has one,
 * otherwise source + label) so a re-check refreshes the same card
 * instead of stacking a duplicate.
 * [route] is the function's page CONNECT lands on (null when this
 * kind of target has no page of its own — a thing he typed, for
 * example). [connectChoice] is the id handed to a command's
 * preparation step so it picks THIS target without asking (a
 * Bluetooth address, a network address, "usb", a tag id) — null
 * when commands need no target.
 * [aiText] is the thinking engine's plain-words answer about this
 * target, or null while it is still thinking. [aiState] says which
 * engine produced it — never faked.
 * [commands] are operation ids from [LiveTargetCommands] — every one
 * resolves to a real operation in the registry and runs through the
 * existing OperationRunner when tapped. An empty list means this
 * target has no tappable commands yet (a Wi-Fi network, before it is
 * joined — see [LiveTargetCommands]).
 */
data class LiveTarget(
    val key: String,
    val report: FindingsReport,
    val source: InvestigationSource,
    val route: String?,
    val connectChoice: String?,
    val aiText: String?,
    val aiState: AiReasoningState,
    val commands: List<String>,
    /** Why the AI call failed, when [aiState] is LOCAL_AI_FAILED. Null otherwise. */
    val aiFailureReason: AiFailureReason? = null
)

/** Stable key for a report: its target id, or source + label. */
fun liveTargetKey(report: FindingsReport, source: InvestigationSource): String =
    report.targetId ?: "${source.name}:${report.targetLabel}"

/**
 * The function's page for this kind of target — the page CONNECT
 * lands on. BLE lands on the target's own page; USB, network, NFC,
 * visual, and the practice board land on their section's page. A
 * picture's target and a typed thing have no page of their own, so
 * this is null and the card says so instead of inventing a page.
 */
fun routeForTarget(report: FindingsReport, source: InvestigationSource): String? =
    when (source) {
        InvestigationSource.BLE -> {
            val mac = report.targetId?.removePrefix("ble:")?.trim().orEmpty()
            if (mac.isNotBlank()) "target/$mac" else null
        }
        InvestigationSource.USB -> "usb_uart"
        InvestigationSource.NETWORK -> "network_tools"
        InvestigationSource.NFC -> "nfc_tools"
        InvestigationSource.VISUAL -> "visual_intel"
        InvestigationSource.VIRTUAL_ESP32 -> "vesp32"
        InvestigationSource.MANUAL -> null
    }

/**
 * The id a command's preparation step uses to pick this target
 * without asking. Null when commands need no target (a picture, a
 * typed thing).
 */
fun connectChoiceFor(report: FindingsReport, source: InvestigationSource): String? =
    when (source) {
        InvestigationSource.BLE -> report.targetId?.removePrefix("ble:")?.trim()
            ?.takeIf { it.isNotBlank() }
        InvestigationSource.USB -> "usb"
        InvestigationSource.NETWORK -> report.targetId?.removePrefix("net:")?.trim()
            ?.takeIf { it.isNotBlank() && !report.targetId.startsWith("wifi:") }
        InvestigationSource.NFC -> report.targetId?.takeIf { it.isNotBlank() }
        InvestigationSource.VIRTUAL_ESP32 -> "virtual:practice-board"
        InvestigationSource.VISUAL, InvestigationSource.MANUAL -> null
    }

/**
 * The existing bring-up target for a report, or null when there is
 * no honest bring-up from a report alone. A real bring-up is wired
 * ONLY when the identifier the bring-up path needs is really there:
 * a Bluetooth address, the fixed USB id, or a network address. A
 * plain name, an NFC tag, a picture, a practice-board device, or a
 * typed thing has no reachable bring-up from here, and no path is
 * invented for it — the label is never guessed into an id, because
 * a wrong id would try to connect to the wrong thing. Those screens
 * keep their own existing connect path instead.
 */
fun bringUpTargetFor(report: FindingsReport): BringUpTarget? {
    val label = report.targetLabel.trim()
    return when (report.targetKind) {
        "Bluetooth device" -> {
            val fromId = report.targetId?.removePrefix("ble:")?.trim().orEmpty()
            val macAddress = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
            // The address usually arrives as the target id ("ble:AA:…");
            // a typed report may carry it as the label instead.
            val mac = when {
                macAddress.matches(fromId) -> fromId
                macAddress.matches(label) -> label
                else -> ""
            }
            if (mac.isNotBlank()) {
                BringUpTarget(kind = BringUpKind.BLE, id = mac, title = label)
            } else {
                null
            }
        }
        "USB device" -> BringUpTarget(kind = BringUpKind.USB, id = "usb", title = label)
        "Network device" -> {
            val isAddress =
                Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(label)
            if (isAddress) {
                BringUpTarget(kind = BringUpKind.NETWORK, id = label, title = label)
            } else {
                null
            }
        }
        else -> null
    }
}

/**
 * The question the AI is asked about one finding. Built ONLY from
 * what was really observed — the name, the kind, the evidence lines,
 * the services/values the target showed. Nothing is invented: no
 * guessed brand, model, or capability. The AI answers in plain
 * words: what it thinks this is, what Ghost Tap can connect to,
 * read, write, and command on it, and what to be careful about.
 */
fun aiPromptFor(report: FindingsReport): String = buildString {
    append("A device was found. Here is ONLY what was actually observed — do not invent anything beyond this:\n")
    append("Name shown: ${report.targetLabel}\n")
    append("Kind: ${report.targetKind}\n")
    append("What it is believed to be: ${report.believedIdentity}\n")
    append("How sure: ${report.confidence}\n")
    append("Evidence:\n")
    report.evidence.forEach { append("- $it\n") }
    append("What Ghost Tap already knows it can do with it:\n")
    if (report.capabilities.isEmpty()) {
        append("- Nothing known yet\n")
    } else {
        report.capabilities.forEach {
            append("- ${it.label}: ${it.plainDescription}\n")
        }
    }
    append("Risks:\n")
    report.risks.forEach { append("- ${it.plainText}\n") }
    append(
        "In plain words a non-technical person understands, say: " +
            "what you think this is, what Ghost Tap can connect to, read, write, " +
            "and command on it, and what to be careful about. " +
            "Say clearly when you are guessing. Keep it short."
    )
}

/**
 * What the app's own on-phone rules say about a target — the
 * fallback when there is no AI key or the AI cannot be reached.
 * Built only from the report itself. This text is NEVER labeled as
 * the AI's: the card shows it under "the app's own rules".
 */
fun localReasoningText(report: FindingsReport): String = buildString {
    append("What the app itself can tell you: ")
    if (report.believedIdentity == "UNKNOWN") {
        append("not enough proof to name this one. ")
    } else {
        append("it calls itself \"${report.believedIdentity}\". ")
    }
    if (report.evidence.isNotEmpty()) {
        append("Seen: ${report.evidence.joinToString("; ")}. ")
    }
    if (report.capabilities.isEmpty()) {
        append("Nothing known yet about what can be done with it — connect first to find out more.")
    } else {
        append("Known so far: ${report.capabilities.joinToString("; ") { "${it.label} — ${it.plainDescription}" }}.")
    }
}

/**
 * The command center: every command Ghost Tap can actually run on a
 * connected target of this kind, as operation ids. Each id must
 * resolve in [com.ghosttap.core.ops.OperationRegistry] — the card
 * skips any id that does not, so a missing operation never becomes
 * a dead button. Every listed command runs through the existing
 * OperationRunner when tapped (preparation, execution, verification),
 * and the answer is shown in plain words. Nothing is listed that
 * has no execution behind it: no find/scan operations (the target
 * is already found), no screen-opening operations (CONNECT already
 * lands on the page), and nothing that needs a choice the card
 * cannot supply.
 *
 * A Wi-Fi network has no commands until it is joined — the phone
 * cannot talk to a network it is not on — so its list is empty and
 * the card says to join it first.
 */
object LiveTargetCommands {

    /** Curated, verified operation ids per source. See the class doc. */
    fun forSource(source: InvestigationSource, targetId: String?): List<String> =
        when (source) {
            InvestigationSource.BLE -> listOf(
                "ble.read-everything",
                "ble.read-with-answer",
                "ble.read-interesting",
                "ble.listen-everything",
                "ble.send-to-interesting",
                "ble.explain-this-device",
                "ble.guided-map-round",
                "ble.what-just-happened",
                "ble.disconnect-all"
            )
            InvestigationSource.USB -> listOf(
                "usb.talk-to-board",
                "usb.watch-replies",
                "usb.test-board",
                "usb.read-board-setting",
                "usb.describe-board",
                "usb.copy-log",
                "usb.find-speed",
                "usb.set-speed",
                "usb.restart-board",
                "usb.disconnect-board"
            )
            InvestigationSource.VIRTUAL_ESP32 -> listOf(
                "vesp32.send-message",
                "vesp32.check-light-pin",
                "vesp32.flip-switch-pin",
                "vesp32.check-practice-status",
                "vesp32.restart-practice-software",
                "vesp32.start-practice-software",
                "vesp32.stop-practice-software",
                "vesp32.disconnect-practice-board"
            )
            InvestigationSource.NETWORK -> {
                if (targetId != null && targetId.startsWith("wifi:")) {
                    // A network the phone is not on yet: nothing can
                    // run on it until it is joined.
                    emptyList()
                } else {
                    listOf(
                        "net.ping-host",
                        "net.check-host-ports",
                        "net.fetch-page",
                        "net.talk-to-device",
                        "net.ask-device",
                        "net.open-advertised-service"
                    )
                }
            }
            InvestigationSource.NFC -> listOf(
                "nfc.read-card",
                "nfc.read-facts",
                "nfc.explain-last-answer",
                "nfc.send-named-command",
                "nfc.run-named-script",
                "nfc.write-words-checked",
                "nfc.write-link-checked",
                "nfc.copy-to-second-card"
            )
            InvestigationSource.VISUAL -> listOf(
                "visual.act-on-last-photo",
                "visual.fetch-link",
                "visual.join-wifi-from-photo",
                "visual.copy-from-photo",
                "visual.investigate-photo",
                "visual.save-last-photo"
            )
            InvestigationSource.MANUAL -> listOf(
                "intel.look-into-device",
                "intel.what-is-this",
                "intel.what-can-i-do-with-this",
                "intel.run-any-control",
                "intel.list-everything-it-can-do",
                "intel.search-the-web-for-this"
            )
        }
}

/**
 * The live list of connectable targets. In-memory only, newest
 * first. The [InvestigationLedger] underneath stays as internal
 * storage/dedup; this store is what the screens render.
 *
 * Synchronized: publish() runs from discovery triggers (BLE scan,
 * USB attach, NFC read, network discovery) while all() is read on
 * the UI thread.
 */
object LiveTargetStore {

    private val targets = mutableListOf<LiveTarget>()

    /**
     * Publish a fresh report as a live target, replacing the earlier
     * card for the same target instead of stacking a duplicate. Two
     * cards are "the same target" only when they share the same key
     * (see [liveTargetKey]).
     *
     * The thinking-engine answer is KEPT when the new report's
     * evidence and capabilities are unchanged — a re-check must not
     * wipe an AI answer that is still about the same thing. When
     * the evidence changed, the answer is dropped back to WAITING
     * so the engine reasons again about the new facts.
     */
    fun publish(report: FindingsReport, source: InvestigationSource): LiveTarget {
        synchronized(targets) {
            val key = liveTargetKey(report, source)
            val existing = targets.firstOrNull { it.key == key }
            val keepReasoning = existing != null &&
                existing.report.evidence == report.evidence &&
                existing.report.capabilities == report.capabilities
            val fresh = LiveTarget(
                key = key,
                report = report,
                source = source,
                route = routeForTarget(report, source),
                connectChoice = connectChoiceFor(report, source),
                aiText = if (keepReasoning) existing!!.aiText else null,
                aiState = if (keepReasoning) existing!!.aiState else AiReasoningState.WAITING,
                commands = LiveTargetCommands.forSource(source, report.targetId),
                aiFailureReason = if (keepReasoning) existing!!.aiFailureReason else null
            )
            val idx = targets.indexOfFirst { it.key == key }
            if (idx >= 0) targets[idx] = fresh else targets.add(0, fresh)
            return fresh
        }
    }

    fun get(key: String): LiveTarget? = synchronized(targets) {
        targets.firstOrNull { it.key == key }
    }

    fun all(): List<LiveTarget> = synchronized(targets) { targets.toList() }

    fun newestFor(source: InvestigationSource): LiveTarget? = synchronized(targets) {
        targets.firstOrNull { it.source == source }
    }

    /** Record a thinking-engine answer for one live target. */
    fun setReasoning(
        key: String,
        text: String?,
        state: AiReasoningState,
        failureReason: AiFailureReason? = null
    ) {
        synchronized(targets) {
            val idx = targets.indexOfFirst { it.key == key }
            if (idx >= 0) {
                targets[idx] = targets[idx].copy(
                    aiText = text,
                    aiState = state,
                    aiFailureReason = failureReason
                )
            }
        }
    }

    fun clear() {
        synchronized(targets) { targets.clear() }
    }
}
