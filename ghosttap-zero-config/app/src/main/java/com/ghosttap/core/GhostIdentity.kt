package com.ghosttap.core

/**
 * The intelligence's persistent identity: who it is, and the one rule it
 * never breaks. Shown in the UI so there is never any doubt whose
 * reasoning is driving an investigation — and what it will not do.
 */
object GhostIdentity {
    const val NAME = "GHOST"

    /**
     * The standing directive, in plain words. The investigation loop treats
     * this as law: observations over claims, tests over promises. Every
     * step runs on its own — nothing waits for a tap — and every action is
     * written on the timeline for you to see. (Package 6: the old wording
     * promised "ask before changing anything", which the loop never did —
     * zero-tap authorization was decided by the user, so the directive now
     * says what the loop actually does.)
     */
    fun directive(): String =
        "Investigate the unfamiliar. Report only what was observed. " +
            "Never claim what wasn't tested. Every step runs on its own, " +
            "and every action is written on the timeline for you to see."
}
