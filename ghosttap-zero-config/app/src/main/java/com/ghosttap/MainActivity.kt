package com.ghosttap

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ghosttap.core.IntelEngine
import com.ghosttap.core.esp32.VirtualEsp32Client
import com.ghosttap.data.IntelDb
import com.ghosttap.ui.GhostTapApp

class MainActivity : ComponentActivity() {
    // Framework-managed ViewModel: survives rotation, onCleared() actually runs.
    private val vm: GhostTapViewModel by viewModels()
    private val usbVm: UsbUartViewModel by viewModels()
    private val netVm: NetworkToolsViewModel by viewModels()
    private val visualIntelVm: VisualIntelViewModel by viewModels()
    // IntelViewModel takes constructor args, so it needs a small factory.
    // Built after the other VMs exist; the engine registers every capability once.
    private val intelVm: IntelViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val engine = IntelEngine(vm, netVm, usbVm, visualIntelVm, IntelDb.get(application))
                return IntelViewModel(application, engine) as T
            }
        }
    }
    private var nfcAdapter: NfcAdapter? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys.toList()
        vm.onPermissionsResult(denied)
    }

    // Firmware file picker for USB UART / ESP32 flashing.
    // Uses the Activity-level API (same pattern as permissionLauncher above),
    // not the Compose rememberLauncherForActivityResult (which broke the build).
    private val firmwarePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            var name = "firmware.bin"
            var size = 0L
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                        if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                    }
                }
            } catch (_: Exception) {}
            // Package 5 (A-30) — keep the picked file usable across
            // sessions for the home-screen shortcut. GetContent URIs may
            // not support it, so a refusal is swallowed, never fatal.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            usbVm.selectFirmware(uri, name, size)
        }
    }

    // Gallery picker for Visual Intel photo analysis.
    // Activity-level launcher (same pattern as firmwarePickerLauncher above),
    // not the Compose rememberLauncherForActivityResult (which broke the build).
    private val galleryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            visualIntelVm.analyzeGalleryImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        vm.handleNfcIntent(intent)
        val vesp32Client = VirtualEsp32Client(applicationContext)
        intelVm.attachVesp32(vesp32Client)

        setContent {
            GhostTapApp(
                viewModel = vm,
                usbViewModel = usbVm,
                netViewModel = netVm,
                visualIntelVm = visualIntelVm,
                vesp32Client = vesp32Client,
                onNeedPermissions = { requestRuntimePermissions() },
                onSelectFirmware = { firmwarePickerLauncher.launch("application/octet-stream") },
                onPickImage = { galleryPickerLauncher.launch("image/*") },
                intelVm = intelVm
            )
        }

        requestRuntimePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleNfcIntent(intent)
    }

    /**
     * While GHOST TAP is open it owns the NFC reader outright (reader mode).
     * Taps go straight to the app — no system popup, no "Open link?" dialog,
     * no other app stealing the tag. Released when the app goes to background.
     */
    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this,
            { tag: Tag -> runOnUiThread { vm.handleNfcTag(tag) } },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE,
            null
        )
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        super.onPause()
    }

    private fun requestRuntimePermissions() {
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            vm.onPermissionsResult(emptyList())
        }
    }
}
