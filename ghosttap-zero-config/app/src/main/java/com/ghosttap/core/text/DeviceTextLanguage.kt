package com.ghosttap.core.text

/**
 * How plain text becomes bytes on the way to a device.
 *
 * RawBytes is today's behavior on every terminal: exactly what was typed,
 * nothing added, nothing translated. Declared at each send path so a real
 * translation can slot in later without touching the send logic.
 */
interface DeviceTextLanguage {
    fun encode(text: String): ByteArray
}

/** Passthrough: text bytes exactly as typed. */
object RawBytes : DeviceTextLanguage {
    override fun encode(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
}
