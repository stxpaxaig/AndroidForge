package com.ghosttap.core.vnic

/**
 * BLAKE2b — pure-Kotlin implementation (RFC 7693), digest size configurable.
 *
 * The Python VNIC seeds its mesh from `hashlib.blake2b(signal, digest_size=32)`.
 * Neither the JDK nor Android's Conscrypt offers BLAKE2b via MessageDigest,
 * so the faithful port implements it here. Verified byte-for-byte against
 * CPython's hashlib (see VnicCoreTest.blake2bMatchesCPython).
 *
 * Only the unkeyed, sequential (fanout=1, depth=1) form is implemented —
 * that is all the VNIC uses.
 */
object Blake2b {
    private val IV = longArrayOf(
        0x6a09e667f3bcc908L, -0x4498517a7b3558c5L,
        0x3c6ef372fe94f82bL, -0x5ab00ac5a0e2c90fL,
        0x510e527fade682d1L, -0x64fa9773d4c193e1L,
        0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
    )

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3)
    )

    private fun rotr(x: Long, n: Int): Long = (x ushr n) or (x shl (64 - n))

    private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = rotr(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]
        v[b] = rotr(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y
        v[d] = rotr(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = rotr(v[b] xor v[c], 63)
    }

    private fun compress(h: LongArray, block: ByteArray, offset: Int, counter: Long, last: Boolean) {
        val m = LongArray(16)
        for (i in 0 until 16) {
            var v = 0L
            for (b in 0 until 8) v = v or ((block[offset + i * 8 + b].toLong() and 0xFF) shl (8 * b))
            m[i] = v
        }
        val v = LongArray(16)
        for (i in 0 until 8) v[i] = h[i]
        for (i in 0 until 8) v[i + 8] = IV[i]
        v[12] = v[12] xor counter
        if (last) v[14] = v[14].inv()
        for (r in 0 until 12) {
            val s = SIGMA[r]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    /** Unkeyed BLAKE2b digest of [input] with [digestSize] bytes (default 32, as the VNIC uses). */
    fun digest(input: ByteArray, digestSize: Int = 32): ByteArray {
        require(digestSize in 1..64) { "digestSize must be 1..64" }
        val h = LongArray(8)
        for (i in 0 until 8) h[i] = IV[i]
        // Parameter block: digest_length | key_length=0 | fanout=1 | depth=1, rest zero.
        h[0] = h[0] xor 0x01010000L xor digestSize.toLong()
        var counter = 0L
        var offset = 0
        val block = ByteArray(128)
        if (input.isEmpty()) {
            compress(h, block, 0, 0L, true)
        } else {
            while (offset + 128 <= input.size) {
                counter += 128
                val last = offset + 128 == input.size
                compress(h, input, offset, counter, last)
                offset += 128
                if (last) {
                    offset = input.size
                    break
                }
            }
            if (offset < input.size) {
                val remaining = input.size - offset
                input.copyInto(block, 0, offset, input.size)
                block.fill(0, remaining, 128)
                counter += remaining
                compress(h, block, 0, counter, true)
            } else if (offset == input.size && input.size % 128 == 0) {
                // Input ended exactly on a block boundary: the last block was
                // already compressed as non-final above, so compress an empty
                // final block. (Handled by the loop's `last` flag when the
                // final block is full — see below.)
            }
        }
        // If the input length is a non-zero multiple of 128, the loop above
        // compressed the final block with last=true already; nothing more to do.
        val out = ByteArray(digestSize)
        for (i in 0 until digestSize) {
            out[i] = ((h[i / 8] ushr (8 * (i % 8))) and 0xFF).toByte()
        }
        return out
    }
}
