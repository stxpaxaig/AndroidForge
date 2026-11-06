package com.ghosttap.core.vnic

/**
 * Autonomous-drive policy — pure Kotlin, JVM-testable, no Android imports.
 *
 * The VNIC runs a fresh intelligence cycle on its own (from the
 * investigation loop's onLoopRound hook) when every gate below holds.
 * Unknown targets are not pre-filtered by local transport keywords: the
 * AI/research cycle gets the opportunity to identify them first.
 *
 * Gates:
 * 1. No drive is currently running (one drive at a time, ever).
 * 2. The round's target is unresolved — loop status RUNNING or EXTENDING
 *    (COMPLETE / DEAD_END / WAITING_USER never drive).
 * 3. The target text is non-blank. Unknown targets are intentionally
 *    included: the live intelligence must identify them instead of a local
 *    keyword gate suppressing the investigation before the AI can run.
 * 4. This investigation+target was not driven within the 60s cooldown.
 * 5. This investigation+target is not already CONTROLLABLE (a held,
 *    mapped connection is never re-driven).
 *
 * The policy is stateful (cooldown + controllable sets + running flag)
 * and never throws; the engine owns one instance.
 */
class VnicDrivePolicy {

    companion object {
        /** Minimum gap between two drives of the same investigation+target. */
        const val COOLDOWN_MS = 60_000L
    }

    private val lastDrivenAtMs = mutableMapOf<String, Long>()
    private val controllableKeys = mutableSetOf<String>()

    /** True while a drive is in flight. Guarded by the engine's single drive slot. */
    @Volatile
    var driveRunning: Boolean = false
        private set

    private fun key(investigationId: String, targetText: String) =
        "$investigationId|${targetText.trim()}"

    /** All gates, in order. Pure decision — records nothing. */
    fun shouldDrive(
        investigationId: String,
        targetText: String,
        status: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (driveRunning) return false
        if (status != "RUNNING" && status != "EXTENDING") return false
        val text = targetText.trim()
        if (text.isEmpty()) return false
        val k = key(investigationId, text)
        if (k in controllableKeys) return false
        val last = lastDrivenAtMs[k]
        if (last != null && nowMs - last < COOLDOWN_MS) return false
        return true
    }

    /** Mark a drive started: occupies the slot and stamps the cooldown. */
    fun markDriveStarted(
        investigationId: String,
        targetText: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        driveRunning = true
        lastDrivenAtMs[key(investigationId, targetText)] = nowMs
    }

    /** Mark a drive finished: frees the slot; a CONTROLLABLE outcome pins the target so it is never re-driven. */
    fun markDriveFinished(investigationId: String, targetText: String, controllable: Boolean) {
        driveRunning = false
        if (controllable) controllableKeys += key(investigationId, targetText)
    }

    /**
     * Package 7 (E-26) — snapshot / restore so the cooldowns and
     * CONTROLLABLE pins survive an app restart (persisted by the engine
     * in SharedPreferences, never in RAM only). Keys are the same
     * "investigationId|targetText" strings [shouldDrive] uses.
     */
    fun controllableKeysSnapshot(): Set<String> = controllableKeys.toSet()

    fun lastDrivenSnapshot(): Map<String, Long> = lastDrivenAtMs.toMap()

    fun restore(controllable: Set<String>, lastDriven: Map<String, Long>) {
        controllableKeys.clear()
        controllableKeys += controllable
        lastDrivenAtMs.clear()
        lastDrivenAtMs.putAll(lastDriven)
    }

    /** Test/support visibility: how many distinct targets are pinned CONTROLLABLE. */
    fun controllableCount(): Int = controllableKeys.size
}
