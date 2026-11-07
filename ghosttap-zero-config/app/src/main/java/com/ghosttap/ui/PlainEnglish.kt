package com.ghosttap.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.core.SigUuids
import com.ghosttap.data.TargetType

/**
 * Plain-English translations for the human-usable release.
 *
 * Every technical value the app shows (UUIDs, hex bytes, protocol names,
 * error strings) gets a plain-English line next to it. Raw data stays
 * available under "Advanced Details" — it never dominates the normal view.
 *
 * These are pure translations. They never claim a device did something
 * it didn't, and they never hide a failure behind friendly wording.
 */

/** Plain-English name for a device type. No jargon. */
fun plainDeviceType(type: TargetType): String = when (type) {
    TargetType.BLE -> "Bluetooth device"
    TargetType.BLUETOOTH_CLASSIC -> "Bluetooth device (classic)"
    TargetType.NFC -> "Tap card or tag"
    TargetType.USB -> "USB device"
    TargetType.NETWORK -> "Network device"
    TargetType.SENSOR -> "Phone sensor"
}

/**
 * Plain-English name for a UUID.
 * Known Bluetooth UUIDs get their real name; everything else is
 * described as what it is — a custom feature only this device defines.
 */
fun plainUuidName(uuid: String): String {
    val lower = uuid.lowercase()
    SigUuids.serviceName(lower)?.let { return it }
    SigUuids.characteristicName(lower)?.let { return it }
    // Short-form 16-bit UUIDs (0000XXXX-0000-1000-8000-00805f9b34fb)
    if (lower.startsWith("0000") && lower.endsWith("-0000-1000-8000-00805f9b34fb")) {
        val short = lower.substring(4, 8)
        return "Standard Bluetooth feature ($short)"
    }
    return "Custom feature (only this device defines it)"
}

/**
 * Try to read hex bytes as text. Returns the readable text if the bytes
 * are mostly printable ASCII, null otherwise.
 */
fun hexToReadableText(hex: String): String? {
    val cleaned = hex.replace(" ", "").replace(":", "").replace("-", "")
    if (cleaned.length % 2 != 0) return null
    if (cleaned.isEmpty()) return null
    val bytes = try {
        cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } catch (_: Exception) {
        return null
    }
    if (bytes.isEmpty()) return null
    val printable = bytes.count { it.toInt() in 32..126 || it == 10.toByte() || it == 13.toByte() || it == 9.toByte() }
    if (printable < bytes.size * 3 / 4) return null
    return try {
        String(bytes, Charsets.UTF_8).trim()
    } catch (_: Exception) {
        null
    }
}

/**
 * Plain-English explanation of a byte payload: what it means, not just
 * what it looks like.
 */
fun plainPayload(hex: String): String {
    val readable = hexToReadableText(hex)
    if (readable != null && readable.isNotEmpty()) {
        return "Device sent text: \"$readable\""
    }
    val byteCount = hex.replace(" ", "").length / 2
    return "Device sent $byteCount pieces of unreadable data (not plain text)"
}

/**
 * Plain-English translation of common errors, with the next useful action.
 * Never blames the user. Never claims success.
 */
fun plainError(raw: String): String {
    val lower = raw.lowercase()
    return when {
        "permission" in lower || "not allowed" in lower ->
            "Permission needed. Grant Bluetooth permission in Android Settings, then try again."
        "not connected" in lower || "disconnected" in lower ->
            "Not connected to the device. Tap Connect first, then try again."
        "timeout" in lower || "timed out" in lower ->
            "The device didn't answer in time. Move closer to it and try again."
        "not found" in lower || "no such" in lower ->
            "That feature wasn't found on this device. It may not support it."
        "invalid hex" in lower ->
            "That isn't valid code. Codes use only 0-9 and A-F, in pairs like 41 54. Switch to text mode to send plain words instead."
        "failed" in lower && ("connect" in lower || "gatt" in lower) ->
            "Connection failed. The device may be out of range, busy, or already connected to something else. Try again."
        "133" in lower || "status 133" in lower ->
            "Connection failed (Android error 133). Turn Bluetooth off and on, then try again."
        "busy" in lower ->
            "The device is busy. Wait a few seconds and try again."
        "unsupported" in lower ->
            "This device doesn't support that feature."
        else -> "Something went wrong: $raw. Try again, or move closer to the device."
    }
}

/** Plain-English explanation of what a connection state means. */
fun plainConnectionState(connected: Boolean, type: TargetType): String {
    return if (connected) {
        when (type) {
            TargetType.BLE -> "Connected. You can now read data and send commands."
            TargetType.BLUETOOTH_CLASSIC -> "Connected by cable. You can now send and receive text."
            TargetType.NFC -> "Tag detected. Hold it steady against the phone."
            else -> "Connected."
        }
    } else {
        when (type) {
            TargetType.BLE -> "Not connected. Tap Connect to link to this device."
            TargetType.BLUETOOTH_CLASSIC -> "Not connected. Tap Connect to link up by cable."
            TargetType.NFC -> "No tag detected. Hold a tag against the back of the phone."
            else -> "Not connected."
        }
    }
}

/**
 * What a "send" result actually means. Sending bytes is NOT proof the
 * device acted on them — it only proves the bytes left the phone.
 */
fun plainSendResult(sent: Boolean, byteCount: Int): String {
    return if (sent) {
        "Sent $byteCount pieces. This means the data left your phone — it does NOT prove the device acted on it. Watch for a reply or a change you can see."
    } else {
        "Nothing was sent. Check the connection and try again."
    }
}

/** Plain-English explanation of signal strength. Never a distance. */
fun plainSignal(rssi: Int?): String {
    if (rssi == null) return "Signal strength unknown."
    return when {
        rssi >= -50 -> "Signal: very strong (device is very close)."
        rssi >= -65 -> "Signal: strong."
        rssi >= -80 -> "Signal: moderate."
        else -> "Signal: weak (device may be far away or behind something)."
    }
}

/**
 * Plain-words next step for a Bluetooth feature, from what it can do.
 * Used where the app used to show only what a feature IS (A-27): the
 * line now also says what to DO about it.
 */
fun nextProbeFor(properties: List<String>): String = when {
    "READ" in properties ->
        "Tap Read — if it answers with words, you'll see them as plain text."
    "NOTIFY" in properties || "INDICATE" in properties ->
        "Turn on live updates, then use the device — you'll see what it sends."
    "WRITE" in properties || "WRITE_NO_RESPONSE" in properties ->
        "Tap it to pick it, then send plain words below — many devices answer plain words."
    else -> "Tap Read on it and see what comes back."
}

/**
 * Plain-words next step for a device Ghost Tap couldn't name (A-10):
 * a concrete probe, not just "read the analysis".
 */
fun nextStepForUnknownDevice(connected: Boolean, mostInteresting: String?): String = when {
    !connected ->
        "Ghost Tap hasn't talked to it yet — connect, and what it shares usually names it."
    mostInteresting != null ->
        "The most interesting part looks like $mostInteresting. Listen to it while you use the device — what it sends usually gives it away."
    else ->
        "Connected — waiting for the device to share what it can do. Give it a moment, then check again."
}

/**
 * A short plain-English header for the top of a screen: what it is, what the
 * action does, what it unlocks, and what to do next. No jargon, 2-4 lines.
 * Sits inside the screen's column, first thing under the title.
 */
@Composable
fun ExplainerCard(vararg lines: String) {
    PanelBox("WHAT THIS SCREEN DOES") {
        lines.forEach { line ->
            Text(
                line,
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
