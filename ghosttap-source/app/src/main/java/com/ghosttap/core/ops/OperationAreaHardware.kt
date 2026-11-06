package com.ghosttap.core.ops

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.ghosttap.UsbUartViewModel
import com.ghosttap.VisualAction
import com.ghosttap.VisualFinding
import com.ghosttap.core.OpResult
import com.ghosttap.core.esp32.FirmwareValidator
import com.ghosttap.core.esp32.VirtualEsp32Client
import com.ghosttap.core.usb.UsbDeviceInfo
import com.ghosttap.parseWifiCredentials
import com.ghosttap.suggestActions
import kotlinx.coroutines.delay

/**
 * Package 5 — Hardware: USB / ESP32 / Virtual ESP32 (D-01–D-31) +
 * USB/Network ViewModels (B-084–B-114) + Visual Intel (B-115–B-126),
 * plus the Package 2 remainders A-28 (fallback suggestion) and A-30
 * (recent-firmware shortcut, wired in the home screen).
 *
 * Every outcome below wraps an EXISTING ViewModel / engine function —
 * connect, send, flash, scan, capture — with preparation +
 * preconfiguration + verification through the [OperationRunner].
 * Nothing here re-implements what the ViewModel already does. The one
 * thing the operations add is the orchestration the user used to do by
 * hand: plug-in → permission → connect → speed found by itself;
 * pick file → check → load mode → test → confirm → flash → read
 * back and compare; scan → host → open ports → send in one step;
 * capture → finding → suggested action, checked against the OCR
 * text before it runs.
 *
 * The existing functions stay exactly as they are: the auto-baud
 * probe still lives in `autoBaud` (its rate list now also tries
 * 74880/230400), the flash still lives in `confirmFlash`, the
 * Virtual ESP32 calls still go through `VirtualEsp32Client`
 * unchanged. Plain-words titles here are entry-screen labels only;
 * all existing screen/panel titles keep their original wording.
 */

/**
 * Small shared UI state owned by Package 5: the stashed picks of
 * multi-step operations (host, then port, then message — the runner
 * only carries one pending choice at a time) and the validation
 * result of the guided flash flow (checked in preparation, reported
 * in execution). The entry screen reads the stash only through the
 * operations; a fresh run with no pick forgets any stale stash.
 */
object Area5State {
    /** Host picked by the first step of a network send flow. */
    var stashedHost: String? by mutableStateOf(null)

    /** Port picked by the second step of a network send flow. */
    var stashedPort: Int? by mutableStateOf(null)

    /** Message picked by the third step of a network send flow. */
    var stashedMessage: String? by mutableStateOf(null)

    /** Profile picked by the first step of a rename/delete flow. */
    var stashedProfileId: String? by mutableStateOf(null)

    /** Firmware check from the guided flash flow's preparation step. */
    var stashedValidation: FirmwareValidator.ValidationResult? = null

    /** Clears the network send stash. */
    fun clearNetStash() {
        stashedHost = null
        stashedPort = null
        stashedMessage = null
    }
}

/** Waits, bounded, for [check] to turn true. Never waits forever. */
private suspend fun waitUntil(
    timeoutMs: Long,
    pollMs: Long = 500L,
    check: () -> Boolean
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (check()) return true
        delay(pollMs)
    }
    return check()
}

/** Plain-words display for a plugged-in USB board. */
private fun usbDeviceDisplay(info: UsbDeviceInfo): String =
    "${info.manufacturer} ${info.product} (${info.vidPidString()})".trim()

/** Plain-words chip line for a plugged-in USB board. */
private fun usbChipLine(driverName: String): String = when (driverName) {
    "CP210x (Silicon Labs)" -> "It talks through a CP210x chip (Silicon Labs) — Ghost Tap knows how to talk to it."
    "CH340/CH341 (WCH)" -> "It talks through a CH340/CH341 chip (WCH) — Ghost Tap knows how to talk to it."
    "Unsupported" -> "Ghost Tap doesn't know this chip yet — it can't talk to this board."
    else -> "Connection type: $driverName."
}

/**
 * A preparation step that asks the user to pick one plugged-in USB
 * board. A single board is picked by itself; several boards ask.
 * The pick is selected in the ViewModel, so the permission and
 * connect steps that follow act on the right board.
 */
private fun usbDeviceChoiceStep(label: String): PreparationStep =
    PreparationStep(label) { context ->
        val usb = context.usb
            ?: return@PreparationStep PreparationOutcome.Failed(
                "Can't check USB — the app isn't ready yet."
            )
        usb.refreshDevices()
        val devices = usb.usbDevices
        if (devices.isEmpty()) {
            return@PreparationStep PreparationOutcome.Failed(
                "No USB board plugged in — plug one in with a USB cable, then try again."
            )
        }
        val picked = context.pendingChoice
        if (picked == null) {
            if (devices.size == 1) {
                usb.selectDevice(devices[0])
                return@PreparationStep PreparationOutcome.Ready
            }
            return@PreparationStep PreparationOutcome.NeedsUserChoice(
                prompt = "Which board?",
                choices = devices.map { usbDeviceDisplay(it) }
            )
        }
        val match = devices.firstOrNull { usbDeviceDisplay(it) == picked }
        if (match != null) {
            usb.selectDevice(match)
            context.pendingChoice = null
            return@PreparationStep PreparationOutcome.Ready
        }
        PreparationOutcome.NeedsUserChoice(
            prompt = "Which board?",
            choices = devices.map { usbDeviceDisplay(it) }
        )
    }

/**
 * A preparation step that asks the user to pick one live host from
 * the last Wi-Fi scan. The pick is stashed, so a later choice step
 * in the same operation (e.g. the port-set step) can re-run past this
 * step without asking again — the runner re-runs the whole operation
 * after every choice.
 */
private fun netHostChoiceStep(label: String): PreparationStep =
    PreparationStep(label) { context ->
        val net = context.net
            ?: return@PreparationStep PreparationOutcome.Failed(
                "Network tools aren't available right now."
            )
        val hosts = net.liveHosts
        if (hosts.isEmpty()) {
            return@PreparationStep PreparationOutcome.Failed(
                "Nothing found on your Wi-Fi yet — run 'Find what's on my Wi-Fi' first."
            )
        }
        val picked = context.pendingChoice
        if (picked == null) {
            Area5State.stashedHost = null
            return@PreparationStep PreparationOutcome.NeedsUserChoice(
                prompt = "Which device?",
                choices = hosts
            )
        }
        if (picked in hosts) {
            Area5State.stashedHost = picked
            context.pendingChoice = null
            return@PreparationStep PreparationOutcome.Ready
        }
        if (Area5State.stashedHost != null && Area5State.stashedHost in hosts) {
            return@PreparationStep PreparationOutcome.Ready
        }
        PreparationOutcome.NeedsUserChoice(
            prompt = "Which device?",
            choices = hosts
        )
    }

/** Resolves the picked live host and consumes the pick, so it never leaks into a later run. */
private fun chosenHost(context: OperationContext): String? {
    val net = context.net ?: return null
    val stashed = Area5State.stashedHost?.takeIf { it in net.liveHosts }
    Area5State.stashedHost = null
    if (stashed != null) return stashed
    val picked = context.pendingChoice
    context.pendingChoice = null
    return if (picked != null && picked in net.liveHosts) picked
    else net.liveHosts.firstOrNull()
}

/** First step of a network send flow: pick the host, stash it. */
private fun stashHostStep(label: String): PreparationStep =
    PreparationStep(label) { context ->
        val net = context.net
            ?: return@PreparationStep PreparationOutcome.Failed(
                "Network tools aren't available right now."
            )
        val hosts = net.liveHosts
        if (hosts.isEmpty()) {
            return@PreparationStep PreparationOutcome.Failed(
                "Nothing found on your Wi-Fi yet — run 'Find what's on my Wi-Fi' first."
            )
        }
        val picked = context.pendingChoice
        if (picked == null) {
            Area5State.stashedHost = null
            return@PreparationStep PreparationOutcome.NeedsUserChoice(
                prompt = "Which device?",
                choices = hosts
            )
        }
        if (picked in hosts) {
            Area5State.stashedHost = picked
            context.pendingChoice = null
            return@PreparationStep PreparationOutcome.Ready
        }
        if (Area5State.stashedHost != null && Area5State.stashedHost in hosts) {
            return@PreparationStep PreparationOutcome.Ready
        }
        PreparationOutcome.NeedsUserChoice(
            prompt = "Which device?",
            choices = hosts
        )
    }

/** Second step of a network send flow: pick the port, stash it. */
private fun stashPortStep(label: String): PreparationStep =
    PreparationStep(label) { context ->
        val net = context.net
            ?: return@PreparationStep PreparationOutcome.Failed(
                "Network tools aren't available right now."
            )
        val host = Area5State.stashedHost
            ?: return@PreparationStep PreparationOutcome.Failed("Pick a device first.")
        val open = net.openPortsByHost[host] ?: emptyList()
        val choices = (open.map { it.toString() } +
            listOf("80", "443", "8080", "1883")).distinct()
        val picked = context.pendingChoice
        if (picked == null) {
            Area5State.stashedPort = null
            return@PreparationStep PreparationOutcome.NeedsUserChoice(
                prompt = "Which connection point on $host?",
                choices = choices
            )
        }
        if (picked in choices) {
            Area5State.stashedPort = picked.toIntOrNull()
            context.pendingChoice = null
            return@PreparationStep PreparationOutcome.Ready
        }
        if (Area5State.stashedPort != null &&
            Area5State.stashedPort.toString() in choices
        ) {
            return@PreparationStep PreparationOutcome.Ready
        }
        PreparationOutcome.NeedsUserChoice(
            prompt = "Which connection point on $host?",
            choices = choices
        )
    }

/** Third step of a network send flow: pick the message, stash it. */
private fun stashMessageStep(
    label: String,
    messages: List<String> = listOf("HELLO", "STATUS", "PING")
): PreparationStep = PreparationStep(label) { context ->
    val picked = context.pendingChoice
    if (picked == null) {
        Area5State.stashedMessage = null
        return@PreparationStep PreparationOutcome.NeedsUserChoice(
            prompt = "What should it say?",
            choices = messages
        )
    }
    if (picked in messages) {
        Area5State.stashedMessage = picked
        context.pendingChoice = null
        return@PreparationStep PreparationOutcome.Ready
    }
    if (Area5State.stashedMessage != null && Area5State.stashedMessage in messages) {
        return@PreparationStep PreparationOutcome.Ready
    }
    PreparationOutcome.NeedsUserChoice(
        prompt = "What should it say?",
        choices = messages
    )
}

/** Takes the stashed host/port/message out of a network send flow and clears them. */
private fun consumeNetStash(): Triple<String?, Int?, String?> {
    val host = Area5State.stashedHost
    val port = Area5State.stashedPort
    val message = Area5State.stashedMessage
    Area5State.clearNetStash()
    return Triple(host, port, message)
}

/** Waits, bounded, for the network tools to go idle (no test running). */
private suspend fun waitForNetIdle(
    net: com.ghosttap.NetworkToolsViewModel,
    timeoutMs: Long = 60000L
): Boolean = waitUntil(timeoutMs) { !net.isBusy && !net.isScanningNetwork }

/** Log lines a network op produced, from [before] onwards. */
private fun netLogSince(
    net: com.ghosttap.NetworkToolsViewModel,
    before: Int
): List<String> = net.log.drop(before)

/** The camera must be allowed before the camera can read anything. */
private fun ensureCameraPermission(context: OperationContext): PreparationOutcome {
    val app = context.ble?.getApplication<Application>()
        ?: return PreparationOutcome.Failed(
            "Can't check the camera — the app isn't ready yet."
        )
    return if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        PreparationOutcome.Ready
    } else {
        context.requestPermissions()
        PreparationOutcome.NeedsUserAction(
            prompt = "Ghost Tap needs the camera to read boards and labels. " +
                "Allow it when asked, then tap this again."
        )
    }
}

/** The Wi-Fi network the phone is on right now, if it will say. */
private fun currentWifiSsid(context: OperationContext): String? {
    val app = context.ble?.getApplication<Application>() ?: return null
    val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return null
    return try {
        @Suppress("DEPRECATION")
        wifi.connectionInfo?.ssid?.trim('"')
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (_: Exception) {
        null
    }
}

/** Waits for the board test to finish, ignoring a stale verdict from an earlier run. */
private suspend fun waitForEsp32Test(usb: UsbUartViewModel): Boolean {
    waitUntil(3000) { usb.esp32Status == "Checking…" }
    return waitUntil(10000) { usb.esp32Status != "Checking…" }
}

/** Waits for the load-mode sequence to finish. */
private suspend fun waitForDownloadMode(usb: UsbUartViewModel): Boolean {
    waitUntil(3000) { usb.esp32Status.contains("Putting the board into download mode") }
    return waitUntil(8000) {
        usb.esp32Status.contains("Download mode started") ||
            usb.esp32Status.contains("Couldn't start download mode")
    }
}

private val IP_REGEX = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
private val MAC_REGEX = Regex("""([0-9A-Fa-f]{2}(:|-)){5}[0-9A-Fa-f]{2}""")
private val USB_KEYWORDS = Regex("""esp32|arduino|ch340|cp210|uart|serial|teensy|stm32""")
private val NFC_KEYWORDS = Regex("""nfc|mifare|ntag|ndef|rfid""")

/** The plain-words fallback for an action id nothing knows how to run (A-28). */
private const val UNKNOWN_VISUAL_ACTION_FALLBACK =
    "I don't know how to do that one yet — here is what I can do: " +
        "join a Wi-Fi network, fetch a link, connect to an address, " +
        "connect to a device, or open the USB or tap-card tools."

/**
 * OCR verification (Package 5, D-30): checks the camera's own text
 * actually contains what [action] needs before the action runs — a
 * misread never flows straight into an address, a password, or a
 * device. Returns the plain-words reason it can't be trusted, or
 * null when the text checks out.
 */
private fun visualActionMismatch(finding: VisualFinding, action: VisualAction): String? {
    val haystack = finding.fullText + "\n" +
        finding.markings.joinToString("\n") + "\n" +
        finding.barcodes.joinToString("\n") { it.rawValue }
    return when (action.id) {
        "wifi" -> {
            val creds = parseWifiCredentials(action.arg)
            when {
                creds == null ->
                    "The Wi-Fi code didn't scan clearly — no network name in it."
                creds.ssid !in haystack ->
                    "The network name '${creds.ssid}' isn't in what the camera read — it may be a misread."
                else -> null
            }
        }
        "fetch_url" ->
            if (action.arg.startsWith("http", ignoreCase = true) && action.arg in haystack) null
            else "The link in the code isn't in what the camera read — it may be a misread."
        "connect_ip" ->
            if (IP_REGEX.find(haystack)?.value == action.arg) null
            else "The address ${action.arg} isn't in what the camera read — it may be a misread."
        "ble" -> {
            val found = MAC_REGEX.find(haystack)?.value?.replace("-", ":")?.uppercase()
            if (found == action.arg.uppercase()) null
            else "The device address ${action.arg} isn't in what the camera read — it may be a misread."
        }
        "usb" ->
            if (USB_KEYWORDS.containsMatchIn(haystack.lowercase())) null
            else "No board or cable clue in what the camera read."
        "nfc" ->
            if (NFC_KEYWORDS.containsMatchIn(haystack.lowercase())) null
            else "No tap-card clue in what the camera read."
        else -> UNKNOWN_VISUAL_ACTION_FALLBACK
    }
}

/**
 * Runs one suggested visual action end-to-end, after the OCR check
 * above passed. Mirrors the Visual Intel screen's `runVisualAction`
 * dispatch — same ViewModel calls, same navigations — with the
 * verification step in front of it. An unknown action id is a
 * plain-words fallback, never a silent no-op (A-28).
 */
private suspend fun executeVisualAction(
    context: OperationContext,
    action: VisualAction
): OpResult {
    val visual = context.visual
        ?: return OpResult(false, "Visual Intel isn't available right now.")
    val finding = visual.lastFinding
        ?: return OpResult(false, "Take a photo first — nothing to act on yet.")
    visualActionMismatch(finding, action)?.let { return OpResult(false, it) }
    return when (action.id) {
        "wifi" -> {
            val parts = action.arg.split("||", limit = 3)
            val ssid = parts.getOrElse(0) { "" }
            val password = parts.getOrElse(1) { "" }.ifEmpty { null }
            if (password == null) {
                context.navigate("visual_intel")
                OpResult(
                    true,
                    "This network needs a password the picture didn't show — " +
                        "type it once on the Visual Intel screen, then tap JOIN THIS WI-FI.",
                    "wifi-nopass:$ssid"
                )
            } else {
                visual.joinWifi(ssid, password, parts.getOrElse(2) { "" })
                val joined = currentWifiSsid(context) == ssid
                val outcome = if (joined) {
                    "Joined '$ssid' — the phone is on it now."
                } else {
                    visual.statusLine
                }
                OpResult(
                    visual.statusLine.contains("Asked the phone to join"),
                    outcome,
                    "wifi:$ssid"
                )
            }
        }
        "fetch_url" -> {
            visual.fetchLinkPreview(action.arg)
            val ready = waitUntil(15000) { visual.linkPreview != null }
            if (!ready) {
                OpResult(false, "The link didn't answer in time.")
            } else {
                val preview = visual.linkPreview ?: ""
                if (preview.startsWith("Couldn't open") || preview.startsWith("The link answered")) {
                    OpResult(false, preview)
                } else {
                    OpResult(true, preview.take(300), "url:${action.arg}")
                }
            }
        }
        "connect_ip" -> {
            val net = context.net
                ?: return OpResult(false, "Network tools aren't available right now.")
            val logBefore = net.log.size
            net.portScan(action.arg)
            waitForNetIdle(net, 30000)
            val lines = netLogSince(net, logBefore)
            val open = net.openPortsByHost[action.arg] ?: emptyList()
            val detail = lines.lastOrNull { "Port check — done" in it }
                ?: lines.joinToString(" ").take(200)
            OpResult(
                true,
                if (open.isEmpty()) "Checked ${action.arg} — nothing open on the usual ports. $detail"
                else "Checked ${action.arg} — open: ${open.joinToString(", ")}. $detail",
                "ip:${action.arg}"
            )
        }
        "ble" -> {
            val ble = context.ble
                ?: return OpResult(false, "Bluetooth isn't available right now.")
            val id = ble.connectBleByAddress(action.arg)
                ?: return OpResult(
                    false,
                    "Couldn't connect to ${action.arg} — the device didn't answer."
                )
            ble.selectTarget(id)
            context.navigate("target/$id")
            OpResult(
                true,
                "Connected to ${action.arg} — its controls are on its screen.",
                "BLE:$id"
            )
        }
        "usb" -> {
            context.navigate("usb_uart")
            OpResult(
                true,
                "USB tools open — plug the board in and check the list.",
                "usb"
            )
        }
        "nfc" -> {
            context.navigate("nfc_tools")
            OpResult(
                true,
                "Tap-card tools open — hold a card to the back of the phone.",
                "nfc"
            )
        }
        else -> OpResult(false, UNKNOWN_VISUAL_ACTION_FALLBACK)
    }
}

/**
 * Verifies a visual action run: a BLE run is verified against the
 * live link right now; anything else is verified by the real
 * response, as usual.
 */
private suspend fun verifyVisualAction(
    context: OperationContext,
    result: OpResult
): VerificationOutcome {
    val kind = result.data.substringBefore(":", "")
    val id = result.data.substringAfter(":", "")
    return when (kind) {
        "BLE" -> verifyStillConnected(TransportKind.BLE, id, context)
        else -> defaultVerify(result)
    }
}

/** Flash spots the screen offers, with their plain-words labels (D-19). */
private val FLASH_SPOTS = listOf(
    0x10000 to "App — the usual spot (0x10000)",
    0x0 to "Bootloader — the very start (0x0)",
    0x8000 to "Settings table (0x8000)"
)

/** Plain-words label for a flash spot. */
private fun flashSpotLabel(offset: Int): String =
    FLASH_SPOTS.firstOrNull { it.first == offset }?.second
        ?: "0x${offset.toString(16).uppercase()}"

/** Plain-words warning for a flash spot other than the usual app spot (D-19). */
private fun flashSpotWarning(offset: Int): String =
    if (offset == 0x10000) ""
    else " Warning: this writes the file somewhere other than the usual " +
        "app spot — only for a bootloader or settings-table file. " +
        "The wrong spot can stop the board from starting."

/** Registers every Package 5 operation. Idempotent; safe to call again. */
fun registerAreaHardwareOperations() {

    // ---- Find the plugged-in board (D-01, D-02, D-06 + B-097, B-098). ----
    // One tap refreshes the list and reports, in plain words, what was
    // found — including boards no driver knows, which used to be
    // silently invisible.
    OperationRegistry.register(
        Operation(
            id = "usb.find-board",
            title = "Find the plugged-in board",
            description = "Checks what is plugged in by USB and says what it is, in plain words — including boards Ghost Tap doesn't know yet.",
            area = OperationArea.USB,
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.refreshDevices()
                val known = usb.usbDevices
                val unknown = usb.listUnknownUsbDevices()
                if (known.isEmpty() && unknown.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "No USB board plugged in — plug one in with a USB cable, then try again."
                    )
                }
                val lines = buildString {
                    if (known.isNotEmpty()) {
                        append("Found ${known.size} board(s) I can talk to: ")
                        append(known.joinToString("; ") { usbDeviceDisplay(it) })
                        append(".")
                    }
                    if (unknown.isNotEmpty()) {
                        if (isNotEmpty()) append(" ")
                        append("Also plugged in but not recognized: ")
                        append(unknown.joinToString("; ") { it.vidPidString() })
                        append(" — Ghost Tap doesn't know these chips yet.")
                    }
                }
                OpResult(true, lines.toString(), "usb:${known.size}:${unknown.size}")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- What is this board? (D-02). ----
    OperationRegistry.register(
        Operation(
            id = "usb.describe-board",
            title = "What is this plugged-in board?",
            description = "Says what the plugged-in board is — who made it, which chip talks for it, and whether the phone is allowed to use it — in plain words, not codes.",
            area = OperationArea.USB,
            preparation = listOf(usbDeviceChoiceStep("Pick a board")),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val dev = usb.selectedDevice
                    ?: return@Operation OpResult(false, "Couldn't find that board any more.")
                val permission = if (dev.hasPermission || !usb.permissionNeeded) {
                    "The phone is allowed to use it."
                } else {
                    "The phone hasn't been allowed to use it yet — run 'Allow USB access'."
                }
                OpResult(
                    true,
                    "${dev.manufacturer} ${dev.product}. " +
                        usbChipLine(dev.driverName) + " " + permission,
                    "usb:${dev.vidPidString()}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Allow USB access (D-03 + B-099). ----
    OperationRegistry.register(
        Operation(
            id = "usb.allow-access",
            title = "Allow USB access",
            description = "Asks Android for permission to talk to the plugged-in board, then checks the permission really landed.",
            area = OperationArea.USB,
            preparation = listOf(
                usbDeviceChoiceStep("Pick a board"),
                PreparationStep("Checking a board is picked") { context ->
                    val usb = context.usb
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check USB — the app isn't ready yet."
                        )
                    if (usb.selectedDevice == null) {
                        PreparationOutcome.Failed(
                            "Pick a USB device first — plug in the board, then pick it from the list."
                        )
                    } else if (!usb.permissionNeeded) {
                        PreparationOutcome.Failed(
                            "Permission is already allowed for this board — nothing to ask for."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.requestPermission()
                val allowed = waitUntil(30000) { !usb.permissionNeeded }
                if (allowed) {
                    OpResult(true, "Permission allowed — ready to connect.", "usb-allowed")
                } else {
                    OpResult(
                        false,
                        "Permission wasn't allowed — the board can't be used until you tap Allow on the pop-up."
                    )
                }
            },
            verify = { context, result ->
                val usb = context.usb
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Can't check USB — the app isn't ready yet."
                    )
                if (!usb.permissionNeeded) {
                    VerificationOutcome.Verified("Permission is allowed for the picked board.")
                } else {
                    VerificationOutcome.NotVerified(
                        "Permission still isn't allowed for this board."
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Connect the plugged-in board (D-01–D-05 + B-097–B-103). ----
    // ONE chained operation: plug-in detected → permission → connect →
    // speed found automatically. The existing connect/permission
    // functions do each step unchanged; the operation only chains them.
    OperationRegistry.register(
        Operation(
            id = "usb.connect-board",
            title = "Connect the plugged-in board",
            description = "Does the whole chain in one tap: finds the plugged-in board, gets permission, connects, and finds the right speed by itself.",
            area = OperationArea.USB,
            prerequisites = listOf("A board plugged in by USB cable"),
            preparation = listOf(
                usbDeviceChoiceStep("Finding the plugged-in board"),
                PreparationStep("Checking USB permission") { context ->
                    ensureUsbPermission(context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val name = usb.selectedDevice?.let { usbDeviceDisplay(it) } ?: "board"
                usb.connect()
                val connected = waitUntil(10000) { usb.isConnected }
                if (!connected) {
                    return@Operation OpResult(
                        false,
                        usb.usbStatus.ifBlank { "Connection failed — try again." }
                    )
                }
                // Speed found automatically (74880/230400 included).
                usb.autoBaud()
                waitUntil(2000) { usb.isAutoBaudRunning }
                waitUntil(20000) { !usb.isAutoBaudRunning }
                val baudNote =
                    if (Regex("AUTO-BAUD: ${usb.baudRate} looks right").containsMatchIn(usb.rawLog)) {
                        "The right speed was found by itself: ${usb.baudRate}."
                    } else {
                        "The speed couldn't be found by itself — it stays at ${usb.baudRate}. " +
                            "Set it by hand if replies look like garbage."
                    }
                OpResult(
                    true,
                    "Connected to $name. $baudNote",
                    "usb-connected"
                )
            },
            verify = { context, result ->
                verifyStillConnected(TransportKind.USB, "", context)
            },
            retry = RetryPolicy(maxAttempts = 2, delayMs = 2000L),
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Disconnect from the board (D-05 + B-101). ----
    OperationRegistry.register(
        Operation(
            id = "usb.disconnect-board",
            title = "Disconnect the board",
            description = "Closes the USB cable link to the board.",
            area = OperationArea.USB,
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                if (!usb.isConnected) {
                    return@Operation OpResult(false, "Nothing is connected — nothing to disconnect.")
                }
                usb.disconnect()
                OpResult(true, "Disconnected — the board is unplugged in software.", "usb-off")
            },
            verify = { context, _ ->
                val usb = context.usb
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Can't check USB — the app isn't ready yet."
                    )
                if (!usb.isConnected) VerificationOutcome.Verified("The USB link is closed.")
                else VerificationOutcome.NotVerified("Still connected.")
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Find the right speed automatically (D-12 + B-103, F-35). ----
    OperationRegistry.register(
        Operation(
            id = "usb.find-speed",
            title = "Find the right speed by itself",
            description = "Tries the speeds one by one — including 74880 for start-up messages and 230400, common on ESP32 boards — and picks the one where replies come back as clean text.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.autoBaud()
                waitUntil(2000) { usb.isAutoBaudRunning }
                val finished = waitUntil(20000) { !usb.isAutoBaudRunning }
                if (!finished) {
                    return@Operation OpResult(
                        false,
                        "Still trying speeds after 20 seconds — the board may not be answering at all."
                    )
                }
                val found = Regex("AUTO-BAUD: (\\d+) looks right")
                    .findAll(usb.rawLog).lastOrNull()
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (found != null && found == usb.baudRate) {
                    OpResult(
                        true,
                        "Found speed $found — replies come back as clean text now.",
                        "baud:$found"
                    )
                } else {
                    OpResult(
                        false,
                        "Couldn't find a clean speed — the speed stays at ${usb.baudRate}. Set it by hand."
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Change the speed (D-12 + B-102). ----
    OperationRegistry.register(
        Operation(
            id = "usb.set-speed",
            title = "Change the speed",
            description = "Sets how fast the board talks — 74880 is what boards use for start-up messages, 230400 is common on ESP32 boards.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Which speed?") { context ->
                    val choices = listOf(
                        "9600", "19200", "38400", "57600",
                        "74880", "115200", "230400", "460800"
                    )
                    val picked = context.pendingChoice
                    if (picked != null && picked in choices) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.NeedsUserChoice(
                            prompt = "Which speed? 74880 is what boards use for their start-up messages; 230400 is common on ESP32 boards.",
                            choices = choices
                        )
                    }
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val rate = context.pendingChoice?.toIntOrNull()
                    ?: return@Operation OpResult(false, "Pick a speed first.")
                context.pendingChoice = null
                usb.changeBaudRate(rate)
                OpResult(true, "Speed set to $rate.", "baud:$rate")
            },
            verify = { context, result ->
                val usb = context.usb
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Can't check USB — the app isn't ready yet."
                    )
                val rate = result.data.substringAfter("baud:", "").toIntOrNull()
                if (rate != null && usb.baudRate == rate) {
                    VerificationOutcome.Verified("Speed is $rate.")
                } else {
                    VerificationOutcome.NotVerified(
                        "The speed didn't stay at ${rate ?: "?"}."
                    )
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Talk to the board (D-10, D-11 + B-104, B-105). ----
    // Terminal as an operation: pick a quick message or one sent
    // before, send it, and watch for the reply. History and presets
    // are the new layer; the existing send/read do the talking.
    OperationRegistry.register(
        Operation(
            id = "usb.talk-to-board",
            title = "Send a message to the board",
            description = "Picks a quick message — or one you sent before — sends it to the board, and reports what comes back.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                },
                PreparationStep("What should it say?") { context ->
                    val usb = context.usb
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check USB — the app isn't ready yet."
                        )
                    val presetLabels = usb.commandPresets.map { (text, does) -> "$text — $does" }
                    val choices = presetLabels + usb.commandHistory
                    if (choices.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Nothing to send yet — type a message on the USB screen first."
                        )
                    }
                    val picked = context.pendingChoice
                    if (picked != null && picked in choices) {
                        Area5State.stashedMessage = picked
                        context.pendingChoice = null
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (Area5State.stashedMessage != null &&
                        Area5State.stashedMessage in choices
                    ) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "What should it say?",
                        choices = choices
                    )
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val picked = Area5State.stashedMessage
                    ?: return@Operation OpResult(false, "Pick a message first.")
                Area5State.stashedMessage = null
                val message = usb.commandPresets
                    .firstOrNull { (text, does) -> "$text — $does" == picked }?.first
                    ?: picked
                val rxBefore = usb.rxText.length
                usb.updateTxText(message)
                usb.sendTx()
                val sent = waitUntil(5000) { "PHONE → ESP32: $message" in usb.rxText }
                if (!sent) {
                    return@Operation OpResult(
                        false,
                        "Couldn't send '$message' — the board may have disconnected."
                    )
                }
                val replied = waitUntil(5000) {
                    usb.rxText.length > rxBefore &&
                        "ESP32 → PHONE:" in usb.rxText.drop(rxBefore)
                }
                val reply = if (replied) {
                    usb.rxText.drop(rxBefore)
                        .lines().lastOrNull { "ESP32 → PHONE:" in it }
                        ?.substringAfter("ESP32 → PHONE:")?.trim().orEmpty()
                } else {
                    ""
                }
                OpResult(
                    true,
                    if (reply.isNotBlank()) "Sent '$message'. The board answered: $reply"
                    else "Sent '$message'. Nothing came back in 5 seconds — sent doesn't mean the board acted on it.",
                    "usb-sent"
                )
            },
            verify = { context, result ->
                val usb = context.usb
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Can't check USB — the app isn't ready yet."
                    )
                if ("PHONE → ESP32:" in usb.rxText) {
                    VerificationOutcome.Verified("The message left the phone.")
                } else {
                    VerificationOutcome.NotVerified("The message never left the phone.")
                }
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Watch for replies (D-11 + B-108). ----
    OperationRegistry.register(
        Operation(
            id = "usb.watch-replies",
            title = "Is the board talking back?",
            description = "Watches the cable for 10 seconds and reports anything the board sends back.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val before = usb.rxText.length
                val arrived = waitUntil(10000) { usb.rxText.length > before }
                if (!arrived) {
                    return@Operation OpResult(
                        false,
                        "Nothing came back in 10 seconds — the board may only listen, or it may need a message first."
                    )
                }
                val fresh = usb.rxText.substring(before).trim().take(300)
                OpResult(true, "The board sent back: $fresh", "usb-rx")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Clear the terminal (B-106). ----
    OperationRegistry.register(
        Operation(
            id = "usb.clear-messages",
            title = "Clear the message box",
            description = "Clears the messages shown in the message box. The raw log underneath is kept.",
            area = OperationArea.USB,
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.clearTerminal()
                OpResult(true, "Message box cleared.", "usb-cleared")
            },
            verify = { context, _ ->
                val usb = context.usb
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Can't check USB — the app isn't ready yet."
                    )
                if (usb.rxText.isEmpty()) VerificationOutcome.Verified("The message box is empty.")
                else VerificationOutcome.NotVerified("The message box didn't clear.")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Switch message mode (B-104). ----
    OperationRegistry.register(
        Operation(
            id = "usb.switch-message-mode",
            title = "Switch between words and code",
            description = "Switches the message box between plain words and code pairs — what you type is sent as words, or as code the board reads as bytes.",
            area = OperationArea.USB,
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.toggleHexMode()
                OpResult(
                    true,
                    if (usb.hexMode) "Now sending as code — type pairs like 48 65 6C 6C 6F."
                    else "Now sending as plain words — what you type is what the board gets.",
                    "usb-mode:${if (usb.hexMode) "code" else "words"}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Newline on/off (D-10). ----
    OperationRegistry.register(
        Operation(
            id = "usb.toggle-newline",
            title = "Add a new line to every message",
            description = "Switches whether every message ends with a new line. Most boards only answer when the message ends with one.",
            area = OperationArea.USB,
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.toggleSendNewline()
                OpResult(
                    true,
                    if (usb.sendNewline) "Messages now end with a new line — most boards need that to answer."
                    else "Messages are sent exactly as typed, with nothing added.",
                    "usb-newline:${usb.sendNewline}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Clear the sent-message history (D-10). ----
    OperationRegistry.register(
        Operation(
            id = "usb.clear-history",
            title = "Forget the sent messages",
            description = "Clears the list of messages sent before, so they stop being offered to send again.",
            area = OperationArea.USB,
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.clearCommandHistory()
                OpResult(true, "Sent-message history cleared.", "usb-history-cleared")
            },
            verify = { context, _ ->
                val usb = context.usb
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Can't check USB — the app isn't ready yet."
                    )
                if (usb.commandHistory.isEmpty()) VerificationOutcome.Verified("The history is empty.")
                else VerificationOutcome.NotVerified("The history didn't clear.")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- What the control wires do (D-13). ----
    OperationRegistry.register(
        Operation(
            id = "usb.wires-what-they-do",
            title = "What do the control wires do?",
            description = "Explains the two control wires in plain words and says what each one is doing right now — no guessing what DTR and RTS mean.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val reset = if (usb.rtsAsserted) "ON — the board is held in reset" else "OFF — the board can run"
                val boot = if (usb.dtrAsserted) "ON — the board will start in load mode next time it restarts" else "OFF — the board will start normally"
                OpResult(
                    true,
                    "Reset wire: $reset. Boot wire: $boot. " +
                        "The reset wire restarts the board; the boot wire picks how it starts.",
                    "usb-wires"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Flip the reset wire (D-13 + B-107, RTS). ----
    OperationRegistry.register(
        Operation(
            id = "usb.flip-reset-wire",
            title = "Flip the reset wire",
            description = "Switches the reset wire on or off. On holds the board in reset; off lets it run.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val target = !usb.rtsAsserted
                usb.setRts(target)
                val settled = waitUntil(3000) { usb.rtsAsserted == target }
                if (!settled) {
                    return@Operation OpResult(false, "The reset wire didn't switch — the board may have disconnected.")
                }
                OpResult(
                    true,
                    if (target) "Reset wire ON — the board is held in reset."
                    else "Reset wire OFF — the board can run.",
                    "usb-rts:$target"
                )
            },
            riskLevel = "CONTROL",
            verified = false
        )
    )

    // ---- Flip the boot wire (D-13 + B-107, DTR). ----
    OperationRegistry.register(
        Operation(
            id = "usb.flip-boot-wire",
            title = "Flip the boot wire",
            description = "Switches the boot wire on or off. On means the board starts in load mode next time it restarts; off means it starts normally.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val target = !usb.dtrAsserted
                usb.setDtr(target)
                val settled = waitUntil(3000) { usb.dtrAsserted == target }
                if (!settled) {
                    return@Operation OpResult(false, "The boot wire didn't switch — the board may have disconnected.")
                }
                OpResult(
                    true,
                    if (target) "Boot wire ON — the board will start in load mode next time it restarts."
                    else "Boot wire OFF — the board will start normally.",
                    "usb-dtr:$target"
                )
            },
            riskLevel = "CONTROL",
            verified = false
        )
    )

    // ---- Test the board (D-14, D-15 + B-110). ----
    OperationRegistry.register(
        Operation(
            id = "usb.test-board",
            title = "Test if the board answers",
            description = "Asks the board if it answers, and names the chip when it can be read — never guessed.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.testEsp32()
                waitForEsp32Test(usb)
                if (usb.esp32Status.startsWith("The board answered")) {
                    OpResult(
                        true,
                        "${usb.esp32Status} Chip: ${usb.esp32ChipInfo ?: "Board details unavailable"}.",
                        "usb-answered"
                    )
                } else {
                    OpResult(false, usb.esp32Status)
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Read the board's ID register (D-16). ----
    OperationRegistry.register(
        Operation(
            id = "usb.read-board-setting",
            title = "Read the board's ID",
            description = "Reads the one board setting the app can honestly explain — the chip's ID — and names the chip when it matches one Ghost Tap knows.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.readChipMagicRegister()
                val ready = waitUntil(12000) { usb.lastRegisterValue != null }
                val value = usb.lastRegisterValue
                if (!ready || value == null) {
                    return@Operation OpResult(
                        false,
                        "The board's ID didn't come back in time."
                    )
                }
                if (value.startsWith("Couldn't") || value.startsWith("Connect") ||
                    value.startsWith("No USB") || value.startsWith("The board didn't")
                ) {
                    return@Operation OpResult(false, value)
                }
                OpResult(true, value, "usb-chip-id")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Put the board in load mode, then test it (D-17 + B-111). ----
    // The two manual steps chained: load mode, then the test the user
    // used to have to remember afterwards.
    OperationRegistry.register(
        Operation(
            id = "usb.put-in-load-mode",
            title = "Put the board in load mode",
            description = "Puts the board in load mode — the state new software needs — then checks it answers, so you don't have to remember the test afterwards.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.enterDownloadMode()
                val modeOk = waitForDownloadMode(usb)
                if (!modeOk || usb.esp32Status.contains("Couldn't start download mode")) {
                    return@Operation OpResult(
                        false,
                        "Couldn't put the board in load mode — some boards need you to hold the BOOT button while it restarts."
                    )
                }
                usb.testEsp32()
                waitForEsp32Test(usb)
                if (usb.esp32Status.startsWith("The board answered")) {
                    OpResult(
                        true,
                        "Load mode started, and the board answered the check — ready to load software.",
                        "usb-load-mode"
                    )
                } else {
                    OpResult(
                        false,
                        "Load mode started, but the board didn't answer the check — ${usb.esp32Status}. " +
                            "Some boards need you to hold the BOOT button while it restarts."
                    )
                }
            },
            riskLevel = "CONTROL",
            verified = false
        )
    )

    // ---- Restart the board (D-18 + B-112). ----
    OperationRegistry.register(
        Operation(
            id = "usb.restart-board",
            title = "Restart the board",
            description = "Restarts the board, then listens briefly for it talking again so the restart is confirmed, not assumed.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val rxBefore = usb.rxText.length
                usb.resetBoard()
                val sent = waitUntil(5000) { "Reset sent" in usb.rawLog }
                if (!sent) {
                    return@Operation OpResult(false, "Couldn't send the restart — the board may have disconnected.")
                }
                val talked = waitUntil(5000) { usb.rxText.length > rxBefore }
                OpResult(
                    true,
                    if (talked) "Restart sent — the board is talking again."
                    else "Restart sent — the board didn't say anything back yet. Some boards stay quiet after a restart.",
                    "usb-restarted"
                )
            },
            riskLevel = "CONTROL",
            verified = false
        )
    )

    // ---- Check the software file (D-20 + B-113). ----
    OperationRegistry.register(
        Operation(
            id = "usb.check-software-file",
            title = "Check the software file",
            description = "Checks the picked file is a real board file before it goes anywhere near the board — a wrong file is caught here, not at load time.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking a file is picked") { context ->
                    val usb = context.usb
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check USB — the app isn't ready yet."
                        )
                    if (usb.firmwareUri == null) {
                        PreparationOutcome.NeedsUserAction(
                            prompt = "Pick a software file first.",
                            actionLabel = "Pick a file",
                            action = { context.selectFirmware() }
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val result = usb.validateFirmwareNow()
                    ?: return@Operation OpResult(
                        false,
                        "Couldn't read that file — pick it again."
                    )
                if (!result.valid) {
                    return@Operation OpResult(
                        false,
                        "This file failed the check: ${result.issues.joinToString("; ")} — pick a different file."
                    )
                }
                val notes = if (result.issues.isEmpty()) "" else " Notes: ${result.issues.joinToString("; ")}."
                OpResult(
                    true,
                    "Checked: ${result.summary}.$notes",
                    "usb-file-ok"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Pick a software file (B-113). ----
    OperationRegistry.register(
        Operation(
            id = "usb.pick-software-file",
            title = "Pick a software file",
            description = "Opens the file picker to choose the software file (.bin) to load onto the board. Once picked, it is checked automatically.",
            area = OperationArea.USB,
            execute = { context ->
                context.selectFirmware()
                OpResult(
                    true,
                    "File picker open — pick the software file. Once picked, it is checked automatically.",
                    "usb-picker"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Put new software on the board (D-14–D-20 + B-110–B-114). ----
    // ONE guided flash flow: pick file → check (D-20) → load mode
    // (D-17) → test (D-15, real chip check) → confirm → flash (D-19)
    // → read back and compare (MD5, F-34) → restart. The existing
    // flasher/protocol functions do each step unchanged; the operation
    // only chains and confirms them.
    OperationRegistry.register(
        Operation(
            id = "usb.load-software",
            title = "Put new software on the board",
            description = "Loads new software onto the board in one guided flow: checks the file, puts the board in load mode, tests it, loads it, then reads it back to make sure it matches.",
            area = OperationArea.USB,
            prerequisites = listOf(
                "A board plugged in and connected",
                "A software file (.bin) picked"
            ),
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                },
                PreparationStep("Checking a file is picked") { context ->
                    val usb = context.usb
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check USB — the app isn't ready yet."
                        )
                    if (usb.firmwareUri == null) {
                        PreparationOutcome.NeedsUserAction(
                            prompt = "Pick a software file first.",
                            actionLabel = "Pick a file",
                            action = { context.selectFirmware() }
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                },
                PreparationStep("Checking the file is a real board file") { context ->
                    val usb = context.usb
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check USB — the app isn't ready yet."
                        )
                    val result = usb.validateFirmwareNow()
                    Area5State.stashedValidation = result
                    when {
                        result == null -> PreparationOutcome.Failed(
                            "Couldn't read that file — pick it again."
                        )
                        !result.valid -> PreparationOutcome.Failed(
                            "This file failed the check: ${result.issues.joinToString("; ")} — pick a different file."
                        )
                        else -> PreparationOutcome.Ready
                    }
                },
                PreparationStep("Confirming the load") { context ->
                    val usb = context.usb
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check USB — the app isn't ready yet."
                        )
                    val picked = context.pendingChoice
                    if (picked == "Load it onto the board") {
                        context.pendingChoice = null
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (picked == "Not now") {
                        context.pendingChoice = null
                        return@PreparationStep PreparationOutcome.Failed(
                            "You chose not to load it — nothing was changed."
                        )
                    }
                    val validation = Area5State.stashedValidation
                    val name = usb.firmwareName ?: "firmware.bin"
                    val sizeKb = usb.firmwareSize / 1024
                    val check = validation?.summary ?: "not checked yet"
                    val spot = flashSpotLabel(usb.flashOffset)
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Load $name ($sizeKb KB) onto the board? " +
                            "This wipes the board's old software. " +
                            "Check: $check. Where on the board: $spot." +
                            flashSpotWarning(usb.flashOffset),
                        choices = listOf("Load it onto the board", "Not now")
                    )
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.enterDownloadMode()
                val modeOk = waitForDownloadMode(usb)
                if (!modeOk || usb.esp32Status.contains("Couldn't start download mode")) {
                    return@Operation OpResult(
                        false,
                        "Couldn't put the board in load mode — some boards need you to hold the BOOT button while it restarts."
                    )
                }
                usb.testEsp32()
                waitForEsp32Test(usb)
                if (!usb.esp32Status.startsWith("The board answered")) {
                    return@Operation OpResult(
                        false,
                        "The board didn't answer — ${usb.esp32Status}."
                    )
                }
                val chip = usb.esp32ChipInfo ?: "Board details unavailable"
                val beforeFlash = usb.flashProgress
                usb.confirmFlash()
                val finished = waitUntil(180000, 1000) {
                    val p = usb.flashProgress ?: ""
                    p != beforeFlash && (
                        p.startsWith("Done") || p.startsWith("Didn't") ||
                            p.startsWith("Couldn't") || p.contains("isn't answering") ||
                            p.contains("No USB")
                        )
                }
                if (!finished) {
                    return@Operation OpResult(
                        false,
                        "Loading is still going after 3 minutes — check the USB screen for the progress."
                    )
                }
                val progress = usb.flashProgress ?: ""
                if (!progress.startsWith("Done")) {
                    return@Operation OpResult(
                        false,
                        progress.ifBlank { "Loading didn't work." }
                    )
                }
                usb.verifyFlashMd5()
                val checked = waitUntil(45000) { usb.flashVerifyResult != null }
                val verdict = usb.flashVerifyResult
                if (!checked || verdict == null) {
                    return@Operation OpResult(
                        false,
                        "The file went on, but the check didn't finish — " +
                            "run 'Check the loaded software' to try the check again."
                    )
                }
                if (verdict.startsWith("Matches")) {
                    OpResult(
                        true,
                        "Loaded and checked — the board holds exactly what was sent. " +
                            "Chip: $chip. The board has restarted.",
                        "flash-verified:${usb.firmwareName}"
                    )
                } else {
                    OpResult(
                        false,
                        "The file went on, but the check says: $verdict"
                    )
                }
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Check the loaded software (D-19 / F-34 + B-114). ----
    OperationRegistry.register(
        Operation(
            id = "usb.verify-loaded-software",
            title = "Check the loaded software",
            description = "Reads back what is on the board and compares it with the file — a mismatch is reported plainly, never smoothed over.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the board is connected") { context ->
                    ensureConnected(TransportKind.USB, "", context)
                },
                PreparationStep("Checking a file is picked") { context ->
                    val usb = context.usb
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check USB — the app isn't ready yet."
                        )
                    if (usb.firmwareUri == null) {
                        PreparationOutcome.Failed(
                            "Pick a software file first — the check compares the board against that file."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.verifyFlashMd5()
                val checked = waitUntil(45000) { usb.flashVerifyResult != null }
                val verdict = usb.flashVerifyResult
                if (!checked || verdict == null) {
                    return@Operation OpResult(
                        false,
                        "The check didn't finish in time — try again."
                    )
                }
                if (verdict.startsWith("Matches")) {
                    OpResult(true, verdict, "usb-verified")
                } else {
                    OpResult(false, verdict)
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Pick where on the board to write (D-19 offset). ----
    OperationRegistry.register(
        Operation(
            id = "usb.set-flash-spot",
            title = "Pick where on the board to write",
            description = "Picks where the software goes on the board. The app spot is the usual one; the other spots are only for bootloader or settings-table files, and the wrong spot can stop the board from starting.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Which spot?") { context ->
                    val choices = FLASH_SPOTS.map { it.second }
                    val picked = context.pendingChoice
                    if (picked != null && picked in choices) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.NeedsUserChoice(
                            prompt = "Where on the board should the software go? The app spot is the usual one.",
                            choices = choices
                        )
                    }
                }
            ),
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val picked = context.pendingChoice
                    ?: return@Operation OpResult(false, "Pick a spot first.")
                context.pendingChoice = null
                val offset = FLASH_SPOTS.firstOrNull { it.second == picked }?.first
                    ?: return@Operation OpResult(false, "Pick a spot first.")
                usb.pickFlashOffset(offset)
                OpResult(
                    true,
                    "Writing spot set to ${flashSpotLabel(offset)}." +
                        flashSpotWarning(offset),
                    "usb-offset:0x${offset.toString(16).uppercase()}"
                )
            },
            verify = { context, result ->
                val usb = context.usb
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Can't check USB — the app isn't ready yet."
                    )
                val offset = result.data.substringAfter("usb-offset:0x", "")
                    .toIntOrNull(16)
                if (offset != null && usb.flashOffset == offset) {
                    VerificationOutcome.Verified("Writing spot is ${flashSpotLabel(offset)}.")
                } else {
                    VerificationOutcome.NotVerified("The writing spot didn't stay set.")
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Copy the log (B-109). ----
    OperationRegistry.register(
        Operation(
            id = "usb.copy-log",
            title = "Copy the USB log",
            description = "Copies the USB details log to the clipboard so it can be pasted somewhere else.",
            area = OperationArea.USB,
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                val logText = usb.copyLog()
                if (logText.isBlank()) {
                    return@Operation OpResult(false, "Nothing logged yet — nothing to copy.")
                }
                val app = context.ble?.getApplication<Application>()
                var copied = false
                if (app != null) {
                    runCatching {
                        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("USB log", logText))
                        copied = true
                    }
                }
                if (copied) {
                    OpResult(
                        true,
                        "Log copied to the clipboard — ${logText.lines().size} line(s).",
                        "usb-log-copied"
                    )
                } else {
                    OpResult(false, "Couldn't copy the log — try COPY THE LOG on the USB screen.")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Show/hide the details (B-109). ----
    OperationRegistry.register(
        Operation(
            id = "usb.show-details",
            title = "Show or hide the USB details",
            description = "Opens or closes the raw details log on the USB screen.",
            area = OperationArea.USB,
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.toggleDetails()
                OpResult(
                    true,
                    if (usb.showDetails) "Details shown — the raw log is below."
                    else "Details hidden.",
                    "usb-details:${usb.showDetails}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Is the practice-board app installed? (D-21). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.check-installed",
            title = "Is the practice-board app installed?",
            description = "Checks whether the practice-board app is on this phone — the practice board lives in that app, not in Ghost Tap.",
            area = OperationArea.USB,
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                if (client.isInstalled()) {
                    OpResult(true, "The practice-board app is on this phone.", "vesp32-installed")
                } else {
                    OpResult(
                        false,
                        "The practice-board app isn't on this phone yet — install 'Virtual ESP32' first, then tap this again."
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Connect to the practice board (D-22). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.connect-practice-board",
            title = "Connect to the practice board",
            description = "Opens the link to the practice board in the other app, then checks the link is really open.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the practice-board app is installed") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.isInstalled()) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "The practice-board app isn't on this phone yet — install 'Virtual ESP32' first, then tap this again."
                    )
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                client.connect()
                val connected = waitUntil(5000) {
                    client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected
                }
                if (connected) {
                    OpResult(true, "Connected to the practice board.", "vesp32-connected")
                } else {
                    OpResult(
                        false,
                        "Couldn't connect to the practice board — the practice-board app may not be running."
                    )
                }
            },
            verify = { context, _ ->
                val client = context.vesp32
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "The practice board isn't available right now."
                    )
                if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected) {
                    VerificationOutcome.Verified("The practice-board link is open.")
                } else {
                    VerificationOutcome.NotVerified("The practice-board link closed.")
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Disconnect from the practice board (D-22). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.disconnect-practice-board",
            title = "Disconnect the practice board",
            description = "Closes the link to the practice board.",
            area = OperationArea.USB,
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                client.disconnect()
                OpResult(true, "Disconnected from the practice board.", "vesp32-off")
            },
            verify = { context, _ ->
                val client = context.vesp32
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "The practice board isn't available right now."
                    )
                if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Disconnected) {
                    VerificationOutcome.Verified("The practice-board link is closed.")
                } else {
                    VerificationOutcome.NotVerified("Still connected.")
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Use the practice board (D-21–D-27 + D-29). ----
    // ONE flow: install check → connect → load software → start.
    // When no software is loaded yet, the built-in test software is
    // used and the operation says so plainly — picking your own file
    // still lives on the practice-board screen. The two validation
    // results are reconciled into one verdict on that screen (D-29).
    OperationRegistry.register(
        Operation(
            id = "vesp32.use-practice-board",
            title = "Use the practice board",
            description = "Does the whole practice-board flow in one tap: checks the app is installed, connects, loads software, and starts it.",
            area = OperationArea.USB,
            prerequisites = listOf("The practice-board app installed"),
            preparation = listOf(
                PreparationStep("Checking the practice-board app is installed") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.isInstalled()) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "The practice-board app isn't on this phone yet — install 'Virtual ESP32' first, then tap this again."
                    )
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                client.connect()
                val connected = waitUntil(5000) {
                    client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected
                }
                if (!connected) {
                    return@Operation OpResult(
                        false,
                        "Couldn't connect to the practice board — the practice-board app may not be running."
                    )
                }
                val loadedName = client.firmwareInfo.value?.fileName
                if (loadedName == null) {
                    client.useBuiltinFirmware()
                    val loaded = waitUntil(10000) { client.firmwareInfo.value != null }
                    if (!loaded) {
                        return@Operation OpResult(
                            false,
                            "The built-in test software didn't load — the practice-board app didn't answer."
                        )
                    }
                }
                val software = client.firmwareInfo.value
                client.startGuest()
                val started = waitUntil(8000) { client.targetInfo.value != null }
                if (!started) {
                    val hint = client.uartLog.value
                        .lastOrNull { "no response" in it }
                    return@Operation OpResult(
                        false,
                        hint ?: "The practice board started but didn't report back — " +
                            "check the practice-board app is running and software is loaded in it."
                    )
                }
                val info = client.targetInfo.value
                val softwareNote = if (loadedName == null) {
                    "No software was loaded yet, so the built-in test software is used — " +
                        "pick your own file on the practice-board screen to use that instead."
                } else {
                    "Running the software already loaded: $loadedName."
                }
                OpResult(
                    true,
                    "Practice board running — status: ${info?.status ?: "not saying"}, " +
                        "software: ${info?.firmware ?: software?.fileName ?: "not saying"}. $softwareNote",
                    "vesp32-running"
                )
            },
            verify = { context, _ ->
                val client = context.vesp32
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "The practice board isn't available right now."
                    )
                val info = client.targetInfo.value
                if (info != null) {
                    VerificationOutcome.Verified(
                        "Practice board reporting: status ${info.status}, software ${info.firmware}."
                    )
                } else {
                    VerificationOutcome.NotVerified(
                        "The practice board isn't reporting back."
                    )
                }
            },
            riskLevel = "CONTROL",
            verified = false
        )
    )

    // ---- Start the practice board's software (D-23). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.start-practice-software",
            title = "Start the practice board's software",
            description = "Starts the software loaded on the practice board, then waits for it to report back.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the practice board is connected") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.NeedsUserAction(
                            prompt = "Connect to the practice board first.",
                            actionLabel = "Connect",
                            action = { client.connect() }
                        )
                    }
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                client.startGuest()
                val started = waitUntil(8000) { client.targetInfo.value != null }
                if (started) {
                    val info = client.targetInfo.value
                    OpResult(
                        true,
                        "Practice board started — status: ${info?.status ?: "not saying"}.",
                        "vesp32-started"
                    )
                } else {
                    val hint = client.uartLog.value.lastOrNull { "no response" in it }
                    OpResult(
                        false,
                        hint ?: "The practice board started but didn't report back — " +
                            "check software is loaded in the practice-board app."
                    )
                }
            },
            riskLevel = "CONTROL",
            verified = false
        )
    )

    // ---- Stop the practice board's software (D-23). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.stop-practice-software",
            title = "Stop the practice board's software",
            description = "Stops the software running on the practice board.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the practice board is connected") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.Failed("Not connected to the practice board — nothing to stop.")
                    }
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                client.stopGuest()
                delay(2000)
                val info = client.targetInfo.value
                OpResult(
                    true,
                    "Stop sent to the practice board." +
                        (info?.let { " It now reports: ${it.status}." } ?: ""),
                    "vesp32-stopped"
                )
            },
            riskLevel = "CONTROL",
            verified = false
        )
    )

    // ---- Restart the practice board's software (D-23). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.restart-practice-software",
            title = "Restart the practice board's software",
            description = "Restarts the software running on the practice board.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the practice board is connected") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.Failed("Not connected to the practice board — nothing to restart.")
                    }
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                client.resetGuest()
                delay(2000)
                OpResult(true, "Restart sent to the practice board.", "vesp32-restarted")
            },
            riskLevel = "CONTROL",
            verified = false
        )
    )

    // ---- Send a message to the practice board (D-24). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.send-message",
            title = "Send a message to the practice board",
            description = "Sends a quick message to the practice board and reports what comes back.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the practice board is connected") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.NeedsUserAction(
                            prompt = "Connect to the practice board first.",
                            actionLabel = "Connect",
                            action = { client.connect() }
                        )
                    }
                },
                PreparationStep("What should it say?") { context ->
                    val messages = listOf(
                        "PING — ask if it's listening",
                        "HELLO — say hello",
                        "STATUS — ask what it's doing"
                    )
                    val picked = context.pendingChoice
                    if (picked != null && picked in messages) {
                        Area5State.stashedMessage = picked
                        context.pendingChoice = null
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (Area5State.stashedMessage != null &&
                        Area5State.stashedMessage in messages
                    ) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "What should it say?",
                        choices = messages
                    )
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                val picked = Area5State.stashedMessage
                    ?: return@Operation OpResult(false, "Pick a message first.")
                Area5State.stashedMessage = null
                val message = picked.substringBefore(" — ")
                val logBefore = client.uartLog.value.size
                client.sendUart(message)
                val sent = waitUntil(3000) {
                    client.uartLog.value.any { it == "TX: $message" }
                }
                if (!sent) {
                    return@Operation OpResult(false, "Couldn't send '$message' — the practice board may have disconnected.")
                }
                val replied = waitUntil(5000) {
                    client.uartLog.value.drop(logBefore).any { it.startsWith("RX:") }
                }
                val reply = if (replied) {
                    client.uartLog.value.drop(logBefore)
                        .lastOrNull { it.startsWith("RX:") }
                        ?.removePrefix("RX:")?.trim().orEmpty()
                } else {
                    ""
                }
                OpResult(
                    true,
                    if (reply.isNotBlank()) "Sent '$message'. The practice board answered: $reply"
                    else "Sent '$message'. Nothing came back in 5 seconds.",
                    "vesp32-sent"
                )
            },
            verify = { context, _ ->
                val client = context.vesp32
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "The practice board isn't available right now."
                    )
                if (client.uartLog.value.any { it.startsWith("TX:") }) {
                    VerificationOutcome.Verified("The message was sent to the practice board.")
                } else {
                    VerificationOutcome.NotVerified("The message never left the phone.")
                }
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Check the practice board's light pin (D-25). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.check-light-pin",
            title = "What is the practice board's light doing?",
            description = "Reads pin 2 — the board's output — and says whether it is on or off.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the practice board is connected") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.Failed("Not connected to the practice board — connect first.")
                    }
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                client.readGpio2()
                val answered = waitUntil(5000) { client.gpio2State.value != null }
                val pin = client.gpio2State.value
                if (!answered || pin == null) {
                    return@Operation OpResult(
                        false,
                        "Pin 2 didn't answer — the practice board's software may not be running."
                    )
                }
                OpResult(
                    true,
                    "The board's light pin is ${if (pin) "ON" else "OFF"}.",
                    "vesp32-pin2:$pin"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Flip the practice board's switch pin (D-25). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.flip-switch-pin",
            title = "Flip the practice board's switch",
            description = "Flips pin 4 — your switch into the practice board — on or off.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the practice board is connected") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.Failed("Not connected to the practice board — connect first.")
                    }
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                val target = !(client.gpio4State.value ?: false)
                client.setGpio4(target)
                val settled = waitUntil(3000) { client.gpio4State.value == target }
                if (!settled) {
                    return@Operation OpResult(false, "The switch didn't flip — the practice board may have disconnected.")
                }
                OpResult(
                    true,
                    "Your switch into the practice board is now ${if (target) "ON" else "OFF"}.",
                    "vesp32-pin4:$target"
                )
            },
            riskLevel = "CONTROL",
            verified = false
        )
    )

    // ---- Use the built-in test software (D-26). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.use-built-in-software",
            title = "Use the built-in test software",
            description = "Loads the built-in test software onto the practice board — no file to pick.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the practice board is connected") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.NeedsUserAction(
                            prompt = "Connect to the practice board first.",
                            actionLabel = "Connect",
                            action = { client.connect() }
                        )
                    }
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                client.useBuiltinFirmware()
                val loaded = waitUntil(10000) { client.firmwareInfo.value?.isBuiltin == true }
                if (!loaded) {
                    return@Operation OpResult(
                        false,
                        "The built-in test software didn't load — the practice-board app didn't answer."
                    )
                }
                val info = client.firmwareInfo.value
                OpResult(
                    true,
                    "Built-in test software loaded: ${info?.fileName ?: "test software"}. " +
                        "Check: ${info?.validationSummary ?: "no check reported"}.",
                    "vesp32-builtin"
                )
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Ask the practice board how it is doing (D-27). ----
    OperationRegistry.register(
        Operation(
            id = "vesp32.check-practice-status",
            title = "How is the practice board doing?",
            description = "Asks the practice board for its status — what it is doing, what processor it pretends to be, and what software it is running — in plain words.",
            area = OperationArea.USB,
            preparation = listOf(
                PreparationStep("Checking the practice board is connected") { context ->
                    val client = context.vesp32
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "The practice board isn't available right now."
                        )
                    if (client.connectionState.value is VirtualEsp32Client.ConnectionState.Connected) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.Failed("Not connected to the practice board — connect first.")
                    }
                }
            ),
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                client.queryStatus()
                val answered = waitUntil(5000) { client.targetInfo.value != null }
                val info = client.targetInfo.value
                if (!answered || info == null) {
                    return@Operation OpResult(
                        false,
                        "The practice board didn't report back — its software may not be running."
                    )
                }
                fun say(value: String): String = if (value == "?") "not saying" else value
                OpResult(
                    true,
                    "Status: ${say(info.status)} — Processor: ${say(info.cpu)} — " +
                        "Running on: ${say(info.backend)} — Software: ${say(info.firmware)}.",
                    "vesp32-status"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Find what's on my Wi-Fi (B-086). ----
    OperationRegistry.register(
        Operation(
            id = "net.find-on-wifi",
            title = "Find what's on my Wi-Fi",
            description = "Finds every live device on your Wi-Fi by itself — no typing — and lists them.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                PreparationStep("Checking no network test is running") { context ->
                    val net = context.net
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Network tools aren't available right now."
                        )
                    if (net.isBusy || net.isScanningNetwork) {
                        PreparationOutcome.Failed(
                            "Another network test is still running — wait for it to finish, then try again."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                net.scanMyNetwork()
                val finished = waitUntil(90000) { !net.isScanningNetwork }
                if (!finished) {
                    return@Operation OpResult(
                        false,
                        "Still looking after 90 seconds — the network may be very slow."
                    )
                }
                val hosts = net.liveHosts
                if (hosts.isEmpty()) {
                    OpResult(
                        true,
                        "Done — no devices answered on this network. " +
                            "Some devices ignore this kind of check even when they are on.",
                        "net-none"
                    )
                } else {
                    OpResult(
                        true,
                        "Found ${hosts.size} live device(s): ${hosts.joinToString(", ")}. " +
                            "Tap one on the Network Tools screen to see its open ports.",
                        "net:${hosts.size}"
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Find what's on my Wi-Fi and connect to it (B-086 + B-087 + B-089 + A-24). ----
    // ONE step: scan → host → open ports → prefill-and-send. The
    // existing scan/host/port/send functions do each step unchanged.
    OperationRegistry.register(
        Operation(
            id = "net.find-and-talk",
            title = "Find what's on my Wi-Fi and talk to it",
            description = "Finds the live devices on your Wi-Fi, checks the one you pick, and talks to it right away — scan, ports, and send in one step.",
            area = OperationArea.NETWORK,
            prerequisites = listOf("On a Wi-Fi network"),
            preparation = listOf(
                PreparationStep("Checking no network test is running") { context ->
                    val net = context.net
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Network tools aren't available right now."
                        )
                    if (net.isBusy || net.isScanningNetwork) {
                        PreparationOutcome.Failed(
                            "Another network test is still running — wait for it to finish, then try again."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                net.scanMyNetwork()
                val scanned = waitUntil(90000) { !net.isScanningNetwork }
                if (!scanned) {
                    return@Operation OpResult(
                        false,
                        "Still looking after 90 seconds — the network may be very slow."
                    )
                }
                val hosts = net.liveHosts
                if (hosts.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "No devices answered on this network — some devices ignore this kind of check even when they are on."
                    )
                }
                // One host choice, then the rest runs by itself.
                val picked = context.pendingChoice
                val host = if (picked != null && picked in hosts) picked else hosts.first()
                context.pendingChoice = null
                val logBefore = net.log.size
                net.scanPortsForHost(host)
                waitForNetIdle(net, 30000)
                val open = net.openPortsByHost[host] ?: emptyList()
                if (open.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "Checked $host — nothing answered on the common connection points, so there's nothing to talk to here."
                    )
                }
                val web = open.firstOrNull { it == 80 || it == 443 || it == 8080 }
                if (web != null) {
                    val scheme = if (web == 443) "https" else "http"
                    net.httpGet("$scheme://$host:$web")
                    waitForNetIdle(net, 30000)
                    val answer = netLogSince(net, logBefore)
                        .lastOrNull { "Answer:" in it }?.substringAfter("Answer:")?.trim()
                    OpResult(
                        true,
                        "Talked to $host — it serves a page on port $web" +
                            (answer?.let { " (answered: $it)" } ?: "") +
                            ". Open ports: ${open.joinToString(", ")}.",
                        "net-web:$host"
                    )
                } else {
                    val port = open.first()
                    net.tcpTest(host, port, "HELLO")
                    waitForNetIdle(net, 30000)
                    val lines = netLogSince(net, logBefore)
                    val reply = lines.lastOrNull { "Received" in it }
                        ?.substringAfter("Received")?.trim()
                    val sent = lines.any { "Sent" in it }
                    if (!sent) {
                        return@Operation OpResult(
                            false,
                            "Couldn't talk to $host on port $port — ${lines.lastOrNull().orEmpty()}."
                        )
                    }
                    OpResult(
                        true,
                        "Talked to $host on port $port — sent HELLO." +
                            if (reply != null) " It answered: $reply" else " It didn't answer back." +
                            " Open ports: ${open.joinToString(", ")}.",
                        "net-tcp:$host"
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Check one host's ports (B-087). ----
    OperationRegistry.register(
        Operation(
            id = "net.check-host-ports",
            title = "Check one device's open ports",
            description = "Tries the common connection points on one device from your Wi-Fi and lists which ones answer.",
            area = OperationArea.NETWORK,
            preparation = listOf(netHostChoiceStep("Pick a device")),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val host = chosenHost(context)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                net.scanPortsForHost(host)
                waitForNetIdle(net, 30000)
                val open = net.openPortsByHost[host]
                    ?: return@Operation OpResult(
                        false,
                        "The port check didn't finish — try again."
                    )
                OpResult(
                    true,
                    if (open.isEmpty()) "Checked $host — nothing answered on the common connection points."
                    else "Checked $host — open: ${open.joinToString(", ")}.",
                    "net-ports:$host"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- What's talking (B-088). ----
    OperationRegistry.register(
        Operation(
            id = "net.whats-talking",
            title = "What's talking on my network?",
            description = "Listens for 8 seconds for services announcing themselves on your network — printers, web pages, file shares — and lists what shows up.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                PreparationStep("Checking no network test is running") { context ->
                    val net = context.net
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Network tools aren't available right now."
                        )
                    if (net.isBusy || net.isDiscoveringNsd) {
                        PreparationOutcome.Failed(
                            "Already listening, or another network test is running — wait for it to finish."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                net.discoverServices()
                delay(8000)
                net.stopDiscoverServices()
                val services = net.nsdServices
                if (services.isEmpty()) {
                    OpResult(
                        true,
                        "Listened for 8 seconds — nothing announced itself on this network.",
                        "net-nsd-none"
                    )
                } else {
                    OpResult(
                        true,
                        "Found ${services.size} service(s): " +
                            services.joinToString("; ") { "${it.name} at ${it.host}:${it.port} (${it.type})" } + ".",
                        "net-nsd:${services.size}"
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Open an advertised service (B-088). ----
    OperationRegistry.register(
        Operation(
            id = "net.open-advertised-service",
            title = "Open a service that announced itself",
            description = "Picks a service found by What's talking and opens it — web services are read, anything else is talked to right away with HELLO.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                PreparationStep("Pick a service") { context ->
                    val net = context.net
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Network tools aren't available right now."
                        )
                    val services = net.nsdServices
                    if (services.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Nothing announced itself yet — run 'What's talking on my network?' first."
                        )
                    }
                    val choices = services.map { "${it.name} (${it.host}:${it.port})" }
                    val picked = context.pendingChoice
                    if (picked != null && picked in choices) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.NeedsUserChoice(
                            prompt = "Which service?",
                            choices = choices
                        )
                    }
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val picked = context.pendingChoice
                    ?: return@Operation OpResult(false, "Pick a service first.")
                context.pendingChoice = null
                val svc = net.nsdServices.firstOrNull { "${it.name} (${it.host}:${it.port})" == picked }
                    ?: return@Operation OpResult(false, "That service isn't around any more.")
                val logBefore = net.log.size
                if (svc.type.contains("http")) {
                    val scheme = if (svc.type.contains("https")) "https" else "http"
                    net.httpGet("$scheme://${svc.host}:${svc.port}")
                } else {
                    net.prefillTcp(svc.host, svc.port)
                    net.tcpTest(svc.host, svc.port, "HELLO")
                }
                waitForNetIdle(net, 30000)
                val lines = netLogSince(net, logBefore)
                OpResult(
                    true,
                    "${svc.name} at ${svc.host}:${svc.port}: " +
                        lines.joinToString(" ").take(300),
                    "net-svc:${svc.host}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Talk to a device (B-089). ----
    OperationRegistry.register(
        Operation(
            id = "net.talk-to-device",
            title = "Talk to a device on my Wi-Fi",
            description = "Connects to one device on your Wi-Fi, sends a message, and reports any reply.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                stashHostStep("Pick a device"),
                stashPortStep("Pick a connection point"),
                stashMessageStep("Pick a message")
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val (host, port, message) = consumeNetStash()
                if (host == null || port == null || message == null) {
                    return@Operation OpResult(false, "Pick a device, a connection point, and a message first.")
                }
                val logBefore = net.log.size
                net.tcpTest(host, port, message)
                waitForNetIdle(net, 30000)
                val lines = netLogSince(net, logBefore)
                if (!lines.any { "Sent" in it }) {
                    return@Operation OpResult(
                        false,
                        "Couldn't talk to $host on port $port — ${lines.lastOrNull().orEmpty()}."
                    )
                }
                val reply = lines.lastOrNull { "Received" in it }
                    ?.substringAfter("Received")?.trim()
                OpResult(
                    true,
                    "Sent '$message' to $host on port $port." +
                        if (reply != null) " It answered: $reply" else " It didn't answer back.",
                    "net-tcp:$host"
                )
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Send a UDP message (B-090). ----
    OperationRegistry.register(
        Operation(
            id = "net.udp-send",
            title = "Send a one-way message",
            description = "Sends one message that gets no confirmation it arrived — that's normal for this kind of message — then listens briefly for any reply.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                stashHostStep("Pick a device"),
                stashPortStep("Pick a connection point"),
                stashMessageStep("Pick a message")
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val (host, port, message) = consumeNetStash()
                if (host == null || port == null || message == null) {
                    return@Operation OpResult(false, "Pick a device, a connection point, and a message first.")
                }
                val logBefore = net.log.size
                net.udpTest(host, port, message)
                waitForNetIdle(net, 30000)
                val lines = netLogSince(net, logBefore)
                if (!lines.any { "Sent" in it }) {
                    return@Operation OpResult(
                        false,
                        "Couldn't send to $host on port $port — ${lines.lastOrNull().orEmpty()}."
                    )
                }
                val reply = lines.lastOrNull { "Reply from" in it }
                    ?.substringAfter("Reply from")?.trim()
                OpResult(
                    true,
                    "Sent '$message' to $host on port $port. (No confirmation that it arrived — that's normal for this kind of message.)" +
                        if (reply != null) " Reply: $reply" else " No reply within 3 seconds.",
                    "net-udp:$host"
                )
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Fetch a web page (B-091). ----
    OperationRegistry.register(
        Operation(
            id = "net.fetch-page",
            title = "Read a device's web page",
            description = "Reads the web page a device on your Wi-Fi serves and shows the first part of it.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                PreparationStep("Pick a web page") { context ->
                    val net = context.net
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Network tools aren't available right now."
                        )
                    val choices = buildList {
                        net.openPortsByHost.forEach { (host, ports) ->
                            ports.filter { it == 80 || it == 443 || it == 8080 }.forEach { port ->
                                add("${if (port == 443) "https" else "http"}://$host:$port")
                            }
                        }
                        net.nsdServices.filter { it.type.contains("http") }.forEach { svc ->
                            add("${if (svc.type.contains("https")) "https" else "http"}://${svc.host}:${svc.port}")
                        }
                    }.distinct()
                    if (choices.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "No web page found yet — run 'Find what's on my Wi-Fi' and check a device's ports first."
                        )
                    }
                    val picked = context.pendingChoice
                    if (picked != null && picked in choices) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.NeedsUserChoice(
                            prompt = "Which page?",
                            choices = choices
                        )
                    }
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val url = context.pendingChoice
                    ?: return@Operation OpResult(false, "Pick a page first.")
                context.pendingChoice = null
                val logBefore = net.log.size
                net.httpGet(url)
                waitForNetIdle(net, 30000)
                val lines = netLogSince(net, logBefore)
                val answer = lines.lastOrNull { "Answer:" in it }
                if (answer == null) {
                    return@Operation OpResult(false, "The page didn't answer — ${lines.lastOrNull().orEmpty()}.")
                }
                OpResult(
                    true,
                    "Read $url — ${answer.substringAfter("Answer:").trim()} " +
                        (lines.lastOrNull { "Page text" in it }?.take(300).orEmpty()),
                    "net-page:$url"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Ping a device (B-092). ----
    OperationRegistry.register(
        Operation(
            id = "net.ping-host",
            title = "Check if a device answers",
            description = "Pings one device on your Wi-Fi and says how fast it answered — or that it didn't answer, which can mean it ignores this kind of check.",
            area = OperationArea.NETWORK,
            preparation = listOf(netHostChoiceStep("Pick a device")),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val host = chosenHost(context)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val logBefore = net.log.size
                net.ping(host)
                waitForNetIdle(net, 30000)
                val lines = netLogSince(net, logBefore)
                val answered = lines.lastOrNull { "answered in" in it }
                if (answered != null) {
                    OpResult(true, answered.substringAfter("] ").trim(), "net-ping:$host")
                } else {
                    OpResult(
                        false,
                        lines.lastOrNull { "didn't answer" in it }
                            ?.substringAfter("] ")?.trim()
                            ?: "$host didn't answer."
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Ping the address from a photo (B-084 + B-092). ----
    // The prefill is consumed and run in one step, so it can never sit
    // waiting to be lost when the user navigates away.
    OperationRegistry.register(
        Operation(
            id = "net.ping-from-photo",
            title = "Check the address from a photo",
            description = "Pings the address the camera spotted in a photo — the one Visual Intel handed over — and says how fast it answered.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                PreparationStep("Checking for an address from a photo") { context ->
                    val net = context.net
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Network tools aren't available right now."
                        )
                    val host = net.consumePingPrefill()
                    if (host == null) {
                        PreparationOutcome.Failed(
                            "No address spotted in a photo yet — take a photo of the address first."
                        )
                    } else {
                        Area5State.stashedHost = host
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val host = Area5State.stashedHost
                    ?: return@Operation OpResult(false, "No address spotted in a photo yet.")
                Area5State.stashedHost = null
                val logBefore = net.log.size
                net.ping(host)
                waitForNetIdle(net, 30000)
                val lines = netLogSince(net, logBefore)
                val answered = lines.lastOrNull { "answered in" in it }
                if (answered != null) {
                    OpResult(true, answered.substringAfter("] ").trim(), "net-ping:$host")
                } else {
                    OpResult(
                        false,
                        lines.lastOrNull { "didn't answer" in it }
                            ?.substringAfter("] ")?.trim()
                            ?: "$host didn't answer."
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Talk to a tapped open port (B-085 + B-089). ----
    // The prefill is consumed and sent in one step, so it can never
    // sit waiting to be lost.
    OperationRegistry.register(
        Operation(
            id = "net.talk-to-open-port",
            title = "Talk to the open port I tapped",
            description = "Talks right away to the open port tapped on the Network Tools screen — the address and port are already filled in.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                PreparationStep("Checking for a tapped open port") { context ->
                    val net = context.net
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Network tools aren't available right now."
                        )
                    val pre = net.consumeTcpPrefill()
                    if (pre == null) {
                        PreparationOutcome.Failed(
                            "No open port tapped yet — find devices on your Wi-Fi, tap one, then tap an open port."
                        )
                    } else {
                        Area5State.stashedHost = pre.first
                        Area5State.stashedPort = pre.second
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val host = Area5State.stashedHost
                val port = Area5State.stashedPort
                Area5State.clearNetStash()
                if (host == null || port == null) {
                    return@Operation OpResult(false, "No open port tapped yet.")
                }
                val logBefore = net.log.size
                net.tcpTest(host, port, "HELLO")
                waitForNetIdle(net, 30000)
                val lines = netLogSince(net, logBefore)
                if (!lines.any { "Sent" in it }) {
                    return@Operation OpResult(
                        false,
                        "Couldn't talk to $host on port $port — ${lines.lastOrNull().orEmpty()}."
                    )
                }
                val reply = lines.lastOrNull { "Received" in it }
                    ?.substringAfter("Received")?.trim()
                OpResult(
                    true,
                    "Sent HELLO to $host on port $port." +
                        if (reply != null) " It answered: $reply" else " It didn't answer back.",
                    "net-tcp:$host"
                )
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Port scan with a chosen set (B-093). ----
    OperationRegistry.register(
        Operation(
            id = "net.port-scan",
            title = "Check a device's ports, my choice of set",
            description = "Checks a chosen set of connection points on one device — the usual set, the web set, or mail and web — and lists which are open and which are closed.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                netHostChoiceStep("Pick a device"),
                PreparationStep("Which set of ports?") { context ->
                    val sets = listOf(
                        "Usual ports (21, 22, 23, 80, 443, 8080, 1883, 9000)",
                        "Web ports (80, 443, 8080)",
                        "Mail and web (25, 80, 110, 143, 443)"
                    )
                    val picked = context.pendingChoice
                    if (picked == null) {
                        Area5State.stashedMessage = null
                        return@PreparationStep PreparationOutcome.NeedsUserChoice(
                            prompt = "Which set of ports?",
                            choices = sets
                        )
                    }
                    if (picked in sets) {
                        Area5State.stashedMessage = picked
                        context.pendingChoice = null
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (Area5State.stashedMessage != null &&
                        Area5State.stashedMessage in sets
                    ) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which set of ports?",
                        choices = sets
                    )
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                val host = chosenHost(context)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val setLabel = Area5State.stashedMessage
                Area5State.stashedMessage = null
                val ports = when (setLabel) {
                    "Web ports (80, 443, 8080)" -> listOf(80, 443, 8080)
                    "Mail and web (25, 80, 110, 143, 443)" -> listOf(25, 80, 110, 143, 443)
                    else -> listOf(21, 22, 23, 80, 443, 8080, 1883, 9000)
                }
                val logBefore = net.log.size
                net.portScan(host, ports)
                waitForNetIdle(net, 90000)
                val lines = netLogSince(net, logBefore)
                val done = lines.lastOrNull { "Port check — done" in it }
                if (done == null) {
                    return@Operation OpResult(false, "The port check didn't finish — try again.")
                }
                OpResult(true, "$host — ${done.substringAfter("Port check — done.").trim()}", "net-scan:$host")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Start the Ghost hotspot (B-094). ----
    OperationRegistry.register(
        Operation(
            id = "net.start-hotspot",
            title = "Start my own hotspot",
            description = "Starts Ghost Tap's own Wi-Fi hotspot — the phone is the network — and checks who joined it.",
            area = OperationArea.NETWORK,
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                if (net.ghostApLive) {
                    return@Operation OpResult(
                        true,
                        "Hotspot already on — Wi-Fi name: ${net.ghostApSsid} / Password: ${net.ghostApPassword}.",
                        "net-ap-on"
                    )
                }
                net.startGhostAp()
                val live = waitUntil(15000) { net.ghostApLive }
                if (!live) {
                    return@Operation OpResult(
                        false,
                        "The hotspot didn't start — the phone may have blocked it."
                    )
                }
                net.refreshGhostApClients()
                delay(2000)
                val clients = net.ghostApClients
                OpResult(
                    true,
                    "Hotspot on — Wi-Fi name: ${net.ghostApSsid} / Password: ${net.ghostApPassword}. " +
                        if (clients.isEmpty()) "No device has joined yet."
                        else "Joined so far: ${clients.joinToString("; ")}.",
                    "net-ap-on"
                )
            },
            verify = { context, _ ->
                val net = context.net
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Network tools aren't available right now."
                    )
                if (net.ghostApLive) {
                    VerificationOutcome.Verified("The hotspot is on: ${net.ghostApSsid}.")
                } else {
                    VerificationOutcome.NotVerified("The hotspot isn't on.")
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Who joined the hotspot (B-094). ----
    OperationRegistry.register(
        Operation(
            id = "net.check-hotspot-joined",
            title = "Who joined my hotspot?",
            description = "Checks which devices joined Ghost Tap's hotspot, by address as observed — no names are guessed.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                PreparationStep("Checking the hotspot is on") { context ->
                    val net = context.net
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Network tools aren't available right now."
                        )
                    if (net.ghostApLive) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "The hotspot isn't on — start it first."
                    )
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                net.refreshGhostApClients()
                delay(2000)
                val clients = net.ghostApClients
                OpResult(
                    true,
                    if (clients.isEmpty()) "No device has joined the hotspot yet — connect a device to it, then check again."
                    else "Joined the hotspot: ${clients.joinToString("; ")}.",
                    "net-ap-clients:${clients.size}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Stop the hotspot (B-095). ----
    OperationRegistry.register(
        Operation(
            id = "net.stop-hotspot",
            title = "Stop my hotspot",
            description = "Stops Ghost Tap's own Wi-Fi hotspot.",
            area = OperationArea.NETWORK,
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                net.stopGhostAp()
                OpResult(true, "Hotspot off.", "net-ap-off")
            },
            verify = { context, _ ->
                val net = context.net
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Network tools aren't available right now."
                    )
                if (!net.ghostApLive) VerificationOutcome.Verified("The hotspot is off.")
                else VerificationOutcome.NotVerified("The hotspot is still on.")
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Clear the results (B-096). ----
    OperationRegistry.register(
        Operation(
            id = "net.clear-results",
            title = "Clear the network results",
            description = "Clears the network results log. This can't be undone — the log lives only in memory.",
            area = OperationArea.NETWORK,
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                net.clearLog()
                OpResult(true, "Network results cleared.", "net-cleared")
            },
            verify = { context, _ ->
                val net = context.net
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Network tools aren't available right now."
                    )
                if (net.log.isEmpty()) VerificationOutcome.Verified("The results log is empty.")
                else VerificationOutcome.NotVerified("The results didn't clear.")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Open the camera (B-115). ----
    OperationRegistry.register(
        Operation(
            id = "visual.open-camera",
            title = "Open the camera",
            description = "Opens the Visual Intel camera so you can point it at a board, chip, label, or device.",
            area = OperationArea.VISUAL,
            preparation = listOf(
                PreparationStep("Checking the camera is allowed") { context ->
                    ensureCameraPermission(context)
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                context.navigate("visual_intel")
                delay(2500)
                if (visual.cameraError != null) {
                    OpResult(false, visual.cameraError!!)
                } else {
                    OpResult(true, "Camera is on — point it at the thing and take a photo.", "visual-camera")
                }
            },
            verify = { context, _ ->
                val visual = context.visual
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Visual Intel isn't available right now."
                    )
                if (visual.cameraError == null) {
                    VerificationOutcome.Verified("The camera started with no error.")
                } else {
                    VerificationOutcome.NotVerified(visual.cameraError!!)
                }
            },
            riskLevel = "READ",
            verified = false,
            route = "visual_intel"
        )
    )

    // ---- Stop the camera (B-116). ----
    OperationRegistry.register(
        Operation(
            id = "visual.stop-camera",
            title = "Turn the camera off",
            description = "Closes the live camera view.",
            area = OperationArea.VISUAL,
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                visual.unbindCamera()
                OpResult(true, "Camera off — the live view is closed.", "visual-off")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Take a photo and act on it (B-117, B-119 + D-30 + A-28). ----
    // Capture → finding → suggested action executed END-TO-END as one
    // operation, with the OCR check in front of the action.
    OperationRegistry.register(
        Operation(
            id = "visual.capture-and-act",
            title = "Take a photo and do what it suggests",
            description = "Takes a photo, reads it, and runs the first thing it suggests — joining the Wi-Fi, fetching the link, or connecting to the address or device — after checking the camera's own text first.",
            area = OperationArea.VISUAL,
            prerequisites = listOf("Camera allowed", "The thing in frame, steady, in good light"),
            preparation = listOf(
                PreparationStep("Checking the camera is allowed") { context ->
                    ensureCameraPermission(context)
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                context.navigate("visual_intel")
                delay(2000)
                val beforeId = visual.lastFinding?.id
                visual.captureAndAnalyze()
                val done = waitUntil(30000) { !visual.isAnalyzing }
                if (!done) {
                    return@Operation OpResult(
                        false,
                        "Still reading the photo after 30 seconds — try again."
                    )
                }
                var finding = visual.lastFinding
                if (finding == null || finding.id == beforeId) {
                    // The camera may not have been ready on the first try — once more.
                    if (visual.statusLine.contains("not ready yet")) {
                        delay(2000)
                        visual.captureAndAnalyze()
                        waitUntil(30000) { !visual.isAnalyzing }
                        finding = visual.lastFinding
                    }
                }
                if (finding == null || finding.id == beforeId) {
                    return@Operation OpResult(
                        false,
                        visual.statusLine.ifBlank {
                            "Nothing readable in the photo — try closer, steadier, with more light."
                        }
                    )
                }
                if (visual.statusLine.startsWith("Couldn't")) {
                    return@Operation OpResult(false, visual.statusLine)
                }
                val actions = suggestActions(finding)
                if (actions.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "Nothing in this picture matched a tool — no link, address, or device clue. " +
                            "Try the Bluetooth search from the Visual Intel screen instead."
                    )
                }
                executeVisualAction(context, actions.first())
            },
            verify = { context, result -> verifyVisualAction(context, result) },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Act on the last photo (B-117/B-118/B-119 + D-30 + A-28). ----
    OperationRegistry.register(
        Operation(
            id = "visual.act-on-last-photo",
            title = "Do what the last photo suggests",
            description = "Runs the first thing the last photo suggests — after checking the camera's own text actually contains what it needs.",
            area = OperationArea.VISUAL,
            preparation = listOf(
                PreparationStep("Checking for a photo") { context ->
                    if (context.visual?.lastFinding == null) {
                        PreparationOutcome.Failed(
                            "Take a photo first — nothing to act on yet."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                val finding = visual.lastFinding
                    ?: return@Operation OpResult(false, "Take a photo first — nothing to act on yet.")
                val actions = suggestActions(finding)
                if (actions.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "Nothing in this picture matched a tool — no link, address, or device clue. " +
                            "Try the Bluetooth search from the Visual Intel screen instead."
                    )
                }
                executeVisualAction(context, actions.first())
            },
            verify = { context, result -> verifyVisualAction(context, result) },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Read a photo from the gallery (B-118). ----
    OperationRegistry.register(
        Operation(
            id = "visual.read-gallery-photo",
            title = "Read a photo from the gallery",
            description = "Opens the gallery to pick a photo. Once picked, it is read automatically — then run 'Do what the last photo suggests'.",
            area = OperationArea.VISUAL,
            execute = { context ->
                context.pickImage()
                OpResult(
                    true,
                    "Gallery open — pick a photo. Once picked, it is read automatically.",
                    "visual-gallery"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Save the last photo (B-121). ----
    OperationRegistry.register(
        Operation(
            id = "visual.save-last-photo",
            title = "Save what the last photo found",
            description = "Saves what the camera found as a target profile, so you can come back to it later.",
            area = OperationArea.VISUAL,
            preparation = listOf(
                PreparationStep("Checking for a photo") { context ->
                    if (context.visual?.lastFinding == null) {
                        PreparationOutcome.Failed(
                            "Take a photo first — nothing to save yet."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                },
                PreparationStep("What should it be called?") { context ->
                    val visual = context.visual
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Visual Intel isn't available right now."
                        )
                    val finding = visual.lastFinding
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Take a photo first — nothing to save yet."
                        )
                    val suggestions = (finding.markings.take(3) +
                        listOf("Device ${visual.profiles.size + 1}"))
                        .distinct().filter { it.isNotBlank() }
                    val picked = context.pendingChoice
                    if (picked != null && picked in suggestions) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "What should it be called?",
                        choices = suggestions
                    )
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                val finding = visual.lastFinding
                    ?: return@Operation OpResult(false, "Take a photo first — nothing to save yet.")
                val name = context.pendingChoice ?: "Device ${visual.profiles.size + 1}"
                context.pendingChoice = null
                visual.saveCurrentAsProfile(name)
                OpResult(
                    true,
                    "Saved as \"$name\".",
                    "visual-profile:$name"
                )
            },
            verify = { context, result ->
                val visual = context.visual
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Visual Intel isn't available right now."
                    )
                val name = result.data.substringAfter("visual-profile:", "")
                if (visual.profiles.any { it.name == name }) {
                    VerificationOutcome.Verified("Profile \"$name\" is saved.")
                } else {
                    VerificationOutcome.NotVerified("The profile didn't save.")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Rename a saved profile (B-122, suggested names). ----
    OperationRegistry.register(
        Operation(
            id = "visual.rename-profile",
            title = "Rename a saved profile",
            description = "Renames one of the saved target profiles — pick the profile, then pick a name from what the camera found on it.",
            area = OperationArea.VISUAL,
            preparation = listOf(
                PreparationStep("Pick a saved profile") { context ->
                    val visual = context.visual
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Visual Intel isn't available right now."
                        )
                    val profiles = visual.profiles
                    if (profiles.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Nothing saved yet — take a photo and save what you find first."
                        )
                    }
                    val picked = context.pendingChoice
                    if (picked == null) {
                        Area5State.stashedProfileId = null
                        return@PreparationStep PreparationOutcome.NeedsUserChoice(
                            prompt = "Which saved profile?",
                            choices = profiles.map { it.name }
                        )
                    }
                    val match = profiles.firstOrNull { it.name == picked }
                    if (match != null) {
                        Area5State.stashedProfileId = match.id
                        context.pendingChoice = null
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (Area5State.stashedProfileId != null &&
                        profiles.any { it.id == Area5State.stashedProfileId }
                    ) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which saved profile?",
                        choices = profiles.map { it.name }
                    )
                },
                PreparationStep("Pick a new name") { context ->
                    val visual = context.visual
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Visual Intel isn't available right now."
                        )
                    val id = Area5State.stashedProfileId
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Pick a saved profile first."
                        )
                    val profile = visual.profiles.firstOrNull { it.id == id }
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "That profile isn't around any more."
                        )
                    val suggestions = (
                        profile.findings.flatMap { it.markings }.take(3) +
                            listOf(profile.name)
                        ).distinct().filter { it.isNotBlank() }
                    val picked = context.pendingChoice
                    if (picked != null && picked in suggestions) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "What should it be called?",
                        choices = suggestions
                    )
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                val id = Area5State.stashedProfileId
                    ?: return@Operation OpResult(false, "Pick a saved profile first.")
                Area5State.stashedProfileId = null
                val name = context.pendingChoice
                    ?: return@Operation OpResult(false, "Pick a new name first.")
                context.pendingChoice = null
                visual.renameProfile(id, name)
                OpResult(true, "Renamed to \"$name\".", "visual-renamed:$name")
            },
            verify = { context, result ->
                val visual = context.visual
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Visual Intel isn't available right now."
                    )
                val name = result.data.substringAfter("visual-renamed:", "")
                if (visual.profiles.any { it.name == name }) {
                    VerificationOutcome.Verified("Profile \"$name\" exists.")
                } else {
                    VerificationOutcome.NotVerified("The rename didn't stick.")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Delete a saved profile (B-122). ----
    OperationRegistry.register(
        Operation(
            id = "visual.delete-profile",
            title = "Delete a saved profile",
            description = "Deletes one of the saved target profiles. This can't be undone.",
            area = OperationArea.VISUAL,
            preparation = listOf(
                PreparationStep("Pick a saved profile") { context ->
                    val visual = context.visual
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Visual Intel isn't available right now."
                        )
                    val profiles = visual.profiles
                    if (profiles.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Nothing saved yet — nothing to delete."
                        )
                    }
                    val picked = context.pendingChoice
                    if (picked == null) {
                        Area5State.stashedProfileId = null
                        return@PreparationStep PreparationOutcome.NeedsUserChoice(
                            prompt = "Which saved profile?",
                            choices = profiles.map { it.name }
                        )
                    }
                    val match = profiles.firstOrNull { it.name == picked }
                    if (match != null) {
                        Area5State.stashedProfileId = match.id
                        context.pendingChoice = null
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (Area5State.stashedProfileId != null &&
                        profiles.any { it.id == Area5State.stashedProfileId }
                    ) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which saved profile?",
                        choices = profiles.map { it.name }
                    )
                },
                PreparationStep("Are you sure?") { context ->
                    val picked = context.pendingChoice
                    if (picked == "Delete it") {
                        context.pendingChoice = null
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    if (picked == "Keep it") {
                        context.pendingChoice = null
                        return@PreparationStep PreparationOutcome.Failed(
                            "Kept — nothing was deleted."
                        )
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Delete this profile? This can't be undone.",
                        choices = listOf("Delete it", "Keep it")
                    )
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                val id = Area5State.stashedProfileId
                    ?: return@Operation OpResult(false, "Pick a saved profile first.")
                Area5State.stashedProfileId = null
                visual.deleteProfile(id)
                OpResult(true, "Profile deleted.", "visual-deleted:$id")
            },
            verify = { context, result ->
                val visual = context.visual
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Visual Intel isn't available right now."
                    )
                val id = result.data.substringAfter("visual-deleted:", "")
                if (visual.profiles.none { it.id == id }) {
                    VerificationOutcome.Verified("The profile is gone.")
                } else {
                    VerificationOutcome.NotVerified("The profile is still there.")
                }
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Reload the saved profiles (B-120). ----
    OperationRegistry.register(
        Operation(
            id = "visual.reload-profiles",
            title = "Reload the saved profiles",
            description = "Re-reads the saved target profiles from the phone.",
            area = OperationArea.VISUAL,
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                visual.loadProfiles()
                OpResult(
                    true,
                    if (visual.profiles.isEmpty()) "No saved profiles."
                    else "${visual.profiles.size} saved profile(s) loaded.",
                    "visual-profiles:${visual.profiles.size}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Fetch what a barcode link points to (B-123). ----
    OperationRegistry.register(
        Operation(
            id = "visual.fetch-link",
            title = "Fetch what the barcode points to",
            description = "Downloads what the link in the last photo points to and shows the text — no browser needed.",
            area = OperationArea.VISUAL,
            preparation = listOf(
                PreparationStep("Checking for a link in the photo") { context ->
                    val finding = context.visual?.lastFinding
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Take a photo first — no link spotted yet."
                        )
                    val url = finding.barcodes.firstOrNull {
                        it.rawValue.startsWith("http://", ignoreCase = true) ||
                            it.rawValue.startsWith("https://", ignoreCase = true)
                    }?.rawValue
                    if (url == null) {
                        PreparationOutcome.Failed(
                            "No link in the last photo — point the camera at a barcode or QR code that points to a web page."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                val finding = visual.lastFinding
                    ?: return@Operation OpResult(false, "Take a photo first — no link spotted yet.")
                val url = finding.barcodes.firstOrNull {
                    it.rawValue.startsWith("http://", ignoreCase = true) ||
                        it.rawValue.startsWith("https://", ignoreCase = true)
                }?.rawValue?.trim()
                    ?: return@Operation OpResult(false, "No link in the last photo.")
                // The OCR check: the link must be in what the camera read.
                if (url !in finding.fullText && finding.barcodes.none { it.rawValue == url }) {
                    return@Operation OpResult(
                        false,
                        "The link isn't in what the camera read — it may be a misread."
                    )
                }
                visual.fetchLinkPreview(url)
                val ready = waitUntil(15000) { visual.linkPreview != null }
                if (!ready) {
                    return@Operation OpResult(false, "The link didn't answer in time.")
                }
                val preview = visual.linkPreview ?: ""
                if (preview.startsWith("Couldn't open") || preview.startsWith("The link answered")) {
                    OpResult(false, preview)
                } else {
                    OpResult(true, preview.take(300), "visual-link:$url")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Join the Wi-Fi from a photo (B-125). ----
    OperationRegistry.register(
        Operation(
            id = "visual.join-wifi-from-photo",
            title = "Join the Wi-Fi from a photo",
            description = "Joins the Wi-Fi network from the QR code in the last photo — no typing the password.",
            area = OperationArea.VISUAL,
            preparation = listOf(
                PreparationStep("Checking for a Wi-Fi code in the photo") { context ->
                    val finding = context.visual?.lastFinding
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Take a photo first — no Wi-Fi code spotted yet."
                        )
                    val creds = finding.barcodes
                        .firstOrNull { it.rawValue.startsWith("WIFI:") }
                        ?.let { parseWifiCredentials(it.rawValue) }
                    when {
                        creds == null -> PreparationOutcome.Failed(
                            "No Wi-Fi code in the last photo — point the camera at the network's QR code."
                        )
                        creds.password.isEmpty() -> PreparationOutcome.NeedsUserAction(
                            prompt = "This network needs a password the picture didn't show — type it once on the Visual Intel screen.",
                            actionLabel = "Open Visual Intel",
                            action = { context.navigate("visual_intel") }
                        )
                        else -> PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                val finding = visual.lastFinding
                    ?: return@Operation OpResult(false, "Take a photo first — no Wi-Fi code spotted yet.")
                val creds = finding.barcodes
                    .firstOrNull { it.rawValue.startsWith("WIFI:") }
                    ?.let { parseWifiCredentials(it.rawValue) }
                    ?: return@Operation OpResult(false, "No Wi-Fi code in the last photo.")
                visual.joinWifi(creds.ssid, creds.password.ifEmpty { null }, creds.security)
                val joined = currentWifiSsid(context) == creds.ssid
                if (visual.statusLine.contains("Asked the phone to join")) {
                    OpResult(
                        true,
                        if (joined) "Joined '${creds.ssid}' — the phone is on it now."
                        else visual.statusLine,
                        "wifi:${creds.ssid}"
                    )
                } else {
                    OpResult(false, visual.statusLine)
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Open Wi-Fi settings (B-124). ----
    OperationRegistry.register(
        Operation(
            id = "visual.open-wifi-settings",
            title = "Open Wi-Fi settings",
            description = "Opens the phone's Wi-Fi settings so you can join a network by hand.",
            area = OperationArea.VISUAL,
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                visual.openWifiSettings()
                OpResult(
                    true,
                    visual.statusLine,
                    "visual-wifi-settings"
                )
            },
            verify = { context, _ ->
                val visual = context.visual
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Visual Intel isn't available right now."
                    )
                if (visual.statusLine.contains("Wi-Fi settings opened")) {
                    VerificationOutcome.Verified("Wi-Fi settings are open.")
                } else {
                    VerificationOutcome.NotVerified(visual.statusLine)
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Copy something from the photo (B-126). ----
    OperationRegistry.register(
        Operation(
            id = "visual.copy-from-photo",
            title = "Copy something from the photo",
            description = "Copies one thing the camera found — the Wi-Fi password, the full text, a barcode, or a marking — to the clipboard.",
            area = OperationArea.VISUAL,
            preparation = listOf(
                PreparationStep("What should be copied?") { context ->
                    val visual = context.visual
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Visual Intel isn't available right now."
                        )
                    val finding = visual.lastFinding
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Take a photo first — nothing to copy yet."
                        )
                    val choices = buildList {
                        val pass = finding.wifiSummary?.lines()
                            ?.firstOrNull { it.startsWith("Password:") }
                            ?.removePrefix("Password:")?.trim()
                        if (!pass.isNullOrBlank() && !pass.startsWith("(")) {
                            add("Wi-Fi password")
                        }
                        if (finding.fullText.isNotBlank()) add("Full text")
                        finding.barcodes.firstOrNull { it.rawValue.isNotBlank() }?.let {
                            add("Barcode: ${it.rawValue.take(40)}")
                        }
                        finding.markings.firstOrNull()?.let { add("Marking: $it") }
                    }
                    if (choices.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Nothing readable in the last photo — nothing to copy."
                        )
                    }
                    val picked = context.pendingChoice
                    if (picked != null && picked in choices) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.NeedsUserChoice(
                            prompt = "What should be copied?",
                            choices = choices
                        )
                    }
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Visual Intel isn't available right now.")
                val finding = visual.lastFinding
                    ?: return@Operation OpResult(false, "Take a photo first — nothing to copy yet.")
                val picked = context.pendingChoice
                    ?: return@Operation OpResult(false, "Pick something to copy first.")
                context.pendingChoice = null
                val (label, text) = when {
                    picked == "Wi-Fi password" -> {
                        val pass = finding.wifiSummary?.lines()
                            ?.firstOrNull { it.startsWith("Password:") }
                            ?.removePrefix("Password:")?.trim().orEmpty()
                        "Wi-Fi password" to pass
                    }
                    picked == "Full text" -> "full text" to finding.fullText
                    picked.startsWith("Barcode: ") ->
                        "barcode" to (finding.barcodes.firstOrNull { it.rawValue.isNotBlank() }?.rawValue.orEmpty())
                    picked.startsWith("Marking: ") ->
                        "marking" to (finding.markings.firstOrNull().orEmpty())
                    else -> "" to ""
                }
                if (text.isBlank()) {
                    return@Operation OpResult(false, "Nothing to copy there.")
                }
                visual.copyToClipboard(label, text)
                OpResult(true, visual.statusLine, "visual-copied")
            },
            verify = { context, _ ->
                val visual = context.visual
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Visual Intel isn't available right now."
                    )
                if (visual.statusLine.startsWith("Copied")) {
                    VerificationOutcome.Verified(visual.statusLine)
                } else {
                    VerificationOutcome.NotVerified(visual.statusLine)
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )
}
