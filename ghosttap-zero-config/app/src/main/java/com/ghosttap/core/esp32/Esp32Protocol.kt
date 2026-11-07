package com.ghosttap.core.esp32

import android.util.Log
import com.ghosttap.core.usb.UsbSerialDriver
import kotlin.math.min

/**
 * Espressif ESP32 Serial Bootloader Protocol implementation.
 * Based on esptool.py protocol documentation.
 *
 * Uses SLIP framing for all communications.
 * Does NOT guess chip models - reports only what the bootloader provides.
 */
class Esp32Protocol(private val driver: UsbSerialDriver) {
    companion object {
        private const val TAG = "Esp32Protocol"

        // SLIP special characters
        private val SLIP_END: Byte = 0xC0.toByte()
        private val SLIP_ESC: Byte = 0xDB.toByte()
        private val SLIP_ESC_END: Byte = 0xDC.toByte()
        private val SLIP_ESC_ESC: Byte = 0xDD.toByte()

        // ESP32 ROM commands
        private const val ESP_SYNC: Byte = 0x08
        private const val ESP_READ_REG: Byte = 0x0A
        private const val ESP_SPI_ATTACH: Byte = 0x0D
        private const val ESP_CHANGE_BAUDRATE: Byte = 0x0F
        private const val ESP_FLASH_BEGIN: Byte = 0x02
        private const val ESP_FLASH_DATA: Byte = 0x03
        private const val ESP_FLASH_END: Byte = 0x04
        private const val ESP_MEM_BEGIN: Byte = 0x05
        private const val ESP_MEM_DATA: Byte = 0x07
        private const val ESP_MEM_END: Byte = 0x06
        private const val ESP_SPI_FLASH_MD5: Byte = 0x13

        // Chip magic values (from esptool)
        private const val ESP32_MAGIC = 0x00F01D83
        private const val ESP32S2_MAGIC = 0x000007C6
        private const val ESP32S3_MAGIC = 0x00000009
        private const val ESP32C3_MAGIC = 0x6921506F
        private const val ESP32C6_MAGIC = 0x2CE0806F
        private const val ESP32H2_MAGIC: Int = -903427038 // 0xCA26CC22 as signed Int

        // Register esptool reads to identify the chip:
        // READ_REG (0x0A) of this address returns the magic value above.
        // Public so the ViewModel can read the board's ID register as its
        // own named step (Package 5, D-16) without duplicating the address.
        const val CHIP_MAGIC_REG_ADDR = 0x40001000

        // EFUSE addresses for chip info (ESP32)
        private const val ESP32_EFUSE_BASE = 0x3FF5A000
    }

    data class ChipInfo(
        val magicValue: Int,
        val chipName: String,  // Only set if magic matches known value
        val isKnown: Boolean
    )

    data class SyncResult(
        val success: Boolean,
        val message: String,
        val chipInfo: ChipInfo? = null
    )

    /**
     * SLIP encode a packet
     */
    private fun slipEncode(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(SLIP_END)
        for (b in data) {
            when (b) {
                SLIP_END -> { out.add(SLIP_ESC); out.add(SLIP_ESC_END) }
                SLIP_ESC -> { out.add(SLIP_ESC); out.add(SLIP_ESC_ESC) }
                else -> out.add(b)
            }
        }
        out.add(SLIP_END)
        return out.toByteArray()
    }

    /**
     * SLIP decode - reads until END, handles escapes
     * Returns null on timeout or error
     */
    private fun slipDecode(timeoutMs: Int): ByteArray? {
        val buffer = mutableListOf<Byte>()
        val temp = ByteArray(256)
        val startTime = System.currentTimeMillis()
        var inPacket = false
        var escaped = false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val len = driver.read(temp, 100)
            if (len > 0) {
                for (i in 0 until len) {
                    val b = temp[i]
                    if (!inPacket) {
                        if (b == SLIP_END) {
                            inPacket = true
                            buffer.clear()
                        }
                        continue
                    }
                    if (escaped) {
                        when (b) {
                            SLIP_ESC_END -> buffer.add(SLIP_END)
                            SLIP_ESC_ESC -> buffer.add(SLIP_ESC)
                            else -> buffer.add(b)
                        }
                        escaped = false
                    } else if (b == SLIP_ESC) {
                        escaped = true
                    } else if (b == SLIP_END) {
                        if (buffer.isNotEmpty()) {
                            return buffer.toByteArray()
                        }
                        // Empty packet, continue
                    } else {
                        buffer.add(b)
                    }
                }
            }
        }
        return null
    }

    /**
     * Send a command and wait for response
     */
    private fun sendCommand(op: Byte, data: ByteArray = ByteArray(0), timeoutMs: Int = 3000): ByteArray? {
        // Command packet: [0x00, op, len_lo, len_hi, chk(4 bytes), data...]
        val packet = mutableListOf<Byte>()
        packet.add(0x00.toByte())  // Direction
        packet.add(op)
        packet.add((data.size and 0xFF).toByte())
        packet.add(((data.size shr 8) and 0xFF).toByte())
        // Checksum (4 bytes, 0xEF for most commands)
        packet.add(0xEF.toByte())
        packet.add(0xEF.toByte())
        packet.add(0xEF.toByte())
        packet.add(0xEF.toByte())
        packet.addAll(data.toList())

        val encoded = slipEncode(packet.toByteArray())
        val written = driver.write(encoded, 1000)
        if (written != encoded.size) {
            Log.e(TAG, "Write failed: $written/${encoded.size}")
            return null
        }

        return slipDecode(timeoutMs)
    }

    /**
     * Attempt to synchronize with ESP32 bootloader.
     * Returns SyncResult with chip info if successful.
     */
    fun sync(): SyncResult {
        try {
            // Flush any pending data
            val flushBuf = ByteArray(256)
            driver.read(flushBuf, 100)

            // Send SYNC: 0x07 0x07 0x12 0x20 + 32 x 0x55
            val syncData = ByteArray(36)
            syncData[0] = 0x07
            syncData[1] = 0x07
            syncData[2] = 0x12
            syncData[3] = 0x20
            for (i in 4 until 36) {
                syncData[i] = 0x55
            }

            // Try multiple times (esptool does 7 attempts)
            for (attempt in 1..7) {
                Log.d(TAG, "SYNC attempt $attempt")
                val response = sendCommand(ESP_SYNC, syncData, 1000)
                if (response != null && response.size >= 10) {
                    // Check response: should be 8 bytes of 0x55 or similar
                    // Response format: [0x01, op, len_lo, len_hi, val(4), status(2)]
                    Log.i(TAG, "SYNC successful on attempt $attempt")
                    // Try to get chip magic
                    val chipInfo = readChipMagic()
                    return SyncResult(
                        success = true,
                        message = "ESP32 responded",
                        chipInfo = chipInfo
                    )
                }
                Thread.sleep(100)
            }

            return SyncResult(
                success = false,
                message = "No response from ESP32"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            return SyncResult(
                success = false,
                message = "Sync error: ${e.message}"
            )
        }
    }

    /**
     * Read chip magic value to identify chip (does not guess).
     *
     * Same as esptool: READ_REG the magic register (0x40001000) and
     * match the value against the known magic constants. The value
     * actually read is reported exactly; a value that matches nothing
     * stays "Unknown", and a read that fails stays "Unknown" too —
     * sync already proved the board answers, the type just could not
     * be read, so no chip name is ever claimed without evidence.
     *
     * Public (Package 5, D-16) so the ViewModel can read the board's
     * ID register as its own named step. Needs a synced bootloader
     * session, exactly like [sync] uses it.
     */
    fun readChipMagic(): ChipInfo? {
        val magic = readReg(CHIP_MAGIC_REG_ADDR)
        if (magic == null) {
            Log.w(TAG, "Read chip magic failed — board type unknown")
            return ChipInfo(
                magicValue = 0,
                chipName = "Unknown",
                isKnown = false
            )
        }
        val chipName = when (magic) {
            ESP32_MAGIC -> "ESP32"
            ESP32S2_MAGIC -> "ESP32-S2"
            ESP32S3_MAGIC -> "ESP32-S3"
            ESP32C3_MAGIC -> "ESP32-C3"
            ESP32C6_MAGIC -> "ESP32-C6"
            ESP32H2_MAGIC -> "ESP32-H2"
            else -> null
        }
        return if (chipName != null) {
            Log.i(TAG, "Chip identified: $chipName (magic %08x)".format(magic))
            ChipInfo(
                magicValue = magic,
                chipName = chipName,
                isKnown = true
            )
        } else {
            Log.w(TAG, "Unknown chip magic: %08x".format(magic))
            ChipInfo(
                magicValue = magic,
                chipName = "Unknown",
                isKnown = false
            )
        }
    }

    /**
     * Read back the MD5 of a flash region (ESP_SPI_FLASH_MD5, 0x13) —
     * the post-flash check (Package 5, D-19 / F-34). Returns the
     * 16-byte digest as lowercase hex, or null when the board doesn't
     * answer the check.
     *
     * The digest is the last 16 bytes of the reply: the ROM answers
     * with its 10-byte header ([0x01, op, len, value(4), status(2)])
     * followed by the 16-byte digest, so the digest is the tail
     * whether the reply carries the full header or not. Anything
     * shorter than a header plus a digest is not a digest — null,
     * never a guess.
     */
    fun readFlashMd5(address: Int, size: Int): String? {
        val data = ByteArray(16)
        putInt32LE(data, 0, address)
        putInt32LE(data, 4, size)
        // Bytes 8..15 stay zero (reserved, as esptool sends them).
        val response = sendCommand(ESP_SPI_FLASH_MD5, data, 5000)
        if (response != null && response.size >= 26) {
            return response.takeLast(16).joinToString("") { "%02x".format(it) }
        }
        Log.w(TAG, "SPI_FLASH_MD5: short/no reply (${response?.size})")
        return null
    }

    private fun putInt32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /**
     * Read a 32-bit register
     */
    fun readReg(address: Int): Int? {
        val data = ByteArray(4)
        data[0] = (address and 0xFF).toByte()
        data[1] = ((address shr 8) and 0xFF).toByte()
        data[2] = ((address shr 16) and 0xFF).toByte()
        data[3] = ((address shr 24) and 0xFF).toByte()

        val response = sendCommand(ESP_READ_REG, data, 2000)
        if (response != null && response.size >= 10) {
            // Response: [0x01, 0x0A, 0x04, 0x00, val0, val1, val2, val3, status0, status1]
            val value = (response[4].toInt() and 0xFF) or
                       ((response[5].toInt() and 0xFF) shl 8) or
                       ((response[6].toInt() and 0xFF) shl 16) or
                       ((response[7].toInt() and 0xFF) shl 24)
            return value
        }
        return null
    }

    /**
     * Perform DTR/RTS sequence to enter bootloader download mode.
     * Standard Espressif sequence for boards with BOOT/EN transistor circuit:
     * - DTR controls GPIO0 (via transistor)
     * - RTS controls EN/RESET (via transistor)
     *
     * Sequence (from esptool):
     * 1. DTR=False, RTS=True (GPIO0=HIGH, EN=LOW - reset)
     * 2. Delay
     * 3. DTR=True, RTS=False (GPIO0=LOW, EN=HIGH - boot into download)
     * 4. Delay
     * 5. DTR=False (GPIO0=HIGH, release)
     *
     * Returns true if sequence was executed (does NOT guarantee ESP32 entered download mode)
     */
    fun enterDownloadMode(): Boolean {
        try {
            Log.i(TAG, "Entering download mode sequence...")

            // Step 1: Assert RTS (EN low = reset), deassert DTR (GPIO0 high)
            driver.setDtr(false)
            driver.setRts(true)
            Thread.sleep(100)

            // Step 2: Assert DTR (GPIO0 low), deassert RTS (EN high = run)
            driver.setDtr(true)
            driver.setRts(false)
            Thread.sleep(100)

            // Step 3: Deassert DTR (GPIO0 high, but already in download mode)
            driver.setDtr(false)
            Thread.sleep(100)

            Log.i(TAG, "Download mode sequence complete")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Download mode sequence failed", e)
            return false
        }
    }

    /**
     * Reset ESP32 (normal boot)
     */
    fun resetBoard(): Boolean {
        try {
            // RTS controls EN: assert = reset, deassert = run
            driver.setRts(true)
            Thread.sleep(100)
            driver.setRts(false)
            Thread.sleep(100)
            Log.i(TAG, "Reset sequence complete")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Reset failed", e)
            return false
        }
    }
}
