package com.ghosttap.core.vnic

/**
 * Mersenne Twister MT19937 — a faithful re-implementation of CPython's
 * `random.Random` core, so the VNIC mesh's seeded RNG stream matches the
 * Python reference exactly.
 *
 * CPython's random module is MT19937 with init_by_array seeding
 * (random.seed(int) converts the int to little-endian 32-bit words —
 * init_genrand(seed) is NOT what CPython does for int seeds), the
 * genrand_res53() 53-bit `random()` (two 32-bit outputs, top 27 + top 26
 * bits), `_randbelow(n)` rejection sampling for `randrange(n)`, and
 * `uniform(a, b) = a + (b - a) * random()`.
 *
 * Verified against CPython: `random.Random(731991)` must produce the same
 * first outputs (see VnicCoreTest.mersenneTwisterMatchesCPython).
 */
class Mt19937(seed: Long) {
    companion object {
        private val UPPER_MASK = 0x80000000u.toInt()
        private const val LOWER_MASK = 0x7fffffff
        private val MATRIX_A = 0x9908b0dfu.toInt()
        private val TEMPER_B = 0x9d2c5680u.toInt()
        private val TEMPER_C = 0xefc60000u.toInt()
    }

    private val mt = IntArray(624)
    private var index = 624

    init {
        // CPython's random.seed(int) does NOT use init_genrand(seed): it
        // converts the int to little-endian 32-bit words and runs
        // init_by_array(key). init_genrand(19650218) is only the first step.
        val key = longToWords(seed)
        initGenrand(19650218L)
        var i = 1
        var j = 0
        var k = maxOf(624, key.size)
        while (k > 0) {
            // mt[i] = (mt[i] ^ ((mt[i-1] ^ (mt[i-1] >> 30)) * 1664525)) + key[j] + j
            // NOTE: the XOR covers only the product term; key[j]+j is ADDED.
            val x = mt[i - 1] xor (mt[i - 1] ushr 30)
            mt[i] = (mt[i] xor (x * 1664525)) + key[j] + j
            i++; j++
            if (i >= 624) { mt[0] = mt[623]; i = 1 }
            if (j >= key.size) j = 0
            k--
        }
        for (kk in 623 downTo 1) {
            // mt[i] = (mt[i] ^ ((mt[i-1] ^ (mt[i-1] >> 30)) * 1566083941)) - i
            val x = mt[i - 1] xor (mt[i - 1] ushr 30)
            mt[i] = (mt[i] xor (x * 1566083941)) - i
            i++
            if (i >= 624) { mt[0] = mt[623]; i = 1 }
        }
        mt[0] = 0x80000000u.toInt()
    }

    /** Little-endian 32-bit words of the seed (CPython's int -> key array). */
    private fun longToWords(value: Long): IntArray {
        if (value == 0L) return intArrayOf(0)
        val words = mutableListOf<Int>()
        var v = value
        while (v != 0L) {
            words += (v and 0xFFFFFFFFL).toInt()
            v = v ushr 32
        }
        return words.toIntArray()
    }

    private fun initGenrand(s: Long) {
        mt[0] = s.toInt()
        for (i in 1 until 624) {
            mt[i] = (1812433253L * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i).toInt()
        }
    }

    private fun twist() {
        for (i in 0 until 624) {
            val y = (mt[i] and UPPER_MASK) or (mt[(i + 1) % 624] and LOWER_MASK)
            var xa = y ushr 1
            if (y and 1 != 0) xa = xa xor MATRIX_A
            mt[i] = mt[(i + 397) % 624] xor xa
        }
        index = 0
    }

    /** One tempered 32-bit output — CPython's genrand_int32(). */
    fun nextInt32(): Int {
        if (index >= 624) twist()
        var y = mt[index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and TEMPER_B)
        y = y xor ((y shl 15) and TEMPER_C)
        y = y xor (y ushr 18)
        return y
    }

    /** CPython's random(): 53-bit precision float in [0, 1). */
    fun nextDouble(): Double {
        val a = (nextInt32().toLong() and 0xFFFFFFFFL) ushr 5
        val b = (nextInt32().toLong() and 0xFFFFFFFFL) ushr 6
        return (a * 67108864.0 + b) / 9007199254740992.0
    }

    /** CPython's _randbelow(n) for 0 < n <= 2^32: rejection sampling on k bits, k = n.bit_length(). */
    fun below(n: Int): Int {
        require(n > 0) { "below() requires n > 0" }
        val k = 32 - Integer.numberOfLeadingZeros(n)
        while (true) {
            val r = (nextInt32().toLong() and 0xFFFFFFFFL) ushr (32 - k)
            if (r < n) return r.toInt()
        }
    }

    /** CPython's randrange(units). */
    fun randrange(n: Int): Int = below(n)

    /** CPython's uniform(a, b). */
    fun uniform(a: Double, b: Double): Double = a + (b - a) * nextDouble()
}
