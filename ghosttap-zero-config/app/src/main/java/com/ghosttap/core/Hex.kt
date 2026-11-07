package com.ghosttap.core

fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

/**
 * Parse hex pairs ("00 A4 04 00" or "00A40400") into bytes.
 * Returns null — never throws — when the text is not valid hex
 * (odd length, or a pair that is not two hex digits), so every
 * caller reports it in plain words instead of crashing. An empty
 * input parses to an empty array, exactly as before.
 */
fun String.hexToBytes(): ByteArray? {
    val cleaned = replace(" ", "").replace("\n", "")
    if (cleaned.length % 2 != 0) return null
    val out = ByteArray(cleaned.length / 2)
    for (i in out.indices) {
        val byte = cleaned.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
        out[i] = byte.toByte()
    }
    return out
}
