package com.ghosttap.core.esp32

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus

/** Client for the Virtual ESP32 IPC bridge (Intent + broadcast based).
 *
 * Sends commands via explicit Intents to Virtual ESP32's bridge service,
 * receives live events via broadcasts. All UART/GPIO operations reach the
 * actual executing Xtensa firmware; nothing is simulated here. */
class VirtualEsp32Client(private val context: Context) {

    companion object {
        const val PKG = "dev.virtualesp32"
        const val SVC = "dev.virtualesp32.VirtualEsp32BridgeService"
        const val ACTION_START_GUEST = "dev.virtualesp32.action.START_GUEST"
        const val ACTION_STOP_GUEST = "dev.virtualesp32.action.STOP_GUEST"
        const val ACTION_RESET_GUEST = "dev.virtualesp32.action.RESET_GUEST"
        const val ACTION_SEND_UART = "dev.virtualesp32.action.SEND_UART"
        const val ACTION_WRITE_GPIO = "dev.virtualesp32.action.WRITE_GPIO"
        const val ACTION_QUERY_STATUS = "dev.virtualesp32.action.QUERY_STATUS"
        const val ACTION_READ_GPIO = "dev.virtualesp32.action.READ_GPIO"
        const val ACTION_LOAD_FIRMWARE = "dev.virtualesp32.action.LOAD_FIRMWARE"
        const val ACTION_EJECT_FIRMWARE = "dev.virtualesp32.action.EJECT_FIRMWARE"
        const val ACTION_USE_BUILTIN_FIRMWARE = "dev.virtualesp32.action.USE_BUILTIN_FIRMWARE"
        const val EVENT_GUEST_STARTED = "dev.virtualesp32.event.GUEST_STARTED"
        const val EVENT_GUEST_STOPPED = "dev.virtualesp32.event.GUEST_STOPPED"
        const val EVENT_GUEST_RESET = "dev.virtualesp32.event.GUEST_RESET"
        const val EVENT_UART_RX = "dev.virtualesp32.event.UART_RX"
        const val EVENT_GPIO_CHANGED = "dev.virtualesp32.event.GPIO_CHANGED"
        const val EVENT_ERROR = "dev.virtualesp32.event.ERROR"
        const val EVENT_STATUS = "dev.virtualesp32.event.STATUS"
        const val EVENT_GPIO_VALUE = "dev.virtualesp32.event.GPIO_VALUE"
        const val EVENT_FIRMWARE_LOADED = "dev.virtualesp32.event.FIRMWARE_LOADED"
        const val EVENT_FIRMWARE_EJECTED = "dev.virtualesp32.event.FIRMWARE_EJECTED"
    }

    data class TargetInfo(
        val status: String,
        val cpu: String,
        val backend: String,
        val firmware: String
    )

    data class FirmwareInfo(
        val fileName: String,
        val filePath: String,
        val imageType: String,
        val fileSize: Long,
        val validationSummary: String,
        val isBuiltin: Boolean
    )

    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data object Connected : ConnectionState
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _targetInfo = MutableStateFlow<TargetInfo?>(null)
    val targetInfo: StateFlow<TargetInfo?> = _targetInfo

    private val _uartLog = MutableStateFlow<List<String>>(emptyList())
    val uartLog: StateFlow<List<String>> = _uartLog

    private val _gpio2State = MutableStateFlow<Boolean?>(null)
    val gpio2State: StateFlow<Boolean?> = _gpio2State

    private val _gpio4State = MutableStateFlow<Boolean?>(null)
    val gpio4State: StateFlow<Boolean?> = _gpio4State

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _firmwareInfo = MutableStateFlow<FirmwareInfo?>(null)
    val firmwareInfo: StateFlow<FirmwareInfo?> = _firmwareInfo

    // Autonomous-investigation support (additive, no existing behavior
    // changed): true once the practice-board app has actually answered a
    // command over UART in this session. Only real UART_RX broadcasts
    // from the board set this — Ghost Tap's own log lines do not. Reset
    // on disconnect so a new session never inherits old proof.
    private val _answeredCommand = MutableStateFlow(false)
    val answeredCommand: StateFlow<Boolean> = _answeredCommand

    /** Event bus: VirtualESP32 results flow to the intelligence engine. */
    private fun pubVesp(id: String, ok: Boolean, summary: String) {
        try {
            IntelEventBus.publish(IntelEvent.ToolOpResult(id, ok, summary.take(1200)))
        } catch (_: Exception) {
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                EVENT_GUEST_STARTED -> {
                    queryStatus()
                    pubVesp("vesp32.guest", true, "VirtualESP32 guest firmware started.")
                }
                EVENT_GUEST_STOPPED -> {
                    queryStatus()
                    pubVesp("vesp32.guest", true, "VirtualESP32 guest firmware stopped.")
                }
                EVENT_GUEST_RESET -> { /* no-op */ }
                EVENT_UART_RX -> {
                    val data = intent.getStringExtra("data") ?: return
                    _answeredCommand.value = true
                    appendUart("RX: $data")
                }
                EVENT_GPIO_CHANGED -> {
                    val pin = intent.getIntExtra("pin", -1)
                    val high = intent.getBooleanExtra("high", false)
                    val isOutput = intent.getBooleanExtra("isOutput", true)
                    if (pin == 2 && isOutput) _gpio2State.value = high
                    if (pin == 4 && !isOutput) _gpio4State.value = high
                }
                EVENT_ERROR -> {
                    val msg = intent.getStringExtra("message") ?: "unknown error"
                    _lastError.value = msg
                    try {
                        IntelEventBus.publish(IntelEvent.IntelError("VirtualESP32", msg.take(500)))
                    } catch (_: Exception) {
                    }
                }
                EVENT_STATUS -> {
                    val json = intent.getStringExtra("data") ?: return
                    try {
                        val j = JSONObject(json)
                        _targetInfo.value = TargetInfo(
                            status = j.optString("status", "?"),
                            cpu = j.optString("cpu", "?"),
                            backend = j.optString("backend", "?"),
                            firmware = j.optString("firmware", "?")
                        )
                        pubVesp(
                            "vesp32.status", true,
                            "VirtualESP32: status=${j.optString("status", "?")}, " +
                                "firmware=${j.optString("firmware", "?")}."
                        )
                    } catch (_: Exception) {}
                }
                EVENT_GPIO_VALUE -> {
                    val pin = intent.getIntExtra("pin", -1)
                    val value = intent.getIntExtra("value", -1)
                    if (pin == 2 && value >= 0) _gpio2State.value = value == 1
                }
                EVENT_FIRMWARE_LOADED -> {
                    val name = intent.getStringExtra("fileName") ?: "firmware.bin"
                    val path = intent.getStringExtra("filePath") ?: ""
                    val type = intent.getStringExtra("imageType") ?: "Unknown"
                    val size = intent.getLongExtra("fileSize", 0)
                    val summary = intent.getStringExtra("validationSummary") ?: ""
                    val builtin = intent.getBooleanExtra("isBuiltin", false)
                    _firmwareInfo.value = FirmwareInfo(name, path, type, size, summary, builtin)
                    appendUart("RX: Firmware loaded: $name ($type)")
                    pubVesp("vesp32.firmware", true, "Firmware loaded: $name ($type, $size bytes).")
                }
                EVENT_FIRMWARE_EJECTED -> {
                    _firmwareInfo.value = null
                    appendUart("RX: Firmware ejected")
                    pubVesp("vesp32.firmware", true, "Firmware ejected.")
                }
            }
        }
    }

    private var receiverRegistered = false

    /** True if the Virtual ESP32 app is installed (package visible). */
    fun isInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(PKG, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun connect() {
        if (!isInstalled()) return
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(EVENT_GUEST_STARTED)
                addAction(EVENT_GUEST_STOPPED)
                addAction(EVENT_GUEST_RESET)
                addAction(EVENT_UART_RX)
                addAction(EVENT_GPIO_CHANGED)
                addAction(EVENT_ERROR)
                addAction(EVENT_STATUS)
                addAction(EVENT_GPIO_VALUE)
                addAction(EVENT_FIRMWARE_LOADED)
                addAction(EVENT_FIRMWARE_EJECTED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                // Must be EXPORTED to receive broadcasts from VirtualESP32 (different app).
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
        _connectionState.value = ConnectionState.Connected
        queryStatus()
        readGpio2()
    }

    fun disconnect() {
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        _connectionState.value = ConnectionState.Disconnected
        _targetInfo.value = null
        _answeredCommand.value = false
    }

    private fun sendCommand(action: String, build: (Intent) -> Unit = {}) {
        val intent = Intent(action).apply {
            // Explicit broadcast to VirtualESP32's command receiver.
            // (Cross-app startService is blocked on Android 15+; explicit broadcasts work.)
            setClassName(PKG, "$PKG.BridgeCommandReceiver")
            build(this)
        }
        try {
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    fun startGuest() {
        appendUart("TX: START command sent")
        sendCommand(ACTION_START_GUEST)
        scope.launch {
            delay(2500)
            // If the service never responded, say so plainly instead of silence.
            if (_targetInfo.value == null && lastError.value == null) {
                appendUart("RX: ERROR - no response from VirtualESP32 service. Check: (1) VirtualESP32 bridge build installed, (2) firmware imported in VirtualESP32 app.")
            }
            queryStatus()
            readGpio2()
        }
    }
    fun stopGuest() = sendCommand(ACTION_STOP_GUEST)
    fun resetGuest() = sendCommand(ACTION_RESET_GUEST)

    fun sendUart(data: String) {
        appendUart("TX: $data")
        sendCommand(ACTION_SEND_UART) { it.putExtra("data", data) }
    }

    fun setGpio4(high: Boolean) {
        sendCommand(ACTION_WRITE_GPIO) {
            it.putExtra("pin", 4)
            it.putExtra("high", high)
        }
        _gpio4State.value = high
    }

    fun queryStatus() = sendCommand(ACTION_QUERY_STATUS)

    fun readGpio2() = sendCommand(ACTION_READ_GPIO) { it.putExtra("pin", 2) }

    fun loadFirmware(uri: android.net.Uri) {
        appendUart("TX: LOAD_FIRMWARE $uri")
        sendCommand(ACTION_LOAD_FIRMWARE) {
            it.data = uri
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            it.putExtra("uri", uri.toString())
        }
    }

    fun ejectFirmware() {
        appendUart("TX: EJECT_FIRMWARE")
        sendCommand(ACTION_EJECT_FIRMWARE)
    }

    fun useBuiltinFirmware() {
        appendUart("TX: USE_BUILTIN_FIRMWARE")
        sendCommand(ACTION_USE_BUILTIN_FIRMWARE)
    }

    private fun appendUart(line: String) {
        val cur = _uartLog.value
        _uartLog.value = (cur + line).takeLast(200)
    }

    fun clearUartLog() {
        _uartLog.value = emptyList()
    }
}
