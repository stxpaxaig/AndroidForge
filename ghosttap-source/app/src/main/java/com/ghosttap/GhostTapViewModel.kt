package com.ghosttap

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.nfc.tech.Ndef
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Build
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghosttap.core.hexToBytes
import com.ghosttap.core.toHexString
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus
import com.ghosttap.core.printer.ALL_LANGUAGES
import com.ghosttap.core.printer.PrinterLanguage
import com.ghosttap.core.text.DeviceTextLanguage
import com.ghosttap.core.text.RawBytes
import com.ghosttap.core.parseHexScript
import com.ghosttap.core.buildNfcVReadSingleBlock
import com.ghosttap.core.parseNfcVResponse
import com.ghosttap.core.buildNtagPwdAuth
import com.ghosttap.core.buildMagicUnlock
import com.ghosttap.core.buildMagicWriteBlock0Prefix
import com.ghosttap.core.isMagicAck
import com.ghosttap.core.DeviceClassifier
import com.ghosttap.core.DeviceSignals
import com.ghosttap.core.Confidence
import com.ghosttap.core.describeRssi
import com.ghosttap.core.investigate.InvestigationInput
import com.ghosttap.core.investigate.InvestigationLedger
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.Investigator
import com.ghosttap.core.investigate.LiveTargetStore
import com.ghosttap.core.ChannelRanker
import com.ghosttap.core.PacketInterpreter
import com.ghosttap.core.SigUuids
import com.ghosttap.core.ActionEngine
import com.ghosttap.core.DeviceAction
import com.ghosttap.core.NfcFacts
import com.ghosttap.core.investigateNfcTagRead
import com.ghosttap.core.readNfcFacts
import com.ghosttap.core.nfcSupportedOps
import com.ghosttap.core.friendlyTechName
import com.ghosttap.core.ndefFitsOnCard
import com.ghosttap.core.emulatableFromSummary
import com.ghosttap.core.ApduResult
import com.ghosttap.core.decodeApduStatus
import com.ghosttap.core.describeApduCommand
import com.ghosttap.core.buildSelectAid
import com.ghosttap.core.bytesToAsciiOrNull
import com.ghosttap.data.BaselineDiff
import com.ghosttap.data.BaselineSnapshot
import com.ghosttap.data.BaselineStore
import com.ghosttap.data.BaselineTarget
import com.ghosttap.data.Capability
import com.ghosttap.data.ChannelChange
import com.ghosttap.data.ControlKind
import com.ghosttap.data.DeviceSummary
import com.ghosttap.data.DiscoveredCharacteristic
import com.ghosttap.data.DiscoveredService
import com.ghosttap.data.HistoryEntry
import com.ghosttap.data.LearnedAction
import com.ghosttap.data.LearnedDiscovery
import com.ghosttap.data.LearnedStore
import com.ghosttap.data.MapCapture
import com.ghosttap.data.PacketEvent
import com.ghosttap.data.PrintCheck
import com.ghosttap.data.GhostTapDatabase
import com.ghosttap.data.LogEntry
import com.ghosttap.data.ScanTarget
import com.ghosttap.data.TargetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class GhostTapViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = getApplication<Application>()
    private val db = GhostTapDatabase.get(appContext)
    private val baselineStore = BaselineStore(appContext)

    val logs: StateFlow<List<LogEntry>> = db.logDao().observeAll()
        .map { it.take(500) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val targets = ConcurrentHashMap<String, ScanTarget>()
    private val gattMap = ConcurrentHashMap<String, BluetoothGatt>()
    /** In-flight BLE connect timeout watchdogs: targetId -> job. Cancelled on resolve. */
    private val connectTimeoutJobs = ConcurrentHashMap<String, Job>()
    private val bleServices = ConcurrentHashMap<String, List<BluetoothGattService>>()
    // Classic Bluetooth SPP (RFCOMM) connections: targetId -> socket.
    // Separate from BLE GATT — a device can only use one at a time.
    private val sppSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val sppReaders = ConcurrentHashMap<String, Job>()
    private val sppStatus = ConcurrentHashMap<String, String>()
    /** SPP terminal log per target: timestamped TX/RX lines. */
    private val _sppLog = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val sppLog: StateFlow<Map<String, List<String>>> = _sppLog
    // Human-readable Bluetooth connection state per target, e.g. "CONNECTING…",
    // "CONNECTED — 3 feature groups, 5 features", "Not connected", "Couldn't list what this device can do".
    private val gattStatus = ConcurrentHashMap<String, String>()
    // Discovered services/characteristics per target. Populated ONLY from data
    // returned by a real BluetoothGatt connection — never fabricated.
    private val gattDiscovered = ConcurrentHashMap<String, List<DiscoveredService>>()
    // Targets whose last failure was an auth error (status 5/15): they need
    // phone pairing before they'll talk. Shown as NEEDS PAIRING FIRST steps.
    private val pairingNeeded = ConcurrentHashMap<String, Boolean>()

    // ---- Package 3 (operationalization) — ViewModel-side state ----
    // All additive: new state the UI and the operations read. No existing
    // function changes what it does; these only record what already happened.

    /**
     * Channels with live updates switched on, per target: targetId ->
     * set of "serviceUuid|charUuid". The single source of truth for the
     * notify toggles (A-15) — the UI reads this instead of keeping its own
     * copy, so a toggle can no longer drift from the real subscription
     * state when the screen recomposes. Records what Ghost Tap ASKED for;
     * a device that ignores the descriptor still looks subscribed here
     * (the platform gives no confirmation callback on this path).
     */
    var notifyChannels by mutableStateOf<Map<String, Set<String>>>(emptyMap())
        private set

    /**
     * Last target whose link actually completed (BLE or SPP). The UI reads
     * this to jump straight to that device's controls after a connect from
     * a card (A-08). Cleared by [consumeConnectCompleted].
     */
    var connectCompletedTargetId: String? by mutableStateOf(null)
        private set

    /** Pending "did it print?" check after a successful print (A-19 / D-50). */
    var printCheckPending by mutableStateOf<PrintCheck?>(null)
        private set

    /** Last Visual Intel hint text, so a scan can highlight the matching device (B-050). */
    var lastVisualHint: String = ""
        private set

    private val targetNamePrefs by lazy {
        getApplication<Application>().getSharedPreferences("ghosttap_target_names", 0)
    }
    private val hiddenTargetPrefs by lazy {
        getApplication<Application>().getSharedPreferences("ghosttap_hidden_targets", 0)
    }

    private var bondReceiverRegistered = false
    /** address -> targetId waiting for pairing to finish (A-14). */
    private val pendingBondReconnect = ConcurrentHashMap<String, String>()
    // Writable characteristic the user tapped for RAW commands:
    // targetId -> (serviceUuid, charUuid).
    private val rawSelection = ConcurrentHashMap<String, Pair<String, String>>()
    // Live translator feed: captured packets per target (newest last, capped).
    private val packetFeed = ConcurrentHashMap<String, MutableList<PacketEvent>>()
    // Last received bytes per "targetId|charUuid" — RX only, so TX writes never
    // pollute change detection or learn-mode baselines.
    private val lastPacketBytes = ConcurrentHashMap<String, ByteArray>()
    // How often each exact byte-change signature recurred: "targetId|charUuid|idx,idx" -> count.
    private val changeSigCounts = ConcurrentHashMap<String, Int>()
    // Active learn-mode session, if any.
    @Volatile
    private var learningSession: LearningSession? = null
    // Finished learn comparison awaiting a user-supplied name: targetId -> discovery.
    private val pendingDiscovery = ConcurrentHashMap<String, LearnedDiscovery>()
    private val learnedStore = LearnedStore(appContext)

    // ---------------- v1.7: multitool state ----------------
    /** NFC facts per target, gathered at tag detection — never fabricated. */
    private val nfcFacts = ConcurrentHashMap<String, NfcFacts>()
    /** Last NDEF message the user copied, held in memory for writing to another tag. */
    @Volatile
    private var savedNdef: NdefMessage? = null
    @Volatile
    private var savedNdefSummary: String = ""

    /** Plain-English summary of the most recently tapped tag — for the NFC TOOLS screen. */
    var lastNfcReadSummary by mutableStateOf("No tag tapped yet.")
        private set

    /** True while the phone is pretending to be an NFC tag. */
    var isEmulating by mutableStateOf(false)
        private set
    /** APDU console history per NFC target: targetId -> results. */
    private val apduHistory = ConcurrentHashMap<String, MutableList<ApduResult>>()
    /** MAP INTERFACE endpoint response history per BLE target: targetId -> entries. */
    private val endpointHistory = ConcurrentHashMap<String, MutableList<HistoryEntry>>()
    /** Finished MAP INTERFACE capture rounds per BLE target. */
    private val mapCaptures = ConcurrentHashMap<String, MutableList<MapCapture>>()
    /** Active MAP INTERFACE capture round, if any. */
    private var mapRound: MapRound? = null

    private data class MapRound(
        val targetId: String,
        val label: String,
        val startedAtMs: Long,
        val before: Map<String, ByteArray>
    )

    private data class LearningSession(
        val targetId: String,
        val startedAtMs: Long,
        val baseline: Map<String, ByteArray>
    )
    // Advertisement-time signals per target, so identification can be re-run
    // with richer data after GATT service discovery.
    private val adSignals = ConcurrentHashMap<String, DeviceSignals>()

    // Autonomous Investigations (BLE): advertised-data fingerprint of
    // each target investigated this session. A target is re-investigated
    // only when its advertised data actually changes — never on every
    // scan tick, and never for a signal-strength change alone.
    private val bleInvestigatedFingerprints = ConcurrentHashMap<String, String>()

    @Volatile
    private var scanning = false

    /**
     * DISCOVER (Home / Bluetooth): set only by [discoverBluetooth], for
     * the scan session that tap starts. While it is on, [autoInvestigateBle]
     * investigates EVERY device the scan finds, including ones already
     * known — the new/changed-only fingerprint gate is lifted. It clears
     * in [stopScan], so a later ordinary scan goes back to new/changed
     * only. Nothing connects or controls anything while this is on;
     * investigation stays report-only.
     */
    @Volatile
    private var bleDiscoverMode = false

    /**
     * DISCOVER (Home / Bluetooth): start a fresh scan and check EVERYTHING
     * it finds, including devices already known. No typing, no setup.
     * Findings land in the ledger (refreshed in place, never duplicated)
     * and on the home card and the Investigations timeline. Nothing
     * connects until the user taps a device.
     */
    fun discoverBluetooth() {
        bleDiscoverMode = true
        setStatus("Checking every Bluetooth device found, including ones already known…")
        startScan()
    }

    var uiTick by mutableIntStateOf(0)
        private set

    var scanStatus by mutableStateOf("Ready — tap SCAN to look for devices.")
        private set

    @Volatile
    var selectedTargetId: String? = null

    @Volatile
    var baselineDiff: BaselineDiff = BaselineDiff()
        private set

    @Volatile
    var lastNfcTag: Tag? = null
        private set

    @Volatile
    var audioSpectrumSummary: String = "N/A"
        private set

    private var audioJob: Job? = null

    val allTargets: List<ScanTarget>
        get() = targets.values.sortedByDescending { it.lastSeenMs }

    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val bleScanCallback = object : ScanCallback() {
        /** Throttle: scan-batch summaries at most every 30s — a busy radio must not spam the bus. */
        private var lastBatchPublishMs = 0L

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // On Android 12+, reading device address/name requires BLUETOOTH_CONNECT.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                setStatus("Bluetooth permission needed — allow it in Settings, then try again.")
                return
            }
            val address = runCatching { result.device.address }.getOrNull() ?: return
            val cap = mutableListOf<Capability>()
            cap += Capability("ble_connect", "Connect with Bluetooth", ControlKind.BUTTON, readable = true, writable = true)

            val serviceIds = result.scanRecord?.serviceUuids?.map(ParcelUuid::toString).orEmpty()
            val manufacturerId: Int? = result.scanRecord?.manufacturerSpecificData?.let { arr ->
                if (arr.size() > 0) arr.keyAt(0) else null
            }
            val manufacturerDataHex: String? = result.scanRecord?.manufacturerSpecificData?.let { arr ->
                if (arr.size() > 0) arr.valueAt(0)?.toHexString()?.take(96) else null
            }
            val serviceDataUuids = result.scanRecord?.serviceData?.keys?.map { it.toString() }.orEmpty()
            val btClass: Int? = runCatching { result.device.bluetoothClass?.deviceClass }.getOrNull()
            val connectable: Boolean = result.isConnectable
            val identity = mutableMapOf(
                "address" to address,
                "protocol" to "BLE",
                "bondState" to runCatching { result.device.bondState.toString() }.getOrDefault("unknown"),
                "txPower" to (result.txPower.takeIf { it != Int.MIN_VALUE }?.toString() ?: "unknown"),
                "connectable" to connectable.toString()
            )
            if (serviceIds.isNotEmpty()) {
                identity["advertisedServices"] = serviceIds.joinToString(",")
            }
            if (serviceDataUuids.isNotEmpty()) {
                identity["serviceData"] = serviceDataUuids.joinToString(",")
            }
            manufacturerId?.let {
                identity["manufacturerId"] = "0x" + it.toString(16).uppercase()
            }
            manufacturerDataHex?.let {
                identity["manufacturerData"] = it
            }
            btClass?.takeIf { it != 0 }?.let {
                identity["bluetoothClass"] = "0x" + it.toString(16).uppercase()
            }
            val devName = runCatching { result.device.name }.getOrNull()
            val targetId = "ble:$address"
            val signals = DeviceSignals(
                name = devName,
                address = address,
                protocol = "BLE",
                advertisedServiceUuids = serviceIds,
                manufacturerId = manufacturerId,
                manufacturerDataHex = manufacturerDataHex,
                serviceDataUuids = serviceDataUuids,
                deviceClassFull = btClass,
                connectable = connectable,
                txPower = result.txPower.takeIf { it != Int.MIN_VALUE },
                rssi = result.rssi
            )
            adSignals[targetId] = signals
            val idn = DeviceClassifier.identify(signals)

            upsertTarget(
                ScanTarget(
                    id = targetId,
                    name = devName ?: "Bluetooth device $address",
                    type = TargetType.BLE,
                    identity = identity,
                    capabilities = cap,
                    rssi = result.rssi,
                    connected = gattMap.containsKey(targetId),
                    lastSeenMs = System.currentTimeMillis(),
                    likely = idn.label,
                    confidence = DeviceClassifier.confidenceName(idn.confidence),
                    evidence = idn.evidence
                )
            )
            // Autonomous Investigations: a device new to this scan gets
            // investigated on its own, from what it actually advertised.
            autoInvestigateBle(
                targetId = targetId,
                devName = devName,
                serviceIds = serviceIds,
                serviceDataUuids = serviceDataUuids,
                manufacturerId = manufacturerId,
                btClass = btClass,
                connectable = connectable,
                rssi = result.rssi
            )
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            val before = targets.keys.toSet()
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            // Intelligence: which devices in this batch are brand new.
            val now = System.currentTimeMillis()
            if (now - lastBatchPublishMs > 30_000) {
                val fresh = (targets.keys - before).take(8)
                if (fresh.isNotEmpty()) {
                    lastBatchPublishMs = now
                    val names = fresh.mapNotNull { targets[it]?.name }
                    pubOp(
                        "ble.scan", true,
                        "${fresh.size} new Bluetooth device(s) found: ${names.joinToString(", ").take(300)}"
                    )
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val msg = "Bluetooth scan failed (code $errorCode). Try again."
            setStatus(msg)
            log("BLE", null, msg)
            pubErr("Bluetooth scan", msg)
        }
    }

    /**
     * Autonomous Investigations (BLE): when a BLE device appears on a
     * scan, look at what it actually advertised and file a findings
     * report — nothing is invented. A device with no advertised name
     * stays unnamed (rawName null); the framework reports it UNKNOWN
     * rather than guessing. Signal is shown as "-86 dBm — Weak", never
     * as a distance. answeredHandshake is true only if this target has
     * a real READ answer on record; a device seen on a scan alone has
     * proven nothing yet, so it stays false.
     */
    private fun autoInvestigateBle(
        targetId: String,
        devName: String?,
        serviceIds: List<String>,
        serviceDataUuids: List<String>,
        manufacturerId: Int?,
        btClass: Int?,
        connectable: Boolean,
        rssi: Int
    ) {
        val fingerprint = listOf(
            devName ?: "",
            serviceIds.sorted().joinToString(","),
            serviceDataUuids.sorted().joinToString(","),
            manufacturerId?.toString() ?: "",
            btClass?.toString() ?: "",
            connectable.toString()
        ).joinToString("|")
        // DISCOVER mode (an "Investigate the unfamiliar" tap) lifts the
        // new/changed-only gate: everything this scan finds is checked,
        // including devices already known. The fingerprint is still
        // recorded, so the next ordinary scan goes back to new/changed only.
        if (!bleDiscoverMode && bleInvestigatedFingerprints[targetId] == fingerprint) return
        bleInvestigatedFingerprints[targetId] = fingerprint
        val details = mutableMapOf<String, String>()
        if (serviceIds.isNotEmpty()) details["advertisedServices"] = serviceIds.joinToString(", ")
        if (serviceDataUuids.isNotEmpty()) details["serviceData"] = serviceDataUuids.joinToString(", ")
        manufacturerId?.let { details["manufacturerId"] = "0x" + it.toString(16).uppercase() }
        btClass?.takeIf { it != 0 }?.let { details["bluetoothClass"] = "0x" + it.toString(16).uppercase() }
        details["connectable"] = if (connectable) "yes" else "no"
        val readable = mutableListOf<String>()
        devName?.takeIf { it.isNotBlank() }?.let { readable += "Advertised name: $it" }
        if (serviceIds.isNotEmpty()) readable += "Offers: ${serviceIds.joinToString(", ")}"
        // Proof only: a READ on record means this device really answered
        // a read. Anything else is not proof, so it stays false.
        val answered = endpointHistoryFor(targetId).any { it.kind == "READ" }
        val report = runCatching {
            Investigator.investigate(
                InvestigationInput(
                    source = InvestigationSource.BLE,
                    rawName = devName?.takeIf { it.isNotBlank() },
                    rawDetails = details,
                    readableValues = readable,
                    answeredHandshake = answered,
                    signalText = "$rssi dBm — ${describeRssi(rssi)}",
                    targetId = targetId
                )
            )
        }.getOrNull() ?: return
        // DISCOVER refresh: a known device's new report replaces its old
        // one instead of stacking a duplicate in the timeline.
        InvestigationLedger.upsert(report)
        // MASTER PROMPT: the same report is also a LIVE TARGET — what
        // the user sees and connects to. The ledger stays as internal
        // storage/dedup only.
        LiveTargetStore.publish(report, InvestigationSource.BLE)
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // Package 3 (B-003): Bluetooth switched off mid-scan.
                    // The stop runs on the ViewModel scope, not inside the
                    // receiver callback (unregistering from inside onReceive
                    // is not a safe place to tear the scan down).
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) {
                        setStatus("Bluetooth was turned off — the scan stopped. Turn Bluetooth back on and tap Find devices.")
                        log("BT", null, "Bluetooth turned off mid-scan")
                        if (scanning) viewModelScope.launch { stopScan() }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    setStatus("Looking for older Bluetooth devices…")
                    log("BT", null, "Classic discovery started")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    setStatus("Finished looking for older Bluetooth devices.")
                    log("BT", null, "Classic discovery finished")
                }
                BluetoothDevice.ACTION_FOUND -> {
                    // On Android 12+, device properties require BLUETOOTH_CONNECT.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    ) {
                        setStatus("Bluetooth permission needed — allow it in Settings, then try again.")
                        return
                    }
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val address = runCatching { device.address }.getOrNull() ?: return
                    val devName = runCatching { device.name }.getOrNull()
                    val bondState = runCatching { device.bondState }.getOrNull()
                    val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
                    val fullClass = runCatching { device.bluetoothClass?.deviceClass }.getOrNull()
                    val targetId = "bt:$address"
                    val idn = DeviceClassifier.identify(
                        DeviceSignals(
                            name = devName,
                            address = address,
                            protocol = "CLASSIC",
                            deviceClassFull = fullClass,
                            rssi = if (rssi == Short.MIN_VALUE.toInt()) null else rssi
                        )
                    )
                    adSignals[targetId] = DeviceSignals(
                        name = devName,
                        address = address,
                        protocol = "CLASSIC",
                        deviceClassFull = fullClass
                    )
                    upsertTarget(
                        ScanTarget(
                            id = targetId,
                            name = devName ?: "Bluetooth device $address",
                            type = TargetType.BLUETOOTH_CLASSIC,
                            identity = mapOf(
                                "address" to address,
                                "protocol" to "Bluetooth Classic",
                                "bondState" to (bondState?.toString() ?: "unknown"),
                                "class" to (majorClass?.toString() ?: "unknown")
                            ),
                            capabilities = listOf(
                                Capability("bt_pair", "Pair", ControlKind.BUTTON, readable = true),
                                Capability("bt_auth", "Pairing needed", ControlKind.BUTTON)
                            ),
                            rssi = if (rssi == Short.MIN_VALUE.toInt()) null else rssi,
                            requiresAuth = bondState != BluetoothDevice.BOND_BONDED,
                            lastSeenMs = System.currentTimeMillis(),
                            likely = idn.label,
                            confidence = DeviceClassifier.confidenceName(idn.confidence),
                            evidence = idn.evidence
                        )
                    )
                }
            }
        }
    }

    /**
     * Package 3 (A-14) — pairing completion listener. Android reports bond
     * changes on [BluetoothDevice.ACTION_BOND_STATE_CHANGED]; when a bond
     * this app started finishes, the connection that failed for pairing is
     * retried automatically instead of leaving the user to tap Connect
     * again by hand. Registered only while a pairing this app started is
     * outstanding; unregistered in [onCleared].
     */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return
            val address = runCatching { device.address }.getOrNull() ?: return
            val targetId = pendingBondReconnect[address] ?: return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingBondReconnect.remove(address)
                    log("BLE", targetId, "Pairing finished — connecting again automatically")
                    setGattStatus(targetId, "Paired — connecting again…")
                    connectBle(targetId)
                }
                BluetoothDevice.BOND_NONE -> {
                    pendingBondReconnect.remove(address)
                    log("BLE", targetId, "Pairing didn't finish — tap Connect to try again")
                    setGattStatus(targetId, "Pairing didn't finish — tap Connect to try again.")
                }
            }
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(bondReceiver, filter)
            }
            bondReceiverRegistered = true
        }.onFailure {
            log("BLE", null, "Bond listener register failed: ${it.message}")
        }
    }

    /** True when the phone is already paired (bonded) with this target. */
    fun isPaired(targetId: String): Boolean {
        val address = targets[targetId]?.identity?.get("address") ?: return false
        return runCatching {
            bluetoothAdapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED
        }.getOrDefault(false)
    }

    /**
     * Package 3 (B-019) — does this device offer the classic text link
     * (SPP)? Asks Android for the device's advertised services (SDP) and
     * checks for the SPP UUID. Returns true (offers it), false (says it
     * doesn't), or null (couldn't tell — the check itself failed or timed
     * out, so the caller should try the connection and let it fail
     * honestly instead of trusting this answer).
     */
    suspend fun checkSppOffered(targetId: String): Boolean? {
        val address = targets[targetId]?.identity?.get("address") ?: return null
        val device = runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull() ?: return null
        val deferred = CompletableDeferred<Boolean?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_UUID) return
                val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                if (runCatching { found.address }.getOrNull() != address) return
                val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    ?.mapNotNull { it as? ParcelUuid }
                    ?.map { it.uuid } ?: emptyList()
                deferred.complete(uuids.any { it == SPP_UUID })
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {
            return null
        }
        try {
            if (!runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)) return null
            return withTimeoutOrNull(8000L) { deferred.await() }
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            val typeName = when (event.sensor.type) {
                Sensor.TYPE_MAGNETIC_FIELD -> "Compass"
                Sensor.TYPE_ACCELEROMETER -> "Motion sensor"
                Sensor.TYPE_GYROSCOPE -> "Rotation sensor"
                else -> return
            }
            val values = event.values.joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }
            val idn = DeviceClassifier.identify(
                DeviceSignals(name = typeName, protocol = "SENSOR")
            )
            upsertTarget(
                ScanTarget(
                    id = "sensor:${event.sensor.type}",
                    name = typeName,
                    type = TargetType.SENSOR,
                    identity = mapOf(
                        "vendor" to event.sensor.vendor,
                        "version" to event.sensor.version.toString(),
                        "power_mA" to event.sensor.power.toString()
                    ),
                    capabilities = listOf(Capability("watch", "Watch live readings", ControlKind.RAW, readable = true)),
                    watchValues = mapOf("values" to values, "audio" to audioSpectrumSummary),
                    lastSeenMs = System.currentTimeMillis(),
                    likely = idn.label,
                    confidence = DeviceClassifier.confidenceName(idn.confidence),
                    evidence = idn.evidence
                )
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun startScan() {
        if (scanning) return
        scanning = true
        registerBtReceiver()
        startClassicDiscovery()
        startBleScan()
        inspectUsbNow()
        startNsdDiscovery()
        startSensors()
        startAudioMonitor()
        log("SYSTEM", null, "Scan started")
    }

    fun stopScan() {
        scanning = false
        // DISCOVER session over: the next scan goes back to
        // new/changed-only investigations.
        bleDiscoverMode = false
        runCatching { bleScanner?.stopScan(bleScanCallback) }
        runCatching { bluetoothAdapter?.cancelDiscovery() }
        runCatching { appContext.unregisterReceiver(btReceiver) }
        stopNsdDiscovery()
        stopSensors()
        stopAudioMonitor()
        log("SYSTEM", null, "Scan stopped")
    }

    fun isScanning(): Boolean = scanning

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun onPermissionsResult(denied: List<String>) {
        if (denied.isEmpty()) {
            startScan()
        } else {
            val names = denied.joinToString(", ") { it.substringAfterLast('.') }
            setStatus("Permission denied: $names. Allow it in Settings, then try again.")
            log("SYSTEM", null, "Permissions denied: $denied")
        }
    }

    private fun setStatus(s: String) {
        scanStatus = s
        uiTick++
    }

    /** Ask Android for permission to talk to a USB device. The grant lands via [usbPermissionReceiver]. */
    private var usbPermissionReceiverRegistered = false

    private fun requestUsbPermission(device: UsbDevice) {
        if (!usbPermissionReceiverRegistered) {
            registerUsbPermissionReceiver()
            usbPermissionReceiverRegistered = true
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            appContext, device.deviceId,
            Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager.requestPermission(device, pi)
        log("USB", "usb:${device.deviceId}", "USB permission requested")
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log("USB", null, if (granted) "USB permission granted" else "USB permission denied")
            refreshUsbAndSensorsWatch()
            uiTick++
        }
    }

    private fun registerUsbPermissionReceiver() {
        runCatching {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(usbPermissionReceiver, filter)
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.ghosttap.USB_PERMISSION"
        /** Standard Serial Port Profile UUID. */
        val SPP_UUID: java.util.UUID = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private fun registerBtReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            // Package 3 (B-003): Bluetooth being switched off mid-scan must
            // surface as an event, not leave the UI polling a stale scan.
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // Android 13+ (targetSdk 34) requires an explicit exported flag for
        // runtime receivers; without it registerReceiver throws and the
        // receiver silently never exists (wrapped in runCatching below).
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(btReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(btReceiver, filter)
            }
        }.onFailure {
            setStatus("Couldn't start listening for Bluetooth devices: ${it.message}")
            log("BT", null, "Receiver register failed: ${it.message}")
        }
    }

    private fun startClassicDiscovery() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setStatus("This phone has no Bluetooth.")
            log("BT", null, "No Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            // No refusal on the setting: the attempt below is what
            // reports it — startDiscovery() returns false when the
            // radio is off, and the outcome is said plainly.
            log("BT", null, "Bluetooth OFF; classic discovery attempted anyway")
        }
        val hasBtPerm = hasPermission(Manifest.permission.BLUETOOTH_SCAN) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        if (!hasBtPerm) {
            setStatus("Bluetooth scan permission needed — allow it in Settings, then try again.")
            log("BT", null, "Missing BLUETOOTH_SCAN permission")
            return
        }
        runCatching {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            listPairedDevices(adapter)
            val started = adapter.startDiscovery()
            if (started) {
                log("BT", null, "Classic discovery start requested")
            } else {
                setStatus("Couldn't start looking for older Bluetooth devices.")
                log("BT", null, "adapter.startDiscovery() returned false")
            }
        }.onFailure {
            setStatus("Looking for older Bluetooth devices failed: ${it.message}")
            log("BT", null, "Classic discovery error: ${it.message}")
        }
    }

    /**
     * List already-paired devices as targets. Discovery won't find them,
     * but they're the most likely SPP targets (e.g. a paired printer).
     * Extracted unchanged from [startClassicDiscovery] so a refresh can
     * re-list them without starting a new scan (B-006).
     */
    private fun listPairedDevices(adapter: BluetoothAdapter) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            runCatching {
                adapter.bondedDevices?.forEach { device ->
                    val address = runCatching { device.address }.getOrNull() ?: return@forEach
                    val devName = runCatching { device.name }.getOrNull()
                    val targetId = "bt:$address"
                    if (targets[targetId] == null) {
                        val idn = DeviceClassifier.identify(
                            DeviceSignals(
                                name = devName,
                                address = address,
                                protocol = "CLASSIC"
                            )
                        )
                        upsertTarget(
                            ScanTarget(
                                id = targetId,
                                name = devName ?: "Bluetooth device $address",
                                type = TargetType.BLUETOOTH_CLASSIC,
                                identity = mapOf(
                                    "address" to address,
                                    "name" to (devName ?: ""),
                                    "paired" to "yes"
                                ),
                                likely = idn.label,
                                confidence = DeviceClassifier.confidenceName(idn.confidence),
                                evidence = idn.evidence
                            )
                        )
                        log("BT", targetId, "Paired device listed: ${devName ?: address}")
                    }
                }
            }
        }
    }

    /** Package 3 (B-006): re-list paired devices without starting a new scan. */
    fun refreshPairedDevices() {
        val adapter = bluetoothAdapter ?: return
        listPairedDevices(adapter)
        log("BT", null, "Paired devices refreshed")
    }

    private fun startBleScan() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            setStatus("This phone has no Bluetooth.")
            log("BLE", null, "No Bluetooth adapter")
            return
        }
        // No pre-check refusal on the setting: the scanner is null
        // when Bluetooth is off, so the attempt itself is what finds
        // out — and the outcome is said plainly.
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            setStatus("Couldn't look for Bluetooth devices — Bluetooth is off on the phone right now.")
            log("BLE", null, "bluetoothLeScanner returned null")
            return
        }
        val hasPerm = hasPermission(Manifest.permission.BLUETOOTH_SCAN) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        if (!hasPerm) {
            setStatus("Bluetooth scan permission needed — allow it in Settings, then try again.")
            log("BLE", null, "Missing BLUETOOTH_SCAN permission")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // null filters = unfiltered scan: every advertisement is delivered.
        runCatching { scanner.startScan(null, settings, bleScanCallback) }
            .onSuccess {
                setStatus("Looking for Bluetooth devices…")
                log("BLE", null, "BLE scan started (low latency, unfiltered)")
            }
            .onFailure {
                setStatus("Bluetooth scan failed: ${it.message}")
                log("BLE", null, "BLE scan failed: ${it.message}")
            }
    }

    private val nsdListeners = mutableListOf<NsdManager.DiscoveryListener>()

    private fun startNsdDiscovery() {
        val serviceTypes = listOf("_http._tcp.", "_https._tcp.", "_mqtt._tcp.")
        serviceTypes.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: "unknown"
                            val port = resolved.port
                            val id = "net:${resolved.serviceName}:$host:$port"
                            val idn = DeviceClassifier.identify(
                                DeviceSignals(
                                    name = resolved.serviceName,
                                    address = "$host:$port",
                                    protocol = "NETWORK"
                                )
                            )
                            upsertTarget(
                                ScanTarget(
                                    id = id,
                                    name = resolved.serviceName,
                                    type = TargetType.NETWORK,
                                    identity = mapOf(
                                        "serviceType" to resolved.serviceType,
                                        "host" to host,
                                        "port" to port.toString()
                                    ),
                                    capabilities = listOf(
                                        Capability("net_get", "Fetch web page", ControlKind.BUTTON, readable = true),
                                        Capability("net_raw", "Send web request", ControlKind.RAW, readable = true, writable = true)
                                    ),
                                    lastSeenMs = System.currentTimeMillis(),
                                    likely = idn.label,
                                    confidence = DeviceClassifier.confidenceName(idn.confidence),
                                    evidence = idn.evidence
                                )
                            )
                        }
                    })
                }
            }
            runCatching {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                nsdListeners += listener
            }
        }
    }

    private fun stopNsdDiscovery() {
        nsdListeners.forEach { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        nsdListeners.clear()
    }

    private fun startSensors() {
        listOf(
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE
        ).mapNotNull(sensorManager::getDefaultSensor)
            .forEach { sensor ->
                sensorManager.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(sensorListener)
    }

    private fun startAudioMonitor() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return
        if (audioJob != null) return
        audioJob = viewModelScope.launch(Dispatchers.Default) {
            val sampleRate = 8000
            val minSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minSize <= 0) return@launch
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minSize * 2
            )
            val buffer = ShortArray(minSize)
            runCatching { recorder.startRecording() }
            while (isActive && scanning) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var zeroCross = 0
                    var sumAbs = 0.0
                    for (i in 1 until read) {
                        val prev = buffer[i - 1]
                        val cur = buffer[i]
                        if ((prev >= 0 && cur < 0) || (prev < 0 && cur >= 0)) zeroCross++
                        sumAbs += abs(cur.toInt())
                    }
                    val avg = (sumAbs / read) / 32767.0
                    val freq = zeroCross * (sampleRate / (2.0 * read))
                    audioSpectrumSummary = "amp=${"%.3f".format(avg)} domHz=${"%.1f".format(freq)}"
                }
            }
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun stopAudioMonitor() {
        audioJob?.cancel()
        audioJob = null
    }

    private fun inspectUsbNow() {
        usbManager.deviceList.values.forEach { device ->
            val idn = DeviceClassifier.identify(
                DeviceSignals(
                    name = device.productName,
                    address = "${device.vendorId}:${device.productId}",
                    protocol = "USB"
                )
            )
            upsertTarget(
                ScanTarget(
                    id = "usb:${device.deviceId}",
                    name = device.productName ?: "USB device ${device.deviceId}",
                    type = TargetType.USB,
                    identity = mapOf(
                        "vendorId" to device.vendorId.toString(),
                        "productId" to device.productId.toString(),
                        "class" to device.deviceClass.toString(),
                        "subclass" to device.deviceSubclass.toString(),
                        "interfaces" to device.interfaceCount.toString(),
                        "serial" to (device.serialNumber ?: "n/a")
                    ),
                    capabilities = buildUsbCapabilities(device),
                    lastSeenMs = System.currentTimeMillis(),
                    likely = idn.label,
                    confidence = DeviceClassifier.confidenceName(idn.confidence),
                    evidence = idn.evidence
                )
            )
        }
    }

    private fun buildUsbCapabilities(device: UsbDevice): List<Capability> {
        val caps = mutableListOf<Capability>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (e in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(e)
                val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                caps += Capability(
                    id = "usb_${i}_${e}",
                    title = "IF${i} EP${e} $direction",
                    kind = ControlKind.RAW,
                    readable = endpoint.direction == UsbConstants.USB_DIR_IN,
                    writable = endpoint.direction == UsbConstants.USB_DIR_OUT,
                    metadata = mapOf(
                        "type" to endpoint.type.toString(),
                        "maxPacketSize" to endpoint.maxPacketSize.toString()
                    )
                )
            }
        }
        if (caps.isEmpty()) {
            caps += Capability("usb_info", "Device details only", ControlKind.BUTTON, readable = true)
        }
        return caps
    }

    fun handleNfcIntent(intent: Intent?) {
        intent ?: return
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED && action != NfcAdapter.ACTION_NDEF_DISCOVERED) return
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return
        handleNfcTag(tag)
    }

    /**
     * Entry point for reader-mode taps. MainActivity enables NFC reader mode while it is
     * open, so taps come straight here with no system popup or "Open link?" dialog.
     * Runs on the UI thread (MainActivity posts the callback there).
     */
    fun handleNfcTag(tag: Tag) {
        lastNfcTag = tag
        val facts = readNfcFacts(tag)
        val targetId = "nfc:${facts.uidHex}"
        nfcFacts[targetId] = facts
        val ops = nfcSupportedOps(facts)
        val ndefSummary = readNdefSummary(tag)

        val identity = mutableMapOf(
            "uid" to facts.uidHex,
            "likelyType" to facts.likelyType,
            "technologies" to facts.techs.joinToString(", ") { friendlyTechName(it) },
            "writable" to facts.ndefWritable.toString(),
            "supportedOps" to ops.joinToString(", ")
        )
        facts.ndefType?.let { identity["ndefType"] = it }
        facts.capacityLine?.let { identity["capacity"] = it }
        facts.isoDepHistorical?.let { identity["historicalBytes"] = it }
        facts.isoDepHiLayer?.let { identity["hiLayerResponse"] = it }
        facts.mifareUltralightType?.let { identity["mifareType"] = it }
        if (ndefSummary.isNotBlank()) identity["ndefContent"] = ndefSummary

        upsertTarget(
            ScanTarget(
                id = targetId,
                name = "${facts.likelyType} ${facts.uidHex.take(8)}",
                type = TargetType.NFC,
                identity = identity,
                capabilities = ops.map { op ->
                    Capability(
                        id = "nfc_${op.lowercase().replace(" ", "_")}",
                        title = op,
                        kind = if (op == "APDU CONSOLE") ControlKind.RAW else ControlKind.BUTTON,
                        readable = op in listOf("READ", "COPY CONTENT", "APDU CONSOLE"),
                        writable = op in listOf("WRITE TEXT", "WRITE URL", "ERASE", "FORMAT", "APDU CONSOLE")
                    )
                },
                lastSeenMs = System.currentTimeMillis(),
                likely = facts.likelyType.uppercase(),
                confidence = "High",
                evidence = listOf(
                    "Tag ID ${facts.uidHex}",
                    "Works with: ${facts.techs.joinToString(", ") { friendlyTechName(it) }}",
                    if (facts.ndefWritable) "You can write to this tag" else "You can't write to this tag"
                )
            )
        )
        log("NFC", targetId, "NFC detected: ${facts.likelyType} (${ops.joinToString(", ")})")
        lastNfcReadSummary = buildString {
            appendLine("Tag: ${facts.likelyType} (ID ${facts.uidHex})")
            appendLine("Works with: ${facts.techs.joinToString(", ") { friendlyTechName(it) }}")
            facts.chipLine?.let { appendLine(it) }
            if (ndefSummary.isNotBlank()) append(ndefSummary) else append("No saved message on it.")
        }.trim()
        // Autonomous investigation: a real tag read kicks off a findings report on its own.
        // DISCOVER: the report carries this tag's stable id, so a re-check
        // refreshes its report in place instead of duplicating it.
        runCatching { investigateNfcTagRead(facts, ndefSummary, targetId) }
        // Event bus: the phone really read a tag.
        try {
            IntelEventBus.publish(
                IntelEvent.NfcTagRead(
                    summary = lastNfcReadSummary,
                    techList = facts.techs.map { friendlyTechName(it) }
                )
            )
        } catch (_: Exception) {
        }
        // TAP-RUN: an armed script fires automatically on every tag tap,
        // through the same runner as the RUN SCRIPT button.
        tapRunArmed?.let { armedName ->
            nfcSavedScripts.firstOrNull { it.first == armedName }?.let { (_, script, tech) ->
                runCatching {
                    val result = nfcRunScript(script, tech)
                    log("NFC", targetId, "TAP-RUN [$armedName]: $result")
                }.onFailure { e ->
                    log("NFC", targetId, "TAP-RUN [$armedName] failed: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
        uiTick++
    }

    /**
     * Read every memory page off a MIFARE Ultralight / NTAG tag as raw hex.
     * Open pages read freely; locked or protected pages report an honest error.
     */
    fun nfcDumpMemory(): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        if (MifareUltralight.get(tag) == null)
            return "This tag doesn't have readable pages to copy."
        return try {
            val mu = MifareUltralight.get(tag)!!
            mu.connect()
            try {
                val pages = mutableListOf<String>()
                var i = 0
                while (i <= 256) {
                    val chunk = runCatching { mu.readPages(i) }.getOrNull() ?: break
                    if (chunk.size < 16) break
                    for (p in 0 until 4)
                        pages += "Page ${i + p}: ${chunk.copyOfRange(p * 4, p * 4 + 4).toHexString()}"
                    i += 4
                }
                if (pages.isEmpty()) "The tag didn't answer — couldn't read any pages."
                else pages.joinToString("\n")
            } finally {
                runCatching { mu.close() }
            }
        } catch (e: Exception) {
            "Couldn't copy this tag: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---------- Raw tag console: Flipper-style direct line to the tag ----------
    var rawConsoleHistory by mutableStateOf(listOf<String>())
        private set

    fun clearRawConsole() {
        rawConsoleHistory = emptyList()
        uiTick++
    }

    fun nfcRawTransceive(hex: String): String = nfcRawTransceiveTech(hex, "AUTO")

    // ---------------- raw execution: sessions, tech select, scripts ----------------

    /** Timeout for tag answers, ms. Applied to every transceive session. */
    var nfcTimeoutMs: Int = 1500
        private set

    fun setNfcTimeout(ms: Int) {
        nfcTimeoutMs = ms.coerceIn(100, 10000)
        uiTick++
    }

    /**
     * One connected tag session: connect once, transceive many times, then close.
     * Gives scripts session continuity instead of one connect per command.
     */
    private inner class RawSession(
        val techName: String,
        private val doConnect: () -> Unit,
        private val doTransceive: (ByteArray) -> ByteArray,
        private val doClose: () -> Unit,
        private val doSetTimeout: ((Int) -> Unit)? = null
    ) {
        fun connect() {
            doConnect()
            doSetTimeout?.let { runCatching { it(nfcTimeoutMs) } }
        }
        fun transceive(bytes: ByteArray): ByteArray = doTransceive(bytes)
        fun close() {
            runCatching { doClose() }
        }
    }

    /**
     * Open a raw session on the requested tech. techName: "AUTO", "IsoDep",
     * "NfcA", "NfcB", "NfcF", "NfcV". AUTO picks IsoDep first, then A/B/F/V.
     * Returns null when the tag doesn't expose that tech. Caller must close().
     */
    private fun openRawSession(tag: Tag, techName: String): RawSession? {
        val want = techName.uppercase()
        fun iso(): RawSession? {
            val t = IsoDep.get(tag) ?: return null
            return RawSession("IsoDep", { t.connect() }, { t.transceive(it) }, { t.close() }, { t.setTimeout(it) })
        }
        fun a(): RawSession? {
            val t = NfcA.get(tag) ?: return null
            return RawSession("NfcA", { t.connect() }, { t.transceive(it) }, { t.close() }, { t.setTimeout(it) })
        }
        fun b(): RawSession? {
            val t = NfcB.get(tag) ?: return null
            // NfcB exposes no setTimeout — default timing stands.
            return RawSession("NfcB", { t.connect() }, { t.transceive(it) }, { t.close() })
        }
        fun f(): RawSession? {
            val t = NfcF.get(tag) ?: return null
            // NfcF exposes no setTimeout — default timing stands.
            return RawSession("NfcF", { t.connect() }, { t.transceive(it) }, { t.close() })
        }
        fun v(): RawSession? {
            val t = NfcV.get(tag) ?: return null
            // NfcV exposes no setTimeout — default timing stands.
            return RawSession("NfcV", { t.connect() }, { t.transceive(it) }, { t.close() })
        }
        return when (want) {
            "ISODEP", "ISO-DEP", "ISO_DEP" -> iso()
            "NFCA", "NFC-A" -> a()
            "NFCB", "NFC-B" -> b()
            "NFCF", "NFC-F", "FELICA" -> f()
            "NFCV", "NFC-V", "15693", "ISO15693" -> v()
            else -> iso() ?: a() ?: b() ?: f() ?: v()
        }
    }

    /**
     * Send raw bytes straight to the tapped tag on the chosen tech and get the
     * raw answer back. No interpretation, no filtering — the tag's own chip
     * decides what answers.
     */
    fun nfcRawTransceiveTech(hex: String, techName: String): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        val bytes = hex.hexToBytes()
            ?: return "Couldn't read that as hex — type pairs like 30 00 or 3000."
        if (bytes.isEmpty()) return "Type what to send first (hex, e.g. 30 00)."
        if (bytes.size > 253) return "Too long — keep it under 253 bytes."
        val session = openRawSession(tag, techName)
            ?: return "This tag doesn't offer $techName — try AUTO or another type."
        return try {
            session.connect()
            val resp = session.transceive(bytes)
            val line = "Sent [${session.techName}] ${bytes.toHexString()}\nAnswer ${resp.toHexString()}"
            rawConsoleHistory = (listOf(line) + rawConsoleHistory).take(50)
            uiTick++
            "Sent ${bytes.size} bytes using ${session.techName}. Answer (${resp.size} bytes): ${resp.toHexString()}"
        } catch (e: Exception) {
            val msg = "The tag didn't answer: ${e.message ?: e.javaClass.simpleName}. Tap it again and retry."
            rawConsoleHistory = (listOf("Sent [${session.techName}] ${bytes.toHexString()}\nNo answer — $msg") + rawConsoleHistory).take(50)
            uiTick++
            msg
        } finally {
            session.close()
        }
    }

    /**
     * Run a multi-line hex script against the tag in ONE connected session.
     * One command per line; blank lines and # comments skipped. The session
     * stays open between commands; the full TX/RX trail goes to history.
     * Stops at the first command the tag won't answer.
     */
    fun nfcRunScript(script: String, techName: String): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        val cmds = try {
            parseHexScript(script)
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Couldn't parse the script."
        }
        if (cmds.isEmpty()) return "Script is empty — one hex command per line."
        if (cmds.size > 64) return "Too many commands — keep scripts to 64 lines."
        val session = openRawSession(tag, techName)
            ?: return "This tag doesn't offer $techName — try AUTO or another type."
        val trail = StringBuilder()
        var okCount = 0
        var failAt = -1
        try {
            session.connect()
            trail.appendLine("== connected [${session.techName}] — waiting up to ${nfcTimeoutMs}ms for an answer ==")
            for ((i, cmd) in cmds.withIndex()) {
                val resp = try {
                    session.transceive(cmd)
                } catch (e: Exception) {
                    trail.appendLine("Sent ${i + 1}: ${cmd.toHexString()}")
                    trail.appendLine("Answer ${i + 1}: no answer — ${e.message ?: e.javaClass.simpleName}")
                    failAt = i + 1
                    break
                }
                trail.appendLine("Sent ${i + 1}: ${cmd.toHexString()}")
                trail.appendLine("Answer ${i + 1}: ${resp.toHexString()}")
                okCount++
            }
        } catch (e: Exception) {
            trail.appendLine("Connection error — ${e.message ?: e.javaClass.simpleName}")
        } finally {
            session.close()
            trail.appendLine("== disconnected ==")
        }
        rawConsoleHistory = (listOf(trail.toString().trim()) + rawConsoleHistory).take(50)
        uiTick++
        return buildString {
            append("$okCount/${cmds.size} commands got an answer")
            if (failAt > 0) append(" — stopped at command $failAt")
            append(". Full details are in the console history below.")
        }
    }

    // ---------------- NFC TAP-RUN: saved scripts that fire on tag tap ----------------

    /**
     * Saved raw-console scripts: (name, script text, tech). In-memory for this
     * session. TAP-RUN arms one of them; every tag tap then runs it
     * automatically through the normal script runner.
     */
    var nfcSavedScripts by mutableStateOf<List<Triple<String, String, String>>>(emptyList())
        private set

    /** Name of the armed TAP-RUN script, or null when disarmed. */
    var tapRunArmed by mutableStateOf<String?>(null)
        private set

    fun saveNfcScript(name: String, script: String, tech: String): String {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return "Give the script a name first."
        if (script.isBlank()) return "The script box is empty — nothing to save."
        nfcSavedScripts = (nfcSavedScripts.filterNot { it.first == clean } +
            Triple(clean, script, tech)).take(20)
        uiTick++
        return "Saved \"$clean\" ($tech) — turn it on below, then tap a card to run it."
    }

    fun deleteNfcScript(name: String) {
        if (tapRunArmed == name) tapRunArmed = null
        nfcSavedScripts = nfcSavedScripts.filterNot { it.first == name }
        uiTick++
    }

    /** Arming the already-armed script disarms it. */
    fun armTapRun(name: String) {
        tapRunArmed = if (tapRunArmed == name) null else name
        uiTick++
    }

    fun disarmTapRun() {
        tapRunArmed = null
        uiTick++
    }

    // ---------------- tag memory write + auth operations ----------------

    /**
     * Write 4 bytes to one Ultralight/NTAG page, then read back to verify.
     * For your own tags — locked pages refuse and the error is reported honestly.
     */
    fun nfcWriteUltralightPage(page: Int, hex: String): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        val mu = MifareUltralight.get(tag) ?: return "Not a tag with readable pages."
        val data = hex.hexToBytes()
            ?: return "Couldn't read that as hex — need exactly 4 bytes, e.g. DE AD BE EF."
        if (data.size != 4) return "A page is exactly 4 bytes — you gave ${data.size}."
        if (page < 0 || page > 255) return "Page number must be 0 to 255."
        return try {
            mu.connect()
            try {
                mu.writePage(page, data)
                val back = runCatching { mu.readPages(page) }.getOrNull()
                val ok = back != null && back.size >= 4 && back.copyOfRange(0, 4).contentEquals(data)
                log("NFC", null, "Ultralight page $page write ${if (ok) "verified" else "unverified"}")
                uiTick++
                if (ok) "Page $page written and verified: ${data.toHexString()}"
                else "Sent to page $page, but reading it back didn't match — not confirmed."
            } finally {
                runCatching { mu.close() }
            }
        } catch (e: Exception) {
            "Write failed: ${e.message ?: e.javaClass.simpleName}. The page may be locked."
        }
    }

    /**
     * Write 16 bytes to one MIFARE Classic block. Only works on sectors the
     * key probe opened (authenticates with the found Key A). For your own or
     * test cards. Sector trailers hold keys — writing one can lock the
     * sector, and the result says so when it happens.
     */
    fun nfcWriteClassicBlock(block: Int, hex: String): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        val mfc = MifareClassic.get(tag) ?: return "Not a MIFARE Classic card."
        val data = hex.hexToBytes()
            ?: return "Couldn't read that as hex — need exactly 16 bytes."
        if (data.size != 16) return "A block is exactly 16 bytes — you gave ${data.size}."
        val openSectors = mifareKeyResults.filterValues { it != null }
        if (openSectors.isEmpty()) return "No open sectors — tap TRY COMMON KEYS first."
        return try {
            mfc.connect()
            try {
                val sector = runCatching { mfc.blockToSector(block) }.getOrElse {
                    return "Block $block isn't valid on this card."
                }
                val keyHex = openSectors[sector]
                    ?: return "Sector $sector is locked — tap TRY COMMON KEYS first."
                if (!runCatching { mfc.authenticateSectorWithKeyA(sector, hexToKeyBytes(keyHex)) }.getOrDefault(false))
                    return "Lost access to sector $sector — tap TRY COMMON KEYS again."
                val isTrailer = runCatching {
                    block == mfc.sectorToBlock(sector) + mfc.getBlockCountInSector(sector) - 1
                }.getOrDefault(false)
                runCatching { mfc.writeBlock(block, data) }.getOrElse {
                    return "Write failed: ${it.message ?: it.javaClass.simpleName}"
                }
                val back = runCatching { mfc.readBlock(block) }.getOrNull()
                val ok = back != null && back.contentEquals(data)
                log("NFC", null, "Classic block $block write ${if (ok) "verified" else "unverified"}")
                uiTick++
                buildString {
                    append(if (ok) "Block $block written and verified." else "Sent to block $block, but reading it back didn't match.")
                    if (isTrailer) append(" That was the sector's key area — access to this sector may have changed.")
                }
            } finally {
                runCatching { mfc.close() }
            }
        } catch (e: Exception) {
            "Write failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Read ISO 15693 (NfcV) blocks via READ SINGLE BLOCK. Best-effort —
     * reports raw answers honestly, including tag error codes.
     */
    fun nfcReadNfcVBlocks(first: Int, count: Int): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        if (NfcV.get(tag) == null) return "Not an ISO 15693 tag."
        if (first < 0 || count < 1 || count > 64) return "Ask for 1 to 64 blocks, starting at 0 or higher."
        val uid = tag.id
        if (uid.size != 8) return "Unexpected tag ID length (${uid.size}) — can't talk to this tag."
        val session = openRawSession(tag, "NfcV") ?: return "Couldn't open a connection to this tag."
        val out = StringBuilder()
        try {
            session.connect()
            for (b in first until first + count) {
                val cmd = buildNfcVReadSingleBlock(uid, b)
                val resp = try {
                    session.transceive(cmd)
                } catch (e: Exception) {
                    out.appendLine("Block $b: no answer (${e.message ?: e.javaClass.simpleName})")
                    break
                }
                val (ok, payload) = parseNfcVResponse(resp)
                if (ok) out.appendLine("Block $b: ${payload.toHexString()}")
                else out.appendLine("Block $b: tag error ${payload.toHexString()}")
            }
        } catch (e: Exception) {
            out.appendLine("Connection error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            session.close()
        }
        val result = out.toString().trim().ifBlank { "No blocks read." }
        rawConsoleHistory = (listOf("Tag read $first..${first + count - 1}\n$result") + rawConsoleHistory).take(50)
        uiTick++
        return result
    }

    /**
     * NTAG password authentication (PWD_AUTH 0x1B). For your own tags — you
     * supply the 4-byte password in hex. A 2-byte PACK answer = accepted.
     */
    fun nfcNtagPwdAuth(hexPassword: String): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        if (NfcA.get(tag) == null) return "Needs an NFC-A tag (NTAG type)."
        val pwd = hexPassword.hexToBytes()
            ?: return "Couldn't read that as hex — need 4 bytes, e.g. FF FF FF FF."
        if (pwd.size != 4) return "Password must be exactly 4 bytes."
        val session = openRawSession(tag, "NfcA") ?: return "Couldn't open a connection to this tag."
        return try {
            session.connect()
            val resp = session.transceive(buildNtagPwdAuth(pwd))
            val hex = resp.toHexString()
            log("NFC", null, "PWD_AUTH -> $hex")
            uiTick++
            if (resp.size == 2) "Password accepted — reply code: $hex"
            else "Unexpected answer (${resp.size} bytes): $hex — password likely wrong."
        } catch (e: Exception) {
            "No answer: ${e.message ?: e.javaClass.simpleName}. Wrong password or not a password-protected tag."
        } finally {
            session.close()
        }
    }

    /**
     * Gen1a "magic" card backdoor: unlock (50 00 57 CD), then write block 0.
     * ONLY works on gen1a magic cards you own — normal cards ignore the
     * backdoor and the failure is reported honestly. Block 0 holds the UID.
     */
    fun nfcMagicWriteBlock0(hex16: String): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        if (NfcA.get(tag) == null) return "Needs an NFC-A tag."
        val data = hex16.hexToBytes()
            ?: return "Couldn't read that as hex — need 16 bytes for block 0."
        if (data.size != 16) return "Block 0 is 16 bytes — you gave ${data.size}."
        val session = openRawSession(tag, "NfcA") ?: return "Couldn't open a connection to this tag."
        return try {
            session.connect()
            val unlock = session.transceive(buildMagicUnlock())
            if (!isMagicAck(unlock)) {
                return "No answer from the card's backdoor (${unlock.toHexString()}) — this isn't a magic card. Nothing written."
            }
            val ack1 = session.transceive(buildMagicWriteBlock0Prefix())
            if (!isMagicAck(ack1)) return "Backdoor open, but the card refused the write start (${ack1.toHexString()}). Nothing written."
            val ack2 = session.transceive(data)
            if (!isMagicAck(ack2)) return "The card refused the data (${ack2.toHexString()}). Block 0 may be unchanged."
            log("NFC", null, "Magic block 0 written")
            uiTick++
            "Block 0 written: ${data.toHexString()}"
        } catch (e: Exception) {
            "Failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            session.close()
        }
    }

    /** Write text, then read back and confirm it landed. Returns the full story. */
    fun nfcWriteTextVerified(text: String): String {
        if (text.isBlank()) return "Type some text first."
        if (!writeNdefText(text)) return "Couldn't write — the tag isn't nearby, or you can't write to it."
        val back = readNdefSummary(lastNfcTag ?: return "Sent, but the tag moved away before we could check it.")
        return if (back.contains(text)) "Written AND verified — read-back shows your text."
        else "Write sent, but read-back doesn't show the text:\n$back"
    }

    /** Write URL, then read back and confirm it landed. Returns the full story. */
    fun nfcWriteUrlVerified(url: String): String {
        val clean = url.trim()
        if (clean.isBlank()) return "Type a link first."
        if (!nfcWriteUrl(clean)) return "Couldn't write — the tag isn't nearby, or you can't write to it."
        val back = readNdefSummary(lastNfcTag ?: return "Sent, but the tag moved away before we could check it.")
        val full = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "https://$clean"
        return if (back.contains(full)) "Written AND verified — read-back shows your link."
        else "Write sent, but read-back doesn't show the link:\n$back"
    }

    /** Plain-English summary of every NDEF record on the tag. */
    private fun readNdefSummary(tag: Tag): String {
        val ndef = Ndef.get(tag) ?: return ""
        return runCatching {
            ndef.connect()
            val message = ndef.ndefMessage
            val out = message?.records?.mapIndexed { i, r -> "Record ${i + 1}: ${describeNdefRecord(r)}" }
                ?.joinToString("\n") ?: ""
            out
        }.getOrDefault("").also {
            runCatching { ndef.close() }
        }
    }

    /** Human-readable one-liner for a single NDEF record. */
    private fun describeNdefRecord(record: NdefRecord): String {
        val tnf: Short = record.tnf
        val type = String(record.type, StandardCharsets.US_ASCII)
        val payload = record.payload
        return when {
            tnf == NdefRecord.TNF_WELL_KNOWN && type == "T" && payload.isNotEmpty() -> {
                val langLen = payload[0].toInt() and 0x3F
                val text = if (payload.size > 1 + langLen) {
                    String(payload, 1 + langLen, payload.size - 1 - langLen, StandardCharsets.UTF_8)
                } else ""
                "Text: \"$text\""
            }
            tnf == NdefRecord.TNF_WELL_KNOWN && type == "U" && payload.isNotEmpty() -> {
                val prefix = uriPrefix(payload[0].toInt() and 0xFF)
                val rest = if (payload.size > 1) String(payload, 1, payload.size - 1, StandardCharsets.UTF_8) else ""
                "URL: $prefix$rest"
            }
            tnf == NdefRecord.TNF_ABSOLUTE_URI -> "Link: ${String(payload, StandardCharsets.UTF_8)}"
            tnf == NdefRecord.TNF_MIME_MEDIA -> "File type ${String(record.type, StandardCharsets.US_ASCII)} (${payload.size} bytes)"
            else -> "Other type: $type (${payload.size} bytes)"
        }
    }

    private fun uriPrefix(code: Int): String = when (code) {
        0x01 -> "http://www."
        0x02 -> "https://www."
        0x03 -> "http://"
        0x04 -> "https://"
        0x05 -> "tel:"
        0x06 -> "mailto:"
        else -> ""
    }

    // ---------------- NFC operations (v1.7 multitool) ----------------

    fun nfcFactsFor(targetId: String): NfcFacts? = nfcFacts[targetId]

    fun nfcOpsFor(targetId: String): List<String> =
        nfcFacts[targetId]?.let { nfcSupportedOps(it) } ?: emptyList()

    /** Re-read the tag and refresh its target entry. Returns a status line. */
    fun nfcReadFull(targetId: String): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        val summary = readNdefSummary(tag)
        val facts = nfcFacts[targetId]
        log("NFC", targetId, "Read: ${if (summary.isBlank()) "no NDEF records" else "NDEF found"}")
        uiTick++
        return when {
            summary.isNotBlank() -> summary
            facts?.hasNdef == true -> "Tag is set up for tap-to-read but has nothing saved on it."
            facts?.isIsoDep == true -> "Smart card — no tap-to-read data on it. Use the Card commands panel below."
            else -> "No readable tap data on this tag."
        }
    }

    /**
     * Package 4 (B-027/B-028, D-32–D-34, D-36) — "read this card":
     * the deep read is picked from the card type itself, so the user
     * never picks which read to run. Ultralight/NTAG cards get the
     * full memory dump (with a plain-words count of the pages read,
     * so the wall of hex has a summary); long-range (NfcV) cards get
     * their blocks read; everything else gets the plain re-read.
     * The existing readers run unchanged underneath.
     */
    fun nfcReadCardGuided(): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone and try again."
        return when {
            MifareUltralight.get(tag) != null -> {
                val dump = nfcDumpMemory()
                val pages = dump.lines().count { it.startsWith("Page ") }
                if (pages > 0) "Read $pages page(s) off this card.\n$dump" else dump
            }
            NfcV.get(tag) != null -> nfcReadNfcVBlocks(0, 16)
            else -> nfcReadFull("nfc:${tag.id.toHexString()}")
        }
    }

    /**
     * Package 4 (B-030, D-38, D-32's `ndefMaxSize`) — capacity check
     * before writing. Returns null when the words/link fit on the
     * card in range, or when the card's capacity is unknown (the
     * write itself then decides honestly). Otherwise the plain-words
     * "too big for this card" reason, with both numbers.
     */
    fun nfcFitsOnCard(text: String, isUrl: Boolean): String? {
        val tag = lastNfcTag ?: return "Hold a card against the phone first."
        val facts = nfcFacts["nfc:${tag.id.toHexString()}"]
        return ndefFitsOnCard(facts, text, isUrl)
    }

    /** Shared write path: NDEF when writable, NdefFormatable as a fallback. Honest about failures. */
    private fun writeNdefMessage(msg: NdefMessage, label: String): Boolean {
        val tag = lastNfcTag ?: return false
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return runCatching {
                ndef.connect()
                if (!ndef.isWritable) {
                    log("NFC", null, "AUTH REQUIRED / tag not writable")
                    return@runCatching false
                }
                if (msg.toByteArray().size > ndef.maxSize) {
                    log("NFC", null, "$label too large for tag (${ndef.maxSize} bytes)")
                    return@runCatching false
                }
                ndef.writeNdefMessage(msg)
                log("NFC", null, "$label success")
                true
            }.getOrDefault(false).also { runCatching { ndef.close() } }
        }
        val formatable = NdefFormatable.get(tag)
        return if (formatable != null) {
            runCatching {
                formatable.connect()
                formatable.format(msg)
                log("NFC", null, "$label via format success")
                true
            }.getOrDefault(false).also { runCatching { formatable.close() } }
        } else {
            log("NFC", null, "$label failed: tag exposes no writable NDEF")
            false
        }
    }

    fun writeNdefText(text: String): Boolean =
        writeNdefMessage(NdefMessage(arrayOf(NdefRecord.createTextRecord("en", text))), "NDEF text write")

    fun nfcWriteUrl(url: String): Boolean {
        val clean = url.trim()
        if (clean.isBlank()) return false
        val full = if (clean.startsWith("http://") || clean.startsWith("https://")) clean else "https://$clean"
        return writeNdefMessage(NdefMessage(arrayOf(NdefRecord.createUri(full))), "NDEF URL write")
    }

    /** Copy the tag's NDEF records into memory so they can be written to another tag. */
    fun nfcCopyContent(): String {
        val tag = lastNfcTag ?: return "No tag nearby — hold the tag to the phone first."
        val ndef = Ndef.get(tag) ?: return "Nothing saved on this tag to copy."
        return runCatching {
            ndef.connect()
            val msg = ndef.ndefMessage
            if (msg == null || msg.records.isEmpty()) {
                "Nothing saved on this tag to copy."
            } else {
                savedNdef = msg
                savedNdefSummary = msg.records.mapIndexed { i, r -> "Record ${i + 1}: ${describeNdefRecord(r)}" }
                    .joinToString("\n")
                log("NFC", null, "Copied ${msg.records.size} NDEF record(s)")
                "Saved ${msg.records.size} item(s). Hold another tag you can write to, then use WRITE SAVED."
            }
        }.getOrDefault("Couldn't read the tag — hold it to the phone again and retry.")
            .also { runCatching { ndef.close() } }
    }

    fun hasSavedNdef(): Boolean = savedNdef != null

    fun savedNdefSummary(): String = savedNdefSummary

    /** Write the previously copied NDEF records to the tag currently in range. */
    fun nfcWriteSaved(): Boolean {
        val msg = savedNdef ?: return false
        return writeNdefMessage(msg, "Saved NDEF write")
    }

    /** Erase = write an empty NDEF message. Only on tags that report writable. */
    fun nfcErase(): Boolean =
        writeNdefMessage(NdefMessage(arrayOf()), "NDEF erase")

    /** Initialize a blank (NdefFormatable) tag for NDEF. */
    fun nfcFormat(): Boolean {
        val tag = lastNfcTag ?: return false
        val formatable = NdefFormatable.get(tag) ?: return false
        return runCatching {
            formatable.connect()
            formatable.format(NdefMessage(arrayOf()))
            log("NFC", null, "NDEF format success")
            true
        }.getOrDefault(false).also { runCatching { formatable.close() } }
    }

    // ---------------- NFC card emulation (HCE) ----------------

    /** What the emulated tag currently broadcasts, in plain English. */
    fun emulatedSummary(): String = NfcEmulationService.emulatedSummary

    fun setEmulatedText(text: String) {
        NfcEmulationService.setEmulatedText(text)
        uiTick++
    }

    fun setEmulatedUrl(url: String) {
        NfcEmulationService.setEmulatedUrl(url)
        uiTick++
    }

    /**
     * Turn tag emulation on/off. The phone then pretends to be an NFC tag
     * holding your text/URL — tap it to another phone or a reader.
     * Emulates YOUR OWN content only; it cannot copy payment cards.
     */
    fun setEmulationEnabled(on: Boolean) {
        val pm = getApplication<Application>().packageManager
        val component = ComponentName(getApplication(), NfcEmulationService::class.java)
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            isEmulating = on
            log("NFC", null, if (on) "Emulation ON — tap this phone to a reader" else "Emulation OFF")
        }.onFailure {
            log("NFC", null, "Emulation switch failed: ${it.message}")
        }
        uiTick++
    }

    /**
     * Package 4 (B-043, D-40) — "pretend to be the card I just read":
     * the NEW path the panel could never do before. Copies the last
     * tapped card's saved words or link into the emulated tag, copying
     * first when nothing is saved yet, then turns pretending on
     * through the existing runtime switch. Only the card's own words
     * or link are broadcast — never a bank card, never authentication.
     * Returns the plain-words outcome; DONE only when pretending is
     * actually on afterwards.
     */
    fun nfcEmulateLastReadCard(): String {
        if (lastNfcTag == null) return "Tap a card first — hold a card against the phone."
        if (!hasSavedNdef()) {
            val copied = nfcCopyContent()
            if (!hasSavedNdef()) return copied
        }
        val content = emulatableFromSummary(savedNdefSummary())
            ?: return "Nothing on this card to pretend to be — it has no words or link saved on it."
        when (content.first) {
            "url" -> setEmulatedUrl(content.second)
            else -> setEmulatedText(content.second)
        }
        setEmulationEnabled(true)
        return if (isEmulating) {
            "Pretending to be the card you just read — broadcasting ${content.first}: ${content.second}. " +
                "Hold this phone to a reader."
        } else {
            "Couldn't turn pretending on — the phone refused the switch. Try the Start pretending button."
        }
    }

    /** Package 4 (B-043) — how many readers have tapped the pretending phone. */
    fun emulationReaderTaps(): Int = NfcEmulationService.readerTapCount

    // ---------------- MIFARE Classic key check (Flipper-style) ----------------

    /** Well-known factory-default MIFARE Classic keys. Public knowledge, in every NFC tool. */
    private val mifareDefaultKeys = listOf(
        "FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "000000000000",
        "A0B0C0D0E0F0", "A1A2A3A4A5A6", "B0B1B2B3B4B5", "B2B3B4B5B6B7",
        "4D3A99C351DD", "1A982C1E5CF", "2A2C13CC242A", "AABBU..." .let { "AABBCCDDEEFF" },
        "714025094425", "4E4552544F4E"
    )

    /** Per-sector result: sector index -> working Key A hex, or null if locked. */
    var mifareKeyResults by mutableStateOf<Map<Int, String?>>(emptyMap())
        private set

    var mifareStatus by mutableStateOf("No MIFARE Classic card checked yet.")
        private set

    var mifareBusy by mutableStateOf(false)
        private set

    private fun hexToKeyBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /**
     * Try the default key list against every sector of the tapped MIFARE
     * Classic card. Read-only probing — for your own or test cards.
     * Cards with changed keys stay locked and are reported honestly.
     */
    fun mifareCheckKeys() {
        val tag = lastNfcTag
        val mfc = tag?.let { MifareClassic.get(it) }
        if (mfc == null) {
            mifareStatus = "Not a MIFARE Classic card — hold one to the phone first."
            return
        }
        if (mifareBusy) return
        mifareBusy = true
        mifareStatus = "Trying ${mifareDefaultKeys.size} common keys on ${mfc.sectorCount} sectors…"
        viewModelScope.launch(Dispatchers.IO) {
            val results = mutableMapOf<Int, String?>()
            try {
                mfc.connect()
                for (sector in 0 until mfc.sectorCount) {
                    var found: String? = null
                    for (keyHex in mifareDefaultKeys) {
                        if (runCatching { mfc.authenticateSectorWithKeyA(sector, hexToKeyBytes(keyHex)) }.getOrDefault(false)) {
                            found = keyHex
                            break
                        }
                    }
                    results[sector] = found
                }
            } catch (e: Exception) {
                // keep partial results
            } finally {
                runCatching { mfc.close() }
            }
            val open = results.count { it.value != null }
            withContext(Dispatchers.Main) {
                mifareKeyResults = results
                mifareStatus = buildString {
                    appendLine("Checked ${results.size} sectors with ${mifareDefaultKeys.size} common keys.")
                    appendLine("$open opened, ${results.size - open} stayed locked.")
                    results.forEach { (sector, key) ->
                        appendLine("Sector $sector: " + (key?.let { "open (key $it)" } ?: "locked"))
                    }
                    if (open > 0) append("Next: tap READ OPEN SECTORS to see the data.")
                    else append("Next: this card doesn't use the usual keys — this check can't do any more.")
                }.trim()
                mifareBusy = false
                log("NFC", null, "MIFARE key check: $open/${results.size} sectors opened")
                uiTick++
            }
        }
    }

    /**
     * Read the data blocks of every sector opened by the key check.
     * Shows raw hex — for your own or test cards.
     */
    fun mifareReadSectors() {
        val tag = lastNfcTag
        val mfc = tag?.let { MifareClassic.get(it) }
        if (mfc == null) {
            mifareStatus = "Not a MIFARE Classic card — hold one to the phone first."
            return
        }
        val openSectors = mifareKeyResults.filterValues { it != null }
        if (openSectors.isEmpty()) {
            mifareStatus = "No open sectors — tap TRY COMMON KEYS first."
            return
        }
        if (mifareBusy) return
        mifareBusy = true
        mifareStatus = "Reading the open sectors (${openSectors.size})…"
        viewModelScope.launch(Dispatchers.IO) {
            val out = StringBuilder()
            try {
                mfc.connect()
                for ((sector, keyHex) in openSectors) {
                    val key = hexToKeyBytes(keyHex!!)
                    if (!runCatching { mfc.authenticateSectorWithKeyA(sector, key) }.getOrDefault(false)) {
                        out.appendLine("Sector $sector: lost access, skipped.")
                        continue
                    }
                    out.appendLine("--- Sector $sector ---")
                    val firstBlock = mfc.sectorToBlock(sector)
                    for (b in 0 until mfc.getBlockCountInSector(sector)) {
                        val data = runCatching { mfc.readBlock(firstBlock + b) }.getOrNull()
                        out.appendLine("Block ${firstBlock + b}: " + (data?.toHexString() ?: "read failed"))
                    }
                }
            } catch (e: Exception) {
                out.appendLine("Reading stopped: ${e.message}")
            } finally {
                runCatching { mfc.close() }
            }
            withContext(Dispatchers.Main) {
                mifareStatus = out.toString().trim().ifBlank { "Nothing read." }
                mifareBusy = false
                log("NFC", null, "MIFARE sector dump done")
                uiTick++
            }
        }
    }

    /**
     * Package 4 (B-041, D-45) — "try the usual passwords, then read
     * what opened" as ONE flow: the existing key check runs, this
     * waits (bounded, 60 s) for it to finish, then the existing open-
     * sector read runs on what opened. The user never picks a key or
     * a sector by hand. The result lands in `mifareStatus`, as before.
     */
    fun mifareTryAndRead() {
        if (mifareBusy) return
        val tag = lastNfcTag
        if (tag == null || MifareClassic.get(tag) == null) {
            mifareStatus = "Not a MIFARE Classic card — hold one to the phone first."
            return
        }
        mifareCheckKeys()
        viewModelScope.launch(Dispatchers.IO) {
            var waitedMs = 0
            while (mifareBusy && waitedMs < 60000) {
                delay(500)
                waitedMs += 500
            }
            withContext(Dispatchers.Main) {
                if (mifareKeyResults.filterValues { it != null }.isEmpty()) {
                    mifareStatus = "Checked every section — none opened with the usual passwords. " +
                        "This card doesn't use the factory passwords, so there's nothing more to read here."
                    log("NFC", null, "MIFARE try-and-read: 0 sectors opened")
                } else {
                    mifareReadSectors()
                }
                uiTick++
            }
        }
    }

    // ---------------- IsoDep APDU console ----------------

    fun isoDepHistoryFor(targetId: String): List<ApduResult> =
        apduHistory[targetId]?.toList() ?: emptyList()

    fun isoDepDescribeCommand(hex: String): List<String> = describeApduCommand(hex)

    /**
     * Transmit one APDU to the IsoDep target in range. For user-owned/test
     * cards only — never used to defeat authentication; cards that require
     * auth answer 69 82 and Ghost Tap reports it plainly.
     */
    fun isoDepTransceive(targetId: String, apduHex: String): ApduResult? {
        val tag = lastNfcTag ?: return null
        val iso = IsoDep.get(tag) ?: return null
        val command = apduHex.hexToBytes() ?: return null
        if (command.size < 4) return null
        val result = runCatching {
            iso.connect()
            val resp = iso.transceive(command)
            val cmdHex = command.toHexString()
            if (resp.size < 2) {
                ApduResult(
                    commandHex = cmdHex,
                    responseHex = resp.toHexString(), dataHex = "",
                    sw1 = -1, sw2 = -1, statusHex = "?",
                    statusMeaning = "The card's answer was too short to read.",
                    ascii = bytesToAsciiOrNull(resp)
                )
            } else {
                val sw1 = resp[resp.size - 2].toInt() and 0xFF
                val sw2 = resp[resp.size - 1].toInt() and 0xFF
                val data = resp.copyOfRange(0, resp.size - 2)
                ApduResult(
                    commandHex = cmdHex,
                    responseHex = resp.toHexString(),
                    dataHex = data.toHexString(),
                    sw1 = sw1, sw2 = sw2,
                    statusHex = "%02X %02X".format(sw1, sw2),
                    statusMeaning = decodeApduStatus(sw1, sw2),
                    ascii = bytesToAsciiOrNull(data)
                )
            }
        }.getOrElse {
            ApduResult(
                commandHex = command.toHexString(),
                responseHex = "", dataHex = "", sw1 = -1, sw2 = -1, statusHex = "—",
                statusMeaning = "No answer — the card moved away or refused to connect. Hold it to the phone again.",
                ascii = null
            )
        }
        runCatching { iso.close() }
        val hist = apduHistory.getOrPut(targetId) { mutableListOf() }
        hist += result
        while (hist.size > 50) hist.removeAt(0)
        log("NFC", targetId, "APDU ${command.toHexString()} -> ${result.statusHex}")
        uiTick++
        return result
    }

    /** Convenience: SELECT an application by its AID hex. */
    fun isoDepSelectAid(targetId: String, aidHex: String): ApduResult? {
        val apdu = buildSelectAid(aidHex) ?: return null
        return isoDepTransceive(targetId, apdu)
    }

    /**
     * One-tap connect straight to a Bluetooth address — no scan needed.
     * Creates an honest UNKNOWN target (no name, no classification — the
     * no-fabrication rule holds) and runs the normal connect path with its
     * 15-second watchdog. Returns the target id, or null if the address was
     * invalid (and says so in the log).
     */
    fun connectBleByAddress(address: String): String? {
        val clean = address.trim().uppercase()
        if (!Regex("""^([0-9A-F]{2}:){5}[0-9A-F]{2}$""").matches(clean)) {
            log("BLE", "visual", "Not a valid Bluetooth address: '$address' — not connecting.")
            return null
        }
        val id = "visual-$clean"
        if (targets[id] == null) {
            upsertTarget(
                ScanTarget(
                    id = id,
                    name = clean,
                    type = TargetType.BLE,
                    identity = mapOf("address" to clean, "source" to "picture"),
                    likely = null, // stays UNKNOWN — an address alone is not evidence
                    confidence = "Low",
                    evidence = listOf("Address read from a picture — connecting to see if it answers.")
                )
            )
            log("BLE", id, "Target added from a picture: $clean")
        }
        connectBle(id)
        return id
    }

    /**
     * Package 3 (B-010): connect straight to an address, with a
     * plain-words answer instead of a silent null when the address is
     * mistyped. The address itself is validated exactly like
     * [connectBleByAddress] validates it; this wrapper only reports the
     * failure in words.
     */
    fun connectBleByAddressChecked(address: String): String {
        val clean = address.trim().uppercase()
        if (!Regex("""^([0-9A-F]{2}:){5}[0-9A-F]{2}$""").matches(clean)) {
            log("BLE", null, "Bad address for direct connect: $address")
            return "That doesn't look like a device address — it should look like AA:BB:CC:DD:EE:FF."
        }
        val id = connectBleByAddress(clean)
            ?: return "Couldn't start connecting to $clean."
        return "Connecting to $clean — watch the status line."
    }

    fun connectBle(targetId: String) {
        val target = targets[targetId] ?: return
        val address = target.identity["address"] ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setGattStatus(targetId, "Permission needed — allow Bluetooth in Settings, then try again.")
            log("BLE", targetId, "AUTH REQUIRED: BLUETOOTH_CONNECT permission")
            return
        }
        if (gattMap.containsKey(targetId)) {
            // Already have a live GATT handle for this target.
            log("BLE", targetId, "Already connected/connecting — ignoring duplicate CONNECT")
            return
        }
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            setGattStatus(targetId, "This phone has no Bluetooth — can't connect.")
            return
        }
        setGattStatus(targetId, "CONNECTING…")
        log("BLE", targetId, "Connecting to $address")
        // 15-second watchdog: a device that never answers must not hang the UI forever.
        connectTimeoutJobs[targetId]?.cancel()
        connectTimeoutJobs[targetId] = viewModelScope.launch(Dispatchers.IO) {
            delay(15000)
            val still = gattStatus[targetId]
            if (still != null && still.startsWith("CONNECTING")) {
                runCatching { gattMap[targetId]?.close() }
                gattMap.remove(targetId)
                setGattStatus(
                    targetId,
                    "No answer — the device didn't pick up. That's normal: many devices " +
                        "refuse connections. Try another one."
                )
                log("BLE", targetId, "Connect timed out after 15s — no answer from device")
            }
            connectTimeoutJobs.remove(targetId)
        }
        val gatt = device.connectGatt(appContext, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val connected = newState == BluetoothGatt.STATE_CONNECTED
                if (connected && status == BluetoothGatt.GATT_SUCCESS) {
                    connectTimeoutJobs[targetId]?.cancel()
                    connectTimeoutJobs.remove(targetId)
                    pairingNeeded.remove(targetId)
                    setGattStatus(targetId, "CONNECTED — discovering services…")
                    // Event bus: a real BLE connection actually completed.
                    try {
                        IntelEventBus.publish(
                            IntelEvent.BleConnected(deviceId = targetId, name = target.name)
                        )
                    } catch (_: Exception) {
                    }
                    gatt.discoverServices()
                    // Link upgrade: bigger packets, faster link, on every connect.
                    // Each in its own try/catch — failures are log-only, never UI noise.
                    runCatching { gatt.requestMtu(517) }
                        .onFailure { log("BLE", targetId, "MTU 517 request failed: ${it.message}") }
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                        .onFailure { log("BLE", targetId, "High-priority link request failed: ${it.message}") }
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            gatt.setPreferredPhy(
                                BluetoothDevice.PHY_LE_2M_MASK,
                                BluetoothDevice.PHY_LE_2M_MASK,
                                BluetoothDevice.PHY_OPTION_NO_PREFERRED
                            )
                        }
                    }.onFailure { log("BLE", targetId, "2M PHY request failed: ${it.message}") }
                } else if (connected) {
                    connectTimeoutJobs[targetId]?.cancel()
                    connectTimeoutJobs.remove(targetId)
                    if (status == 5 || status == 15) {
                        // Auth failure: the device wants the phone paired first.
                        pairingNeeded[targetId] = true
                        setGattStatus(
                            targetId,
                            "Needs pairing — this device wants your phone paired first. " +
                                "Follow the pairing steps below."
                        )
                        log("BLE", targetId, "Auth failure (status $status) — pairing needed")
                    } else {
                        setGattStatus(targetId, gattFailMessage(status))
                    }
                    runCatching { gatt.close() }
                    gattMap.remove(targetId)
                } else {
                    connectTimeoutJobs[targetId]?.cancel()
                    connectTimeoutJobs.remove(targetId)
                    val wasConnecting = (gattStatus[targetId] ?: "").startsWith("CONNECTING")
                    val msg = if (wasConnecting && status != BluetoothGatt.GATT_SUCCESS) {
                        // The connect attempt itself failed — say why, plainly.
                        gattFailMessage(status)
                    } else {
                        "Not connected"
                    }
                    setGattStatus(targetId, msg)
                    rawSelection.remove(targetId)
                    runCatching { gatt.close() }
                    gattMap.remove(targetId)
                }
                updateConnectionState(targetId, connected && status == BluetoothGatt.GATT_SUCCESS)
                log("BLE", targetId, "Connection state=$newState status=$status")
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setGattStatus(targetId, "Couldn't list what this device can do — try connecting again.")
                    log("BLE", targetId, "Service discovery failed, status=$status")
                    pubErr("Bluetooth feature list", "Couldn't list what it can do (code $status) — ${targets[targetId]?.name ?: targetId}")
                    return
                }
                bleServices[targetId] = gatt.services
                val discovered = gatt.services.map { service ->
                    DiscoveredService(
                        uuid = service.uuid.toString(),
                        characteristics = service.characteristics.map { c ->
                            DiscoveredCharacteristic(
                                uuid = c.uuid.toString(),
                                properties = decodeGattProperties(c.properties)
                            )
                        }
                    )
                }
                // Only what the real connection returned. No fabrication.
                gattDiscovered[targetId] = discovered
                val charCount = discovered.sumOf { it.characteristics.size }
                setGattStatus(targetId, "CONNECTED — ${discovered.size} feature group(s), $charCount feature(s)")
                val caps = mutableListOf<Capability>()
                caps += Capability("ble_disconnect", "Disconnect", ControlKind.BUTTON)
                gatt.services.forEach { service ->
                    service.characteristics.forEach { c ->
                        val props = c.properties
                        val propNames = decodeGattProperties(props)
                        caps += Capability(
                            id = "ble:${service.uuid}:${c.uuid}",
                            title = "${service.uuid} / ${c.uuid}",
                            kind = ControlKind.RAW,
                            readable = props and BluetoothGattCharacteristic.PROPERTY_READ != 0,
                            writable = (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
                                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0),
                            notifiable = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) ||
                                (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0),
                            metadata = mapOf(
                                "service" to service.uuid.toString(),
                                "char" to c.uuid.toString(),
                                "properties" to propNames.joinToString(",")
                            )
                        )
                    }
                }
                // Re-run identification with the newly discovered GATT services;
                // classification can only improve from here.
                val old = targets[targetId] ?: return
                val merged = (adSignals[targetId] ?: DeviceSignals(
                    name = old.name, address = old.identity["address"], protocol = "BLE"
                )).copy(gattServices = gatt.services.map { it.uuid.toString() })
                adSignals[targetId] = merged
                val idn = DeviceClassifier.identify(merged)
                upsertTarget(
                    old.copy(
                        capabilities = caps,
                        connected = true,
                        lastSeenMs = System.currentTimeMillis(),
                        likely = idn.label,
                        confidence = DeviceClassifier.confidenceName(idn.confidence),
                        evidence = idn.evidence
                    )
                )
                log("BLE", targetId, "Services discovered: ${gatt.services.size} -> ${idn.label} (${DeviceClassifier.confidenceName(idn.confidence)})")
                // Intelligence: the real GATT layout of a real device.
                pubOp(
                    "ble.services", true,
                    "${old.name}: ${discovered.size} feature group(s), $charCount feature(s) — " +
                        "looks like ${idn.label} (${DeviceClassifier.confidenceName(idn.confidence)})."
                )
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == 5 || status == 15) {
                    pairingNeeded[targetId] = true
                    setGattStatus(
                        targetId,
                        "Needs pairing — this device wants your phone paired first. " +
                            "Follow the pairing steps below."
                    )
                    log("BLE", targetId, "READ auth failure (status $status) — pairing needed")
                    // Intelligence: an access gate, never bypassed — recorded honestly.
                    pubErr(
                        "Bluetooth read",
                        "${characteristic.uuid} said no (code $status) — the device wants pairing first."
                    )
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("BLE", targetId, "READ failed (status $status)")
                    pubErr("Bluetooth read", "${characteristic.uuid} didn't answer (code $status)")
                }
                val key = characteristic.uuid.toString()
                val old = targets[targetId] ?: return
                val updatedWatch = old.watchValues + (key to value.toHexString())
                upsertTarget(old.copy(watchValues = updatedWatch, lastSeenMs = System.currentTimeMillis()))
                recordPacket(
                    targetId, "RX",
                    characteristic.service.uuid.toString(), characteristic.uuid.toString(), value,
                    kind = "READ"
                )
                log("BLE", targetId, "READ ${characteristic.uuid} = ${value.toHexString()}")
                // Intelligence: a real read result from a real device.
                pubOp(
                    "ble.read", true,
                    "Read from ${characteristic.uuid}: ${value.toHexString().take(200)}"
                )
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                val key = characteristic.uuid.toString()
                val old = targets[targetId] ?: return
                val updatedWatch = old.watchValues + (key to value.toHexString())
                upsertTarget(old.copy(watchValues = updatedWatch, lastSeenMs = System.currentTimeMillis()))
                recordPacket(
                    targetId, "RX",
                    characteristic.service.uuid.toString(), characteristic.uuid.toString(), value,
                    kind = "NOTIFY"
                )
                log("BLE", targetId, "NOTIFY ${characteristic.uuid} = ${value.toHexString()}")
            }
        })
        gattMap[targetId] = gatt
    }

    /** Decode a real GATT property bitmask into names. Unknown bits are ignored, never invented. */
    private fun decodeGattProperties(props: Int): List<String> {
        val out = mutableListOf<String>()
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) out += "READ"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) out += "WRITE"
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) out += "WRITE_NO_RESPONSE"
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) out += "NOTIFY"
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) out += "INDICATE"
        if (props and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) out += "SIGNED_WRITE"
        return out
    }

    private fun setGattStatus(targetId: String, status: String) {
        gattStatus[targetId] = status
        uiTick++
    }

    /** Human-readable GATT state for the UI. Defaults to Not connected. */
    fun gattStatusText(targetId: String): String = gattStatus[targetId] ?: "Not connected"

    /** Services/characteristics from the last real discovery. Empty = none discovered yet. */
    fun discoveredServices(targetId: String): List<DiscoveredService> =
        gattDiscovered[targetId] ?: emptyList()

    /**
     * Tap-to-select a writable characteristic for RAW commands. Only
     * characteristics the real discovery reported as writable can be selected.
     */
    fun selectRawCharacteristic(targetId: String, serviceUuid: String, charUuid: String) {
        val ch = gattDiscovered[targetId]
            ?.firstOrNull { it.uuid == serviceUuid }
            ?.characteristics?.firstOrNull { it.uuid == charUuid }
            ?: return
        if (ch.properties.none { it == "WRITE" || it == "WRITE_NO_RESPONSE" }) return
        rawSelection[targetId] = serviceUuid to charUuid
        uiTick++
        log("BLE", targetId, "RAW target selected: $charUuid")
    }

    /** Currently selected (service, characteristic) for RAW commands, or null. */
    fun rawSelection(targetId: String): Pair<String, String>? = rawSelection[targetId]

    private fun updateConnectionState(targetId: String, connected: Boolean) {
        val old = targets[targetId] ?: return
        upsertTarget(old.copy(connected = connected, lastSeenMs = System.currentTimeMillis()))
        if (connected) {
            // Package 3 (A-08): the link completed — the UI reads this to
            // jump straight to this device's controls.
            connectCompletedTargetId = targetId
        } else {
            // Package 3 (A-15): a dropped link forgets its live-update
            // subscriptions, so the toggles can never claim to listen to a
            // device that is no longer connected.
            notifyChannels = notifyChannels - targetId
            if (connectCompletedTargetId == targetId) connectCompletedTargetId = null
        }
    }

    /** Take the completed-connect target id for the controls jump, clearing it so it fires once. */
    fun consumeConnectCompleted(): String? {
        val id = connectCompletedTargetId
        connectCompletedTargetId = null
        return id
    }

    /** Package 3 (B-018/B-021): close every live Bluetooth link, BLE and text link alike. */
    fun disconnectAll() {
        gattMap.keys.toList().forEach { disconnectBle(it) }
        sppSockets.keys.toList().forEach { disconnectSpp(it) }
        log("BLE", null, "Disconnect all requested")
    }

    // ---- Package 3 (A-08) — rename / hide devices in the TARGETS list ----
    // Persisted in SharedPreferences (names by target id, hidden as a set).
    // The existing target list is untouched; the UI reads [displayNameFor]
    // and [visibleTargets] when it wants the renamed / hidden view.

    /** Name to show for a target: the user's rename when set, else the scanned name. */
    fun displayNameFor(targetId: String): String {
        val target = targets[targetId] ?: return targetId
        return targetNamePrefs.getString(targetId, null)?.takeIf { it.isNotBlank() } ?: target.name
    }

    /** Rename a device. A blank name clears the rename back to the scanned name. */
    fun renameTarget(targetId: String, name: String) {
        if (targets[targetId] == null) return
        val clean = name.trim().take(60)
        if (clean.isEmpty()) targetNamePrefs.edit().remove(targetId).apply()
        else targetNamePrefs.edit().putString(targetId, clean).apply()
        uiTick++
        log("TARGET", targetId, "Renamed to \"${clean.ifBlank { "scanned name" }}\"")
    }

    /** Hide a device from the TARGETS list. The device itself is untouched — it still scans and connects. */
    fun hideTarget(targetId: String) {
        val hidden = hiddenTargetPrefs.getStringSet("hidden", emptySet())?.toMutableSet() ?: mutableSetOf()
        hidden += targetId
        hiddenTargetPrefs.edit().putStringSet("hidden", hidden).apply()
        uiTick++
        log("TARGET", targetId, "Hidden from the device list")
    }

    /** Show a hidden device again. */
    fun unhideTarget(targetId: String) {
        val hidden = hiddenTargetPrefs.getStringSet("hidden", emptySet())?.toMutableSet() ?: mutableSetOf()
        hidden -= targetId
        hiddenTargetPrefs.edit().putStringSet("hidden", hidden).apply()
        uiTick++
        log("TARGET", targetId, "Shown in the device list again")
    }

    /** True when this target is hidden from the TARGETS list. */
    fun isTargetHidden(targetId: String): Boolean =
        hiddenTargetPrefs.getStringSet("hidden", emptySet())?.contains(targetId) == true

    /** Ids currently hidden from the TARGETS list. */
    fun hiddenTargetIds(): Set<String> =
        hiddenTargetPrefs.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()

    /** Devices with the hidden ones left out, in the usual newest-first order. */
    fun visibleTargets(): List<ScanTarget> =
        allTargets.filterNot { isTargetHidden(it.id) }

    fun disconnectBle(targetId: String) {
        rawSelection.remove(targetId)
        val gatt = gattMap[targetId]
        if (gatt == null) {
            setGattStatus(targetId, "Not connected")
            updateConnectionState(targetId, false)
            return
        }
        setGattStatus(targetId, "Disconnecting…")
        runCatching { gatt.disconnect() }
        // The connection callback finalizes: status, map removal, gatt.close().
        log("BLE", targetId, "Disconnect requested")
    }

    /**
     * Cancel an in-flight BLE connect. Closes the handle, kills the 15s
     * watchdog, and says so plainly. Safe to call when nothing is connecting.
     */
    fun cancelBleConnect(targetId: String) {
        connectTimeoutJobs[targetId]?.cancel()
        connectTimeoutJobs.remove(targetId)
        runCatching { gattMap[targetId]?.close() }
        gattMap.remove(targetId)
        setGattStatus(targetId, "Cancelled")
        updateConnectionState(targetId, false)
        log("BLE", targetId, "Connect cancelled by user")
    }

    /** True when the last failure for this target was an auth error — pair first. */
    fun needsPairing(targetId: String): Boolean = pairingNeeded[targetId] == true

    /**
     * Pair with a BLE device (phone-level bond). The phone shows its own PIN
     * dialog — Ghost Tap just starts the process. Only offered after a real
     * auth failure, never preemptively.
     */
    fun pairBle(targetId: String) {
        val target = targets[targetId] ?: return
        val address = target.identity["address"] ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setGattStatus(targetId, "Permission needed — allow Bluetooth in Settings, then try again.")
            log("BLE", targetId, "AUTH REQUIRED: BLUETOOTH_CONNECT permission")
            return
        }
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: run {
            setGattStatus(targetId, "This phone has no Bluetooth — can't pair.")
            return
        }
        setGattStatus(targetId, "Pairing — your phone will ask for a PIN…")
        log("BLE", targetId, "Pairing requested for $address")
        try {
            val started = device.createBond()
            if (!started) {
                setGattStatus(targetId, "Pairing didn't start — try again.")
                log("BLE", targetId, "createBond returned false")
            } else {
                // Package 3 (A-14): when pairing finishes, the bond
                // listener connects again by itself — no second tap.
                pendingBondReconnect[address] = targetId
                registerBondReceiver()
                log("BLE", targetId, "Pairing started — will connect again when it finishes")
            }
        } catch (e: Exception) {
            setGattStatus(targetId, "Pairing failed: ${e.message ?: "unknown problem"}")
            log("BLE", targetId, "Pairing failed: ${e.message}")
        }
    }

    /**
     * Plain-English for common Android GATT failure codes. The numeric code
     * stays in the log line; the screen gets words a non-technical person
     * can act on.
     */
    private fun gattFailMessage(status: Int): String = when (status) {
        133 -> "Connection dropped — the device is out of reach or busy. Try again closer to it."
        62 -> "Connection timed out — try again closer to the device."
        8 -> "Connection timed out — the device is too far away or not answering."
        else -> "Connection failed — the device said no or went away. Try again."
    }

    // ---------------- Classic Bluetooth SPP (RFCOMM) ----------------
    // Real RFCOMM client for Serial Port Profile devices (HC-05/HC-06,
    // OBD-II adapters, BT printers, etc.). Discovery already finds these;
    // this is the missing transport. All socket I/O runs off the UI thread.

    /** Human-readable SPP status per target. */
    fun sppStatusFor(targetId: String): String = sppStatus[targetId] ?: "Not connected"

    fun isSppConnected(targetId: String): Boolean =
        sppSockets[targetId]?.isConnected == true

    private fun setSppStatus(targetId: String, s: String) {
        sppStatus[targetId] = s
        uiTick++
    }

    private fun appendSppLog(targetId: String, line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val entry = "[$ts] $line"
        val cur = _sppLog.value[targetId].orEmpty()
        _sppLog.value = _sppLog.value + (targetId to (cur + entry).takeLast(200))
    }

    fun sppLogFor(targetId: String): List<String> =
        _sppLog.value[targetId].orEmpty()

    fun clearSppLog(targetId: String) {
        _sppLog.value = _sppLog.value + (targetId to emptyList())
    }

    /**
     * Connect to a Classic Bluetooth device via RFCOMM/SPP.
     * Must be called with a targetId like "bt:AA:BB:CC:DD:EE:FF".
     */
    fun connectSpp(targetId: String) {
        // Prevent duplicate connections.
        if (isSppConnected(targetId)) {
            setSppStatus(targetId, "Already connected")
            return
        }
        val target = targets[targetId] ?: run {
            setSppStatus(targetId, "Device not found")
            return
        }
        val address = target.identity["address"] ?: targetId.removePrefix("bt:")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            setSppStatus(targetId, "Permission needed — allow Bluetooth in Settings, then try again.")
            log("SPP", targetId, "AUTH REQUIRED: BLUETOOTH_CONNECT permission")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            setSppStatus(targetId, "This phone has no Bluetooth")
            return
        }
        setSppStatus(targetId, "Trying to connect…")
        log("SPP", targetId, "Connecting via RFCOMM/SPP to $address")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Cancel discovery: it slows down and can break RFCOMM connects.
                runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }
                val device: BluetoothDevice = adapter.getRemoteDevice(address)
                val socket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    throw SecurityException("BLUETOOTH_CONNECT revoked mid-connect")
                } else {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                }
                // Blocking connect — off the UI thread by design.
                socket.connect()
                sppSockets[targetId] = socket
                setSppStatus(targetId, "Connected")
                updateConnectionState(targetId, true)
                appendSppLog(targetId, "— Connected to ${target.name} ($address) —")
                log("SPP", targetId, "RFCOMM connected")
                // Intelligence: a real serial link opened to a real device.
                pubOp("spp.connect", true, "Classic Bluetooth connected to ${target.name} ($address).")
                startSppReader(targetId, socket)
            } catch (e: SecurityException) {
                setSppStatus(targetId, "Permission denied: ${e.message}")
                log("SPP", targetId, "Connect failed (permission): ${e.message}")
                pubErr("Classic Bluetooth connect", "Permission denied: ${e.message ?: "unknown"}")
            } catch (e: java.io.IOException) {
                // Most common: device doesn't offer SPP, is out of range, or
                // refused the connection. Say so plainly — never fake it.
                setSppStatus(targetId, "Couldn't connect: ${e.message ?: "the device didn't answer"}")
                log("SPP", targetId, "Connect failed (I/O): ${e.message}")
                appendSppLog(targetId, "— Couldn't connect: ${e.message ?: "this device may not support classic Bluetooth"} —")
                pubErr("Classic Bluetooth connect", "Couldn't connect: ${e.message ?: "this device may not support classic Bluetooth"}")
            } catch (e: Exception) {
                setSppStatus(targetId, "Couldn't connect: ${e.message}")
                log("SPP", targetId, "Connect failed: ${e.message}")
                pubErr("Classic Bluetooth connect", e.message ?: "unknown problem")
            }
        }
    }

    private fun startSppReader(targetId: String, socket: BluetoothSocket) {
        // Kill any stale reader first.
        sppReaders[targetId]?.cancel()
        val job = viewModelScope.launch(Dispatchers.IO) {
            val input = runCatching { socket.inputStream }.getOrNull() ?: return@launch
            val buf = ByteArray(4096)
            try {
                while (isActive && socket.isConnected) {
                    val n = try {
                        input.read(buf)
                    } catch (e: java.io.IOException) {
                        break // Socket closed remotely or locally.
                    }
                    if (n <= 0) break
                    val bytes = buf.copyOf(n)
                    val hex = bytes.joinToString(" ") { "%02X".format(it) }
                    // Try ASCII, fall back to noting raw bytes.
                    val ascii = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { "" }
                    val printable = ascii.filter { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' }
                    withContext(Dispatchers.Main) {
                        if (printable.length >= n / 2) {
                            appendSppLog(targetId, "RX: $printable")
                        } else {
                            appendSppLog(targetId, "RX(hex): $hex")
                        }
                        log("SPP", targetId, "RX ${n}B")
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    // Only mark disconnected if WE didn't already (e.g. user tapped disconnect).
                    if (sppSockets[targetId] === socket) {
                        disconnectSpp(targetId, reason = "REMOTE CLOSED")
                    }
                }
            }
        }
        sppReaders[targetId] = job
    }

    /**
     * How plain text becomes bytes on each send path. RawBytes is today's
     * behavior everywhere: exactly what was typed, nothing translated.
     */
    val sppTextLanguage: DeviceTextLanguage = RawBytes
    val bleTextLanguage: DeviceTextLanguage = RawBytes

    // ---------------- Printer language probe ----------------
    // The app learns how a printer talks by trying each known printer
    // language and asking the user whether anything printed. The winning
    // language is locked per device address and remembered permanently
    // (survives app restarts) in SharedPreferences.
    private val printerPrefs by lazy {
        getApplication<Application>().getSharedPreferences("ghosttap_printer", 0)
    }
    val lockedPrinterLang = mutableMapOf<String, String>() // address -> language id

    init {
        // Restore permanently remembered printer languages.
        val saved = printerPrefs.getStringSet("locked_langs", emptySet()) ?: emptySet()
        for (entry in saved) {
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2 && parts[0].isNotEmpty()) lockedPrinterLang[parts[0]] = parts[1]
        }
    }

    /** Write the current locks to permanent storage. */
    private fun persistLockedLangs() {
        val set = lockedPrinterLang.map { (addr, id) -> "$addr|$id" }.toSet()
        printerPrefs.edit().putStringSet("locked_langs", set).apply()
    }
    var printerProbeIndex by mutableStateOf<Int?>(null)
        private set
    var printerProbeActive by mutableStateOf(false)
        private set
    var printerProbeNote by mutableStateOf("")
        private set

    fun printerLanguageFor(address: String): PrinterLanguage? =
        ALL_LANGUAGES.firstOrNull { it.id == lockedPrinterLang[address] }

    fun startPrinterProbe(targetId: String) {
        val address = targets[targetId]?.identity?.get("address") as? String ?: return
        if (!isSppConnected(targetId)) {
            printerProbeNote = "Connect with classic Bluetooth first."
            uiTick++
            return
        }
        printerProbeIndex = 0
        printerProbeActive = true
        printerProbeNote = ""
        sendPrinterProbeJob(targetId)
    }

    private fun sendPrinterProbeJob(targetId: String) {
        val idx = printerProbeIndex ?: return
        val lang = ALL_LANGUAGES.getOrNull(idx) ?: return
        sendSpp(targetId, lang.encodeJob("GHOST TAP"))
    }

    fun answerPrinterProbe(targetId: String, printed: Boolean) {
        val address = targets[targetId]?.identity?.get("address") as? String ?: return
        val idx = printerProbeIndex ?: return
        if (printed) {
            lockedPrinterLang[address] = ALL_LANGUAGES[idx].id
            persistLockedLangs()
            printerProbeIndex = null
            printerProbeActive = false
            printerProbeNote = "Locked in. PRINT now speaks your printer's language."
            // Intelligence: the printer's real language, identified by test.
            pubOp(
                "printer.probe", true,
                "Printer language found: ${ALL_LANGUAGES[idx].id} — it printed the test word."
            )
        } else {
            val next = idx + 1
            if (next < ALL_LANGUAGES.size) {
                printerProbeIndex = next
                sendPrinterProbeJob(targetId)
            } else {
                printerProbeIndex = null
                printerProbeActive = false
                printerProbeNote = "None of the known printer languages worked — your printer speaks something private. Check the manual or the app it came with."
                pubOp(
                    "printer.probe", false,
                    "None of the known printer languages made the printer print."
                )
            }
        }
        uiTick++
    }

    fun cancelPrinterProbe() {
        printerProbeIndex = null
        printerProbeActive = false
        printerProbeNote = ""
        uiTick++
    }

    fun forgetPrinterLanguage(targetId: String) {
        val address = targets[targetId]?.identity?.get("address") as? String
        if (address != null) lockedPrinterLang.remove(address)
        persistLockedLangs()
        cancelPrinterProbe()
    }

    /** Print plain text on a printer whose language was learned via the probe. */
    fun printText(targetId: String, text: String): Boolean {
        val address = targets[targetId]?.identity?.get("address") as? String ?: return false
        val lang = printerLanguageFor(address) ?: return false
        if (!isSppConnected(targetId)) return false
        val sent = sendSpp(targetId, lang.encodeJob(text))
        // Intelligence: a real print job with its real outcome.
        pubOp("printer.print", sent, "Printed \"${text.take(80)}\" with ${lang.id}: ${if (sent) "sent" else "couldn't send"}.")
        return sent
    }

    /**
     * Package 3 (A-19 / D-50): print, then ask once whether it actually
     * came out on paper. The check is [printCheckPending]; the panel shows
     * "Did it print?" with Yes / No while it is set.
     */
    fun printTextWithCheck(targetId: String, text: String): Boolean {
        val sent = printText(targetId, text)
        if (sent) {
            val address = targets[targetId]?.identity?.get("address") as? String ?: ""
            printCheckPending = PrintCheck(targetId, text, lockedPrinterLang[address] ?: "")
            uiTick++
        }
        return sent
    }

    /**
     * Package 3 (A-19 / D-50): answer the "did it print?" check. Yes clears
     * it. No forgets the locked language and re-probes ONE language — the
     * next one in the probe order — so the user answers Yes/No for that
     * one language instead of starting the whole probe over.
     */
    fun answerPrintCheck(targetId: String, printed: Boolean) {
        val check = printCheckPending?.takeIf { it.targetId == targetId } ?: return
        printCheckPending = null
        if (printed) {
            printerProbeNote = "Printed — all good."
        } else {
            reprobeOnePrinterLanguage(targetId)
            printerProbeNote = "Didn't print — trying the next printer language. Watch the paper."
        }
        uiTick++
    }

    /**
     * Package 3 (A-19 / D-50): forget the locked language and try the NEXT
     * one in the probe order, once. The user answers Yes/No for that one
     * language on the device screen (the normal probe flow); a No moves to
     * the following language. Wraps to the first language after the last.
     */
    fun reprobeOnePrinterLanguage(targetId: String) {
        val address = targets[targetId]?.identity?.get("address") as? String ?: return
        if (!isSppConnected(targetId)) {
            printerProbeNote = "Connect with classic Bluetooth first."
            uiTick++
            return
        }
        val lockedId = lockedPrinterLang[address]
        val lockedIdx = ALL_LANGUAGES.indexOfFirst { it.id == lockedId }
        val nextIdx = if (lockedIdx < 0) 0 else (lockedIdx + 1) % ALL_LANGUAGES.size
        lockedPrinterLang.remove(address)
        persistLockedLangs()
        printerProbeIndex = nextIdx
        printerProbeActive = true
        printerProbeNote = ""
        sendPrinterProbeJob(targetId)
        uiTick++
    }

    /**
     * Send bytes over the SPP connection. Returns true if the write was attempted.
     */
    fun sendSpp(targetId: String, data: ByteArray): Boolean {
        val socket = sppSockets[targetId] ?: run {
            setSppStatus(targetId, "Not connected")
            return false
        }
        if (!socket.isConnected) {
            setSppStatus(targetId, "Not connected")
            return false
        }
        return try {
            val out = socket.outputStream
            // Write off the UI thread; SPP writes are usually fast but can block.
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { out.write(data); out.flush() }
                    .onFailure { e ->
                        withContext(Dispatchers.Main) {
                            appendSppLog(targetId, "— Send failed: ${e.message} —")
                            log("SPP", targetId, "TX failed: ${e.message}")
                        }
                    }
            }
            val hex = data.joinToString(" ") { "%02X".format(it) }
            val ascii = try { String(data, Charsets.UTF_8).filter { it.code in 32..126 } } catch (_: Exception) { "" }
            appendSppLog(targetId, if (ascii.isNotEmpty()) "TX: $ascii" else "TX(hex): $hex")
            log("SPP", targetId, "TX ${data.size}B")
            true
        } catch (e: Exception) {
            appendSppLog(targetId, "— Send failed: ${e.message} —")
            false
        }
    }

    fun disconnectSpp(targetId: String, reason: String = "USER") {
        sppReaders[targetId]?.cancel()
        sppReaders.remove(targetId)
        val socket = sppSockets.remove(targetId)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { socket?.inputStream?.close() }
            runCatching { socket?.outputStream?.close() }
            runCatching { socket?.close() }
            withContext(Dispatchers.Main) {
                setSppStatus(targetId, "Not connected")
                updateConnectionState(targetId, false)
                appendSppLog(targetId, "— Disconnected ($reason) —")
                log("SPP", targetId, "Disconnected ($reason)")
            }
        }
    }

    fun bleRead(targetId: String, serviceUuid: String, charUuid: String) {
        val gatt = gattMap[targetId] ?: return
        val service = runCatching { gatt.getService(UUID.fromString(serviceUuid)) }.getOrNull() ?: return
        val characteristic = runCatching { service.getCharacteristic(UUID.fromString(charUuid)) }.getOrNull() ?: return
        runCatching { gatt.readCharacteristic(characteristic) }
    }

    /**
     * DUMP ALL — read every readable characteristic sequentially through the
     * existing read path. Each answer lands in the response history as
     * "READ <uuid> = <hex>" via the normal read callback. One tap, whole device.
     */
    fun bleDumpAll(targetId: String) {
        val readable = discoveredServices(targetId).flatMap { svc ->
            svc.characteristics
                .filter { "READ" in it.properties }
                .map { svc.uuid to it.uuid }
        }
        if (readable.isEmpty()) {
            log("BLE", targetId, "DUMP ALL: no readable characteristics on this device.")
            return
        }
        log("BLE", targetId, "DUMP ALL: reading ${readable.size} characteristics…")
        viewModelScope.launch(Dispatchers.IO) {
            for ((svc, char) in readable) {
                if (gattMap[targetId] == null) {
                    log("BLE", targetId, "DUMP ALL: disconnected — stopped.")
                    return@launch
                }
                bleRead(targetId, svc, char)
                delay(150)
            }
            log("BLE", targetId, "DUMP ALL: done — every answer is in the history.")
        }
    }

    /**
     * SUBSCRIBE ALL — enable notifications (or indications where notify isn't
     * supported) on every notifiable characteristic, through the existing
     * subscribe path. Live updates stream into the response history.
     */
    fun bleSubscribeAll(targetId: String) {
        val notifiable = discoveredServices(targetId).flatMap { svc ->
            svc.characteristics
                .filter { "NOTIFY" in it.properties || "INDICATE" in it.properties }
                .map { svc.uuid to it.uuid }
        }
        if (notifiable.isEmpty()) {
            log("BLE", targetId, "SUBSCRIBE ALL: nothing notifiable on this device.")
            return
        }
        log("BLE", targetId, "SUBSCRIBE ALL: subscribing to ${notifiable.size} characteristics…")
        viewModelScope.launch(Dispatchers.IO) {
            for ((svc, char) in notifiable) {
                if (gattMap[targetId] == null) {
                    log("BLE", targetId, "SUBSCRIBE ALL: disconnected — stopped.")
                    return@launch
                }
                bleSetNotify(targetId, svc, char, true)
                delay(150)
            }
            log("BLE", targetId, "SUBSCRIBE ALL: done — live updates will appear below.")
        }
    }

    /**
     * Write a hex payload to a GATT characteristic. Honors the characteristic's
     * real write type: WRITE_NO_RESPONSE-only characteristics are written with
     * WRITE_TYPE_NO_RESPONSE, never forced to WRITE_TYPE_DEFAULT.
     * Returns a human-readable result for the RAW panel.
     */
    fun bleWrite(targetId: String, serviceUuid: String, charUuid: String, hexPayload: String): String {
        val gatt = gattMap[targetId] ?: return "Not connected — connect first, then try again."
        val service = runCatching { gatt.getService(UUID.fromString(serviceUuid)) }.getOrNull()
            ?: return "That feature wasn't found on this device."
        val characteristic = runCatching { service.getCharacteristic(UUID.fromString(charUuid)) }.getOrNull()
            ?: return "That feature wasn't found on this device."
        val payload = hexPayload.hexToBytes()
            ?: return "That isn't valid hex — use pairs like 41 54."
        val props = characteristic.properties
        val noResponseOnly = (props and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) &&
            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
        val sent = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeType = if (noResponseOnly) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                gatt.writeCharacteristic(characteristic, payload, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = if (noResponseOnly) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        val result = if (sent) {
            "Sent to ${characteristic.uuid}: ${payload.toHexString()}"
        } else {
            "Couldn't send to ${characteristic.uuid}."
        }
        if (sent) {
            recordPacket(targetId, "TX", serviceUuid, charUuid, payload, updateBaseline = false, kind = "WRITE")
        }
        log("BLE", targetId, result)
        // Intelligence: a real write attempt with its real outcome.
        pubOp("ble.write", sent, result)
        return result
    }

    /**
     * Write with a plain-text option: ASCII encodes the input as UTF-8 bytes,
     * HEX parses pairs like "A1 03". The RAW panel shows which one is active
     * so the user always knows what the bytes will be.
     */
    fun bleWritePayload(
        targetId: String,
        serviceUuid: String,
        charUuid: String,
        input: String,
        ascii: Boolean
    ): String {
        if (ascii) {
            if (input.isEmpty()) return "Type something first."
            val gatt = gattMap[targetId] ?: return "Not connected — connect first, then try again."
            val service = runCatching { gatt.getService(UUID.fromString(serviceUuid)) }.getOrNull()
                ?: return "That feature wasn't found on this device."
            val characteristic = runCatching { service.getCharacteristic(UUID.fromString(charUuid)) }.getOrNull()
                ?: return "That feature wasn't found on this device."
            val payload = bleTextLanguage.encode(input)
            val props = characteristic.properties
            val noResponseOnly = (props and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) &&
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            val sent = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val writeType = if (noResponseOnly) {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    gatt.writeCharacteristic(characteristic, payload, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.writeType = if (noResponseOnly) {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
            }.getOrDefault(false)
            val result = if (sent) {
                "Sent text \"$input\" to ${characteristic.uuid} (${payload.toHexString()})"
            } else {
                "Couldn't send to ${characteristic.uuid}."
            }
            if (sent) {
                recordPacket(targetId, "TX", serviceUuid, charUuid, payload, updateBaseline = false, kind = "WRITE")
            }
            log("BLE", targetId, result)
            // Intelligence: a real write attempt with its real outcome.
            pubOp("ble.write", sent, result)
            return result
        }
        return bleWrite(targetId, serviceUuid, charUuid, input)
    }

    /** MAP INTERFACE: recent endpoint responses, newest first. */
    fun endpointHistoryFor(targetId: String): List<HistoryEntry> =
        endpointHistory[targetId]?.let { synchronized(it) { it.toList() } }?.reversed() ?: emptyList()

    // ---------------- MAP INTERFACE capture rounds (v1.7) ----------------

    /**
     * Begin a capture round: snapshots current channel bytes as BEFORE.
     * The user performs ONE action on the device, then stops the round.
     */
    fun startMapRound(targetId: String): String {
        val roundNum = (mapCaptures[targetId]?.size ?: 0) + 1
        val before = mutableMapOf<String, ByteArray>()
        gattDiscovered[targetId]?.forEach { svc ->
            svc.characteristics.forEach { ch ->
                lastPacketBytes["$targetId|${ch.uuid}"]?.let { before[ch.uuid] = it.copyOf() }
            }
        }
        mapRound = MapRound(targetId, "TEST $roundNum", System.currentTimeMillis(), before)
        uiTick++
        log("MAP", targetId, "Capture TEST $roundNum started")
        return if (before.isEmpty()) {
            "Test $roundNum recording — but there's nothing to compare against yet. Read a channel or listen to one " +
                "first, then tap STOP."
        } else {
            "Test $roundNum recording — do ONE thing on the device, then tap STOP."
        }
    }

    fun mapRoundActiveFor(targetId: String): Boolean = mapRound?.targetId == targetId

    fun mapRoundLabelFor(targetId: String): String? = mapRound?.takeIf { it.targetId == targetId }?.label

    /** Stop the active round, diff AFTER vs BEFORE, and store the round. */
    fun stopMapRound(targetId: String): MapCapture? {
        val round = mapRound?.takeIf { it.targetId == targetId } ?: return null
        mapRound = null
        val changes = mutableListOf<ChannelChange>()
        val svcByChar = mutableMapOf<String, String>()
        gattDiscovered[targetId]?.forEach { svc ->
            svc.characteristics.forEach { ch -> svcByChar[ch.uuid] = svc.uuid }
        }
        lastPacketBytes.forEach { (key, cur) ->
            if (!key.startsWith("$targetId|")) return@forEach
            val charUuid = key.substringAfter("|")
            val base = round.before[charUuid] ?: return@forEach
            if (base.size != cur.size) return@forEach
            val moved = cur.indices.filter { base[it] != cur[it] }
            if (moved.isNotEmpty()) {
                val desc = moved.joinToString(", ") { i ->
                    "byte $i: %02X → %02X".format(base[i], cur[i])
                }
                changes += ChannelChange(
                    serviceUuid = svcByChar[charUuid] ?: "",
                    charUuid = charUuid,
                    byteChanges = desc,
                    beforeHex = base.toHexString(),
                    afterHex = cur.toHexString()
                )
            }
        }
        val capture = MapCapture(round.label, round.startedAtMs, System.currentTimeMillis(), changes)
        val list = mapCaptures.getOrPut(targetId) { mutableListOf() }
        list += capture
        uiTick++
        log("MAP", targetId, "${round.label} stopped: ${changes.size} channel(s) changed")
        // Intelligence: a finished map round is a real comparative result.
        pubOp(
            "ble.map_round", true,
            "Test '${round.label}' on ${targets[targetId]?.name ?: targetId}: " +
                "${changes.size} data channel(s) changed."
        )
        return capture
    }

    fun mapCapturesFor(targetId: String): List<MapCapture> =
        mapCaptures[targetId]?.toList() ?: emptyList()

    fun clearMapCaptures(targetId: String) {
        mapCaptures.remove(targetId)
        mapRound?.takeIf { it.targetId == targetId }?.let { mapRound = null }
        uiTick++
    }

    /**
     * Correlations across rounds: channels whose same bytes changed in 2+
     * rounds are the repeatable signal. Plain-English, never overstated.
     */
    fun mapCorrelations(targetId: String): List<String> {
        val rounds = mapCaptures[targetId] ?: return emptyList()
        if (rounds.size < 2) return emptyList()
        // channel -> set of "byte index" values seen changing
        val byChannel = mutableMapOf<String, MutableMap<Int, Int>>()
        rounds.forEach { cap ->
            cap.changes.forEach { ch ->
                val key = "${ch.serviceUuid}|${ch.charUuid}"
                val byteIdx = Regex("byte (\\d+):").findAll(ch.byteChanges)
                    .map { it.groupValues[1].toInt() }.toSet()
                val counts = byChannel.getOrPut(key) { mutableMapOf() }
                byteIdx.forEach { i -> counts[i] = (counts[i] ?: 0) + 1 }
            }
        }
        val out = mutableListOf<String>()
        byChannel.forEach { (key, counts) ->
            val repeatable = counts.filter { it.value >= 2 }.keys.sorted()
            if (repeatable.isNotEmpty()) {
                val charUuid = key.substringAfter("|")
                val name = SigUuids.characteristicName(charUuid)
                    ?: "characteristic ${SigUuids.shortForm(charUuid)}"
                val n = counts.values.maxOrNull() ?: 0
                out += "$name — byte(s) ${repeatable.joinToString(", ")} changed in $n of ${rounds.size} tests. " +
                    "This is the change that repeats; the other bytes are just noise."
            }
        }
        return out
    }

    /**
     * Package 3 (B-047): plain-words next step for map rounds — start,
     * repeat the same action so the real change can be told from noise,
     * or save a repeatable change as a control.
     */
    fun mapNextStep(targetId: String): String {
        val rounds = mapCaptures[targetId]?.size ?: 0
        return when {
            mapRoundActiveFor(targetId) ->
                "Test ${rounds + 1} is recording — do ONE thing on the device, then tap Stop."
            rounds == 0 ->
                "No tests yet — start a test, do one thing on the device, then stop it."
            rounds == 1 ->
                "Test 1 done — do the SAME thing once more so Ghost Tap can tell the real change from noise."
            else -> {
                val corr = mapCorrelations(targetId)
                if (corr.isEmpty()) "Ran $rounds tests but nothing repeats yet — try the same action once more."
                else "Found a repeatable change: ${corr.first()} Save it as a control to use it with one tap."
            }
        }
    }

    /** Package 3 (A-16 / B-047): plain-words suggested name for a map capture — the channel that changed. */
    fun suggestMapActionName(targetId: String, captureLabel: String): String {
        val capture = mapCaptures[targetId]?.firstOrNull { it.label == captureLabel }
            ?: return captureLabel
        val first = capture.changes.firstOrNull() ?: return "$captureLabel — nothing changed"
        val name = SigUuids.characteristicName(first.charUuid)
            ?: "Custom feature ${SigUuids.shortForm(first.charUuid)}"
        return "$name changed"
    }

    /**
     * Package 3 (A-16): turn one map capture into a one-tap learned
     * control — a correlation becomes a control. Saves the AFTER bytes of
     * the first changed channel, so replaying it sends what the device
     * looked like after the action. Confidence is HIGH only when the same
     * channel changed the same way in 2+ rounds; otherwise MEDIUM.
     */
    fun saveMapCaptureAsAction(targetId: String, captureLabel: String, name: String): LearnedAction? {
        val capture = mapCaptures[targetId]?.firstOrNull { it.label == captureLabel } ?: return null
        val change = capture.changes.firstOrNull() ?: return null
        val address = targets[targetId]?.identity?.get("address") ?: return null
        if (change.afterHex.isBlank()) return null
        val repeats = mapCaptures[targetId]?.count { cap ->
            cap.changes.any { it.charUuid == change.charUuid && it.byteChanges == change.byteChanges }
        } ?: 0
        val action = LearnedAction(
            id = learnedStore.newId(),
            name = name.ifBlank { suggestMapActionName(targetId, captureLabel) },
            serviceUuid = change.serviceUuid,
            charUuid = change.charUuid,
            packetHex = change.afterHex,
            byteChanges = change.byteChanges,
            confidence = if (repeats >= 2) "HIGH" else "MEDIUM",
            reason = "Map test ${capture.label}: ${change.byteChanges}" +
                if (repeats >= 2) " (same change in $repeats tests)" else "",
            source = "Saved from map test ${capture.label}",
            learnedAtMs = System.currentTimeMillis()
        )
        learnedStore.addAction(address, action)
        uiTick++
        log("LEARN", targetId, "Map test saved as control: ${action.name}")
        return action
    }

    /** Package 3 (B-053 / B-054): plain-words suggested name for the pending discovery — the channel that changed. */
    fun suggestDiscoveryName(targetId: String): String {
        val disc = pendingDiscovery[targetId] ?: return "Learned action"
        if (disc.charUuid.isBlank()) return "Learned action"
        val channel = SigUuids.characteristicName(disc.charUuid)
            ?: "Custom feature ${SigUuids.shortForm(disc.charUuid)}"
        return "$channel changed"
    }

    // ---------------- Action Engine (v1.7) ----------------

    /** Real operations Ghost Tap can attempt on this target right now. */
    fun deviceActions(targetId: String): List<DeviceAction> {
        val target = targets[targetId] ?: return emptyList()
        return when (target.type) {
            TargetType.BLE -> {
                val discovered = gattDiscovered[targetId] ?: emptyList()
                var readable = 0
                var writable = 0
                var notifiable = 0
                discovered.forEach { svc ->
                    svc.characteristics.forEach { ch ->
                        if ("READ" in ch.properties) readable++
                        if ("WRITE" in ch.properties || "WRITE_NO_RESPONSE" in ch.properties) writable++
                        if ("NOTIFY" in ch.properties || "INDICATE" in ch.properties) notifiable++
                    }
                }
                ActionEngine.bleActions(
                    connected = target.connected,
                    readable = readable,
                    writable = writable,
                    notifiable = notifiable,
                    hasLearnedActions = learnedActionsFor(targetId).isNotEmpty()
                )
            }
            TargetType.NFC -> {
                val facts = nfcFacts[targetId] ?: return emptyList()
                ActionEngine.nfcActions(facts, hasSavedNdef())
            }
            TargetType.USB -> {
                val id = targetId.substringAfter("usb:").toIntOrNull()
                val device = id?.let { did -> usbManager.deviceList.values.firstOrNull { it.deviceId == did } }
                if (device == null) return emptyList()
                var hasOut = false
                var hasIn = false
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_OUT) hasOut = true
                        if (ep.direction == UsbConstants.USB_DIR_IN) hasIn = true
                    }
                }
                ActionEngine.usbActions(hasOut, hasIn, usbManager.hasPermission(device))
            }
            TargetType.BLUETOOTH_CLASSIC ->
                ActionEngine.classicBtActions(connected = isSppConnected(targetId))
            else -> emptyList()
        }
    }

    /**
     * Perform a directly-executable action. Returns a plain-English result.
     * Panel-type actions (opensPanel != null) are handled by the UI.
     */
    /**
     * Visual Intel handoff: the camera found something worth looking for.
     * Hands the clue to the scanner so the user can run SCAN and match it
     * against real Bluetooth targets. The clue is a candidate, not an ID.
     */
    fun visualIntelHint(query: String) {
        // Package 3 (B-050): keep the hint so a scan can highlight the
        // device it names, instead of the hint staying text only.
        lastVisualHint = query
        log("visual", null, "Visual Intel hint: run SCAN and look for '$query'")
        scanStatus = "Visual Intel: tap SCAN and look for '$query'"
    }

    /** Package 3 (B-050): devices whose name or likely identity matches the last Visual Intel hint. */
    fun targetsMatchingHint(): List<ScanTarget> {
        val query = lastVisualHint.trim()
        if (query.isBlank()) return emptyList()
        return rankedTargets().filter {
            it.name.contains(query, ignoreCase = true) ||
                (it.likely?.contains(query, ignoreCase = true) == true)
        }
    }

    /** Package 3 (B-005): devices ranked strongest first, newest first on ties. */
    fun rankedTargets(): List<ScanTarget> =
        allTargets.sortedWith(
            compareByDescending<ScanTarget> { it.rssi ?: Int.MIN_VALUE }
                .thenByDescending { it.lastSeenMs }
        )

    /** Package 3 (B-007): plain-words explanation of a phone-sensor target — it is the phone's own sensor, not a device nearby. */
    fun explainSensorTarget(targetId: String): String {
        val target = targets[targetId] ?: return "Device not found."
        if (target.type != TargetType.SENSOR) return "This isn't one of the phone's own sensors."
        return "This is your phone's own ${target.name.lowercase()} — not a device nearby. " +
            "Ghost Tap lists it here so you can watch what your phone itself senses. " +
            "Latest reading: ${target.watchValues["values"] ?: "none yet"}."
    }

    fun performDeviceAction(targetId: String, actionId: String): String {
        return when (actionId) {
            "ble_connect" -> {
                connectBle(targetId)
                "Trying to connect — watch the status line."
            }
            "ble_disconnect" -> {
                disconnectBle(targetId)
                "Disconnected."
            }
            "spp_connect" -> {
                connectSpp(targetId)
                "Connecting with classic Bluetooth — watch the status line."
            }
            "spp_disconnect" -> {
                disconnectSpp(targetId)
                "Classic Bluetooth disconnected."
            }
            "nfc_read" -> nfcReadFull(targetId)
            "nfc_copy" -> nfcCopyContent()
            "nfc_write_saved" -> if (nfcWriteSaved()) {
                "Saved records written to the tag."
            } else {
                "Write failed — tap a writable tag and try again."
            }
            "nfc_erase" -> if (nfcErase()) {
                "Tag erased."
            } else {
                "Erase failed — the tag may be read-only."
            }
            "nfc_format" -> if (nfcFormat()) {
                "Tag set up — ready for tap-to-read data."
            } else {
                "Format failed — this tag doesn't support it."
            }
            "usb_auth" -> {
                val id = targetId.substringAfter("usb:").toIntOrNull()
                val device = id?.let { did -> usbManager.deviceList.values.firstOrNull { it.deviceId == did } }
                if (device == null) {
                    "USB device no longer present."
                } else {
                    requestUsbPermission(device)
                    "Permission asked for — tap Allow on the Android pop-up."
                }
            }
            else -> "That action isn't available."
        }
    }

    // ------------------------------------------------------------------
    // Plain-English interpreter: packet feed, device summary, learn mode.
    // ------------------------------------------------------------------

    /**
     * Record one captured packet into the live translator feed.
     * Consecutive identical packets on the same channel are grouped
     * ("Repeated N times") instead of flooding the feed.
     */
    private fun recordPacket(
        targetId: String,
        direction: String,
        serviceUuid: String,
        charUuid: String,
        bytes: ByteArray,
        updateBaseline: Boolean = true,
        kind: String? = null
    ) {
        val key = "$targetId|$charUuid"
        val prev = lastPacketBytes[key]
        val analysis = PacketInterpreter.analyze(bytes, prev)

        // Track recurring byte-change signatures; repeated identical changes
        // raise confidence that the bytes are linked to a repeated action.
        var interpretation = analysis.interpretation
        var confidence = analysis.confidence
        if (analysis.changedBytes.isNotEmpty()) {
            val sig = "$key|${analysis.changedBytes.joinToString(",")}"
            val count = (changeSigCounts[sig] ?: 0) + 1
            changeSigCounts[sig] = count
            if (count >= 3 && confidence == "LOW") {
                confidence = "MEDIUM"
                interpretation += " Seen this exact change $count times — stronger link to a repeated action."
            }
        }

        val now = System.currentTimeMillis()
        val feed = packetFeed.getOrPut(targetId) { mutableListOf() }
        synchronized(feed) {
            val last = feed.lastOrNull()
            if (last != null && last.charUuid == charUuid && last.direction == direction &&
                last.hex == analysis.hex
            ) {
                feed[feed.lastIndex] = last.copy(repeatCount = last.repeatCount + 1, timestampMs = now)
            } else {
                feed.add(
                    PacketEvent(
                        timestampMs = now,
                        direction = direction,
                        serviceUuid = serviceUuid,
                        charUuid = charUuid,
                        hex = analysis.hex,
                        bytes = bytes.map { it.toInt() and 0xFF },
                        ascii = analysis.ascii,
                        interpretation = interpretation,
                        confidence = confidence,
                        changedBytes = analysis.changedBytes,
                        prevHex = analysis.prevHex
                    )
                )
                while (feed.size > 200) feed.removeAt(0)
            }
        }
        if (updateBaseline) {
            lastPacketBytes[key] = bytes.copyOf()
        }
        // MAP INTERFACE endpoint history (last 10 per target).
        val histKind = kind ?: if (direction == "TX") "WRITE" else "READ"
        val hist = endpointHistory.getOrPut(targetId) { mutableListOf() }
        synchronized(hist) {
            hist.add(
                HistoryEntry(
                    timestampMs = now,
                    kind = histKind,
                    serviceUuid = serviceUuid,
                    charUuid = charUuid,
                    hex = analysis.hex,
                    ascii = analysis.ascii,
                    interpretation = interpretation
                )
            )
            while (hist.size > 200) hist.removeAt(0)
        }
        uiTick++
    }

    /** Live translator feed for a target, oldest first. */
    fun packetFeedFor(targetId: String): List<PacketEvent> =
        packetFeed[targetId]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    /** The most recent live-translator event for a target, or null when nothing has been heard yet. */
    fun latestFeedEvent(targetId: String): PacketEvent? =
        packetFeed[targetId]?.let { synchronized(it) { it.lastOrNull() } }

    /**
     * Package 3 (B-051): plain-words verdict on what just happened on a
     * device — the busiest channel, what it last said, and whether that
     * exact message keeps repeating (a repeat is the only honest "this
     * looks like the same action again" signal on this path).
     */
    fun summarizeFeed(targetId: String): String {
        val feed = packetFeedFor(targetId)
        if (feed.isEmpty()) {
            return "Nothing heard from this device yet — read a value or turn on live updates first."
        }
        val byChannel = feed.groupBy { it.charUuid }
        val busiest = byChannel.maxByOrNull { it.value.size } ?: return "Nothing heard yet."
        val name = SigUuids.characteristicName(busiest.key)
            ?: "Custom feature ${SigUuids.shortForm(busiest.key)}"
        val latest = busiest.value.last()
        val repeats = busiest.value.count { it.hex == latest.hex }
        return buildString {
            append("Heard ${feed.size} message(s) from this device. ")
            append("Busiest: $name (${busiest.value.size} message(s)). ")
            append("Latest: ${latest.interpretation} ")
            if (repeats >= 3) {
                append("This exact message repeated $repeats times — it looks like the same action happening again. ")
            }
            append("Confidence: ${latest.confidence}.")
        }
    }

    /**
     * Package 3 (A-11): save one live-translator event as a learned
     * control, under the name given — the "Save this" for the live feed.
     * The saved action replays the exact bytes the device sent, labelled
     * with its source, exactly like a learned control.
     */
    fun savePacketEventAsAction(targetId: String, timestampMs: Long, name: String): LearnedAction? {
        val event = packetFeed[targetId]
            ?.let { synchronized(it) { it.toList() } }
            ?.firstOrNull { it.timestampMs == timestampMs } ?: return null
        val address = targets[targetId]?.identity?.get("address") ?: return null
        if (event.hex.isBlank()) return null
        val action = LearnedAction(
            id = learnedStore.newId(),
            name = name.ifBlank { "Saved from the live feed" },
            serviceUuid = event.serviceUuid,
            charUuid = event.charUuid,
            packetHex = event.hex,
            byteChanges = if (event.changedBytes.isEmpty()) "" else "bytes changed: ${event.changedBytes.joinToString(", ")}",
            confidence = event.confidence,
            reason = event.interpretation,
            source = "Saved from the live feed",
            learnedAtMs = System.currentTimeMillis()
        )
        learnedStore.addAction(address, action)
        uiTick++
        log("LEARN", targetId, "Live feed event saved as control: ${action.name}")
        return action
    }

    /** Package 3 (A-11): plain-words suggested name for a live-feed event. */
    fun suggestFeedEventName(targetId: String, timestampMs: Long): String {
        val event = packetFeed[targetId]
            ?.let { synchronized(it) { it.toList() } }
            ?.firstOrNull { it.timestampMs == timestampMs } ?: return "Saved from the live feed"
        val channel = SigUuids.characteristicName(event.charUuid)
            ?: "Custom feature ${SigUuids.shortForm(event.charUuid)}"
        val direction = if (event.direction == "RX") "device sent" else "phone sent"
        return "$channel — $direction ${event.hex.take(11)}"
    }

    /** Plain-English proximity band for the target's last RSSI. */
    fun deviceProximity(targetId: String): String =
        DeviceClassifier.signalProximity(targets[targetId]?.rssi)

    /**
     * HELP IDENTIFY: deep plain-English walkthrough of every identification
     * signal Android provided for this target. Uses the richest signals seen
     * (advertisement + post-connection GATT when available).
     */
    fun explainDeviceDeep(targetId: String): List<String> {
        val target = targets[targetId] ?: return listOf("Device not found.")
        val signals = adSignals[targetId] ?: DeviceSignals(
            name = target.name,
            address = target.identity["address"],
            protocol = "BLE",
            rssi = target.rssi
        )
        return DeviceClassifier.explainDeep(signals)
    }

    /** True when the verdict is uncertain enough to offer HELP IDENTIFY. */
    fun needsHelpIdentify(targetId: String): Boolean {
        val target = targets[targetId] ?: return false
        return target.confidence == "Low" || (target.likely?.startsWith("UNKNOWN") == true)
    }

    /** Plain-English device summary built from real discovery data. */
    fun deviceSummary(targetId: String): DeviceSummary {
        val target = targets[targetId]
        val discovered = gattDiscovered[targetId] ?: emptyList()
        val standard = discovered.count { SigUuids.isStandardService(it.uuid) }
        val custom = discovered.size - standard

        var bestRank: ChannelRanker.Rank? = null
        var bestChannel: Pair<String, String>? = null
        var bestCharName: String? = null
        discovered.forEach { svc ->
            if (!SigUuids.isStandardService(svc.uuid)) {
                svc.characteristics.forEach { ch ->
                    val rank = ChannelRanker.rank(ch.properties)
                    if (bestRank == null || rank.order > bestRank!!.order) {
                        bestRank = rank
                        bestChannel = svc.uuid to ch.uuid
                        bestCharName = SigUuids.characteristicName(ch.uuid)
                            ?: "Custom feature ${SigUuids.shortForm(ch.uuid)}"
                    }
                }
            }
        }
        val rank = bestRank
        return DeviceSummary(
            connected = target?.connected == true,
            deviceType = target?.likely ?: "Unknown device",
            typeConfidence = target?.confidence ?: "Low",
            totalServices = discovered.size,
            standardServices = standard,
            customServices = custom,
            mostInteresting = rank?.let { "${it.label} — $bestCharName" },
            mostInterestingWhy = rank?.plainEnglish,
            mostInterestingChannel = bestChannel
        )
    }

    // ---------------- Learn mode ----------------

    /** Begin a learn session. Returns the guidance text for the user. */
    fun startLearning(targetId: String): String {
        val baseline = mutableMapOf<String, ByteArray>()
        gattDiscovered[targetId]?.forEach { svc ->
            svc.characteristics.forEach { ch ->
                lastPacketBytes["$targetId|${ch.uuid}"]?.let { baseline[ch.uuid] = it.copyOf() }
            }
        }
        learningSession = LearningSession(targetId, System.currentTimeMillis(), baseline)
        uiTick++
        log("LEARN", targetId, "Learn session started")
        return "Perform ONE action now — press a button on the device, change a setting " +
            "in its official app, or trigger its sensor. Then press STOP LEARNING."
    }

    fun learningActiveFor(targetId: String): Boolean = learningSession?.targetId == targetId

    fun cancelLearning() {
        learningSession?.let { log("LEARN", it.targetId, "Learn session cancelled") }
        learningSession = null
        uiTick++
    }

    /**
     * Compare traffic during the learn session against the pre-action baseline.
     * Returns a discovery (possibly with no evidence) awaiting a user-supplied name.
     */
    fun stopLearning(targetId: String): LearnedDiscovery? {
        val session = learningSession
        if (session == null || session.targetId != targetId) return null
        learningSession = null

        val during = packetFeed[targetId]
            ?.let { synchronized(it) { it.toList() } }
            ?.filter { it.timestampMs >= session.startedAtMs && it.direction == "RX" }
            ?: emptyList()

        // Per channel: first packet seen during the session vs the baseline.
        data class Change(val charUuid: String, val serviceUuid: String, val bytes: ByteArray, val changed: List<Int>)
        val changes = mutableListOf<Change>()
        during.groupBy { it.charUuid }.forEach { (charUuid, events) ->
            val first = events.firstOrNull() ?: return@forEach
            val base = session.baseline[charUuid] ?: return@forEach
            val cur = first.bytes.map { it.toByte() }.toByteArray()
            if (base.size != cur.size) return@forEach
            val changed = cur.indices.filter { base[it] != cur[it] }
            if (changed.isNotEmpty()) {
                changes += Change(charUuid, first.serviceUuid, cur, changed)
            }
        }

        val discovery = if (changes.isEmpty()) {
            LearnedDiscovery(
                id = java.util.UUID.randomUUID().toString(),
                serviceUuid = "", charUuid = "", packetHex = "", byteChanges = "",
                confidence = "Unknown",
                reason = "No clear change was seen while learning. Try doing the action " +
                    "while watching the live feed, then tap STOP LEARNING right after."
            )
        } else {
            val best = changes.minByOrNull { it.changed.size }!!
            val hex = best.bytes.toHexString()
            val byteDesc = best.changed.joinToString(", ") { i ->
                val base = session.baseline[best.charUuid]!!
                "byte $i: %02X → %02X".format(base[i], best.bytes[i])
            }
            val sigKey = "$targetId|${best.charUuid}|${best.changed.joinToString(",")}"
            val seen = changeSigCounts[sigKey] ?: 0
            val confidence: String
            val reason: String
            when {
                seen >= 3 -> {
                    confidence = "HIGH"
                    reason = "This exact byte change has been seen $seen times, and it appeared " +
                        "right after the action while surrounding bytes stayed constant."
                }
                best.changed.size == 1 -> {
                    confidence = "MEDIUM"
                    reason = "The same byte changed immediately after the action while surrounding " +
                        "bytes remained constant."
                }
                else -> {
                    confidence = "LOW"
                    reason = "Multiple bytes changed at once. Repeat the action — the bytes that move " +
                        "the same way every time are the ones linked to it."
                }
            }
            LearnedDiscovery(
                id = java.util.UUID.randomUUID().toString(),
                serviceUuid = best.serviceUuid,
                charUuid = best.charUuid,
                packetHex = hex,
                byteChanges = byteDesc,
                confidence = confidence,
                reason = reason
            )
        }
        pendingDiscovery[targetId] = discovery
        uiTick++
        log("LEARN", targetId, "Learn session stopped: ${discovery.byteChanges.ifBlank { "no change" }}")
        // Intelligence: a finished learn session is a real behavioral result.
        pubOp(
            "ble.learn", true,
            "Learning from ${targets[targetId]?.name ?: targetId}: " +
                (discovery.byteChanges.ifBlank { "no clear change observed" })
        )
        return discovery
    }

    fun pendingDiscoveryFor(targetId: String): LearnedDiscovery? = pendingDiscovery[targetId]

    /** Name the pending discovery and save it as a learned control for this device. */
    fun saveDiscoveryAsAction(targetId: String, name: String): LearnedAction? {
        val disc = pendingDiscovery[targetId] ?: return null
        if (disc.packetHex.isBlank()) return null
        val address = targets[targetId]?.identity?.get("address") ?: return null
        val action = LearnedAction(
            id = learnedStore.newId(),
            name = name.ifBlank { "Unnamed action" },
            serviceUuid = disc.serviceUuid,
            charUuid = disc.charUuid,
            packetHex = disc.packetHex,
            byteChanges = disc.byteChanges,
            confidence = disc.confidence,
            reason = disc.reason,
            source = "Captured from device interaction",
            learnedAtMs = System.currentTimeMillis()
        )
        learnedStore.addAction(address, action)
        pendingDiscovery.remove(targetId)
        uiTick++
        log("LEARN", targetId, "Learned control saved: ${action.name}")
        return action
    }

    fun learnedActionsFor(targetId: String): List<LearnedAction> {
        val address = targets[targetId]?.identity?.get("address") ?: return emptyList()
        return learnedStore.actionsFor(address)
    }

    fun removeLearnedAction(targetId: String, actionId: String) {
        val address = targets[targetId]?.identity?.get("address") ?: return
        learnedStore.removeAction(address, actionId)
        uiTick++
    }

    /** Replay a learned command. The source is always labeled — never silent. */
    fun replayLearned(targetId: String, actionId: String): String {
        val action = learnedActionsFor(targetId).firstOrNull { it.id == actionId }
            ?: return "Couldn't find that saved action."
        val result = bleWrite(targetId, action.serviceUuid, action.charUuid, action.packetHex)
        return "Running saved action [${action.name}]\n$result\nSource: ${action.source}"
    }

    /** LISTEN: enable live updates on a channel. */
    fun listenToChannel(targetId: String, serviceUuid: String, charUuid: String) {
        bleSetNotify(targetId, serviceUuid, charUuid, true)
    }

    /** True when live updates are switched on for this channel (A-15 — the UI reads this, not a local toggle). */
    fun isNotifyOn(targetId: String, serviceUuid: String, charUuid: String): Boolean =
        notifyChannels[targetId]?.contains("$serviceUuid|$charUuid") == true

    fun bleSetNotify(targetId: String, serviceUuid: String, charUuid: String, enable: Boolean) {
        val gatt = gattMap[targetId] ?: return
        val service = runCatching { gatt.getService(UUID.fromString(serviceUuid)) }.getOrNull() ?: return
        val characteristic = runCatching { service.getCharacteristic(UUID.fromString(charUuid)) }.getOrNull() ?: return
        gatt.setCharacteristicNotification(characteristic, enable)
        characteristic.descriptors.forEach { descriptor ->
            if (descriptor.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) {
                // Notify where supported, indication where only indicate is offered.
                val value = if (enable) {
                    if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
        }
        // Package 3 (A-15): record what was asked for, in the ViewModel, so
        // every toggle on every panel reads the same state and a
        // recomposition can never show a toggle the app didn't ask for.
        val key = "$serviceUuid|$charUuid"
        notifyChannels = if (enable) {
            notifyChannels + (targetId to (notifyChannels[targetId].orEmpty() + key))
        } else {
            val remaining = notifyChannels[targetId].orEmpty() - key
            if (remaining.isEmpty()) notifyChannels - targetId
            else notifyChannels + (targetId to remaining)
        }
        uiTick++
        log("BLE", targetId, "NOTIFY ${if (enable) "ENABLED" else "DISABLED"}")
    }

    fun networkRawRequest(targetId: String, method: String, path: String, body: String): String {
        val target = targets[targetId] ?: return "Device not found"
        val host = target.identity["host"] ?: return "Address missing"
        val port = target.identity["port"] ?: return "Port number missing"
        val url = "http://$host:$port${if (path.startsWith("/")) path else "/$path"}"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method.uppercase()
                connectTimeout = 2500
                readTimeout = 2500
                doInput = true
                if (body.isNotBlank()) {
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            log("NETWORK", targetId, "${method.uppercase()} $url -> OK")
            response
        }.getOrElse {
            log("NETWORK", targetId, "${method.uppercase()} $url -> ${it.message}")
            "Couldn't reach it: ${it.message}"
        }
    }

    /**
     * Package 3 (B-057): the USB connections (interfaces) on a USB target,
     * in plain words, so the user can pick one instead of always getting
     * the first. "Connection 1" is the first claimable connection — the
     * same one the no-index call uses.
     */
    fun usbInterfacesFor(targetId: String): List<String> {
        val id = targetId.substringAfter("usb:").toIntOrNull() ?: return emptyList()
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == id } ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val eps = (0 until intf.endpointCount).map { e ->
                val ep = intf.getEndpoint(e)
                val kind = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "steady stream"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                    else -> "data"
                }
                "${if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out"} ($kind)"
            }
            out += "Connection ${i + 1}: ${eps.joinToString(", ").ifBlank { "no data points" }}"
        }
        return out
    }

    fun usbRawTransfer(targetId: String, payloadHex: String): String =
        usbRawTransferOn(targetId, payloadHex, 0)

    /**
     * Package 3 (B-057): send raw bytes over USB on a chosen connection
     * (interface). Index 0 is the first connection that can be claimed —
     * the same one the no-index call uses — so existing behaviour is
     * unchanged. An out-of-range index is refused in plain words.
     */
    fun usbRawTransfer(targetId: String, payloadHex: String, interfaceIndex: Int): String {
        val interfaces = usbInterfacesFor(targetId)
        if (interfaces.isEmpty()) return "That isn't a USB device"
        if (interfaceIndex !in interfaces.indices) {
            return "That connection isn't on this device — it has ${interfaces.size}."
        }
        return usbRawTransferOn(targetId, payloadHex, interfaceIndex)
    }

    private fun usbRawTransferOn(targetId: String, payloadHex: String, interfaceIndex: Int): String {
        val id = targetId.substringAfter("usb:").toIntOrNull() ?: return "That isn't a USB device"
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == id } ?: return "USB device not found"
        if (!usbManager.hasPermission(device)) {
            log("USB", targetId, "AUTH REQUIRED: USB permission")
            return "Permission needed — approve it first, then try again."
        }
        val payload = payloadHex.hexToBytes()
            ?: return "That isn't valid hex — use pairs like 41 54."
        val connection = usbManager.openDevice(device) ?: return "Couldn't open the USB device."
        return connection.useWithResult(device, interfaceIndex) { conn, intf, outEndpoint, inEndpoint ->
            val outResult = if (outEndpoint != null) conn.bulkTransfer(outEndpoint, payload, payload.size, 1000) else -1
            val readBuf = ByteArray(64)
            val inResult = if (inEndpoint != null) conn.bulkTransfer(inEndpoint, readBuf, readBuf.size, 1000) else -1
            val inData = if (inResult > 0) readBuf.copyOf(inResult).toHexString() else ""
            log("USB", targetId, "OUT=$outResult IN=$inResult")
            "Sent $outResult, received $inResult. Data: $inData"
        }
    }

    private inline fun <T> UsbDeviceConnection.useWithResult(
        device: UsbDevice,
        interfaceIndex: Int = 0,
        block: (UsbDeviceConnection, UsbInterface, UsbEndpoint?, UsbEndpoint?) -> T
    ): T {
        // Claimable connections in order; the index picks one (0 = the
        // first claimable — exactly what the old code picked). Connections
        // before the pick are claimed then released straight away.
        var chosenInterface: UsbInterface? = null
        var seenClaimable = 0
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (claimInterface(intf, true)) {
                if (seenClaimable == interfaceIndex) {
                    chosenInterface = intf
                    break
                }
                seenClaimable++
                releaseInterface(intf)
            }
        }
        val intf = chosenInterface ?: throw IllegalStateException("No claimable USB interface")
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (e in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(e)
            if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
            if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
        }
        return try {
            block(this, intf, outEp, inEp)
        } finally {
            releaseInterface(intf)
            close()
        }
    }

    fun saveBaseline() {
        val snapshot = BaselineSnapshot(
            timestampMs = System.currentTimeMillis(),
            targets = allTargets.associate { target ->
                target.id to BaselineTarget(
                    id = target.id,
                    name = target.name,
                    type = target.type,
                    fingerprint = target.identity.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" },
                    rssi = target.rssi
                )
            }
        )
        baselineStore.save(snapshot)
        log("BASELINE", null, "Baseline saved (${snapshot.targets.size} targets)")
    }

    fun compareWithBaseline() {
        val baseline = baselineStore.load() ?: run {
            baselineDiff = BaselineDiff()
            log("BASELINE", null, "No baseline saved")
            return
        }
        val current = allTargets.associateBy { it.id }

        val newTargets = current.keys.filterNot { baseline.targets.containsKey(it) }
        val missingTargets = baseline.targets.keys.filterNot { current.containsKey(it) }

        val changed = mutableListOf<String>()
        val signalChanged = mutableListOf<String>()

        current.forEach { (id, now) ->
            val old = baseline.targets[id] ?: return@forEach
            val nowFingerprint = now.identity.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }
            if (old.fingerprint != nowFingerprint) changed += id

            val oldRssi = old.rssi
            val nowRssi = now.rssi
            if (oldRssi != null && nowRssi != null && abs(oldRssi - nowRssi) >= 6) {
                signalChanged += id
            }
        }

        baselineDiff = BaselineDiff(
            newTargets = newTargets,
            missingTargets = missingTargets,
            changedTargets = changed,
            signalChangedTargets = signalChanged
        )
        uiTick++
        log("BASELINE", null, "Diff: new=${newTargets.size} missing=${missingTargets.size} changed=${changed.size} signal=${signalChanged.size}")
    }

    /**
     * Event-bus helpers: every real tool result in this ViewModel flows to
     * the intelligence engine through these. Never throws, never blocks —
     * a failed publish is dropped, never the tool's result.
     */
    private fun pubOp(id: String, ok: Boolean, summary: String) {
        try {
            IntelEventBus.publish(IntelEvent.ToolOpResult(id, ok, summary.take(1500)))
        } catch (_: Exception) {
        }
    }

    private fun pubErr(source: String, message: String) {
        try {
            IntelEventBus.publish(IntelEvent.IntelError(source, message.take(500)))
        } catch (_: Exception) {
        }
    }

    fun selectTarget(id: String?) {
        selectedTargetId = id
        // Event bus: a real selection of a real scanned target.
        if (id != null) {
            try {
                targets[id]?.let { t ->
                    IntelEventBus.publish(
                        IntelEvent.BleTargetSelected(
                            deviceId = t.id,
                            name = t.name,
                            rssi = t.rssi,
                            advertised = t.identity.entries
                                .joinToString("; ") { "${it.key}=${it.value}" }
                                .take(400),
                            likely = t.likely,
                            confidence = t.confidence
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun selectedTarget(): ScanTarget? = selectedTargetId?.let { targets[it] }

    fun refreshUsbAndSensorsWatch() {
        inspectUsbNow()
        val sensorTargets = allTargets.filter { it.type == TargetType.SENSOR }
        sensorTargets.forEach { target ->
            upsertTarget(target.copy(watchValues = target.watchValues + ("audio" to audioSpectrumSummary), lastSeenMs = System.currentTimeMillis()))
        }
    }

    fun exportLogsToFile(): File? {
        return runCatching {
            val lines = logs.value
            val outputDir = File(appContext.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val file = File(outputDir, "ghost_tap_logs_${System.currentTimeMillis()}.txt")
            val text = buildString {
                lines.reversed().forEach { l ->
                    append("${l.timestampMs} [${l.category}] (${l.targetId ?: "-"}) ${l.message}\n")
                }
            }
            file.writeText(text)
            log("LOG", null, "Exported logs to ${file.absolutePath}")
            file
        }.getOrNull()
    }

    fun clearLogs() {
        viewModelScope.launch {
            db.logDao().clearAll()
        }
    }

    private fun upsertTarget(target: ScanTarget) {
        targets[target.id] = target
        uiTick++
    }

    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(appContext, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun log(category: String, targetId: String?, message: String) {
        viewModelScope.launch {
            db.logDao().insert(
                LogEntry(
                    timestampMs = System.currentTimeMillis(),
                    targetId = targetId,
                    category = category,
                    message = message
                )
            )
        }
    }

    override fun onCleared() {
        stopScan()
        runCatching { appContext.unregisterReceiver(usbPermissionReceiver) }
        if (bondReceiverRegistered) runCatching { appContext.unregisterReceiver(bondReceiver) }
        gattMap.values.forEach { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        gattMap.clear()
        super.onCleared()
    }
}
