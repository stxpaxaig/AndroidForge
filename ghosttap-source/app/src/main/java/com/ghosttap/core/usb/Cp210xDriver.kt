package com.ghosttap.core.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * Silicon Labs CP210x USB-UART bridge driver (CP2102N, CP2102, CP2104, etc.)
 * Implements the USB control protocol directly via Android USB Host API.
 * Based on Silicon Labs AN571 and the Linux cp210x driver.
 */
class Cp210xDriver : UsbSerialDriver {
    override val driverName = "CP210x (Silicon Labs)"

    // Silicon Labs VID
    override val supportedVendorIds = intArrayOf(0x10C4)
    // Common CP210x PIDs
    override val supportedProductIds = intArrayOf(
        0xEA60, // CP2102/CP2109
        0xEA61, // CP2102N
        0xEA63, // CP2103
        0xEA70, // CP2105
        0xEA71, // CP2108
        0xF001, 0xF002, 0xF003, 0xF004, 0xF005 // Custom
    )

    companion object {
        private const val TAG = "Cp210xDriver"

        // CP210x vendor requests
        private const val CP210X_IFC_ENABLE = 0x00
        private const val CP210X_SET_BAUDRATE = 0x1E
        private const val CP210X_SET_LINE_CTL = 0x03
        private const val CP210X_SET_MHS = 0x07
        private const val CP210X_GET_MDMSTS = 0x08
        private const val CP210X_SET_FLOW = 0x13

        // MHS bits
        private const val MHS_DTR_ON = 0x0001
        private const val MHS_DTR_OFF = 0x0000
        private const val MHS_RTS_ON = 0x0002
        private const val MHS_RTS_OFF = 0x0000
        private const val MHS_ALL_OFF = 0x0000

        // USB request types
        private const val REQTYPE_OUT = 0x41 // Vendor, OUT, Interface
        private const val REQTYPE_IN = 0xC1  // Vendor, IN, Interface
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null
    private var dtrAsserted = false
    private var rtsAsserted = false
    override var isOpen = false
        private set

    override fun open(device: UsbDevice, connection: UsbDeviceConnection, baudRate: Int): Boolean {
        try {
            // Find the interface (usually interface 0)
            var iface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                // CP210x uses vendor-specific class
                iface = intf
                break
            }
            if (iface == null) {
                Log.e(TAG, "No interface found")
                return false
            }

            if (!connection.claimInterface(iface, true)) {
                Log.e(TAG, "Failed to claim interface")
                return false
            }

            // Find bulk endpoints
            var readEp: UsbEndpoint? = null
            var writeEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        readEp = ep
                    } else {
                        writeEp = ep
                    }
                }
            }

            if (readEp == null || writeEp == null) {
                Log.e(TAG, "Bulk endpoints not found")
                connection.releaseInterface(iface)
                return false
            }

            this.connection = connection
            this.usbInterface = iface
            this.readEndpoint = readEp
            this.writeEndpoint = writeEp

            // CP210x initialization sequence
            // 1. Enable interface
            if (controlOut(CP210X_IFC_ENABLE, 0x0001, 0, null) < 0) {
                Log.e(TAG, "IFC_ENABLE failed")
                close()
                return false
            }

            // 2. Set baud rate
            if (!setParameters(baudRate, 8, 1, 0)) {
                Log.e(TAG, "Set parameters failed")
                close()
                return false
            }

            // 3. Set DTR/RTS off initially
            updateMhs()

            isOpen = true
            Log.i(TAG, "CP210x opened at $baudRate baud")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Open failed", e)
            close()
            return false
        }
    }

    override fun close() {
        try {
            connection?.let { conn ->
                // Disable interface
                try {
                    controlOut(CP210X_IFC_ENABLE, 0x0000, 0, null)
                } catch (_: Exception) {}
                usbInterface?.let { conn.releaseInterface(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        } finally {
            connection = null
            usbInterface = null
            readEndpoint = null
            writeEndpoint = null
            isOpen = false
            dtrAsserted = false
            rtsAsserted = false
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: return -1
        val ep = readEndpoint ?: return -1
        return try {
            conn.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "Read failed", e)
            -1
        }
    }

    override fun write(data: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: return -1
        val ep = writeEndpoint ?: return -1
        return try {
            conn.bulkTransfer(ep, data, data.size, timeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
            -1
        }
    }

    override fun setDtr(asserted: Boolean): Boolean {
        dtrAsserted = asserted
        return updateMhs()
    }

    override fun setRts(asserted: Boolean): Boolean {
        rtsAsserted = asserted
        return updateMhs()
    }

    private fun updateMhs(): Boolean {
        var value = MHS_ALL_OFF
        if (dtrAsserted) value = value or MHS_DTR_ON
        if (rtsAsserted) value = value or MHS_RTS_ON
        // CP210X_SET_MHS: wValue = control bits, wIndex = 0
        val result = controlOut(CP210X_SET_MHS, value, 0, null)
        if (result < 0) {
            Log.e(TAG, "SET_MHS failed (DTR=$dtrAsserted RTS=$rtsAsserted)")
            return false
        }
        Log.d(TAG, "MHS updated: DTR=$dtrAsserted RTS=$rtsAsserted")
        return true
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int): Boolean {
        // Set baud rate (4 bytes, little endian)
        val baudBytes = ByteArray(4)
        baudBytes[0] = (baudRate and 0xFF).toByte()
        baudBytes[1] = ((baudRate shr 8) and 0xFF).toByte()
        baudBytes[2] = ((baudRate shr 16) and 0xFF).toByte()
        baudBytes[3] = ((baudRate shr 24) and 0xFF).toByte()

        if (controlOut(CP210X_SET_BAUDRATE, 0, 0, baudBytes) < 0) {
            Log.e(TAG, "SET_BAUDRATE failed")
            return false
        }

        // Set line control: bits 0-3 = data bits, bits 4-7 = parity, bits 8-9 = stop bits
        // wValue format: (stopBits << 8) | (parity << 4) | dataBits
        // dataBits: 5-8, parity: 0=none, 1=odd, 2=even, stopBits: 0=1, 1=1.5, 2=2
        val stopBitsVal = when (stopBits) {
            1 -> 0
            2 -> 2
            else -> 0
        }
        val lineCtl = (stopBitsVal shl 8) or (parity shl 4) or dataBits
        if (controlOut(CP210X_SET_LINE_CTL, lineCtl, 0, null) < 0) {
            Log.e(TAG, "SET_LINE_CTL failed")
            return false
        }

        Log.d(TAG, "Parameters set: ${baudRate}bps ${dataBits}N${stopBits}")
        return true
    }

    private fun controlOut(request: Int, value: Int, index: Int, data: ByteArray?): Int {
        val conn = connection ?: return -1
        return try {
            conn.controlTransfer(
                REQTYPE_OUT, request, value, index,
                data, data?.size ?: 0, 1000
            )
        } catch (e: Exception) {
            Log.e(TAG, "controlOut failed: req=$request", e)
            -1
        }
    }

    @Suppress("unused")
    private fun controlIn(request: Int, value: Int, index: Int, length: Int): ByteArray? {
        val conn = connection ?: return null
        return try {
            val buffer = ByteArray(length)
            val result = conn.controlTransfer(
                REQTYPE_IN, request, value, index,
                buffer, length, 1000
            )
            if (result >= 0) buffer else null
        } catch (e: Exception) {
            Log.e(TAG, "controlIn failed: req=$request", e)
            null
        }
    }

    fun isDtrAsserted(): Boolean = dtrAsserted
    fun isRtsAsserted(): Boolean = rtsAsserted
}
