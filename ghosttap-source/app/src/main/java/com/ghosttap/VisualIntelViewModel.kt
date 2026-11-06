package com.ghosttap

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigationInput
import com.ghosttap.core.investigate.InvestigationLedger
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.Investigator
import com.ghosttap.core.investigate.LiveTargetStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.io.File
import java.util.UUID

/**
 * Visual Intel: point the camera at boards, chips, labels, and devices.
 * Reads text (OCR), scans barcodes/QR codes, pulls out candidate part
 * markings, and saves investigations as target profiles.
 *
 * Everything the ML models infer is labeled a candidate — never fact.
 * Always verify against the real object before acting on it.
 */

@Serializable
data class BarcodeFinding(
    val rawValue: String,
    val formatName: String,
    val valueTypeName: String
)

@Serializable
data class VisualFinding(
    val id: String,
    val timestampMs: Long,
    val source: String,
    val fullText: String,
    val textBlocks: List<String>,
    val barcodes: List<BarcodeFinding>,
    val markings: List<String>,
    val wifiSummary: String? = null
)

@Serializable
data class VisualProfile(
    val id: String,
    val name: String,
    val notes: String,
    val createdMs: Long,
    val findings: List<VisualFinding>
)

class VisualIntelViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------- UI state ----------
    var cameraError by mutableStateOf<String?>(null)
        private set
    var isAnalyzing by mutableStateOf(false)
        private set
    var lastFinding by mutableStateOf<VisualFinding?>(null)
        private set
    /**
     * Latest Autonomous Investigation report for a visual finding, or null
     * when the last photo produced nothing readable to investigate. Set only
     * by [runVisualInvestigation]; the screen shows it below the analysis
     * output. Report-only: it never sends the image anywhere and never
     * touches a device.
     */
    var latestVisualReport by mutableStateOf<FindingsReport?>(null)
        private set
    /** Finding ids already investigated, so one analysis is never reported twice. */
    private val investigatedFindingIds = mutableSetOf<String>()
    /** Reports keyed by extracted-content hash, so re-reading the same photo reuses its report. */
    private val visualReportsByContent = mutableMapOf<String, FindingsReport>()
    var statusLine by mutableStateOf("Point the camera at a board, chip, label, or device.")
        private set
    val profiles: SnapshotStateList<VisualProfile> = mutableStateListOf()

    // ---------- CameraX ----------
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var boundPreviewView: PreviewView? = null

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (boundPreviewView === previewView && cameraProvider != null) return
        boundPreviewView = previewView
        try {
            val future = ProcessCameraProvider.getInstance(app)
            future.addListener({
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    val capture = ImageCapture.Builder().build()
                    imageCapture = capture
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture
                    )
                    postMain { cameraError = null }
                } catch (e: Exception) {
                    postMain { cameraError = "Camera didn't start: ${e.message ?: "unknown problem"}" }
                }
            }, ContextCompat.getMainExecutor(app))
        } catch (e: Exception) {
            cameraError = "Camera didn't start: ${e.message ?: "unknown problem"}"
        }
    }

    fun unbindCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        boundPreviewView = null
    }

    fun captureAndAnalyze() {
        val capture = imageCapture
        if (capture == null) {
            statusLine = "Camera is not ready yet — wait a moment and try again."
            return
        }
        isAnalyzing = true
        statusLine = "Reading the photo…"
        capture.takePicture(
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    analyzeImageProxy(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    isAnalyzing = false
                    statusLine = "Couldn't take the photo: ${exception.message ?: "unknown problem"}"
                }
            }
        )
    }

    private fun analyzeImageProxy(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            isAnalyzing = false
            statusLine = "The photo came out blank — try again."
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        runTextAndBarcode(input, "camera") { proxy.close() }
    }

    fun analyzeGalleryImage(uri: Uri) {
        isAnalyzing = true
        statusLine = "Reading the photo…"
        val input = try {
            InputImage.fromFilePath(app, uri)
        } catch (e: Exception) {
            isAnalyzing = false
            statusLine = "Couldn't open that photo: ${e.message ?: "unknown problem"}"
            return
        }
        runTextAndBarcode(input, "gallery")
    }

    // ---------- ML Kit pipeline ----------
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    }
    private val barcodeScanner by lazy {
        BarcodeScanning.getClient()
    }

    private fun runTextAndBarcode(input: InputImage, source: String, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val visionText = Tasks.await(textRecognizer.process(input))
                val barcodeList = Tasks.await(barcodeScanner.process(input))
                val finding = buildFinding(visionText, barcodeList, source)
                withContext(Dispatchers.Main) {
                    lastFinding = finding
                    visualActionFallback = null
                    statusLine = describeFinding(finding)
                    // Intelligence: the analyzed image enters the pipeline —
                    // the engine auto-opens an investigation and starts the
                    // READ-only loop on the extracted identifiers.
                    try {
                        IntelEventBus.publish(
                            IntelEvent.VisualCapture(
                                source = source,
                                text = finding.fullText.take(2000),
                                barcodes = finding.barcodes
                                    .joinToString("; ") { "${it.formatName}: ${it.rawValue}" }
                                    .take(500),
                                markings = finding.markings.joinToString(", ").take(500)
                            )
                        )
                        statusLine = describeFinding(finding) +
                            " — saved to the investigations timeline."
                    } catch (_: Exception) {
                    }
                    // Autonomous Investigation (VISUAL): what the camera
                    // actually read is reported, nothing more. A picture
                    // can never prove a device answers, so
                    // answeredHandshake is always false here.
                    runVisualInvestigation(finding)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusLine = "Couldn't read the photo: ${e.message ?: "unknown problem"}"
                    try {
                        IntelEventBus.publish(
                            IntelEvent.IntelError(
                                "Visual Intel",
                                "Couldn't read the photo: ${e.message ?: "unknown problem"}".take(500)
                            )
                        )
                    } catch (_: Exception) {
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    onDone()
                }
            }
        }
    }

    /**
     * Autonomous Investigation trigger for a completed image analysis.
     * Runs once per analysis result (deduped by finding id) and once per
     * distinct extracted content (deduped by content hash, so re-reading
     * the same photo reuses its report instead of stacking duplicates).
     *
     * Evidence is ONLY what the camera actually extracted: read text,
     * candidate markings, and code values. A Wi-Fi code's login details
     * are never passed along — only the fact that a Wi-Fi code was found.
     * answeredHandshake is always false: a picture cannot prove a device
     * answers, and this report must never claim otherwise. The image
     * itself is never sent anywhere, and no device action is triggered.
     * Any failure here is swallowed on purpose: investigation is
     * report-only and must never break the analysis display.
     */
    private fun runVisualInvestigation(finding: VisualFinding) {
        if (finding.fullText.isBlank() && finding.barcodes.isEmpty() && finding.markings.isEmpty()) {
            latestVisualReport = null
            return
        }
        val contentKey = buildString {
            append(finding.fullText)
            append("\n")
            finding.barcodes.forEach { append(it.formatName).append(":").append(it.rawValue).append("\n") }
            finding.markings.forEach { append(it).append("\n") }
        }.hashCode().toString()
        visualReportsByContent[contentKey]?.let { existing ->
            latestVisualReport = existing
            return
        }
        if (!investigatedFindingIds.add(finding.id)) return
        try {
            val report = Investigator.investigate(
                InvestigationInput(
                    source = InvestigationSource.VISUAL,
                    rawName = null,
                    rawDetails = mapOf(
                        "extractionSource" to finding.source,
                        "text" to finding.fullText,
                        "markings" to finding.markings.joinToString(", "),
                        "codes" to finding.barcodes.joinToString("; ") { bc ->
                            if (bc.rawValue.startsWith("WIFI:")) {
                                "${bc.formatName}: Wi-Fi code found (login details not included)"
                            } else {
                                "${bc.formatName}: ${bc.rawValue}"
                            }
                        }
                    ),
                    readableValues = buildList {
                        finding.textBlocks.filter { it.isNotBlank() }.forEach { add(it) }
                        finding.markings.forEach { add(it) }
                        finding.barcodes.forEach { bc ->
                            if (!bc.rawValue.startsWith("WIFI:") && bc.rawValue.isNotBlank()) {
                                add(bc.rawValue)
                            }
                        }
                    },
                    answeredHandshake = false,
                    signalText = null,
                    // Stable picture id (hash of what was actually read),
                    // so re-reading the same photo refreshes its report
                    // in place instead of duplicating it.
                    targetId = "visual:$contentKey"
                )
            )
            InvestigationLedger.upsert(report)
            LiveTargetStore.publish(report, InvestigationSource.VISUAL)
            visualReportsByContent[contentKey] = report
            latestVisualReport = report
        } catch (_: Exception) {
        }
    }

    private fun buildFinding(
        visionText: Text,
        barcodes: List<Barcode>,
        source: String
    ): VisualFinding {
        val fullText = visionText.text
        val blocks = visionText.textBlocks.map { it.text }
        val barcodeFindings = barcodes.map {
            BarcodeFinding(
                rawValue = it.rawValue ?: "",
                formatName = barcodeFormatName(it.format),
                valueTypeName = barcodeValueTypeName(it.valueType)
            )
        }
        val markings = extractMarkings(fullText)
        val wifi = barcodeFindings
            .firstOrNull { it.rawValue.startsWith("WIFI:") }
            ?.let { parseWifiQr(it.rawValue) }
        return VisualFinding(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            source = source,
            fullText = fullText,
            textBlocks = blocks,
            barcodes = barcodeFindings,
            markings = markings,
            wifiSummary = wifi
        )
    }

    private fun describeFinding(f: VisualFinding): String {
        if (f.fullText.isBlank() && f.barcodes.isEmpty()) {
            return "Nothing readable found — try closer, steadier, with more light."
        }
        val parts = mutableListOf<String>()
        if (f.barcodes.isNotEmpty()) parts.add("${f.barcodes.size} barcode(s)")
        if (f.fullText.isNotBlank()) parts.add("text found")
        if (f.markings.isNotEmpty()) parts.add("${f.markings.size} possible part numbers")
        return "Done: ${parts.joinToString(", ")}. Check them by eye below."
    }

    private fun barcodeFormatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR code"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        else -> "Unknown type"
    }

    private fun barcodeValueTypeName(type: Int): String = when (type) {
        Barcode.TYPE_WIFI -> "Wi-Fi network"
        Barcode.TYPE_URL -> "Web link"
        Barcode.TYPE_TEXT -> "Text"
        Barcode.TYPE_CONTACT_INFO -> "Contact"
        Barcode.TYPE_EMAIL -> "Email"
        Barcode.TYPE_PHONE -> "Phone number"
        Barcode.TYPE_SMS -> "Text message"
        Barcode.TYPE_GEO -> "Location"
        Barcode.TYPE_CALENDAR_EVENT -> "Calendar event"
        Barcode.TYPE_DRIVER_LICENSE -> "Driver license"
        Barcode.TYPE_ISBN -> "ISBN"
        Barcode.TYPE_PRODUCT -> "Product code"
        else -> "Other"
    }

    /**
     * Candidate part markings: letter/digit patterns common on chips and
     * boards (e.g. ESP32S3, CP2102N). Heuristic — verify visually.
     */
    private fun extractMarkings(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val upper = text.uppercase()
        val patterns = listOf(
            Regex("\\b[A-Z]{2,5}[0-9]{2,6}[A-Z0-9]{0,4}\\b"),
            Regex("\\b[0-9]{3,4}[A-Z]{2,4}\\b")
        )
        return patterns
            .flatMap { p -> p.findAll(upper).map { it.value } }
            .distinct()
            .filter { it.length in 4..14 }
            .take(20)
    }

    /** Parse a WIFI:T:..;S:..;P:..;; QR payload into plain lines. */
    private fun parseWifiQr(raw: String): String {
        return try {
            val body = raw.removePrefix("WIFI:").trimEnd(';')
            var ssid = ""
            var sec = ""
            var pass = ""
            for (field in splitUnescaped(body, ';')) {
                val parts = splitUnescaped(field, ':')
                val key = parts.getOrNull(0)?.trim() ?: ""
                val value = parts.drop(1).joinToString(":")
                when (key) {
                    "S" -> ssid = value
                    "T" -> sec = value
                    "P" -> pass = value
                }
            }
            buildString {
                append("Network: ").append(ssid.ifBlank { "(not readable)" })
                append("\nSecurity: ").append(sec.ifBlank { "(not readable)" })
                append("\nPassword: ").append(pass.ifBlank { "(none or not readable)" })
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun splitUnescaped(s: String, delimiter: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                cur.append(s[i + 1])
                i += 2
            } else if (c == delimiter) {
                out.add(cur.toString())
                cur.clear()
                i++
            } else {
                cur.append(c)
                i++
            }
        }
        out.add(cur.toString())
        return out
    }

    // ---------- Target profiles + persistence ----------
    private val storeFile = File(app.filesDir, "visual_intel.json")
    private val json = Json { ignoreUnknownKeys = true }

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        try {
            if (storeFile.exists()) {
                val loaded = json.decodeFromString<List<VisualProfile>>(storeFile.readText())
                profiles.clear()
                profiles.addAll(loaded)
            }
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        try {
            storeFile.writeText(json.encodeToString(ListSerializer(VisualProfile.serializer()), profiles.toList()))
        } catch (_: Exception) {
        }
    }

    fun saveCurrentAsProfile(name: String) {
        val finding = lastFinding ?: run {
            statusLine = "Nothing to save yet — capture or pick a photo first."
            return
        }
        val trimmed = name.trim().ifBlank { "Device ${profiles.size + 1}" }
        profiles.add(
            0,
            VisualProfile(
                id = UUID.randomUUID().toString(),
                name = trimmed,
                notes = "",
                createdMs = System.currentTimeMillis(),
                findings = listOf(finding)
            )
        )
        persist()
        statusLine = "Saved as \"$trimmed\"."
    }

    fun renameProfile(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            profiles[idx] = profiles[idx].copy(name = trimmed)
            persist()
        }
    }

    fun updateNotes(id: String, notes: String) {
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            profiles[idx] = profiles[idx].copy(notes = notes)
            persist()
            statusLine = "Notes saved."
        }
    }

    fun deleteProfile(id: String) {
        profiles.removeAll { it.id == id }
        persist()
        statusLine = "Profile deleted."
    }

    var linkPreview by mutableStateOf<String?>(null)
        private set

    /**
     * Plain-words fallback for a suggested action Ghost Tap doesn't
     * know how to run (Package 5, A-28). Set by
     * [reportUnknownVisualAction]; cleared when a new photo is read.
     * Null = nothing to say.
     */
    var visualActionFallback by mutableStateOf<String?>(null)
        private set

    /** An action id came back that nothing knows how to run — say so plainly instead of silently doing nothing. */
    fun reportUnknownVisualAction(actionId: String) {
        visualActionFallback = "I don't know how to do '$actionId' yet — " +
            "here is what I can do: join a Wi-Fi network, fetch a link, " +
            "connect to an address, connect to a device, or open the USB " +
            "or tap-card tools."
    }

    /**
     * Download what a barcode link points to and show the text here — no
     * browser needed. Honest about failures: a timeout or bad response is
     * reported as-is, never faked.
     */
    fun fetchLinkPreview(url: String) {
        linkPreview = null
        statusLine = "Opening the link…"
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = true
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "GhostTap/1.0")
                val code = conn.responseCode
                if (code !in 200..299) {
                    "The link didn't give a page to show."
                } else {
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                        val sb = StringBuilder()
                        val buf = CharArray(4096)
                        var total = 0
                        while (total < 6000) {
                            val n = r.read(buf)
                            if (n < 0) break
                            sb.append(buf, 0, n)
                            total += n
                        }
                        sb.toString()
                    }
                    // Strip tags crudely so HTML reads as text.
                    val stripped = text.replace(Regex("<[^>]*>"), " ")
                        .replace(Regex("\\s+"), " ").trim()
                    if (stripped.isBlank()) "The link loaded but had no readable text."
                    else stripped.take(1500)
                }
            } catch (e: Exception) {
                "Couldn't open the link: ${e.message ?: "unknown problem"}. " +
                    "Check your connection and try again."
            }
            withContext(Dispatchers.Main) {
                linkPreview = result
                statusLine = "Link preview ready below."
            }
        }
    }

    fun closeLinkPreview() {
        linkPreview = null
    }

    /** Open the phone's Wi-Fi settings so the user can join a scanned network. */
    fun openWifiSettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            statusLine = "Wi-Fi settings opened — join the network, then come back."
        } catch (e: Exception) {
            statusLine = "Couldn't open Wi-Fi settings — open them yourself from the phone's settings."
        }
    }

    /**
     * Ghost Tap connects to one specific network, on its own terms: a
     * connection to just that network, held by Ghost Tap while it
     * works on it. The phone's usual network never takes it over —
     * Ghost Tap stays on the network it asked for until it lets go.
     * First time, the phone shows a one-time approval popup — tap it.
     * The connection is released by [disconnectWifiNetwork] (or when
     * this screen goes away); the phone then goes back to its usual
     * network by itself.
     */
    private var wifiSpecifierCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiSpecifierNetwork: Network? = null

    /**
     * HOLD THE CONNECTION: the network Ghost Tap is on right now
     * through its own connection, or null when it holds none. Set
     * the moment the network answers, cleared the moment it drops —
     * the network card reads this, so its commands only show while
     * Ghost Tap is actually on that network.
     */
    var connectedWifiSsid by mutableStateOf<String?>(null)
        private set

    fun joinWifi(ssid: String, password: String?, security: String) {
        if (ssid.isBlank()) {
            statusLine = "No network name to join — the code didn't scan clearly."
            return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            statusLine = "This phone is too old to join a network this way."
            return
        }
        try {
            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            val sec = security.uppercase()
            val open = password.isNullOrEmpty() || sec.contains("NOPASS")
            if (!open) {
                val useWpa3 = sec.contains("WPA3") &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                if (useWpa3) specBuilder.setWpa3Passphrase(password!!)
                else specBuilder.setWpa2Passphrase(password!!)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specBuilder.build())
                .build()
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            disconnectWifiNetwork(quiet = true)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wifiSpecifierNetwork = network
                    // Ghost Tap's own traffic goes over this network
                    // while it holds it, so its commands reach the
                    // devices on it.
                    runCatching { cm.bindProcessToNetwork(network) }
                    postMain {
                        connectedWifiSsid = ssid
                        statusLine = "Connected to '$ssid' — Ghost Tap is on that network now."
                    }
                }

                override fun onUnavailable() {
                    postMain {
                        statusLine = "Couldn't connect to '$ssid' — try again."
                    }
                }

                override fun onLost(network: Network) {
                    if (network == wifiSpecifierNetwork) {
                        wifiSpecifierNetwork = null
                        postMain {
                            connectedWifiSsid = null
                            statusLine = "Lost the connection to '$ssid'."
                        }
                    }
                }
            }
            wifiSpecifierCallback = callback
            cm.requestNetwork(request, callback, 30000)
            statusLine = "Asking to connect to '$ssid' — approve it on the popup, and Ghost Tap holds that connection."
        } catch (e: Exception) {
            statusLine = "Couldn't connect to '$ssid' — try again."
        }
    }

    /** Let go of Ghost Tap's own network connection, if it holds one. */
    fun disconnectWifiNetwork(quiet: Boolean = false) {
        try {
            wifiSpecifierCallback?.let { cb ->
                (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {
        }
        wifiSpecifierCallback = null
        wifiSpecifierNetwork = null
        connectedWifiSsid = null
        runCatching {
            (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.bindProcessToNetwork(null)
        }
        if (!quiet) {
            statusLine = "Left that network — Ghost Tap is back on the phone's usual connection."
        }
    }

    fun copyToClipboard(label: String, text: String) {
        try {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            statusLine = "Copied $label."
        } catch (_: Exception) {
            statusLine = "Couldn't copy it."
        }
    }

    private fun postMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    override fun onCleared() {
        try {
            unbindCamera()
        } catch (_: Exception) {
        }
        disconnectWifiNetwork(quiet = true)
        runCatching { textRecognizer.close() }
        runCatching { barcodeScanner.close() }
        super.onCleared()
    }
}
