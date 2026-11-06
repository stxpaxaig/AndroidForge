package com.ghosttap

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * NFC card emulation — the phone pretends to be an NFC tag.
 * Emulates an NFC Forum Type 4 Tag holding one NDEF message
 * (set from the NFC TOOLS screen). Tap this phone to another
 * phone or an NFC reader and it reads the message like a real tag.
 * The emulated NDEF file also answers UPDATE BINARY, so a reader
 * can write it like a real writable tag.
 *
 * AID D2760000850101 = the standard "NDEF Tag Application".
 * This is read-only emulation of YOUR OWN content — it cannot
 * copy a bank card or defeat any authentication.
 */
class NfcEmulationService : HostApduService() {

    companion object {
        /** What the emulated tag broadcasts. Set from the NFC TOOLS screen. */
        @Volatile
        var emulatedMessage: NdefMessage =
            NdefMessage(arrayOf(NdefRecord.createTextRecord("en", "GHOST TAP")))

        @Volatile
        var emulatedSummary: String = "Text: \"GHOST TAP\""

        fun setEmulatedText(text: String) {
            val t = text.ifBlank { "GHOST TAP" }
            emulatedMessage = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", t)))
            emulatedSummary = "Text: \"$t\""
            ndefFileOverride = null
        }

        fun setEmulatedUrl(url: String) {
            val clean = url.trim().ifBlank { "https://example.com" }
            val full = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "https://$clean"
            emulatedMessage = NdefMessage(arrayOf(NdefRecord.createUri(full)))
            emulatedSummary = "URL: $full"
            ndefFileOverride = null
        }

        /**
         * Raw NDEF-file bytes written by a reader via UPDATE BINARY. When set,
         * the emulated tag serves these instead of the generated message —
         * the emulated tag is writable, like a real one.
         */
        @Volatile
        var ndefFileOverride: ByteArray? = null

        /**
         * Package 4 (B-043) — how many readers have actually tapped
         * this phone while it pretends to be a card. Counts a tap when
         * a reader selects the NDEF app (the first thing a real reader
         * does). Lets the panel show whether anyone has read the phone
         * yet, instead of only showing that pretending is on.
         */
        @Volatile
        var readerTapCount: Int = 0
    }

    // NDEF Tag Application AID.
    private val ndefAid = hex("D2760000850101")
    // Capability Container file (standard 15-byte CC for a Type 4 tag).
    private val ccFile = hex("000F20003B00340406E1040E000000")
    private var selectedFile = 0 // 0 = none, 0xE103 = CC, 0xE104 = NDEF

    private fun ndefFile(): ByteArray {
        ndefFileOverride?.let { return it }
        val msg = emulatedMessage.toByteArray()
        val out = ByteArray(2 + msg.size)
        out[0] = ((msg.size shr 8) and 0xFF).toByte()
        out[1] = (msg.size and 0xFF).toByte()
        msg.copyInto(out, 2)
        return out
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (commandApdu.size < 4) return sw(0x6F00)
        val cla = commandApdu[0].toInt() and 0xFF
        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        // SELECT
        if (cla == 0x00 && ins == 0xA4) {
            // SELECT by AID (P1=04): NDEF Tag Application.
            if (p1 == 0x04 && commandApdu.size >= 5) {
                val lc = commandApdu[4].toInt() and 0xFF
                if (commandApdu.size >= 5 + lc) {
                    val aid = commandApdu.sliceArray(5 until 5 + lc)
                    if (aid.contentEquals(ndefAid)) {
                        selectedFile = 0
                        // A reader just tapped this phone to read it.
                        readerTapCount++
                        return sw(0x9000)
                    }
                }
                return sw(0x6A82) // file/AID not found
            }
            // SELECT by file ID (P1=00, P2=0C).
            if (p1 == 0x00 && p2 == 0x0C && commandApdu.size >= 7) {
                val fid = ((commandApdu[5].toInt() and 0xFF) shl 8) or (commandApdu[6].toInt() and 0xFF)
                return if (fid == 0xE103 || fid == 0xE104) {
                    selectedFile = fid
                    sw(0x9000)
                } else sw(0x6A82)
            }
            return sw(0x6A86)
        }

        // READ BINARY
        if (cla == 0x00 && ins == 0xB0 && commandApdu.size >= 5) {
            val offset = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)
            val le = commandApdu[4].toInt() and 0xFF
            val file = when (selectedFile) {
                0xE103 -> ccFile
                0xE104 -> ndefFile()
                else -> return sw(0x6986) // no file selected
            }
            if (offset > file.size) return sw(0x6B00)
            val count = minOf(if (le == 0) 256 else le, file.size - offset)
            return file.sliceArray(offset until offset + count) + sw(0x9000)
        }

        // UPDATE BINARY — lets a reader write the emulated NDEF file,
        // making the emulated tag writable like a real one.
        if (cla == 0x00 && ins == 0xD6 && commandApdu.size >= 5) {
            if (selectedFile != 0xE104) return sw(0x6986) // NDEF file not selected
            val offset = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)
            val lc = commandApdu[4].toInt() and 0xFF
            if (commandApdu.size < 5 + lc) return sw(0x6700) // wrong length
            val file = ndefFile()
            if (offset + lc > file.size) return sw(0x6B00) // past end of file
            val updated = file.copyOf()
            commandApdu.copyInto(updated, offset, 5, 5 + lc)
            ndefFileOverride = updated
            emulatedSummary = "Changed by a reader (${updated.size} bytes written to it)"
            return sw(0x9000)
        }

        return sw(0x6D00) // instruction not supported
    }

    override fun onDeactivated(reason: Int) {
        selectedFile = 0
    }

    private fun sw(code: Int): ByteArray =
        byteArrayOf(((code shr 8) and 0xFF).toByte(), (code and 0xFF).toByte())

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
