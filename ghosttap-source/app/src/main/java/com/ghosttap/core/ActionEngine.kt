package com.ghosttap.core

/**
 * Shared Action Engine: turns what Ghost Tap *actually discovered* about a
 * target into a list of REAL operations it can perform right now.
 *
 * Every entry is either available (tap performs it or opens its control) or
 * honestly unavailable with the reason. No fake controls, ever.
 */

/** One real operation Ghost Tap can attempt on a target. */
data class DeviceAction(
    /** Stable id the ViewModel switches on, e.g. "ble_read", "nfc_write_text". */
    val id: String,
    /** Short button label, e.g. "Read data". */
    val label: String,
    /** Plain-English explanation of what tapping does. */
    val detail: String,
    /** True when Ghost Tap can actually attempt this right now. */
    val available: Boolean,
    /** Shown when unavailable — never a fake "unsupported", always the real reason. */
    val unavailableWhy: String = "",
    /**
     * When non-null, tapping expands the named UI panel instead of running an
     * operation (e.g. "map" opens MAP INTERFACE). Null = performs directly.
     */
    val opensPanel: String? = null
)

object ActionEngine {

    // ---------------- BLE ----------------

    fun bleActions(
        connected: Boolean,
        readable: Int,
        writable: Int,
        notifiable: Int,
        hasLearnedActions: Boolean
    ): List<DeviceAction> {
        if (!connected) return listOf(
            DeviceAction(
                id = "ble_connect",
                label = "Connect",
                detail = "Open a live Bluetooth link to this device.",
                available = true
            ),
            DeviceAction(
                id = "ble_read", label = "Read data",
                detail = "Needs a connection first.", available = false,
                unavailableWhy = "Not connected yet — connect first."
            ),
            DeviceAction(
                id = "ble_write", label = "Send data",
                detail = "Needs a connection first.", available = false,
                unavailableWhy = "Not connected yet — connect first."
            ),
            DeviceAction(
                id = "ble_watch", label = "Watch live data",
                detail = "Needs a connection first.", available = false,
                unavailableWhy = "Not connected yet — connect first."
            )
        )
        val list = mutableListOf(
            DeviceAction(
                id = "ble_disconnect", label = "Disconnect",
                detail = "Close the Bluetooth link.", available = true
            )
        )
        list += DeviceAction(
            id = "ble_read", label = "Read data",
            detail = if (readable > 0) "Read from $readable connection(s) you can read."
            else "This device has nothing you can read.",
            available = readable > 0,
            unavailableWhy = "Nothing readable was found on this device.",
            opensPanel = "map"
        )
        list += DeviceAction(
            id = "ble_write", label = "Send data",
            detail = if (writable > 0) "Send to $writable connection(s) you can write to."
            else "This device has nothing you can write to.",
            available = writable > 0,
            unavailableWhy = "Nothing writable was found on this device.",
            opensPanel = "map"
        )
        list += DeviceAction(
            id = "ble_watch", label = "Watch live data",
            detail = if (notifiable > 0) "Get live updates from $notifiable connection(s)."
            else "This device offers no live updates.",
            available = notifiable > 0,
            unavailableWhy = "No live updates were found on this device.",
            opensPanel = "map"
        )
        list += DeviceAction(
            id = "ble_map", label = "Map what it can do",
            detail = "Check every connection to learn what it does.",
            available = true, opensPanel = "map"
        )
        if (hasLearnedActions) {
            list += DeviceAction(
                id = "ble_learned", label = "Replay learned controls",
                detail = "Run actions you previously taught Ghost Tap.",
                available = true, opensPanel = "learned"
            )
        }
        return list
    }

    // ---------------- NFC ----------------

    fun nfcActions(facts: NfcFacts, hasSavedNdef: Boolean): List<DeviceAction> {
        val list = mutableListOf(
            DeviceAction(
                id = "nfc_read", label = "Read",
                detail = if (facts.hasNdef) "Read the saved notes on this card."
                else "Read whatever this card shares (nothing saved on it).",
                available = true
            )
        )
        list += DeviceAction(
            id = "nfc_write_text", label = "Write text",
            detail = if (facts.ndefWritable) "Save words onto this card."
            else nfcWriteWhyNot(facts),
            available = facts.ndefWritable,
            unavailableWhy = nfcWriteWhyNot(facts)
        )
        list += DeviceAction(
            id = "nfc_write_url", label = "Write URL",
            detail = if (facts.ndefWritable) "Save a web link onto this card."
            else nfcWriteWhyNot(facts),
            available = facts.ndefWritable,
            unavailableWhy = nfcWriteWhyNot(facts)
        )
        list += DeviceAction(
            id = "nfc_copy", label = "Copy content",
            detail = "Save this card's notes so you can write them onto another card.",
            available = facts.hasNdef,
            unavailableWhy = "Nothing saved on this card to copy."
        )
        if (hasSavedNdef) {
            list += DeviceAction(
                id = "nfc_write_saved", label = "Write saved",
                detail = if (facts.ndefWritable) "Write your saved notes onto this card."
                else nfcWriteWhyNot(facts),
                available = facts.ndefWritable,
                unavailableWhy = nfcWriteWhyNot(facts)
            )
        }
        list += DeviceAction(
            id = "nfc_erase", label = "Erase",
            detail = if (facts.ndefWritable) "Clear the notes saved on this card."
            else nfcWriteWhyNot(facts),
            available = facts.ndefWritable,
            unavailableWhy = nfcWriteWhyNot(facts)
        )
        list += DeviceAction(
            id = "nfc_format", label = "Format",
            detail = if (facts.canFormat) "Set up this blank card so it can hold notes."
            else "This card doesn't say it can be set up.",
            available = facts.canFormat,
            unavailableWhy = "This card can't be set up to hold notes."
        )
        list += DeviceAction(
            id = "nfc_apdu", label = "Card commands",
            detail = if (facts.isIsoDep) "Send direct commands to this card."
            else "Only smart-card style devices support this.",
            available = facts.isIsoDep,
            unavailableWhy = "Not a smart-card device.",
            opensPanel = "apdu"
        )
        return list
    }

    private fun nfcWriteWhyNot(facts: NfcFacts): String = when {
        facts.isIsoDep && !facts.hasNdef ->
            "This is a smart card — Ghost Tap won't pretend saving notes works on it."
        !facts.hasNdef && !facts.canFormat ->
            "This card can't hold notes."
        facts.hasNdef && !facts.ndefWritable ->
            "This card says it's read-only."
        else -> "This card can't be written to right now."
    }

    // ---------------- Classic Bluetooth SPP ----------------

    fun classicBtActions(connected: Boolean): List<DeviceAction> {
        if (!connected) return listOf(
            DeviceAction(
                id = "spp_connect",
                label = "Connect (text link)",
                detail = "Open a text link to this device.",
                available = true
            ),
            DeviceAction(
                id = "spp_terminal", label = "Message box",
                detail = "Needs a text link first.", available = false,
                unavailableWhy = "Not connected yet — connect first."
            )
        )
        return listOf(
            DeviceAction(
                id = "spp_disconnect", label = "Disconnect",
                detail = "Close the text link.", available = true
            ),
            DeviceAction(
                id = "spp_terminal", label = "Message box",
                detail = "Send and receive messages.",
                available = true, opensPanel = "spp_terminal"
            )
        )
    }

    // ---------------- USB ----------------
    fun usbActions(hasOut: Boolean, hasIn: Boolean, hasPermission: Boolean): List<DeviceAction> {
        if (!hasPermission) return listOf(
            DeviceAction(
                id = "usb_auth", label = "Grant USB access",
                detail = "Android needs your permission before Ghost Tap can talk to this device.",
                available = true
            ),
            DeviceAction(
                id = "usb_send", label = "Send data", detail = "Needs USB permission first.",
                available = false, unavailableWhy = "Permission needed — allow USB access first."
            ),
            DeviceAction(
                id = "usb_receive", label = "Receive data", detail = "Needs USB permission first.",
                available = false, unavailableWhy = "Permission needed — allow USB access first."
            )
        )
        return listOf(
            DeviceAction(
                id = "usb_send", label = "Send data",
                detail = if (hasOut) "Send to the device's Out point."
                else "This device has no Out point.",
                available = hasOut,
                unavailableWhy = "No Out point found.",
                opensPanel = "raw"
            ),
            DeviceAction(
                id = "usb_receive", label = "Receive data",
                detail = if (hasIn) "Read from the device's In point."
                else "This device has no In point.",
                available = hasIn,
                unavailableWhy = "No In point found.",
                opensPanel = "raw"
            )
        )
    }

    // ---------------- VISUAL INTEL ----------------
    /**
     * Visual Intel capabilities: camera capture, OCR of the last capture,
     * and saved target profiles. The WHAT CAN GHOST TAP DO? panel is
     * per-target only, so this list is surfaced by the Visual Intel screen
     * itself — available here for any future global capabilities surface.
     */
    fun visualActions(
        cameraGranted: Boolean,
        findingCount: Int,
        profileCount: Int
    ): List<DeviceAction> {
        return listOf(
            DeviceAction(
                id = "visual_capture", label = "Take a photo and read it",
                detail = "Point the camera at a board, chip, label, or device and read it.",
                available = cameraGranted,
                unavailableWhy = "Camera permission not granted yet."
            ),
            DeviceAction(
                id = "visual_ocr", label = "Read words from the last photo",
                detail = "Show the words, barcodes, and possible markings found.",
                available = findingCount > 0,
                unavailableWhy = "Capture or pick a photo first."
            ),
            DeviceAction(
                id = "visual_profiles", label = "Open saved finds",
                detail = if (profileCount > 0) "$profileCount saved case file(s)."
                else "No profiles saved yet.",
                available = true,
                opensPanel = "visual"
            )
        )
    }
}
