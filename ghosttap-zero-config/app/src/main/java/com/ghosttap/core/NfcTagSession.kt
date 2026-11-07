package com.ghosttap.core

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigationInput
import com.ghosttap.core.investigate.InvestigationLedger
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.Investigator
import com.ghosttap.core.investigate.LiveTargetStore

/**
 * Package 4 — the ONE tag-session manager every NFC panel shares.
 *
 * The panels used to each manage their own idea of "is there a card,
 * can this card do this, what happened": the tag-present check lived
 * in the panel text, the supported-ops list ([nfcSupportedOps]) was
 * never consulted, and each panel reported its own result. This file
 * is the single place for all of that:
 *
 * - [gateForPanel]: which of the 10 NFC panels a card can honestly
 *   use, and the plain-words reason when it cannot (D-35 / F-32).
 * - [ndefMessageSizeBytes] / [ndefFitsOnCard]: the capacity check
 *   before writing (D-32's `ndefMaxSize`) — "too big for this card".
 * - [nextApduCheck]: the "what to try next" suggestion, shared by
 *   the APDU console panel and the `nfc.try-next-command` operation
 *   (same pattern Package 2 used for A-22).
 * - [classicTrailerBlock] / [ultralightPageWarning]: the guards that
 *   stop a write from locking a sector or breaking a card (F-49).
 * - [emulatableFromSummary]: what a just-read card can offer the
 *   "pretend to be this card" path (B-043 / D-40).
 * - [deepReadChoice]: which deep read fits this card type (D-32–D-34).
 *
 * Everything here is pure except [classicTrailerBlock], which reads
 * only what Android reports about the tapped tag. Nothing here opens
 * a connection: connect / transceive / disconnect still live in
 * `GhostTapViewModel` (one connected session per operation), and LAST
 * RESULT reporting still flows through the NFC TOOLS screen's `say()`.
 */

/** The three quick smart-card checks, shared by panel and operation. */
val APDU_QUICK_CHECKS: List<Pair<String, String>> = listOf(
    "Pick stored data" to "00 A4 04 00 07 D2 76 00 00 85 01 01 00",
    "Read data" to "00 B0 00 00 00",
    "Get info" to "00 CA 00 00 00"
)

/**
 * First quick check whose command is not in [triedCommandHexes] yet
 * (hex compared without spaces, upper-cased — same normalisation the
 * APDU console panel uses). Null when all three have been tried.
 */
fun nextApduCheck(triedCommandHexes: Set<String>): Pair<String, String>? {
    val tried = triedCommandHexes.map { it.replace(" ", "").uppercase() }.toSet()
    return APDU_QUICK_CHECKS.firstOrNull { it.second.replace(" ", "") !in tried }
}

/** Whether one of the 10 NFC panels can honestly run on this card. */
data class NfcPanelGate(
    /** True when the panel's action can run on this card. */
    val usable: Boolean,
    /** Plain-words reason when it cannot, null when it can. */
    val reason: String? = null
)

private fun usable() = NfcPanelGate(true)

private fun blocked(reason: String) = NfcPanelGate(false, reason)

/**
 * Gates one of the 10 NFC TOOLS panels (1–10) against the tapped
 * card's facts. A null [facts] means no card has been tapped yet:
 * panels that need a card are blocked with "tap a card first", while
 * the read panel and the pretend-to-be-a-tag panel stay usable (the
 * read panel IS the tap, and pretending needs no card). [hasSavedNdef]
 * is whether a copied card is waiting to be written.
 */
fun gateForPanel(panel: Int, facts: NfcFacts?, hasSavedNdef: Boolean): NfcPanelGate {
    val ops = facts?.let { nfcSupportedOps(it) } ?: emptyList()
    return when (panel) {
        // 1. READ A TAG OR CARD — always: the tap itself is the read.
        1 -> usable()
        // 2. COPY TAG — needs a card with something saved on it to copy.
        2 -> when {
            facts == null -> blocked("Tap a card first — hold a card against the phone.")
            !facts.hasNdef -> blocked("Nothing saved on this card to copy.")
            else -> usable()
        }
        // 3. WRITE TEXT TO TAG / 4. WRITE LINK TO TAG — need writable NDEF.
        3, 4 -> when {
            facts == null -> blocked("Tap a card first — hold a card against the phone.")
            "WRITE TEXT" !in ops -> blocked("This card can't be written to — try a blank card.")
            else -> usable()
        }
        // 5. WRITE SAVED — CLONE A TAG — needs a saved copy first.
        5 -> when {
            !hasSavedNdef -> blocked("Nothing copied yet — use Copy a card first.")
            facts == null -> blocked("Tap the second card — hold it against the phone.")
            "WRITE TEXT" !in ops && !facts.canFormat ->
                blocked("This card can't be written to — try a different blank card.")
            else -> usable()
        }
        // 6. ERASE TAG — needs writable NDEF (or a blank-formattable card).
        6 -> when {
            facts == null -> blocked("Tap a card first — hold a card against the phone.")
            "ERASE" !in ops && !facts.canFormat ->
                blocked("This card can't be wiped — it isn't a writable card.")
            else -> usable()
        }
        // 7. PRETEND TO BE A TAG — needs no card: the phone IS the card.
        7 -> usable()
        // 8. RAW TAG CONSOLE — needs a card in range, any kind.
        8 -> when {
            facts == null -> blocked("Tap a card first — hold a card against the phone.")
            else -> usable()
        }
        // 9. MIFARE CLASSIC TOOLS — needs a Mifare Classic card.
        9 -> when {
            facts == null -> blocked("Tap a card first — hold a card against the phone.")
            facts.mifareClassicSectors == null ->
                blocked("Not a Mifare Classic card — these tools only work on door and access cards.")
            else -> usable()
        }
        // 10. TAG MEMORY WRITE — ULTRALIGHT / NTAG — needs an Ultralight card.
        10 -> when {
            facts == null -> blocked("Tap a card first — hold a card against the phone.")
            facts.mifareUltralightType == null ->
                blocked("Not an Ultralight card — this only writes to Ultralight and NTAG cards.")
            else -> usable()
        }
        else -> usable()
    }
}

/**
 * Size in bytes of the NDEF message holding one text or URL record,
 * as the phone would write it: a short record is 4 header bytes plus
 * the payload. Text payload is 1 status byte + 2 language bytes
 * ("en") + the words in UTF-8; URL payload is 1 prefix byte + the
 * link in UTF-8 (a bare link gets "https://" added, as the writer does).
 */
fun ndefMessageSizeBytes(text: String, isUrl: Boolean): Int {
    val clean = text.trim()
    val body = if (isUrl) {
        val full = if (clean.startsWith("http://") || clean.startsWith("https://")) clean
        else "https://$clean"
        full.toByteArray(Charsets.UTF_8).size + 1
    } else {
        clean.toByteArray(Charsets.UTF_8).size + 3
    }
    return 4 + body
}

/**
 * Capacity check before writing (D-32's `ndefMaxSize`).
 * Returns null when the words/link fit, or when the card's capacity
 * is unknown (the write itself then decides honestly). Otherwise the
 * plain-words "too big for this card" reason, with both numbers.
 */
fun ndefFitsOnCard(facts: NfcFacts?, text: String, isUrl: Boolean): String? {
    val max = facts?.ndefMaxSize ?: return null
    val need = ndefMessageSizeBytes(text, isUrl)
    if (need <= max) return null
    val what = if (isUrl) "link" else "words"
    return "Too big for this card — your $what need $need bytes but this card only holds $max. " +
        "Shorten it and try again."
}

/**
 * Which deep read fits this card type (D-32–D-34): the memory dump for
 * Ultralight/NTAG cards, the block read for long-range (NfcV) cards,
 * and the plain re-read for everything else. The chip line only ever
 * names a family, never a precise model.
 */
fun deepReadChoice(facts: NfcFacts?): String = when {
    facts == null -> "read again"
    facts.mifareUltralightType != null -> "read all memory"
    facts.techs.any { it.substringAfterLast('.') == "NfcV" } -> "read card blocks"
    facts.isIsoDep -> "read again — then try the card commands below"
    else -> "read again"
}

/**
 * True when [block] is the last block of its sector — the block that
 * holds the sector's passwords. Writing there can lock the sector.
 * Null when the tag is not a Mifare Classic card or the block is not
 * valid on this card.
 */
fun classicTrailerBlock(tag: Tag, block: Int): Boolean? {
    val mfc = MifareClassic.get(tag) ?: return null
    return runCatching {
        val sector = mfc.blockToSector(block)
        block == mfc.sectorToBlock(sector) + mfc.getBlockCountInSector(sector) - 1
    }.getOrNull()
}

/** Pages 0–3 of an Ultralight/NTAG card hold its ID and lock settings. */
val ULTRALIGHT_ID_PAGES: IntRange = 0..3

/**
 * Plain-words warning for writing to one Ultralight page, or null
 * when the page is in the ordinary writable area (page 4 and up).
 * Pages 0–3 hold the card's ID and lock settings — writing there can
 * break the card, so the panel asks before it writes.
 */
fun ultralightPageWarning(page: Int): String? =
    if (page in ULTRALIGHT_ID_PAGES) {
        "Page $page holds the card's ID and lock settings — writing there can break the card. " +
            "Pages 4 and up are the safe place for your own data."
    } else {
        null
    }

/**
 * What a just-read card offers the "pretend to be this card" path:
 * the first saved record, as ("text", words) or ("url", link).
 * Reads only the plain-words summary the ViewModel already shows —
 * the saved message itself stays in the ViewModel. Null when the
 * summary holds no words or link to pretend to be.
 */
fun emulatableFromSummary(summary: String): Pair<String, String>? {
    summary.lines().forEach { line ->
        // A raw string cannot end in a quote, so the text pattern is a
        // regular escaped string: Text: "hello".
        val text = Regex("Text: \"([^\"]*)\"").find(line)?.groupValues?.get(1)
        if (!text.isNullOrBlank()) return "text" to text
        val url = Regex("""URL: (\S+)""").find(line)?.groupValues?.get(1)
        if (!url.isNullOrBlank()) return "url" to url
        val link = Regex("""Link: (\S+)""").find(line)?.groupValues?.get(1)
        if (!link.isNullOrBlank()) return "url" to link
    }
    return null
}

/**
 * Plain-words next step when the card type came back UNKNOWN (D-33):
 * honest about not knowing, with a concrete thing to try instead of
 * a dead end.
 */
fun unknownCardNextStep(likelyType: String): String? =
    if (likelyType == "Unknown NFC target") {
        "Not sure what kind of card this is — try the raw console (card 8) " +
            "or the Mifare tools (card 9) to find out more."
    } else {
        null
    }

// ---------------------------------------------------------------------------
// Autonomous Investigations — NFC trigger (additive; reads only).
//
// WIRING: the coordinator adds ONE line to GhostTapViewModel.handleNfcTag,
// right after lastNfcReadSummary is built (before the event-bus publish):
//     investigateNfcTagRead(facts, ndefSummary)
// This file cannot call it itself — handleNfcTag owns the read path and
// lives outside this worker's files. Everything below is pure trigger
// logic: no UI, no connection, no write, no emulation.
//
// What it does, on every successful summary read in the existing read
// path (the tag tap in handleNfcTag):
// - Runs Investigator.investigate with InvestigationSource.NFC using
//   ONLY what was actually read: the tag technologies as Android
//   reported them (raw class names, e.g. android.nfc.tech.IsoDep —
//   the framework turns them into plain words), the tag family derived
//   from those technologies, and the NDEF record lines actually read.
//   Nothing is invented: no guessed content, no guessed identity.
// - answeredHandshake is true ONLY when the summary read returned real
//   tag content (a non-blank NDEF summary). A tag with nothing saved
//   on it is still investigated (its technologies are real evidence),
//   but honestly reported as not having answered with content.
// - UNKNOWN stays UNKNOWN: when the tag family came back
//   "Unknown NFC target", rawName is null, so the framework reports
//   believedIdentity "UNKNOWN" with LOW confidence instead of naming it.
// - Investigates ONCE per tag session: the same tag ID with the same
//   content is never re-investigated. A new tag, or new content on the
//   same tag (e.g. after a rewrite), triggers a fresh investigation.
// - The report goes to InvestigationLedger; the NFC screen reads the
//   latest NFC report from there (see NfcInvestigationPanel).
// - Failures inside the framework never break the read path: any
//   exception is swallowed and null is returned.
// ---------------------------------------------------------------------------

/** Tag ID + content of the last investigation, for once-per-session dedup. */
private var lastInvestigatedTagUid: String? = null
private var lastInvestigatedContent: String? = null

/**
 * Investigates one just-read tag. Returns the report, or null when
 * this exact tag + content was already investigated or the framework
 * failed. Read-only: never writes to the tag, never emulates anything.
 * [targetId] is the tag's stable id ("nfc:<uid>") when the caller knows
 * it, so a re-check refreshes the tag's report in place instead of
 * duplicating it; null keeps the old behaviour (filed with no id).
 */
fun investigateNfcTagRead(facts: NfcFacts, ndefSummary: String, targetId: String? = null): FindingsReport? {
    val uid = facts.uidHex
    val content = ndefSummary.trim()
    if (uid == lastInvestigatedTagUid && content == lastInvestigatedContent) return null
    lastInvestigatedTagUid = uid
    lastInvestigatedContent = content

    val details = mutableMapOf(
        "uid" to uid,
        // Raw tech class names exactly as Android reported them.
        "technologies" to facts.techs.joinToString(", "),
        "tag_type" to facts.likelyType,
        "writable" to facts.ndefWritable.toString()
    )
    facts.ndefType?.let { details["ndef_type"] = it }
    facts.capacityLine?.let { details["capacity"] = it }
    facts.chipLine?.let { details["chip"] = it }
    facts.mifareUltralightType?.let { details["mifare_type"] = it }
    facts.isoDepHistorical?.let { details["historical_bytes"] = it }
    facts.isoDepHiLayer?.let { details["hi_layer_response"] = it }

    // Only the record lines actually read — never invented content.
    val readable = if (content.isBlank()) emptyList()
    else content.lines().filter { it.isNotBlank() }

    val input = InvestigationInput(
        source = InvestigationSource.NFC,
        // Null when the family is unknown, so UNKNOWN stays UNKNOWN.
        rawName = facts.likelyType.takeIf { it != "Unknown NFC target" },
        rawDetails = details,
        readableValues = readable,
        // True only on a real read: content actually came back.
        answeredHandshake = readable.isNotEmpty(),
        // NFC taps carry no signal reading — null, never invented.
        signalText = null,
        targetId = targetId
    )
    return runCatching {
        val report = Investigator.investigate(input)
        InvestigationLedger.upsert(report)
        LiveTargetStore.publish(report, InvestigationSource.NFC)
        report
    }.getOrNull()
}
