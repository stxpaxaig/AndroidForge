package com.ghosttap.core.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

/**
 * Abstract interface for USB-UART bridge drivers.
 * Implementations: Cp210xDriver (Silicon Labs), Ch340Driver (WCH), FtdiDriver (FTDI)
 */
interface UsbSerialDriver {
    val driverName: String
    val supportedVendorIds: IntArray
    val supportedProductIds: IntArray

    fun supportsDevice(device: UsbDevice): Boolean {
        val vid = device.vendorId
        val pid = device.productId
        return supportedVendorIds.contains(vid) && supportedProductIds.contains(pid)
    }

    /**
     * Open and configure the device. Must be called after USB permission granted.
     * @param device The USB device
     * @param connection Opened UsbDeviceConnection
     * @param baudRate e.g. 115200
     * @return true on success
     */
    fun open(device: UsbDevice, connection: UsbDeviceConnection, baudRate: Int): Boolean

    fun close()

    fun read(buffer: ByteArray, timeoutMs: Int): Int

    fun write(data: ByteArray, timeoutMs: Int): Int

    fun setDtr(asserted: Boolean): Boolean

    fun setRts(asserted: Boolean): Boolean

    fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int): Boolean

    val isOpen: Boolean
}

/**
 * Driver registry - add new drivers here without rewriting the feature
 */
object UsbDriverRegistry {
    private val drivers = mutableListOf<UsbSerialDriver>()

    init {
        drivers.add(Cp210xDriver())
        drivers.add(Ch340Driver())
        // Future: drivers.add(FtdiDriver())
    }

    fun findDriver(device: UsbDevice): UsbSerialDriver? {
        return drivers.firstOrNull { it.supportsDevice(device) }
    }

    fun getAllDrivers(): List<UsbSerialDriver> = drivers.toList()
}
