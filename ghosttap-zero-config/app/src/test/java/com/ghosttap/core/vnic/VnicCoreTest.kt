package com.ghosttap.core.vnic

import com.ghosttap.core.CapabilityRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure-Kotlin VNIC core, ported faithfully from vnic.py.
 * Reference values below were produced by the actual Python source
 * (random.Random(731991), hashlib.blake2b, SparseNeuralMesh,
 * VirtualNeuralIntelligenceCore.process) on 2026-11-01. These run on the
 * JVM with JUnit 4 — no Android framework, no network (live-network
 * research is validated separately by the replay harness, test A).
 *
 * All device identifiers in this file are synthetic (SYNTH-TARGET-01,
 * fixture provider, example.invalid) — no real device, finding, or URL.
 * The BLAKE2b vector for "SYNTH-TARGET-01" was recomputed from vnic.py
 * (hashlib.blake2b, digest_size=32) when the identifiers were replaced.
 */
class VnicCoreTest {

    // ---- MT19937: must match CPython's random.Random(731991) ----

    @Test
    fun mersenneTwisterMatchesCPython() {
        val rng = Mt19937(731991L)
        val expected = listOf(0.20205771994693067, 0.3389713041455664, 0.8478223346159577, 0.35134168605199545, 0.9464619851453376)
        expected.forEach { e -> assertEquals(e, rng.nextDouble(), 1e-15) }
    }

    @Test
    fun randrangeMatchesCPython() {
        val rng = Mt19937(731991L)
        val expected = listOf(1655, 787, 2776, 2454, 4094, 2878, 2331, 2936)
        expected.forEach { e -> assertEquals(e, rng.randrange(4096)) }
    }

    @Test
    fun uniformMatchesCPython() {
        val rng = Mt19937(731991L)
        val expected = listOf(0.1106173159840792, 0.15169139124366993, 0.3043467003847873)
        expected.forEach { e -> assertEquals(e, rng.uniform(0.05, 0.35), 1e-15) }
    }

    // ---- BLAKE2b: must match CPython's hashlib.blake2b(digest_size=32) ----

    @Test
    fun blake2bMatchesCPython() {
        assertEquals(
            "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
            Blake2b.digest(ByteArray(0), 32).joinToString("") { "%02x".format(it) }
        )
        assertEquals(
            "324dcf027dd4a30a932c441f365a25e86b173defa4b8e58948253471b81b72cf",
            Blake2b.digest("hello".toByteArray(Charsets.UTF_8), 32).joinToString("") { "%02x".format(it) }
        )
        assertEquals(
            "1ab4aed37fd87ea0d648e4d597715a7383a6c7857eaf74845095eb2075aed561",
            Blake2b.digest("SYNTH-TARGET-01".toByteArray(Charsets.UTF_8), 32).joinToString("") { "%02x".format(it) }
        )
        assertEquals(
            "6b6e59aaf00eb730cf93de53560846722184bbd92f8368c21ffa95380c2f9fe6",
            Blake2b.digest(ByteArray(200) { 'a'.code.toByte() }, 32).joinToString("") { "%02x".format(it) }
        )
        assertEquals(
            "f55e2a6db7e6093e5dbd1bc7ee4027e7334d804518fb15e7640df1373c4c2741",
            Blake2b.digest("ESP32 Bluetooth Low Energy GATT".toByteArray(Charsets.UTF_8), 32).joinToString("") { "%02x".format(it) }
        )
    }

    // ---- Mesh (real Python structure) ----

    @Test
    fun meshHasExpectedSizeFanoutAndSynapses() {
        val mesh = VnicMesh()
        assertEquals(4096, mesh.units)
        assertEquals(12, mesh.fanout)
        assertEquals(49152, mesh.synapseCount())
        assertTrue(mesh.edges.all { it.size == 12 })
    }

    @Test
    fun meshTargetsMatchCPythonForUnitZero() {
        // The target SET is stream-determined and must match Python exactly.
        val mesh = VnicMesh()
        assertEquals(
            listOf(679, 787, 1321, 1655, 2331, 2454, 2776, 2878, 2936, 3589, 3914, 4094),
            mesh.edges[0].keys.sorted()
        )
    }

    @Test
    fun meshWeightsAndThresholdsInPythonRanges() {
        val mesh = VnicMesh()
        assertTrue(mesh.edges.all { row -> row.values.all { it in 0.05..0.35 } })
        assertTrue(mesh.threshold.all { it in 0.42..0.67 })
    }

    @Test
    fun meshIsDeterministic() {
        val a = VnicMesh()
        val b = VnicMesh()
        assertEquals(a.edges[0].keys, b.edges[0].keys)
        assertEquals(a.threshold[0], b.threshold[0], 0.0)
        assertEquals(a.edges[100], b.edges[100])
    }

    @Test
    fun meshActivateSeedsEightAndFiresSeeds() {
        val mesh = VnicMesh()
        val result = mesh.activate("SYNTH-TARGET-01")
        assertEquals(8, result.seedUnits)
        // Every seed is boosted to 0.75, above every threshold (max 0.67),
        // so all 8 seeds fire in round 1 and push 8*12 = 96 propagations.
        assertEquals(8, result.firedUnits)
        assertEquals(96L, result.propagationEvents)
        assertEquals(8.0 / 4096.0, result.activeFraction, 1e-9)
    }

    @Test
    fun meshReinforceUpdatesFirst256RowsOnly() {
        val mesh = VnicMesh()
        assertEquals(3072, mesh.reinforce(1.0))
        assertEquals(3072L, mesh.plasticity)
        assertTrue(mesh.edges.all { row -> row.values.all { it in 0.001..1.0 } })
    }

    @Test
    fun meshSnapshotCounts() {
        val mesh = VnicMesh()
        mesh.activate("SYNTH-TARGET-01")
        val snap = mesh.snapshot()
        assertEquals(8_700_000_000L, snap.logicalNeurons)
        assertEquals(87_400_000_000L, snap.logicalSynapses)
        assertEquals(4096, snap.materializedUnits)
        assertEquals(49152, snap.materializedSparseSynapses)
        assertEquals(8L, snap.totalFiringEvents)
        assertEquals(96L, snap.propagationEvents)
    }

    // ---- Memory (real Python semantics) ----

    @Test
    fun memoryRecallOrdersByStrengthThenRecency() = runBlocking {
        val mem = InMemoryVnicMemory()
        mem.remember(VnicMemoryRecord(type = VnicMemoryClass.SEMANTIC, content = "ESP32 wifi chip", confidence = 0.3, ts = 3000))
        mem.remember(VnicMemoryRecord(type = VnicMemoryClass.SEMANTIC, content = "ESP32 bluetooth radio", confidence = 0.9, ts = 1000))
        mem.remember(VnicMemoryRecord(type = VnicMemoryClass.SEMANTIC, content = "Unrelated cooking recipe", confidence = 1.0, ts = 2000))
        val hits = mem.recall("ESP32", 5)
        assertEquals(2, hits.size)
        assertTrue(hits[0].content.contains("bluetooth")) // higher strength first, despite older ts
        assertEquals(1, mem.accesses(hits[0].id))
    }

    @Test
    fun memoryConceptsLowercasedAndGraph() = runBlocking {
        val mem = InMemoryVnicMemory()
        mem.addConcept(VnicConcept(name = "ESP32", definition = "Wi-Fi/BLE microcontroller", confidence = 0.9))
        mem.relate(VnicRelation(fromConcept = "ESP32", toConcept = "Bluetooth", relation = "supports", strength = 0.9))
        assertEquals("Wi-Fi/BLE microcontroller", mem.getConcept("esp32")!!.definition)
        assertEquals(1, mem.graph("ESP32", 5).size)
        // Re-relating bumps the weight by 0.05, capped at 1.0.
        mem.relate(VnicRelation(fromConcept = "ESP32", toConcept = "Bluetooth", relation = "supports", strength = 0.9))
        assertEquals(0.95, mem.graph("esp32", 5)[0].strength, 1e-9)
    }

    @Test
    fun memoryProceduresTrackOutcomes() = runBlocking {
        val mem = InMemoryVnicMemory()
        mem.saveProcedure(VnicProcedure(name = "ble.probe", stepsJson = "[\"ble.scan\"]"))
        mem.recordProcedureOutcome("ble.probe", true)
        mem.recordProcedureOutcome("ble.probe", true)
        mem.recordProcedureOutcome("ble.probe", false)
        val p = mem.getProcedure("ble.probe")!!
        assertEquals(2, p.successes)
        assertEquals(1, p.failures)
        assertEquals(2.0 / 3.0, p.successRate, 0.001)
    }

    // ---- Interpret (Python rules) ----

    @Test
    fun interpretDetectsIntent() {
        val core = VnicCore(memory = InMemoryVnicMemory())
        assertEquals("question", core.interpret("What is this device?").intent)
        assertEquals("instruction", core.interpret("Investigate the SYNTH-TARGET-01 device").intent)
        assertEquals("statement", core.interpret("The SYNTH-TARGET-01 is a synthetic test target").intent)
        assertEquals(12, core.interpret("one two three four five six seven eight nine ten eleven twelve thirteen fourteen").keywords.size)
    }

    // ---- Research parsing (offline fixtures, no network) ----

    @Test
    fun htmlToTextStripsTagsAndDecodesEntities() {
        val html = "<html><head><title>T</title><style>.a{}</style></head><body><script>var x=1;</script><p>Hello &amp; <b>world</b> &#x27;quoted&#x27;</p></body></html>"
        assertEquals("Hello & world 'quoted'", VnicHtml.toText(html))
    }

    @Test
    fun jsonStringFieldExtractsEscapes() {
        val json = """{"title":"ESP32 \"DevKit\"","snippet":"a & b"}"""
        assertEquals("ESP32 \"DevKit\"", VnicJson.stringField(json, "title"))
        assertEquals(2, VnicJson.allStringFields("""{"title":"a","title":"b"}""", "title").size)
    }

    // ---- Reasoning ----

    @Test
    fun localReasonerHypothesesForBle() {
        val r = LocalRuleReasoner()
        val h = r.hypotheses("Found a Bluetooth device advertising as SYNTH-TARGET-01", emptyList())
        assertTrue(h.any { it.contains("Bluetooth", ignoreCase = true) })
        assertTrue(r.planFor("scan bluetooth devices", listOf("ble.scan", "net.ping", "usb.probe")).contains("ble.scan"))
    }

    // ---- Allow-list (Package 7 / E-11 / F-18) ----
    //
    // The old VnicToolRegistry was removed: it was constructed empty and
    // process() never called it. The allow-list is the CapabilityRegistry
    // — execution runs only ids registered there. An unregistered id
    // resolves to null and is refused, never improvised.

    @Test
    fun allowListRefusesUnregisteredCapabilityIds() {
        assertNull(CapabilityRegistry.get("vnic.definitely.not.registered.anywhere"))
    }

    // ---- Cognition pipeline (Python decision rules) ----

    private class StubResearcher(private val findings: List<VnicFinding>) : VnicResearchPort {
        override suspend fun research(query: String): VnicResearchReport =
            VnicResearchReport(query, findings, listOf("stub"), 0, 1)
    }

    private class FakeExecutor(private val outcome: VnicExecutionOutcome) : VnicExecutor {
        var calledWith: String? = null
        override suspend fun execute(capabilityId: String, args: Map<String, String>): VnicExecutionOutcome {
            calledWith = capabilityId
            return outcome
        }
    }

    @Test
    fun unknownInstructionWithNoFindingsIsResearchRequired() = runBlocking {
        val mem = InMemoryVnicMemory()
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()))
        val result = core.process(VnicObservation("ZXQ-9999", "Investigate the ZXQ-9999 gadget"))
        assertEquals("research_required", result.action)
        assertEquals(0.25, result.confidence, 1e-9)
        assertEquals(VnicExecutionState.IDENTIFIED, result.executionState)
        assertEquals(8, result.neuralActivity!!.seedUnits)
        assertEquals("instruction", result.interpretation!!.intent)
        assertTrue(mem.recall("ZXQ-9999", 5).isNotEmpty())
    }

    @Test
    fun statementIsStoreContext() = runBlocking {
        val mem = InMemoryVnicMemory()
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()))
        val result = core.process(VnicObservation("SYNTH-TARGET-01", "The SYNTH-TARGET-01 is a synthetic test target"))
        assertEquals("store_context", result.action)
        assertEquals(VnicExecutionState.IDENTIFIED, result.executionState)
    }

    @Test
    fun priorMemoryMakesAnswerFromContext() = runBlocking {
        val mem = InMemoryVnicMemory()
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()))
        // First cycle: no prior memory -> research_required (stub: no findings).
        val first = core.process(VnicObservation("x", "Investigate the neural mesh"))
        assertEquals("research_required", first.action)
        // Second cycle: the first cycle's records are now prior memory.
        val result = core.process(VnicObservation("neural mesh", "Explain the neural mesh"))
        assertEquals("answer_from_context", result.action)
        assertTrue(result.confidence > 0.25)
    }

    @Test
    fun researchFindingsTurnResearchRequiredIntoAnswer() = runBlocking {
        val mem = InMemoryVnicMemory()
        val finding = VnicFinding("SYNTH-TARGET-01", "Synthetic Test Target",
            "https://example.invalid/fixture", "FIXTURE — not a real finding.", "fixture", 0.75)
        val core = VnicCore(memory = mem, researcher = StubResearcher(listOf(finding)))
        val result = core.process(VnicObservation("SYNTH-TARGET-01", "Investigate the SYNTH-TARGET-01 device"))
        assertEquals("answer_from_context", result.action)
        assertEquals(1, result.findings.size)
        assertEquals(0.75, result.confidence, 1e-9)
        assertEquals(VnicExecutionState.RESEARCHED, result.executionState)
        assertTrue(mem.recall("SYNTH-TARGET-01", 5, VnicMemoryClass.SEMANTIC).isNotEmpty())
    }

    @Test
    fun verifiedExecutionReachesVerifiedState() = runBlocking {
        val mem = InMemoryVnicMemory()
        val caps = listOf(VnicCapabilityInfo("ble.scan", "BLE scan", "scan bluetooth", "READ", false))
        val exec = FakeExecutor(VnicExecutionOutcome(VnicExecutionState.VERIFIED, "found device SYNTH-TARGET-01"))
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()))
        // Instruction + no local knowledge -> research first (stub: none) ->
        // stays research_required, BUT the Defect 2 repair plans anyway:
        // the first cycle already plans ble.scan and executes it through
        // the attached executor, reaching VERIFIED before any knowledge
        // is added. The second cycle then runs answer_from_context.
        val result1 = core.process(
            VnicObservation("SYNTH-TARGET-01", "Investigate bluetooth device SYNTH-TARGET-01"),
            capabilities = caps, executor = exec
        )
        assertEquals("research_required", result1.action)
        assertEquals(listOf("ble.scan"), result1.plan)
        assertEquals("ble.scan", exec.calledWith)
        assertEquals(VnicExecutionState.VERIFIED, result1.executionState)
        mem.remember(VnicMemoryRecord(type = VnicMemoryClass.SEMANTIC, content = "Bluetooth devices pair over BLE", confidence = 0.9))
        val result2 = core.process(
            VnicObservation("SYNTH-TARGET-01", "Investigate bluetooth device SYNTH-TARGET-01"),
            capabilities = caps, executor = exec
        )
        assertEquals("ble.scan", exec.calledWith)
        assertEquals(VnicExecutionState.VERIFIED, result2.executionState)
        assertEquals("answer_from_context", result2.action)
        assertTrue(result2.learned.any { it.contains("reward=0.9") })
    }

    @Test
    fun failedExecutionIsRecordedNotHidden() = runBlocking {
        val mem = InMemoryVnicMemory()
        mem.remember(VnicMemoryRecord(type = VnicMemoryClass.SEMANTIC, content = "Bluetooth devices pair over BLE", confidence = 0.9))
        val caps = listOf(VnicCapabilityInfo("ble.connect", "BLE connect", "connect bluetooth", "CONTROL", false))
        val exec = FakeExecutor(VnicExecutionOutcome(VnicExecutionState.FAILED, "connection refused"))
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()))
        val result = core.process(
            VnicObservation("SYNTH-TARGET-01", "Investigate bluetooth device SYNTH-TARGET-01"),
            capabilities = caps, executor = exec
        )
        assertEquals(VnicExecutionState.FAILED, result.executionState)
        assertTrue(result.learned.any { it.contains("reward=0.2") })
    }

    @Test
    fun learningMetricsAreMeasuredNotReference() = runBlocking {
        val core = VnicCore(memory = InMemoryVnicMemory(), researcher = StubResearcher(emptyList()))
        core.learning.feedback(0.9)
        core.learning.feedback(0.9)
        core.learning.feedback(0.2)
        val m = core.learning.metrics()
        assertEquals(3, m["feedback_events"])
        assertEquals(2, m["successes"])
        assertEquals(1, m["failures"])
        assertTrue((m["measured_loss"] as Double) < 1.0)
        assertTrue((m["measured_loss"] as Double) > 0.0)
    }

    @Test
    fun telemetryCountsRealRunsOnly() = runBlocking {
        val telemetry = VnicTelemetry()
        val core = VnicCore(memory = InMemoryVnicMemory(), researcher = StubResearcher(emptyList()), telemetry = telemetry)
        core.process(VnicObservation("x", "Investigate unknown thing x"))
        val snap = telemetry.snapshot()
        assertEquals(1L, snap["cycles_run"])
        assertEquals(1L, snap["observations_seen"])
        assertEquals(1L, snap["research_queries"])
    }

    @Test
    fun referenceProfileIsReferenceOnly() {
        assertEquals("8.7B", VnicReferenceProfile.TOTAL_NEURONS)
        assertEquals(99.7, VnicReferenceProfile.OVERALL_PERFORMANCE_PCT, 0.0)
        assertEquals(0L, VnicTelemetry().snapshot()["cycles_run"])
    }
}
