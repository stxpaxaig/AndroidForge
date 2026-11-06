package com.ghosttap.core.esp32

import android.util.Log
import com.ghosttap.core.usb.UsbSerialDriver
import java.io.InputStream
import kotlin.math.min

/**
 * ESP32 firmware flashing via serial bootloader protocol.
 * Implements FLASH_BEGIN, FLASH_DATA, FLASH_END commands.
 */
class Esp32Flasher(private val driver: UsbSerialDriver) {
    companion object {
        private const val TAG = "Esp32Flasher"

        private const val ESP_FLASH_BEGIN: Byte = 0x02
        private const val ESP_FLASH_DATA: Byte = 0x03
        private const val ESP_FLASH_END: Byte = 0x04

        private const val FLASH_BLOCK_SIZE = 0x400  // 1KB blocks
        private const val FLASH_SECTOR_SIZE = 0x1000 // 4KB sectors
    }

    data class FlashProgress(
        val stage: String,
        val bytesWritten: Long,
        val totalBytes: Long,
        val percent: Int
    )

    interface ProgressCallback {
        fun onProgress(progress: FlashProgress)
    }

    /**
     * Flash a firmware binary to the ESP32.
     * @param inputStream Firmware .bin file
     * @param fileSize Size in bytes
     * @param offset Flash offset (usually 0x10000 for app, 0x0 for bootloader)
     * @param callback Progress updates
     * @return true on success
     */
    fun flash(
        inputStream: InputStream,
        fileSize: Long,
        offset: Int = 0x10000,
        callback: ProgressCallback? = null
    ): Boolean {
        try {
            val protocol = Esp32Protocol(driver)

            // Step 1: Sync
            callback?.onProgress(FlashProgress("Connecting to ESP32...", 0, fileSize, 0))
            val syncResult = protocol.sync()
            if (!syncResult.success) {
                Log.e(TAG, "Sync failed: ${syncResult.message}")
                return false
            }

            // Step 2: FLASH_BEGIN
            callback?.onProgress(FlashProgress("Preparing flash...", 0, fileSize, 0))
            if (!flashBegin(fileSize, offset)) {
                Log.e(TAG, "FLASH_BEGIN failed")
                return false
            }

            // Step 3: FLASH_DATA blocks
            val buffer = ByteArray(FLASH_BLOCK_SIZE)
            var bytesWritten = 0L
            var seq = 0

            while (bytesWritten < fileSize) {
                val toRead = min(FLASH_BLOCK_SIZE.toLong(), fileSize - bytesWritten).toInt()
                val read = inputStream.read(buffer, 0, toRead)
                if (read <= 0) break

                // Pad last block with 0xFF if needed
                val block = if (read < FLASH_BLOCK_SIZE) {
                    val padded = ByteArray(FLASH_BLOCK_SIZE) { 0xFF.toByte() }
                    System.arraycopy(buffer, 0, padded, 0, read)
                    padded
                } else {
                    buffer
                }

                if (!flashData(block, seq)) {
                    Log.e(TAG, "FLASH_DATA failed at seq $seq")
                    return false
                }

                bytesWritten += read
                seq++

                val percent = ((bytesWritten * 100) / fileSize).toInt()
                callback?.onProgress(FlashProgress(
                    "Writing firmware...",
                    bytesWritten,
                    fileSize,
                    percent
                ))
            }

            // Step 4: FLASH_END
            callback?.onProgress(FlashProgress("Finishing...", bytesWritten, fileSize, 100))
            if (!flashEnd()) {
                Log.e(TAG, "FLASH_END failed")
                return false
            }

            Log.i(TAG, "Flash complete: $bytesWritten bytes")
            callback?.onProgress(FlashProgress("Complete!", bytesWritten, fileSize, 100))
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Flash failed", e)
            return false
        }
    }

    private fun flashBegin(size: Long, offset: Int): Boolean {
        // FLASH_BEGIN: [erase_size(4), packet_count(4), block_size(4), offset(4)]
        val eraseSize = ((size + FLASH_SECTOR_SIZE - 1) / FLASH_SECTOR_SIZE) * FLASH_SECTOR_SIZE
        val packetCount = ((size + FLASH_BLOCK_SIZE - 1) / FLASH_BLOCK_SIZE).toInt()

        val data = ByteArray(16)
        putInt32(data, 0, eraseSize.toInt())
        putInt32(data, 4, packetCount)
        putInt32(data, 8, FLASH_BLOCK_SIZE)
        putInt32(data, 12, offset)

        val response = sendFlashCommand(ESP_FLASH_BEGIN, data)
        return response != null
    }

    private fun flashData(block: ByteArray, seq: Int): Boolean {
        // FLASH_DATA: [size(4), seq(4), 0(4), 0(4), data...]
        val data = ByteArray(16 + block.size)
        putInt32(data, 0, block.size)
        putInt32(data, 4, seq)
        putInt32(data, 8, 0)
        putInt32(data, 12, 0)
        System.arraycopy(block, 0, data, 16, block.size)

        val response = sendFlashCommand(ESP_FLASH_DATA, data, timeoutMs = 5000)
        return response != null
    }

    private fun flashEnd(): Boolean {
        // FLASH_END: [reboot(4)] - 0 = no reboot, 1 = reboot
        val data = ByteArray(4)
        putInt32(data, 0, 1)  // Reboot after flash

        val response = sendFlashCommand(ESP_FLASH_END, data)
        return response != null
    }

    private fun sendFlashCommand(op: Byte, data: ByteArray, timeoutMs: Int = 3000): ByteArray? {
        // Use SLIP framing (simplified - reuse from Esp32Protocol)
        // For now, delegate to a helper
        // In a full implementation, this would share the SLIP code

        // SLIP encode
        val packet = mutableListOf<Byte>()
        packet.add(0x00.toByte())
        packet.add(op)
        packet.add((data.size and 0xFF).toByte())
        packet.add(((data.size shr 8) and 0xFF).toByte())
        packet.add(0xEF.toByte())
        packet.add(0xEF.toByte())
        packet.add(0xEF.toByte())
        packet.add(0xEF.toByte())
        packet.addAll(data.toList())

        val encoded = slipEncode(packet.toByteArray())
        driver.write(encoded, 1000)

        return slipDecode(timeoutMs)
    }

    private fun slipEncode(data: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        out.add(0xC0.toByte())
        for (b in data) {
            when (b) {
                0xC0.toByte() -> { out.add(0xDB.toByte()); out.add(0xDC.toByte()) }
                0xDB.toByte() -> { out.add(0xDB.toByte()); out.add(0xDD.toByte()) }
                else -> out.add(b)
            }
        }
        out.add(0xC0.toByte())
        return out.toByteArray()
    }

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
                        if (b == 0xC0.toByte()) {
                            inPacket = true
                            buffer.clear()
                        }
                        continue
                    }
                    if (escaped) {
                        when (b) {
                            0xDC.toByte() -> buffer.add(0xC0.toByte())
                            0xDD.toByte() -> buffer.add(0xDB.toByte())
                            else -> buffer.add(b)
                        }
                        escaped = false
                    } else if (b == 0xDB.toByte()) {
                        escaped = true
                    } else if (b == 0xC0.toByte()) {
                        if (buffer.isNotEmpty()) {
                            return buffer.toByteArray()
                        }
                    } else {
                        buffer.add(b)
                    }
                }
            }
        }
        return null
    }

    private fun putInt32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
