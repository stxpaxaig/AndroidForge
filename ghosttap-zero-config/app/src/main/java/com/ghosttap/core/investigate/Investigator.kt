package com.ghosttap.core.investigate

/**
 * Rule-based investigator for Ghost Tap's Autonomous Investigations.
 *
 * Pure Kotlin: no network, no API keys, no Android framework imports.
 *
 * Hard rules, enforced here rather than left to callers:
 * - The believed identity comes ONLY from the evidence in the input.
 *   When the evidence is not enough to name the target, the identity
 *   is "UNKNOWN" and confidence is LOW. No name, model, or brand is
 *   ever invented.
 * - Signal text is passed through verbatim. It is never converted
 *   into a distance.
 * - A target is only said to have answered when [InvestigationInput.answeredHandshake]
 *   is true.
 * - WRITE / TOGGLE / ACTION controls only appear when the input
 *   carries an explicit, real capability hint. Nothing is invented
 *   from a readable value alone.
 */
object Investigator {

    fun investigate(input: InvestigationInput): FindingsReport {
        val name = input.rawName?.trim().orEmpty()
        val hasName = name.isNotEmpty()

        // ---- identity: from the device's own announced name, or UNKNOWN ----
        // An address is NOT a name: an IP or hardware address only says
        // WHERE the thing is, never WHAT it is. It stays the label (so
        // the card still shows which device this is) but never becomes
        // the believed identity — UNKNOWN is preferable to a bad guess.
        val believedIdentity = if (hasName && !isAddressLike(name)) name else "UNKNOWN"

        // ---- corroborating evidence (services, protocol, readings) ----
        val serviceHint = input.rawDetails["service"]
            ?: input.rawDetails["services"]
        val protocolHint = input.rawDetails["protocol"]
        val tagTypeHint = input.rawDetails["tag_type"]
        val hasCorroboration = !serviceHint.isNullOrBlank() ||
            !protocolHint.isNullOrBlank() ||
            !tagTypeHint.isNullOrBlank() ||
            input.readableValues.isNotEmpty()

        // No identity behind it means low confidence — never "Pretty
        // sure" about a thing we could not name, however loudly it
        // answered. A handshake proves it answers, not what it is.
        val confidence = when {
            believedIdentity == "UNKNOWN" -> Confidence.LOW
            input.answeredHandshake -> Confidence.HIGH
            hasCorroboration -> Confidence.MEDIUM
            else -> Confidence.MEDIUM
        }

        // ---- evidence lines, in plain words ----
        val evidence = mutableListOf<String>()
        if (input.answeredHandshake) {
            evidence.add("It answered when asked")
        } else {
            evidence.add("It did not answer yet")
        }
        if (input.readableValues.isNotEmpty()) {
            evidence.add("It shows these readings: " + input.readableValues.joinToString(", "))
        }
        if (!serviceHint.isNullOrBlank()) {
            evidence.add("It offers this service: $serviceHint")
        }
        if (!protocolHint.isNullOrBlank()) {
            evidence.add("It talks over: $protocolHint")
        }
        if (!tagTypeHint.isNullOrBlank()) {
            evidence.add("Tag type: $tagTypeHint")
        }
        // Signal text passes through exactly as supplied — never a distance.
        if (!input.signalText.isNullOrBlank()) {
            evidence.add("Signal: ${input.signalText}")
        }

        // ---- capabilities: reads from evidence, writes only from explicit hints ----
        val capabilities = mutableListOf<InvestigatedControl>()
        for (value in input.readableValues) {
            capabilities.add(
                InvestigatedControl(
                    label = value,
                    plainDescription = "Shows this reading",
                    kind = ControlKind.READ,
                    readOnly = true
                )
            )
        }
        if (input.rawDetails["writable"]?.trim()?.equals("true", ignoreCase = true) == true) {
            capabilities.add(
                InvestigatedControl(
                    label = "Change a setting",
                    plainDescription = "Changes something on the device — only use it if you mean to",
                    kind = ControlKind.WRITE,
                    readOnly = false
                )
            )
        }
        if (input.rawDetails["has_switch"]?.trim()?.equals("true", ignoreCase = true) == true) {
            capabilities.add(
                InvestigatedControl(
                    label = "Switch it on or off",
                    plainDescription = "Turns something on the device on or off — only use it if you mean to",
                    kind = ControlKind.TOGGLE,
                    readOnly = false
                )
            )
        }

        // ---- risks: always present, driven by what is actually known ----
        val risks = mutableListOf<RiskNote>()
        if (believedIdentity == "UNKNOWN") {
            risks.add(
                RiskNote(
                    level = RiskLevel.HIGH,
                    plainText = "This device is unknown — connecting could do something unexpected"
                )
            )
        }
        if (capabilities.any { !it.readOnly }) {
            risks.add(
                RiskNote(
                    level = RiskLevel.MEDIUM,
                    plainText = "Some controls change the device — only use one if you mean to"
                )
            )
        }
        if (!input.answeredHandshake) {
            risks.add(
                RiskNote(
                    level = RiskLevel.MEDIUM,
                    plainText = "It has not proven it answers yet — treat what it shows as unproven"
                )
            )
        }

        return FindingsReport(
            targetLabel = if (hasName) name else "Unknown device",
            targetKind = targetKindFor(input.source),
            believedIdentity = believedIdentity,
            confidence = confidence,
            evidence = evidence,
            capabilities = capabilities,
            risks = risks,
            connectLabel = "Connect to this device",
            stateChanging = capabilities.any { !it.readOnly },
            answeredHandshake = input.answeredHandshake,
            targetId = input.targetId
        )
    }

    /**
     * An IP address or hardware (MAC) address: a location, never a
     * name. A device announcing "10.1.1.120" has told us where it
     * is, not what it is.
     */
    private fun isAddressLike(name: String): Boolean {
        if (name.isBlank()) return false
        if (Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(name)) return true
        if (Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$").matches(name)) return true
        // IPv6: only hex digits and colons, with at least two colons.
        if (name.count { it == ':' } >= 2 &&
            name.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
        ) return true
        return false
    }

    private fun targetKindFor(source: InvestigationSource): String = when (source) {        InvestigationSource.BLE -> "Bluetooth device"
        InvestigationSource.USB -> "USB device"
        InvestigationSource.NFC -> "NFC tag"
        InvestigationSource.NETWORK -> "Network device"
        InvestigationSource.VISUAL -> "Thing seen in a picture"
        InvestigationSource.VIRTUAL_ESP32 -> "Virtual ESP32 device"
        InvestigationSource.MANUAL -> "Thing you picked"
    }
}
