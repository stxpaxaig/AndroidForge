package com.ghosttap

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus
import com.ghosttap.core.describeRssi
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigationInput
import com.ghosttap.core.investigate.InvestigationLedger
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.Investigator
import com.ghosttap.core.investigate.LiveTargetStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import com.ghosttap.core.text.DeviceTextLanguage
import com.ghosttap.core.text.RawBytes

/**
 * Network Tools — real TCP/UDP/HTTP/ping/port-scan over the phone's
 * Wi-Fi / mobile data connection. No external hardware needed.
 *
 * Every result is honest: "sent" means bytes left the phone, "received"
 * means bytes came back. A timeout is reported as a timeout, never as
 * success.
 */
class NetworkToolsViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * How plain text becomes bytes before sending. RawBytes is today's
     * behavior: exactly what was typed, nothing translated.
     */
    val tcpTextLanguage: DeviceTextLanguage = RawBytes

    var log by mutableStateOf<List<String>>(emptyList())
        private set
    var isBusy by mutableStateOf(false)
        private set

    /**
     * One-shot prefill for the PING host box, set by Visual Intel when a
     * photo contains an IP address. The screen consumes it on arrival.
     *
     * Package 5 (B-084): a second prefill no longer overwrites the
     * first — extras wait in a queue and are handed out in order.
     */
    var pingPrefill by mutableStateOf<String?>(null)
        private set

    private val pingPrefillQueue = ArrayDeque<String>()

    fun prefillPing(host: String) {
        if (pingPrefill == null) {
            pingPrefill = host
        } else {
            pingPrefillQueue.addLast(host)
        }
    }

    /** Returns the pending prefill and clears it. Null = nothing pending. */
    fun consumePingPrefill(): String? {
        val v = pingPrefill
        pingPrefill = pingPrefillQueue.removeFirstOrNull()
        return v
    }

    /**
     * One-shot prefill for the TCP box (host + port), set when tapping an
     * open port found by SCAN MY NETWORK. The screen consumes it on arrival.
     *
     * Package 5 (B-085): same queue as the ping prefill — a second tap
     * no longer overwrites the first.
     */
    var tcpPrefill by mutableStateOf<Pair<String, Int>?>(null)
        private set

    private val tcpPrefillQueue = ArrayDeque<Pair<String, Int>>()

    fun prefillTcp(host: String, port: Int) {
        val pre = host to port
        if (tcpPrefill == null) {
            tcpPrefill = pre
        } else {
            tcpPrefillQueue.addLast(pre)
        }
    }

    /** Returns the pending TCP prefill and clears it. Null = nothing pending. */
    fun consumeTcpPrefill(): Pair<String, Int>? {
        val v = tcpPrefill
        tcpPrefill = tcpPrefillQueue.removeFirstOrNull()
        return v
    }

    /** Live hosts found by the last SCAN MY NETWORK run. */
    var liveHosts by mutableStateOf<List<String>>(emptyList())
        private set

    /** Open ports per host, from tapping a live host. Host -> sorted open ports. */
    var openPortsByHost by mutableStateOf<Map<String, List<Int>>>(emptyMap())
        private set

    var isScanningNetwork by mutableStateOf(false)
        private set

    // ---------------- AUTONOMOUS INVESTIGATIONS (NETWORK) ----------------
    /**
     * Latest network findings report, for the network screen's findings
     * panel. Null until the first network target is investigated.
     */
    var latestNetworkReport by mutableStateOf<FindingsReport?>(null)
        private set

    /** Address of the host [latestNetworkReport] describes. */
    var latestInvestigatedHost by mutableStateOf<String?>(null)
        private set

    /** What discovery has actually observed per host, merged across sources. */
    private data class NetworkObservation(
        var answeredProbe: Boolean = false,
        val openPorts: MutableSet<Int> = mutableSetOf(),
        val services: MutableSet<String> = mutableSetOf(),
        var mac: String? = null
    )

    private val networkObservations = mutableMapOf<String, NetworkObservation>()

    /**
     * Per-session fingerprints of already-reported observations. A host is
     * re-investigated only when its observed data actually changes, which
     * is exactly when its fingerprint is new.
     */
    private val investigatedNetworkFingerprints = mutableSetOf<String>()

    /**
     * Investigation trigger. Call whenever discovery learns something new
     * about a host. It READS ONLY what discovery already found — it starts
     * no probe, sends nothing, and changes nothing on the target. The user
     * still presses every connect/control button themselves.
     *
     * Honesty rules, enforced here:
     * - answeredHandshake is true only when the host actually answered a
     *   probe (the ping sweep's answer gate, or a TCP connect that
     *   succeeded). Advertised-only (mDNS) and neighbour-table-only
     *   sightings report false — presence is not proof of a handshake.
     * - Evidence strings carry only observed values: address as seen,
     *   open ports actually connected to, service names actually
     *   announced, hardware address actually seen. No identity is ever
     *   guessed — UNKNOWN stays UNKNOWN.
     * - A failed investigation never breaks discovery: no report is kept
     *   for that fingerprint, so the next new observation retries it.
     */
    private fun investigateNetworkHost(host: String, update: NetworkObservation.() -> Unit) {
        val obs = networkObservations.getOrPut(host) { NetworkObservation() }
        obs.update()
        val fingerprint = buildString {
            append("host=").append(host)
            append(";answered=").append(obs.answeredProbe)
            append(";ports=").append(obs.openPorts.sorted().joinToString(","))
            append(";services=").append(obs.services.sorted().joinToString(";"))
            append(";mac=").append(obs.mac ?: "")
        }
        if (!investigatedNetworkFingerprints.add(fingerprint)) return
        val evidence = mutableListOf<String>()
        evidence.add("Address seen on your Wi-Fi: $host")
        if (obs.answeredProbe) evidence.add("It answered when checked.")
        if (obs.openPorts.isNotEmpty()) {
            evidence.add("Open connection points found: ${obs.openPorts.sorted().joinToString(", ")}.")
        }
        obs.services.forEach { evidence.add("It announced itself as: $it.") }
        obs.mac?.let { evidence.add("Hardware address seen: $it.") }
        val details = mutableMapOf("ip" to host, "answered_probe" to obs.answeredProbe.toString())
        if (obs.openPorts.isNotEmpty()) details["open_ports"] = obs.openPorts.sorted().joinToString(",")
        if (obs.services.isNotEmpty()) details["services"] = obs.services.sorted().joinToString(";")
        obs.mac?.let { details["mac"] = it }
        try {
            val report = Investigator.investigate(
                InvestigationInput(
                    source = InvestigationSource.NETWORK,
                    rawName = host,
                    rawDetails = details,
                    readableValues = evidence,
                    answeredHandshake = obs.answeredProbe,
                    signalText = null,
                    // Stable host id, so a re-check refreshes this host's
                    // report in place instead of duplicating it.
                    targetId = "net:$host"
                )
            )
            InvestigationLedger.upsert(report)
            LiveTargetStore.publish(report, InvestigationSource.NETWORK)
            latestNetworkReport = report
            latestInvestigatedHost = host
        } catch (_: Exception) {
            investigatedNetworkFingerprints.remove(fingerprint)
        }
    }

    // ---------------- DISCOVER (NETWORK + WI-FI) ----------------
    /**
     * One Wi-Fi network the phone's scan actually saw. [security] is a
     * short code the screen uses to decide how Connect works: "OPEN"
     * joins straight away, anything else opens Wi-Fi settings so the
     * user can type the password there. [levelDbm] is the raw signal
     * reading — it is shown only as a signal word, never as a distance.
     */
    data class WifiNetworkInfo(
        val ssid: String,
        val bssid: String,
        val security: String,
        val levelDbm: Int
    )

    /** Wi-Fi networks found by the last DISCOVER scan, strongest first. */
    var wifiNetworks by mutableStateOf<List<WifiNetworkInfo>>(emptyList())
        private set

    /**
     * DISCOVER (Network): plain-words note from the last Wi-Fi scan —
     * Wi-Fi off, scan blocked, nothing found, or how many were found.
     * Null = nothing to say.
     */
    var wifiDiscoverNote by mutableStateOf<String?>(null)
        private set

    /**
     * DISCOVER (Network): security code per investigated Wi-Fi network,
     * keyed by its stable report id ("wifi:<network>"). The screen reads
     * this to decide whether a network's Connect button joins it
     * directly (open) or asks for the password right here (needs one).
     */
    var wifiSecurityById by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private var wifiScanReceiver: BroadcastReceiver? = null

    /**
     * DISCOVER (Network): run every discovery this screen already has,
     * on demand and with no typing — the Wi-Fi network scan, the
     * live-device sweep, the service listener, and (only while Ghost
     * Tap's own hotspot is on) who joined it. Every thing found is
     * investigated and listed in this section; nothing connects until
     * the user taps a finding.
     */
    fun discoverNetwork() {
        scanWifiNetworks()
        scanMyNetwork()
        discoverServices()
        if (ghostApLive) refreshGhostApClients()
    }

    /**
     * DISCOVER (Network): list the Wi-Fi networks the phone can actually
     * see, and investigate each one. Ghost Tap attempts the scan on
     * its own terms and reports what actually happened — no setting
     * is read as a reason to refuse. Signal is shown only as a word
     * (Strong/Weak…) — never a distance.
     * - The phone limits how often apps may scan: when it refuses a
     *   scan, the screen says to wait and tap again.
     * - A scan result list also needs location on: when nothing comes
     *   back, the screen says so instead of showing an empty list as
     *   if no networks existed.
     */
    fun scanWifiNetworks() {
        val wifi = getApplication<Application>().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            wifiDiscoverNote = "This phone can't list Wi-Fi networks."
            return
        }
        registerWifiScanReceiver()
        val started = runCatching { wifi.startScan() }.getOrDefault(false)
        if (!started) {
            // The phone refused a fresh scan (it only allows a few per
            // minute) — fall back to the networks it already knows, and
            // say plainly if even those are empty.
            processWifiScanResults()
            if (wifiNetworks.isEmpty()) {
                wifiDiscoverNote = if (!wifi.isWifiEnabled) {
                    // Attempted and reported: the scan itself is what
                    // found nothing, because Wi-Fi is off right now.
                    "Couldn't look for Wi-Fi networks — Wi-Fi is off on the phone right now."
                } else {
                    "The phone wouldn't start a Wi-Fi scan right now — it only allows a few scans per minute. Wait a bit and tap again."
                }
            }
            return
        }
        wifiDiscoverNote = "Looking for Wi-Fi networks…"
        append("Wi-Fi scan — looking for networks…")
    }

    /** Plain-words log line after a Wi-Fi join tap, so the network screen says what happened. */
    fun noteWifiJoinAttempt(ssid: String, open: Boolean) {
        append(
            if (open) "Wi-Fi join — asked to connect to '$ssid'. The first time, the phone asks you to approve it."
            else "Wi-Fi join — type the password for '$ssid' right here, then Ghost Tap connects."
        )
    }

    /**
     * Ghost Tap password prompt for one secured Wi-Fi network: tapping
     * Connect on a network that needs a password asks for it right
     * here, in Ghost Tap — never a detour anywhere else. Null = no
     * prompt showing.
     */
    var wifiPasswordPromptSsid by mutableStateOf<String?>(null)
        private set

    /** The password typed for the network the prompt is showing. */
    var wifiPasswordText by mutableStateOf("")
        private set

    /** Plain-words note under the password prompt ("type it first", …). Null = nothing to say. */
    var wifiPasswordNote by mutableStateOf<String?>(null)
        private set

    fun askWifiPassword(ssid: String) {
        wifiPasswordPromptSsid = ssid
        wifiPasswordText = ""
        wifiPasswordNote = null
    }

    fun enterWifiPasswordText(value: String) {
        wifiPasswordText = value
        wifiPasswordNote = null
    }

    fun showWifiPasswordNote(value: String?) {
        wifiPasswordNote = value
    }

    fun cancelWifiPassword() {
        wifiPasswordPromptSsid = null
        wifiPasswordText = ""
        wifiPasswordNote = null
    }

    private fun registerWifiScanReceiver() {
        if (wifiScanReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    processWifiScanResults()
                }
            }
        }
        wifiScanReceiver = receiver
        val app = getApplication<Application>()
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            app.registerReceiver(receiver, filter)
        }
    }

    /**
     * Turns the phone's Wi-Fi scan results into findings. Only networks
     * the phone actually saw are listed — nothing is invented, and a
     * network with no name stays unnamed (UNKNOWN) instead of guessing.
     * One entry per network (strongest reading wins when the phone saw
     * the same network twice); strongest first.
     */
    private fun processWifiScanResults() {
        val wifi = getApplication<Application>().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val results = runCatching { wifi.scanResults }.getOrNull().orEmpty()
        if (results.isEmpty()) {
            wifiNetworks = emptyList()
            wifiDiscoverNote = "No Wi-Fi networks found — if location is off, the phone can't list networks."
            return
        }
        val seen = linkedMapOf<String, ScanResult>()
        results.sortedByDescending { it.level }.forEach { result ->
            val key = result.BSSID?.takeIf { it.isNotBlank() } ?: result.SSID
            if (!key.isNullOrBlank()) seen.putIfAbsent(key, result)
        }
        val infos = seen.values.map { result ->
            WifiNetworkInfo(
                ssid = result.SSID ?: "",
                bssid = result.BSSID ?: "",
                security = wifiSecurityOf(result),
                levelDbm = result.level
            )
        }
        wifiNetworks = infos
        wifiSecurityById = infos.associate { "wifi:${it.bssid.ifBlank { it.ssid }}" to it.security }
        infos.forEach { investigateWifiNetwork(it) }
        wifiDiscoverNote = "Found ${infos.size} Wi-Fi network(s) — tap one to join it. Nothing joins until you tap."
        append("Wi-Fi scan — found ${infos.size} network(s).")
    }

    /** Security of a scanned network, from what the phone reported. OPEN = no password needed. */
    private fun wifiSecurityOf(result: ScanResult): String {
        val caps = result.capabilities.uppercase()
        return when {
            "WPA3" in caps -> "WPA3"
            "WPA2" in caps -> "WPA2"
            "WPA" in caps -> "WPA"
            "WEP" in caps -> "WEP"
            else -> "OPEN"
        }
    }

    /**
     * Investigation trigger for one scanned Wi-Fi network. A scan
     * sighting is NOT a handshake — answeredHandshake stays false, so
     * the report never claims the network answered. Signal is shown
     * only as a word, never as a distance.
     */
    private fun investigateWifiNetwork(info: WifiNetworkInfo) {
        val id = "wifi:${info.bssid.ifBlank { info.ssid }}"
        val securityWords = when (info.security) {
            "OPEN" -> "Open — no password needed"
            else -> "Needs a password (${info.security})"
        }
        val readable = mutableListOf<String>()
        if (info.ssid.isNotBlank()) readable += "Network name: ${info.ssid}"
        readable += "Security: $securityWords"
        runCatching {
            val report = Investigator.investigate(
                InvestigationInput(
                    source = InvestigationSource.NETWORK,
                    rawName = info.ssid.takeIf { it.isNotBlank() },
                    rawDetails = mapOf(
                        "ssid" to info.ssid,
                        "bssid" to info.bssid,
                        "security" to info.security
                    ),
                    readableValues = readable,
                    answeredHandshake = false,
                    signalText = "${info.levelDbm} dBm — ${describeRssi(info.levelDbm)}",
                    targetId = id
                )
            )
            InvestigationLedger.upsert(report)
            LiveTargetStore.publish(report, InvestigationSource.NETWORK)
            latestNetworkReport = report
        }
    }

    /**
     * SCAN MY NETWORK — finds every live device on the phone's Wi-Fi with
     * zero typing. Ping-sweeps the /24 subnet with bounded parallelism.
     * Honest throughout: only hosts that actually answered are listed.
     */
    fun scanMyNetwork() {
        if (isScanningNetwork || isBusy) return
        isScanningNetwork = true
        val previousHosts = liveHosts.toSet()
        liveHosts = emptyList()
        openPortsByHost = emptyMap()
        append("Network scan — finding your Wi-Fi network…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
                val ipInt = wifi.connectionInfo.ipAddress
                if (ipInt == 0) {
                    withContext(Dispatchers.Main) {
                        append("Network scan — the phone isn't on a Wi-Fi network right now, so there's nothing to check.")
                        isScanningNetwork = false
                    }
                    return@launch
                }
                // Android reports the IP little-endian.
                val base = "${ipInt and 0xff}.${(ipInt shr 8) and 0xff}.${(ipInt shr 16) and 0xff}"
                withContext(Dispatchers.Main) {
                    append("Network scan — checking $base.1 to $base.254…")
                }
                val found = mutableListOf<String>()
                // Bounded parallelism: 32 at a time so 254 hosts take seconds, not minutes.
                for (chunk in (1..254).chunked(32)) {
                    val results = chunk.map { i ->
                        async(Dispatchers.IO) {
                            val host = "$base.$i"
                            try {
                                if (InetAddress.getByName(host).isReachable(800)) host else null
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll()
                    results.filterNotNull().forEach { found.add(it) }
                    withContext(Dispatchers.Main) {
                        liveHosts = found.sorted()
                    }
                }
                withContext(Dispatchers.Main) {
                    // Event bus: hosts that actually answered the ping sweep.
                    // (MAC/hostname aren't known from a ping — left blank, honestly.)
                    for (h in found.filter { it !in previousHosts }) {
                        try {
                            IntelEventBus.publish(
                                IntelEvent.NetworkHostFound(ip = h, mac = "", hostname = "")
                            )
                        } catch (_: Exception) {
                        }
                    }
                    liveHosts = found.sorted()
                    // Autonomous investigation trigger: every host that
                    // actually answered the ping sweep is a new target.
                    // (Repeat scans of the same hosts report nothing new.)
                    for (h in found) {
                        investigateNetworkHost(h) { answeredProbe = true }
                    }
                    append(
                        if (found.isEmpty()) "Network scan — done. No devices answered on this network."
                        else "Network scan — done. Found ${found.size} live device(s). Tap one to see its open ports."
                    )
                    isScanningNetwork = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    append("Network scan — something went wrong: ${e.message ?: "unknown problem"}")
                    isScanningNetwork = false
                }
            }
        }
    }

    /**
     * Port scan for one host tapped from SCAN MY NETWORK results. Same real
     * TCP connects as portScan(), but the open ports are also stored per host
     * so the UI can list them under the tapped device.
     */
    fun scanPortsForHost(host: String) {
        if (isBusy) return
        isBusy = true
        append("Port check — $host…")
        val logBefore = log.size
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val open = mutableListOf<Int>()
                val ports = listOf(21, 22, 23, 80, 443, 8080, 1883, 9000)
                for (port in ports) {
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(host, port), 1500)
                        runCatching { socket.close() }
                        open.add(port)
                        append("Port check — $port is open")
                    } catch (_: Exception) {
                        // closed/filtered — not listed as open
                    }
                }
                withContext(Dispatchers.Main) {
                    openPortsByHost = openPortsByHost + (host to open.sorted())
                    // Autonomous investigation trigger: the port check
                    // observed new data for this host (a TCP connect that
                    // succeeded also counts as answering a probe).
                    investigateNetworkHost(host) {
                        answeredProbe = true
                        openPorts.addAll(open)
                    }
                    append(
                        if (open.isEmpty()) "Port check — $host: nothing open on the usual ports."
                        else "Port check — $host: open ports ${open.sorted().joinToString(", ")}."
                    )
                    isBusy = false
                    pubNetOp("net.port_scan", logBefore, true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    append("Port check — something went wrong: ${e.message ?: "unknown problem"}")
                    isBusy = false
                    pubNetOp("net.port_scan", logBefore, false)
                }
            }
        }
    }

    private var udpJob: Job? = null
    private var udpSocket: DatagramSocket? = null

    private fun append(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        log = (log + "[$ts] $line").takeLast(200)
    }

    /**
     * Event bus: every finished network tool op reports its real output to
     * the intelligence engine. Takes the log lines the op produced.
     */
    private fun pubNetOp(id: String, logBefore: Int, ok: Boolean) {
        try {
            val lines = log.drop(logBefore).takeLast(6).joinToString("\n")
            IntelEventBus.publish(IntelEvent.ToolOpResult(id, ok, lines.take(1200)))
        } catch (_: Exception) {
        }
    }

    fun clearLog() {
        log = emptyList()
    }

    /** TCP: connect, send a message, read any reply until timeout. */
    fun tcpTest(host: String, port: Int, message: String, timeoutMs: Int = 5000) {
        if (isBusy) return
        isBusy = true
        append("Connecting to $host:$port…")
        val logBefore = log.size
        var opFailed = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                append("Connected to $host:$port")
                // Banner grab: some servers speak first (SSH, FTP, SMTP…).
                // Short timeout so the send path never waits on it.
                val bannerBuf = ByteArray(512)
                val bannerN = try {
                    socket.soTimeout = 1500
                    socket.getInputStream().read(bannerBuf)
                } catch (e: java.net.SocketTimeoutException) {
                    -1 // No banner — normal. Send path continues.
                } catch (e: Exception) {
                    -1
                }
                socket.soTimeout = timeoutMs
                if (bannerN > 0) {
                    append("First message ($bannerN bytes): ${bannerBuf.asciiSafe(bannerN)}")
                }
                val out = socket.getOutputStream()
                val bytes = tcpTextLanguage.encode(message)
                out.write(bytes)
                out.flush()
                append("Sent ${bytes.size} bytes. (Sent doesn't mean the device acted on it.)")
                // Read reply — may time out if the server doesn't answer.
                val buf = ByteArray(4096)
                val n = try {
                    socket.getInputStream().read(buf)
                } catch (e: java.net.SocketTimeoutException) {
                    -1 // No reply within timeout — normal for many servers.
                }
                if (n > 0) {
                    val reply = String(buf, 0, n, Charsets.UTF_8)
                    append("Received $n bytes: $reply")
                } else {
                    append("No reply within ${timeoutMs}ms (the other end may not answer).")
                }
                runCatching { socket.close() }
                append("Connection closed.")
            } catch (e: java.net.UnknownHostException) {
                opFailed = true
                append("Couldn't find \"$host\". Check the address.")
            } catch (e: java.net.ConnectException) {
                opFailed = true
                append("Connection refused at $host:$port. That port may be closed, or the device may be off.")
            } catch (e: java.net.SocketTimeoutException) {
                opFailed = true
                append("Timed out connecting to $host:$port — the device didn't answer in time.")
            } catch (e: Exception) {
                opFailed = true
                append("Something went wrong: ${e.message ?: "unknown problem"}")
            } finally {
                withContext(Dispatchers.Main) {
                    isBusy = false
                    pubNetOp("net.tcp_connect", logBefore, !opFailed)
                }
            }
        }
    }

    /** Render bytes as printable ASCII; non-printables become dots. */
    private fun ByteArray.asciiSafe(n: Int): String = buildString {
        for (i in 0 until n.coerceAtMost(size)) {
            val b = this@asciiSafe[i].toInt() and 0xFF
            append(
                when {
                    b in 32..126 -> b.toChar()
                    b == 10 -> "\\n"
                    b == 13 -> "\\r"
                    b == 9 -> "\\t"
                    else -> "."
                }
            )
        }
    }

    // ---------------- WHAT'S TALKING: mDNS/NSD service discovery ----------------

    /** One advertised service: name, resolved host, port, and service type. */
    data class NsdEntry(val name: String, val host: String, val port: Int, val type: String)

    /** Services found by the last WHAT'S TALKING run. */
    var nsdServices by mutableStateOf<List<NsdEntry>>(emptyList())
        private set

    var isDiscoveringNsd by mutableStateOf(false)
        private set

    private var nsdManager: NsdManager? = null
    private val nsdDiscoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val nsdSeen = mutableSetOf<String>()

    private val nsdTypes = listOf(
        "_http._tcp.", "_https._tcp.", "_printer._tcp.", "_ssh._tcp.",
        "_smb._tcp.", "_ftp._tcp.", "_telnet._tcp.", "_mqtt._tcp."
    )

    /**
     * WHAT'S TALKING — finds every service advertising on the local network
     * via mDNS/NSD. No typing, no guessing ports. Tapping a result opens it:
     * web services fetch, everything else fills the TCP box.
     */
    fun discoverServices() {
        if (isDiscoveringNsd) return
        val mgr = getApplication<Application>()
            .getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (mgr == null) {
            append("What's talking — this phone can't look for shared services.")
            return
        }
        nsdManager = mgr
        nsdServices = emptyList()
        nsdSeen.clear()
        isDiscoveringNsd = true
        append("What's talking — listening for services other devices are sharing…")
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = runCatching { serviceInfo.host?.hostAddress }.getOrNull()
                    ?: return
                val key = "${serviceInfo.serviceType}|${serviceInfo.serviceName}"
                if (!nsdSeen.add(key)) return
                val entry = NsdEntry(
                    name = serviceInfo.serviceName ?: "unknown",
                    host = host,
                    port = serviceInfo.port,
                    type = (serviceInfo.serviceType ?: "").trimEnd('.')
                )
                nsdServices = (nsdServices + entry).sortedBy { it.host }
                append("What's talking — ${entry.name} at ${entry.host}:${entry.port} (${entry.type})")
                // Autonomous investigation trigger: a service that
                // actually announced itself. Advertisement alone is NOT
                // a handshake, so answeredProbe stays as previously
                // observed (false unless the host also answered a probe).
                investigateNetworkHost(host) {
                    services.add("${entry.name} (${entry.type}, port ${entry.port})")
                }
                // Intelligence: a real advertised service on the real network.
                try {
                    IntelEventBus.publish(
                        IntelEvent.ToolOpResult(
                            "net.nsd", true,
                            "Found '${entry.name}' at ${entry.host}:${entry.port} (${entry.type})."
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
        for (type in nsdTypes) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    append("What's talking — couldn't look for $serviceType (code $errorCode).")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        mgr.resolveService(service, resolveListener)
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
            }
            nsdDiscoveryListeners.add(listener)
            runCatching { mgr.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { append("What's talking — couldn't start looking for $type.") }
        }
    }

    /** Stop WHAT'S TALKING discovery. Safe to call when not discovering. */
    fun stopDiscoverServices() {
        val mgr = nsdManager
        for (listener in nsdDiscoveryListeners) {
            runCatching { mgr?.stopServiceDiscovery(listener) }
        }
        nsdDiscoveryListeners.clear()
        nsdManager = null
        if (isDiscoveringNsd) {
            isDiscoveringNsd = false
            append("What's talking — stopped listening.")
        }
    }

    // ---------------- GHOST AP: Ghost Tap's own hotspot ----------------

    /**
     * GHOST AP — Ghost Tap's own Wi-Fi hotspot, created and owned by the app
     * (not the phone's settings). Connect a target device to it for testing:
     * the phone is the network, so everything the target does is visible here.
     * Local-only: no internet is shared through it.
     */
    var ghostApLive by mutableStateOf(false)
        private set
    var ghostApSsid by mutableStateOf<String?>(null)
        private set
    var ghostApPassword by mutableStateOf<String?>(null)
        private set

    /**
     * Devices that joined the Ghost hotspot (Package 5, B-094), as
     * observed in the phone's neighbour table: "IP address — hardware
     * address". Addresses only — the phone can't ask their names, so
     * none are invented. Empty until [refreshGhostApClients] runs.
     */
    var ghostApClients by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * Re-reads who is on the network while the hotspot is on. Only
     * complete neighbour entries count (a half-seen address is not a
     * device that joined).
     */
    fun refreshGhostApClients() {
        viewModelScope.launch(Dispatchers.IO) {
            val found = mutableListOf<String>()
            runCatching {
                java.io.File("/proc/net/arp").readLines().drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 6) {
                        val ip = parts[0]
                        val flags = parts[2]
                        val mac = parts[3]
                        if (flags == "0x2" && mac != "00:00:00:00:00:00") {
                            found.add("$ip — $mac")
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                ghostApClients = found.sorted()
                // Autonomous investigation trigger: a device actually
                // seen in the neighbour table. Table presence alone is
                // NOT a handshake, so answeredProbe stays false here.
                for (client in found) {
                    val ip = client.substringBefore(" — ").trim()
                    val seenMac = client.substringAfter(" — ", "").trim()
                    if (ip.isNotEmpty()) {
                        investigateNetworkHost(ip) {
                            if (seenMac.isNotEmpty()) mac = seenMac
                        }
                    }
                }
            }
        }
    }

    private var ghostApReservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun startGhostAp() {
        if (ghostApLive) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            append("Ghost hotspot needs Android 8 or newer.")
            return
        }
        try {
            val wifi = getApplication<Application>()
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        ghostApReservation = reservation
                        val ssid: String?
                        val pass: String?
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val c = runCatching { reservation.softApConfiguration }.getOrNull()
                            ssid = c?.ssid
                            pass = c?.passphrase
                        } else {
                            @Suppress("DEPRECATION")
                            val c = reservation.wifiConfiguration
                            ssid = c?.SSID
                            pass = c?.preSharedKey
                        }
                        ghostApSsid = ssid
                        ghostApPassword = pass
                        ghostApLive = true
                        append(
                            "Ghost hotspot is live — Network name: $ssid / Password: $pass — " +
                                "connect a device to it to test."
                        )
                        try {
                            IntelEventBus.publish(
                                IntelEvent.ToolOpResult(
                                    "net.ghost_ap", true,
                                    "Ghost hotspot is live — network name: $ssid."
                                )
                            )
                        } catch (_: Exception) {
                        }
                    }

                    override fun onStopped() {
                        ghostApLive = false
                        ghostApSsid = null
                        ghostApPassword = null
                        ghostApClients = emptyList()
                        append("Ghost hotspot off.")
                    }

                    override fun onFailed(reason: Int) {
                        append("Ghost hotspot didn't start — try again.")
                        try {
                            IntelEventBus.publish(
                                IntelEvent.IntelError("Ghost hotspot", "couldn't start — try again")
                            )
                        } catch (_: Exception) {
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: SecurityException) {
            append("Ghost hotspot is blocked by the phone — check the app's permissions in settings.")
        } catch (e: Exception) {
            append("Ghost hotspot couldn't start — try again.")
        }
    }

    fun stopGhostAp() {
        runCatching { ghostApReservation?.close() }
        ghostApReservation = null
        if (ghostApLive) {
            ghostApLive = false
            ghostApSsid = null
            ghostApPassword = null
            ghostApClients = emptyList()
            append("Ghost hotspot off.")
        }
    }

    /** UDP: send a datagram, listen briefly for a reply. */
    fun udpTest(host: String, port: Int, message: String, listenMs: Int = 3000) {        if (isBusy) return
        isBusy = true
        append("Sending to $host:$port…")
        val logBefore = log.size
        var opFailed = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val addr = InetAddress.getByName(host)
                val socket = DatagramSocket()
                socket.soTimeout = listenMs
                udpSocket = socket
                val bytes = message.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(bytes, bytes.size, addr, port)
                socket.send(packet)
                append("Sent ${bytes.size} bytes to $host:$port. (No confirmation that it arrived — that's normal for this kind of message.)")
                // Listen for a reply.
                val buf = ByteArray(4096)
                val replyPacket = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(replyPacket)
                    val reply = String(replyPacket.data, 0, replyPacket.length, Charsets.UTF_8)
                    append("Reply from ${replyPacket.address.hostAddress}:${replyPacket.port}: $reply")
                } catch (e: java.net.SocketTimeoutException) {
                    append("No reply within ${listenMs}ms (normal — this kind of message gets no confirmation).")
                }
                runCatching { socket.close() }
                udpSocket = null
            } catch (e: java.net.UnknownHostException) {
                opFailed = true
                append("Couldn't find \"$host\".")
            } catch (e: Exception) {
                opFailed = true
                append("Something went wrong: ${e.message ?: "unknown problem"}")
            } finally {
                withContext(Dispatchers.Main) {
                    isBusy = false
                    pubNetOp("net.udp_send", logBefore, !opFailed)
                }
            }
        }
    }

    /** HTTP GET: fetch a URL, show status + first part of body. */
    fun httpGet(rawUrl: String) {
        if (isBusy) return
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
        isBusy = true
        append("Fetching $url…")
        val logBefore = log.size
        var opFailed = false
        viewModelScope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "GHOST-TAP/1.0")
                val code = conn.responseCode
                append("Answer: $code ${conn.responseMessage}")
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.let {
                    BufferedReader(InputStreamReader(it)).readText().take(1000)
                } ?: "(no text)"
                append("Page text (first 1000 characters): $body")
            } catch (e: java.net.UnknownHostException) {
                opFailed = true
                append("Couldn't find that site. Check the web address.")
            } catch (e: java.net.ConnectException) {
                opFailed = true
                append("Connection refused. The site may be down or the port may be closed.")
            } catch (e: Exception) {
                opFailed = true
                append("Something went wrong: ${e.message ?: "unknown problem"}")
            } finally {
                runCatching { conn?.disconnect() }
                withContext(Dispatchers.Main) {
                    isBusy = false
                    pubNetOp("net.http_fetch", logBefore, !opFailed)
                }
            }
        }
    }

    /** Ping: ICMP reachability check (may fail on networks that block ICMP). */
    fun ping(host: String) {
        if (isBusy) return
        isBusy = true
        append("Checking $host…")
        val logBefore = log.size
        var opFailed = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val addr = InetAddress.getByName(host)
                val start = System.currentTimeMillis()
                val reachable = addr.isReachable(4000)
                val ms = System.currentTimeMillis() - start
                if (reachable) {
                    append("$host (${addr.hostAddress}) answered in ${ms}ms.")
                } else {
                    append("$host didn't answer (it may block this check, or be offline).")
                }
            } catch (e: java.net.UnknownHostException) {
                opFailed = true
                append("Couldn't find \"$host\".")
            } catch (e: Exception) {
                opFailed = true
                append("Something went wrong: ${e.message ?: "unknown problem"}")
            } finally {
                withContext(Dispatchers.Main) {
                    isBusy = false
                    pubNetOp("net.ping", logBefore, !opFailed)
                }
            }
        }
    }

    /** Port scan: check a list of common ports on a host. */
    fun portScan(host: String, ports: List<Int> = listOf(21, 22, 23, 80, 443, 8080, 1883, 9000)) {
        if (isBusy) return
        isBusy = true
        append("Port check — $host (ports: ${ports.joinToString(", ")})…")
        val logBefore = log.size
        var opFailed = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val open = mutableListOf<Int>()
                val closed = mutableListOf<Int>()
                for (port in ports) {
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(host, port), 1500)
                        runCatching { socket.close() }
                        open.add(port)
                        append("Port check — $port is open")
                    } catch (_: Exception) {
                        closed.add(port)
                    }
                }
                append("Port check — done. Open: ${open.ifEmpty { "none" }}. Closed or blocked: ${closed.joinToString(", ")}")
            } catch (e: Exception) {
                opFailed = true
                append("Port check — something went wrong: ${e.message ?: "unknown problem"}")
            } finally {
                withContext(Dispatchers.Main) {
                    isBusy = false
                    pubNetOp("net.port_scan", logBefore, !opFailed)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { udpSocket?.close() }
        udpJob?.cancel()
        stopDiscoverServices()
        runCatching { ghostApReservation?.close() }
        ghostApReservation = null
        runCatching {
            wifiScanReceiver?.let { getApplication<Application>().unregisterReceiver(it) }
        }
        wifiScanReceiver = null
    }
}
