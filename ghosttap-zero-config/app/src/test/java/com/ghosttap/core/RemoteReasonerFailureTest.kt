package com.ghosttap.core

import com.ghosttap.core.investigate.AiFailureReason
import com.ghosttap.core.investigate.plainWords
import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Tests for Gap 4 (old investigations path) and Gap 5a (status line):
 * 429 vs 500 are distinct failures, unexpected exceptions are no longer
 * mislabelled EMPTY_ANSWER, and postDraft / postScriptDraft / postRefine
 * record a failure reason instead of failing silently.
 *
 * Scope honesty, read before trusting these:
 * - The "stub-test-key" strings below are DUMMY values, not a real key.
 * - The HTTP tests run the REAL RemoteLlmReasoner against a LOCAL
 *   loopback stub server (127.0.0.1, ephemeral port), gated on loopback
 *   TCP actually working here (Assume) because this sandbox black-holes
 *   JVM loopback. That is a local stub, not a live call to OpenAI.
 * - The 200-body tests are additionally gated on org.json actually
 *   working: on plain JVM unit tests android.jar's org.json is a stub
 *   that throws, so those tests Assume out here and run on device/CI
 *   instead. The non-2xx tests never touch org.json and run anywhere
 *   loopback works.
 */
class RemoteReasonerFailureTest {

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

    /** True only when org.json is the real implementation, not android.jar's throwing stub. */
    private fun orgJsonWorks(): Boolean = try {
        JSONObject("{\"a\":1}").optInt("a") == 1
    } catch (_: Exception) {
        false
    }

    private fun stubServer(status: Int, body: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = null
        server.start()
        return server
    }

    private fun stubReasoner(server: HttpServer) = RemoteLlmReasoner(
        "http://127.0.0.1:${server.address.port}/v1/chat/completions",
        "stub-test-key"
    )

    private fun unreachableReasoner() = RemoteLlmReasoner(
        "http://127.0.0.1:9/v1/chat/completions",
        "stub-test-key"
    )

    // ---- Gap 5a: the status line must not claim a connection ----

    @Test
    fun statusLineDoesNotClaimConnection() {
        val configured = RemoteLlmReasoner("https://api.openai.com/v1/chat/completions", "stub-test-key")
        assertFalse(configured.statusLine.contains("connected", ignoreCase = true))
        assertTrue(configured.statusLine.contains("set up"))
        val unconfigured = RemoteLlmReasoner("", "")
        assertTrue(unconfigured.statusLine.contains("not configured"))
    }

    // ---- Gap 4: 429 vs 500 are distinct ----

    @Test
    fun background429IsTooBusy() = runBlocking {
        Assume.assumeTrue(loopbackUsable())
        val server = stubServer(429, """{"error":{"message":"slow down"}}""")
        try {
            val remote = stubReasoner(server)
            assertEquals("", remote.backgroundOn("test topic"))
            assertEquals(AiFailureReason.TOO_BUSY, remote.lastFailureReason)
            assertTrue(remote.lastFailureReason!!.plainWords().contains("busy"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun background500IsServerError() = runBlocking {
        Assume.assumeTrue(loopbackUsable())
        val server = stubServer(500, """{"error":{"message":"boom"}}""")
        try {
            val remote = stubReasoner(server)
            assertEquals("", remote.backgroundOn("test topic"))
            assertEquals(AiFailureReason.SERVER_ERROR, remote.lastFailureReason)
            assertTrue(remote.lastFailureReason!!.plainWords().contains("problem on its side"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun background401StaysKeyRejected() = runBlocking {
        Assume.assumeTrue(loopbackUsable())
        val server = stubServer(401, """{"error":{"message":"bad key"}}""")
        try {
            val remote = stubReasoner(server)
            assertEquals("", remote.backgroundOn("test topic"))
            assertEquals(AiFailureReason.KEY_REJECTED, remote.lastFailureReason)
        } finally {
            server.stop(0)
        }
    }

    // ---- Gap 4: unexpected exceptions are not EMPTY_ANSWER ----

    @Test
    fun backgroundUnreachableIsNotEmptyAnswer() = runBlocking {
        val remote = unreachableReasoner()
        assertEquals("", remote.backgroundOn("test topic"))
        assertEquals(AiFailureReason.NO_NETWORK, remote.lastFailureReason)
        assertNotEquals(AiFailureReason.EMPTY_ANSWER, remote.lastFailureReason)
    }

    @Test
    fun backgroundGarbageBodyIsUnreadableNotEmpty() = runBlocking {
        Assume.assumeTrue(loopbackUsable())
        Assume.assumeTrue("org.json is stubbed on JVM unit tests — runs where org.json is real.", orgJsonWorks())
        val server = stubServer(200, "not json at all")
        try {
            val remote = stubReasoner(server)
            assertEquals("", remote.backgroundOn("test topic"))
            assertEquals(AiFailureReason.UNREADABLE_ANSWER, remote.lastFailureReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun backgroundEmptyContentStaysEmptyAnswer() = runBlocking {
        Assume.assumeTrue(loopbackUsable())
        Assume.assumeTrue("org.json is stubbed on JVM unit tests — runs where org.json is real.", orgJsonWorks())
        val server = stubServer(
            200,
            """{"choices":[{"index":0,"message":{"role":"assistant","content":""},"finish_reason":"stop"}]}"""
        )
        try {
            val remote = stubReasoner(server)
            assertEquals("", remote.backgroundOn("test topic"))
            assertEquals(AiFailureReason.EMPTY_ANSWER, remote.lastFailureReason)
        } finally {
            server.stop(0)
        }
    }

    // ---- Gap 4: draft / script / refine record a failure reason ----

    @Test
    fun draftRecordsServerErrorOn500() = runBlocking {
        Assume.assumeTrue(loopbackUsable())
        val server = stubServer(500, """{"error":{"message":"boom"}}""")
        try {
            val remote = stubReasoner(server)
            assertEquals("", remote.draftCapabilityJson("gap", emptyList()))
            assertEquals(AiFailureReason.SERVER_ERROR, remote.lastFailureReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun scriptDraftRecordsServerErrorOn500() = runBlocking {
        Assume.assumeTrue(loopbackUsable())
        val server = stubServer(500, """{"error":{"message":"boom"}}""")
        try {
            val remote = stubReasoner(server)
            assertEquals("", remote.draftScriptJson("gap", emptyList()))
            assertEquals(AiFailureReason.SERVER_ERROR, remote.lastFailureReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun refineRecordsServerErrorOn500() = runBlocking {
        Assume.assumeTrue(loopbackUsable())
        val server = stubServer(500, """{"error":{"message":"boom"}}""")
        try {
            val remote = stubReasoner(server)
            val base = listOf(PlanStep("ble.scan", emptyMap(), "why", false, "READ"))
            val refined = remote.refinePlan(IntelTarget("BLE", "Test", "test text"), base, "")
            assertEquals(base, refined)
            assertEquals(AiFailureReason.SERVER_ERROR, remote.lastFailureReason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun draftRecordsTooBusyOn429() = runBlocking {
        Assume.assumeTrue(loopbackUsable())
        val server = stubServer(429, """{"error":{"message":"slow down"}}""")
        try {
            val remote = stubReasoner(server)
            assertEquals("", remote.draftCapabilityJson("gap", emptyList()))
            assertEquals(AiFailureReason.TOO_BUSY, remote.lastFailureReason)
        } finally {
            server.stop(0)
        }
    }
}
