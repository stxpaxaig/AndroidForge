package com.ghosttap.core.vnic

import com.ghosttap.core.CapabilityRegistry
import com.ghosttap.core.IntelCapability
import com.ghosttap.core.OpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Closed-loop demonstration: the VNIC loop closing end to end against a
 * REAL local TCP ESP32-emulator service — real sockets, real bytes, real
 * registry dispatch, the real researcher parsing real provider bodies,
 * the real [VnicVerify] verification, the real [VnicCore] loop.
 *
 * What runs here (no mocks anywhere in the execution leg):
 * - [TcpEsp32Emulator]: a ServerSocket on 127.0.0.1 (ephemeral port) that
 *   sends a boot banner on connect, answers "AT" with "OK", "GPIO?" with
 *   live pin-state JSON, "GPIO2=1" with a confirmation, and HOLDS every
 *   connection open (it never closes after responding). Every byte
 *   sent/received is logged to a transcript file.
 * - REAL capabilities in the REAL [CapabilityRegistry]: esp32.tcp_connect
 *   (real socket connect + AT handshake, socket held in a session map),
 *   esp32.enumerate (GPIO? over the held socket -> mapped control list),
 *   esp32.send_uart (send over the held socket, read the response),
 *   esp32.status (socket still open + a live GPIO? read -> live data).
 * - Research: the REAL [VnicResearcher] in replay mode over FRESH
 *   live-captured provider bodies (DuckDuckGo HTML + Wikipedia JSON +
 *   one fetched Wikipedia page, captured via curl at demo time and saved
 *   under hidden_files/vnic-validation/closed-loop-demo/). Label, stated
 *   precisely: "live-captured provider responses, parsed by the real
 *   researcher; JVM TLS is broken in this sandbox so live HTTPS from the
 *   JVM is impossible — on-device HTTPS uses the same HttpURLConnection
 *   and will be field-tested."
 * - The FULL loop: [VnicCore.process] with the real researcher (replay),
 *   real registry mapping, a [VnicVerify] + registry-dispatch executor,
 *   a harness developProcedure (structural validation + READ steps via
 *   registry dispatch + assertions + persist via memory.saveProcedure),
 *   the real JVM bring-up port ([TcpEsp32BringUpPort]: connect ->
 *   handshake -> enumerate -> map -> hold-verify), [InMemoryVnicMemory],
 *   the real mesh. Observation subject is the synthetic ESP32 target
 *   "ESP32-TEST-NODE-01" — NOT a real device.
 *
 * Environment gate: this sandbox black-holes JVM loopback TCP (connect()
 * returns success but no byte ever reaches the server — the same
 * `other_tcp: Deny` setting that blocks the Gradle build). The demo
 * therefore probes loopback first and SKIPS with the exact blocker named
 * when the probe fails, instead of hanging or faking a pass. The
 * non-socket halves of the loop are proven here by [coreBringUpClosesLoopWithFakePort]
 * (core DEVELOP/ACT/VERIFY -> CONTROLLABLE + reward) and
 * [emulatorProtocolResponds] (the emulator's pure response logic). The
 * full socket demo runs wherever JVM loopback TCP works — flip
 * `other_tcp` from Deny to Ask in Muse settings, or run on a machine
 * without the block — and the JUnit suite reproduces it.
 *
 * Evidence written on every run (pass or skip) to
 * goals/ghost-tap-android-app-build/hidden_files/vnic-validation/closed-loop-demo/:
 * demo-log.txt (full phase trace + assertions), emulator-transcript.txt
 * (bytes sent/received), and the captured HTTP bodies used for replay.
 */
class VnicClosedLoopTest {

    companion object {
        /**
         * NOTE: the JVM here runs as root with user.home=/root while the
         * workspace lives under $HOME=/home/hatch, so resolve the demo dir
         * from $HOME first — user.home would write evidence to /root.
         */
        val DEMO_DIR = File(
            System.getenv("HOME") ?: System.getProperty("user.home"),
            "workspace/goals/ghost-tap-android-app-build/hidden_files/vnic-validation/closed-loop-demo"
        )
        const val HOST = "127.0.0.1"
        const val IO_TIMEOUT_MS = 3000
        const val SUBJECT = "ESP32-TEST-NODE-01"
        const val OBSERVATION =
            "Investigate the ESP32-TEST-NODE-01 microcontroller node over TCP — ESP32 WiFi Bluetooth UART GPIO"
    }

    // ---- Emulator protocol: pure line -> response, unit-testable without sockets ----

    /** The emulator's brain: banner + one response per command line. No I/O here. */
    class Esp32EmulatorProtocol {
        var gpio2: Int = 0
            private set
        var gpio4: Int = 0
            private set

        fun banner(): String = "ESP32-TEST-NODE-01 boot v1.0 ready\r\n"

        fun respond(line: String): String = when (line.trim()) {
            "AT" -> "OK\r\n"
            "GPIO?" -> "{\"gpio2\":$gpio2,\"gpio4\":$gpio4}\r\n"
            "GPIO2=1" -> { gpio2 = 1; "GPIO2=1 OK\r\n" }
            "GPIO2=0" -> { gpio2 = 0; "GPIO2=0 OK\r\n" }
            "GPIO4=1" -> { gpio4 = 1; "GPIO4=1 OK\r\n" }
            "GPIO4=0" -> { gpio4 = 0; "GPIO4=0 OK\r\n" }
            else -> "ERR unknown command\r\n"
        }
    }

    // ---- TCP emulator service: real ServerSocket, real bytes, holds connections ----

    class TcpEsp32Emulator {
        val protocol = Esp32EmulatorProtocol()
        private val transcriptLock = Any()
        val transcript = StringBuilder()
        private val server = ServerSocket(0, 50, InetAddress.getByName(HOST))
        val port: Int = server.localPort
        @Volatile
        private var running = true

        private val acceptThread = Thread({ acceptLoop() }, "esp32-emulator-accept").apply {
            isDaemon = true
            start()
        }

        private fun log(direction: String, bytes: String) {
            val shown = bytes.replace("\r", "<CR>").replace("\n", "<LF>")
            synchronized(transcriptLock) { transcript.append("${System.currentTimeMillis()} $direction $shown\n") }
        }

        private fun acceptLoop() {
            server.soTimeout = 1000
            while (running) {
                try {
                    val socket = server.accept()
                    Thread({ handle(socket) }, "esp32-emulator-conn").apply { isDaemon = true; start() }
                } catch (_: SocketTimeoutException) {
                    // Idle tick — re-check `running`.
                } catch (_: Exception) {
                    if (running) log("ERR", "accept failed")
                }
            }
        }

        private fun handle(socket: Socket) {
            try {
                socket.soTimeout = 10_000
                val out = socket.getOutputStream()
                val banner = protocol.banner()
                out.write(banner.toByteArray(Charsets.UTF_8))
                out.flush()
                log("SENT", banner)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                // Hold the connection open: keep reading until the CLIENT
                // closes it or the emulator stops. Never close after a response.
                while (running && !socket.isClosed) {
                    val line = try {
                        reader.readLine()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } ?: break
                    log("RECV", line)
                    val resp = protocol.respond(line)
                    out.write(resp.toByteArray(Charsets.UTF_8))
                    out.flush()
                    log("SENT", resp)
                }
            } catch (_: Exception) {
                // Client went away — nothing to prove, just close.
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        fun stop() {
            running = false
            try { server.close() } catch (_: Exception) {}
        }
    }

    // ---- Held session: one socket + one reader, reused across capabilities ----

    class Esp32TcpSession {
        @Volatile
        private var socket: Socket? = null
        private var reader: BufferedReader? = null
        private var writer: OutputStream? = null

        /** Connect (or reuse the live socket) and return the boot banner. Real socket I/O. */
        @Synchronized
        fun connect(host: String, port: Int): String {
            val existing = socket
            if (existing != null && existing.isConnected && !existing.isClosed) return "already connected"
            val s = Socket()
            s.connect(InetSocketAddress(host, port), IO_TIMEOUT_MS)
            s.soTimeout = IO_TIMEOUT_MS
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            writer = s.getOutputStream()
            return reader!!.readLine() ?: ""
        }

        /** Send one line over the held socket and read its response line. Real socket I/O. */
        @Synchronized
        fun sendAndRead(line: String): String {
            val w = writer ?: throw IllegalStateException("ESP32 session is not connected")
            w.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            w.flush()
            return reader!!.readLine() ?: ""
        }

        fun isOpen(): Boolean {
            val s = socket
            return s != null && s.isConnected && !s.isClosed
        }

        @Synchronized
        fun close() {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            reader = null
            writer = null
        }
    }

    // ---- Real capabilities, registered into the REAL CapabilityRegistry ----

    private fun registerEsp32Capabilities(session: Esp32TcpSession, port: Int) {
        fun reg(id: String, title: String, detail: String, riskLevel: String, run: suspend (Map<String, String>) -> OpResult) {
            CapabilityRegistry.register(
                IntelCapability(
                    id, title, detail, "ESP32", emptyList(), emptyList(), emptyList(),
                    readOnly = riskLevel == "READ",
                    riskLevel = riskLevel,
                    effects = "Real TCP I/O against the local ESP32 emulator on 127.0.0.1:$port.",
                    verified = false, sourceMethod = "VnicClosedLoopTest.$id", run = run
                )
            )
        }
        reg("esp32.tcp_connect", "ESP32 TCP connect + AT handshake",
            "Opens a TCP socket to the ESP32 node, reads its boot banner, and runs the AT handshake. The socket stays held in the session.",
            "CONFIGURE") { args ->
            try {
                val host = args["host"] ?: HOST
                val p = args["port"]?.toIntOrNull() ?: port
                val banner = session.connect(host, p)
                val at = session.sendAndRead("AT")
                if (banner.contains("ESP32") && at == "OK") {
                    OpResult(true, "ESP32 TCP connect + AT handshake OK: banner='$banner' AT->'$at'", "banner=$banner at=$at")
                } else {
                    OpResult(false, "ESP32 handshake failed: banner='$banner' AT->'$at'")
                }
            } catch (e: Exception) {
                OpResult(false, "ESP32 TCP connect failed: ${e.message ?: "unknown error"}")
            }
        }
        reg("esp32.enumerate", "ESP32 enumerate controls",
            "Reads live GPIO state over the held socket and maps the controls.",
            "READ") {
            try {
                if (!session.isOpen()) return@reg OpResult(false, "ESP32 session is not open — connect first.")
                val resp = session.sendAndRead("GPIO?")
                OpResult(
                    true,
                    "ESP32 controls mapped: UART, GPIO2, GPIO4 (live GPIO? read: $resp)",
                    resp
                )
            } catch (e: Exception) {
                OpResult(false, "ESP32 enumerate failed: ${e.message ?: "unknown error"}")
            }
        }
        reg("esp32.send_uart", "ESP32 send UART",
            "Sends text over the held socket and reads the real response.",
            "WRITE") { args ->
            try {
                val text = args["text"].orEmpty()
                if (text.isBlank()) return@reg OpResult(false, "Nothing to send — text was empty.")
                if (!session.isOpen()) return@reg OpResult(false, "ESP32 session is not open — connect first.")
                val resp = session.sendAndRead(text)
                OpResult(resp.isNotBlank(), "UART '$text' -> '$resp'", resp)
            } catch (e: Exception) {
                OpResult(false, "ESP32 UART send failed: ${e.message ?: "unknown error"}")
            }
        }
        reg("esp32.status", "ESP32 link status",
            "Verifies the socket is still open with a live GPIO? read — live data, not a cached flag.",
            "READ") {
            try {
                if (!session.isOpen()) return@reg OpResult(false, "ESP32 link down: socket is closed.")
                val resp = session.sendAndRead("GPIO?")
                OpResult(true, "ESP32 link live: socket open, GPIO? -> $resp (esp32)", resp)
            } catch (e: Exception) {
                OpResult(false, "ESP32 status read failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    // ---- JVM bring-up port: connect -> handshake -> enumerate -> map -> hold-verify ----

    class TcpEsp32BringUpPort(
        private val session: Esp32TcpSession,
        private val host: String,
        private val port: Int
    ) : VnicBringUpPort {
        override suspend fun bringUp(target: VnicBringUpTarget, note: String): VnicBringUpResult {
            val log = mutableListOf<String>()
            fun fail(reason: String): VnicBringUpResult {
                log += "FAILED: $reason"
                return VnicBringUpResult(false, "", emptyList(), log.toList(), false, reason)
            }
            val args = mapOf("subject" to target.id, "text" to "AT", "host" to host, "port" to port.toString())
            suspend fun runCap(id: String): VnicExecutionOutcome {
                val cap = CapabilityRegistry.get(id)
                    ?: return VnicExecutionOutcome(VnicExecutionState.FAILED, "capability $id is not registered")
                return VnicVerify.verify(id, cap.run(args))
            }
            val connect = runCap("esp32.tcp_connect")
            log += "connect: ${connect.state} — ${connect.evidence}"
            if (connect.state == VnicExecutionState.FAILED) return fail("TCP connect/handshake failed: ${connect.evidence}")
            val enumerate = runCap("esp32.enumerate")
            log += "enumerate: ${enumerate.state} — ${enumerate.evidence}"
            if (enumerate.state == VnicExecutionState.FAILED) return fail("Enumerate failed: ${enumerate.evidence}")
            val status = runCap("esp32.status")
            log += "status: ${status.state} — ${status.evidence}"
            if (status.state == VnicExecutionState.FAILED) return fail("Status check failed: ${status.evidence}")
            if (!session.isOpen()) return fail("Link dropped during bring-up — socket no longer open.")
            val controls = listOf("UART", "GPIO2", "GPIO4")
            log += "Ready: controls mapped (${controls.joinToString()}). Connection held."
            return VnicBringUpResult(
                ready = true,
                route = "esp32-tcp://$host:$port",
                controlLines = controls,
                log = log.toList(),
                connectionHeld = true,
                failReason = null
            )
        }
    }

    // ---- Replay researcher over the live-captured provider bodies ----

    private class ReplayProvider(
        override val name: String,
        private val body: String,
        private val parse: (String, String) -> List<VnicFinding>
    ) : VnicResearchProvider {
        override suspend fun search(query: String, config: VnicResearchConfig): List<VnicFinding> =
            parse(query, body)
    }

    private class StubResearcher(private val findings: List<VnicFinding>) : VnicResearchPort {
        override suspend fun research(query: String): VnicResearchReport =
            VnicResearchReport(query, findings, listOf("stub"), 0, 1)
    }

    // ---- Loopback probe: is JVM loopback TCP actually usable in this environment? ----

    /**
     * A byte-exchange probe, not just a connect: this sandbox's JVM TCP
     * block lets connect() return success while no byte ever arrives, so
     * only a completed PING/PONG proves loopback works.
     */
    private fun loopbackUsable(): Boolean {
        var server: ServerSocket? = null
        var client: Socket? = null
        var accepted: Socket? = null
        return try {
            server = ServerSocket(0, 50, InetAddress.getByName(HOST))
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
            client.connect(InetSocketAddress(HOST, port), 2000)
            client.soTimeout = 2000
            client.getOutputStream().write("PING\r\n".toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
            val reply = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).readLine()
            reply == "PONG"
        } catch (_: Exception) {
            false
        } finally {
            try { client?.close() } catch (_: Exception) {}
            try { accepted?.close() } catch (_: Exception) {}
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun writeEvidence(name: String, content: String) {
        try {
            DEMO_DIR.mkdirs()
            File(DEMO_DIR, name).writeText(content)
        } catch (e: Exception) {
            println("evidence write failed for $name: ${e.message}")
        }
    }

    // ---- The pure halves, proven here regardless of the socket environment ----

    @Test
    fun emulatorProtocolResponds() {
        val p = Esp32EmulatorProtocol()
        assertTrue(p.banner().contains("ESP32-TEST-NODE-01"))
        assertEquals("OK\r\n", p.respond("AT"))
        assertEquals("{\"gpio2\":0,\"gpio4\":0}\r\n", p.respond("GPIO?"))
        assertEquals("GPIO2=1 OK\r\n", p.respond("GPIO2=1"))
        assertEquals("{\"gpio2\":1,\"gpio4\":0}\r\n", p.respond("GPIO?"))
        assertEquals("GPIO4=1 OK\r\n", p.respond("GPIO4=1"))
        assertEquals("{\"gpio2\":1,\"gpio4\":1}\r\n", p.respond("GPIO?"))
        assertEquals("ERR unknown command\r\n", p.respond("BOGUS"))
    }

    @Test
    fun coreBringUpClosesLoopWithFakePort() = runBlocking {
        val mem = InMemoryVnicMemory()
        mem.remember(
            VnicMemoryRecord(
                type = VnicMemoryClass.SEMANTIC,
                content = "ESP32 wifi bluetooth microcontroller UART GPIO",
                confidence = 0.9
            )
        )
        val fakePort = object : VnicBringUpPort {
            override suspend fun bringUp(target: VnicBringUpTarget, note: String): VnicBringUpResult =
                VnicBringUpResult(
                    ready = true, route = "esp32-tcp://127.0.0.1:1",
                    controlLines = listOf("UART", "GPIO2", "GPIO4"),
                    log = listOf("fake bring-up: connect, handshake, enumerate, map, hold"),
                    connectionHeld = true, failReason = null
                )
        }
        var developed: VnicDevelopedProcedure? = null
        val caps = listOf(
            VnicCapabilityInfo("esp32.tcp_connect", "ESP32 TCP connect", "connect esp32 tcp", "CONFIGURE", false)
        )
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()))
        val result = core.process(
            VnicObservation(SUBJECT, OBSERVATION),
            capabilities = caps,
            bringUp = fakePort,
            developProcedure = { _, steps, _ ->
                VnicDevelopedProcedure("esp32.bring_up", steps, true, listOf("fake develop ok"))
                    .also { developed = it }
            }
        )
        assertEquals(VnicExecutionState.CONTROLLABLE, result.executionState)
        assertEquals(true, result.bringUp?.ready)
        assertEquals(true, result.bringUp?.connectionHeld)
        assertEquals(listOf("UART", "GPIO2", "GPIO4"), result.controlsMapped)
        assertTrue(result.learned.any { it.contains("reward=0.9") })
        assertNotNull(developed)
        assertEquals(true, developed!!.testPassed)
        assertTrue(result.phases.any { it.contains("ACT: bring-up ESP32 -> CONTROLLABLE") })
    }

    @Test
    fun failedBringUpEndsFailedWithExactReason() = runBlocking {
        val mem = InMemoryVnicMemory()
        mem.remember(
            VnicMemoryRecord(
                type = VnicMemoryClass.SEMANTIC,
                content = "ESP32 wifi bluetooth microcontroller UART GPIO",
                confidence = 0.9
            )
        )
        val failingPort = object : VnicBringUpPort {
            override suspend fun bringUp(target: VnicBringUpTarget, note: String): VnicBringUpResult =
                VnicBringUpResult(
                    ready = false, route = "", controlLines = emptyList(),
                    log = listOf("no answer from target"),
                    connectionHeld = false, failReason = "No answer from target."
                )
        }
        val caps = listOf(
            VnicCapabilityInfo("esp32.tcp_connect", "ESP32 TCP connect", "connect esp32 tcp", "CONFIGURE", false)
        )
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()))
        val result = core.process(
            VnicObservation(SUBJECT, OBSERVATION),
            capabilities = caps,
            bringUp = failingPort
        )
        assertEquals(VnicExecutionState.FAILED, result.executionState)
        assertTrue(result.executionEvidence.contains("No answer from target."))
        assertTrue(result.learned.any { it.contains("reward=0.2") })
        assertEquals(true, result.bringUp?.connectionHeld == false)
    }

    @Test
    fun kindWithoutPortIsStatedExplicitly() = runBlocking {
        val mem = InMemoryVnicMemory()
        mem.remember(
            VnicMemoryRecord(
                type = VnicMemoryClass.SEMANTIC,
                content = "ESP32 wifi bluetooth microcontroller UART GPIO",
                confidence = 0.9
            )
        )
        var executed: String? = null
        val executor = object : VnicExecutor {
            override suspend fun execute(capabilityId: String, args: Map<String, String>): VnicExecutionOutcome {
                executed = capabilityId
                return VnicExecutionOutcome(VnicExecutionState.VERIFIED, "ok")
            }
        }
        val caps = listOf(
            VnicCapabilityInfo("esp32.tcp_connect", "ESP32 TCP connect", "connect esp32 tcp", "CONFIGURE", false)
        )
        val core = VnicCore(memory = mem, researcher = StubResearcher(emptyList()))
        val result = core.process(
            VnicObservation(SUBJECT, OBSERVATION),
            capabilities = caps,
            executor = executor,
            bringUp = null
        )
        assertTrue(result.phases.any { it.contains("no bring-up port is attached") })
        assertEquals("esp32.tcp_connect", executed)
        assertEquals(VnicExecutionState.VERIFIED, result.executionState)
    }

    // ---- The full closed-loop demonstration (real sockets; gated on loopback) ----

    @Test
    fun closedLoopDemo() = runBlocking {
        val evidence = StringBuilder()
        fun note(s: String) {
            evidence.append(s).append("\n")
            println(s)
        }
        note("=== VNIC CLOSED-LOOP DEMO ===")
        note("Research note: live-captured provider responses, parsed by the real researcher; " +
            "JVM TLS is broken in this sandbox so live HTTPS from the JVM is impossible — " +
            "on-device HTTPS uses the same HttpURLConnection and will be field-tested.")
        note("Emulator: real local TCP ESP32 service on 127.0.0.1 (ephemeral port); " +
            "real bytes; connections held open, never closed after responding.")
        var emulator: TcpEsp32Emulator? = null
        val session = Esp32TcpSession()
        try {
            // Environment gate: prove loopback TCP works before claiming a socket demo.
            Assume.assumeTrue(
                "JVM loopback TCP is black-holed in this sandbox (connect() succeeds but no byte " +
                    "reaches the server; Muse settings -> Permissions -> Direct network protocols: " +
                    "other_tcp is Deny — the same setting that blocks the Gradle build). " +
                    "The full socket demo cannot run here; it runs wherever JVM loopback TCP works.",
                loopbackUsable()
            )
            note("Loopback probe: PING/PONG exchanged — JVM loopback TCP works here.")

            emulator = TcpEsp32Emulator()
            val emuPort = emulator.port
            note("Emulator listening on 127.0.0.1:$emuPort")
            registerEsp32Capabilities(session, emuPort)
            note("Registered real capabilities: esp32.tcp_connect, esp32.enumerate, esp32.send_uart, esp32.status")

            // Research: real researcher, replay over the fresh live-captured bodies.
            val ddgBody = File(DEMO_DIR, "ddg-esp32.html").readText(Charsets.UTF_8)
            val wikiBody = File(DEMO_DIR, "wiki-esp32.json").readText(Charsets.UTF_8)
            val pageBody = File(DEMO_DIR, "page-esp32.html").readText(Charsets.UTF_8)
            val researcher = VnicResearcher(
                providersOverride = listOf(
                    ReplayProvider("duckduckgo", ddgBody) { q, b -> DuckDuckGoProvider().parseResults(q, b) },
                    ReplayProvider("wikipedia", wikiBody) { q, b -> WikipediaProvider().parseResults(q, b) }
                ),
                fetcher = DirectFetchProvider(getOverride = { url, _ ->
                    // Page fetch is replay too: the live-captured Wikipedia page
                    // body is served for its URL; anything else is a 404 (not fetched).
                    if (url.contains("wikipedia.org/wiki/ESP32")) 200 to pageBody else 404 to ""
                })
            )
            note("Researcher: real VnicResearcher, replay over captured DDG (${ddgBody.length} bytes) + " +
                "Wikipedia (${wikiBody.length} bytes) + fetched page (${pageBody.length} bytes).")

            val memory = InMemoryVnicMemory()
            // Executor: the real production verification over real registry dispatch.
            val executor = object : VnicExecutor {
                override suspend fun execute(capabilityId: String, args: Map<String, String>): VnicExecutionOutcome {
                    val cap = CapabilityRegistry.get(capabilityId)
                        ?: return VnicExecutionOutcome(VnicExecutionState.FAILED, "capability $capabilityId is not registered")
                    return VnicVerify.verify(capabilityId, cap.run(args))
                }
            }
            // Procedure development harness: structural validation + READ
            // steps via registry dispatch + assertions + persist.
            val developProcedure: suspend (String, List<String>, Map<String, String>) -> VnicDevelopedProcedure =
                { _, steps, _ ->
                    val notes = mutableListOf<String>()
                    var passed = true
                    val missing = steps.filter { CapabilityRegistry.get(it) == null }
                    if (missing.isNotEmpty()) {
                        passed = false
                        notes += "missing capabilities: ${missing.joinToString()}"
                    }
                    val stepArgs = mapOf(
                        "subject" to SUBJECT, "text" to "AT",
                        "host" to HOST, "port" to emuPort.toString()
                    )
                    for (step in steps) {
                        val cap = CapabilityRegistry.get(step) ?: continue
                        val outcome = executor.execute(step, stepArgs)
                        notes += "$step -> ${outcome.state}: ${outcome.evidence.take(160)}"
                        if (outcome.state == VnicExecutionState.FAILED) passed = false
                        if (cap.readOnly && outcome.state != VnicExecutionState.VERIFIED &&
                            outcome.state != VnicExecutionState.CONNECTED
                        ) passed = false
                    }
                    if (passed) {
                        memory.saveProcedure(
                            VnicProcedure(
                                name = "esp32.bring_up",
                                stepsJson = steps.joinToString(",", "[", "]") { "\"$it\"" },
                                verified = true
                            )
                        )
                        notes += "procedure esp32.bring_up persisted (verified=true)"
                    }
                    VnicDevelopedProcedure("esp32.bring_up", steps, passed, notes)
                }

            val caps = CapabilityRegistry.all()
                .filter { it.id.startsWith("esp32.") }
                .map { VnicCapabilityInfo(it.id, it.title, it.detail, it.riskLevel, it.verified) }
            assertEquals(4, caps.size)
            val core = VnicCore(memory = memory, researcher = researcher)
            val result = core.process(
                VnicObservation(SUBJECT, OBSERVATION),
                capabilities = caps,
                executor = executor,
                bringUp = TcpEsp32BringUpPort(session, HOST, emuPort),
                developProcedure = developProcedure
            )

            // ---- Assertions: the loop closed ----
            note("--- cycle result ---")
            result.phases.forEach { note("  $it") }
            note("action=${result.action} confidence=${result.confidence} state=${result.executionState}")
            note("findings=${result.findings.size} plan=${result.plan}")
            assertTrue("research findings must come from the real provider bodies", result.findings.isNotEmpty())
            assertTrue(result.findings.any { it.source == "duckduckgo" })
            assertTrue(result.findings.any { it.source == "wikipedia" })
            note("findings: " + result.findings.joinToString(" | ") { "${it.title} (${it.source}, ${it.confidence})" })
            assertTrue("plan must select esp32 capabilities", result.plan.any { it.startsWith("esp32.") })
            assertEquals(VnicExecutionState.CONTROLLABLE, result.executionState)
            assertNotNull(result.bringUp)
            assertTrue(result.bringUp!!.ready)
            assertTrue("connection must still be held after the cycle", result.bringUp.connectionHeld)
            assertEquals(listOf("UART", "GPIO2", "GPIO4"), result.controlsMapped)
            assertTrue(result.learned.any { it.contains("reward=0.9") })
            assertTrue(result.phases.any { it.contains("ACT: bring-up ESP32 -> CONTROLLABLE") })

            // ---- Held-connection proof: socket open + a fresh status read returns live data ----
            assertTrue("socket must still be open after the cycle", session.isOpen())
            val statusAfter = executor.execute("esp32.status", emptyMap())
            note("post-cycle status: ${statusAfter.state} — ${statusAfter.evidence}")
            assertTrue(statusAfter.state == VnicExecutionState.VERIFIED || statusAfter.state == VnicExecutionState.CONNECTED)
            assertTrue(statusAfter.evidence.contains("GPIO?"))

            // ---- Follow-up control action over the SAME held connection (the owner driving from here) ----
            val gpioWrite = executor.execute("esp32.send_uart", mapOf("text" to "GPIO2=1"))
            note("follow-up GPIO2=1: ${gpioWrite.state} — ${gpioWrite.evidence}")
            assertTrue(gpioWrite.state != VnicExecutionState.FAILED)
            assertTrue(gpioWrite.evidence.contains("GPIO2=1 OK"))
            assertEquals(1, emulator.protocol.gpio2)
            val gpioRead = executor.execute("esp32.status", emptyMap())
            assertTrue("live read after the write must show gpio2=1", gpioRead.evidence.contains("\"gpio2\":1"))

            // ---- Memories persisted ----
            val counts = memory.counts()
            note("memory counts: $counts procedures=${memory.listProcedures().map { it.name }}")
            assertTrue((counts[VnicMemoryClass.SEMANTIC] ?: 0) > 0)
            assertTrue((counts[VnicMemoryClass.EPISODIC] ?: 0) > 0)
            assertTrue(memory.listProcedures().any { it.name == "esp32.bring_up" && it.verified })
            assertTrue(core.learning.metrics()["feedback_events"] as Int >= 1)

            note("=== DEMO PASSED: identified -> researched -> procedure developed+tested+persisted -> " +
                "connected -> enumerated -> handshook -> controls mapped -> connection HELD -> " +
                "follow-up control action succeeded over the same connection ===")
        } finally {
            session.close()
            emulator?.stop()
            emulator?.let { writeEvidence("emulator-transcript.txt", it.transcript.toString()) }
            writeEvidence("demo-log.txt", evidence.toString())
            println(evidence.toString())
        }
    }
}
