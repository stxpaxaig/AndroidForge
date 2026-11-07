package com.ghosttap.core

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF

/**
 * Plain-English interpretation of an NFC tag's technologies.
 * Everything here comes from what Android reports about the tag —
 * never guessed, never simulated.
 */

/** Friendly name for an Android NFC tech class name. */
fun friendlyTechName(className: String): String = when (className.substringAfterLast('.')) {
    "NfcA" -> "Tap card (type A)"
    "NfcB" -> "Tap card (type B)"
    "NfcF" -> "Tap card (FeliCa)"
    "NfcV" -> "Tap card (long-range)"
    "IsoDep" -> "Smart card"
    "Ndef" -> "Tap-to-read notes"
    "NdefFormatable" -> "Blank card (can hold notes)"
    "MifareClassic" -> "Mifare Classic"
    "MifareUltralight" -> "Mifare Ultralight card"
    else -> className.substringAfterLast('.')
}

/** Best-effort plain-English tag family from the reported technologies. */
fun likelyNfcType(techs: List<String>): String {
    val short = techs.map { it.substringAfterLast('.') }.toSet()
    return when {
        "MifareClassic" in short -> "Mifare Classic card"
        "MifareUltralight" in short -> "Mifare Ultralight tag"
        "IsoDep" in short -> "Smart card (ISO-DEP)"
        "NfcF" in short -> "FeliCa tag"
        "NfcV" in short -> "Vicinity tag (ISO 15693)"
        "Ndef" in short -> "NDEF data tag"
        "NdefFormatable" in short -> "Blank tag (can hold NDEF)"
        "NfcA" in short -> "NFC-A tag"
        "NfcB" in short -> "NFC-B tag"
        else -> "Unknown NFC target"
    }
}

/** Read-only facts Android exposes about a tag — no connection required. */
data class NfcFacts(
    val uidHex: String,
    val techs: List<String>,
    val likelyType: String,
    val ndefType: String?,
    val ndefMaxSize: Int?,
    val ndefWritable: Boolean,
    val hasNdef: Boolean,
    val canFormat: Boolean,
    val isIsoDep: Boolean,
    val isoDepHistorical: String?,
    val isoDepHiLayer: String?,
    val mifareClassicSize: Int?,
    val mifareClassicSectors: Int?,
    val mifareUltralightType: String?,
    /** Plain-English memory/capacity line, or null when unknown. */
    val capacityLine: String?,
    /** Plain-English chip identification from ATQA/SAK etc, or null when unavailable. */
    val chipLine: String?
)

/** Gather every fact Android offers about this tag without connecting. */
fun readNfcFacts(tag: Tag): NfcFacts {
    val techs = tag.techList.toList()
    val short = techs.map { it.substringAfterLast('.') }.toSet()
    val ndef = Ndef.get(tag)
    val writable = runCatching { ndef?.isWritable == true }.getOrDefault(false)

    var mcSize: Int? = null
    var mcSectors: Int? = null
    if ("MifareClassic" in short) {
        val mc = MifareClassic.get(tag)
        mcSize = runCatching { mc?.size }.getOrNull()
        mcSectors = runCatching { mc?.sectorCount }.getOrNull()
    }
    var muType: String? = null
    if ("MifareUltralight" in short) {
        muType = when (runCatching { MifareUltralight.get(tag)?.type }.getOrNull()) {
            MifareUltralight.TYPE_ULTRALIGHT -> "Ultralight (64 bytes)"
            MifareUltralight.TYPE_ULTRALIGHT_C -> "Ultralight C (192 bytes)"
            else -> "Ultralight (unknown variant)"
        }
    }
    var hist: String? = null
    var hiLayer: String? = null
    if ("IsoDep" in short) {
        val iso = IsoDep.get(tag)
        hist = runCatching { iso?.historicalBytes?.toHexString() }.getOrNull()?.takeIf { it.isNotBlank() }
        hiLayer = runCatching { iso?.hiLayerResponse?.toHexString() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    val capacityLine: String? = when {
        ndef?.maxSize != null -> "Can hold: ${ndef.maxSize} bytes"
        mcSize != null -> "Card memory: $mcSize bytes in $mcSectors sections"
        muType != null -> "Card type: $muType"
        else -> null
    }

    return NfcFacts(
        uidHex = tag.id.toHexString(),
        techs = techs,
        likelyType = likelyNfcType(techs),
        ndefType = ndef?.type,
        ndefMaxSize = ndef?.maxSize,
        ndefWritable = writable,
        hasNdef = ndef != null,
        canFormat = "NdefFormatable" in short,
        isIsoDep = "IsoDep" in short,
        isoDepHistorical = hist,
        isoDepHiLayer = hiLayer,
        mifareClassicSize = mcSize,
        mifareClassicSectors = mcSectors,
        mifareUltralightType = muType,
        capacityLine = capacityLine,
        chipLine = decodeChipLine(tag)
    )
}

/**
 * Plain-English chip identification from the low-level RF answers
 * (ATQA/SAK for NFC-A, app data for NFC-B, maker info for FeliCa).
 * Uses only public chip assignments — never guessed.
 */
fun decodeChipLine(tag: Tag): String? {
    val short = tag.techList.map { it.substringAfterLast('.') }.toSet()
    if ("NfcA" in short) {
        val a = NfcA.get(tag) ?: return null
        val atqa = runCatching { a.atqa?.toHexString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"
        val sak = runCatching { a.sak.toInt() and 0xFF }.getOrNull()
            ?: return "NFC-A tag (ATQA $atqa)"
        val family = when (sak) {
            0x00 -> "MIFARE Ultralight / NTAG family"
            0x08 -> "MIFARE Classic 1K"
            0x09 -> "MIFARE Classic Mini"
            0x18 -> "MIFARE Classic 4K"
            0x20 -> "MIFARE DESFire / Plus"
            0x28 -> "JCOP smartcard"
            else -> "Unlisted NFC-A chip"
        }
        return "Chip: $family (ATQA $atqa, SAK %02X)".format(sak)
    }
    if ("NfcB" in short) {
        val b = NfcB.get(tag) ?: return null
        val app = runCatching { b.applicationData?.toHexString() }.getOrNull()?.takeIf { it.isNotBlank() }
        val proto = runCatching { b.protocolInfo?.toHexString() }.getOrNull()?.takeIf { it.isNotBlank() }
        return "NFC-B tag" + (app?.let { " (app data $it)" } ?: "") + (proto?.let { " (protocol $it)" } ?: "")
    }
    if ("NfcF" in short) {
        val f = NfcF.get(tag) ?: return null
        val maker = runCatching { f.manufacturer?.toHexString() }.getOrNull()?.takeIf { it.isNotBlank() }
        val sys = runCatching { f.systemCode?.toHexString() }.getOrNull()?.takeIf { it.isNotBlank() }
        return "FeliCa tag" + (maker?.let { " (maker $it)" } ?: "") + (sys?.let { " (system $it)" } ?: "")
    }
    return null
}

/** Which operations Ghost Tap can honestly offer for these facts. */
fun nfcSupportedOps(facts: NfcFacts): List<String> {
    val ops = mutableListOf("READ")
    if (facts.hasNdef && facts.ndefWritable) {
        ops += listOf("WRITE TEXT", "WRITE URL", "COPY CONTENT", "ERASE")
    }
    if (facts.canFormat) ops += "FORMAT"
    if (facts.isIsoDep) ops += "APDU CONSOLE"
    return ops
}
