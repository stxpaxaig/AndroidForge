package com.ghosttap.core

/**
 * Offline-first, best-effort device identification for GHOST TAP.
 *
 * Interprets legitimately observed signals (advertised name, service UUIDs,
 * manufacturer ID, Bluetooth device class, GATT services) and produces a
 * human-readable best guess. NEVER fabricates: when evidence is insufficient
 * the result is UNKNOWN with LOW confidence.
 *
 * To expand the local signature database, add entries to [nameRules],
 * [serviceRules], [companyNames], or the device-class branches in
 * [classHit]. No other code changes are needed.
 */

enum class Confidence { LOW, MEDIUM, HIGH }

data class Identification(
    val label: String,
    val confidence: Confidence,
    val evidence: List<String>
)

data class DeviceSignals(
    val name: String? = null,
    val address: String? = null,
    val protocol: String = "UNKNOWN", // BLE, CLASSIC, NFC, USB, NETWORK, SENSOR
    val advertisedServiceUuids: List<String> = emptyList(),
    val manufacturerId: Int? = null,
    /** First manufacturer's raw data as hex, e.g. "02 15 ...". */
    val manufacturerDataHex: String? = null,
    /** Service-data UUIDs seen in the advertisement. */
    val serviceDataUuids: List<String> = emptyList(),
    val deviceClassFull: Int? = null, // BluetoothClass.deviceClass (Classic + BLE when present)
    /** ScanResult.isConnectable — null when unknown. */
    val connectable: Boolean? = null,
    val gattServices: List<String> = emptyList(),
    val txPower: Int? = null,
    val rssi: Int? = null
)

object DeviceClassifier {

    // ------------------------------------------------------------------
    // Local signature database — ADD NEW SIGNATURES HERE.
    // ------------------------------------------------------------------

    /** Regex (matched against the advertised name) -> (label, confidence). */
    private val nameRules: List<Triple<Regex, String, Confidence>> = listOf(
        // Remote tank-level monitors, e.g. "TM5030_24769042"
        Triple(Regex("^TM\\d{3,}"), "TANK MONITOR", Confidence.MEDIUM),
        Triple(Regex("(?i)tank[_ -]?(level|monitor|sensor)"), "TANK MONITOR", Confidence.MEDIUM),
        Triple(Regex("(?i)\\btank\\b"), "TANK MONITOR", Confidence.LOW),

        Triple(Regex("(?i)(airpods|pixel buds|galaxy buds|echo buds|ear ?buds|\\bWH-\\d+\\w*|\\bWF-\\d+\\w*|headphones?)"), "HEADPHONES", Confidence.MEDIUM),
        Triple(Regex("(?i)(\\bjbl\\b|soundcore|boom ?box|party ?box|\\bspeaker\\b|sound ?bar|marshall|sonos)"), "SPEAKER", Confidence.MEDIUM),
        Triple(Regex("(?i)(apple watch|galaxy watch|pixel watch|garmin|fitbit|\\bversa\\b|smart ?watch)"), "SMART WATCH", Confidence.MEDIUM),
        Triple(Regex("(?i)(fitness|\\btracker\\b|mi band|\\bband \\d|charge \\d)"), "FITNESS TRACKER", Confidence.MEDIUM),
        Triple(Regex("(?i)(\\biphone\\b|pixel \\d|galaxy s\\d+|sm-[a-z]\\d+|oneplus|\\bphone\\b)"), "PHONE", Confidence.MEDIUM),
        Triple(Regex("(?i)(\\btv\\b|bravia|smart ?tv|\\broku\\b|fire ?tv|chromecast|apple ?tv|shield ?tv)"), "TV", Confidence.MEDIUM),
        Triple(Regex("(?i)(printer|laser ?jet|desk ?jet|office ?jet|\\bbrother\\b|\\bepson\\b|\\bcanon\\b)"), "PRINTER", Confidence.MEDIUM),
        Triple(Regex("(?i)(\\btesla\\b|\\bobd\\b|\\bcar\\b|vehicle|\\bford\\b|\\btoyota\\b|\\bbmw\\b)"), "VEHICLE", Confidence.LOW),
        Triple(Regex("(?i)(\\bbeacon\\b|ibeacon|eddystone|estimote|kontakt)"), "BEACON", Confidence.MEDIUM),
        Triple(Regex("(?i)(thermostat|\\bnest\\b|\\bhue\\b|philips|smart ?plug|smart ?switch|light ?bulb|\\bring\\b|\\barlo\\b|\\bwyze\\b|ecobee)"), "SMART HOME DEVICE", Confidence.MEDIUM),
        Triple(Regex("(?i)(esp32|esp8266|arduino|raspberry|sensor ?tag|ruuvi|lywsd|atc_|qingping|\\btilt\\b)"), "SENSOR", Confidence.LOW),
        Triple(Regex("(?i)(\\bscale\\b|thermometer|hygrometer|air ?quality|plant ?sensor|soil|water ?leak)"), "SENSOR", Confidence.LOW),
    )

    /** 16-bit Bluetooth SIG service number -> (label, confidence). */
    private val serviceRules: Map<Int, Pair<String, Confidence>> = mapOf(
        0x180D to ("FITNESS TRACKER" to Confidence.MEDIUM), // Heart Rate
        0x1816 to ("FITNESS TRACKER" to Confidence.MEDIUM), // Cycling Speed and Cadence
        0x1826 to ("FITNESS TRACKER" to Confidence.MEDIUM), // Fitness Machine
        0x181A to ("SENSOR" to Confidence.MEDIUM),           // Environmental Sensing
        0x181D to ("SENSOR" to Confidence.MEDIUM),           // Weight Scale
        0x181B to ("SENSOR" to Confidence.LOW),             // Body Composition
        0xFEAA to ("BEACON" to Confidence.HIGH),            // Eddystone
    )

    /** 16-bit service number -> short human name (for evidence lines). */
    private val serviceNames: Map<Int, String> = mapOf(
        0x180D to "Heart Rate",
        0x1816 to "Cycling",
        0x1826 to "Fitness Machine",
        0x181A to "Environmental Sensing",
        0x181D to "Weight Scale",
        0x181B to "Body Composition",
        0xFEAA to "Eddystone",
        0x1800 to "Generic Access",
        0x1801 to "Generic Attribute",
        0x180F to "Battery",
    )

    /** Bluetooth SIG company ID -> maker name (supporting evidence only). */
    private val companyNames: Map<Int, String> = mapOf(
        0x004C to "Apple",
        0x0006 to "Microsoft",
        0x00E0 to "Google",
        0x0075 to "Samsung",
        0x012D to "Sony",
        0x009E to "Bose",
        0x0087 to "Garmin",
        0x0157 to "Huawei",
        0x02E5 to "Xiaomi",
        0x000F to "Broadcom",
        0x0059 to "Nordic",
        0x0590 to "Espressif",
    )

    // ------------------------------------------------------------------
    // Identification
    // ------------------------------------------------------------------

    private data class Hit(val label: String, val conf: Confidence, val ev: String)

    fun identify(s: DeviceSignals): Identification {
        // Protocol-native identities: no guessing needed.
        when (s.protocol) {
            "NFC" -> return Identification(
                "NFC TAG", Confidence.MEDIUM,
                listOf("NFC tag ${s.address ?: "detected"}")
            )
            "USB" -> return Identification(
                "USB DEVICE", Confidence.MEDIUM,
                listOfNotNull(s.address?.let { "USB $it" } ?: "USB device attached")
            )
            "SENSOR" -> return Identification(
                s.name?.uppercase() ?: "SENSOR", Confidence.HIGH,
                listOf("On-board phone sensor")
            )
            "NETWORK" -> return Identification(
                "NETWORK SERVICE", Confidence.LOW,
                listOfNotNull(s.name?.let { "Service \"$it\"" })
            )
        }

        val hits = mutableListOf<Hit>()        // 1. Advertised name patterns (skip synthetic "BLE-…"/"BT-…" fallbacks).
        val name = s.name?.trim().orEmpty()
        if (name.isNotEmpty() && !name.startsWith("BLE-") && !name.startsWith("BT-")) {
            nameRules.firstOrNull { it.first.containsMatchIn(name) }?.let { rule ->
                hits += Hit(rule.second, rule.third, "Name \"${name.take(24)}\"")
            }
        }

        // 2. Advertised + GATT service UUIDs.
        val seenServices = mutableSetOf<Int>()
        (s.advertisedServiceUuids + s.gattServices).forEach { uuid ->
            val n = uuid16(uuid) ?: return@forEach
            if (!seenServices.add(n)) return@forEach
            serviceRules[n]?.let { (label, conf) ->
                val svcName = serviceNames[n] ?: ("0x" + n.toString(16).uppercase())
                val viaGatt = uuid in s.gattServices
                // Post-connection GATT evidence is stronger than advertisements.
                val boosted = if (viaGatt && conf == Confidence.MEDIUM) Confidence.HIGH else conf
                hits += Hit(label, boosted, "${if (viaGatt) "GATT" else "Advertised"} service $svcName")
            }
        }

        // 3. Bluetooth device class (Classic).
        s.deviceClassFull?.let { dc ->
            classHit(dc)?.let { hits += it }
        }

        // 4. Manufacturer ID: supporting evidence only, never decides alone.
        val makerEvidence = s.manufacturerId?.let { id ->
            companyNames[id]?.let { "Maker: $it" }
        }

        val labelHits = hits.filter { it.label.isNotEmpty() }
        if (labelHits.isEmpty()) {
            val unknownLabel = if (s.protocol == "BLE") "UNKNOWN BLE DEVICE" else "UNKNOWN DEVICE"
            val ev = mutableListOf("No matching signature")
            if (s.advertisedServiceUuids.isNotEmpty()) {
                ev += "Services seen: ${s.advertisedServiceUuids.size}"
            }
            return Identification(unknownLabel, Confidence.LOW, ev)
        }

        // Same label from 2+ independent sources -> HIGH confidence.
        val byLabel = labelHits.groupBy { it.label }
        val (label, group) = byLabel.maxWithOrNull(
            compareBy({ it.value.size }, { it.value.maxOf { h -> h.conf.ordinal } })
        )!!
        val conf = if (group.size >= 2) Confidence.HIGH else group.maxOf { it.conf }
        val evidence = (group.map { it.ev } + listOfNotNull(makerEvidence)).distinct().take(3)
        return Identification(label, conf, evidence)
    }

    /** BluetoothClass device class -> best-effort hit. Works for Classic and BLE. */
    private fun classHit(deviceClass: Int): Hit? {
        // Major/minor constants in Android's BluetoothClass already carry their
        // bit positions, so compare masked raw values directly.
        // 0 means "uncategorized / not reported" — never a signal.
        if (deviceClass == 0) return null
        val major = deviceClass and 0x1F00
        val minor = deviceClass and 0xFC
        return when (major) {
            0x100 -> Hit("COMPUTER", Confidence.MEDIUM, "Class: computer")
            0x200 -> Hit("PHONE", Confidence.MEDIUM, "Class: phone")
            0x400 -> when (minor) {
                // 4 = wearable headset, 8 = hands-free, 28 = headphones
                4, 8, 28 -> Hit("HEADPHONES", Confidence.MEDIUM, "Class: headset/headphones")
                20 -> Hit("SPEAKER", Confidence.MEDIUM, "Class: loudspeaker")
                // 36 = set-top box, 60 = video monitor, 64 = video display + speaker
                36, 60, 64 -> Hit("TV", Confidence.MEDIUM, "Class: TV / video device")
                24, 40 -> Hit("SPEAKER", Confidence.LOW, "Class: audio device")
                else -> Hit("AUDIO DEVICE", Confidence.LOW, "Class: audio/video")
            }
            0x500 -> Hit("CONTROLLER / REMOTE", Confidence.LOW, "Class: input device (keyboard, mouse, or remote)")
            0x600 -> if (minor == 128) {
                // minor 128 = printer
                Hit("PRINTER", Confidence.MEDIUM, "Class: printer")
            } else {
                Hit("PRINTER", Confidence.LOW, "Class: imaging device")
            }
            0x700 -> if (minor == 4) {
                // minor 4 = wrist watch
                Hit("SMART WATCH", Confidence.MEDIUM, "Class: wrist watch")
            } else {
                Hit("SMART WATCH", Confidence.LOW, "Class: wearable")
            }
            0x900 -> Hit("SENSOR", Confidence.LOW, "Class: health device")
            else -> null
        }
    }

    /** Extract 16-bit SIG number from a 128-bit BLE UUID string. */
    private fun uuid16(uuid: String): Int? {
        val m = Regex("^0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb$")
            .matchEntire(uuid.lowercase()) ?: return null
        return m.groupValues[1].toIntOrNull(16)
    }

    fun confidenceName(c: Confidence): String = when (c) {
        Confidence.HIGH -> "High"
        Confidence.MEDIUM -> "Medium"
        Confidence.LOW -> "Low"
    }

    /** Plain-English proximity from RSSI — a band, never an exact distance. */
    fun signalProximity(rssi: Int?): String = when {
        rssi == null -> "Unknown"
        rssi >= -60 -> "CLOSE"
        rssi >= -75 -> "NEAR"
        rssi >= -90 -> "FAR"
        else -> "VERY FAR"
    }

    /**
     * HELP IDENTIFY: walk every signal Android provided and narrate what each
     * one tells us, in normal language. Uses the same data as [identify] —
     * nothing new is measured — but explains the reasoning fully and states
     * plainly what IS known when the verdict is uncertain. Never invents.
     */
    fun explainDeep(s: DeviceSignals): List<String> {
        val out = mutableListOf<String>()
        val idn = identify(s)

        // Name.
        val name = s.name?.trim().orEmpty()
        if (name.isEmpty() || name.startsWith("BLE-") || name.startsWith("BT-")) {
            out += "The device isn't advertising a name — many gadgets stay silent " +
                "until you connect, so this tells us nothing either way."
        } else {
            out += "It's calling itself \"$name\"."
        }

        // Maker.
        if (s.manufacturerId != null) {
            val maker = companyNames[s.manufacturerId]
            val hex = "0x" + s.manufacturerId.toString(16).uppercase()
            if (maker != null) {
                out += "Its maker ID is $hex — that's $maker. This is a real clue " +
                    "about who built it, though not what kind of product it is."
            } else {
                out += "Its maker ID is $hex, which isn't in my list of known companies. " +
                    "It's a real manufacturer, just not one I recognize."
            }
            if (!s.manufacturerDataHex.isNullOrBlank()) {
                out += "It also broadcasts ${s.manufacturerDataHex.split(" ").size} bytes of " +
                    "maker data, but that data is proprietary — only ${maker ?: "the maker"}'s " +
                    "own app can read it."
            }
        } else {
            out += "No maker information in its advertisement."
        }

        // Services.
        val allServices = (s.advertisedServiceUuids + s.gattServices).distinct()
        if (allServices.isEmpty()) {
            out += "It advertises no service list, so I can't tell what it offers yet. " +
                "Connecting (if it allows it) would reveal more."
        } else {
            val named = allServices.mapNotNull { uuid ->
                val n = uuid16(uuid)
                if (n != null) serviceNames[n] ?: ("service 0x" + n.toString(16).uppercase())
                else "custom service ${uuid.take(8)}…"
            }
            out += "It offers: ${named.take(6).joinToString(", ")}${if (named.size > 6) " and ${named.size - 6} more" else ""}."
            if (s.gattServices.isNotEmpty()) {
                out += "That list came from a real connection, so it's the full picture — " +
                    "not just what it chose to advertise."
            }
        }

        // Bluetooth class.
        val dc = s.deviceClassFull
        if (dc != null && dc != 0) {
            val majorName = when (dc and 0x1F00) {
                0x100 -> "computer"
                0x200 -> "phone"
                0x400 -> "audio/video gadget"
                0x500 -> "input device (keyboard, mouse, or remote)"
                0x600 -> "imaging device (printer, scanner, or camera)"
                0x700 -> "wearable"
                0x900 -> "health gadget"
                else -> "device"
            }
            out += "Its Bluetooth device class says it's a $majorName."
        } else {
            out += "It doesn't report a Bluetooth device class — common for " +
                "low-energy gadgets, which often leave this blank."
        }

        // Connectability.
        when (s.connectable) {
            true -> out += "It's accepting connections right now, so you could connect " +
                "and look deeper with MAP INTERFACE."
            false -> out += "It's currently not accepting connections (it may be busy, " +
                "paired elsewhere, or broadcast-only like a beacon)."
            null -> { /* unknown — say nothing rather than guess */ }
        }

        // Signal.
        if (s.rssi != null) {
            out += "Signal is ${s.rssi} dBm — ${signalProximity(s.rssi).lowercase().replaceFirstChar { it.uppercase() }} " +
                "by, roughly. The signal alone can't say what it is."
        }

        // Verdict, stated plainly.
        out += if (idn.confidence == Confidence.LOW) {
            "Bottom line: I can't say what this is yet — " +
                if (idn.label.startsWith("UNKNOWN")) "not enough clues."
                else "best guess is ${idn.label.lowercase()}, but the evidence is thin. " +
                    "What I know for sure is listed above."
        } else {
            "Bottom line: likely ${idn.label.lowercase()} " +
                "(${confidenceName(idn.confidence).lowercase()} confidence). " +
                idn.evidence.joinToString(" ")
        }
        return out
    }

    /**
     * Public lookup into the maker table — used by the research step so it
     * reuses this table instead of duplicating it.
     */
    fun lookupCompanyName(companyId: Int): String? = companyNames[companyId]
}
