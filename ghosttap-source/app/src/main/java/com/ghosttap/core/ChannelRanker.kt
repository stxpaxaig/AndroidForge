package com.ghosttap.core

/**
 * Ranks a custom (non-SIG) characteristic by its real GATT properties and
 * explains what those properties mean in plain English.
 *
 * Ranking describes CAPABILITY, never confirmed behavior: a WRITE property
 * means the phone *can* send data, not that the characteristic controls
 * anything. That distinction is surfaced in every explanation.
 */
object ChannelRanker {

    data class Rank(
        /** e.g. "HIGH-INTEREST TWO-WAY CHANNEL" */
        val label: String,
        /** Higher = more interesting. Used to pick "most interesting channel". */
        val order: Int,
        /** Plain-English explanation of what the capabilities mean. */
        val plainEnglish: String
    )

    fun rank(properties: List<String>): Rank {
        val canWrite = properties.any { it == "WRITE" || it == "WRITE_NO_RESPONSE" || it == "SIGNED_WRITE" }
        val liveOut = properties.any { it == "NOTIFY" || it == "INDICATE" }
        val canRead = properties.contains("READ")
        return when {
            canWrite && liveOut -> Rank(
                "HIGH-INTEREST TWO-WAY CHANNEL",
                5,
                "Your phone can send data here, and the device can send data back. " +
                    "That combination is the strongest candidate for a communication or control channel — " +
                    "but this is capability, not proof. It has not been shown to control anything yet."
            )
            canWrite -> Rank(
                "POSSIBLE CONTROL",
                4,
                "The device lets your phone send data through this channel, which makes it worth " +
                    "investigating as a possible input or control. WRITE alone does not prove it controls anything."
            )
            liveOut -> Rank(
                "LIVE DATA",
                3,
                "The device can push live updates through this channel on its own. " +
                    "A good place to LISTEN and watch what changes when you use the device."
            )
            canRead -> Rank(
                "INFORMATION",
                2,
                "Your phone can ask this channel for information. Useful for reading device state, " +
                    "not for controlling it."
            )
            else -> Rank(
                "LOW INTEREST",
                1,
                "This channel did not report useful capabilities (no read, write, or live updates)."
            )
        }
    }

    /** One plain-English line per real GATT property. */
    fun capabilityLines(properties: List<String>): List<String> {
        val lines = mutableListOf<String>()
        if (properties.contains("READ")) lines += "READ — device lets us ask for information"
        if (properties.contains("NOTIFY")) lines += "NOTIFY — device can send live updates"
        if (properties.contains("INDICATE")) lines += "INDICATE — device can send confirmed live updates"
        if (properties.contains("WRITE")) lines += "WRITE — possible control/input channel"
        if (properties.contains("WRITE_NO_RESPONSE")) lines += "WRITE (no response) — possible fast control/input channel"
        if (properties.contains("SIGNED_WRITE")) lines += "SIGNED WRITE — possible authenticated control channel"
        if (properties.contains("WRITE") && properties.contains("NOTIFY")) {
            lines += "WRITE + NOTIFY — possible two-way command/data channel"
        }
        return lines
    }
}
