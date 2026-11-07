package com.ghosttap.core.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * A printer command language: knows how to wrap plain words into a complete
 * print job the printer understands.
 *
 * These are best-effort probes. Real printers vary, and some speak private
 * languages no app can guess — the probe flow in the UI handles that honestly
 * by asking the user whether anything printed.
 */
interface PrinterLanguage {
    /** Stable id, never shown to the user. */
    val id: String

    /** Wrap plain text into a full print job, ready to send as bytes. */
    fun encodeJob(text: String): ByteArray
}

/** Plain text + newline. The fallback every device at least receives. */
object RawText : PrinterLanguage {
    override val id = "raw"
    override fun encodeJob(text: String): ByteArray =
        (text + "\n").toByteArray(Charsets.UTF_8)
}

/**
 * ESC/POS — the most common thermal-printer language.
 * Init, text lines, then feed so the paper comes out.
 */
object EscPos : PrinterLanguage {
    override val id = "escpos"
    override fun encodeJob(text: String): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x1B.toByte())
        out.add(0x40.toByte()) // init
        val safe = text.map { c ->
            if (c.code in 32..126 || c == '\n') c else '?'
        }.joinToString("")
        safe.lines().forEach { line ->
            out.addAll(line.toByteArray(Charsets.UTF_8).toList())
            out.add(0x0A.toByte())
        }
        repeat(3) { out.add(0x0A.toByte()) } // feed
        return out.toByteArray()
    }
}

/**
 * CPCL — common on small mobile/label printers.
 */
object Cpcl : PrinterLanguage {
    override val id = "cpcl"
    override fun encodeJob(text: String): ByteArray {
        val safe = text.replace("\r", "").replace("\n", " ").take(200)
        return ("! 0 200 200 210 1\r\nTEXT 4 0 30 40 $safe\r\nPRINT\r\n")
            .toByteArray(Charsets.UTF_8)
    }
}

/**
 * TSPL — common on label printers.
 */
object Tspl : PrinterLanguage {
    override val id = "tspl"
    override fun encodeJob(text: String): ByteArray {
        val safe = text.replace("\"", "").replace("\r", "").replace("\n", " ").take(200)
        return ("SIZE 58,40\r\nGAP 3,0\r\nCLS\r\nTEXT 30,30,\"2\",0,1,1,\"$safe\"\r\nPRINT 1\r\n")
            .toByteArray(Charsets.UTF_8)
    }
}

/**
 * CTP500-style raster — reverse-engineered from the CTP500 thermal printer
 * (source: ThirtyThreeDown Studio's CTP500 Bluetooth client; the user's
 * uploaded Python client for the same family).
 *
 * This printer doesn't render text itself: the host renders words to a 1-bit
 * bitmap and sends it as ESC/POS raster data. Job bytes:
 *   1B 40            init
 *   1D 49 F0 19      start print sequence
 *   1D 76 30 00      raster: GS v 0, normal mode
 *     xL xH          width in bytes, little-endian (384 dots -> 48 = 0x30 0x00)
 *     yL yH          height in rows, little-endian
 *     data           1-bit packed, MSB-first, bit 1 = printed dot
 *   0A 0A 0A 9A      end / feed paper
 */
object Ctp500Raster : PrinterLanguage {
    override val id = "ctp500-raster"

    private const val DOT_WIDTH = 384
    private const val ROW_BYTES = DOT_WIDTH / 8 // 48

    override fun encodeJob(text: String): ByteArray {
        val lines = wordWrap(text)
        val paint = textPaint()
        val ascent = -paint.ascent() // positive distance from baseline to top
        val descent = paint.descent() // positive
        val lineHeight = ascent + descent + 4f
        val pad = 16f
        val height = (pad + lines.size * lineHeight + pad).toInt().coerceAtLeast(32)

        val bmp = Bitmap.createBitmap(DOT_WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        lines.forEachIndexed { i, line ->
            val baseline = pad + ascent + i * lineHeight
            canvas.drawText(line, 0f, baseline, paint)
        }

        // 1-bit pack: dark pixel (avg RGB < 128) -> bit 1, MSB-first per row.
        val px = IntArray(DOT_WIDTH * height)
        bmp.getPixels(px, 0, DOT_WIDTH, 0, 0, DOT_WIDTH, height)
        val packed = ByteArray(ROW_BYTES * height)
        var k = 0
        for (y in 0 until height) {
            for (xb in 0 until ROW_BYTES) {
                var b = 0
                for (bit in 0 until 8) {
                    val p = px[y * DOT_WIDTH + xb * 8 + bit]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val bl = p and 0xFF
                    if ((r + g + bl) / 3 < 128) b = b or (0x80 shr bit)
                }
                packed[k++] = b.toByte()
            }
        }
        bmp.recycle()

        val out = ArrayList<Byte>()
        fun bytes(vararg vs: Int) = vs.forEach { out.add(it.toByte()) }
        bytes(0x1B, 0x40) // init
        bytes(0x1D, 0x49, 0xF0, 0x19) // start print sequence
        bytes(0x1D, 0x76, 0x30, 0x00) // raster, normal mode
        bytes(ROW_BYTES and 0xFF, (ROW_BYTES shr 8) and 0xFF) // xL xH = 48
        bytes(height and 0xFF, (height shr 8) and 0xFF) // yL yH
        packed.forEach { out.add(it) }
        bytes(0x0A, 0x0A, 0x0A, 0x9A) // end / feed
        return out.toByteArray()
    }

    private fun textPaint(): Paint = Paint().apply {
        typeface = Typeface.MONOSPACE
        textSize = 28f
        color = Color.BLACK
        isAntiAlias = false
    }

    /** Word-wrap by measured width so lines fit 384 dots; keeps explicit newlines. */
    private fun wordWrap(text: String): List<String> {
        val paint = textPaint()
        val lines = ArrayList<String>()
        for (raw in text.split('\n')) {
            if (raw.isEmpty() || paint.measureText(raw) <= DOT_WIDTH) {
                lines.add(raw)
                continue
            }
            var cur = StringBuilder()
            for (word in raw.split(' ')) {
                val cand = if (cur.isEmpty()) word else "$cur $word"
                if (paint.measureText(cand) > DOT_WIDTH && cur.isNotEmpty()) {
                    lines.add(cur.toString())
                    cur = StringBuilder(word)
                } else {
                    cur = StringBuilder(cand)
                }
            }
            lines.add(cur.toString())
        }
        if (lines.isEmpty()) lines.add("") // blank text still prints an empty line
        return lines
    }
}

/** Probe order: raster first (prints on true ESC/POS too), plain fallback last. */
val ALL_LANGUAGES: List<PrinterLanguage> = listOf(Ctp500Raster, EscPos, Cpcl, Tspl, RawText)
