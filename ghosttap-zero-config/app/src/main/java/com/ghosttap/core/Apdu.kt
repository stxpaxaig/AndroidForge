package com.ghosttap.core

/**
 * APDU (smart-card command) helpers for the NFC IsoDep console.
 * Interprets what Android legitimately exposes — never fabricates responses,
 * never attempts to defeat authentication or access control.
 */

/** Result of one IsoDep transceive, with a plain-English status reading. */
data class ApduResult(
    /** The command that was sent, e.g. "00 A4 04 00". */
    val commandHex: String,
    /** Full response bytes as hex, e.g. "6F 10 ... 90 00". */
    val responseHex: String,
    /** Data bytes only (everything except the trailing SW1/SW2), or "" if none. */
    val dataHex: String,
    val sw1: Int,
    val sw2: Int,
    /** e.g. "90 00". */
    val statusHex: String,
    /** Plain-English reading, e.g. "SUCCESS — command completed." */
    val statusMeaning: String,
    /** ASCII rendering of the data bytes, or null when not printable. */
    val ascii: String?
)

/** Decode the two status bytes every smart card returns. */
fun decodeApduStatus(sw1: Int, sw2: Int): String = when (sw1) {
    0x90 -> if (sw2 == 0x00) "SUCCESS — command completed."
    else "SUCCESS with extra info (%02X).".format(sw2)
    0x61 -> "SUCCESS — %d more response bytes available (send GET RESPONSE).".format(sw2)
    0x62 -> when (sw2) {
        0x81 -> "WARNING — returned data may be corrupted."
        0x82 -> "WARNING — end of file reached before reading all bytes."
        0x83 -> "WARNING — selected file is deactivated."
        else -> "WARNING — state of memory unchanged (%02X %02X).".format(sw1, sw2)
    }
    0x63 -> if (sw2 and 0xF0 == 0xC0) "WARNING — counter at %d, PIN/PUK may be needed.".format(sw2 and 0x0F)
    else "WARNING — verification failed (%02X %02X).".format(sw1, sw2)
    0x64, 0x65 -> "ERROR — memory or card problem (%02X %02X).".format(sw1, sw2)
    0x67 -> "ERROR — wrong length (expected %d bytes).".format(sw2)
    0x68 -> when (sw2) {
        0x81 -> "ERROR — logical channel not supported."
        0x82 -> "ERROR — secure messaging not supported."
        else -> "ERROR — function not supported in this mode (%02X).".format(sw2)
    }
    0x69 -> when (sw2) {
        0x81 -> "ERROR — file structure incompatible with command."
        0x82 -> "ERROR — security status not satisfied (AUTHENTICATION REQUIRED)."
        0x83 -> "ERROR — selected file is locked."
        0x85 -> "ERROR — conditions of use not satisfied."
        else -> "ERROR — command not allowed (%02X %02X).".format(sw1, sw2)
    }
    0x6A -> when (sw2) {
        0x80 -> "ERROR — wrong data in the command."
        0x81 -> "ERROR — function not supported."
        0x82 -> "ERROR — file or application not found."
        0x84 -> "ERROR — not enough memory on the card."
        0x86 -> "ERROR — wrong parameters P1/P2."
        else -> "ERROR — wrong parameters (%02X %02X).".format(sw1, sw2)
    }
    0x6B -> "ERROR — wrong parameters P1/P2."
    0x6D -> "ERROR — instruction (INS) not supported by this card."
    0x6E -> "ERROR — class (CLA) not supported by this card."
    0x6F -> "ERROR — no precise diagnosis (card reported a problem)."
    else -> "Unknown status %02X %02X — the card answered, but Ghost Tap has no translation for this code.".format(
        sw1,
        sw2
    )
}

/** Split a raw APDU command into its fields with plain-English labels. */
fun describeApduCommand(hex: String): List<String> {
    val bytes = hex.hexToBytes()
        ?: return listOf("That doesn't look like valid hex — use pairs like 00 A4 04 00.")
    if (bytes.size < 4) return listOf("An APDU needs at least 4 bytes: CLA INS P1 P2.")
    val out = mutableListOf(
        "CLA %02X — command group".format(bytes[0]),
        "INS %02X — %s".format(bytes[1], insName(bytes[1].toInt() and 0xFF)),
        "P1 %02X, P2 %02X — parameters".format(bytes[2], bytes[3])
    )
    var i = 4
    if (i < bytes.size) {
        val lc = bytes[i].toInt() and 0xFF
        out += "Lc $lc — sending $lc data byte(s)"
        i++
        if (i + lc <= bytes.size) {
            out += "Data: ${bytes.copyOfRange(i, i + lc).toHexString()}"
            i += lc
        }
    }
    if (i < bytes.size) {
        out += "Le ${bytes[i].toInt() and 0xFF} — expecting that many response bytes back"
    }
    return out
}

private fun insName(ins: Int): String = when (ins) {
    0xA4 -> "SELECT (choose an application/file)"
    0xB0 -> "READ BINARY"
    0xB2 -> "READ RECORD"
    0xD6 -> "UPDATE BINARY (write)"
    0xDC -> "UPDATE RECORD (write)"
    0x20 -> "VERIFY (PIN / authentication)"
    0x82 -> "EXTERNAL AUTHENTICATE"
    0x84 -> "GET CHALLENGE"
    0x88 -> "INTERNAL AUTHENTICATE"
    0xC0 -> "GET RESPONSE"
    0xCA -> "GET DATA"
    0xE0 -> "CREATE FILE"
    0xE2 -> "APPEND RECORD"
    else -> "instruction"
}

/** Build a SELECT-by-AID command (hex string) for a user-supplied application ID. */
fun buildSelectAid(aidHex: String): String? {
    val aid = aidHex.hexToBytes() ?: return null
    if (aid.isEmpty() || aid.size > 16) return null
    val lc = "%02X".format(aid.size)
    return "00 A4 04 00 $lc ${aid.toHexString()}"
}

/** Printable ASCII rendering of bytes, or null when nothing printable. */
fun bytesToAsciiOrNull(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    val s = bytes.map { b ->
        val v = b.toInt() and 0xFF
        if (v in 32..126) v.toChar() else '.'
    }.joinToString("")
    return if (s.any { it != '.' }) s else null
}
