package com.ghosttap.core.vnic

import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Tests for the Part 2 AI connection check (diagnostic) and the proven
 * gaps it closes (Gap 1 result→reasoning, Gap 3 malformed-vs-empty,
 * Gap 5b hypotheses fallback flag).
 *
 * Scope honesty, read before trusting these:
 * - The "stub-test-key-ABC123" strings below are DUMMY values, not a real key.
 * - The diagnostic stage-machine tests use a FAKE port (no network, no
 *   model). They prove the stage logic — which stage fails on which
 *   outcome, that a local answer never passes as remote success, and
 *   that no stage detail ever contains the key. They do NOT prove a live
 *   call: that is live Test A, which needs the user's saved key and his
 *   phone.
 * - The probe tests run the REAL RemoteLlmReasoner.postChat against a
 *   LOCAL loopback stub server (127.0.0.1, ephemeral port), gated on
 *   loopback TCP actually working here (Assume) because this sandbox
 *   black-holes JVM loopback. That proves the real transport + the exact
 *   probe bytes, NOT a live call to OpenAI.
 * - No VnicEngine test exists: VnicEngine needs Android (Application /
 *   Room), so the port implementation is verified by code read only.
 */
class VnicDiagnosticTest {

    // ---- Fake port: the diagnostic's seam, with a knob per stage ----

    private class FakePort(
        var saved: VnicLlmConfig = VnicLlmConfig(apiKey = "stub-test-key-ABC123"),
        var summary: VnicRemoteSummary? = VnicRemoteSummary(
            VnicLlmConfig.DEFAULT_ENDPOINT, "gpt-4o-mini", true
        ),
        var current: VnicReasoningProvider = RemoteLlmReasoner(
            VnicLlmConfig(apiKey = "stub-test-key-ABC123")
        ),
        var probe: VnicProbeOutcome? = VnicProbeOutcome(
            requestSent = true, httpCode = 200, httpSuccess = true,
            parsedContent = "GHOST_TAP_AI_CONNECTED", malformed = false, failureReason = null
        ),
        var hypotheses: List<String> = listOf(
            "The online helper replied with the exact words GHOST_TAP_AI_CONNECTED, so the link works."
        ),
        var fellBack: Boolean = false,
        var failure: String? = null
    ) : VnicAiDiagnosticPort {
        override fun savedLlmConfig() = saved
        override fun refreshReasonerFromSettings() = current.name
        override fun currentReasoner() = current
        override fun remoteSummary() = summary
        override fun reasonerFailureReason() = failure
        override fun reasonerFellBackToLocal() = fellBack
        override fun probeConnection() = probe
        override fun reasonerHypotheses(observation: String, knownFacts: List<String>) = hypotheses
    }

    private fun passingReport() = VnicAiDiagnostic(FakePort()).run()

    private fun stageOf(report: VnicDiagReport, stage: VnicDiagStage) =
        report.stages.firstOrNull { it.stage == stage }

    private fun assertNoKeyLeak(report: VnicDiagReport) {
        for (s in report.stages) {
            assertFalse("stage detail leaked the key: ${s.detail}", s.detail.contains("stub-test-key-ABC123"))
        }
        assertFalse("overall line leaked the key", report.overallLine.contains("stub-test-key-ABC123"))
    }

    // ---- The exact probe request ----

    @Test
    fun probePayloadIsExactlyTheSpecifiedRequest() {
        assertEquals(
            "{\"model\": \"gpt-4o-mini\", \"messages\": [{\"role\": \"user\", \"content\": \"Reply with exactly: GHOST_TAP_AI_CONNECTED\"}], \"temperature\": 0}",
            vnicDiagProbePayload("gpt-4o-mini")
        )
        // A configured model is preserved, not forced back to the default.
        assertTrue(vnicDiagProbePayload("gpt-4o").contains("\"model\": \"gpt-4o\""))
        assertEquals(VNIC_DIAG_EXPECTED_REPLY, "GHOST_TAP_AI_CONNECTED")
    }

    // ---- Stage machine: happy path + each failure stops at its stage ----

    @Test
    fun diagnosticPassesEndToEndWithFakeRemote() {
        val report = passingReport()
        assertTrue(report.passed)
        assertEquals(VnicDiagStage.values().size, report.stages.size)
        assertTrue(report.stages.all { it.passed })
        assertTrue(report.overallLine.contains("passed"))
        assertNoKeyLeak(report)
    }

    @Test
    fun noKeyFailsOnlyKeyPresent() {
        val report = VnicAiDiagnostic(FakePort(saved = VnicLlmConfig())).run()
        assertFalse(report.passed)
        assertEquals(1, report.stages.size)
        val stage = stageOf(report, VnicDiagStage.KEY_PRESENT)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("No key saved"))
        assertNull(stageOf(report, VnicDiagStage.CONFIG_RECEIVED))
        assertNoKeyLeak(report)
    }

    @Test
    fun localReasonerFailsConfigReceived() {
        val port = FakePort(summary = null, current = LocalRuleReasoner())
        val report = VnicAiDiagnostic(port).run()
        assertFalse(report.passed)
        assertTrue(stageOf(report, VnicDiagStage.KEY_PRESENT)!!.passed)
        val stage = stageOf(report, VnicDiagStage.CONFIG_RECEIVED)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertNull(stageOf(report, VnicDiagStage.REASONER_SELECTED))
        assertNoKeyLeak(report)
    }

    @Test
    fun unconfiguredRemoteSummaryFailsConfigReceived() {
        val port = FakePort(
            summary = VnicRemoteSummary(VnicLlmConfig.DEFAULT_ENDPOINT, "gpt-4o-mini", false)
        )
        val report = VnicAiDiagnostic(port).run()
        assertFalse(stageOf(report, VnicDiagStage.CONFIG_RECEIVED)!!.passed)
        assertNoKeyLeak(report)
    }

    @Test
    fun localDelegateFailsReasonerSelected() {
        val port = FakePort(current = LocalRuleReasoner())
        val report = VnicAiDiagnostic(port).run()
        assertTrue(stageOf(report, VnicDiagStage.CONFIG_RECEIVED)!!.passed)
        assertFalse(stageOf(report, VnicDiagStage.REASONER_SELECTED)!!.passed)
        assertNull(stageOf(report, VnicDiagStage.REQUEST_SENT))
    }

    @Test
    fun probeNeverSentFailsRequestSent() {
        val port = FakePort(
            probe = VnicProbeOutcome(
                requestSent = false, httpCode = null, httpSuccess = false,
                parsedContent = null, malformed = false,
                failureReason = "Could not reach the online helper — check the connection."
            )
        )
        val report = VnicAiDiagnostic(port).run()
        assertFalse(report.passed)
        val stage = stageOf(report, VnicDiagStage.REQUEST_SENT)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("Could not reach"))
        assertNull(stageOf(report, VnicDiagStage.HTTP_SUCCESS))
        assertNoKeyLeak(report)
    }

    @Test
    fun probeNullFailsRequestSent() {
        val report = VnicAiDiagnostic(FakePort(probe = null)).run()
        assertFalse(stageOf(report, VnicDiagStage.REQUEST_SENT)!!.passed)
        assertNull(stageOf(report, VnicDiagStage.HTTP_SUCCESS))
    }

    @Test
    fun http401FailsHttpSuccessAsKeyRefused() {
        val port = FakePort(
            probe = VnicProbeOutcome(
                requestSent = true, httpCode = 401, httpSuccess = false,
                parsedContent = null, malformed = false,
                failureReason = "Online helper refused the saved key (HTTP 401) — check the key in the Thinking Engine box."
            )
        )
        val report = VnicAiDiagnostic(port).run()
        assertTrue(stageOf(report, VnicDiagStage.REQUEST_SENT)!!.passed)
        val stage = stageOf(report, VnicDiagStage.HTTP_SUCCESS)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("refused the saved key"))
        assertNull(stageOf(report, VnicDiagStage.RESPONSE_PARSED))
        assertNoKeyLeak(report)
    }

    @Test
    fun http429FailsHttpSuccessAsTooManyRequests() {
        val port = FakePort(
            probe = VnicProbeOutcome(
                requestSent = true, httpCode = 429, httpSuccess = false,
                parsedContent = null, malformed = false,
                failureReason = "Online helper is busy (HTTP 429) — try again shortly."
            )
        )
        val report = VnicAiDiagnostic(port).run()
        val stage = stageOf(report, VnicDiagStage.HTTP_SUCCESS)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("busy"))
    }

    @Test
    fun http500FailsHttpSuccessAsServerError() {
        val port = FakePort(
            probe = VnicProbeOutcome(
                requestSent = true, httpCode = 500, httpSuccess = false,
                parsedContent = null, malformed = false,
                failureReason = "Online helper had a problem (HTTP 500) — try again later."
            )
        )
        val report = VnicAiDiagnostic(port).run()
        val stage = stageOf(report, VnicDiagStage.HTTP_SUCCESS)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("had a problem"))
    }

    @Test
    fun malformedResponseFailsResponseParsed() {
        val port = FakePort(
            probe = VnicProbeOutcome(
                requestSent = true, httpCode = 200, httpSuccess = true,
                parsedContent = null, malformed = true,
                failureReason = "Online helper answered, but the answer was in a shape Ghost Tap couldn't read."
            )
        )
        val report = VnicAiDiagnostic(port).run()
        assertTrue(stageOf(report, VnicDiagStage.HTTP_SUCCESS)!!.passed)
        val stage = stageOf(report, VnicDiagStage.RESPONSE_PARSED)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("couldn't read"))
        assertNull(stageOf(report, VnicDiagStage.AI_REASONING_CONFIRMED))
    }

    @Test
    fun emptyResponseFailsResponseParsedAsEmpty() {
        val port = FakePort(
            probe = VnicProbeOutcome(
                requestSent = true, httpCode = 200, httpSuccess = true,
                parsedContent = "", malformed = false,
                failureReason = "Online helper answered but said nothing."
            )
        )
        val report = VnicAiDiagnostic(port).run()
        val stage = stageOf(report, VnicDiagStage.RESPONSE_PARSED)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("said nothing"))
    }

    @Test
    fun wrongContentFailsResponseParsed() {
        val port = FakePort(
            probe = VnicProbeOutcome(
                requestSent = true, httpCode = 200, httpSuccess = true,
                parsedContent = "hello there", malformed = false, failureReason = null
            )
        )
        val report = VnicAiDiagnostic(port).run()
        val stage = stageOf(report, VnicDiagStage.RESPONSE_PARSED)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("didn't say the expected check words"))
        assertNull(stageOf(report, VnicDiagStage.AI_REASONING_CONFIRMED))
    }

    // ---- Stage 7: real consumption, never a self-asserted PASS ----

    @Test
    fun stageSevenFailsWhenBrainDoesNotMentionCheckWords() {
        val port = FakePort(
            hypotheses = listOf("The connection looks fine, nothing else to say.")
        )
        val report = VnicAiDiagnostic(port).run()
        assertTrue(stageOf(report, VnicDiagStage.RESPONSE_PARSED)!!.passed)
        val stage = stageOf(report, VnicDiagStage.AI_REASONING_CONFIRMED)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("didn't mention the check words"))
        assertFalse(report.passed)
    }

    @Test
    fun stageSevenFailsWhenLocalHelperAnswersInstead() {
        val port = FakePort(
            hypotheses = listOf(
                "The online brain didn't answer — using the built-in helper instead.",
                "Unknown device. No rule fits — look it up first, before trying to control it."
            ),
            fellBack = true,
            failure = "Could not reach the online helper — check the connection."
        )
        val report = VnicAiDiagnostic(port).run()
        val stage = stageOf(report, VnicDiagStage.AI_REASONING_CONFIRMED)
        assertNotNull(stage)
        assertFalse(stage!!.passed)
        assertTrue(stage.detail.contains("built-in helper answered instead"))
        assertFalse(report.passed)
    }

    @Test
    fun stageSevenFailsOnFallbackFlagEvenWithoutTheStatusLine() {
        // The flag alone is enough: a fallback must never pass as remote success.
        val port = FakePort(
            hypotheses = listOf("The online helper replied with the exact words GHOST_TAP_AI_CONNECTED, so the link works."),
            fellBack = true
        )
        val report = VnicAiDiagnostic(port).run()
        assertFalse(stageOf(report, VnicDiagStage.AI_REASONING_CONFIRMED)!!.passed)
    }

    // ---- Gap 3: malformed vs empty, at the parser (no network) ----

    private fun parsingReasoner() = RemoteLlmReasoner(VnicLlmConfig(apiKey = "stub-test-key"))

    @Test
    fun parseDetailDistinguishesMalformedFromEmpty() {
        val reasoner = parsingReasoner()
        // Malformed: bad JSON / missing choices / missing message / non-string content.
        assertTrue(reasoner.parseChatContentDetailed("not json at all") is VnicChatContent.Malformed)
        assertTrue(reasoner.parseChatContentDetailed("""{"error":{"message":"bad key"}}""") is VnicChatContent.Malformed)
        assertTrue(reasoner.parseChatContentDetailed("""{"choices":[]}""") is VnicChatContent.Malformed)
        assertTrue(
            reasoner.parseChatContentDetailed(
                """{"choices":[{"index":0,"message":{"role":"assistant","content":null},"finish_reason":"tool_calls"}]}"""
            ) is VnicChatContent.Malformed
        )
        assertTrue(
            reasoner.parseChatContentDetailed(
                """{"choices":[{"index":0,"message":{"role":"assistant","content":123},"finish_reason":"stop"}]}"""
            ) is VnicChatContent.Malformed
        )
        // Empty: valid reply, empty content.
        assertTrue(
            reasoner.parseChatContentDetailed(
                """{"choices":[{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"stop"}]}"""
            ) is VnicChatContent.Empty
        )
        assertTrue(
            reasoner.parseChatContentDetailed(
                """{"choices":[{"index":0,"message":{"role":"assistant","content":"   "},"finish_reason":"stop"}]}"""
            ) is VnicChatContent.Empty
        )
        // Text: the normal case.
        val text = reasoner.parseChatContentDetailed(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"GHOST_TAP_AI_CONNECTED"},"finish_reason":"stop"}]}"""
        )
        assertTrue(text is VnicChatContent.Text)
        assertEquals("GHOST_TAP_AI_CONNECTED", (text as VnicChatContent.Text).text)
    }

    @Test
    fun parseChatContentKeepsOldBehaviorForEmptyAndMalformed() {
        val reasoner = parsingReasoner()
        val method = RemoteLlmReasoner::class.java.getDeclaredMethod("parseChatContent", String::class.java)
        method.isAccessible = true
        fun parse(body: String): String? = method.invoke(reasoner, body) as String?
        assertNull(parse("""{"error":{"message":"bad key"}}"""))
        assertEquals("", parse("""{"choices":[{"message":{"content":""}}]}"""))
        assertEquals("hi", parse("""{"choices":[{"message":{"content":"hi"}}]}"""))
    }

    // ---- Gap 5b: a failed hypotheses call sets the fallback flag ----

    @Test
    fun hypothesesFailureSetsFallbackFlag() {
        val remote = RemoteLlmReasoner(
            VnicLlmConfig(
                endpoint = "http://127.0.0.1:9/v1/chat/completions",
                apiKey = "stub-test-key",
                connectTimeoutMs = 200,
                readTimeoutMs = 200
            )
        )
        val hyps = remote.hypotheses("Found a Bluetooth device advertising as SYNTH-TARGET-01", emptyList())
        assertTrue(remote.lastPlanFellBackToLocal)
        assertTrue(hyps.first().startsWith("The online brain didn't answer"))
        assertNotNull(remote.lastFailureReason)
    }

    // ---- Gap 1: the result→reasoning return path ----

    private class StubResearcher(private val findings: List<VnicFinding>) : VnicResearchPort {
        override suspend fun research(query: String): VnicResearchReport =
            VnicResearchReport(query, findings, listOf("stub"), 0, 1)
    }

    private class RecordingExecutor(private val outcome: VnicExecutionOutcome) : VnicExecutor {
        val executed = mutableListOf<String>()
        override suspend fun execute(capabilityId: String, args: Map<String, String>): VnicExecutionOutcome {
            executed += capabilityId
            return outcome
        }
    }

    private class RecordingReasoner(
        private val evaluation: List<String> = listOf("Next: check the device again in plain words.")
    ) : VnicReasoningProvider {
        val noted = mutableListOf<Pair<String, VnicExecutionOutcome>>()
        override val name = "Recording test reasoner"
        override fun hypotheses(observation: String, knownFacts: List<String>): List<String> =
            listOf("test hypothesis")
        override fun planFor(goal: String, capabilityIds: List<String>): List<String> =
            listOf("ble.scan")
        override fun noteResult(capabilityId: String, outcome: VnicExecutionOutcome): List<String> {
            noted += capabilityId to outcome
            return evaluation
        }
    }

    private val bleScanCap = VnicCapabilityInfo("ble.scan", "BLE scan", "scan bluetooth", "READ", false)

    @Test
    fun verifiedResultReachesReasonerAndDerivesNextStep() = runBlocking {
        val reasoner = RecordingReasoner()
        val exec = RecordingExecutor(VnicExecutionOutcome(VnicExecutionState.VERIFIED, "device answered"))
        val core = VnicCore(
            memory = InMemoryVnicMemory(),
            researcher = StubResearcher(emptyList()),
            reasoner = reasoner
        )
        val result = core.process(
            VnicObservation("ZXQ-9999", "Investigate the ZXQ-9999 bluetooth gadget"),
            capabilities = listOf(bleScanCap),
            executor = exec
        )
        assertEquals(listOf("ble.scan"), exec.executed)
        assertEquals(1, reasoner.noted.size)
        assertEquals("ble.scan", reasoner.noted[0].first)
        assertEquals(VnicExecutionState.VERIFIED, reasoner.noted[0].second.state)
        assertEquals(listOf("Next: check the device again in plain words."), result.resultEvaluation)
        assertTrue(result.phases.any { it.startsWith("EVALUATE:") && it.contains("Next: check the device again") })
        assertTrue(result.learned.any { it.contains("Next step from result:") })
        // The scalar reward / mesh feedback is intact alongside the evaluation.
        assertTrue(result.learned.any { it.startsWith("Feedback reward=0.9") })
    }

    @Test
    fun failedResultIsAlsoEvaluated() = runBlocking {
        val reasoner = RecordingReasoner(listOf("Next: try a different check, not the same one again."))
        val exec = RecordingExecutor(VnicExecutionOutcome(VnicExecutionState.FAILED, "no answer"))
        val core = VnicCore(
            memory = InMemoryVnicMemory(),
            researcher = StubResearcher(emptyList()),
            reasoner = reasoner
        )
        val result = core.process(
            VnicObservation("ZXQ-9999", "Investigate the ZXQ-9999 bluetooth gadget"),
            capabilities = listOf(bleScanCap),
            executor = exec
        )
        assertEquals(1, reasoner.noted.size)
        assertEquals(VnicExecutionState.FAILED, reasoner.noted[0].second.state)
        assertEquals(listOf("Next: try a different check, not the same one again."), result.resultEvaluation)
        assertTrue(result.learned.any { it.startsWith("Feedback reward=0.2") })
    }

    @Test
    fun storeContextRunsNoEvaluation() = runBlocking {
        val reasoner = RecordingReasoner()
        val exec = RecordingExecutor(VnicExecutionOutcome(VnicExecutionState.VERIFIED, "ok"))
        val core = VnicCore(
            memory = InMemoryVnicMemory(),
            researcher = StubResearcher(emptyList()),
            reasoner = reasoner
        )
        val result = core.process(
            VnicObservation("SYNTH-TARGET-01", "The SYNTH-TARGET-01 bluetooth device is a synthetic test target"),
            capabilities = listOf(bleScanCap),
            executor = exec
        )
        assertEquals("store_context", result.action)
        assertTrue(reasoner.noted.isEmpty())
        assertTrue(result.resultEvaluation.isEmpty())
        assertTrue(result.phases.none { it.startsWith("EVALUATE:") })
    }

    @Test
    fun localNoteResultEvaluatesEachState() {
        val local = LocalRuleReasoner()
        assertTrue(local.noteResult("ble.scan", VnicExecutionOutcome(VnicExecutionState.VERIFIED, "ok")).first().contains("worked"))
        assertTrue(local.noteResult("ble.scan", VnicExecutionOutcome(VnicExecutionState.CONTROLLABLE, "held")).first().contains("worked"))
        assertTrue(local.noteResult("ble.scan", VnicExecutionOutcome(VnicExecutionState.CONNECTED, "open")).first().contains("connected"))
        assertTrue(local.noteResult("ble.scan", VnicExecutionOutcome(VnicExecutionState.ATTEMPTED, "silent")).first().contains("no answer came back"))
        assertTrue(local.noteResult("ble.scan", VnicExecutionOutcome(VnicExecutionState.FAILED, "no")).first().contains("did not work"))
        assertTrue(local.noteResult("ble.scan", VnicExecutionOutcome(VnicExecutionState.IDENTIFIED, "")).isEmpty())
    }

    @Test
    fun remoteNoteResultFallsBackToLocalOnFailure() {
        val remote = RemoteLlmReasoner(
            VnicLlmConfig(
                endpoint = "http://127.0.0.1:9/v1/chat/completions",
                apiKey = "stub-test-key",
                connectTimeoutMs = 200,
                readTimeoutMs = 200
            )
        )
        val eval = remote.noteResult("ble.scan", VnicExecutionOutcome(VnicExecutionState.VERIFIED, "device answered"))
        assertTrue(eval.first().startsWith("The online brain didn't answer"))
        assertTrue(eval.any { it.contains("worked") })
        assertNotNull(remote.lastFailureReason)
        assertFalse(eval.joinToString().contains("stub-test-key"))
    }

    @Test
    fun switchableReasonerDelegatesNoteResult() {
        val recording = RecordingReasoner(listOf("next step"))
        val switch = SwitchableReasoner(recording)
        val eval = switch.noteResult("ble.scan", VnicExecutionOutcome(VnicExecutionState.VERIFIED, "ok"))
        assertEquals(listOf("next step"), eval)
        assertEquals(1, recording.noted.size)
    }

    // ---- Gap 3 + probe through the REAL transport, local stub server (not a live call) ----

    /**
     * Minimal loopback probe (same idea as VnicRepairTest's): this
     * sandbox black-holes JVM loopback TCP, so the stub-server tests
     * Assume out here and run on device/CI instead.
     */
    private fun loopbackUsable(): Boolean {
        var server: ServerSocket? = null
        var client: Socket? = null
        var accepted: Socket? = null
        return try {
            server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            val port = server.localPort
            server.soTimeout = 2000
            val srv = server
            val t = Thread {
                try {
                    accepted = srv.accept()
                    accepted!!.soTimeout = 2000
                    val line = BufferedReader(InputStreamReader(accepted!!.getInputStream(), Charsets.UTF_8)).readLine()
                    if (line == "PING") accepted!!.getOutputStream().write("PONG\r\n".toByteArray(Charsets.UTF_8))
                } catch (_: Exception) {
                }
            }
            t.isDaemon = true
            t.start()
            client = Socket()
            client.connect(InetSocketAddress("127.0.0.1", port), 2000)
            client.soTimeout = 2000
            client.getOutputStream().write("PING\r\n".toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
            BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).readLine() == "PONG"
        } catch (_: Exception) {
            false
        } finally {
            runCatching { client?.close() }
            runCatching { accepted?.close() }
            runCatching { server?.close() }
        }
    }

    /** Local stub chat-completions server on an ephemeral loopback port; captures the request body it received. */
    private class CapturingStubServer(status: Int, body: String) {
        var lastRequestBody: String? = null
            private set
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { srv ->
            srv.createContext("/v1/chat/completions") { exchange ->
                lastRequestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            srv.executor = null
            srv.start()
        }

        fun stop() = server.stop(0)
        val port: Int get() = server.address.port
    }

    private fun stubReasoner(stub: CapturingStubServer) = RemoteLlmReasoner(
        VnicLlmConfig(
            endpoint = "http://127.0.0.1:${stub.port}/v1/chat/completions",
            apiKey = "stub-test-key",
            connectTimeoutMs = 2000,
            readTimeoutMs = 2000
        )
    )

    @Test
    fun probeSendsExactPayloadAndParsesExactReply() {
        Assume.assumeTrue(
            "Loopback TCP unusable in this sandbox — stub-server test skipped here; runs where loopback works.",
            loopbackUsable()
        )
        val payload = """{"id":"chatcmpl-test","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"GHOST_TAP_AI_CONNECTED"},"finish_reason":"stop"}]}"""
        val stub = CapturingStubServer(200, payload)
        try {
            val remote = stubReasoner(stub)
            val outcome = remote.probeConnection()
            assertTrue(outcome.requestSent)
            assertTrue(outcome.httpSuccess)
            assertEquals(200, outcome.httpCode)
            assertEquals("GHOST_TAP_AI_CONNECTED", outcome.parsedContent)
            assertFalse(outcome.malformed)
            assertNull(outcome.failureReason)
            assertNull(remote.lastFailureReason)
            // The exact bytes the real transport sent.
            assertEquals(vnicDiagProbePayload("gpt-4o-mini"), stub.lastRequestBody)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun probe401ReportsKeyRefused() {
        Assume.assumeTrue(
            "Loopback TCP unusable in this sandbox — stub-server test skipped here; runs where loopback works.",
            loopbackUsable()
        )
        val stub = CapturingStubServer(401, """{"error":{"message":"bad key"}}""")
        try {
            val remote = stubReasoner(stub)
            val outcome = remote.probeConnection()
            assertTrue(outcome.requestSent)
            assertFalse(outcome.httpSuccess)
            assertEquals(401, outcome.httpCode)
            assertNotNull(outcome.failureReason)
            assertTrue(outcome.failureReason!!.contains("refused the saved key"))
            assertFalse(outcome.failureReason!!.contains("stub-test-key"))
        } finally {
            stub.stop()
        }
    }

    @Test
    fun malformedBodyGetsItsOwnReasonViaRealTransport() {
        Assume.assumeTrue(
            "Loopback TCP unusable in this sandbox — stub-server test skipped here; runs where loopback works.",
            loopbackUsable()
        )
        val stub = CapturingStubServer(200, """{"error":{"message":"bad key"}}""")
        try {
            val remote = stubReasoner(stub)
            remote.hypotheses("test observation", emptyList())
            assertEquals(
                "Online helper answered, but the answer was in a shape Ghost Tap couldn't read.",
                remote.lastFailureReason
            )
            assertTrue(remote.lastPlanFellBackToLocal)
        } finally {
            stub.stop()
        }
    }

    @Test
    fun emptyBodyGetsEmptyReasonViaRealTransport() {
        Assume.assumeTrue(
            "Loopback TCP unusable in this sandbox — stub-server test skipped here; runs where loopback works.",
            loopbackUsable()
        )
        val stub = CapturingStubServer(
            200,
            """{"choices":[{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"stop"}]}"""
        )
        try {
            val remote = stubReasoner(stub)
            remote.hypotheses("test observation", emptyList())
            assertEquals("Online helper answered but said nothing.", remote.lastFailureReason)
            assertTrue(remote.lastPlanFellBackToLocal)
        } finally {
            stub.stop()
        }
    }
}
