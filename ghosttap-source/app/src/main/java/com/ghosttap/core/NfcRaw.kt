package com.ghosttap.core

/**
 * Pure NFC protocol helpers — no Android dependencies.
 *
 * Byte framing for ISO 15693, command-script parsing, NTAG password auth
 * and gen1a magic-card backdoor sequences. Kept free of android.* imports
 * so the logic stays unit-testable.
 */

/**
 * Parse a command script: one hex command per line.
 * Blank lines and lines starting with # are skipped.
 * Throws IllegalArgumentException naming the bad line.
 */
fun parseHexScript(text: String): List<ByteArray> {
    val out = mutableListOf<ByteArray>()
    text.lines().forEachIndexed { i, raw ->
        val t = raw.trim()
        if (t.isEmpty() || t.startsWith("#")) return@forEachIndexed
        out += t.hexToBytes()
            ?: throw IllegalArgumentException("Line ${i + 1}: not valid hex")
    }
    return out
}

/**
 * ISO 15693 READ SINGLE BLOCK (0x20), addressed mode.
 * uid = 8 bytes exactly as Android reports them (LSB first).
 */
fun buildNfcVReadSingleBlock(uid: ByteArray, block: Int): ByteArray {
    require(uid.size == 8) { "UID must be 8 bytes" }
    require(block in 0..255) { "Block must be 0..255" }
    return byteArrayOf(0x22.toByte(), 0x20.toByte()) + uid + byteArrayOf(block.toByte())
}

/**
 * Split an ISO 15693 read response into Pair(ok, payload).
 * ok=false means the tag answered with an error (payload = error bytes).
 */
fun parseNfcVResponse(resp: ByteArray): Pair<Boolean, ByteArray> {
    if (resp.isEmpty()) return false to resp
    val ok = (resp[0].toInt() and 0x01) == 0
    return ok to resp.copyOfRange(1, resp.size)
}

/** NTAG PWD_AUTH command (0x1B) + 4-byte password. A 2-byte PACK answer = accepted. */
fun buildNtagPwdAuth(password: ByteArray): ByteArray {
    require(password.size == 4) { "Password must be 4 bytes" }
    return byteArrayOf(0x1B.toByte()) + password
}

/** Gen1a magic-card backdoor unlock sequence (50 00 57 CD). */
fun buildMagicUnlock(): ByteArray =
    byteArrayOf(0x50.toByte(), 0x00.toByte(), 0x57.toByte(), 0xCD.toByte())

/** Gen1a magic-card write-block-0 prefix (A0 00); caller sends the 16 data bytes after ACK. */
fun buildMagicWriteBlock0Prefix(): ByteArray =
    byteArrayOf(0xA0.toByte(), 0x00.toByte())

/** True when the response is the single ACK byte (0x0A) — the magic card's "yes". */
fun isMagicAck(resp: ByteArray): Boolean =
    resp.size == 1 && (resp[0].toInt() and 0xFF) == 0x0A
