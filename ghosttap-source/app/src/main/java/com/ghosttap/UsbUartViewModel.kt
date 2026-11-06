package com.ghosttap

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghosttap.core.usb.UsbDeviceInfo
import com.ghosttap.core.usb.UsbSerialManager
import com.ghosttap.core.esp32.Esp32Protocol
import com.ghosttap.core.esp32.Esp32Flasher
import com.ghosttap.core.esp32.FirmwareValidator
import com.ghosttap.core.text.DeviceTextLanguage
import com.ghosttap.core.text.RawBytes
import com.ghosttap.data.RecentFirmware
import com.ghosttap.data.RecentFirmwareStore
import com.ghosttap.data.UsbCommandHistoryStore
import java.io.File
import java.security.MessageDigest
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigatedControl
import com.ghosttap.core.investigate.InvestigationInput
import com.ghosttap.core.investigate.InvestigationLedger
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.Investigator
import com.ghosttap.core.investigate.LiveTargetStore
import java.io.InputStream

/**
 * ViewModel for USB UART / ESP32 Programmer tool.
 * Separate from GhostTapViewModel to avoid breaking existing features.
 */
class UsbUartViewModel(application: Application) : AndroidViewModel(application) {

    private val usbManager = UsbSerialManager(application)

    /**
     * How plain text becomes bytes before sending. RawBytes is today's
     * behavior: exactly what was typed, nothing translated.
     */
    val txTextLanguage: DeviceTextLanguage = RawBytes

    // USB device state
    var usbDevices by mutableStateOf<List<UsbDeviceInfo>>(emptyList())
        private set
    var selectedDevice by mutableStateOf<UsbDeviceInfo?>(null)
        private set
    var usbStatus by mutableStateOf("No USB device found")
        private set
    var isConnected by mutableStateOf(false)
        private set
    var permissionNeeded by mutableStateOf(false)
        private set

    /** Latest USB findings report, for the USB screen — null until a device has been investigated. */
    var latestUsbReport by mutableStateOf<FindingsReport?>(null)
        private set

    /**
     * DISCOVER (USB): plain-words note from the last "Check what is
     * plugged in" tap — e.g. nothing plugged in. Null = nothing to say.
     * Shown under the DISCOVER button; never a faked device.
     */
    var usbDiscoverNote by mutableStateOf<String?>(null)
        private set

    /**
     * Autonomous Investigations (USB): evidence fingerprint per USB
     * device investigated this session. A device is investigated when
     * it plugs in, again when its board first talks, and again if the
     * board later proves it answers — never on every refresh or every
     * message.
     */
    private val usbInvestigatedFingerprints = mutableMapOf<String, String>()

    /** USB device keys that have really proven they answer, by talking first on the cable. */
    private val usbAnsweredKeys = mutableSetOf<String>()

    /** Key of the board that passed the board check, if any — proof is per board, never borrowed. */
    private var esp32AnsweredKey: String? = null

    // Serial terminal state
    var baudRate by mutableStateOf(115200)
        private set
    var rxText by mutableStateOf("")
        private set
    var txText by mutableStateOf("")
        private set
    var hexMode by mutableStateOf(false)
        private set
    var dtrAsserted by mutableStateOf(false)
        private set
    var rtsAsserted by mutableStateOf(false)
        private set

    // ESP32 state
    var esp32Status by mutableStateOf("Not checked yet")
        private set
    var esp32ChipInfo by mutableStateOf<String?>(null)
        private set
    var firmwareUri by mutableStateOf<Uri?>(null)
        private set
    var firmwareName by mutableStateOf<String?>(null)
        private set
    var firmwareSize by mutableStateOf<Long>(0)
        private set
    var flashProgress by mutableStateOf<String?>(null)
        private set
    var flashPercent by mutableStateOf(0)
        private set
    var showFlashConfirm by mutableStateOf(false)
        private set

    // Package 5 — flash spot (D-19). Default 0x10000 is the usual place
    // for the app; the screen offers the other spots with a warning.
    // confirmFlash() uses this, so the default keeps old behaviour.
    var flashOffset by mutableStateOf(0x10000)
        private set

    /** The check result for the picked firmware file, if it has been checked (D-20). */
    var firmwareValidation by mutableStateOf<FirmwareValidator.ValidationResult?>(null)
        private set

    /** The MD5 read-back verdict for the last flash check, if one ran (D-19 / F-34). */
    var flashVerifyResult by mutableStateOf<String?>(null)
        private set

    /** The board's ID-register read, if one ran (D-16). */
    var lastRegisterValue by mutableStateOf<String?>(null)
        private set

    // Package 5 — terminal history, presets, newline (D-10).
    private val historyStore = UsbCommandHistoryStore(application)

    /** Messages sent before, most recent first (max 20). Tap one to send it again. */
    var commandHistory by mutableStateOf(historyStore.load())
        private set

    /** Quick messages: the text to send, and what it does in plain words. */
    val commandPresets: List<Pair<String, String>> = listOf(
        "PING" to "Ask if it's listening",
        "HELLO" to "Say hello",
        "AT" to "Ask for attention",
        "STATUS" to "Ask what it's doing"
    )

    /** When on, every message ends with a new line — most boards only answer then. */
    var sendNewline by mutableStateOf(false)
        private set

    fun toggleSendNewline() {
        sendNewline = !sendNewline
    }

    fun clearCommandHistory() {
        commandHistory = emptyList()
        historyStore.clear()
    }

    private fun recordCommand(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val updated = (listOf(clean) + commandHistory.filter { it != clean }).take(20)
        commandHistory = updated
        runCatching { historyStore.save(updated) }
    }

    // Package 5 — last-used firmware file (A-30), persisted like the
    // other recent items. Only ever set from a file the user picked.
    private val recentStore = RecentFirmwareStore(application)

    /** The last firmware file picked, or null when none was ever picked. */
    var recentFirmware by mutableStateOf<RecentFirmware?>(null)
        private set

    /** Re-picks the last-used firmware file. False when none was ever picked. */
    fun reopenRecentFirmware(): Boolean {
        val recent = recentFirmware ?: return false
        val uri = runCatching { Uri.parse(recent.uri) }.getOrNull() ?: return false
        selectFirmware(uri, recent.name, recent.size)
        return true
    }

    /** Devices plugged in that no driver knows — shown, not silently hidden (D-01/D-06). */
    fun listUnknownUsbDevices(): List<UsbDeviceInfo> =
        usbManager.findUnsupportedDevices().map { usbManager.getDeviceInfo(it) }

    /** Picks the flash spot. 0x10000 is the usual place for the app. */
    fun pickFlashOffset(offset: Int) {
        flashOffset = offset
        log("Flash spot set to 0x${offset.toString(16).uppercase()}")
    }

    // Details/Raw log
    var rawLog by mutableStateOf("")
        private set
    var showDetails by mutableStateOf(false)
        private set

    private var rxJob: Job? = null

    /** Fires when a USB device is plugged in: refresh the list automatically. */
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            refreshDevices()
            if (usbDevices.isNotEmpty()) {
                val name = usbDevices.firstOrNull()?.let {
                    "${it.manufacturer} ${it.product}".trim().ifBlank { "USB device" }
                } ?: "USB device"
                usbStatus = "Found $name — tap CONNECT."
                log("Auto-detected USB plug-in: $name")
            }
            // Autonomous Investigations: a board that just plugged in
            // gets investigated on its own, from what it really reports.
            usbDevices.forEach { autoInvestigateUsb(it, null) }
        }
    }

    init {
        usbManager.register()
        // Auto-detect: when a USB device is plugged in, refresh by ourselves —
        // no manual REFRESH tap needed. Existing permission + CONNECT flow untouched.
        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(attachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            app.registerReceiver(attachReceiver, attachFilter)
        }
        refreshDevices()
        recentFirmware = recentStore.load()
    }

    override fun onCleared() {
        super.onCleared()
        rxJob?.cancel()
        usbManager.closeDevice()
        usbManager.unregister()
        runCatching { getApplication<Application>().unregisterReceiver(attachReceiver) }
    }

    fun refreshDevices() {
        val devices = usbManager.findSupportedDevices()
        usbDevices = devices.map { usbManager.getDeviceInfo(it) }
        if (usbDevices.isEmpty()) {
            usbStatus = "No USB device found"
        } else if (usbDevices.size == 1) {
            val dev = usbDevices[0]
            selectedDevice = dev
            usbStatus = if (dev.hasPermission) {
                "USB cable found — ready to connect"
            } else {
                "USB cable found — permission needed"
            }
            permissionNeeded = !dev.hasPermission
        } else {
            usbStatus = "${usbDevices.size} USB devices found — tap one to pick it"
        }
        log("Refreshed: ${usbDevices.size} device(s)")
    }

    fun selectDevice(info: UsbDeviceInfo) {
        selectedDevice = info
        permissionNeeded = !info.hasPermission
        usbStatus = if (info.hasPermission) {
            "USB cable found — ready to connect"
        } else {
            "USB cable found — permission needed"
        }
    }

    fun requestPermission() {
        val dev = selectedDevice?.device ?: return
        usbStatus = "Asking for USB permission…"
        usbManager.requestPermission(dev) { granted ->
            permissionNeeded = !granted
            usbStatus = if (granted) {
                "Permission allowed — ready to connect"
            } else {
                "USB cable found — permission needed"
            }
            log("Permission ${if (granted) "granted" else "denied"}")
            refreshDevices()
        }
    }

    /**
     * Event bus: every real USB/serial/ESP32 result flows to the intelligence
     * engine through these. Never throws, never blocks.
     */
    private fun pubUsb(id: String, ok: Boolean, summary: String) {
        try {
            IntelEventBus.publish(IntelEvent.ToolOpResult(id, ok, summary.take(1500)))
        } catch (_: Exception) {
        }
    }

    private fun pubUsbState(connected: Boolean, detail: String) {
        try {
            IntelEventBus.publish(IntelEvent.UsbState(connected, detail.take(500)))
        } catch (_: Exception) {
        }
    }

    /**
     * DISCOVER (USB): check what is plugged in right now, on demand —
     * no typing, no setup. Refreshes the device list, then runs the
     * same checks the plug-in trigger runs (attached device, and the
     * board-answer check when already connected) for every device
     * found, including ones already known — the fingerprint gate is
     * lifted for this tap, and a known board's report is refreshed in
     * place instead of duplicated. Nothing connects or sends anything
     * the user didn't already ask for: the board-answer check only
     * runs when the cable is already connected. When nothing is
     * plugged in, it says so plainly and files nothing.
     */
    fun discoverUsb() {
        refreshDevices()
        if (usbDevices.isEmpty()) {
            usbDiscoverNote = "Nothing plugged in right now — plug a board in with a USB cable, then tap this again."
            return
        }
        usbDiscoverNote = null
        usbDevices.forEach { autoInvestigateUsb(it, null, force = true) }
        // Board-answer check, on demand — only when already connected.
        if (isConnected) testEsp32()
    }

    /**
     * Autonomous Investigations (USB): file a findings report for a
     * plugged-in board from real evidence only — maker/product codes,
     * the connection type (like CH340) the driver actually reported,
     * and words the board itself sent, if any. answeredHandshake is
     * true only for a board that has really talked back.
     *
     * [force] is DISCOVER-only: a "Check what is plugged in" tap lifts
     * the new/changed-only fingerprint gate so a known board is
     * checked again. The report carries the board's stable id, so a
     * re-check refreshes its report in place instead of duplicating it.
     */
    private fun autoInvestigateUsb(info: UsbDeviceInfo, firstWords: String?, force: Boolean = false) {
        val key = "${info.vidPidString()}|${info.driverName}"
        val answered = key in usbAnsweredKeys || esp32AnsweredKey == key
        val stage = if (firstWords != null) "talked" else "attached"
        val fingerprint = listOf(
            stage,
            info.manufacturer,
            info.product,
            info.vidPidString(),
            info.driverName,
            answered.toString()
        ).joinToString("|")
        if (!force && usbInvestigatedFingerprints[key] == fingerprint) return
        usbInvestigatedFingerprints[key] = fingerprint
        val details = mutableMapOf<String, String>()
        details["deviceCode"] = info.vidPidString()
        details["driverName"] = info.driverName
        if (info.manufacturer.isNotBlank()) details["manufacturer"] = info.manufacturer
        if (info.product.isNotBlank()) details["product"] = info.product
        val readable = mutableListOf<String>()
        val shownName = "${info.manufacturer} ${info.product}".trim()
        shownName.takeIf { it.isNotBlank() }?.let { readable += "Plugged in: $it" }
        readable += "Connection type: ${info.driverName}"
        firstWords?.takeIf { it.isNotBlank() }?.let {
            readable += "First words from the board: ${it.take(200)}"
        }
        val report = runCatching {
            Investigator.investigate(
                InvestigationInput(
                    source = InvestigationSource.USB,
                    rawName = shownName.takeIf { it.isNotBlank() },
                    rawDetails = details,
                    readableValues = readable,
                    answeredHandshake = answered,
                    signalText = null,
                    // Stable board id (device code + driver), so a
                    // re-check refreshes this board's report in place.
                    targetId = "usb:$key"
                )
            )
        }.getOrNull() ?: return
        InvestigationLedger.upsert(report)
        LiveTargetStore.publish(report, InvestigationSource.USB)
        latestUsbReport = report
    }

    /**
     * Runs one capability from a USB findings report — and only ever an
     * action this screen already has. A quick message the screen already
     * sends goes out through the normal send path; asking to test the
     * board runs the normal board test. Anything else has no existing
     * matching action, so nothing runs for it — it stays plain words
     * on the report, never a button that invents something new. The
     * user always presses the button; nothing here runs on its own.
     */
    fun runUsbReportControl(control: InvestigatedControl) {
        val label = control.label
        if (label.contains("test", ignoreCase = true) &&
            label.contains("board", ignoreCase = true)
        ) {
            testEsp32()
            return
        }
        val preset = commandPresets.firstOrNull { (text, _) ->
            label.contains(text, ignoreCase = true)
        }
        if (preset != null) {
            updateTxText(preset.first)
            sendTx()
        }
        // No existing matching action: do nothing.
    }

    /** Throttle fields for serial-RX summaries (at most every 15s). */
    private var lastRxPublishMs = 0L
    private var lastRxPublishLen = 0

    fun connect() {
        val devInfo = selectedDevice ?: return
        if (!devInfo.hasPermission && !usbManager.getDeviceInfo(devInfo.device).hasPermission) {
            requestPermission()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                usbStatus = "Connecting…"
            }
            val success = usbManager.openDevice(devInfo.device, baudRate)
            withContext(Dispatchers.Main) {
                if (success) {
                    isConnected = true
                    usbStatus = "Connected — ready to use"
                    log("Connected to ${devInfo.driverName} at $baudRate baud")
                    startRxLoop()
                    pubUsbState(true, "Connected to ${devInfo.driverName} (speed $baudRate)")
                } else {
                    usbStatus = "Connection failed — try again"
                    log("Connection failed")
                    pubUsbState(false, "Connection failed — try again")
                }
            }
        }
    }

    fun disconnect() {
        rxJob?.cancel()
        usbManager.closeDevice()
        isConnected = false
        usbStatus = "Not connected"
        log("Disconnected")
        pubUsbState(false, "You disconnected it.")
    }

    fun changeBaudRate(rate: Int) {
        baudRate = rate
        // If connected, reconfigure
        if (isConnected) {
            viewModelScope.launch(Dispatchers.IO) {
                val driver = usbManager.driver
                driver?.setParameters(rate, 8, 1, 0)
                withContext(Dispatchers.Main) {
                    log("Baud rate set to $rate")
                }
            }
        }
    }

    /** AUTO-BAUD running flag — the button shows progress while probing. */
    var isAutoBaudRunning by mutableStateOf(false)
        private set

    /**
     * AUTO-BAUD — tries 9600, 19200, 38400, 57600, 74880, 115200, 230400
     * on the connected device: at each rate it sends a newline, reads
     * briefly, and scores how clean the reply looks (printable-ASCII
     * ratio). Picks the best, applies it through the normal baud
     * setter. If nothing comes back clean, the original rate is
     * restored and it says so honestly. 74880 (ESP32 start-up
     * messages) and 230400 (common on ESP32 boards) are tried too
     * (Package 5, B-103 / F-35) — the CH340 formula path and the
     * CP210x driver both accept them.
     */
    fun autoBaud() {
        if (!isConnected || isAutoBaudRunning) return
        isAutoBaudRunning = true
        log("AUTO-BAUD: trying common baud rates — sending a newline at each, watching what comes back…")
        viewModelScope.launch(Dispatchers.IO) {
            val driver = usbManager.driver
            val original = baudRate
            var best = -1
            var bestScore = -1.0
            try {
                // Pause the RX loop so probe reads aren't stolen by it.
                rxJob?.cancel()
                for (rate in intArrayOf(9600, 19200, 38400, 57600, 74880, 115200, 230400)) {
                    try {
                        driver?.setParameters(rate, 8, 1, 0)
                        // Flush stale bytes, then probe.
                        driver?.read(ByteArray(1024), 50)
                        driver?.write("\r\n".toByteArray(Charsets.UTF_8), 500)
                        var total = 0
                        var printable = 0
                        val buf = ByteArray(1024)
                        val deadline = System.currentTimeMillis() + 600
                        while (System.currentTimeMillis() < deadline) {
                            val n = try {
                                driver?.read(buf, 150) ?: 0
                            } catch (_: Exception) {
                                0
                            }
                            for (i in 0 until n) {
                                val b = buf[i].toInt() and 0xFF
                                total++
                                if (b in 32..126 || b == 10 || b == 13 || b == 9) printable++
                            }
                        }
                        val score = if (total > 0) printable.toDouble() / total else -1.0
                        val verdict = if (total == 0) "silence"
                            else "$total bytes, ${(score * 100).toInt()}% clean text"
                        log("AUTO-BAUD: $rate → $verdict")
                        if (score > bestScore) {
                            bestScore = score
                            best = rate
                        }
                    } catch (e: Exception) {
                        log("AUTO-BAUD: $rate → error (${e.message ?: e.javaClass.simpleName})")
                    }
                }
            } finally {
                if (best > 0 && bestScore >= 0.7) {
                    runCatching { driver?.setParameters(best, 8, 1, 0) }
                    withContext(Dispatchers.Main) {
                        baudRate = best
                        isAutoBaudRunning = false
                        log("AUTO-BAUD: $best looks right — clean text coming back.")
                        pubUsb("usb.auto_baud", true, "Auto speed check found $best — replies are readable now.")
                    }
                } else {
                    runCatching { driver?.setParameters(original, 8, 1, 0) }
                    withContext(Dispatchers.Main) {
                        baudRate = original
                        isAutoBaudRunning = false
                        log("AUTO-BAUD: couldn't find a clean baud — set it by hand.")
                        pubUsb("usb.auto_baud", false, "Auto speed check couldn't find a clear speed.")
                    }
                }
                // RX loop back on.
                startRxLoop()
            }
        }
    }

    fun toggleHexMode() {
        hexMode = !hexMode
    }

    fun updateTxText(text: String) {
        txText = text
    }

    fun sendTx() {
        if (!isConnected || txText.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val driver = usbManager.driver ?: return@launch
            val data = if (hexMode) {
                // Parse hex string
                try {
                    txText.replace(" ", "").chunked(2).map {
                        it.toInt(16).toByte()
                    }.toByteArray()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        log("Invalid hex: ${e.message}")
                    }
                    return@launch
                }
            } else {
                // Text mode: exactly what was typed — plus a new line at
                // the end when the user asked for one (Package 5, D-10).
                // Most boards only answer when the message ends with one.
                txTextLanguage.encode(if (sendNewline) "$txText\r\n" else txText)
            }
            val written = driver.write(data, 1000)
            withContext(Dispatchers.Main) {
                if (written > 0) {
                    val display = if (hexMode) txText else txText
                    rxText += "PHONE → ESP32: $display\n"
                    log("TX: $written bytes")
                    // Package 5 (D-10): remember what was sent so it can
                    // be sent again with one tap instead of retyping.
                    recordCommand(display)
                } else {
                    log("TX failed")
                }
                txText = ""
            }
        }
    }

    fun clearTerminal() {
        rxText = ""
    }

    fun setDtr(asserted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = usbManager.driver?.setDtr(asserted) ?: false
            withContext(Dispatchers.Main) {
                if (result) {
                    dtrAsserted = asserted
                    log("DTR ${if (asserted) "asserted" else "released"}")
                } else {
                    log("DTR control failed")
                }
            }
        }
    }

    fun setRts(asserted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = usbManager.driver?.setRts(asserted) ?: false
            withContext(Dispatchers.Main) {
                if (result) {
                    rtsAsserted = asserted
                    log("RTS ${if (asserted) "asserted" else "released"}")
                } else {
                    log("RTS control failed")
                }
            }
        }
    }

    fun testEsp32() {
        if (!isConnected) {
            esp32Status = "Connect the USB cable first"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                esp32Status = "Checking…"
                log("TEST ESP32: attempting bootloader sync")
            }
            val driver = usbManager.driver
            if (driver == null) {
                withContext(Dispatchers.Main) {
                    esp32Status = "No USB cable connection"
                }
                return@launch
            }
            val protocol = Esp32Protocol(driver)
            val result = protocol.sync()
            withContext(Dispatchers.Main) {
                if (result.success) {
                    esp32Status = "The board answered — ready to load code"
                    esp32ChipInfo = result.chipInfo?.chipName ?: "Board details unavailable"
                    log("ESP32 DETECTED: ${result.message}")
                    pubUsb("esp32.sync", true, "The board answered — ${result.message.take(300)}")
                    // Autonomous Investigations: the board just proved
                    // it answers — refresh its report with that proof.
                    selectedDevice?.let { dev ->
                        esp32AnsweredKey = "${dev.vidPidString()}|${dev.driverName}"
                        autoInvestigateUsb(dev, null)
                    }
                } else {
                    esp32Status = "The board didn't answer — check the wires and that it's in download mode"
                    esp32ChipInfo = null
                    log("NO RESPONSE: ${result.message}")
                    pubUsb("esp32.sync", false, "The board didn't answer — ${result.message.take(300)}")
                }
            }
        }
    }

    fun enterDownloadMode() {
        if (!isConnected) {
            esp32Status = "Connect the USB cable first"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                esp32Status = "Putting the board into download mode…"
                log("BOOT RESET: DTR/RTS sequence")
            }
            val driver = usbManager.driver ?: return@launch
            val protocol = Esp32Protocol(driver)
            val success = protocol.enterDownloadMode()
            withContext(Dispatchers.Main) {
                if (success) {
                    esp32Status = "Download mode started — tap TEST ESP32 to check it"
                    log("Download mode sequence executed")
                    pubUsb("esp32.download_mode", true, "Download mode started.")
                } else {
                    esp32Status = "Couldn't start download mode"
                    log("Download mode sequence failed")
                    pubUsb("esp32.download_mode", false, "Couldn't start download mode.")
                }
            }
        }
    }

    fun resetBoard() {
        if (!isConnected) return
        viewModelScope.launch(Dispatchers.IO) {
            val driver = usbManager.driver ?: return@launch
            val protocol = Esp32Protocol(driver)
            protocol.resetBoard()
            withContext(Dispatchers.Main) {
                log("Reset sent")
            }
        }
    }

    fun selectFirmware(uri: Uri, name: String?, size: Long) {
        firmwareUri = uri
        firmwareName = name ?: "firmware.bin"
        firmwareSize = size
        log("Firmware selected: $firmwareName (${size} bytes) — ready to flash")
        // Package 5 (A-30): remember this file for the one-tap shortcut
        // on the home screen. Only ever set from a file the user picked.
        val recent = RecentFirmware(firmwareName!!, uri.toString(), size, System.currentTimeMillis())
        recentFirmware = recent
        runCatching { recentStore.save(recent) }
        // Package 5 (D-20 / B-113): check the file as soon as it is
        // picked, so a wrong file is caught here — not at flash time.
        firmwareValidation = null
        viewModelScope.launch { validateFirmwareNow() }
    }

    /**
     * Checks the picked file is a real board file (Package 5, D-20).
     * Copies it into app-private cache and runs [FirmwareValidator]
     * on it, exactly like the practice-board screen does. Returns the
     * check result, or null when the file can't be read. Also stored
     * in [firmwareValidation] for the screen.
     */
    suspend fun validateFirmwareNow(): FirmwareValidator.ValidationResult? {
        val uri = firmwareUri ?: return null
        val result = withContext(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                var fileName = firmwareName ?: "firmware.bin"
                app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) fileName = cursor.getString(idx) ?: fileName
                    }
                }
                val destDir = File(app.cacheDir, "firmware")
                destDir.mkdirs()
                val destFile = File(destDir, fileName)
                val stream = app.contentResolver.openInputStream(uri)
                    ?: return@withContext null
                stream.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                FirmwareValidator.validate(destFile)
            } catch (_: Exception) {
                null
            }
        }
        firmwareValidation = result
        return result
    }

    /**
     * Reads the board's ID register (Package 5, D-16) — the one
     * register read the app can honestly explain: it names the chip
     * when the value matches a known chip, and says "unknown" when it
     * doesn't. Needs a synced bootloader session.
     */
    fun readChipMagicRegister() {
        if (!isConnected) {
            lastRegisterValue = "Connect the USB cable first"
            return
        }
        lastRegisterValue = null
        viewModelScope.launch(Dispatchers.IO) {
            val driver = usbManager.driver
            if (driver == null) {
                withContext(Dispatchers.Main) {
                    lastRegisterValue = "No USB cable connection"
                }
                return@launch
            }
            val protocol = Esp32Protocol(driver)
            val syncResult = protocol.sync()
            if (!syncResult.success) {
                withContext(Dispatchers.Main) {
                    lastRegisterValue = "The board didn't answer — check the wires and that it's in download mode"
                }
                return@launch
            }
            val chip = protocol.readChipMagic()
            withContext(Dispatchers.Main) {
                lastRegisterValue = when {
                    chip == null ->
                        "Couldn't read the board's ID register — the board answered, but the ID didn't come back."
                    chip.isKnown ->
                        "Board ID: 0x%08X — %s".format(chip.magicValue, chip.chipName)
                    else ->
                        "Board ID: 0x%08X — not a chip Ghost Tap knows, so nothing is guessed.".format(chip.magicValue)
                }
            }
        }
    }

    /**
     * Checks the board holds exactly what was sent (Package 5, D-19 /
     * F-34): puts the board back in load mode, reads the flash MD5
     * back, and compares it with the file's MD5. The board is left
     * restarted into normal boot afterwards. The result lands in
     * [flashVerifyResult]: "Matches…", "Doesn't match…", or an honest
     * "Couldn't check…" — a failed check never says "probably fine".
     */
    fun verifyFlashMd5() {
        val uri = firmwareUri
        if (!isConnected || uri == null) {
            flashVerifyResult = "Connect the board and pick a software file first — nothing to check."
            return
        }
        flashVerifyResult = null
        viewModelScope.launch(Dispatchers.IO) {
            val driver = usbManager.driver
            if (driver == null) {
                withContext(Dispatchers.Main) {
                    flashVerifyResult = "No USB cable connection — nothing to check."
                }
                return@launch
            }
            val protocol = Esp32Protocol(driver)
            // The flash rebooted the board into normal boot — put it
            // back in load mode, then check it answers.
            protocol.enterDownloadMode()
            val syncResult = protocol.sync()
            if (!syncResult.success) {
                withContext(Dispatchers.Main) {
                    flashVerifyResult = "Couldn't check — the board didn't answer. Put it in load mode and try again."
                }
                return@launch
            }
            val boardMd5 = protocol.readFlashMd5(flashOffset, firmwareSize.toInt())
            val localMd5 = runCatching {
                val digest = MessageDigest.getInstance("MD5")
                val app = getApplication<Application>()
                app.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(8192)
                    var n = input.read(buf)
                    while (n > 0) {
                        digest.update(buf, 0, n)
                        n = input.read(buf)
                    }
                } ?: return@runCatching null
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
            val verdict = when {
                boardMd5 == null ->
                    "Couldn't check — the board didn't answer the check."
                localMd5 == null ->
                    "Couldn't check — the file couldn't be read again."
                boardMd5.equals(localMd5, ignoreCase = true) ->
                    "Matches — the board holds exactly what was sent."
                else ->
                    "Doesn't match — what the board holds is different from the file. Load it again."
            }
            protocol.resetBoard()
            withContext(Dispatchers.Main) {
                flashVerifyResult = verdict
                log("Flash check: $verdict")
            }
        }
    }

    fun requestFlashConfirm() {
        if (!isConnected) {
            flashProgress = "Connect the USB cable first"
            return
        }
        if (firmwareUri == null) {
            flashProgress = "Pick a file first"
            return
        }
        showFlashConfirm = true
    }

    fun cancelFlashConfirm() {
        showFlashConfirm = false
    }

    fun confirmFlash() {
        showFlashConfirm = false
        val uri = firmwareUri ?: return
        if (!isConnected) {
            flashProgress = "Connect the USB cable first"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    withContext(Dispatchers.Main) {
                        flashProgress = "Couldn't read that file"
                    }
                    return@launch
                }

                // Verify ESP32 is responding first
                withContext(Dispatchers.Main) {
                    flashProgress = "Checking the board…"
                }
                val driver = usbManager.driver
                if (driver == null) {
                    withContext(Dispatchers.Main) {
                        flashProgress = "No USB cable connection"
                    }
                    inputStream.close()
                    return@launch
                }

                val protocol = Esp32Protocol(driver)
                val syncResult = protocol.sync()
                if (!syncResult.success) {
                    withContext(Dispatchers.Main) {
                        flashProgress = "The board isn't answering — check the cable and wires"
                    }
                    inputStream.close()
                    return@launch
                }

                // Flash (at the chosen spot — 0x10000, the usual place
                // for the app, unless the user picked another one).
                val flasher = Esp32Flasher(driver)
                val success = flasher.flash(
                    inputStream,
                    firmwareSize,
                    offset = flashOffset,
                    callback = object : Esp32Flasher.ProgressCallback {
                        override fun onProgress(progress: Esp32Flasher.FlashProgress) {
                            viewModelScope.launch(Dispatchers.Main) {
                                flashProgress = progress.stage
                                flashPercent = progress.percent
                                log("Flash: ${progress.stage} ${progress.percent}%")
                            }
                        }
                    }
                )

                inputStream.close()

                withContext(Dispatchers.Main) {
                    if (success) {
                        flashProgress = "Done — the file is now on the board."
                        log("FLASH SUCCESS")
                        pubUsb("esp32.flash", true, "Done — $firmwareName (${firmwareSize} bytes) written to the board.")
                    } else {
                        flashProgress = "Didn't work — check the details below."
                        log("FLASH FAILED")
                        pubUsb("esp32.flash", false, "Didn't work — check the USB details.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    flashProgress = "Couldn't load it: ${e.message}"
                    log("Flash error: ${e.message}")
                    pubUsb("esp32.flash", false, "Couldn't load it: ${e.message ?: "unknown problem"}")
                }
            }
        }
    }

    fun toggleDetails() {
        showDetails = !showDetails
    }

    private fun startRxLoop() {
        rxJob?.cancel()
        rxJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isConnected) {
                try {
                    val driver = usbManager.driver ?: break
                    val len = driver.read(buffer, 100)
                    if (len > 0) {
                        val data = buffer.copyOf(len)
                        withContext(Dispatchers.Main) {
                            val display = if (hexMode) {
                                data.joinToString(" ") { "%02X".format(it) }
                            } else {
                                try {
                                    String(data, Charsets.UTF_8)
                                } catch (_: Exception) {
                                    data.joinToString(" ") { "%02X".format(it) }
                                }
                            }
                            rxText += "ESP32 → PHONE: $display\n"
                            // Autonomous Investigations: the board just
                            // talked for the first time — investigate it
                            // with its own first words as evidence.
                            selectedDevice?.let { dev ->
                                val key = "${dev.vidPidString()}|${dev.driverName}"
                                if (key !in usbAnsweredKeys) {
                                    usbAnsweredKeys += key
                                    autoInvestigateUsb(dev, display)
                                }
                            }
                            // Keep log manageable
                            if (rxText.length > 10000) {
                                rxText = rxText.takeLast(8000)
                            }
                            // Intelligence: throttled serial-traffic summaries
                            // (at most every 15s) — the loop never spams the bus.
                            val now = System.currentTimeMillis()
                            if (rxText.length < lastRxPublishLen) lastRxPublishLen = 0
                            if (now - lastRxPublishMs > 15_000 &&
                                rxText.length > lastRxPublishLen
                            ) {
                                lastRxPublishMs = now
                                val chunk = rxText.substring(lastRxPublishLen).take(600)
                                lastRxPublishLen = rxText.length
                                pubUsb("usb.serial_rx", true, "Messages on the cable:\n$chunk")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Connection lost
                    withContext(Dispatchers.Main) {
                        log("RX error: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        rawLog += "[$timestamp] $message\n"
        if (rawLog.length > 20000) {
            rawLog = rawLog.takeLast(16000)
        }
    }

    fun copyLog(): String = rawLog
}
