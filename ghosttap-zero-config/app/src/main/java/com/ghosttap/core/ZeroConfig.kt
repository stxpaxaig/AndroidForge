package com.ghosttap.core

/**
 * ZERO-CONFIG transformation (2026-11-06).
 *
 * The user's corrected directive governs: "YOU WILL NOT TAKE AWAY FROM
 * WHAT THERE, YOU WILL NOT ALTER ANY FUNCTIONS AND YOU WILL NOT BUILD
 * WITH ANY LIMITATIONS OR BARRIERS!" — only Ghost Tap's own limitations
 * apply. The flow in EVERY function is DISCOVER → SELECT → AUTO-CONNECT
 * → COMMANDS:
 *
 * 1. DISCOVER: every function keeps its own discover button. Discovery
 *    is not limited to what the app already knows — the AI investigates
 *    the unfamiliar and the app learns from it. That learning loop
 *    already exists and is reused, not re-implemented: every discover
 *    path files a real [com.ghosttap.core.investigate.Investigator]
 *    report into the ledger + [com.ghosttap.core.investigate.LiveTargetStore]
 *    (BLE: GhostTapViewModel, USB: UsbUartViewModel.discoverUsb,
 *    Network: NetworkToolsViewModel, Virtual ESP32: VirtualEsp32Screen,
 *    Visual: VisualIntelScreen, Investigations: IntelViewModel), and
 *    bring-up outcomes are recorded to memory by IntelViewModel so
 *    future bring-ups learn from them.
 * 2. SELECT → AUTO-CONNECT: tapping a found device/source connects it
 *    to a controllable state automatically, through the existing
 *    [BringUpPipeline] / ViewModel connect paths. No config by the
 *    user: every connection detail comes from discovery itself, via
 *    [autoConnectionFor] below. Manual entry stays ONLY as a fallback
 *    where discovery genuinely cannot supply a value — exactly one
 *    such case exists (a secured Wi-Fi network's password, which no
 *    scan can observe; see [AutoConnection.missingFields]).
 * 3. COMMANDS: once connected, the function shows the commands for
 *    that device in plain words, each one tap, through the existing
 *    capability/operation system ([com.ghosttap.core.ops.OperationRunner]).
 * 4. NO STANDALONE GLOBAL PICKER: the "What do you want to do?" list
 *    does NOT exist as its own standalone screen. Its content lives
 *    ONLY inside each function, already configured for each
 *    controllable device there (per-area picker routes + per-device
 *    command centers + the intent box). This explicitly reverses the
 *    earlier "home global button untouched" rule.
 * 5. INTENT BOX: the user types what they are trying to do in plain
 *    words; [IntentRouter] matches it against the REAL registered
 *    capabilities only and the match runs through the existing
 *    operation runner. It never invents an action and never fakes
 *    execution. This is intent ("do X"), not configuration.
 *
 * This file is pure Kotlin (stdlib only, no Android imports) so the
 * rules in it are unit-testable on the JVM. It contains no API key,
 * no network call, and no hardcoded device recognition.
 */

/** What kind of thing discovery just found. */
enum class DiscoveredKind {
    BLE,
    NETWORK_HOST,
    WIFI_NETWORK,
    USB,
    NFC,
    VIRTUAL_ESP32
}

/**
 * One thing discovery found, carrying everything discovery learned
 * about it. Connection details come from HERE — never typed by the
 * user — except the single justified fallback (secured Wi-Fi password).
 */
data class DiscoveredEndpoint(
    val kind: DiscoveredKind,
    /** Stable id from discovery: BLE MAC, host IP, "usb", "virtual:practice-board", tag key. */
    val id: String,
    val label: String,
    /** Network host address, from the scan — null for non-network kinds. */
    val host: String? = null,
    /** Open ports the scan actually observed answering — never guessed. */
    val openPorts: List<Int> = emptyList(),
    /** Wi-Fi network name, from the scan — null for non-Wi-Fi kinds. */
    val ssid: String? = null,
    /** Wi-Fi security as the scan reported it, e.g. "OPEN" or "WPA2". */
    val security: String? = null
)

/**
 * The pre-filled connection for a discovered endpoint.
 *
 * [ready] is true when Ghost Tap can connect with NO further input.
 * [missingFields] names, in plain words, anything still needed — it
 * is empty for every kind except a secured Wi-Fi network, whose
 * password no scan can observe. A missing field is a stated fact,
 * never a silent default: a network host with no open port does NOT
 * get a guessed port, it reports "no open port found".
 */
data class AutoConnection(
    val endpoint: DiscoveredEndpoint,
    val host: String?,
    val port: Int?,
    val ssid: String?,
    val needsPassword: Boolean,
    val ready: Boolean,
    val missingFields: List<String>,
    /** Plain-words line saying where the details came from. */
    val note: String
)

/**
 * Picks the connection point to talk to first from the ports a scan
 * actually observed open. Prefers the ports that serve something
 * readable (web) or are the common control points, in discovery order
 * otherwise. Returns null when nothing was observed open — the caller
 * must say so, never guess a port.
 */
fun preferredPort(openPorts: List<Int>): Int? {
    if (openPorts.isEmpty()) return null
    val preference = listOf(80, 443, 8080, 1883, 9000, 22, 23, 21)
    for (port in preference) if (port in openPorts) return port
    return openPorts.first()
}

/**
 * Builds the pre-filled connection for a discovered endpoint.
 * Nothing here asks the user for anything: [AutoConnection.ready] is
 * false only when discovery genuinely could not supply a value, and
 * [AutoConnection.missingFields] says exactly what that is.
 */
fun autoConnectionFor(endpoint: DiscoveredEndpoint): AutoConnection {
    return when (endpoint.kind) {
        DiscoveredKind.BLE -> AutoConnection(
            endpoint = endpoint,
            host = null,
            port = null,
            ssid = null,
            needsPassword = false,
            ready = endpoint.id.isNotBlank(),
            missingFields = if (endpoint.id.isBlank()) listOf("device address") else emptyList(),
            note = "Connected from what the scan found — no typing."
        )
        DiscoveredKind.NETWORK_HOST -> {
            val host = endpoint.host?.trim().orEmpty()
            val port = preferredPort(endpoint.openPorts)
            val missing = mutableListOf<String>()
            if (host.isBlank()) missing += "device address"
            if (port == null) missing += "port — nothing answered on the usual ports"
            AutoConnection(
                endpoint = endpoint,
                host = host.ifBlank { null },
                port = port,
                ssid = null,
                needsPassword = false,
                ready = missing.isEmpty(),
                missingFields = missing,
                note = if (port != null) {
                    "Address and port came from the scan — $host on port $port."
                } else {
                    "The scan found the device but nothing answered to connect to."
                }
            )
        }
        DiscoveredKind.WIFI_NETWORK -> {
            val ssid = endpoint.ssid?.trim().orEmpty()
            // The ONE justified manual fallback: a secured network's
            // password cannot be observed by any scan, and Android
            // cannot join a secured network without it. An open
            // network needs nothing. A password the camera already
            // read (photo/QR) joins without typing — see
            // VisualIntelScreen's Wi-Fi action, which passes the
            // photographed password straight to joinWifi.
            val needsPassword = endpoint.security?.uppercase() != "OPEN"
            val missing = mutableListOf<String>()
            if (ssid.isBlank()) missing += "network name"
            if (needsPassword) missing += "Wi-Fi password"
            AutoConnection(
                endpoint = endpoint,
                host = null,
                port = null,
                ssid = ssid.ifBlank { null },
                needsPassword = needsPassword,
                ready = missing.isEmpty(),
                missingFields = missing,
                note = if (needsPassword) {
                    "This network needs its password — no scan can find that, so it is the one thing typed."
                } else {
                    "Open network found by the scan — joins with no typing."
                }
            )
        }
        DiscoveredKind.USB -> AutoConnection(
            endpoint = endpoint,
            host = null,
            port = null,
            ssid = null,
            needsPassword = false,
            ready = endpoint.id.isNotBlank(),
            missingFields = if (endpoint.id.isBlank()) listOf("USB device") else emptyList(),
            note = "Board found plugged in — connects over the cable, no typing."
        )
        DiscoveredKind.NFC -> AutoConnection(
            endpoint = endpoint,
            host = null,
            port = null,
            ssid = null,
            needsPassword = false,
            ready = endpoint.id.isNotBlank(),
            missingFields = if (endpoint.id.isBlank()) listOf("tap card") else emptyList(),
            note = "The tap itself is the connection — hold the card to the phone."
        )
        DiscoveredKind.VIRTUAL_ESP32 -> AutoConnection(
            endpoint = endpoint,
            host = null,
            port = null,
            ssid = null,
            needsPassword = false,
            ready = true,
            missingFields = emptyList(),
            note = "Practice board found on this phone — connects with no typing."
        )
    }
}

/**
 * The "What do you want to do?" picker, as routes.
 *
 * The standalone global list is REMOVED: [GLOBAL_PICKER_ROUTE]
 * ("what_to_do", no area) must not appear in the app's navigation.
 * The picker lives ONLY per function, at [perFunctionPickerRoute] —
 * one short list per section, already configured for that section's
 * devices. This explicitly reverses the earlier "home global button
 * untouched" rule.
 */
object ZeroConfigRoutes {
    /** The removed standalone global picker route. Kept as a named constant so code and tests can assert it is gone. */
    const val GLOBAL_PICKER_ROUTE = "what_to_do"

    /** The seven per-function picker areas (same names as OperationArea). */
    val AREAS: List<String> = listOf("BLE", "USB", "NETWORK", "NFC", "VISUAL", "INVESTIGATIONS", "SYSTEM")

    /** The per-function picker route for one area, e.g. "what_to_do/NETWORK". */
    fun perFunctionPickerRoute(area: String): String = "what_to_do/$area"

    /** True for the removed standalone global picker route — and only that route. */
    fun isGlobalPickerRoute(route: String): Boolean = route == GLOBAL_PICKER_ROUTE

    /** True for a per-function picker route (area present and known). */
    fun isPerFunctionPickerRoute(route: String): Boolean {
        if (!route.startsWith("what_to_do/")) return false
        return route.removePrefix("what_to_do/") in AREAS
    }

    /** Every route the app navigates to. The global picker route is NOT in here. */
    fun appRoutes(): List<String> = listOf(
        "home",
        "target/{id}",
        "logs",
        "usb_uart",
        "vesp32",
        "network_tools",
        "nfc_tools",
        "visual_intel",
        "investigations"
    ) + AREAS.map(::perFunctionPickerRoute)
}

/**
 * The intent box: plain-words intent ("what I'm tryna do") → a REAL
 * registered capability, executed through the existing operation
 * runner. Pure matching over the caller-supplied list: it can only
 * ever return an id that was in that list — it never invents one,
 * and an intent that matches nothing returns null (the UI then says
 * so honestly instead of running something wrong).
 *
 * Matching is word-overlap against id + title + description, with
 * id words counting most (an id names the exact thing), title words
 * next, description words least, plus a small synonym expansion
 * ("bluetooth" is "ble", a photo is "visual") and prefix matching
 * for word variants ("scan" matches "scanning"). "wi-fi" is
 * normalised to "wifi" before tokenising so it matches the id word.
 */
object IntentRouter {

    /** One capability the router may return: id, title, description as registered. [area] scopes per-function lists. */
    data class CapabilityRef(
        val id: String,
        val title: String,
        val description: String,
        val area: String = ""
    )

    private val STOPWORDS = setOf(
        "the", "a", "an", "to", "do", "i", "want", "me", "my", "it", "is",
        "on", "for", "of", "and", "please", "can", "you", "get", "let", "go",
        "this", "that", "with", "up", "try", "trying", "im", "am", "are", "be",
        "in", "at", "by", "so", "just", "now", "how", "what", "find", "show"
    )

    private val SYNONYMS = mapOf(
        "bluetooth" to "ble",
        "ble" to "bluetooth",
        "wifi" to "wireless",
        "wireless" to "wifi",
        "tap" to "nfc",
        "card" to "nfc",
        "camera" to "visual",
        "photo" to "visual",
        "picture" to "visual",
        "print" to "printer",
        "printer" to "print"
    )

    /** Plain-words text → significant lower-case words (stopwords dropped, "wi-fi" normalised). */
    fun tokens(text: String): List<String> {
        val normalised = text.lowercase()
            .replace("wi-fi", "wifi")
            .replace("wi fi", "wifi")
        return normalised.split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in STOPWORDS }
    }

    private fun expanded(words: Set<String>): Set<String> {
        val out = words.toMutableSet()
        for (word in words) SYNONYMS[word]?.let { out += it }
        return out
    }

    /**
     * All matching capability ids for [intent], best match first.
     * Empty when nothing matches — never an invented id.
     */
    fun route(intent: String, capabilities: List<CapabilityRef>): List<String> {
        val words = expanded(tokens(intent).toSet())
        if (words.isEmpty()) return emptyList()
        return capabilities.mapNotNull { cap ->
            val idWords = cap.id.lowercase().split(Regex("[^a-z0-9]+")).toSet()
            val titleWords = expanded(tokens(cap.title).toSet())
            val descWords = expanded(tokens(cap.description).toSet())
            var score = 0
            for (word in words) {
                when {
                    word in idWords -> score += 3
                    word in titleWords -> score += 2
                    word in descWords -> score += 1
                    titleWords.any { it.startsWith(word) || word.startsWith(it) } -> score += 1
                    descWords.any { it.startsWith(word) || word.startsWith(it) } -> score += 1
                }
            }
            if (score > 0) cap.id to score else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** Best matching capability id for [intent], or null when nothing matches. */
    fun resolve(intent: String, capabilities: List<CapabilityRef>): String? =
        route(intent, capabilities).firstOrNull()

    /**
     * The per-function picker content for one area: only that area's
     * operations, in registration order — the picker content that used
     * to live in the standalone global list, now inside the function.
     */
    fun perFunctionPicker(area: String, capabilities: List<CapabilityRef>): List<CapabilityRef> =
        capabilities.filter { it.area == area }
}
