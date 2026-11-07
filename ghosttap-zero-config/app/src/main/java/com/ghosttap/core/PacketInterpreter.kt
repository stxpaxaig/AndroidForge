package com.ghosttap.core

/**
 * Plain-English packet interpreter.
 *
 * Every analysis is honest about its evidence level: interpretations are
 * labeled LOW / MEDIUM / HIGH, and when there is not enough evidence the
 * result is UNKNOWN plus a note about what observation would help.
 * Nothing is ever presented as known without evidence.
 */
object PacketInterpreter {

    data class Analysis(
        val hex: String,
        val ascii: String?,
        val decimals: String,
        val length: Int,
        val interpretation: String,
        /** LOW / MEDIUM / HIGH */
        val confidence: String,
        /** 0-based indexes of bytes that differ from the previous packet. */
        val changedBytes: List<Int>,
        val prevHex: String?,
        /** Set when interpretation is UNKNOWN. */
        val whatWouldHelp: String?
    )

    fun analyze(bytes: ByteArray, prev: ByteArray?): Analysis {
        val hex = bytes.toHexString()
        val prevHex = prev?.toHexString()
        val decimals = bytes.joinToString(" ") { (it.toInt() and 0xFF).toString() }
        val ascii = bytes.toAsciiOrNull()

        if (bytes.isEmpty()) {
            return Analysis(hex, null, "", 0, "Empty packet", "HIGH", emptyList(), prevHex, null)
        }

        if (prev != null && prev.contentEquals(bytes)) {
            return Analysis(
                hex, ascii, decimals, bytes.size,
                "Repeated packet — possibly heartbeat/status traffic", "MEDIUM",
                emptyList(), prevHex, null
            )
        }

        if (ascii != null) {
            return Analysis(
                hex, ascii, decimals, bytes.size,
                "Text/string data: \"$ascii\"", "HIGH",
                changedBytesVs(prev, bytes), prevHex, null
            )
        }

        if (prev == null || prev.size != bytes.size) {
            return Analysis(
                hex, ascii, decimals, bytes.size,
                "First sighting of this packet — watching for changes", "LOW",
                emptyList(), prevHex,
                "Perform an action on the device (press a button, change a setting) and watch which bytes change."
            )
        }

        val changed = changedBytesVs(prev, bytes)
        if (changed.isEmpty()) {
            // Same length, same content handled above; defensive fallback.
            return Analysis(hex, ascii, decimals, bytes.size, "Unknown data", "LOW",
                emptyList(), prevHex, "No byte-level change detected versus the previous packet.")
        }

        if (changed.size == 1) {
            val i = changed[0]
            val oldV = "%02X".format(prev[i])
            val newV = "%02X".format(bytes[i])
            val oldI = prev[i].toInt() and 0xFF
            val newI = bytes[i].toInt() and 0xFF
            // Single byte toggling between 00 and 01.
            if (bytes.size >= 1 && ((oldI == 0 && newI == 1) || (oldI == 1 && newI == 0))) {
                return Analysis(hex, ascii, decimals, bytes.size,
                    "Possible state ON/OFF value — byte $i: $oldV → $newV", "LOW",
                    changed, prevHex,
                    "Toggle the same action again. If byte $i flips back, confidence rises.")
            }
            // Single byte incrementing.
            if (newI == oldI + 1) {
                return Analysis(hex, ascii, decimals, bytes.size,
                    "Counter increased — byte $i: $oldV → $newV", "MEDIUM",
                    changed, prevHex, null)
            }
            return Analysis(hex, ascii, decimals, bytes.size,
                "Status value changed — byte $i: $oldV → $newV. Possible button event or sensor reading.", "LOW",
                changed, prevHex,
                "Repeat the action you just performed. If byte $i changes the same way again, it is likely linked to that action.")
        }

        val desc = changed.joinToString(", ") { i ->
            "byte $i: %02X → %02X".format(prev[i], bytes[i])
        }
        return Analysis(hex, ascii, decimals, bytes.size,
            "Multiple bytes changed ($desc). Possible sensor reading or multi-field status.", "LOW",
            changed, prevHex,
            "Perform one action at a time and watch which bytes move together.")
    }

    private fun changedBytesVs(prev: ByteArray?, bytes: ByteArray): List<Int> {
        if (prev == null || prev.size != bytes.size) return emptyList()
        return bytes.indices.filter { prev[it] != bytes[it] }
    }

    private fun ByteArray.toAsciiOrNull(): String? {
        if (size < 2) return null
        if (!all { it.toInt() and 0xFF in 32..126 }) return null
        return String(this, Charsets.US_ASCII)
    }
}
