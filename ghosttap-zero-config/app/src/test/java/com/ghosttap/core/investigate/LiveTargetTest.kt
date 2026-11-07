package com.ghosttap.core.investigate

import com.ghosttap.core.ops.OperationRegistry
import com.ghosttap.core.ops.registerAreaAOperations
import com.ghosttap.core.ops.registerAreaBOperations
import com.ghosttap.core.ops.registerAreaHardwareOperations
import com.ghosttap.core.ops.registerAreaIntelOperations
import com.ghosttap.core.ops.registerAreaVnicOperations
import com.ghosttap.core.ops.registerNfcOperations
import com.ghosttap.core.ops.registerSeedOperations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the MASTER PROMPT live-target core. Pure Kotlin,
 * JUnit 4 — no Android framework, no network.
 *
 * Covers the hard rules: live targets dedup by stable key (never
 * stack duplicates); a re-check keeps the thinking-engine answer
 * only when the evidence is unchanged; routes and bring-up targets
 * come only from real identifiers (never a guessed label); every
 * command-center id resolves to a real registered operation; the
 * AI prompt carries only observed facts; the local fallback text
 * never claims to be the AI.
 */
class LiveTargetTest {

    private fun report(
        label: String = "AA:BB:CC:DD:EE:FF",
        kind: String = "Bluetooth device",
        targetId: String? = "ble:AA:BB:CC:DD:EE:FF",
        evidence: List<String> = listOf("It did not answer yet", "Signal: -70 dBm — Weak"),
        capabilities: List<InvestigatedControl> = emptyList()
    ) = FindingsReport(
        targetLabel = label,
        targetKind = kind,
        believedIdentity = "UNKNOWN",
        confidence = Confidence.LOW,
        evidence = evidence,
        capabilities = capabilities,
        risks = listOf(RiskNote(RiskLevel.HIGH, "This device is unknown")),
        connectLabel = "Connect to this device",
        stateChanging = false,
        answeredHandshake = false,
        targetId = targetId
    )

    @Before
    fun setUp() {
        LiveTargetStore.clear()
    }

    @Test
    fun keyUsesTargetIdWhenPresent() {
        assertEquals(
            "ble:AA:BB:CC:DD:EE:FF",
            liveTargetKey(report(), InvestigationSource.BLE)
        )
    }

    @Test
    fun keyFallsBackToSourceAndLabel() {
        assertEquals(
            "MANUAL:Something typed",
            liveTargetKey(report(label = "Something typed", targetId = null), InvestigationSource.MANUAL)
        )
    }

    @Test
    fun publishDedupsByKeyNewestFirst() {
        val first = LiveTargetStore.publish(report(), InvestigationSource.BLE)
        val second = LiveTargetStore.publish(
            report(label = "AA:BB:CC:DD:EE:FF"),
            InvestigationSource.BLE
        )
        assertEquals(first.key, second.key)
        assertEquals(1, LiveTargetStore.all().size)
        assertEquals(second.key, LiveTargetStore.all().first().key)
    }

    @Test
    fun publishKeepsReasoningWhenEvidenceUnchanged() {
        val published = LiveTargetStore.publish(report(), InvestigationSource.BLE)
        LiveTargetStore.setReasoning(published.key, "AI words", AiReasoningState.AI)
        val republished = LiveTargetStore.publish(report(), InvestigationSource.BLE)
        assertEquals("AI words", republished.aiText)
        assertEquals(AiReasoningState.AI, republished.aiState)
    }

    @Test
    fun publishResetsReasoningWhenEvidenceChanges() {
        val published = LiveTargetStore.publish(report(), InvestigationSource.BLE)
        LiveTargetStore.setReasoning(published.key, "AI words", AiReasoningState.AI)
        val republished = LiveTargetStore.publish(
            report(evidence = listOf("It answered when asked")),
            InvestigationSource.BLE
        )
        assertNull(republished.aiText)
        assertEquals(AiReasoningState.WAITING, republished.aiState)
    }

    @Test
    fun routesPointAtEachFunctionsPage() {
        assertEquals(
            "target/AA:BB:CC:DD:EE:FF",
            routeForTarget(report(), InvestigationSource.BLE)
        )
        assertEquals("usb_uart", routeForTarget(report(kind = "USB device"), InvestigationSource.USB))
        assertEquals("network_tools", routeForTarget(report(kind = "Network device"), InvestigationSource.NETWORK))
        assertEquals("nfc_tools", routeForTarget(report(kind = "NFC tag"), InvestigationSource.NFC))
        assertEquals("visual_intel", routeForTarget(report(kind = "Thing seen in a picture"), InvestigationSource.VISUAL))
        assertEquals("vesp32", routeForTarget(report(kind = "Virtual ESP32 device"), InvestigationSource.VIRTUAL_ESP32))
        assertNull(routeForTarget(report(kind = "Thing you picked", targetId = null), InvestigationSource.MANUAL))
    }

    @Test
    fun bringUpUsesRealIdentifiersOnly() {
        val ble = bringUpTargetFor(report())
        assertNotNull(ble)
        assertEquals("AA:BB:CC:DD:EE:FF", ble!!.id)
        // A plain name with no address: no bring-up is invented.
        assertNull(
            bringUpTargetFor(report(label = "Mystery speaker", targetId = null))
        )
        // A typed address as the label still works.
        val typed = bringUpTargetFor(
            report(label = "AA:BB:CC:DD:EE:FF", targetId = null)
        )
        assertNotNull(typed)
        assertEquals("AA:BB:CC:DD:EE:FF", typed!!.id)
        assertEquals("usb", bringUpTargetFor(report(kind = "USB device"))!!.id)
        assertEquals(
            "192.168.1.5",
            bringUpTargetFor(report(label = "192.168.1.5", kind = "Network device"))!!.id
        )
        // A network name that is not an address: no bring-up.
        assertNull(
            bringUpTargetFor(report(label = "MyRouter", kind = "Network device"))
        )
        // Pictures, tags, practice boards, typed things: no bring-up.
        assertNull(bringUpTargetFor(report(kind = "Thing seen in a picture")))
        assertNull(bringUpTargetFor(report(kind = "NFC tag")))
    }

    @Test
    fun everyCommandCenterIdResolvesToARealOperation() {
        registerSeedOperations()
        registerAreaAOperations()
        registerAreaBOperations()
        registerAreaHardwareOperations()
        registerAreaIntelOperations()
        registerNfcOperations()
        registerAreaVnicOperations()
        for (source in InvestigationSource.values()) {
            val ids = LiveTargetCommands.forSource(source, "ble:AA:BB:CC:DD:EE:FF")
            assertFalse("no commands for $source", ids.isEmpty())
            for (id in ids) {
                assertNotNull(
                    "command $id for $source is not a registered operation",
                    OperationRegistry.get(id)
                )
            }
        }
    }

    @Test
    fun wifiNetworkHasNoCommandsUntilJoined() {
        assertTrue(
            LiveTargetCommands.forSource(InvestigationSource.NETWORK, "wifi:MyNetwork").isEmpty()
        )
        assertFalse(
            LiveTargetCommands.forSource(InvestigationSource.NETWORK, "net:192.168.1.5").isEmpty()
        )
    }

    @Test
    fun aiPromptCarriesOnlyObservedFacts() {
        val prompt = aiPromptFor(report())
        assertTrue(prompt.contains("Signal: -70 dBm — Weak"))
        assertTrue(prompt.contains("do not invent anything"))
        assertFalse(prompt.contains("gpt"))
        assertFalse(prompt.contains("openai", ignoreCase = true))
    }

    @Test
    fun localReasoningNeverClaimsToBeAi() {
        val text = localReasoningText(report())
        assertFalse(text.contains("AI"))
        assertTrue(text.contains("the app itself"))
        assertEquals(
            "Checked by your AI (your key)",
            AiReasoningState.AI.plainLabel()
        )
        assertTrue(
            AiReasoningState.LOCAL_NO_KEY.plainLabel().contains("app's own rules")
        )
    }

    @Test
    fun failureReasonSurvivesRecheckWithUnchangedEvidence() {
        val published = LiveTargetStore.publish(report(), InvestigationSource.BLE)
        LiveTargetStore.setReasoning(
            published.key,
            "local words",
            AiReasoningState.LOCAL_AI_FAILED,
            AiFailureReason.KEY_REJECTED
        )
        val republished = LiveTargetStore.publish(report(), InvestigationSource.BLE)
        assertEquals(AiReasoningState.LOCAL_AI_FAILED, republished.aiState)
        assertEquals(AiFailureReason.KEY_REJECTED, republished.aiFailureReason)
    }

    @Test
    fun failureReasonClearsWhenEvidenceChanges() {
        val published = LiveTargetStore.publish(report(), InvestigationSource.BLE)
        LiveTargetStore.setReasoning(
            published.key,
            "local words",
            AiReasoningState.LOCAL_AI_FAILED,
            AiFailureReason.TIMEOUT
        )
        val republished = LiveTargetStore.publish(
            report(evidence = listOf("It answered when asked")),
            InvestigationSource.BLE
        )
        assertEquals(AiReasoningState.WAITING, republished.aiState)
        assertNull(republished.aiFailureReason)
    }

    @Test
    fun failureReasonsArePlainWordsWithNoCodesOrJargon() {
        for (reason in AiFailureReason.values()) {
            val words = reason.plainWords()
            assertFalse("reason must not show a status code: $words", words.contains("401"))
            assertFalse("reason must not show a status code: $words", words.contains("403"))
            assertFalse("reason must not show raw error text: $words", words.contains("Exception"))
            assertFalse("reason must not show raw error text: $words", words.contains("Socket"))
            assertTrue("reason must say something: $words", words.isNotBlank())
        }
    }

    @Test
    fun localFallbackForAddressOnlyTargetSaysUnknown() {
        val unknown = localReasoningText(
            report(
                label = "10.1.1.120",
                kind = "Network device",
                targetId = "net:10.1.1.120",
                evidence = listOf(
                    "Address seen on your Wi-Fi: 10.1.1.120",
                    "It answered when checked."
                )
            )
        )
        assertTrue(unknown.contains("not enough proof to name this one"))
        assertFalse(unknown.contains("it calls itself"))
    }
}
