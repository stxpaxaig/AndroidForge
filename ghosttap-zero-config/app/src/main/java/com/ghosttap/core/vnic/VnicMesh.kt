package com.ghosttap.core.vnic

import kotlin.math.abs
import kotlin.math.min

/**
 * Sparse neural mesh — faithful Kotlin port of the VNIC Python
 * `SparseNeuralMesh` (vnic.py).
 *
 * The real algorithm, as written in the Python source (NOT the shorthand in
 * the handoff summary — the summary's hash-neighbour description was wrong;
 * this file was re-ported from the actual vnic.py on 2026-11-01):
 *
 * - 4096 materialised units stand in for a logical 8.7B neurons / 87.4B
 *   synapses (the logical counts are reference-profile numbers, kept as
 *   constants only).
 * - Each unit has [FANOUT]=12 random outgoing edges, drawn from a seeded
 *   MT19937 stream ([Mt19937], seed 731991 — the same stream CPython's
 *   `random.Random(731991)` produces), weights uniform(0.05, 0.35).
 * - Each unit has a firing threshold 0.42 + random()*0.25, drawn after all
 *   edges (RNG stream order matters and is preserved).
 * - `activate(signal)`: BLAKE2b-256 of the signal string seeds 8 units;
 *   seeds are boosted; then up to 3 rounds of threshold-gated propagation
 *   (a unit fires only at/above its threshold, pushing value*w*0.22 to its
 *   targets). Returns seed/fired counts, the CUMULATIVE propagation-event
 *   counter, latency, and the active fraction.
 * - `reinforce(reward)`: learning rate 0.035 * clamped reward, applied to
 *   the FIRST 256 edge rows only, weight update
 *   `w + delta*(0.5 - |w - 0.5|)`, clamped to [0.001, 1].
 * - `snapshot()`: materialised counts, active units (>= 0.42), firings in
 *   the last second, totals.
 *
 * ONE documented deviation: CPython builds each unit's target set as a
 * `set`, and the weights are drawn while iterating that set — CPython set
 * iteration order for small ints is a hash-table layout detail this port
 * does not reproduce. The port draws the same 12 uniform values per unit
 * (same RNG stream, same count) but assigns them to the unit's targets in
 * sorted-target order. The target SET, the weight MULTISET, and every
 * distribution are identical; only which target gets which of the 12
 * weights can differ from a CPython run. The mesh is a bounded simulation
 * whose exact per-edge weights do not change any product behaviour.
 */
data class VnicActivationResult(
    val seedUnits: Int,
    val firedUnits: Int,
    /** Cumulative propagation events across the mesh lifetime (as in Python). */
    val propagationEvents: Long,
    val latencyMs: Double,
    val activeFraction: Double
)

data class VnicMeshSnapshot(
    val logicalNeurons: Long,
    val logicalSynapses: Long,
    val materializedUnits: Int,
    val materializedSparseSynapses: Int,
    val activeMaterializedUnits: Int,
    val firingEventsLastSecond: Int,
    val totalFiringEvents: Long,
    val propagationEvents: Long,
    val plasticityUpdates: Long
)

class VnicMesh(
    val units: Int = DEFAULT_UNITS,
    val fanout: Int = DEFAULT_FANOUT,
    seed: Long = DEFAULT_SEED,
    val learningRate: Double = DEFAULT_LEARNING_RATE
) {
    companion object {
        const val DEFAULT_UNITS = 4096
        const val DEFAULT_FANOUT = 12
        const val DEFAULT_SEED = 731991L
        const val DEFAULT_LEARNING_RATE = 0.035
        const val LOGICAL_NEURONS = 8_700_000_000L
        const val LOGICAL_SYNAPSES = 87_400_000_000L
        const val PROPAGATION_ROUNDS = 3
        const val PROPAGATION_FACTOR = 0.22
        const val ACTIVATION_DECAY = 0.985
    }

    /** Outgoing edges per unit: target unit -> weight in [0.05, 0.35]. */
    val edges: Array<LinkedHashMap<Int, Double>> = Array(units) { LinkedHashMap() }
    val activation: DoubleArray = DoubleArray(units)
    val threshold: DoubleArray = DoubleArray(units)
    var firings: Long = 0L
        private set
    var propagations: Long = 0L
        private set
    var plasticity: Long = 0L
        private set
    private val recentFirings = ArrayDeque<Long>()

    init {
        require(units >= 32) { "simulation_units must be >= 32" }
        val rng = Mt19937(seed)
        for (i in 0 until units) {
            val targets = LinkedHashSet<Int>()
            while (targets.size < min(fanout, units - 1)) {
                val j = rng.randrange(units)
                if (j != i) targets += j
            }
            // Weights drawn in sorted-target order (see class KDoc deviation note).
            for (j in targets.sorted()) edges[i][j] = rng.uniform(0.05, 0.35)
        }
        for (i in 0 until units) threshold[i] = 0.42 + rng.nextDouble() * 0.25
    }

    /**
     * Activate the mesh from a signal string, exactly like Python's
     * `activate(signal, strength)`: BLAKE2b-256 digest -> 8 seed units,
     * seed boost, then up to 3 threshold-gated propagation rounds.
     */
    fun activate(signal: String, strength: Double = 1.0): VnicActivationResult {
        val t0 = System.nanoTime()
        val digest = Blake2b.digest(signal.toByteArray(Charsets.UTF_8), 32)
        val seeds = (0 until 32 step 4).map { k ->
            var v = 0L
            for (b in 0 until 4) v = (v shl 8) or (digest[k + b].toLong() and 0xFF)
            (v % units).toInt()
        }
        var frontier = seeds.toSet()
        val fired = LinkedHashSet<Int>()
        for (i in seeds) activation[i] = min(1.0, activation[i] * 0.25 + 0.75 * strength)
        repeat(PROPAGATION_ROUNDS) {
            val nxt = LinkedHashSet<Int>()
            for (src in frontier) {
                val value = activation[src]
                if (value >= threshold[src]) {
                    fired += src
                    firings++
                    recentFirings.addLast(System.currentTimeMillis())
                    if (recentFirings.size > 10000) recentFirings.removeFirst()
                    for ((dst, w) in edges[src]) {
                        activation[dst] = min(1.0, activation[dst] + value * w * PROPAGATION_FACTOR)
                        nxt += dst
                        propagations++
                    }
                }
            }
            frontier = nxt
            if (frontier.isEmpty()) return@repeat
        }
        val latencyMs = (System.nanoTime() - t0) / 1_000_000.0
        return VnicActivationResult(
            seedUnits = seeds.size,
            firedUnits = fired.size,
            propagationEvents = propagations,
            latencyMs = latencyMs,
            activeFraction = fired.size.toDouble() / units
        )
    }

    /** Python's `reinforce(reward)`: delta over the FIRST 256 edge rows only. */
    fun reinforce(reward: Double = 1.0): Int {
        val delta = learningRate * reward.coerceIn(-1.0, 1.0)
        var updates = 0
        for (row in edges.take(min(256, units))) {
            for ((j, w) in row) {
                row[j] = min(1.0, maxOf(0.001, w + delta * (0.5 - abs(w - 0.5))))
                updates++
            }
        }
        plasticity += updates
        return updates
    }

    /** Python's per-cycle activation decay (process() multiplies by 0.985). */
    fun decay(factor: Double = ACTIVATION_DECAY) {
        for (i in activation.indices) activation[i] *= factor
    }

    fun snapshot(): VnicMeshSnapshot {
        val now = System.currentTimeMillis()
        return VnicMeshSnapshot(
            logicalNeurons = LOGICAL_NEURONS,
            logicalSynapses = LOGICAL_SYNAPSES,
            materializedUnits = units,
            materializedSparseSynapses = edges.sumOf { it.size },
            activeMaterializedUnits = activation.count { it >= 0.42 },
            firingEventsLastSecond = recentFirings.count { now - it <= 1000 },
            totalFiringEvents = firings,
            propagationEvents = propagations,
            plasticityUpdates = plasticity
        )
    }

    fun synapseCount(): Int = edges.sumOf { it.size }
}
