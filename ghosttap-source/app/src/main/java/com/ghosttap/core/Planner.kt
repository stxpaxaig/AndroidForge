package com.ghosttap.core

/**
 * What the intelligence system is investigating. Built from a Visual Intel
 * finding, a BLE target, or something the user typed by hand.
 */
data class IntelTarget(
    val kind: String, // "VISUAL", "BLE", "MANUAL", "NFC", "NETWORK", "USB"
    val title: String,
    val text: String,
    val markings: List<String> = emptyList(),
    val barcodes: List<String> = emptyList()
)

/**
 * One step of an investigation plan.
 *
 * @param auto true when the planner judges the step safe to run on its own.
 *   Ghost Tap runs in full autonomous mode: every step runs without asking,
 *   at any risk level. Risk is still recorded on every step and every
 *   auto-run action is logged to the investigations timeline.
 * @param riskLevel copied from the registry entry at plan time ("READ",
 *   "CONFIGURE", "WRITE", "CONTROL"). The UI badges it; the engine enforces it.
 */
data class PlanStep(
    val capabilityId: String,
    val args: Map<String, String> = emptyMap(),
    val why: String,
    val auto: Boolean,
    val riskLevel: String = "READ"
)

/**
 * Rule-based investigation planner. Works fully offline — no network, no AI
 * service, no guessing. Reads the target's text/markings/barcodes, matches
 * simple patterns, and emits plan steps that reference ONLY registered
 * capability ids (validated; unknown ids are dropped, never crash).
 *
 * The UI labels this honestly: "On-device planner (rule-based)."
 */
object Planner {

    private val macRegex = Regex("(?i)\\b([0-9A-F]{2}[:-]){5}[0-9A-F]{2}\\b")
    private val ipv4Regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val urlRegex = Regex("(?i)\\bhttps?://[^\\s]+")
    private val hostRegex = Regex("(?i)\\b([a-z0-9-]+\\.)+(com|net|org|io|local|lan)\\b")
    private val nfcWords = Regex("(?i)\\b(nfc|rfid|tag|mifare|ntag|card|felica)\\b")
    private val bleWords = Regex("(?i)\\b(bluetooth|ble|beacon|peripheral)\\b")
    private val usbWords = Regex("(?i)\\b(esp32|uart|serial|cp210|ch340|ftdi|usb|ttl|flash|firmware)\\b")

    /**
     * @param failedIds capability ids that already failed on this investigation
     *   (from memory). Those steps are deprioritized — moved to the end with
     *   auto cleared and the reason noted — unless the user taps RUN manually.
     */
    fun planFor(
        t: IntelTarget,
        registry: CapabilityRegistry,
        failedIds: Set<String> = emptySet()
    ): List<PlanStep> {
        val normal = mutableListOf<PlanStep>()
        val demoted = mutableListOf<PlanStep>()
        val text = (t.text + " " + t.markings.joinToString(" ") + " " + t.barcodes.joinToString(" "))
        val seen = mutableSetOf<String>()

        fun add(step: PlanStep) {
            // Dedupe by capability id; keep the first (most specific) reason.
            if (!seen.add(step.capabilityId)) return
            // Risk comes from the registry — the single source of truth.
            val risk = registry.get(step.capabilityId)?.riskLevel ?: "READ"
            // Full autonomous mode: auto stays as the planner judged it, at
            // any risk level. Risk is recorded on the step and every auto-run
            // action is logged to the timeline.
            var s = step.copy(riskLevel = risk)
            if (s.capabilityId in failedIds) {
                s = s.copy(
                    why = s.why + " — tried before on this target and failed",
                    auto = false
                )
                demoted.add(s)
            } else {
                normal.add(s)
            }
        }

        // 0. Always start with context: what do we already know?
        add(
            PlanStep(
                "ble.list_targets",
                why = "See what Bluetooth targets Ghost Tap already knows about before doing anything new.",
                auto = true
            )
        )

        // 1. MAC address spotted -> the device may be advertising right now.
        val mac = macRegex.find(text)?.value
        if (mac != null) {
            add(
                PlanStep(
                    "ble.start_scan", mapOf("hint" to mac),
                    why = "Found a hardware address ($mac) in the text — scan to see if that device is nearby.",
                    auto = true
                )
            )
        } else if (bleWords.containsMatchIn(text)) {
            add(
                PlanStep(
                    "ble.start_scan",
                    why = "The text mentions Bluetooth — scan to see what's actually nearby.",
                    auto = true
                )
            )
        }

        // 2. IP address -> ping it, then offer a deeper look.
        val ip = ipv4Regex.find(text)?.value
        if (ip != null) {
            add(
                PlanStep(
                    "net.ping", mapOf("host" to ip),
                    why = "Found an IP address ($ip) — ping it to see if the host answers.",
                    auto = true
                )
            )
            add(
                PlanStep(
                    "net.port_scan", mapOf("host" to ip),
                    why = "If $ip answers, a port scan shows which services it exposes. Tap RUN when ready.",
                    auto = false
                )
            )
        }

        // 3. URL or hostname -> fetch it.
        val url = urlRegex.find(text)?.value
            ?: hostRegex.find(text)?.value?.let { "http://$it" }
        if (url != null) {
            add(
                PlanStep(
                    "net.http_fetch", mapOf("url" to url),
                    why = "Found a web address ($url) — fetch it to see what it serves.",
                    auto = true
                )
            )
        }

        // 4. Wi-Fi QR code -> joining is manual, then network tools apply.
        if (t.barcodes.any { it.startsWith("WIFI:") } || text.contains("WIFI:")) {
            add(
                PlanStep(
                    "sys.manual_step",
                    mapOf("instruction" to "Join the Wi-Fi network from the QR code (tap it in your Wi-Fi settings), then come back — NETWORK TOOLS can probe it."),
                    why = "A Wi-Fi code was scanned. Joining the network is a phone setting Ghost Tap can't change itself.",
                    auto = false
                )
            )
        }

        // 5. Serial/ESP32 words -> check the USB bench.
        if (usbWords.containsMatchIn(text) || t.kind == "USB") {
            add(
                PlanStep(
                    "usb.refresh",
                    why = "The target involves serial/ESP32 hardware — check whether a USB adapter is plugged in.",
                    auto = true
                )
            )
            add(
                PlanStep(
                    "usb.status",
                    why = "Read the USB serial state before touching anything.",
                    auto = true
                )
            )
            add(
                PlanStep(
                    "usb.connect",
                    why = "If an adapter shows up, connect it to open the serial link. Tap RUN when ready.",
                    auto = false
                )
            )
        }

        // 6. NFC words (or an NFC-kind target) -> NFC needs a physical tap.
        if (nfcWords.containsMatchIn(text) || t.kind == "NFC") {
            add(
                PlanStep(
                    "nfc.open_tools",
                    why = "This looks NFC-related. NFC needs a physical tap — open NFC TOOLS and tap the tag.",
                    auto = false
                )
            )
            add(
                PlanStep(
                    "nfc.read_summary",
                    why = "Check what the last tapped tag reported, if any.",
                    auto = true
                )
            )
            add(
                PlanStep(
                    "nfc.emulation_status",
                    why = "Check whether Ghost Tap is currently pretending to be a tag.",
                    auto = true
                )
            )
        }

        // 6b. Network-kind target without an IP yet -> point at the scanner.
        if (t.kind == "NETWORK" && ip == null) {
            add(
                PlanStep(
                    "sys.manual_step",
                    mapOf(
                        "instruction" to "No address on this target yet. Run SCAN MY NETWORK on the NETWORK TOOLS screen, " +
                            "note the device's IP, then start a new investigation with that IP in the description."
                    ),
                    why = "This is a network target with no address — the network scan finds it first.",
                    auto = false
                )
            )
        }

        // 7. Came from a photo -> offer a closer look.
        if (t.kind == "VISUAL") {
            add(
                PlanStep(
                    "visual.capture_analyze",
                    why = "This target came from a photo — capture a closer shot for a sharper read. Tap RUN when ready.",
                    auto = false
                )
            )
        }

        // 8. Markings worth remembering -> searchable later.
        if (t.markings.isNotEmpty() && mac == null) {
            add(
                PlanStep(
                    "sys.manual_step",
                    mapOf(
                        "instruction" to "Markings on file: ${t.markings.take(5).joinToString(", ")}. " +
                            "Verify them by eye against the real object, then use KNOWLEDGE SEARCH to check past investigations."
                    ),
                    why = "Chip markings were pulled from the photo. They're candidates until you verify them visually.",
                    auto = false
                )
            )
        }

        // 9. Always end with a human decision point.
        add(
            PlanStep(
                "sys.manual_step",
                mapOf("instruction" to "Review the timeline below, then pick the next move — or save this as a reusable procedure."),
                why = "The automatic probes are done. You decide what happens next.",
                auto = false
            )
        )

        // Validate against the registry: drop anything unknown, never crash.
        // Demoted (previously failed) steps go last.
        val all = normal + demoted
        val bad = all.map { it.capabilityId }.filter { registry.get(it) == null }.toSet()
        return all.filter { it.capabilityId !in bad }
    }
}
