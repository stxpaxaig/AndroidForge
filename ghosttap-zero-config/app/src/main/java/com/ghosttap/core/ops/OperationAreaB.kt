package com.ghosttap.core.ops

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ghosttap.GhostTapViewModel
import com.ghosttap.VisualFinding
import com.ghosttap.core.ChannelRanker
import com.ghosttap.core.OpResult
import com.ghosttap.core.SigUuids
import com.ghosttap.data.BaselineStore
import com.ghosttap.data.ScanTarget
import com.ghosttap.data.TargetType
import kotlinx.coroutines.delay

/**
 * Package 3 — Area B (Scan / BLE / SPP / printer ViewModels) operations.
 *
 * Every outcome below wraps an EXISTING ViewModel function — scan, BLE
 * GATT, SPP, printer probe/print, map/learn — with preparation +
 * preconfiguration + verification through the [OperationRunner]. Nothing
 * here re-implements what the ViewModel already does. The one thing the
 * operations add is the orchestration the user used to do by hand:
 * picking the strongest device, pre-picking the most interesting
 * channel, checking SPP before connecting, guiding a map/learn round,
 * and asking once whether a print really came out on paper.
 *
 * The existing functions stay exactly as they are: the 15 s BLE watchdog
 * still lives in `connectBle`, the 133/62/8 failure wording still lives
 * in `gattFailMessage`, and retries happen only through the runner's
 * bounded [RetryPolicy] — nothing here changes a watchdog or a retry
 * count. Plain-words titles here are entry-screen labels only; all
 * existing titles keep their original wording.
 */

/**
 * Small shared UI state owned by Area B: live progress of a bulk
 * read/listen run (the entry screen shows "N of M"), and the stashed
 * device pick of a two-step save flow (device first, name second — the
 * runner only carries one pending choice at a time). The entry screen
 * reads the progress; the two-step operations read the stash.
 */
object AreaBState {
    /** Plain-words label for the bulk run in progress, e.g. "Reading values…". */
    var bulkProgressLabel by mutableStateOf("")

    /** How many channels the bulk run has finished. */
    var bulkProgressDone by mutableIntStateOf(0)

    /** How many channels the bulk run has to do in total. */
    var bulkProgressTotal by mutableIntStateOf(0)

    /** Clears the bulk progress (called when the run finishes, pass or fail). */
    fun clearBulkProgress() {
        bulkProgressLabel = ""
        bulkProgressDone = 0
        bulkProgressTotal = 0
    }

    /** (operation id, target id) picked by the first step of a two-step save flow. */
    var stashedPick: Pair<String, String>? by mutableStateOf(null)
}

/** Signal word for a scan result. Plain words only — never a distance. */
private fun signalWord(rssi: Int?): String = when {
    rssi == null -> "signal unknown"
    rssi >= -50 -> "very strong"
    rssi >= -65 -> "strong"
    rssi >= -80 -> "moderate"
    else -> "weak"
}

/** Plain-words name for a channel: the known name, else its short form. */
private fun channelName(charUuid: String): String =
    SigUuids.characteristicName(charUuid) ?: "Custom feature ${SigUuids.shortForm(charUuid)}"

/**
 * A preparation step that asks the user to pick one target from the
 * candidates matching [filter]. When the user has already picked (the
 * entry screen wrote [OperationContext.pendingChoice] and re-ran), the
 * step passes — the pick is consumed by [chosenTarget] in execute, never
 * here, so a stale pick can never silently carry into a later run.
 */
private fun chooseTargetStep(
    label: String,
    filter: (ScanTarget) -> Boolean,
    emptyReason: String
): PreparationStep = PreparationStep(label) { context ->
    val ble = context.ble
        ?: return@PreparationStep PreparationOutcome.Failed(
            "Can't check devices — the app isn't ready yet."
        )
    val candidates = ble.allTargets.filter(filter)
    if (candidates.isEmpty()) {
        return@PreparationStep PreparationOutcome.Failed(emptyReason)
    }
    val picked = context.pendingChoice
    if (picked != null &&
        candidates.any { it.name == picked || it.id == picked }
    ) {
        return@PreparationStep PreparationOutcome.Ready
    }
    PreparationOutcome.NeedsUserChoice(
        prompt = "Which one?",
        choices = candidates.map { it.name.ifBlank { it.id } }
    )
}

/** Peeks at the picked target without consuming the choice. A pick that
 * no longer matches any candidate fails instead of silently falling back
 * to a different device — operating on the wrong device (printing to the
 * wrong printer, reading the wrong sensor) is worse than failing plainly.
 * With no pick at all (planner / VNIC path) the first candidate is used. */
private fun peekChosenTarget(
    context: OperationContext,
    filter: (ScanTarget) -> Boolean
): ScanTarget? {
    val ble = context.ble ?: return null
    val candidates = ble.allTargets.filter(filter)
    val picked = context.pendingChoice ?: return candidates.firstOrNull()
    return candidates.firstOrNull { it.name == picked || it.id == picked }
}

/**
 * Resolves the target the user picked and consumes the choice, so it
 * never leaks into a later run. Falls back to the first candidate when
 * the operation runs without the entry screen (planner / VNIC path).
 */
private fun chosenTarget(
    context: OperationContext,
    filter: (ScanTarget) -> Boolean
): ScanTarget? {
    val target = peekChosenTarget(context, filter)
    context.pendingChoice = null
    return target
}

/**
 * First step of a two-step save flow (device, then name): picks the
 * device and stashes it in [AreaBState] for the name step. A pick that
 * is not a device means the run is continuing to the name step, so a
 * stashed device for this operation is accepted; a fresh run with no
 * pick forgets any stale stash from an abandoned run.
 */
private fun pickDeviceStep(
    opId: String,
    label: String,
    filter: (ScanTarget) -> Boolean,
    emptyReason: String
): PreparationStep = PreparationStep(label) { context ->
    val ble = context.ble
        ?: return@PreparationStep PreparationOutcome.Failed(
            "Can't check devices — the app isn't ready yet."
        )
    val candidates = ble.allTargets.filter(filter)
    if (candidates.isEmpty()) {
        return@PreparationStep PreparationOutcome.Failed(emptyReason)
    }
    val picked = context.pendingChoice
    if (picked != null) {
        val match = candidates.firstOrNull { it.name == picked || it.id == picked }
        if (match != null) {
            AreaBState.stashedPick = opId to match.id
            context.pendingChoice = null
            return@PreparationStep PreparationOutcome.Ready
        }
        val stashed = AreaBState.stashedPick
        if (stashed?.first == opId && candidates.any { it.id == stashed.second }) {
            return@PreparationStep PreparationOutcome.Ready
        }
        return@PreparationStep PreparationOutcome.NeedsUserChoice(
            prompt = "Which one?",
            choices = candidates.map { it.name.ifBlank { it.id } }
        )
    }
    if (AreaBState.stashedPick?.first == opId) AreaBState.stashedPick = null
    PreparationOutcome.NeedsUserChoice(
        prompt = "Which one?",
        choices = candidates.map { it.name.ifBlank { it.id } }
    )
}

/**
 * Second step of a two-step save flow: asks for the name, offering the
 * suggestions [suggestions] computes for the stashed device. Fails
 * plainly when there is nothing to name yet (no capture, no discovery,
 * no feed event) so the user is never asked to name thin air.
 */
private fun pickNameStep(
    opId: String,
    label: String,
    suggestions: (OperationContext, ScanTarget) -> List<String>
): PreparationStep = PreparationStep(label) { context ->
    val ble = context.ble
        ?: return@PreparationStep PreparationOutcome.Failed(
            "Can't check — the app isn't ready yet."
        )
    val stashedId = AreaBState.stashedPick?.takeIf { it.first == opId }?.second
        ?: return@PreparationStep PreparationOutcome.Failed("Pick a device first.")
    val target = ble.allTargets.firstOrNull { it.id == stashedId }
        ?: return@PreparationStep PreparationOutcome.Failed(
            "That device isn't around any more."
        )
    val names = suggestions(context, target).distinct().filter { it.isNotBlank() }
    if (names.isEmpty()) {
        return@PreparationStep PreparationOutcome.Failed(
            "Nothing to name yet — run the test or the learning step first."
        )
    }
    val picked = context.pendingChoice
    if (picked != null && picked in names) {
        return@PreparationStep PreparationOutcome.Ready
    }
    PreparationOutcome.NeedsUserChoice(
        prompt = "What should it be called?",
        choices = names
    )
}

/**
 * Takes the stashed device and the picked name out of a two-step save
 * flow and clears both, so neither can leak into a later run. Returns
 * (device or null, name or null).
 */
private fun consumeStash(opId: String, context: OperationContext): Pair<ScanTarget?, String?> {
    val stashedId = AreaBState.stashedPick?.takeIf { it.first == opId }?.second
    val target = stashedId?.let { id -> context.ble?.allTargets?.firstOrNull { it.id == id } }
    val name = context.pendingChoice
    AreaBState.stashedPick = null
    context.pendingChoice = null
    return target to name
}

/** Waits, bounded, for a BLE link to report CONNECTED. */
private suspend fun waitForBleConnected(
    ble: GhostTapViewModel,
    targetId: String,
    timeoutMs: Long = 16000L
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (ble.gattStatusText(targetId).startsWith("CONNECTED")) return true
        delay(500)
    }
    return ble.gattStatusText(targetId).startsWith("CONNECTED")
}

/** Waits, bounded, for a text (SPP) link to open. */
private suspend fun waitForSppConnected(
    ble: GhostTapViewModel,
    targetId: String,
    timeoutMs: Long = 12000L
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (ble.isSppConnected(targetId)) return true
        delay(500)
    }
    return ble.isSppConnected(targetId)
}

/**
 * Verifies an execution result whose data carries "BLE:<targetId>" or
 * "SPP:<targetId>" against the live link, right now — never trusting
 * the one-shot connect result. Anything else falls back to the default
 * verification (a real response counts).
 */
private suspend fun verifyTargetData(context: OperationContext, result: OpResult): VerificationOutcome {
    val kind = result.data.substringBefore(":", "")
    val id = result.data.substringAfter(":", "")
    return when (kind) {
        "BLE" -> verifyStillConnected(TransportKind.BLE, id, context)
        "SPP" -> verifyStillConnected(TransportKind.SPP, id, context)
        else -> defaultVerify(result)
    }
}

/** Best-ranked channel the phone can send to, or null when none takes a message. */
private fun mostInterestingWritableChannel(
    ble: GhostTapViewModel,
    targetId: String
): Pair<String, String>? {
    var best: Pair<String, String>? = null
    var bestOrder = -1
    ble.discoveredServices(targetId).forEach { svc ->
        svc.characteristics.forEach { ch ->
            val writable = ch.properties.any {
                it == "WRITE" || it == "WRITE_NO_RESPONSE" || it == "SIGNED_WRITE"
            }
            if (writable) {
                val order = ChannelRanker.rank(ch.properties).order
                if (order > bestOrder) {
                    bestOrder = order
                    best = svc.uuid to ch.uuid
                }
            }
        }
    }
    return best
}

/** Best-ranked channel the phone can read from, or null when none can be read. */
private fun mostInterestingReadableChannel(
    ble: GhostTapViewModel,
    targetId: String
): Pair<String, String>? {
    var best: Pair<String, String>? = null
    var bestOrder = -1
    ble.discoveredServices(targetId).forEach { svc ->
        svc.characteristics.forEach { ch ->
            if ("READ" in ch.properties) {
                val order = ChannelRanker.rank(ch.properties).order
                if (order > bestOrder) {
                    bestOrder = order
                    best = svc.uuid to ch.uuid
                }
            }
        }
    }
    return best
}

private val MAC_REGEX = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

/** First Bluetooth address printed in a Visual Intel finding, or null. */
private fun findMacInFinding(finding: VisualFinding): String? {
    val texts = listOf(finding.fullText) + finding.markings +
        finding.barcodes.map { it.rawValue }
    return texts.firstNotNullOfOrNull { MAC_REGEX.find(it)?.value?.uppercase() }
}

/** Registers every Area B operation. Idempotent; safe to call again. */
fun registerAreaBOperations() {
    val bluetoothFilter: (ScanTarget) -> Boolean =
        { it.type == TargetType.BLE || it.type == TargetType.BLUETOOTH_CLASSIC }
    val connectedBleFilter: (ScanTarget) -> Boolean =
        { it.type == TargetType.BLE && it.connected }

    // ---- Find and connect to the strongest device (B-001 + B-009 + B-060). ----
    // One outcome: scan, pick the strongest, connect, verify it is still
    // connected. The 15 s watchdog lives inside connectBle; a failed
    // connect is retried once by the runner (RetryPolicy), never by hand.
    OperationRegistry.register(
        Operation(
            id = "scan.connect-strongest",
            title = "Find the strongest device and connect to it",
            description = "Searches for about 10 seconds, picks the strongest device found, connects, and checks the link is really open.",
            area = OperationArea.BLE,
            prerequisites = listOf("Bluetooth on"),
            preparation = listOf(
                PreparationStep("Checking permissions") { requestPermissionsAndRerun(it) }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't search — the app isn't ready yet.")
                ble.startScan()
                repeat(10) { delay(1000) }
                ble.stopScan()
                val pick = ble.allTargets.filter { bluetoothFilter(it) && it.rssi != null }
                    .maxByOrNull { it.rssi!! }
                    ?: ble.allTargets.filter(bluetoothFilter).maxByOrNull { it.lastSeenMs }
                    ?: return@Operation OpResult(
                        false,
                        "No devices found — move closer to the device and try again."
                    )
                val display = pick.name.ifBlank { pick.id }
                if (pick.type == TargetType.BLUETOOTH_CLASSIC) {
                    ble.connectSpp(pick.id)
                    if (waitForSppConnected(ble, pick.id)) {
                        OpResult(
                            true,
                            "Text link open to $display (${signalWord(pick.rssi)}) — you can send text now.",
                            "SPP:${pick.id}"
                        )
                    } else {
                        OpResult(
                            false,
                            ble.sppStatusFor(pick.id).ifBlank { "Couldn't open the text link." }
                        )
                    }
                } else {
                    ble.connectBle(pick.id)
                    if (waitForBleConnected(ble, pick.id)) {
                        OpResult(
                            true,
                            "Connected to $display (${signalWord(pick.rssi)}) — its controls are on its screen.",
                            "BLE:${pick.id}"
                        )
                    } else {
                        OpResult(
                            false,
                            ble.gattStatusText(pick.id).ifBlank { "Couldn't connect — the device didn't answer." }
                        )
                    }
                }
            },
            verify = { context, result -> verifyTargetData(context, result) },
            retry = RetryPolicy(maxAttempts = 2, delayMs = 2000L),
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Stop the scan (B-002) — with how many devices were found. ----
    OperationRegistry.register(
        Operation(
            id = "scan.stop-scan",
            title = "Stop finding devices",
            description = "Stops the search and says how many devices were found.",
            area = OperationArea.BLE,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't stop — the app isn't ready yet.")
                val wasScanning = ble.isScanning()
                ble.stopScan()
                val n = ble.allTargets.size
                OpResult(
                    true,
                    if (wasScanning) "Stopped finding devices. $n device(s) found."
                    else "Nothing was scanning. $n device(s) in the list.",
                    "stopped"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Ranked scan list (B-005) — strongest first, in plain words. ----
    OperationRegistry.register(
        Operation(
            id = "scan.ranked-list",
            title = "List devices strongest first",
            description = "Lists the devices found so far, strongest signal first, in plain words.",
            area = OperationArea.BLE,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't list — the app isn't ready yet.")
                val ranked = ble.rankedTargets().filter(bluetoothFilter)
                if (ranked.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "No devices found yet — tap Find devices first."
                    )
                }
                val lines = ranked.take(5).mapIndexed { i, t ->
                    "${i + 1}. ${t.name.ifBlank { t.id }} — ${signalWord(t.rssi)}"
                }
                OpResult(
                    true,
                    "Strongest first:\n" + lines.joinToString("\n"),
                    ranked.first().id
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Refresh the paired list (B-006). ----
    OperationRegistry.register(
        Operation(
            id = "scan.refresh-paired",
            title = "Check paired devices again",
            description = "Re-lists the devices already paired with this phone, without starting a new search.",
            area = OperationArea.BLE,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                ble.refreshPairedDevices()
                val n = ble.allTargets.count { it.type == TargetType.BLUETOOTH_CLASSIC }
                OpResult(
                    true,
                    "Paired devices checked — $n classic device(s) in the list.",
                    "paired:$n"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- What are the phone's own sensors? (B-007). ----
    OperationRegistry.register(
        Operation(
            id = "scan.what-are-sensors",
            title = "What are the phone's own sensors?",
            description = "Explains the phone-sensor entries in the device list — they are this phone's own sensors, not devices nearby.",
            area = OperationArea.BLE,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val sensors = ble.allTargets.filter { it.type == TargetType.SENSOR }
                if (sensors.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "No phone sensors listed yet — tap Find devices first."
                    )
                }
                val lines = sensors.take(3).map { ble.explainSensorTarget(it.id) }
                OpResult(true, lines.joinToString("\n"), "sensors:${sensors.size}")
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Ask for USB permission again (B-008). ----
    OperationRegistry.register(
        Operation(
            id = "usb.ask-permission-again",
            title = "Ask for USB permission again",
            description = "Asks Android again for permission to talk to a USB device you picked.",
            area = OperationArea.USB,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a USB device",
                    { it.type == TargetType.USB },
                    "No USB devices found yet — plug one in and tap Find devices first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't ask — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.USB }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val result = ble.performDeviceAction(target.id, "usb_auth")
                OpResult(result != "USB device no longer present.", result, target.id)
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Connect to the device in the last photo (B-010). ----
    OperationRegistry.register(
        Operation(
            id = "ble.connect-from-photo",
            title = "Connect to the device in the last photo",
            description = "Reads the device address from the last photo and connects to it — a mistyped address is reported in plain words instead of failing silently.",
            area = OperationArea.BLE,
            preparation = listOf(
                PreparationStep("Checking for a photo") { context ->
                    if (context.visual?.lastFinding == null) {
                        PreparationOutcome.Failed(
                            "Take a photo first — no device address spotted yet."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't connect — the app isn't ready yet.")
                val finding = context.visual?.lastFinding
                    ?: return@Operation OpResult(false, "Take a photo first — no device address spotted yet.")
                val address = findMacInFinding(finding)
                    ?: return@Operation OpResult(
                        false,
                        "No device address in the last photo — point the camera at the address printed on the device."
                    )
                val result = ble.connectBleByAddressChecked(address)
                if (!result.startsWith("Connecting to")) {
                    return@Operation OpResult(false, result)
                }
                val id = "visual-${address.trim().uppercase()}"
                if (waitForBleConnected(ble, id)) {
                    OpResult(true, "Connected to $address — its controls are on its screen.", "BLE:$id")
                } else {
                    OpResult(
                        false,
                        ble.gattStatusText(id).ifBlank { "Couldn't connect — the device didn't answer." }
                    )
                }
            },
            verify = { context, result -> verifyTargetData(context, result) },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Read one value — and wait for the answer (B-011). ----
    OperationRegistry.register(
        Operation(
            id = "ble.read-with-answer",
            title = "Read the most interesting thing — and wait for the answer",
            description = "Reads the part of the device that looks most useful, then waits up to 5 seconds for the answer instead of leaving you guessing.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't read — the app isn't ready yet.")
                val target = chosenTarget(context, connectedBleFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val channel = mostInterestingReadableChannel(ble, target.id)
                    ?: return@Operation OpResult(
                        false,
                        "Nothing on this device can be read — try Read everything on its screen."
                    )
                val since = System.currentTimeMillis()
                ble.bleRead(target.id, channel.first, channel.second)
                val deadline = since + 5000L
                while (System.currentTimeMillis() < deadline) {
                    val answer = ble.endpointHistoryFor(target.id).firstOrNull {
                        it.charUuid == channel.second && it.kind == "READ" && it.timestampMs >= since
                    }
                    if (answer != null) {
                        val name = channelName(channel.second)
                        return@Operation OpResult(
                            true,
                            "$name answered: ${answer.hex}" +
                                (answer.ascii?.let { " ($it)" } ?: ""),
                            target.id
                        )
                    }
                    if (!ble.gattStatusText(target.id).startsWith("CONNECTED")) {
                        return@Operation OpResult(
                            false,
                            "The connection dropped while waiting for the answer."
                        )
                    }
                    delay(250)
                }
                OpResult(
                    false,
                    "Asked, but nothing came back in 5 seconds — the device may be slow or not answering reads."
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Read everything, with live progress and a what-changed summary (B-012). ----
    OperationRegistry.register(
        Operation(
            id = "ble.read-everything",
            title = "Read everything on this device",
            description = "Reads every value the device offers, shows how many are done, and finishes with a plain-words summary of what changed since the last read.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't read — the app isn't ready yet.")
                val target = chosenTarget(context, connectedBleFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val readable = ble.discoveredServices(target.id).flatMap { svc ->
                    svc.characteristics.filter { "READ" in it.properties }
                        .map { svc.uuid to it.uuid }
                }
                if (readable.isEmpty()) {
                    return@Operation OpResult(false, "Nothing on this device can be read.")
                }
                val beforeWatch = ble.allTargets.firstOrNull { it.id == target.id }
                    ?.watchValues ?: emptyMap()
                val startedAt = System.currentTimeMillis()
                AreaBState.bulkProgressLabel = "Reading values…"
                AreaBState.bulkProgressDone = 0
                AreaBState.bulkProgressTotal = readable.size
                try {
                    ble.bleDumpAll(target.id)
                    val answered = mutableSetOf<String>()
                    val deadline = startedAt + readable.size * 150L + 8000L
                    var lastGrowthAt = startedAt
                    var lastCount = 0
                    while (System.currentTimeMillis() < deadline) {
                        val fresh = ble.endpointHistoryFor(target.id)
                            .filter { it.kind == "READ" && it.timestampMs >= startedAt }
                        answered += fresh.map { it.charUuid }
                        AreaBState.bulkProgressDone = answered.size
                        if (answered.size >= readable.size) break
                        if (fresh.size != lastCount) {
                            lastCount = fresh.size
                            lastGrowthAt = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - lastGrowthAt > 2000) break
                        if (!ble.gattStatusText(target.id).startsWith("CONNECTED")) break
                        delay(500)
                    }
                    val afterWatch = ble.allTargets.firstOrNull { it.id == target.id }
                        ?.watchValues ?: emptyMap()
                    val changed = afterWatch.filter { (k, v) -> beforeWatch[k] != v }.keys
                    val changedNames = if (changed.isEmpty()) "nothing changed since the last read"
                    else changed.joinToString(", ") { channelName(it) }
                    OpResult(
                        true,
                        "Read ${answered.size} of ${readable.size} value(s). " +
                            "Changed since the last read: $changedNames.",
                        target.id
                    )
                } finally {
                    AreaBState.clearBulkProgress()
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Listen to everything, with live progress (B-013). ----
    OperationRegistry.register(
        Operation(
            id = "ble.listen-everything",
            title = "Listen to everything on this device",
            description = "Turns on live updates from every part of the device that can send them, shows how many are on, and says what to watch.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't listen — the app isn't ready yet.")
                val target = chosenTarget(context, connectedBleFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val notifiable = ble.discoveredServices(target.id).flatMap { svc ->
                    svc.characteristics
                        .filter { "NOTIFY" in it.properties || "INDICATE" in it.properties }
                        .map { svc.uuid to it.uuid }
                }
                if (notifiable.isEmpty()) {
                    return@Operation OpResult(false, "Nothing on this device sends live updates.")
                }
                val startedAt = System.currentTimeMillis()
                AreaBState.bulkProgressLabel = "Turning on live updates…"
                AreaBState.bulkProgressDone = 0
                AreaBState.bulkProgressTotal = notifiable.size
                try {
                    ble.bleSubscribeAll(target.id)
                    val deadline = startedAt + notifiable.size * 150L + 6000L
                    var lastGrowthAt = startedAt
                    var lastCount = 0
                    while (System.currentTimeMillis() < deadline) {
                        val on = ble.notifyChannels[target.id]?.size ?: 0
                        AreaBState.bulkProgressDone = on
                        if (on >= notifiable.size) break
                        if (on != lastCount) {
                            lastCount = on
                            lastGrowthAt = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - lastGrowthAt > 2000) break
                        if (!ble.gattStatusText(target.id).startsWith("CONNECTED")) break
                        delay(500)
                    }
                    val on = ble.notifyChannels[target.id]?.size ?: 0
                    OpResult(
                        true,
                        "Listening to $on of ${notifiable.size} live channel(s) — " +
                            "new readings appear in the history as they arrive.",
                        target.id
                    )
                } finally {
                    AreaBState.clearBulkProgress()
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Send this — channel pre-picked from the most interesting one (B-014–B-017 + C-14). ----
    OperationRegistry.register(
        Operation(
            id = "ble.send-to-interesting",
            title = "Send a message to the most interesting part",
            description = "Picks the part of the device most likely to take a message, so you type the message — not the technical ID — and send it from the device screen.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't send — the app isn't ready yet.")
                val target = chosenTarget(context, connectedBleFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val channel = mostInterestingWritableChannel(ble, target.id)
                    ?: return@Operation OpResult(
                        false,
                        "Nothing on this device takes a message — try Read everything on its screen instead."
                    )
                ble.selectRawCharacteristic(target.id, channel.first, channel.second)
                context.navigate("target/${target.id}")
                val name = channelName(channel.second)
                OpResult(
                    true,
                    "Ready to send to $name — type your message in the Send box on the device screen. " +
                        "Try plain words first — most devices that take text answer in words; " +
                        "switch to Code if nothing answers. Sent as plain words (UTF-8) unless you switch.",
                    target.id
                )
            },
            verify = { context, result ->
                val ble = context.ble
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "Can't check — the app isn't ready yet."
                    )
                val target = ble.allTargets.firstOrNull { it.id == result.data }
                    ?: return@Operation VerificationOutcome.NotVerified(
                        "That device isn't around any more."
                    )
                val channel = mostInterestingWritableChannel(ble, target.id)
                if (channel != null && ble.rawSelection(target.id) == channel) {
                    VerificationOutcome.Verified("Channel picked: ${channelName(channel.second)}.")
                } else {
                    VerificationOutcome.NotVerified(
                        "The channel didn't stay picked — pick it in Control and try again."
                    )
                }
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Disconnect everything (B-018 / B-021). ----
    OperationRegistry.register(
        Operation(
            id = "ble.disconnect-all",
            title = "Disconnect everything",
            description = "Closes every open Bluetooth link — Bluetooth and text links alike.",
            area = OperationArea.BLE,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't disconnect — the app isn't ready yet.")
                ble.disconnectAll()
                OpResult(true, "Disconnected everything.", "all")
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- SPP connect with timeout and pre-check (B-019–B-022). ----
    OperationRegistry.register(
        Operation(
            id = "spp.connect-checked",
            title = "Open a text link — checked first",
            description = "Checks whether the device is paired and offers a text link, then opens the link — giving up after 12 seconds instead of hanging.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device",
                    bluetoothFilter,
                    "No Bluetooth devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't connect — the app isn't ready yet.")
                val target = chosenTarget(context, bluetoothFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val display = target.name.ifBlank { target.id }
                val paired = ble.isPaired(target.id)
                // SDP (the "does it offer SPP?" check) only speaks for
                // Classic devices — a BLE advertisement carries no SDP
                // answer, so for BLE targets the text link stays try-it-and-see.
                val offered = if (target.type == TargetType.BLUETOOTH_CLASSIC) {
                    ble.checkSppOffered(target.id)
                } else {
                    null
                }
                if (offered == false) {
                    return@Operation OpResult(
                        false,
                        "$display doesn't offer a text link — it may only talk over Bluetooth Low Energy."
                    )
                }
                val precheck = buildString {
                    append(if (paired) "Paired. " else "Not paired yet — it may ask to pair when connecting. ")
                    append(
                        when {
                            offered == true -> "It offers a text link. "
                            target.type == TargetType.BLUETOOTH_CLASSIC ->
                                "Couldn't tell in advance whether it offers a text link — trying anyway. "
                            else -> "Bluetooth Low Energy device — the text link is a try-it-and-see. "
                        }
                    )
                }
                ble.connectSpp(target.id)
                if (waitForSppConnected(ble, target.id)) {
                    OpResult(
                        true,
                        precheck + "Text link open to $display — you can send text now.",
                        "SPP:${target.id}"
                    )
                } else {
                    OpResult(
                        false,
                        precheck + ble.sppStatusFor(target.id)
                            .ifBlank { "Couldn't open the text link." }
                    )
                }
            },
            verify = { context, result -> verifyTargetData(context, result) },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Is the device talking back? (B-020 — the acknowledgement the send itself cannot give). ----
    OperationRegistry.register(
        Operation(
            id = "spp.watch-for-reply",
            title = "Is this device talking back?",
            description = "Watches an open text link for 5 seconds and reports anything the device sends back — the closest thing to a reply the link itself can give.",
            area = OperationArea.BLE,
            preparation = listOf(
                PreparationStep("Pick a device with an open text link") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val candidates = ble.allTargets.filter { ble.isSppConnected(it.id) }
                    if (candidates.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "No text link open — open one first with 'Open a text link — checked first'."
                        )
                    }
                    val picked = context.pendingChoice
                    if (picked != null &&
                        candidates.any { it.name == picked || it.id == picked }
                    ) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which one?",
                        choices = candidates.map { it.name.ifBlank { it.id } }
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't watch — the app isn't ready yet.")
                val target = chosenTarget(context) { ble.isSppConnected(it.id) }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val before = ble.sppLogFor(target.id).size
                val deadline = System.currentTimeMillis() + 5000L
                while (System.currentTimeMillis() < deadline) {
                    val fresh = ble.sppLogFor(target.id).drop(before)
                    val reply = fresh.lastOrNull { "RX" in it }
                    if (reply != null) {
                        return@Operation OpResult(
                            true,
                            "It answered back: $reply",
                            target.id
                        )
                    }
                    if (!ble.isSppConnected(target.id)) {
                        return@Operation OpResult(false, "The text link closed while watching.")
                    }
                    delay(500)
                }
                OpResult(
                    false,
                    "Nothing came back in 5 seconds — the device may only listen, or it may need a message first."
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Print these words (B-023–B-026 + D-49/D-50). ----
    OperationRegistry.register(
        Operation(
            id = "print.print-words",
            title = "Print these words",
            description = "Gets the printer ready — finds its language once and remembers it — then you type your words and tap PRINT on the device screen. After it prints, answer 'Did it print?'",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a printer",
                    bluetoothFilter,
                    "No Bluetooth devices found yet — tap Find devices first."
                ),
                PreparationStep("Checking the text link is open") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val target = peekChosenTarget(context, bluetoothFilter)
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Couldn't find that device any more."
                        )
                    if (ble.isSppConnected(target.id)) PreparationOutcome.Ready
                    else PreparationOutcome.NeedsUserAction(
                        prompt = "No printer link open — connect with Try text link first.",
                        actionLabel = "Connect text link",
                        action = { ble.connectSpp(target.id) }
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't print — the app isn't ready yet.")
                val target = chosenTarget(context, bluetoothFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that printer any more.")
                if (!ble.isSppConnected(target.id)) {
                    return@Operation OpResult(
                        false,
                        "No text link open to ${target.name.ifBlank { target.id }} — connect with Try text link first."
                    )
                }
                val address = target.identity["address"]
                    ?: return@Operation OpResult(
                        false,
                        "That device has no address to remember the language by."
                    )
                context.navigate("target/${target.id}")
                if (ble.printerLanguageFor(address) == null) {
                    ble.startPrinterProbe(target.id)
                    OpResult(
                        true,
                        "Finding the printer's language — watch the paper and answer Yes or No on the device screen.",
                        target.id
                    )
                } else {
                    OpResult(
                        true,
                        "Ready to print — type your words and tap PRINT on the device screen. " +
                            "After it prints, answer “Did it print?”",
                        target.id
                    )
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Try the next printer language (B-024 — one-language re-probe). ----
    OperationRegistry.register(
        Operation(
            id = "print.try-next-language",
            title = "Try the next printer language",
            description = "Forgets the printer language that didn't print right and tries the next one — watch the paper and answer Yes or No.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a printer",
                    bluetoothFilter,
                    "No Bluetooth devices found yet — tap Find devices first."
                ),
                PreparationStep("Checking a language is already locked") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val target = peekChosenTarget(context, bluetoothFilter)
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Couldn't find that device any more."
                        )
                    val address = target.identity["address"]
                    if (address != null && ble.lockedPrinterLang[address] != null) {
                        PreparationOutcome.Ready
                    } else {
                        PreparationOutcome.Failed(
                            "No printer language locked for this device yet — run 'Print these words' first."
                        )
                    }
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't try — the app isn't ready yet.")
                val target = chosenTarget(context, bluetoothFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that printer any more.")
                ble.reprobeOnePrinterLanguage(target.id)
                context.navigate("target/${target.id}")
                OpResult(
                    true,
                    "Trying the next printer language — watch the paper and answer Yes or No on the device screen.",
                    target.id
                )
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Guided map round (B-045–B-047) — reads first, then "do one thing now". ----
    OperationRegistry.register(
        Operation(
            id = "ble.guided-map-round",
            title = "Test what changes when I use the device",
            description = "Starts a guided test: it reads the device first so there is something to compare against, then you do one thing on the device and stop.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't test — the app isn't ready yet.")
                val target = chosenTarget(context, connectedBleFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                // Build something to compare against first: read everything,
                // then start the round. If nothing answers, the round still
                // starts and says so plainly.
                ble.bleDumpAll(target.id)
                val deadline = System.currentTimeMillis() + 8000L
                while (System.currentTimeMillis() < deadline) {
                    if (ble.endpointHistoryFor(target.id).isNotEmpty()) break
                    if (!ble.gattStatusText(target.id).startsWith("CONNECTED")) break
                    delay(500)
                }
                val status = ble.startMapRound(target.id)
                context.navigate("target/${target.id}")
                OpResult(
                    true,
                    "$status Try to do it within the next minute, then tap Stop.",
                    target.id
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Save the last test as a one-tap control (B-047 + A-16). ----
    OperationRegistry.register(
        Operation(
            id = "map.save-as-control",
            title = "Save the last test as a one-tap control",
            description = "Turns the last map test's repeatable change into a named control you can play again with one tap.",
            area = OperationArea.BLE,
            preparation = listOf(
                pickDeviceStep(
                    "map.save-as-control",
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                ),
                pickNameStep(
                    "map.save-as-control",
                    "Name the control",
                    { context, target ->
                        val ble = context.ble ?: return@pickNameStep emptyList()
                        val latest = ble.mapCapturesFor(target.id)
                            .filter { it.changes.isNotEmpty() }
                            .maxByOrNull { it.finishedAtMs }
                            ?: return@pickNameStep emptyList()
                        listOf(
                            ble.suggestMapActionName(target.id, latest.label),
                            "${latest.label} change"
                        )
                    }
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't save — the app isn't ready yet.")
                val (target, name) = consumeStash("map.save-as-control", context)
                val t = target
                    ?: return@Operation OpResult(false, "Pick a device first.")
                val capture = ble.mapCapturesFor(t.id)
                    .filter { it.changes.isNotEmpty() }
                    .maxByOrNull { it.finishedAtMs }
                    ?: return@Operation OpResult(
                        false,
                        "No test with changes yet — run a guided test first."
                    )
                val saved = ble.saveMapCaptureAsAction(
                    t.id,
                    capture.label,
                    name ?: ble.suggestMapActionName(t.id, capture.label)
                ) ?: return@Operation OpResult(
                    false,
                    "Couldn't save that test — try running the test again."
                )
                OpResult(
                    true,
                    "Saved as '${saved.name}' — find it under Learned controls on the device screen. Tap it to play it again.",
                    t.id
                )
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Guided learn (B-053) — "do one thing on the device now". ----
    OperationRegistry.register(
        Operation(
            id = "ble.guided-learn",
            title = "Teach Ghost Tap one thing the device does",
            description = "Starts a guided learning session: do one thing on the device, then stop — Ghost Tap works out which message that was.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't learn — the app isn't ready yet.")
                val target = chosenTarget(context, connectedBleFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val guidance = ble.startLearning(target.id)
                context.navigate("target/${target.id}")
                OpResult(
                    true,
                    "$guidance Try to do it within the next minute, while watching the live feed, then tap Stop learning.",
                    target.id
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Save what was just taught, with a suggested name (B-054). ----
    OperationRegistry.register(
        Operation(
            id = "learn.save-with-name",
            title = "Save what I just taught it",
            description = "Saves the last learning result as a named control, suggesting a name from what actually changed.",
            area = OperationArea.BLE,
            preparation = listOf(
                pickDeviceStep(
                    "learn.save-with-name",
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                ),
                pickNameStep(
                    "learn.save-with-name",
                    "Name the control",
                    { context, target ->
                        val ble = context.ble ?: return@pickNameStep emptyList()
                        val disc = ble.pendingDiscoveryFor(target.id)
                        if (disc == null || disc.packetHex.isBlank()) {
                            return@pickNameStep emptyList()
                        }
                        listOf(ble.suggestDiscoveryName(target.id), "Learned action")
                    }
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't save — the app isn't ready yet.")
                val (target, name) = consumeStash("learn.save-with-name", context)
                val t = target
                    ?: return@Operation OpResult(false, "Pick a device first.")
                val saved = ble.saveDiscoveryAsAction(
                    t.id,
                    name ?: ble.suggestDiscoveryName(t.id)
                ) ?: return@Operation OpResult(
                    false,
                    "Nothing to save — the learning step found no clear change."
                )
                OpResult(
                    true,
                    "Saved as '${saved.name}' — find it under Learned controls on the device screen. Tap it to play it again.",
                    t.id
                )
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Play the newest saved action again (B-054). ----
    OperationRegistry.register(
        Operation(
            id = "learn.replay-newest",
            title = "Play the newest saved action again",
            description = "Sends the newest saved control for a device again, and reports what the device said back.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't play — the app isn't ready yet.")
                val target = chosenTarget(context, connectedBleFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val newest = ble.learnedActionsFor(target.id)
                    .maxByOrNull { it.learnedAtMs }
                    ?: return@Operation OpResult(
                        false,
                        "No saved actions for this device yet — teach it one first."
                    )
                val result = ble.replayLearned(target.id, newest.id)
                OpResult("Sent to " in result, result, target.id)
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- Save the last thing the device said (B-051 + A-11). ----
    OperationRegistry.register(
        Operation(
            id = "learn.save-last-event",
            title = "Save the last thing the device said",
            description = "Saves the newest message in the live translator feed as a named control you can play again.",
            area = OperationArea.BLE,
            preparation = listOf(
                pickDeviceStep(
                    "learn.save-last-event",
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                ),
                pickNameStep(
                    "learn.save-last-event",
                    "Name the control",
                    { context, target ->
                        val ble = context.ble ?: return@pickNameStep emptyList()
                        val event = ble.latestFeedEvent(target.id)
                            ?: return@pickNameStep emptyList()
                        listOf(
                            ble.suggestFeedEventName(target.id, event.timestampMs),
                            "Saved from the live feed"
                        )
                    }
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't save — the app isn't ready yet.")
                val (target, name) = consumeStash("learn.save-last-event", context)
                val t = target
                    ?: return@Operation OpResult(false, "Pick a device first.")
                val event = ble.latestFeedEvent(t.id)
                    ?: return@Operation OpResult(
                        false,
                        "Nothing heard from this device yet — read a value or turn on live updates first."
                    )
                val saved = ble.savePacketEventAsAction(
                    t.id,
                    event.timestampMs,
                    name ?: ble.suggestFeedEventName(t.id, event.timestampMs)
                ) ?: return@Operation OpResult(
                    false,
                    "Couldn't save that message — try again while the device is talking."
                )
                OpResult(
                    true,
                    "Saved as '${saved.name}' — find it under Learned controls on the device screen. Tap it to play it again.",
                    t.id
                )
            },
            riskLevel = "WRITE",
            verified = false
        )
    )

    // ---- What just happened on this device? (B-051 / B-055). ----
    OperationRegistry.register(
        Operation(
            id = "ble.what-just-happened",
            title = "What just happened on this device?",
            description = "Gives a plain-words verdict on the device's recent messages — the busiest part, what it last said, and whether it keeps repeating.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    connectedBleFilter,
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val target = chosenTarget(context, connectedBleFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                OpResult(true, ble.summarizeFeed(target.id), target.id)
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Explain this device in plain words (B-052). ----
    OperationRegistry.register(
        Operation(
            id = "ble.explain-this-device",
            title = "Explain this device in plain words",
            description = "Reads the full identification evidence for a device and lays it out in plain words.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a Bluetooth device",
                    { it.type == TargetType.BLE },
                    "No Bluetooth devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't explain — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.BLE }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val summary = ble.deviceSummary(target.id)
                val deep = ble.explainDeviceDeep(target.id)
                OpResult(
                    true,
                    "${summary.deviceType} (${summary.typeConfidence} confidence).\n" +
                        deep.take(6).joinToString("\n"),
                    target.id
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Do the sensible next thing (B-048 / B-049). ----
    OperationRegistry.register(
        Operation(
            id = "target.do-next-thing",
            title = "Do the sensible next thing",
            description = "Works out the one sensible next step for a device — connect, disconnect, read, or copy — and does it.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device",
                    { true },
                    "No devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't do anything — the app isn't ready yet.")
                val target = chosenTarget(context) { true }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val next = ble.deviceActions(target.id).firstOrNull {
                    it.available && it.id in EXECUTABLE_ACTION_IDS
                }
                if (next == null) {
                    context.navigate("target/${target.id}")
                    return@Operation OpResult(
                        true,
                        "The next step needs you on the device screen — opened it for you.",
                        target.id
                    )
                }
                val result = ble.performDeviceAction(target.id, next.id)
                when (next.id) {
                    "ble_connect" -> {
                        if (waitForBleConnected(ble, target.id)) {
                            OpResult(
                                true,
                                "Connected to ${target.name.ifBlank { target.id }} — its controls are on its screen.",
                                "BLE:${target.id}"
                            )
                        } else {
                            OpResult(
                                false,
                                ble.gattStatusText(target.id)
                                    .ifBlank { "Couldn't connect — the device didn't answer." }
                            )
                        }
                    }
                    "spp_connect" -> {
                        if (waitForSppConnected(ble, target.id)) {
                            OpResult(
                                true,
                                "Text link open to ${target.name.ifBlank { target.id }} — you can send text now.",
                                "SPP:${target.id}"
                            )
                        } else {
                            OpResult(
                                false,
                                ble.sppStatusFor(target.id)
                                    .ifBlank { "Couldn't open the text link." }
                            )
                        }
                    }
                    else -> OpResult(true, "${next.label}: $result", target.id)
                }
            },
            verify = { context, result -> verifyTargetData(context, result) },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Ask a device on the list a plain web question (B-056). ----
    OperationRegistry.register(
        Operation(
            id = "net.ask-device",
            title = "Ask a device on my Wi-Fi a question",
            description = "Asks a device found on your Wi-Fi a plain web question (GET /) and reports what it answers. Plain HTTP only — not a secure connection.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device on your Wi-Fi",
                    { it.type == TargetType.NETWORK },
                    "No network devices found yet — find devices on your Wi-Fi first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't ask — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.NETWORK }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val body = ble.networkRawRequest(target.id, "GET", "/", "")
                if (body.startsWith("Couldn't reach it:")) {
                    OpResult(false, body)
                } else {
                    OpResult(true, "It answered: ${body.take(300)}", target.id)
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- See the connections on a USB device (B-057). ----
    OperationRegistry.register(
        Operation(
            id = "usb.list-connections",
            title = "See the connections on a USB device",
            description = "Lists the connections a plugged-in USB device offers, so a message can go to the right one instead of always the first.",
            area = OperationArea.USB,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a USB device",
                    { it.type == TargetType.USB },
                    "No USB devices found yet — plug one in and tap Find devices first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.USB }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val interfaces = ble.usbInterfacesFor(target.id)
                if (interfaces.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "That isn't a USB device any more."
                    )
                }
                OpResult(
                    true,
                    "Connections on ${target.name.ifBlank { target.id }}:\n" +
                        interfaces.joinToString("\n"),
                    target.id
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Save this list of devices (B-058). ----
    OperationRegistry.register(
        Operation(
            id = "baseline.save",
            title = "Save this list of devices",
            description = "Saves the devices around right now, so a later comparison can name what is new, gone, or changed.",
            area = OperationArea.SYSTEM,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't save — the app isn't ready yet.")
                ble.saveBaseline()
                val app = ble.getApplication<Application>()
                val snapshot = runCatching { BaselineStore(app).load() }.getOrNull()
                    ?: return@Operation OpResult(false, "Couldn't save the list — try again.")
                OpResult(
                    true,
                    "Saved — ${snapshot.targets.size} device(s) in the list.",
                    "baseline:${snapshot.targets.size}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Compare against the saved list (B-059). ----
    // compareWithBaseline() publishes its answer in baselineDiff, so the
    // operation reads that back and names the new / gone / changed devices
    // in plain words. Two preparation checks close the entry's gap: no
    // saved list yet means "save one first", and an empty scan means the
    // comparison would be against stale data, so it refuses instead.
    OperationRegistry.register(
        Operation(
            id = "baseline.compare",
            title = "What's new since I saved the list?",
            description = "Compares the devices around now with the saved list and names what is new, gone, or changed.",
            area = OperationArea.SYSTEM,
            preparation = listOf(
                PreparationStep("Checking a list was saved") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val app = ble.getApplication<Application>()
                    val snapshot = runCatching { BaselineStore(app).load() }.getOrNull()
                    if (snapshot == null || snapshot.targets.isEmpty()) {
                        PreparationOutcome.Failed(
                            "No list saved yet — run 'Save this list of devices' first."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                },
                PreparationStep("Checking there is something to compare") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    if (ble.allTargets.isEmpty()) {
                        PreparationOutcome.Failed(
                            "Nothing around right now — tap Find devices first, so the comparison isn't against a stale list."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't compare — the app isn't ready yet.")
                ble.compareWithBaseline()
                val diff = ble.baselineDiff
                val nameOf: (String) -> String = { id ->
                    ble.allTargets.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
                        ?: ble.displayNameFor(id)
                }
                val parts = mutableListOf<String>()
                if (diff.newTargets.isNotEmpty()) {
                    parts += "New: " + diff.newTargets.joinToString(", ") { nameOf(it) }
                }
                if (diff.missingTargets.isNotEmpty()) {
                    parts += "Gone: " + diff.missingTargets.joinToString(", ") { nameOf(it) }
                }
                if (diff.changedTargets.isNotEmpty()) {
                    parts += "Changed: " + diff.changedTargets.joinToString(", ") { nameOf(it) }
                }
                if (diff.signalChangedTargets.isNotEmpty()) {
                    parts += "Signal changed a lot: " +
                        diff.signalChangedTargets.joinToString(", ") { nameOf(it) }
                }
                if (parts.isEmpty()) {
                    OpResult(
                        true,
                        "Nothing new, gone, or changed since the saved list.",
                        "compared"
                    )
                } else {
                    OpResult(true, parts.joinToString("\n"), "compared")
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Refresh USB and sensor readings (B-061). ----
    OperationRegistry.register(
        Operation(
            id = "scan.refresh-usb-sensors",
            title = "Refresh USB and sensor readings",
            description = "Re-checks plugged-in USB devices and the phone's own sensors without starting a new search.",
            area = OperationArea.BLE,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't refresh — the app isn't ready yet.")
                ble.refreshUsbAndSensorsWatch()
                val usb = ble.allTargets.count { it.type == TargetType.USB }
                val sensors = ble.allTargets.count { it.type == TargetType.SENSOR }
                OpResult(
                    true,
                    "Refreshed — $usb USB device(s), $sensors phone sensor(s) in the list.",
                    "refreshed"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Save the activity log to a file (B-062). ----
    OperationRegistry.register(
        Operation(
            id = "logs.save-to-file",
            title = "Save the activity log to a file",
            description = "Saves the whole activity log to a file and says where it is.",
            area = OperationArea.SYSTEM,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't save — the app isn't ready yet.")
                val file = ble.exportLogsToFile()
                    ?: return@Operation OpResult(false, "Couldn't save the file.")
                OpResult(true, "Saved to ${file.absolutePath}.", file.absolutePath)
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Clear the activity log (B-062). ----
    OperationRegistry.register(
        Operation(
            id = "logs.clear",
            title = "Clear the activity log",
            description = "Clears the activity log. This cannot be undone.",
            area = OperationArea.SYSTEM,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't clear — the app isn't ready yet.")
                ble.clearLogs()
                val deadline = System.currentTimeMillis() + 3000L
                while (System.currentTimeMillis() < deadline) {
                    if (ble.logs.value.isEmpty()) {
                        return@Operation OpResult(true, "Activity log cleared.", "cleared")
                    }
                    delay(250)
                }
                OpResult(false, "Couldn't clear the log — try again.")
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Find the device from the photo hint (B-050). ----
    OperationRegistry.register(
        Operation(
            id = "scan.find-hinted",
            title = "Find the device from the photo hint",
            description = "Finds the device Visual Intel named in its hint and highlights it in the device list.",
            area = OperationArea.BLE,
            preparation = listOf(
                PreparationStep("Checking for a photo hint") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    if (ble.lastVisualHint.isBlank()) {
                        PreparationOutcome.Failed(
                            "No photo hint yet — point the camera at something first."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't find — the app isn't ready yet.")
                val hint = ble.lastVisualHint
                val matches = ble.targetsMatchingHint()
                if (matches.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "Nothing matches '$hint' yet — tap Find devices and try again."
                    )
                }
                val pick = matches.first()
                AreaAState.highlightedTargetId = pick.id
                AreaAState.highlightedLabel = "Matches the photo hint"
                OpResult(
                    true,
                    "The photo hint '$hint' matches ${pick.name.ifBlank { pick.id }} (${signalWord(pick.rssi)}).",
                    pick.id
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )
}

/** Action ids [GhostTapViewModel.performDeviceAction] can run directly (no panel needed). */
private val EXECUTABLE_ACTION_IDS = setOf(
    "ble_connect", "ble_disconnect",
    "spp_connect", "spp_disconnect",
    "nfc_read", "nfc_copy", "nfc_write_saved", "nfc_erase", "nfc_format",
    "usb_auth"
)
