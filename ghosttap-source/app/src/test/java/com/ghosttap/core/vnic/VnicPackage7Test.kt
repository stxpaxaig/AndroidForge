package com.ghosttap.core.vnic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Package 7 — the pure-Kotlin VNIC changes: the drive-policy pins and
 * telemetry counters survive a restart (snapshot / restore round-trip),
 * the remote-LLM fallback is labelled and really falls back, and the
 * concept graph grows from a real cycle (not just the 9 seeds).
 * All device identifiers here are synthetic — no real device.
 */
class VnicPackage7Test {

    // ---- Drive policy survives a restart (E-26) ----

    @Test
    fun drivePolicyPinsSurviveRestore() {
        val policy = VnicDrivePolicy()
        val target = "Investigate the ESP32-TEST-NODE-01 node over TCP"
        val inv = "inv-1"
        assertTrue(policy.shouldDrive(inv, target, "RUNNING", nowMs = 1_000))
        policy.markDriveStarted(inv, target, nowMs = 1_000)
        policy.markDriveFinished(inv, target, controllable = true)
        assertFalse(policy.shouldDrive(inv, target, "RUNNING", nowMs = 2_000))

        // A fresh policy restored from the snapshot pins the same target.
        val restored = VnicDrivePolicy()
        restored.restore(policy.controllableKeysSnapshot(), policy.lastDrivenSnapshot())
        assertFalse(restored.shouldDrive(inv, target, "RUNNING", nowMs = 2_000))
        assertEquals(1, restored.controllableCount())
    }

    @Test
    fun drivePolicyCooldownSurvivesRestore() {
        val policy = VnicDrivePolicy()
        val target = "bluetooth printer SYNTH-PRINTER-01"
        val inv = "inv-2"
        policy.markDriveStarted(inv, target, nowMs = 1_000)
        policy.markDriveFinished(inv, target, controllable = false)
        // Inside the 60s cooldown: no re-drive.
        assertFalse(policy.shouldDrive(inv, target, "RUNNING", nowMs = 30_000))

        val restored = VnicDrivePolicy()
        restored.restore(policy.controllableKeysSnapshot(), policy.lastDrivenSnapshot())
        assertFalse(restored.shouldDrive(inv, target, "RUNNING", nowMs = 30_000))
        // Past the cooldown: drivable again.
        assertTrue(restored.shouldDrive(inv, target, "RUNNING", nowMs = 61_001))
    }

    // ---- Telemetry survives a restart (E-10) ----

    @Test
    fun telemetryCountersSurviveRestore() {
        val telemetry = VnicTelemetry()
        telemetry.recordCycle(120)
        telemetry.recordObservation()
        telemetry.recordResearch(3)
        telemetry.recordExecution(VnicExecutionState.CONTROLLABLE)
        telemetry.recordProcedure(true)

        val restored = VnicTelemetry()
        restored.restore(telemetry.snapshot())
        val snap = restored.snapshot()
        assertEquals(1L, snap["cycles_run"])
        assertEquals(1L, snap["observations_seen"])
        assertEquals(1L, snap["research_queries"])
        assertEquals(3L, snap["findings_stored"])
        assertEquals(1L, snap["executions_connected"])
        assertEquals(1L, snap["executions_verified"])
        assertEquals(1L, snap["procedures_developed"])
        assertEquals(1L, snap["procedures_verified"])
        assertEquals(120L, snap["last_cycle_ms"])
    }

    // ---- Remote-LLM failures are explicit and never masquerade as local AI ----

    @Test
    fun remoteFailureIsExplicitAndDoesNotMasqueradeAsLocalDiscovery() = runBlocking {
        // Nothing listens on 127.0.0.1:9 — the request fails fast without
        // falling back to stale memory or local keyword hypotheses.
        val remote = RemoteLlmReasoner(
            VnicLlmConfig(
                endpoint = "http://127.0.0.1:9/v1/chat/completions",
                apiKey = "test-key-only",
                connectTimeoutMs = 200,
                readTimeoutMs = 200
            )
        )
        val hypotheses = remote.hypotheses("Found a Bluetooth device advertising as SYNTH-TARGET-01", emptyList())
        assertTrue(hypotheses.isNotEmpty())
        assertTrue(hypotheses.first().startsWith("OPENAI REQUEST FAILED:"))
        assertTrue(remote.lastError?.isNotBlank() == true)
        assertFalse(hypotheses.any { it.contains("This looks like a Bluetooth", ignoreCase = true) })
    }

    @Test
    fun configuredReasonerPicksUpKeyChangesWithoutRecreatingEngine() {
        var config = VnicLlmConfig(apiKey = "")
        val reasoner = ConfiguredVnicReasoner({ config })
        assertTrue(reasoner.name.startsWith("Built-in helper"))
        config = VnicLlmConfig(apiKey = "test-key-not-a-real-secret")
        assertTrue(reasoner.name.startsWith("OpenAI"))
        config = VnicLlmConfig(apiKey = "")
        assertTrue(reasoner.name.startsWith("Built-in helper"))
    }

    // ---- Concept graph grows from a real cycle (E-07 / F-09) ----

    private class EmptyResearcher : VnicResearchPort {
        override suspend fun research(query: String): VnicResearchReport =
            VnicResearchReport(query, emptyList(), listOf("stub"), 0, 1)
    }

    @Test
    fun cycleGrowsConceptGraphFromRealSubject() = runBlocking {
        val mem = InMemoryVnicMemory()
        val core = VnicCore(memory = mem, researcher = EmptyResearcher())
        core.process(
            VnicObservation("SYNTH-PRINTER-01", "find the printer SYNTH-PRINTER-01", "user"),
            capabilities = emptyList(),
            executor = null,
            bringUp = null,
            developProcedure = null
        )
        assertTrue(mem.searchConcepts("synth-printer-01", 5).isNotEmpty())
        assertTrue(mem.graph("synth-printer-01", 5).isNotEmpty())
    }

    // ---- Recent memories are readable (E-06 / E-28) ----

    @Test
    fun recentMemoriesAreNewestFirst() = runBlocking {
        val mem = InMemoryVnicMemory()
        mem.remember(VnicMemoryRecord(type = VnicMemoryClass.EPISODIC, content = "first event", ts = 1000))
        mem.remember(VnicMemoryRecord(type = VnicMemoryClass.EPISODIC, content = "second event", ts = 2000))
        val recent = mem.recent(5)
        assertEquals(2, recent.size)
        assertEquals("second event", recent.first().content)
        assertEquals("first event", recent.last().content)
    }
}
