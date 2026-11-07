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
 * Tests for the VNIC surgical repair (Defect 1 wiring + Defect 2
 * zero-findings planning), added by the verification pass 2026-11-06.
 *
 * Scope honesty, read before trusting these:
 * - The "stub-test-key" strings below are DUMMY values, not a real key.
 * - The chat-completions parsing tests use CANNED payload strings fed to
 *   the real private parser by reflection. They prove parsing, NOT a live
 *   call: no request ever leaves the JVM in those tests.
 * - The 201 / 500 tests run the REAL HttpURLConnection client in
 *   RemoteLlmReasoner.complete() against a LOCAL loopback stub server
 *   (127.0.0.1, ephemeral port). That is a local stub, not a live call to
 *   OpenAI, and it is gated on loopback TCP actually working here
 *   (Assume) because this sandbox black-holes JVM loopback.
 * - RemoteLlmReasoner has NO transport override, so there is no way to
 *   unit-test complete() without either a local stub server (used here)
 *   or a live endpoint (never done here). Live Test A (real outbound
 *   request + genuine model response) and live Test B (unknown subject ->
 *   AI plan on device) still need the user's saved key and his field test.
 * - No VnicEngine test exists: VnicEngine needs Android (Application /
 *   Room), so refreshReasonerFromSettings() is verified by code read
 *   only, not by a unit test here.
 */
class VnicRepairTest {

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

    /** Returns ids the model must never be allowed to invent. */
    private class InventingReasoner : VnicReasoningProvider {
        override val name = "Inventing test reasoner"
        override fun hypotheses(observation: String, knownFacts: List<String>): List<String> =
            listOf("test hypothesis")
        override fun planFor(goal: String, capabilityIds: List<String>): List<String> =
            listOf("invented.device.probe", "ble.scan", "made.up.id")
    }

    private val bleScanCap = VnicCapabilityInfo("ble.scan", "BLE scan", "scan bluetooth", "READ", false)

    // ---- Defect 1: saved-settings config plumbing (VnicLlmConfig) ----

    @Test
    fun fromSavedBlankEndpointDefaultsToOpenAiAndKeyOnlyConfigures() {
        val cfg = VnicLlmConfig.fromSaved("", "  stub-test-key  ")
        assertTrue(cfg.configured)
        assertEquals("stub-test-key", cfg.apiKey)
        assertEquals(VnicLlmConfig.DEFAULT_ENDPOINT, cfg.endpoint)
        assertEquals(VnicLlmConfig.DEFAULT_ENDPOINT, cfg.effectiveEndpoint)
        assertEquals("https://api.openai.com/v1/chat/completions", VnicLlmConfig.DEFAULT_ENDPOINT)
    }

    @Test
    fun fromSavedBlankKeyIsNotConfigured() {
        val cfg = VnicLlmConfig.fromSaved("", "")
        assertFalse(cfg.configured)
        assertNull(cfg.apiKey)
        // Even unconfigured, the endpoint still resolves to the default.
        assertEquals(VnicLlmConfig.DEFAULT_ENDPOINT, cfg.effectiveEndpoint)
        val nulls = VnicLlmConfig.fromSaved(null, null)
        assertFalse(nulls.configured)
        assertNull(nulls.apiKey)
    }

    @Test
    fun fromSavedKeepsExplicitEndpointAndTrims() {
        val cfg = VnicLlmConfig.fromSaved("  https://example.invalid/v1/chat/completions  ", "k")
        assertEquals("https://example.invalid/v1/chat/completions", cfg.endpoint)
        assertEquals("https://example.invalid/v1/chat/completions", cfg.effectiveEndpoint)
        assertTrue(cfg.configured)
    }

    @Test
    fun configuredIsKeyOnlyEvenWithBlankEndpoint() {
        assertTrue(VnicLlmConfig(endpoint = null, apiKey = "k").configured)
        assertTrue(VnicLlmConfig(endpoint = "   ", apiKey = "k").configured)
        assertEquals(VnicLlmConfig.DEFAULT_ENDPOINT, VnicLlmConfig(endpoint = "   ", apiKey = "k").effectiveEndpoint)
        assertFalse(VnicLlmConfig(endpoint = VnicLlmConfig.DEFAULT_ENDPOINT, apiKey = null).configured)
        assertFalse(VnicLlmConfig(endpoint = VnicLlmConfig.DEFAULT_ENDPOINT, apiKey = "   ").configured)
        // A configured key selects the remote reasoner by name at construction.
        assertEquals("Online helper", RemoteLlmReasoner(VnicLlmConfig(apiKey = "k")).name)
        assertEquals("Built-in helper", LocalRuleReasoner().name)
    }

    // ---- Defect 1: choices[0].message.content parsing (PARSING TEST — canned payloads, no live call) ----

    /** Calls the real private parser by reflection; no network is involved. */
    private fun parseChatContent(reasoner: RemoteLlmReasoner, body: String): String? {
        val method = RemoteLlmReasoner::class.java.getDeclaredMethod("parseChatContent", String::class.java)
        method.isAccessible = true
        return method.invoke(reasoner, body) as String?
    }

    private fun parsingReasoner() = RemoteLlmReasoner(VnicLlmConfig(apiKey = "stub-test-key"))

    @Test
    fun parseChatContentReadsChoicesMessageContent() {
        val body = """{"id":"chatcmpl-test","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ble.scan, net.ping"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        assertEquals("ble.scan, net.ping", parseChatContent(parsingReasoner(), body))
    }

    @Test
    fun parseChatContentIgnoresDecoyContentBeforeChoices() {
        // The old code took the first "content" anywhere in the body and
        // would have returned the decoy. The repair anchors at "choices"
        // then "message", so the real answer wins.
        val body = """{"content":"DECOY — not the answer","choices":[{"index":0,"message":{"role":"assistant","content":"REAL-ANSWER"},"finish_reason":"stop"}]}"""
        assertEquals("REAL-ANSWER", parseChatContent(parsingReasoner(), body))
    }

    @Test
    fun parseChatContentReturnsNullForNullContentAndMissingChoices() {
        // Tool-call style reply: content is null, not a string.
        val toolCall = """{"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function"}]},"finish_reason":"tool_calls"}]}"""
        assertNull(parseChatContent(parsingReasoner(), toolCall))
        // No choices at all (e.g. an error body).
        assertNull(parseChatContent(parsingReasoner(), """{"error":{"message":"bad key"}}"""))
        assertNull(parseChatContent(parsingReasoner(), "not json at all"))
    }

    @Test
    fun parseChatContentDecodesEscapes() {
        val body = """{"choices":[{"message":{"content":"line1\nline2 \"quoted\""}}]}"""
        assertEquals("line1\nline2 \"quoted\"", parseChatContent(parsingReasoner(), body))
    }

    // ---- Defect 1: remote-vs-local labelling (failed remote call, unreachable endpoint, no live call) ----

    @Test
    fun remoteFailureIsLabelledLocalFallbackNeverRemote() {
        // Nothing listens on 127.0.0.1:9 — the call fails fast, same
        // pattern as the existing VnicPackage7Test remote-fallback test.
        val remote = RemoteLlmReasoner(
            VnicLlmConfig(
                endpoint = "http://127.0.0.1:9/v1/chat/completions",
                apiKey = "stub-test-key",
                connectTimeoutMs = 200,
                readTimeoutMs = 200
            )
        )
        val plan = remote.planFor("scan bluetooth devices", listOf("ble.scan", "net.ping"))
        // The built-in helper's plan is used, and it is labelled as such.
        assertEquals(listOf("ble.scan"), plan)
        assertTrue(remote.lastPlanFellBackToLocal)
        val reason = remote.lastFailureReason
        assertNotNull(reason)
        assertFalse(reason!!.contains("stub-test-key"))
        // The hypotheses path labels the same failure as a status line,
        // never as a remote hypothesis.
        val hyps = remote.hypotheses("Found a Bluetooth device advertising as SYNTH-TARGET-01", emptyList())
        assertTrue(hyps.first().startsWith("The online brain didn't answer"))
    }

    @Test
    fun switchableReasonerSwapsDelegateAndSurfacesItsLabels() {
        val local = LocalRuleReasoner()
        val remote = RemoteLlmReasoner(
            VnicLlmConfig(
                endpoint = "http://127.0.0.1:9/v1/chat/completions",
                apiKey = "stub-test-key",
                connectTimeoutMs = 200,
                readTimeoutMs = 200
            )
        )
        val switch = SwitchableReasoner(local)
        assertEquals("Built-in helper", switch.name)
        assertFalse(switch.lastPlanFellBackToLocal)
        assertNull(switch.lastFailureReason)
        switch.delegate = remote
        assertEquals("Online helper", switch.name)
        val plan = switch.planFor("scan bluetooth devices", listOf("ble.scan", "net.ping"))
        assertEquals(listOf("ble.scan"), plan)
        assertTrue(switch.lastPlanFellBackToLocal)
        assertNotNull(switch.lastFailureReason)
        // Swap back: the labels follow the live delegate.
        switch.delegate = local
        assertEquals("Built-in helper", switch.name)
        assertFalse(switch.lastPlanFellBackToLocal)
        assertNull(switch.lastFailureReason)
    }

    // ---- Defect 1: 2xx acceptance via a LOCAL stub server (not a live call) ----

    /**
     * Minimal loopback probe (same idea as VnicClosedLoopTest's
     * loopbackUsable): this sandbox black-holes JVM loopback TCP, so the
     * stub-server tests Assume out here and run on device/CI instead.
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

    /** Local stub chat-completions server on an ephemeral loopback port. */
    private fun stubChatServer(status: Int, body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = null // default executor
        server.start()
        return server
    }

    private fun stubReasoner(server: HttpServer) = RemoteLlmReasoner(
        VnicLlmConfig(
            endpoint = "http://127.0.0.1:${server.address.port}/v1/chat/completions",
            apiKey = "stub-test-key",
            connectTimeoutMs = 2000,
            readTimeoutMs = 2000
        )
    )

    @Test
    fun remoteAccepts201FromLocalStubServer() {
        Assume.assumeTrue(
            "Loopback TCP unusable in this sandbox — stub-server test skipped here; runs where loopback works.",
            loopbackUsable()
        )
        val payload = """{"id":"chatcmpl-test","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ble.scan"},"finish_reason":"stop"}]}"""
        val server = stubChatServer(201, payload)
        try {
            val remote = stubReasoner(server)
            val plan = remote.planFor("scan bluetooth devices", listOf("ble.scan", "net.ping"))
            assertEquals(listOf("ble.scan"), plan)
            assertFalse(remote.lastPlanFellBackToLocal)
            assertNull(remote.lastFailureReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun remote500FromLocalStubServerFallsBackToLocalWithReason() {
        Assume.assumeTrue(
            "Loopback TCP unusable in this sandbox — stub-server test skipped here; runs where loopback works.",
            loopbackUsable()
        )
        val server = stubChatServer(500, """{"error":{"message":"boom"}}""")
        try {
            val remote = stubReasoner(server)
            val plan = remote.planFor("scan bluetooth devices", listOf("ble.scan", "net.ping"))
            assertEquals(listOf("ble.scan"), plan)
            assertTrue(remote.lastPlanFellBackToLocal)
            val reason = remote.lastFailureReason
            assertNotNull(reason)
            assertTrue(reason!!.contains("500"))
            assertFalse(reason.contains("stub-test-key"))
        } finally {
            server.stop(0)
        }
    }

    // ---- Defect 2: zero-findings subjects still get a plan ----

    @Test
    fun zeroFindingsStillPlansButStaysResearchRequired() = runBlocking {
        val mem = InMemoryVnicMemory()
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()))
        // No memory, no graph entry, research returns zero findings.
        val result = core.process(
            VnicObservation("ZXQ-9999", "Investigate the ZXQ-9999 bluetooth gadget"),
            capabilities = listOf(bleScanCap)
        )
        assertEquals("research_required", result.action)
        assertEquals(listOf("ble.scan"), result.plan)
        assertEquals(VnicExecutionState.IDENTIFIED, result.executionState)
        assertTrue(result.phases.none { it.startsWith("PLAN: dropped") })
    }

    @Test
    fun storeContextPlanIsCandidateOnly() = runBlocking {
        var developCalls = 0
        val exec = RecordingExecutor(VnicExecutionOutcome(VnicExecutionState.VERIFIED, "ok"))
        val core = VnicCore(memory = InMemoryVnicMemory(), researcher = StubResearcher(emptyList()))
        val result = core.process(
            VnicObservation("SYNTH-TARGET-01", "The SYNTH-TARGET-01 bluetooth device is a synthetic test target"),
            capabilities = listOf(bleScanCap),
            executor = exec,
            developProcedure = { _, steps, _ ->
                developCalls++
                VnicDevelopedProcedure("vnic.test", steps, true, listOf("test"))
            }
        )
        assertEquals("store_context", result.action)
        // A plan IS produced for the statement, but it is a candidate only.
        assertEquals(listOf("ble.scan"), result.plan)
        assertEquals(0, developCalls)
        assertTrue(exec.executed.isEmpty())
        assertTrue(result.phases.any { it.contains("DEVELOP: plan recorded as candidate procedure") })
        assertTrue(result.phases.any { it.contains("ACT: store_context") })
        assertEquals(VnicExecutionState.IDENTIFIED, result.executionState)
    }

    @Test
    fun inventedPlanIdsAreDroppedAndNamedNeverExecuted() = runBlocking {
        val mem = InMemoryVnicMemory()
        val exec = RecordingExecutor(VnicExecutionOutcome(VnicExecutionState.VERIFIED, "ok"))
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()), reasoner = InventingReasoner())
        val result = core.process(
            VnicObservation("ZXQ-9999", "Investigate the ZXQ-9999 bluetooth gadget"),
            capabilities = listOf(bleScanCap),
            executor = exec
        )
        assertEquals(listOf("ble.scan"), result.plan)
        assertEquals(listOf("ble.scan"), exec.executed)
        assertTrue(result.phases.any { it.startsWith("PLAN: dropped 2 id(s)") })
        assertTrue(result.phases.any { it.contains("invented.device.probe") && it.contains("made.up.id") })
    }
}
