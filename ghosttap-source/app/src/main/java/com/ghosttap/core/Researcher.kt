package com.ghosttap.core

import com.ghosttap.data.IntelDatabase
import com.ghosttap.data.LearnedStore

/**
 * One research result. Honest by construction:
 * - verified=true ONLY when the text is grounded in OBSERVED records
 *   (past investigations, learned controls) or the app's own identifier
 *   tables. Verified findings are facts.
 * - verified=false means "a lead, not a fact" — the intelligence may use it
 *   to pick the next probe, but must never present it as true.
 */
data class Finding(
    val text: String,
    val source: String,
    val confidence: Double,
    val verified: Boolean
)

/**
 * On-device research for the investigation loop. There is deliberately NO web
 * search backend on the phone — this researcher gathers only from evidence
 * already on the device, and says plainly where each finding came from.
 *
 * Sources, in order:
 * 1. Prior investigations (OBSERVED records — verified).
 * 2. Learned controls keyed by MAC (OBSERVED captures — verified).
 * 3. The app's own identifier tables: DeviceClassifier's company table and
 *    SigUuids (verified).
 * 4. The optional remote AI engine, if the user configured one (unverified,
 *    confidence capped at 0.5, source labeled as such).
 */
object Researcher {

    private val macRegex = Regex("(?i)\\b([0-9A-F]{2}[:-]){5}[0-9A-F]{2}\\b")
    private val uuid128Regex =
        Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    private val shortUuidRegex = Regex("(?i)\\b0x([0-9a-f]{4})\\b")
    private val companyIdRegex =
        Regex("(?i)(?:company|manufacturer)(?:[_ ]?id)?[:\\s]+(0x[0-9a-f]{1,4}|\\d{1,5})")

    suspend fun research(
        target: IntelTarget,
        db: IntelDatabase,
        learned: LearnedStore,
        remote: ReasoningProvider?
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        val hay = buildString {
            append(target.title).append('\n')
            append(target.text).append('\n')
            append(target.markings.joinToString("\n"))
        }.take(4000)

        // 1. Prior investigations that mention the same keywords/identifiers.
        val seenInv = mutableSetOf<String>()
        for (kw in keywordsOf(target).take(6)) {
            try {
                for (inv in db.investigationDao().search(kw).take(3)) {
                    if (seenInv.add(inv.id)) {
                        out.add(
                            Finding(
                                text = "Past investigation \"${inv.title}\" " +
                                    "(${inv.targetKind}): ${inv.targetText.take(200)}",
                                source = "prior investigation '${inv.title}'",
                                confidence = 0.9,
                                verified = true
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // One bad keyword must never break research.
            }
        }

        // 2. Learned controls captured from real traffic with this MAC.
        val macs = macRegex.findAll(hay).map { it.value }.distinct().take(3).toList()
        for (mac in macs) {
            val actions = try {
                learned.actionsFor(mac).ifEmpty { learned.actionsFor(mac.uppercase()) }
            } catch (_: Exception) {
                emptyList()
            }
            for (a in actions.take(5)) {
                out.add(
                    Finding(
                        text = "Learned control '${a.name}' for $mac: " +
                            "${a.reason.take(160)} (packet ${a.packetHex.take(60)})",
                        source = "learned controls",
                        confidence = 0.9,
                        verified = true
                    )
                )
            }
        }

        // 3. The app's own identifier tables — never duplicated here.
        for (u in uuid128Regex.findAll(hay).map { it.value.lowercase() }.distinct().take(5)) {
            val name = SigUuids.services[u]
            if (name != null) {
                out.add(
                    Finding(
                        text = "Bluetooth identifier $u is \"$name\" " +
                            "(standard SIG assignment).",
                        source = "device identifier table",
                        confidence = 1.0,
                        verified = true
                    )
                )
            }
        }
        for (m in shortUuidRegex.findAll(hay).map { it.groupValues[1] }.distinct().take(5)) {
            val full = "0000" + m.lowercase() + "-0000-1000-8000-00805f9b34fb"
            val name = SigUuids.services[full]
            if (name != null) {
                out.add(
                    Finding(
                        text = "Service 0x${m.uppercase()} is \"$name\" " +
                            "(standard SIG assignment).",
                        source = "device identifier table",
                        confidence = 1.0,
                        verified = true
                    )
                )
            }
        }
        for (m in companyIdRegex.findAll(hay).take(3)) {
            val raw = m.groupValues[1]
            val id = if (raw.startsWith("0x", ignoreCase = true)) {
                raw.substring(2).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            val name = id?.let { DeviceClassifier.lookupCompanyName(it) }
            if (name != null) {
                out.add(
                    Finding(
                        text = "Maker ID $raw belongs to \"$name\".",
                        source = "device identifier table",
                        confidence = 1.0,
                        verified = true
                    )
                )
            }
        }

        // 4. Remote AI background — a lead, never a fact.
        if (remote is RemoteLlmReasoner && remote.configured) {
            val topic = buildString {
                append("Target: ${target.title} (${target.kind}). ")
                val ids = (macs +
                    uuid128Regex.findAll(hay).map { it.value }.distinct().take(3).toList())
                    .distinct().take(5)
                if (ids.isNotEmpty()) append("Identifiers seen: ${ids.joinToString(", ")}. ")
                append("Known so far: ${out.take(3).joinToString(" | ") { it.text.take(120) }}")
            }.take(1200)
            val answer = try {
                remote.backgroundOn(topic)
            } catch (_: Exception) {
                ""
            }
            if (answer.isNotBlank()) {
                out.add(
                    Finding(
                        text = answer.take(600),
                        source = "remote AI (unverified)",
                        confidence = 0.5,
                        verified = false
                    )
                )
            }
        }

        return out.take(20)
    }

    /**
     * Full research pass: the on-device sources first, then real web research.
     * The existing [research] signature and behavior are unchanged — this is
     * the additive entry the investigation loop calls. Web findings are always
     * unverified (see WebResearcher); they join the local findings as leads.
     */
    suspend fun researchWithWeb(
        target: IntelTarget,
        db: IntelDatabase,
        learned: LearnedStore,
        remote: ReasoningProvider?,
        investigationId: String
    ): List<Finding> {
        val base = research(target, db, learned, remote)
        val web = try {
            val q = webQueryFor(target)
            if (q.isBlank()) emptyList()
            else WebResearcher.research(q, db, investigationId)
        } catch (_: Exception) {
            emptyList()
        }
        val seen = base.map { it.text }.toMutableSet()
        return (base + web.filter { seen.add(it.text) }).take(25)
    }

    /** Short search query from the target's own words and identifiers. */
    private fun webQueryFor(target: IntelTarget): String {
        val parts = mutableListOf<String>()
        parts.add(target.title.take(80))
        parts.add(target.markings.take(2).joinToString(" "))
        when (target.kind) {
            "BLE" -> parts.add("bluetooth")
            "NFC" -> parts.add("nfc tag")
            "NETWORK" -> parts.add("network device")
            "USB" -> parts.add("usb serial")
        }
        return parts.joinToString(" ").trim().take(140).trim()
    }

    /**
     * Plain-English list of what evidence would help the investigation next.
     * Used by the loop to explain dead ends and pick research directions.
     */
    fun missingInfo(target: IntelTarget, findings: List<Finding>): List<String> {
        val out = mutableListOf<String>()
        val hay = (target.title + "\n" + target.text).lowercase()
        val hasMac = macRegex.containsMatchIn(hay)
        val hasIp = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").containsMatchIn(hay)
        val hasUuid = uuid128Regex.containsMatchIn(hay) || shortUuidRegex.containsMatchIn(hay)
        if (!hasMac && target.kind != "NETWORK") {
            out.add("No hardware address seen yet — a Bluetooth scan would provide one.")
        }
        if (!hasIp && (target.kind == "NETWORK" || hay.contains("wifi") || hay.contains("network"))) {
            out.add("No IP address seen yet — SCAN MY NETWORK on the network tools screen would find local devices.")
        }
        if (!hasUuid && target.kind == "BLE") {
            out.add("No service identifiers seen yet — connecting and listing services would provide them.")
        }
        if (findings.isEmpty()) {
            out.add("Almost no evidence yet — add a photo, a scan result, or a few words describing the target.")
        } else if (findings.none { it.verified }) {
            out.add("Everything so far is unverified — one successful probe would confirm or deny it.")
        }
        return out
    }

    /** Search keywords: identifiers plus meaningful words from the title/text. */
    private fun keywordsOf(target: IntelTarget): List<String> {
        val keys = mutableListOf<String>()
        val hay = target.title + "\n" + target.text + "\n" + target.markings.joinToString("\n")
        macRegex.findAll(hay).mapTo(keys) { it.value }
        Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").findAll(hay).mapTo(keys) { it.value }
        hay.split(Regex("[^A-Za-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 5 }
            .distinct()
            .take(8)
            .forEach { keys.add(it) }
        return keys.distinct()
    }
}
