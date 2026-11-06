package com.ghosttap.core.vnic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the autonomous-drive policy ([VnicDrivePolicy]) — pure
 * Kotlin, no network, no Android. Deterministic: every decision is made
 * against an explicit `nowMs` so the 60s cooldown is exact.
 */
class VnicDrivePolicyTest {

    private val target = "Investigate the ESP32-TEST-NODE-01 node over TCP"
    private val inv = "inv-1"

    @Test
    fun drivesUnresolvedTargetEvenWhenItsTransportIsUnfamiliar() {
        val p = VnicDrivePolicy()
        assertTrue(p.shouldDrive(inv, target, "RUNNING", nowMs = 1_000))
        assertTrue(p.shouldDrive(inv, "unrecognized appliance model QX-491", "RUNNING", nowMs = 1_000))
        assertTrue(p.shouldDrive(inv, target, "EXTENDING", nowMs = 1_000))
    }

    @Test
    fun neverDrivesFinishedOrWaitingRounds() {
        val p = VnicDrivePolicy()
        assertFalse(p.shouldDrive(inv, target, "COMPLETE", nowMs = 1_000))
        assertFalse(p.shouldDrive(inv, target, "DEAD_END", nowMs = 1_000))
        assertFalse(p.shouldDrive(inv, target, "WAITING_USER", nowMs = 1_000))
    }

    @Test
    fun unknownTargetIsDrivenAndOnlyBlankTextIsRejected() {
        val p = VnicDrivePolicy()
        assertTrue(p.shouldDrive(inv, "hello world, nothing recognized yet", "RUNNING", nowMs = 1_000))
        assertFalse(p.shouldDrive(inv, "   ", "RUNNING", nowMs = 1_000))
        assertFalse(p.shouldDrive(inv, "", "RUNNING", nowMs = 1_000))
    }

    @Test
    fun oneDriveAtATime() {
        val p = VnicDrivePolicy()
        assertTrue(p.shouldDrive(inv, target, "RUNNING", nowMs = 1_000))
        p.markDriveStarted(inv, target, nowMs = 1_000)
        assertTrue(p.driveRunning)
        assertFalse(p.shouldDrive(inv, "Investigate another ESP32 node", "RUNNING", nowMs = 2_000))
        p.markDriveFinished(inv, target, controllable = false)
        assertFalse(p.driveRunning)
    }

    @Test
    fun cooldownBlocksRedriveWithinSixtySeconds() {
        val p = VnicDrivePolicy()
        p.markDriveStarted(inv, target, nowMs = 10_000)
        p.markDriveFinished(inv, target, controllable = false)
        assertFalse(p.shouldDrive(inv, target, "RUNNING", nowMs = 10_000 + 30_000))
        assertFalse(p.shouldDrive(inv, target, "RUNNING", nowMs = 10_000 + 59_999))
        assertTrue(p.shouldDrive(inv, target, "RUNNING", nowMs = 10_000 + 60_000))
    }

    @Test
    fun controllableTargetIsNeverRedriven() {
        val p = VnicDrivePolicy()
        p.markDriveStarted(inv, target, nowMs = 10_000)
        p.markDriveFinished(inv, target, controllable = true)
        assertEquals(1, p.controllableCount())
        assertFalse(p.shouldDrive(inv, target, "RUNNING", nowMs = 10_000 + 3600_000))
        // A different target in the same investigation is still drivable.
        assertTrue(p.shouldDrive(inv, "Investigate the ESP32-TEST-NODE-02 node over TCP", "RUNNING", nowMs = 10_000 + 3600_000))
    }

    @Test
    fun cooldownIsPerInvestigationPlusTarget() {
        val p = VnicDrivePolicy()
        p.markDriveStarted(inv, target, nowMs = 10_000)
        p.markDriveFinished(inv, target, controllable = false)
        assertFalse(p.shouldDrive(inv, target, "RUNNING", nowMs = 20_000))
        assertTrue(p.shouldDrive("inv-2", target, "RUNNING", nowMs = 20_000))
    }
}
