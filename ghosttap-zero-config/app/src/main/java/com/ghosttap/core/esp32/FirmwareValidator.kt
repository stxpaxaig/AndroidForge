package com.ghosttap.core.esp32

import java.io.File
import java.io.RandomAccessFile

/** Validates ESP32 firmware/flash images before passing them to the QEMU backend.
 *
 * Supports:
 * - Merged flash images (bootloader @0x1000, partition table @0x8000, app @0x10000)
 * - Single app images (ESP image format with 0xE9 magic at offset 0)
 *
 * Validation is structural only — it does not execute code. A "valid" result
 * means the image has the expected ESP32 structure, not that QEMU can run it
 * (peripheral support is a separate concern, reported at runtime). */
object FirmwareValidator {

    data class Partition(
        val label: String,
        val type: Int,
        val subtype: Int,
        val offset: Long,
        val size: Long
    )

    data class ValidationResult(
        val valid: Boolean,
        val fileName: String,
        val fileSize: Long,
        val imageType: String, // "Merged flash image", "Single app image", "Unknown"
        val flashSizeMB: Int,
        val partitions: List<Partition>,
        val appPartitions: List<Partition>,
        val issues: List<String>, // warnings/errors
        val summary: String
    )

    private const val ESP_IMAGE_MAGIC: Byte = 0xE9.toByte()
    private const val PARTITION_MAGIC: Short = 0x50AA.toShort() // little-endian: AA 50

    private const val BOOTLOADER_OFFSET = 0x1000L
    private const val PARTITION_TABLE_OFFSET = 0x8000L

    fun validate(file: File): ValidationResult {
        val issues = mutableListOf<String>()
        val partitions = mutableListOf<Partition>()

        if (!file.exists()) {
            return ValidationResult(
                valid = false, fileName = file.name, fileSize = 0,
                imageType = "Unknown", flashSizeMB = 0,
                partitions = emptyList(), appPartitions = emptyList(),
                issues = listOf("File does not exist"),
                summary = "File not found"
            )
        }

        val size = file.length()

        // Size sanity: ESP32 flash is typically 1MB-16MB
        if (size < 1024) {
            issues.add("File too small (${size} bytes) — not a valid firmware image")
        }
        if (size > 16 * 1024 * 1024) {
            issues.add("File larger than 16MB — unusual for ESP32 flash")
        }

        var imageType = "Unknown"
        var flashSizeMB = (size / (1024 * 1024)).toInt().coerceAtLeast(1)

        try {
            RandomAccessFile(file, "r").use { raf ->
                // Try merged flash image: bootloader at 0x1000
                if (size > BOOTLOADER_OFFSET + 16) {
                    raf.seek(BOOTLOADER_OFFSET)
                    val magic = raf.readByte()
                    if (magic == ESP_IMAGE_MAGIC) {
                        imageType = "Merged flash image"
                        // Try to read partition table
                        if (size > PARTITION_TABLE_OFFSET + 32) {
                            raf.seek(PARTITION_TABLE_OFFSET)
                            val pMagic = raf.readShort() // big-endian read, need to swap
                            // Partition magic is 0x50AA stored little-endian as AA 50
                            // readShort() reads big-endian, so AA 50 becomes 0xAA50
                            if (pMagic == 0xAA50.toShort()) {
                                // Parse partition entries (32 bytes each, up to 95 entries)
                                var offset = PARTITION_TABLE_OFFSET + 32
                                while (offset + 32 <= size && partitions.size < 95) {
                                    raf.seek(offset)
                                    val eMagic = raf.readShort()
                                    if (eMagic != 0xAA50.toShort()) break // end of table or invalid

                                    val type = raf.readByte().toInt() and 0xFF
                                    val subtype = raf.readByte().toInt() and 0xFF
                                    raf.seek(offset + 4) // skip to offset field
                                    val partOffset = readIntLE(raf).toLong() and 0xFFFFFFFFL
                                    val partSize = readIntLE(raf).toLong() and 0xFFFFFFFFL
                                    val labelBytes = ByteArray(16)
                                    raf.readFully(labelBytes)
                                    val label = String(labelBytes).trim { it <= ' ' }.trimEnd('\u0000')
                                    // flags at offset+28 (4 bytes, skip)

                                    partitions.add(Partition(label, type, subtype, partOffset, partSize))
                                    offset += 32
                                }
                            } else {
                                issues.add("No valid partition table at 0x8000 (expected for merged flash)")
                            }
                        }
                    }
                }

                // Try single app image: magic at offset 0
                if (imageType == "Unknown" && size > 16) {
                    raf.seek(0)
                    val magic = raf.readByte()
                    if (magic == ESP_IMAGE_MAGIC) {
                        imageType = "Single app image"
                        issues.add("Single app image detected — needs bootloader + partition table to run as merged flash")
                    }
                }

                if (imageType == "Unknown") {
                    issues.add("No ESP image magic (0xE9) found at 0x0 or 0x1000 — not a recognized ESP32 image")
                }
            }
        } catch (e: Exception) {
            issues.add("Read error: ${e.message}")
        }

        val appPartitions = partitions.filter { it.type == 0x00 && it.subtype == 0x00 }

        if (imageType == "Merged flash image" && appPartitions.isEmpty() && partitions.isNotEmpty()) {
            issues.add("No app partitions (type 0, subtype 0) found in partition table")
        }

        val valid = issues.none { it.contains("not a valid") || it.contains("not a recognized") || it.contains("File does not exist") }

        val summary = buildString {
            append(imageType)
            append(" (${size / 1024} KB)")
            if (appPartitions.isNotEmpty()) {
                append(" — ${appPartitions.size} app partition(s)")
            }
            if (issues.isNotEmpty()) {
                append(" — ${issues.size} issue(s)")
            }
        }

        return ValidationResult(
            valid = valid,
            fileName = file.name,
            fileSize = size,
            imageType = imageType,
            flashSizeMB = flashSizeMB,
            partitions = partitions,
            appPartitions = appPartitions,
            issues = issues,
            summary = summary
        )
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b0 = raf.readByte().toInt() and 0xFF
        val b1 = raf.readByte().toInt() and 0xFF
        val b2 = raf.readByte().toInt() and 0xFF
        val b3 = raf.readByte().toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
