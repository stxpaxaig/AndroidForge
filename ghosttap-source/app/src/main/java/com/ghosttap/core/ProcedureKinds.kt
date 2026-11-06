package com.ghosttap.core

/**
 * Package 6 (C-18 / B-080) — which kind of target a saved plan fits.
 *
 * A procedure is only a list of steps, and each step names a capability. The
 * capability's category (BLE / NFC / NETWORK / USB / VISUAL) says what kind
 * of target that step works on — so the plan's kind is read off its steps,
 * not stored separately. Adding a column to the procedure table would have
 * needed a destructive database migration for a check this derivation
 * already answers.
 *
 * Categories SYSTEM / DYNAMIC / SCRIPT say nothing about the target kind:
 * a SYSTEM step (read a saved note, look something up) and a grown
 * DYNAMIC/SCRIPT control fit any target, so they never block a load.
 */
object ProcedureKinds {

    /** Target kinds that carry a real device kind. MANUAL is user-described: any plan fits. */
    fun isConcreteKind(targetKind: String): Boolean =
        targetKind.uppercase() in setOf("BLE", "NFC", "NETWORK", "USB", "VISUAL")

    /** Capability categories that say nothing about the target kind. */
    private val KIND_NEUTRAL = setOf("SYSTEM", "DYNAMIC", "SCRIPT")

    /**
     * True when a procedure whose steps have these capability categories may
     * be loaded onto an investigation of [targetKind]. True when the target
     * kind is user-described, when the plan has no kind-specific steps, or
     * when one of the plan's kind-specific steps matches the target.
     */
    fun matches(targetKind: String, stepCategories: List<String>): Boolean {
        if (!isConcreteKind(targetKind)) return true
        val specific = stepCategories.map { it.uppercase() }.filter { it !in KIND_NEUTRAL }.toSet()
        if (specific.isEmpty()) return true
        return targetKind.uppercase() in specific
    }

    /** Plain-words name for a target kind, for refusal messages. */
    fun plainKindName(targetKind: String): String = when (targetKind.uppercase()) {
        "BLE" -> "Bluetooth devices"
        "NFC" -> "tap cards"
        "NETWORK" -> "Wi-Fi devices"
        "USB" -> "USB cable devices"
        "VISUAL" -> "things in photos"
        else -> "this kind of target"
    }
}
