package com.ghosttap.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.core.esp32.FirmwareValidator
import com.ghosttap.core.esp32.VirtualEsp32Client
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigatedControl
import com.ghosttap.core.investigate.InvestigationInput
import com.ghosttap.core.investigate.InvestigationLedger
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.LiveTargetStore
import com.ghosttap.core.investigate.Investigator
import java.io.File

/** ZERO-CONFIG FIX (2026-11-06): stable id for the practice board in
 * the thinking engine's manual-disconnect memory — one board, so one
 * key, shared by the auto-connect guard and the DISCONNECT button. */
private const val VIRTUAL_ESP32_ID = "virtual-esp32"

/** GHOST TAP control surface for the Virtual ESP32 QEMU target.
 *
 * All operations go through the AIDL IPC bridge to the real executing
 * Xtensa firmware. UART and GPIO are not simulated here. */
@Composable
fun VirtualEsp32Screen(
    client: VirtualEsp32Client,
    onBack: () -> Unit,
    onOpenWhatToDo: (() -> Unit)? = null,
    // MASTER PROMPT: live-target card wiring (thinking engine,
    // command runner, open-page). Null-safe: the card renders with
    // the app's own rules when the engine is not wired.
    intelVm: com.ghosttap.IntelViewModel? = null,
    onRunLiveCommand: ((String, String?) -> Unit)? = null,
    onOpenRoute: ((String) -> Unit)? = null,
    // ZERO-CONFIG — the intent box ("what I'm tryna do"). Null = no intent box.
    onIntent: ((String) -> Unit)? = null,
    intentAnswer: String = ""
) {
    val connState by client.connectionState.collectAsState()
    val targetInfo by client.targetInfo.collectAsState()
    val uartLog by client.uartLog.collectAsState()
    val gpio2 by client.gpio2State.collectAsState()
    val gpio4 by client.gpio4State.collectAsState()
    val lastError by client.lastError.collectAsState()
    val firmwareInfo by client.firmwareInfo.collectAsState()
    val answeredCommand by client.answeredCommand.collectAsState()
    var uartInput by remember { mutableStateOf("") }
    // Autonomous investigation: the latest virtual-device report for this
    // screen, plus the state signatures already investigated this session.
    // Nothing here acts on its own — the report only offers buttons.
    var latestVirtualReport by remember { mutableStateOf<FindingsReport?>(null) }
    val investigatedVirtualStates = remember { mutableSetOf<String>() }
    var validationText by remember { mutableStateOf<String?>(null) }
    // DISCOVER (Virtual ESP32): plain-words note from the last "Check
    // the practice board" tap — e.g. no board info yet. Null = nothing
    // to say. Shown under the DISCOVER button; never a faked board.
    var virtualDiscoverNote by remember { mutableStateOf<String?>(null) }

    val installed = remember { client.isInstalled() }
    val context = LocalContext.current

    // ZERO-CONFIG FIX (2026-11-06): the intent answer line is shared
    // across screens — clear it when this screen opens so a stale
    // outcome from another screen never shows here.
    LaunchedEffect(Unit) { intelVm?.clearCommandAnswer() }

    // ZERO-CONFIG — SELECT → AUTO-CONNECT (2026-11-06): opening the
    // practice-board screen connects to the board's bridge on its own
    // (listens for its messages, asks its state) — no CONNECT tap.
    // Once per entry, unless the user deliberately disconnected: that
    // choice lives in the thinking engine's ViewModel
    // (IntelViewModel.manuallyDisconnectedIds), so it survives leaving
    // and re-entering — disconnecting is the user's call and is never
    // undone by re-entering, until the user taps CONNECT himself.
    // Starting the guest stays one tap: which software runs (a loaded
    // file vs the built-in test software) is a real choice, not a
    // connection detail.
    var vespAutoConnected by remember { mutableStateOf(false) }
    LaunchedEffect(installed, connState) {
        // Connected again (by any path) clears a stale manual-
        // disconnect entry, so a later drop that was NOT the user's
        // choice can still auto-reconnect.
        if (connState !is VirtualEsp32Client.ConnectionState.Disconnected) {
            intelVm?.clearManualDisconnect(VIRTUAL_ESP32_ID)
        }
        if (installed && connState is VirtualEsp32Client.ConnectionState.Disconnected &&
            !vespAutoConnected &&
            intelVm?.wasManuallyDisconnected(VIRTUAL_ESP32_ID) != true
        ) {
            vespAutoConnected = true
            client.connect()
        }
    }

    // Autonomous-investigation trigger (additive): a signature of the
    // virtual device's reported state. When it changes to a state not yet
    // investigated this session — a new target, new firmware, new pin
    // readings, a first real reply, or new messages from the board — the
    // virtual device is investigated once with real evidence only. The
    // report is filed in the ledger and shown below the device output.
    // Re-investigation happens only when the reported state changes.
    val virtualStateKey = buildString {
        val info = targetInfo
        if (info != null) {
            append("status="); append(info.status); append("|")
            append("cpu="); append(info.cpu); append("|")
            append("backend="); append(info.backend); append("|")
            append("firmware="); append(info.firmware); append("|")
        }
        val fw = firmwareInfo
        if (fw != null) {
            append("file="); append(fw.fileName); append("|")
            append("type="); append(fw.imageType); append("|")
        }
        append("pin2="); append(gpio2?.toString() ?: "?"); append("|")
        append("pin4="); append(gpio4?.toString() ?: "?"); append("|")
        append("answered="); append(answeredCommand); append("|")
        append("recent=")
        append(uartLog.filter { it.startsWith("RX:") }.takeLast(5).joinToString(";"))
    }
    // DISCOVER (Virtual ESP32): the same check the automatic trigger
    // below runs, on demand. [force] lifts the already-checked gate so
    // a tap re-checks the board's current state; the report carries the
    // board's stable id, so a re-check refreshes its report in place
    // instead of duplicating it. When there is no board info yet, it
    // says so plainly and files nothing — never a faked board.
    fun investigateVirtualNow(force: Boolean) {
        val info = targetInfo
        if (info == null) {
            virtualDiscoverNote = if (!installed) {
                "The practice-board app is not installed — install it first."
            } else {
                "No practice board info yet — tap CONNECT, wait a moment, then tap this again."
            }
            return
        }
        if (!force && virtualStateKey in investigatedVirtualStates) return
        investigatedVirtualStates += virtualStateKey
        val fw = firmwareInfo
        val details = mutableMapOf<String, String>()
        details["status"] = info.status
        details["processor"] = info.cpu
        details["running on"] = info.backend
        details["software"] = info.firmware
        if (fw != null) {
            details["software file"] = fw.fileName
            details["software kind"] = fw.imageType
        }
        gpio2?.let { details["pin 2"] = if (it) "ON" else "OFF" }
        gpio4?.let { details["pin 4"] = if (it) "ON" else "OFF" }
        val recentReadings = uartLog.filter { it.startsWith("RX:") }.takeLast(8)
        val report = Investigator.investigate(
            InvestigationInput(
                source = InvestigationSource.VIRTUAL_ESP32,
                rawName = listOf(info.firmware, fw?.fileName)
                    .firstOrNull { !it.isNullOrBlank() && it != "?" }
                    ?: "Virtual ESP32 practice board",
                rawDetails = details,
                readableValues = listOf(
                    "Practice board only — this device is simulated on this phone, not real hardware."
                ) + recentReadings,
                // Honest handshake proof only: true when the practice-board
                // app actually answered a command over UART this session.
                answeredHandshake = answeredCommand,
                signalText = null,
                // Stable board id, so a re-check refreshes this board's
                // report in place instead of duplicating it.
                targetId = "virtual:practice-board"
            )
        )
        InvestigationLedger.upsert(report)
        // MASTER PROMPT: the same report is also a LIVE TARGET — what
        // the user sees and connects to. The ledger stays as internal
        // storage/dedup only.
        LiveTargetStore.publish(report, InvestigationSource.VIRTUAL_ESP32)
        latestVirtualReport = report
        virtualDiscoverNote = null
    }
    LaunchedEffect(virtualStateKey) {
        investigateVirtualNow(force = false)
    }

    // File picker for firmware images
    val firmwarePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val result = copyFirmwareToSharedStorage(context, uri)
            validationText = result.validationSummary
            if (result.success && result.cachedFile != null) {
                // Share via FileProvider (our own file, grant is reliable)
                val shareUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    result.cachedFile
                )
                client.loadFirmware(shareUri)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .background(Bg),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("VIRTUAL ESP32", color = Accent, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            Button(onClick = onBack) { Text("Back") }
        }

        onOpenWhatToDo?.let { TerminalButton("What do you want to do?", it) }

        // DISCOVER (Virtual ESP32): one tap checks the practice board's
        // reported state right now — no typing, no setup. It asks the
        // board for its current state first, then runs the same check
        // the automatic trigger runs. Findings list below; nothing here
        // runs on its own beyond the check.
        TerminalButton("Investigate the unfamiliar") {
            client.queryStatus()
            investigateVirtualNow(force = true)
        }
        virtualDiscoverNote?.let { note ->
            Text(
                note,
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ZERO-CONFIG — the intent box: type what you're trying to do
        // and Ghost Tap does it.
        IntentBox(onIntent = onIntent, answer = intentAnswer)

        if (!installed) {
            Text(
                "The practice-board app is not installed. Install it to use this practice board.",
                color = Color.Red, fontFamily = FontFamily.Monospace
            )
            return@Column
        }

        // Firmware manager (top: load before connecting)
        PanelBox(title = "FIRMWARE") {
            val fw = firmwareInfo
            if (fw != null) {
                Text("File: ${fw.fileName}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Kind: ${fw.imageType}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Size: ${fw.fileSize / 1024} KB", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Check: ${fw.validationSummary}", color = Dim, fontFamily = FontFamily.Monospace)
                if (fw.isBuiltin) {
                    Text("(built-in test software)", color = Accent, fontFamily = FontFamily.Monospace)
                }
            } else {
                Text("No software loaded from Ghost Tap yet.", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Use LOAD SOFTWARE or BUILT-IN TEST SOFTWARE.", color = Dim, fontFamily = FontFamily.Monospace)
            }
            validationText?.let {
                Text("Last check: $it", color = Color(0xFFFFC857), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            // Package 5 (D-29) — ONE verdict from the two checks: Ghost
            // Tap's own check (validationText) and the practice board's
            // (firmwareInfo.validationSummary). Disagreement is said
            // plainly; a failed check is never softened.
            if (validationText != null || fw != null) {
                Text(
                    "One verdict: ${reconcileFirmwareChecks(validationText, fw?.validationSummary)}",
                    color = Accent, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("LOAD SOFTWARE", {
                    firmwarePicker.launch(arrayOf("application/octet-stream", "*/*"))
                })
                TerminalButton("REMOVE SOFTWARE", client::ejectFirmware)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("BUILT-IN TEST SOFTWARE", client::useBuiltinFirmware)
            }
            Text(
                "Pick a real board software file (.bin) from your phone. It is checked first, then handed to the practice board to run.",
                color = Dim, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Connection
        PanelBox(title = "CONNECTION") {
            Text(
                "State: ${when (connState) {
                    is VirtualEsp32Client.ConnectionState.Disconnected -> "Not connected"
                    is VirtualEsp32Client.ConnectionState.Connected -> "Connected"
                }}",
                color = Dim, fontFamily = FontFamily.Monospace
            )
            lastError?.let {
                Text("Problem: $it", color = Color.Red, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("CONNECT", client::connect)
                // ZERO-CONFIG FIX (2026-11-06): a manual DISCONNECT is
                // the user's decision — remember it so re-entering this
                // screen never auto-connects the board again.
                TerminalButton("DISCONNECT") { intelVm?.noteManualDisconnect(VIRTUAL_ESP32_ID); client.disconnect() }
            }
        }

        val info = targetInfo
        if (info != null) {
            PanelBox(title = "TARGET") {
                Text("Status: ${info.status}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Processor: ${info.cpu}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Running on: ${info.backend}", color = Dim, fontFamily = FontFamily.Monospace)
                Text("Software: ${info.firmware}", color = Dim, fontFamily = FontFamily.Monospace)
            }
        }

        // Guest lifecycle
        PanelBox(title = "GUEST CONTROL") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("START", client::startGuest)
                TerminalButton("STOP", client::stopGuest)
                TerminalButton("RESTART", client::resetGuest)
            }
        }

        // UART terminal
        PanelBox(title = "UART TERMINAL") {
            OutlinedTextField(
                value = uartInput,
                onValueChange = { uartInput = it },
                label = { Text("Send a message to the board") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("SEND", {
                    if (uartInput.isNotBlank()) {
                        client.sendUart(uartInput)
                        uartInput = ""
                    }
                })
                TerminalButton("TEST REPLY", { client.sendUart("PING") })
                TerminalButton("CLEAR MESSAGES", client::clearUartLog)
            }
            Text(
                uartLog.takeLast(30).joinToString("\n").ifEmpty { "(no messages yet)" },
                color = Color(0xFFA9E5C5), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // GPIO
        PanelBox(title = "GPIO") {
            Text(
                "Pin 2 (board's output): ${gpio2?.let { if (it) "ON" else "OFF" } ?: "(unknown)"}",
                color = Dim, fontFamily = FontFamily.Monospace
            )
            Text(
                "Pin 4 (your input): ${gpio4?.let { if (it) "ON" else "OFF" } ?: "OFF"}",
                color = Dim, fontFamily = FontFamily.Monospace
            )
            TerminalButton("SWITCH PIN 4 ON/OFF", {
                client.setGpio4(!(gpio4 ?: false))
            })
            Text(
                "Pin 2 shows what the board's software is doing. Pin 4 sends your switch into the practice board.",
                color = Dim, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // MASTER PROMPT — LIVE TARGET for this practice board.
        // Shown below the device output; nothing here runs on its own.
        // "Connect" reuses the existing CONNECT path; each control button
        // runs only an existing button action on this screen, and a
        // capability with no matching button is shown as text only.
        // A re-check refreshes the board's card in place, so the list
        // never stacks duplicates of the same board. New on the card:
        // what the thinking engine says, and — once connected — the
        // commands you can tap on the board.
        val virtualLive = remember(latestVirtualReport) {
            LiveTargetStore.all().filter { it.source == InvestigationSource.VIRTUAL_ESP32 }
        }
        if (virtualLive.isNotEmpty()) {
            Text(
                "What we found about this practice board",
                color = Accent, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Nothing here runs on its own — you press the buttons.",
                color = Dim, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            virtualLive.forEach { live ->
                LiveTargetCard(
                    target = live,
                    intelVm = intelVm,
                    isConnected = connState is VirtualEsp32Client.ConnectionState.Connected,
                    onConnect = client::connect,
                    onControl = { control -> runVirtualControl(client, control, gpio4) },
                    onRunCommand = onRunLiveCommand,
                    onOpenRoute = onOpenRoute,
                    commandAnswer = intelVm?.lastCommandAnswer.orEmpty()
                )
            }
        }
    }
}

/**
 * Maps an investigated capability to an EXISTING action on this screen.
 * Anything with no matching button is left alone (shown as text only) —
 * no new action is invented for it. Order matters: "restart" is checked
 * before "start" because it contains "start".
 */
private fun runVirtualControl(
    client: VirtualEsp32Client,
    control: InvestigatedControl,
    pin4On: Boolean?
) {
    // Match on the capability's own label + description (stable fields),
    // never on the data-class toString, which also contains field names.
    val text = (control.label + " " + control.plainDescription).lowercase()
    when {
        "pin 4" in text || "gpio4" in text || "gpio 4" in text || "switch" in text ->
            client.setGpio4(!(pin4On ?: false))
        "pin 2" in text || "gpio2" in text || "gpio 2" in text ->
            client.readGpio2()
        "uart" in text || "message" in text || "send" in text ->
            client.sendUart("PING")
        "restart" in text || "reset" in text ->
            client.resetGuest()
        "start" in text ->
            client.startGuest()
        "stop" in text ->
            client.stopGuest()
        "status" in text || "check" in text ->
            client.queryStatus()
        else -> { /* No matching button on this screen — text only. */ }
    }
}

/** Copy a user-selected firmware file to app-private cache for validation.
 * Returns the cached file, the original content URI, and a validation summary. */
private data class FirmwareCopyResult(
    val success: Boolean,
    val cachedFile: java.io.File?,
    val validationSummary: String
)

/**
 * Package 5 (D-29) — one verdict from the two firmware checks: Ghost
 * Tap's own check of the picked file (localSummary) and the practice
 * board app's check of the file it received (remoteSummary). Pure
 * function, no Android calls. A failed check is never softened, and
 * disagreement is said plainly instead of picked silently.
 */
fun reconcileFirmwareChecks(localSummary: String?, remoteSummary: String?): String {
    val local = localSummary?.trim().orEmpty()
    val remote = remoteSummary?.trim().orEmpty()
    if (local.isEmpty() && remote.isEmpty()) return "Nothing checked yet."
    if (local.isEmpty()) return "Only the practice board has checked it so far: $remote"
    if (remote.isEmpty()) return "Only Ghost Tap has checked it so far: $local"
    fun imageType(summary: String): String = when {
        "Merged flash image" in summary -> "Merged flash image"
        "Single app image" in summary -> "Single app image"
        else -> ""
    }
    val localType = imageType(local)
    val remoteType = imageType(remote)
    val eitherHasIssue = "issue" in local.lowercase() || "issue" in remote.lowercase()
    if (eitherHasIssue) {
        return "One check found a problem — Ghost Tap says: $local. " +
            "The practice board says: $remote. Trust the stricter one."
    }
    if (localType.isNotEmpty() && remoteType.isNotEmpty() && localType != remoteType) {
        return "The checks disagree — Ghost Tap says: $local. " +
            "The practice board says: $remote. Don't start it until they agree."
    }
    val agreed = localType.ifEmpty { remoteType }
    if (agreed.isNotEmpty()) return "Both checks agree: $agreed — good to start."
    return "Both checks ran. Ghost Tap says: $local. The practice board says: $remote."
}

private fun copyFirmwareToSharedStorage(context: Context, uri: Uri): FirmwareCopyResult {
    try {
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            if (nameIndex >= 0) cursor.getString(nameIndex) else "firmware.bin"
        } ?: "firmware.bin"

        // Copy to app-private cache for validation (no permission issues for own app)
        val destDir = File(context.cacheDir, "firmware")
        destDir.mkdirs()
        val destFile = File(destDir, fileName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return FirmwareCopyResult(false, null, "Couldn't open the file you picked")

        // Validate
        val result = FirmwareValidator.validate(destFile)
        val summary = buildString {
            append(result.summary)
            if (result.issues.isNotEmpty()) {
                append(" | Issues: ${result.issues.joinToString("; ")}")
            }
        }

        return FirmwareCopyResult(true, destFile, summary)
    } catch (e: Exception) {
        return FirmwareCopyResult(false, null, "Copying failed: ${e.message}")
    }
}
