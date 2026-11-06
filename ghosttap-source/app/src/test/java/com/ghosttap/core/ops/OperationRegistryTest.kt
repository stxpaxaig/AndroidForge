package com.ghosttap.core.ops

import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.OpResult
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
 * Unit tests for the operation registry and its CapabilityRegistry
 * bridge. Pure Kotlin, JUnit 4 — no Android framework, no ViewModels.
 * Test ids use the "test.pkg0." prefix so they can never collide with
 * real capability ids in the shared CapabilityRegistry singleton.
 */
class OperationRegistryTest {

    private fun testOp(
        id: String,
        area: OperationArea = OperationArea.SYSTEM,
        route: String? = null
    ): Operation = Operation(
        id = id,
        title = "Test: $id",
        description = "A test operation that always succeeds.",
        area = area,
        execute = { OpResult(true, "did it", "test evidence") },
        route = route
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
    fun registerAndLookupById() {
        val op = testOp("test.pkg0.lookup")
        OperationRegistry.register(op)
        assertEquals(op, OperationRegistry.get("test.pkg0.lookup"))
    }

    @Test
    fun lookupUnknownIdReturnsNull() {
        assertNull(OperationRegistry.get("test.pkg0.nope"))
    }

    @Test
    fun listReturnsAllInRegistrationOrder() {
        val first = testOp("test.pkg0.first")
        val second = testOp("test.pkg0.second")
        OperationRegistry.register(first)
        OperationRegistry.register(second)
        assertEquals(listOf(first, second), OperationRegistry.all())
    }

    @Test
    fun registeringSameIdKeepsLatest() {
        OperationRegistry.register(testOp("test.pkg0.dup"))
        val updated = testOp("test.pkg0.dup").copy(title = "Updated title")
        OperationRegistry.register(updated)
        assertEquals("Updated title", OperationRegistry.get("test.pkg0.dup")?.title)
        assertEquals(1, OperationRegistry.all().size)
    }

    @Test
    fun listByAreaFilters() {
        OperationRegistry.register(testOp("test.pkg0.ble", OperationArea.BLE))
        OperationRegistry.register(testOp("test.pkg0.net", OperationArea.NETWORK))
        OperationRegistry.register(testOp("test.pkg0.ble2", OperationArea.BLE))
        assertEquals(
            listOf("test.pkg0.ble", "test.pkg0.ble2"),
            OperationRegistry.byArea(OperationArea.BLE).map { it.id }
        )
        assertEquals(
            listOf("test.pkg0.net"),
            OperationRegistry.byArea(OperationArea.NETWORK).map { it.id }
        )
    }

    @Test
    fun areasListsFirstSeenOrder() {
        OperationRegistry.register(testOp("test.pkg0.a", OperationArea.NFC))
        OperationRegistry.register(testOp("test.pkg0.b", OperationArea.BLE))
        OperationRegistry.register(testOp("test.pkg0.c", OperationArea.NFC))
        assertEquals(
            listOf(OperationArea.NFC, OperationArea.BLE),
            OperationRegistry.areas()
        )
    }

    @Test
    fun asCapabilityKeepsIdTitleDetailAndCategory() {
        val op = testOp("test.pkg0.cap", OperationArea.NETWORK)
        val cap = op.asCapability(OperationContext())
        assertEquals("test.pkg0.cap", cap.id)
        assertEquals(op.title, cap.title)
        assertEquals(op.description, cap.detail)
        assertEquals("NETWORK", cap.category)
        assertTrue(cap.readOnly)
        assertEquals("READ", cap.riskLevel)
        assertFalse(cap.verified)
    }

    @Test
    fun asCapabilityForScreenOpNamesItsScreen() {
        val op = testOp("test.pkg0.screen", OperationArea.USB, route = "usb_uart")
        val cap = op.asCapability(OperationContext())
        assertTrue(cap.sourceMethod.contains("usb_uart"))
    }

    @Test
    fun publishMakesOperationsVisibleToCapabilityRegistry() {
        val op = testOp("test.pkg0.published", OperationArea.VISUAL)
        OperationRegistry.register(op)
        OperationRegistry.publishToCapabilityRegistry(OperationContext())
        val cap = CapabilityRegistry.get("test.pkg0.published")
        assertNotNull(cap)
        assertEquals("VISUAL", cap?.category)
        assertEquals(op.title, cap?.title)
    }

    @Test
    fun publishedCapabilityRunsTheOperation() = runBlocking {
        val op = testOp("test.pkg0.runnable")
        OperationRegistry.register(op)
        OperationRegistry.publishToCapabilityRegistry(OperationContext())
        val cap = CapabilityRegistry.get("test.pkg0.runnable")
        assertNotNull(cap)
        val result = cap!!.run(emptyMap())
        assertTrue(result.ok)
        assertEquals("test evidence", result.data)
    }

    @Test
    fun vnicOperationBridgePassesExactTargetChoiceWithoutMutatingUiContext() = runBlocking {
        val sharedContext = OperationContext()
        var receivedChoice = ""
        val op = Operation(
            id = "test.pkg0.target-choice",
            title = "Use selected device",
            description = "Test target propagation through the VNIC bridge.",
            area = OperationArea.BLE,
            preparation = listOf(
                PreparationStep("Resolve the selected device") { context ->
                    receivedChoice = context.pendingChoice.orEmpty()
                    PreparationOutcome.Ready
                }
            ),
            execute = { OpResult(true, "selected", "device=$receivedChoice") }
        )
        val cap = op.asCapability(sharedContext)

        assertEquals(listOf("targetId", "choice"), cap.inputs)
        val result = cap.run(mapOf("targetId" to "AA:BB:CC:DD"))

        assertTrue(result.ok)
        assertEquals("AA:BB:CC:DD", receivedChoice)
        assertNull("the shared UI context must remain untouched", sharedContext.pendingChoice)
    }
}
