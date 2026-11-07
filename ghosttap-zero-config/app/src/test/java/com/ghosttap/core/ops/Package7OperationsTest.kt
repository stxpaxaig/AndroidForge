package com.ghosttap.core.ops

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Package 7 — every VNIC operation (E-01–E-33, E-34–E-37) registers,
 * with plain-words titles and descriptions and no placeholder text.
 * Registration only constructs Operation data — no ViewModel, no
 * Android call — so this runs on the JVM like the other ops tests.
 */
class Package7OperationsTest {

    private val expectedIds = listOf(
        // Ask the brain + retry + open + dismiss (E-01, E-29, E-30, E-31)
        "vnic.ask-about-device", "vnic.retry-bring-up",
        "vnic.open-controls", "vnic.dismiss-ready",
        // Look it up + build a plan (E-14, E-23, E-30)
        "vnic.look-it-up", "vnic.build-a-plan",
        // Brain-saved plans (E-23, E-37, F-08, F-10)
        "vnic.use-saved-plan", "vnic.list-saved-plans",
        // What the brain knows / remembers / is doing (E-03–E-07, E-28, E-34–E-36)
        "vnic.show-what-it-knows", "vnic.show-what-it-remembers",
        "vnic.show-brain-activity",
        // Score, research setup, feedback, status (E-09–E-10, E-13, E-17, E-27, E-32, E-33)
        "vnic.show-score", "vnic.show-research-options",
        "vnic.say-that-was-right", "vnic.say-that-was-wrong",
        "vnic.check-brain-status"
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
    fun allPackage7OperationsRegister() {
        registerAreaVnicOperations()
        assertEquals(16, expectedIds.size)
        expectedIds.forEach { id ->
            assertNotNull("missing operation: $id", OperationRegistry.get(id))
        }
    }

    @Test
    fun allPackage7OperationsHavePlainWordsCopy() {
        registerAreaVnicOperations()
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
    fun allPackage7OperationsLiveInInvestigations() {
        registerAreaVnicOperations()
        expectedIds.forEach { id ->
            val op = OperationRegistry.get(id)!!
            assertTrue(
                "$id: unexpected area ${op.area}",
                op.area == OperationArea.INVESTIGATIONS
            )
        }
    }

    @Test
    fun allPackage7OperationIdsAreVnicNamespaced() {
        registerAreaVnicOperations()
        expectedIds.forEach { id ->
            assertTrue("$id: not vnic.*", id.startsWith("vnic."))
        }
    }
}
