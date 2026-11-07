package com.ghosttap.core.investigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the rule-based investigator. Pure Kotlin, JUnit 4 —
 * no Android framework, no network. Covers the hard rules: no invented
 * identities, no signal-to-distance conversion, no write controls
 * without an explicit hint, and risks that always reflect what is
 * actually known.
 */
class InvestigatorTest {

    private fun input(
        source: InvestigationSource = InvestigationSource.BLE,
        rawName: String? = null,
        rawDetails: Map<String, String> = emptyMap(),
        readableValues: List<String> = emptyList(),
        answeredHandshake: Boolean = false,
        signalText: String? = null
    ) = InvestigationInput(
        source = source,
        rawName = rawName,
        rawDetails = rawDetails,
        readableValues = readableValues,
        answeredHandshake = answeredHandshake,
        signalText = signalText
    )

    @Test
    fun emptyEvidenceIsUnknownWithLowConfidence() {
        val report = Investigator.investigate(input())

        assertEquals("UNKNOWN", report.believedIdentity)
        assertEquals(Confidence.LOW, report.confidence)
    }

    @Test
    fun namedBleDeviceWithServicesTakesIdentityFromName() {
        val report = Investigator.investigate(
            input(
                rawName = "Heart Rate Monitor",
                rawDetails = mapOf("service" to "heart rate"),
                answeredHandshake = true
            )
        )

        assertEquals("Heart Rate Monitor", report.believedIdentity)
        assertTrue(report.confidence != Confidence.LOW)
        assertEquals("Bluetooth device", report.targetKind)
    }

    @Test
    fun signalTextPassesThroughUnchangedAndNeverBecomesDistance() {
        val signal = "-86 dBm — Weak"
        val report = Investigator.investigate(input(signalText = signal))

        assertTrue(report.evidence.any { it.contains(signal) })
        val distanceWords = listOf("meter", "metre", "feet", "foot", "away", "distance", "cm ")
        for (line in report.evidence) {
            for (word in distanceWords) {
                assertFalse("Evidence must never contain distance text: $line", line.contains(word))
            }
        }
    }

    @Test
    fun writeControlsOnlyAppearWithExplicitWritableHint() {
        val withoutHint = Investigator.investigate(
            input(
                rawName = "Sensor",
                readableValues = listOf("23.5 °C")
            )
        )
        assertTrue(withoutHint.capabilities.all { it.readOnly })
        assertFalse(withoutHint.stateChanging)

        val withHint = Investigator.investigate(
            input(
                rawName = "Sensor",
                readableValues = listOf("23.5 °C"),
                rawDetails = mapOf("writable" to "true")
            )
        )
        val writes = withHint.capabilities.filter { !it.readOnly }
        assertEquals(1, writes.size)
        assertEquals(ControlKind.WRITE, writes.single().kind)
        assertTrue(withHint.stateChanging)

        val withSwitch = Investigator.investigate(
            input(rawName = "Lamp", rawDetails = mapOf("has_switch" to "true"))
        )
        val toggles = withSwitch.capabilities.filter { !it.readOnly }
        assertEquals(1, toggles.size)
        assertEquals(ControlKind.TOGGLE, toggles.single().kind)
    }

    @Test
    fun unknownDeviceAlwaysCarriesHighRiskNote() {
        val report = Investigator.investigate(input())

        assertTrue(
            report.risks.any {
                it.level == RiskLevel.HIGH &&
                    it.plainText.contains("unknown", ignoreCase = true)
            }
        )
    }

    @Test
    fun unansweredHandshakeCarriesUnprovenRiskNote() {
        val report = Investigator.investigate(
            input(rawName = "Sensor", answeredHandshake = false)
        )

        assertFalse(report.answeredHandshake)
        assertTrue(
            report.risks.any {
                it.level == RiskLevel.MEDIUM &&
                    it.plainText.contains("has not proven it answers yet")
            }
        )
        assertTrue(report.evidence.contains("It did not answer yet"))
    }

    @Test
    fun answeredHandshakeIsReportedAndRemovesUnprovenRisk() {
        val report = Investigator.investigate(
            input(rawName = "Sensor", answeredHandshake = true)
        )

        assertTrue(report.answeredHandshake)
        assertTrue(report.evidence.contains("It answered when asked"))
        assertFalse(
            report.risks.any { it.plainText.contains("has not proven it answers yet") }
        )
    }

    @Test
    fun addressAsNameStaysUnknownWithLowConfidence() {
        // An IP address says WHERE the thing is, never WHAT it is.
        // Even when it answered, it must not be named or trusted.
        val report = Investigator.investigate(
            input(
                source = InvestigationSource.NETWORK,
                rawName = "10.1.1.120",
                rawDetails = mapOf("ip" to "10.1.1.120"),
                readableValues = listOf(
                    "Address seen on your Wi-Fi: 10.1.1.120",
                    "It answered when checked."
                ),
                answeredHandshake = true
            )
        )

        assertEquals("UNKNOWN", report.believedIdentity)
        assertEquals(Confidence.LOW, report.confidence)
        // The address stays the label (which device this is), not the identity.
        assertEquals("10.1.1.120", report.targetLabel)
        assertTrue(
            report.risks.any {
                it.level == RiskLevel.HIGH &&
                    it.plainText.contains("unknown", ignoreCase = true)
            }
        )
    }

    @Test
    fun macAddressAsNameStaysUnknown() {
        val report = Investigator.investigate(
            input(rawName = "AA:BB:CC:DD:EE:FF", answeredHandshake = true)
        )

        assertEquals("UNKNOWN", report.believedIdentity)
        assertEquals(Confidence.LOW, report.confidence)
    }

    @Test
    fun namedDeviceWithHandshakeStaysHighConfidence() {
        val report = Investigator.investigate(
            input(rawName = "Heart Rate Monitor", answeredHandshake = true)
        )

        assertEquals("Heart Rate Monitor", report.believedIdentity)
        assertEquals(Confidence.HIGH, report.confidence)
    }
}
