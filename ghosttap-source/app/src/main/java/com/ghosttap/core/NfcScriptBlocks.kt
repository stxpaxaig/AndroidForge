package com.ghosttap.core

/**
 * Package 4 — NFC scripts built from NAMED blocks, not raw hex.
 *
 * The user picks what to do in plain words ("Read page 0", "Check the
 * stored-data app"); each block is one hex command the card understands.
 * Building a script only assembles hex lines — the ONE parser stays
 * [parseHexScript] (which itself uses the null-safe [String.hexToBytes]
 * from C-23, fixed by Package 1). Nothing here parses hex a second
 * way, and nothing here talks to a card: the assembled text goes
 * through the existing [com.ghosttap.GhostTapViewModel.nfcRunScript] /
 * `nfcRawTransceiveTech`, unchanged.
 *
 * The hex itself is the standard command set these cards answer:
 * Ultralight/NTAG READ (0x30 + page), GET VERSION (0x60),
 * READ SIGNATURE (0x3C 00), and the three smart-card quick checks the
 * APDU console already offers. A block that needs card-specific bytes
 * (an ISO 15693 block read needs the card's UID) is deliberately NOT
 * a named block — it stays a guided operation that fills the UID in.
 */

/** One named thing to send to a card: plain-words name + the hex it sends. */
data class NfcNamedBlock(
    /** Plain-words name shown as a button, e.g. "Read page 0". */
    val name: String,
    /** One plain-words line saying what the answer tells the user. */
    val explains: String,
    /** The hex command, e.g. "30 00". */
    val hex: String,
    /** Which card language it needs: "AUTO" or a tech name. */
    val tech: String = "AUTO"
)

/** Single commands the user can send without typing hex. */
val NAMED_NFC_COMMANDS: List<NfcNamedBlock> = listOf(
    NfcNamedBlock(
        "Read page 0",
        "Shows the first 4 bytes of the card's memory.",
        "30 00", "NfcA"
    ),
    NfcNamedBlock(
        "Read page 4",
        "Shows the first page you can write to on most tap cards.",
        "30 04", "NfcA"
    ),
    NfcNamedBlock(
        "Read page 8",
        "Shows another page further into the card's memory.",
        "30 08", "NfcA"
    ),
    NfcNamedBlock(
        "Check the card's version",
        "Asks the card what kind of chip it is.",
        "60", "NfcA"
    ),
    NfcNamedBlock(
        "Check the card's signature",
        "Asks the card for its built-in signature bytes.",
        "3C 00", "NfcA"
    ),
    NfcNamedBlock(
        "Pick stored data",
        "Opens the stored-data app on a smart card.",
        "00 A4 04 00 07 D2 76 00 00 85 01 01 00", "IsoDep"
    ),
    NfcNamedBlock(
        "Read data",
        "Reads the data from the app that is open on a smart card.",
        "00 B0 00 00 00", "IsoDep"
    ),
    NfcNamedBlock(
        "Get info",
        "Asks a smart card for its information.",
        "00 CA 00 00 00", "IsoDep"
    )
)

/** One named script: plain-words name + the blocks it runs, in order. */
data class NfcNamedScript(
    /** Plain-words name shown as a button. */
    val name: String,
    /** One plain-words line saying what the script finds out. */
    val explains: String,
    /** The blocks, in the order they run. */
    val blocks: List<NfcNamedBlock>,
    /** Which card language the whole script needs. */
    val tech: String = "AUTO"
)

private fun namedCommand(name: String): NfcNamedBlock =
    NAMED_NFC_COMMANDS.first { it.name == name }

/** Multi-command scripts built from the named blocks above. */
val NAMED_NFC_SCRIPTS: List<NfcNamedScript> = listOf(
    NfcNamedScript(
        "Read the first pages",
        "Reads the first three pages of the card's memory, one after another.",
        listOf(
            namedCommand("Read page 0"),
            namedCommand("Read page 4"),
            namedCommand("Read page 8")
        ),
        tech = "NfcA"
    ),
    NfcNamedScript(
        "Check the card's identity",
        "Asks the card for its version and its signature.",
        listOf(
            namedCommand("Check the card's version"),
            namedCommand("Check the card's signature")
        ),
        tech = "NfcA"
    ),
    NfcNamedScript(
        "Check the stored data app",
        "Opens the stored-data app on a smart card and reads what is in it.",
        listOf(
            namedCommand("Pick stored data"),
            namedCommand("Read data")
        ),
        tech = "IsoDep"
    )
)

/**
 * Assembles a named script into the multi-line hex text the existing
 * script runner takes. Returns null when no script has that name.
 * The text is parsed by [parseHexScript] inside the runner — this
 * function never parses hex itself.
 */
fun buildNamedScript(name: String): String? {
    val script = NAMED_NFC_SCRIPTS.firstOrNull { it.name == name } ?: return null
    return script.blocks.joinToString("\n") { it.hex }
}

/** The tech a named script needs, or null when no script has that name. */
fun namedScriptTech(name: String): String? =
    NAMED_NFC_SCRIPTS.firstOrNull { it.name == name }?.tech

/** The hex for one named command, or null when no command has that name. */
fun namedCommandHex(name: String): String? =
    NAMED_NFC_COMMANDS.firstOrNull { it.name == name }?.hex

/** The tech one named command needs, or null when no command has that name. */
fun namedCommandTech(name: String): String? =
    NAMED_NFC_COMMANDS.firstOrNull { it.name == name }?.tech
