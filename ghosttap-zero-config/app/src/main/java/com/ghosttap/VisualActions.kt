package com.ghosttap

/**
 * "WHAT YOU CAN DO WITH THIS" — automatic action suggestions for a visual
 * finding. Looks at what the camera actually found (Wi-Fi codes, links,
 * addresses, chip keywords) and matches it against Ghost Tap's real tools.
 *
 * Every action is plain-English: what tapping it does, and what it unlocks
 * next. No jargon, no dead ends. The screen renders each action with its
 * explanation; the "id" tells the UI which handler to run and "arg" carries
 * the extracted value (URL, IP, MAC, …).
 *
 * Pure detection — no side effects here. Execution lives in the screen /
 * viewmodels, each try/catch'd with honest status messages.
 */
data class VisualAction(
    val title: String,
    val does: String,
    val unlocks: String,
    val id: String,
    val arg: String = ""
)

private val ipRegex = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
private val macRegex = Regex("""([0-9A-Fa-f]{2}(:|-)){5}[0-9A-Fa-f]{2}""")
private val usbKeywords = Regex("""esp32|arduino|ch340|cp210|uart|serial|teensy|stm32""")
private val nfcKeywords = Regex("""nfc|mifare|ntag|ndef|rfid""")

/** Structured Wi-Fi login from a WIFI:T:..;S:..;P:..;; QR payload. */
data class WifiCredentials(
    val ssid: String,
    val password: String,
    val security: String
)

/**
 * Parse a WIFI: QR payload honoring backslash escapes (\;, \:, \,, \\).
 * Null when there's no usable SSID.
 */
fun parseWifiCredentials(raw: String): WifiCredentials? {
    return try {
        val body = raw.removePrefix("WIFI:").trimEnd(';')
        var ssid = ""
        var sec = ""
        var pass = ""
        for (field in splitUnescapedTop(body, ';')) {
            val parts = splitUnescapedTop(field, ':')
            val key = parts.getOrNull(0)?.trim() ?: ""
            val value = parts.drop(1).joinToString(":")
            when (key) {
                "S" -> ssid = value
                "T" -> sec = value
                "P" -> pass = value
            }
        }
        if (ssid.isBlank()) null else WifiCredentials(ssid, pass, sec)
    } catch (_: Exception) {
        null
    }
}

private fun splitUnescapedTop(s: String, delimiter: Char): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            cur.append(s[i + 1])
            i += 2
        } else if (c == delimiter) {
            out.add(cur.toString())
            cur.clear()
            i++
        } else {
            cur.append(c)
            i++
        }
    }
    out.add(cur.toString())
    return out
}

/**
 * Match a finding against Ghost Tap's real capabilities. Returns the actions
 * in a sensible order (most specific first). Empty list = nothing recognized.
 */
fun suggestActions(f: VisualFinding): List<VisualAction> {
    val out = mutableListOf<VisualAction>()
    val seen = mutableSetOf<String>()
    fun add(a: VisualAction) {
        if (seen.add(a.id)) out.add(a)
    }

    // 1. Wi-Fi QR code — the clearest signal there is. One tap joins it.
    f.barcodes.firstOrNull { it.rawValue.startsWith("WIFI:") }?.let { bc ->
        parseWifiCredentials(bc.rawValue)?.let { creds ->
            add(
                VisualAction(
                    title = "JOIN THIS WI-FI",
                    does = "Tells the phone to join this network right now — no typing the password.",
                    unlocks = "Once you're on it, the network tools can reach everything on that network.",
                    id = "wifi",
                    arg = "${creds.ssid}||${creds.password}||${creds.security}"
                )
            )
        }
    }

    // 2. Barcode / QR pointing at a web link.
    f.barcodes.firstOrNull {
        it.rawValue.startsWith("http://", ignoreCase = true) ||
            it.rawValue.startsWith("https://", ignoreCase = true)
    }?.let { bc ->
        add(
            VisualAction(
                title = "FETCH WHAT THIS CODE POINTS TO",
                does = "Downloads what the link points to and shows the text right here — " +
                    "no browser needed.",
                unlocks = "Read it here, then decide if it's worth investigating.",
                id = "fetch_url",
                arg = bc.rawValue.trim()
            )
        )
    }

    val haystack = (f.fullText + "\n" + f.markings.joinToString("\n") +
        "\n" + f.barcodes.joinToString("\n") { it.rawValue })

    // 3. IP address spotted — connect to it directly, no ping step.
    ipRegex.find(haystack)?.value?.let { ip ->
        add(
            VisualAction(
                title = "CONNECT TO THIS ADDRESS",
                does = "Tries to reach this address right now.",
                unlocks = "See what answers — no extra step in between.",
                id = "connect_ip",
                arg = ip
            )
        )
    }

    // 4. Bluetooth hardware address spotted — connect straight to it, no scan.
    macRegex.find(haystack)?.value?.let { mac ->
        val clean = mac.replace("-", ":").uppercase()
        add(
            VisualAction(
                title = "CONNECT TO THIS DEVICE",
                does = "Connects straight to this device — no searching needed.",
                unlocks = "If it answers, you can read from it, send to it, and talk to it.",
                id = "ble",
                arg = clean
            )
        )
    }

    // 5. Board / serial keywords — USB territory.
    val lower = haystack.lowercase()
    if (usbKeywords.containsMatchIn(lower)) {
        add(
            VisualAction(
                title = "OPEN USB TOOLS",
                does = "Opens the USB screen to check whether the board is plugged in.",
                unlocks = "Message box, live replies from the board, and loading new software onto it.",
                id = "usb"
            )
        )
    }

    // 6. NFC keywords — tag territory.
    if (nfcKeywords.containsMatchIn(lower)) {
        add(
            VisualAction(
                title = "OPEN TAP-CARD TOOLS",
                does = "Opens the tap-card tools so you can tap and read the card.",
                unlocks = "Read, copy, or write the card (never write to a card you actually use).",
                id = "nfc"
            )
        )
    }

    return out
}
