package com.ghosttap.core.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * WCH CH340/CH341 USB-UART bridge driver.
 * Implements the USB control protocol directly via Android USB Host API.
 * Based on the Linux ch341 driver (drivers/usb/serial/ch341.c).
 */
class Ch340Driver : UsbSerialDriver {
    override val driverName = "CH340/CH341 (WCH)"

    // WCH VID
    override val supportedVendorIds = intArrayOf(0x1A86)
    // CH340 / CH341 PIDs
    override val supportedProductIds = intArrayOf(
        0x7523, // CH340
        0x5523  // CH341
    )

    companion object {
        private const val TAG = "Ch340Driver"

        // CH341 vendor requests
        private const val CH341_REQ_SERIAL_INIT = 0xA1
        private const val CH341_REQ_MODEM_CTRL = 0xA4
        private const val CH341_REQ_READ_VERSION = 0x5F
        private const val CH341_REQ_READ_REG = 0x95
        private const val CH341_REQ_WRITE_REG = 0x9A

        // Modem-control bits (wValue for CH341_REQ_MODEM_CTRL is inverted)
        private const val CH341_BIT_DTR = 0x20
        private const val CH341_BIT_RTS = 0x40

        // Line-control bits (0x9A to reg 0x1312 low byte region, value 'a')
        private const val LCR_ENABLE_RX = 0x80
        private const val LCR_ENABLE_TX = 0x40
        private const val LCR_MARK_SPACE = 0x20
        private const val LCR_PAR_EVEN = 0x10
        private const val LCR_ENABLE_PAR = 0x08
        private const val LCR_STOP_BITS_2 = 0x04
        private const val LCR_CS8 = 0x03
        private const val LCR_CS7 = 0x02
        private const val LCR_CS6 = 0x01
        private const val LCR_CS5 = 0x00

        // USB request types
        private const val REQTYPE_OUT = 0x41 // Vendor, OUT, Interface
        private const val REQTYPE_IN = 0xC1  // Vendor, IN, Interface

        // Baud-rate table: baud -> (value 'a' for reg 0x1312, value 'b' for reg 0x0F2C)
        // From the Linux ch341 driver, via the usb-serial-for-android
        // CH34x driver — the only rates that lineage publishes pairs for.
        // Any other rate goes through baudRateToReg1312() below instead.
        private val BAUD_TABLE = mapOf(
            2400 to Pair(0xd901, 0x0038),
            4800 to Pair(0x6402, 0x001f),
            9600 to Pair(0xb202, 0x0013),
            19200 to Pair(0xd902, 0x000d),
            38400 to Pair(0x6403, 0x000a),
            115200 to Pair(0xcc03, 0x0008)
        )

        // Baud-rate formula constants from the Linux ch341 driver
        // (drivers/usb/serial/ch341.c).
        private const val CH341_BAUDBASE_FACTOR = 1532620800
        private const val CH341_BAUDBASE_DIVMAX = 3
        // The chip's own clock for the baud-rate error check below.
        private const val CH341_OSC_FREQUENCY = 12000000
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

            // CH341 initialization sequence
            // 1. Serial init
            if (controlOut(CH341_REQ_SERIAL_INIT, 0, 0, null) < 0) {
                Log.e(TAG, "SERIAL_INIT failed")
                close()
                return false
            }

            // 2. Read version (log-only, informational)
            val version = controlIn(CH341_REQ_READ_VERSION, 0, 0, 2)
            if (version != null) {
                Log.i(TAG, "CH341 chip version: ${version.joinToString(" ") { "%02x".format(it) }}")
            } else {
                Log.w(TAG, "READ_VERSION returned nothing (continuing anyway)")
            }

            // 3. Set baud rate + line control
            if (!setParameters(baudRate, 8, 1, 0)) {
                Log.e(TAG, "Set parameters failed")
                close()
                return false
            }

            // 4. Clear DTR/RTS initially
            updateModemCtrl()

            isOpen = true
            Log.i(TAG, "CH340/CH341 opened at $baudRate baud")
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
        return updateModemCtrl()
    }

    override fun setRts(asserted: Boolean): Boolean {
        rtsAsserted = asserted
        return updateModemCtrl()
    }

    /**
     * CH341 modem control: wValue is the INVERTED set of control bits —
     * a bit set in wValue means that signal is DEASSERTED.
     */
    private fun updateModemCtrl(): Boolean {
        var value = 0
        if (dtrAsserted) value = value or CH341_BIT_DTR
        if (rtsAsserted) value = value or CH341_BIT_RTS
        val inverted = value.inv() and 0xFFFF
        val result = controlOut(CH341_REQ_MODEM_CTRL, inverted, 0, null)
        if (result < 0) {
            Log.e(TAG, "MODEM_CTRL failed (DTR=$dtrAsserted RTS=$rtsAsserted)")
            return false
        }
        Log.d(TAG, "Modem ctrl updated: DTR=$dtrAsserted RTS=$rtsAsserted")
        return true
    }

    private fun setBaudRate(baudRate: Int): Boolean {
        // Rates with a published (a, b) pair keep their exact published
        // register writes, unchanged.
        val legacy = BAUD_TABLE[baudRate]
        if (legacy != null) {
            val (a, b) = legacy
            // 0x9A writes: reg 0x1312 <- a, reg 0x0F2C <- b
            if (controlOut(CH341_REQ_WRITE_REG, a, 0x1312, null) < 0) {
                Log.e(TAG, "WRITE_REG 0x1312 failed")
                return false
            }
            if (controlOut(CH341_REQ_WRITE_REG, b, 0x0F2C, null) < 0) {
                Log.e(TAG, "WRITE_REG 0x0F2C failed")
                return false
            }
            Log.d(TAG, "Baud rate set: $baudRate (a=%04x b=%04x)".format(a, b))
            return true
        }
        // Any other rate — including the 230400 and 460800 the speed
        // buttons offer: the Linux ch341 driver computes the 0x1312
        // value by formula and never writes 0x0F2C at all, so do the
        // same. The formula path refuses rates it cannot hit closely
        // instead of silently setting a wrong speed.
        val a = baudRateToReg1312(baudRate)
        if (a == null) {
            Log.e(TAG, "Unsupported baud rate for CH340: $baudRate (table: ${BAUD_TABLE.keys.sorted()}; other rates only when the formula hits within 2%)")
            return false
        }
        if (controlOut(CH341_REQ_WRITE_REG, a, 0x1312, null) < 0) {
            Log.e(TAG, "WRITE_REG 0x1312 failed")
            return false
        }
        Log.d(TAG, "Baud rate set: $baudRate (a=%04x, by formula)".format(a))
        return true
    }

    /**
     * The 0x1312 value for any baud rate, computed exactly the way the
     * Linux ch341 driver does it: factor = CH341_BAUDBASE_FACTOR /
     * baud, divisor 3 down to 0 while factor > 0xfff0, then
     * a = (0x10000 - factor & 0xff00) | divisor. This reproduces every
     * BAUD_TABLE 'a' value exactly (2400 -> 0xd901, 4800 -> 0x6402,
     * 9600 -> 0xb202, 19200 -> 0xd902, 38400 -> 0x6403,
     * 115200 -> 0xcc03), and gives 230400 -> 0xe603 and
     * 460800 -> 0xf303, each 0.16% off the wanted rate — the same error
     * as the published 115200 entry.
     *
     * Returns null when the formula cannot hit the rate within 2%
     * (about the receive tolerance the CH341 datasheet allows — the
     * chip itself supports 50bps to 2Mbps, but this divisor scheme
     * cannot land every rate, e.g. 921600 lands ~7% off).
     */
    private fun baudRateToReg1312(baudRate: Int): Int? {
        if (baudRate <= 0) return null
        var factor = CH341_BAUDBASE_FACTOR / baudRate
        var divisor = CH341_BAUDBASE_DIVMAX
        while (factor > 0xfff0 && divisor > 0) {
            factor = factor shr 3
            divisor--
        }
        if (factor > 0xfff0) return null
        factor = 0x10000 - factor
        val a = (factor and 0xff00) or divisor
        // The rate the chip will actually run:
        // baud = 12000000 / ((1 << (10 - 3 * divisor)) * (256 - msb(a))).
        val msb = (a shr 8) and 0xFF
        val denom = (1 shl (10 - 3 * divisor)) * (256 - msb)
        if (denom <= 0) return null
        val actual = CH341_OSC_FREQUENCY.toDouble() / denom
        if (kotlin.math.abs(actual - baudRate) / baudRate > 0.02) return null
        return a
    }

    private fun setLineControl(dataBits: Int, stopBits: Int, parity: Int): Boolean {
        val csBits = when (dataBits) {
            5 -> LCR_CS5
            6 -> LCR_CS6
            7 -> LCR_CS7
            8 -> LCR_CS8
            else -> {
                Log.e(TAG, "Unsupported data bits for CH340: $dataBits")
                return false
            }
        }
        var lcr = LCR_ENABLE_RX or LCR_ENABLE_TX or csBits
        when (parity) {
            0 -> { /* none */ }
            1 -> lcr = lcr or LCR_ENABLE_PAR // odd
            2 -> lcr = lcr or (LCR_ENABLE_PAR or LCR_PAR_EVEN) // even
            else -> {
                Log.e(TAG, "Unsupported parity for CH340: $parity")
                return false
            }
        }
        if (stopBits == 2) {
            lcr = lcr or LCR_STOP_BITS_2
        } else if (stopBits != 1) {
            Log.e(TAG, "Unsupported stop bits for CH340: $stopBits")
            return false
        }
        // Line control is written via 0x9A to register 0x2518
        // (per the Linux ch341 driver).
        if (controlOut(CH341_REQ_WRITE_REG, lcr, 0x2518, null) < 0) {
            Log.e(TAG, "WRITE_REG line control failed")
            return false
        }
        Log.d(TAG, "Line control set: lcr=%02x".format(lcr))
        return true
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int): Boolean {
        if (!setBaudRate(baudRate)) return false
        if (!setLineControl(dataBits, stopBits, parity)) return false
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
