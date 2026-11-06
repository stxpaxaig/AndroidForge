package com.ghosttap.core.ops

import com.ghosttap.core.DynamicCapabilityDef
import com.ghosttap.core.DynStep
import com.ghosttap.core.GhostIdentity
import com.ghosttap.core.IntelCapability
import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.IntelTarget
import com.ghosttap.core.ProcedureKinds
import com.ghosttap.core.RuleBasedReasoner
import com.ghosttap.core.ScriptDef
import com.ghosttap.core.buildExtensionDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Package 6 — every core-engines + investigations operation
 * (C-01–C-23, B-063–B-083, E-38–E-45, D-31) registers, with
 * plain-words titles and descriptions and no placeholder text.
 * Registration only constructs Operation data — no ViewModel, no
 * Android call — so this runs on the JVM like the other ops tests.
 * The pure helpers get their own tests: the C-03 wiring table, the
 * B-080 kind match, the C-22 directive, the U-05 restore round-trip,
 * and the C-16/C-17 draft builder.
 */
class Package6OperationsTest {

    private val expectedIds = listOf(
        // Look into this device + plan steps
        "intel.look-into-device", "intel.start-auto-investigate",
        "intel.stop-looking", "intel.run-auto-checks",
        "intel.approve-next-step", "intel.skip-this-step",
        "intel.make-new-plan", "intel.show-plan",
        // Case files
        "intel.open-saved-investigation", "intel.close-case-file",
        "intel.mark-case-done", "intel.delete-case-file",
        "intel.list-case-files",
        // Procedures
        "intel.save-this-plan", "intel.load-saved-plan",
        "intel.delete-saved-plan", "intel.save-what-worked",
        "intel.list-saved-plans",
        // History
        "intel.show-all-history", "intel.show-timeline",
        "intel.see-cycle-log",
        // Capability scan + wiring
        "intel.check-what-else", "intel.list-everything-it-can-do",
        "intel.run-any-control", "intel.how-controllable-is-this",
        "intel.how-to-add-a-control",
        // Device understanding
        "intel.what-is-this", "intel.what-did-it-just-say",
        "intel.best-part-to-try", "intel.what-can-i-do-with-this",
        "intel.name-these-parts", "intel.signal-in-plain-words",
        "intel.what-do-we-know-about-this",
        // Grow a new control
        "intel.suggest-new-control", "intel.review-new-control",
        "intel.approve-new-control", "intel.discard-new-control",
        "intel.list-grown-controls", "intel.check-saved-extras",
        // Thinking engine
        "intel.what-does-it-think", "intel.check-thinking-engine",
        "intel.check-online-brain", "intel.ask-the-brain",
        "intel.check-practice-board-link", "intel.what-are-the-rules",
        "intel.check-status",
        // Knowledge
        "intel.what-do-we-already-know", "intel.search-the-web-for-this",
        "intel.what-links-to-this", "intel.what-just-happened",
        "intel.refresh-lists",
        // Bring-up
        "intel.get-device-ready", "intel.is-it-still-ready",
        // Learned / logs
        "intel.what-did-we-learn-from-this", "intel.show-recent-log"
    )

    @Before
    fun setUp() {
        OperationRegistry.clear()
    }

    @After
    fun tearDown() {
        OperationRegistry.clear()
    }

    @Test
    fun allPackage6OperationsRegister() {
        registerAreaIntelOperations()
        assertEquals(55, expectedIds.size)
        expectedIds.forEach { id ->
            assertNotNull("missing operation: $id", OperationRegistry.get(id))
        }
    }

    @Test
    fun allPackage6OperationsHavePlainWordsCopy() {
        registerAreaIntelOperations()
        val banned = listOf("TODO", "placeholder", "not implemented")
        expectedIds.forEach { id ->
            val op = OperationRegistry.get(id)!!
            assertTrue("$id: blank title", op.title.isNotBlank())
            assertTrue("$id: blank description", op.description.isNotBlank())
            banned.forEach { word ->
                assertTrue(
                    "$id: '$word' in title/description",
                    word !in op.title && word !in op.description
                )
            }
        }
    }

    @Test
    fun allPackage6OperationsLiveInInvestigationsOrSystem() {
        registerAreaIntelOperations()
        expectedIds.forEach { id ->
            val op = OperationRegistry.get(id)!!
            assertTrue(
                "$id: unexpected area ${op.area}",
                op.area == OperationArea.INVESTIGATIONS || op.area == OperationArea.SYSTEM
            )
        }
    }

    // ----- C-03 wiring table -----

    @Test
    fun wiringTableMapsVerifiedMethods() {
        assertEquals("ble.disconnect-all", wiredOperationFor("GhostTapViewModel.disconnectAll"))
        assertEquals("ble.disconnect-all", wiredOperationFor("GhostTapViewModel.disconnectAll()"))
        assertEquals("net.ping-host", wiredOperationFor("NetworkToolsViewModel.ping(host: String)"))
        assertEquals("usb.find-speed", wiredOperationFor("UsbUartViewModel.autoBaud"))
        assertEquals("visual.save-last-photo", wiredOperationFor("VisualIntelViewModel.saveCurrentAsProfile"))
        assertEquals("ble.guided-learn", wiredOperationFor("GhostTapViewModel.saveDiscoveryAsAction"))
        assertEquals("spp.connect-checked", wiredOperationFor("GhostTapViewModel.connectSpp"))
        assertEquals("print.try-next-language", wiredOperationFor("GhostTapViewModel.reprobeOnePrinterLanguage"))
    }

    @Test
    fun wiringTableReturnsNullForUnknownMethods() {
        assertNull(wiredOperationFor("GhostTapViewModel.bleReadSomethingNew"))
        assertNull(wiredOperationFor("NfcViewModel.readTag"))
        assertNull(wiredOperationFor(""))
    }

    // ----- B-080 kind match -----

    @Test
    fun kindMatchAcceptsMatchingAndNeutralSteps() {
        assertTrue(ProcedureKinds.matches("BLE", listOf("BLE")))
        assertTrue(ProcedureKinds.matches("BLE", listOf("SYSTEM")))
        assertTrue(ProcedureKinds.matches("BLE", listOf("DYNAMIC", "SCRIPT")))
        assertTrue(ProcedureKinds.matches("BLE", listOf("BLE", "NETWORK")))
        assertTrue(ProcedureKinds.matches("BLE", emptyList()))
        assertTrue(ProcedureKinds.matches("MANUAL", listOf("NETWORK")))
        assertTrue(ProcedureKinds.matches("MANUAL", listOf("BLE")))
    }

    @Test
    fun kindMatchRefusesMismatchedKind() {
        assertFalse(ProcedureKinds.matches("BLE", listOf("NETWORK")))
        assertFalse(ProcedureKinds.matches("NFC", listOf("BLE", "USB")))
        assertFalse(ProcedureKinds.matches("NETWORK", listOf("VISUAL")))
    }

    // ----- C-22 directive -----

    @Test
    fun directiveMatchesAutonomousMode() {
        val directive = GhostIdentity.directive()
        assertTrue(directive.contains("runs on its own"))
        assertTrue(directive.contains("timeline"))
        assertFalse(directive.contains("Ask before changing"))
    }

    // ----- U-05 restore round-trip -----

    @Test
    fun dynamicCapabilityDefSurvivesJsonRoundTrip() {
        val def = DynamicCapabilityDef(
            id = "test.round-trip",
            title = "Round trip test",
            detail = "A control built from one read step.",
            category = "DYNAMIC",
            riskLevel = "READ",
            steps = listOf(DynStep(capabilityId = "test.ping-host"))
        )
        val restored = DynamicCapabilityDef.fromJson(DynamicCapabilityDef.toJson(def))
        assertNotNull(restored)
        assertEquals("test.round-trip", restored!!.id)
        assertEquals("Round trip test", restored.title)
        assertEquals(1, restored.steps.size)
        assertEquals("test.ping-host", restored.steps[0].capabilityId)
    }

    @Test
    fun scriptDefSurvivesJsonRoundTrip() {
        val def = ScriptDef(
            id = "test.script",
            title = "Script round trip",
            description = "A scripted control.",
            jsSource = "ghost.run(\"test.ping-host\", {})",
            risk = "READ"
        )
        val restored = ScriptDef.fromJson(ScriptDef.toJson(def))
        assertNotNull(restored)
        assertEquals("test.script", restored!!.id)
        assertEquals("ghost.run(\"test.ping-host\", {})", restored.jsSource)
    }

    // ----- C-16/C-17 draft builder -----

    @Test
    fun buildExtensionDraftDraftsFromExistingTools() = runBlocking {
        CapabilityRegistry.register(
            IntelCapability(
                id = "test.ping-host",
                title = "Ping a host",
                detail = "Ping a host to see if it answers.",
                category = "NETWORK",
                readOnly = true,
                riskLevel = "READ",
                sourceMethod = "test"
            )
        )
        val draft = buildExtensionDraft(
            IntelTarget(
                kind = "NETWORK",
                title = "Test host",
                text = "ping the host at 192.168.1.5"
            ),
            gapIds = setOf("test.missing"),
            reasoner = RuleBasedReasoner()
        )
        assertNotNull("expected a draft from the registered test control", draft)
        assertEquals("control", draft!!.kind)
        assertEquals("intel.propose_capability", draft.proposeId)
        assertTrue(draft.summary.contains("Ping a host"))
        assertTrue(draft.summary.contains("nothing is switched on until you approve it"))
    }

    @Test
    fun buildExtensionDraftReturnsNullWhenNothingFits() = runBlocking {
        val draft = buildExtensionDraft(
            IntelTarget(
                kind = "BLE",
                title = "Unknown thing",
                text = "zzzzqqqq unknown thing with no matching tools at all"
            ),
            gapIds = setOf("qqqq.zzzz.wwww"),
            reasoner = RuleBasedReasoner()
        )
        assertNull(draft)
    }
}
