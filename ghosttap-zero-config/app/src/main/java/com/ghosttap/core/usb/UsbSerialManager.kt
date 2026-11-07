package com.ghosttap.core.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Manages USB device detection, permission, and serial driver lifecycle.
 */
class UsbSerialManager(private val context: Context) {
    companion object {
        private const val TAG = "UsbSerialManager"
        const val ACTION_USB_PERMISSION = "com.ghosttap.USB_PERMISSION"
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    var driver: UsbSerialDriver? = null
        private set

    var currentDevice: UsbDevice? = null
        private set

    var permissionGranted = false
        private set

    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "Permission ${if (granted) "GRANTED" else "DENIED"} for ${device?.deviceName}")
                if (device == currentDevice) {
                    permissionGranted = granted
                    permissionCallback?.invoke(granted)
                    permissionCallback = null
                }
            }
        }
    }

    private var receiverRegistered = false

    fun register() {
        if (!receiverRegistered) {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(permissionReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    fun unregister() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    /**
     * Scan for supported USB-UART bridges.
     */
    fun findSupportedDevices(): List<UsbDevice> {
        val result = mutableListOf<UsbDevice>()
        for (device in usbManager.deviceList.values) {
            val driver = UsbDriverRegistry.findDriver(device)
            if (driver != null) {
                result.add(device)
            }
        }
        return result
    }

    /**
     * Devices plugged in that no driver knows (Package 5, D-01/D-06).
     * Reported so the user gets "I see something I don't know how to
     * talk to, and here is its code" instead of silence — an
     * unsupported chip is no longer invisible.
     */
    fun findUnsupportedDevices(): List<UsbDevice> {
        val result = mutableListOf<UsbDevice>()
        for (device in usbManager.deviceList.values) {
            if (UsbDriverRegistry.findDriver(device) == null) {
                result.add(device)
            }
        }
        return result
    }

    /**
     * Get info about a USB device for display.
     */
    fun getDeviceInfo(device: UsbDevice): UsbDeviceInfo {
        val driver = UsbDriverRegistry.findDriver(device)
        val manufacturer = try {
            device.manufacturerName ?: "Unknown"
        } catch (_: Exception) { "Unknown" }
        val product = try {
            device.productName ?: "Unknown"
        } catch (_: Exception) { "Unknown" }
        return UsbDeviceInfo(
            device = device,
            manufacturer = manufacturer,
            product = product,
            vendorId = device.vendorId,
            productId = device.productId,
            driverName = driver?.driverName ?: "Unsupported",
            hasPermission = usbManager.hasPermission(device)
        )
    }

    /**
     * Request USB permission for a device.
     */
    fun requestPermission(device: UsbDevice, callback: (Boolean) -> Unit) {
        currentDevice = device
        permissionCallback = callback
        if (usbManager.hasPermission(device)) {
            permissionGranted = true
            callback(true)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), flags
        )
        usbManager.requestPermission(device, intent)
    }

    /**
     * Open a serial connection to the device.
     * Must have permission already.
     */
    fun openDevice(device: UsbDevice, baudRate: Int): Boolean {
        val drv = UsbDriverRegistry.findDriver(device)
        if (drv == null) {
            Log.e(TAG, "No driver for device")
            return false
        }
        if (!usbManager.hasPermission(device)) {
            Log.e(TAG, "No permission for device")
            return false
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "Failed to open device connection")
            return false
        }

        // Create a fresh driver instance of the same type the registry
        // matched — the registry holds shared prototypes, and open()
        // keeps per-connection state, so a fresh instance is opened.
        val newDriver: UsbSerialDriver = when (drv) {
            is Cp210xDriver -> Cp210xDriver()
            is Ch340Driver -> Ch340Driver()
            else -> {
                Log.e(TAG, "Unknown driver type")
                connection.close()
                return false
            }
        }

        if (!newDriver.open(device, connection, baudRate)) {
            Log.e(TAG, "Driver open failed")
            connection.close()
            return false
        }

        // Close old driver if any
        driver?.close()

        driver = newDriver
        currentDevice = device
        permissionGranted = true
        Log.i(TAG, "Device opened: ${drv.driverName}")
        return true
    }

    fun closeDevice() {
        try {
            driver?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        } finally {
            driver = null
            // Keep currentDevice for reconnect
        }
    }

    fun isConnected(): Boolean = driver?.isOpen == true
}

data class UsbDeviceInfo(
    val device: UsbDevice,
    val manufacturer: String,
    val product: String,
    val vendorId: Int,
    val productId: Int,
    val driverName: String,
    val hasPermission: Boolean
) {
    fun vidPidString(): String = String.format("%04X:%04X", vendorId, productId)
}
