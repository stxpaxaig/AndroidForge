package com.ghosttap.core.ops

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ghosttap.GhostTapViewModel
import com.ghosttap.IntelViewModel
import com.ghosttap.core.BringUpKind
import com.ghosttap.core.BringUpState
import com.ghosttap.core.BringUpTarget
import com.ghosttap.core.IntelTarget
import com.ghosttap.core.OpResult
import com.ghosttap.data.BaselineStore
import com.ghosttap.data.ScanTarget
import com.ghosttap.data.TargetType
import kotlinx.coroutines.delay

/**
 * Package 2 — Area A (main UI & navigation) operations.
 *
 * Every outcome below wraps an EXISTING function — a screen, a ViewModel
 * call, or an IntelViewModel call — with preparation + preconfiguration +
 * verification through the [OperationRunner]. Nothing here re-implements
 * what the screens or ViewModels already do.
 *
 * The six function screens are registered under the SAME ids Package 0
 * seeded (`screen.*`): registration keeps the latest definition, so these
 * preconfigured versions replace the plain open-screen seeds. The six
 * main-screen buttons keep their EXACT original wording — the plain-words
 * titles here are entry-screen labels only.
 */

/**
 * Small shared UI state owned by Area A: the scan highlight (which target
 * the last "find the strongest" run picked) and a details request (the
 * target screen opens its technical panels for this target). The target
 * screen and the home screen read these as Compose state.
 */
object AreaAState {
    /** Target id the last strongest/newest scan picked, or null. */
    var highlightedTargetId: String? by mutableStateOf(null)

    /** Plain-words label for the highlight, e.g. "Strongest device found". */
    var highlightedLabel: String by mutableStateOf("")

    /** Target id whose technical detail panels should open, or null. */
    var detailsRequestedFor: String? by mutableStateOf(null)
}

/** The three quick smart-card checks the APDU console offers. */
private val APDU_QUICK_CHECKS = listOf(
    "Pick stored data" to "00 A4 04 00 07 D2 76 00 00 85 01 01 00",
    "Read data" to "00 B0 00 00 00",
    "Get info" to "00 CA 00 00 00"
)

/** Signal word for a scan result. Plain words only — never a distance. */
private fun signalWord(rssi: Int?): String = when {
    rssi == null -> "signal unknown"
    rssi >= -50 -> "very strong"
    rssi >= -65 -> "strong"
    rssi >= -80 -> "moderate"
    else -> "weak"
}

/**
 * Rebuilds the bring-up target for the banner's one-tap retry (A-02)
 * from what [IntelViewModel] remembers about the last bring-up: kind,
 * id, and the target's name when it is still around. Null when there is
 * no bring-up to retry. NFC bring-up checks the live tag, not this id,
 * so a rebuilt NFC target still retries honestly.
 */
fun rebuildBringUpTarget(intel: IntelViewModel, ble: GhostTapViewModel): BringUpTarget? {
    val kind = intel.bringUpTargetKind ?: return null
    val id = intel.bringUpTargetId.ifBlank { return null }
    val title = when (kind) {
        BringUpKind.BLE -> ble.allTargets.firstOrNull { it.id == id }
            ?.name?.ifBlank { id } ?: id
        BringUpKind.NFC -> "NFC tag"
        BringUpKind.NETWORK -> id
        BringUpKind.USB -> "USB serial"
    }
    return BringUpTarget(kind = kind, id = id, title = title)
}

/** Plain-words evidence bundle for one-tap INVESTIGATE entries. */
private fun targetEvidenceText(t: ScanTarget): String = buildString {
    append(t.name.ifBlank { t.id })
    append(" [${t.type}]")
    if (t.identity.isNotEmpty()) {
        append("\n")
        append(t.identity.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    t.likely?.let { append("\nLikely: $it (${t.confidence ?: "?"})") }
    if (t.evidence.isNotEmpty()) {
        append("\nEvidence: ${t.evidence.take(5).joinToString("; ")}")
    }
}.take(2000)

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

/** Peeks at the picked target without consuming the choice. */
private fun peekChosenTarget(
    context: OperationContext,
    filter: (ScanTarget) -> Boolean
): ScanTarget? {
    val ble = context.ble ?: return null
    val candidates = ble.allTargets.filter(filter)
    val picked = context.pendingChoice ?: return candidates.firstOrNull()
    return candidates.firstOrNull { it.name == picked || it.id == picked }
        ?: candidates.firstOrNull()
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

/** Waits, bounded, for a bring-up to land READY or FAILED. */
private suspend fun waitForBringUp(intel: IntelViewModel, timeoutMs: Long = 20000L): OpResult {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        when (intel.bringUpState) {
            BringUpState.READY -> return OpResult(
                true,
                intel.bringUpStatus.ifBlank { "Ready — you're in control." },
                intel.bringUpTargetId
            )
            BringUpState.FAILED -> return OpResult(
                false,
                intel.bringUpStatus.ifBlank { "Couldn't get it ready." }
            )
            else -> delay(500)
        }
    }
    return OpResult(
        false,
        "Still getting it ready — watch the banner at the bottom of the screen."
    )
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
    timeoutMs: Long = 10000L
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (ble.isSppConnected(targetId)) return true
        delay(500)
    }
    return ble.isSppConnected(targetId)
}

/** Waits, bounded, for an investigation to open on the planner's thread. */
private suspend fun verifyInvestigationOpened(context: OperationContext): VerificationOutcome {
    val intel = context.intel
        ?: return VerificationOutcome.NotVerified(
            "Can't check the investigation — the app isn't ready yet."
        )
    val deadline = System.currentTimeMillis() + 10000
    while (System.currentTimeMillis() < deadline) {
        val active = intel.activeInvestigation
        if (active != null) {
            return VerificationOutcome.Verified("Investigation open: \"${active.title}\".")
        }
        delay(500)
    }
    return VerificationOutcome.NotVerified(
        "The investigation didn't open — try again from the device screen."
    )
}

/** Registers every Area A operation. Idempotent; safe to call again. */
fun registerAreaAOperations() {
    // ---- The six function screens, preconfigured (A-06). ----
    // These overwrite Package 0's plain open-screen seeds (same ids).
    OperationRegistry.register(
        Operation(
            id = "screen.usb_uart",
            title = "Talk to a wired board",
            description = "Opens USB UART / ESP32 with the plugged-in board list already refreshed.",
            area = OperationArea.USB,
            execute = { context ->
                val usb = context.usb
                    ?: return@Operation OpResult(false, "USB isn't available right now.")
                usb.refreshDevices()
                context.navigate("usb_uart")
                val n = usb.usbDevices.size
                OpResult(
                    true,
                    if (n == 0) "No USB board plugged in — plug one in and the list refreshes."
                    else "Found $n USB device(s) — the list is already refreshed.",
                    "usb:$n"
                )
            },
            riskLevel = "READ",
            verified = false,
            route = "usb_uart"
        )
    )
    OperationRegistry.register(
        Operation(
            id = "screen.vesp32",
            title = "Use the practice board",
            description = "Opens VIRTUAL ESP32 and starts the practice board connection.",
            area = OperationArea.USB,
            execute = { context ->
                val client = context.vesp32
                    ?: return@Operation OpResult(false, "The practice board isn't available right now.")
                if (!client.isInstalled()) {
                    return@Operation OpResult(
                        false,
                        "The practice board app isn't installed on this phone — install VirtualESP32 to use it."
                    )
                }
                client.connect()
                context.navigate("vesp32")
                OpResult(true, "Practice board connection started.", "vesp32")
            },
            riskLevel = "READ",
            verified = false,
            route = "vesp32"
        )
    )
    OperationRegistry.register(
        Operation(
            id = "screen.network_tools",
            title = "Check what's on my Wi-Fi",
            description = "Opens NETWORK TOOLS with a Wi-Fi scan already running.",
            area = OperationArea.NETWORK,
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                net.scanMyNetwork()
                context.navigate("network_tools")
                OpResult(true, "Wi-Fi scan started — live devices appear as they're found.", "network_tools")
            },
            riskLevel = "READ",
            verified = false,
            route = "network_tools"
        )
    )
    OperationRegistry.register(
        Operation(
            id = "screen.nfc_tools",
            title = "Read or write a tap card",
            description = "Opens NFC TOOLS — the last card you tapped is already shown.",
            area = OperationArea.NFC,
            execute = { context ->
                val hadCard = context.ble?.lastNfcTag != null
                context.navigate("nfc_tools")
                OpResult(
                    true,
                    if (hadCard) "The last card you tapped is already shown."
                    else "No card tapped yet this session — hold one against the phone.",
                    "nfc_tools"
                )
            },
            riskLevel = "READ",
            verified = false,
            route = "nfc_tools"
        )
    )
    OperationRegistry.register(
        Operation(
            id = "screen.visual_intel",
            title = "Point the camera at something",
            description = "Opens VISUAL INTEL — the camera is ready to capture.",
            area = OperationArea.VISUAL,
            execute = { context ->
                context.navigate("visual_intel")
                OpResult(true, "Camera ready — point it at a board, chip, label, or device.", "visual_intel")
            },
            riskLevel = "READ",
            verified = false,
            route = "visual_intel"
        )
    )
    OperationRegistry.register(
        Operation(
            id = "screen.investigations",
            title = "Look into something",
            description = "Opens INVESTIGATIONS on the latest case, if there is one.",
            area = OperationArea.INVESTIGATIONS,
            execute = { context ->
                val intel = context.intel
                val latest = intel?.investigations?.maxByOrNull { it.createdMs }
                if (latest != null) intel?.openInvestigation(latest)
                context.navigate("investigations")
                OpResult(
                    true,
                    if (latest != null) "Opened the latest case: \"${latest.title}\"."
                    else "No cases yet — start one from a device, a photo, or a card.",
                    "investigations"
                )
            },
            riskLevel = "READ",
            verified = false,
            route = "investigations"
        )
    )

    // ---- Activity log (A-07): clock times + filters live on the screen. ----
    OperationRegistry.register(
        Operation(
            id = "screen.logs",
            title = "See what happened",
            description = "Opens the activity log — every entry with its clock time, filterable by device.",
            area = OperationArea.SYSTEM,
            execute = { context ->
                context.navigate("logs")
                OpResult(true, "Activity log open.", "logs")
            },
            riskLevel = "READ",
            verified = false,
            route = "logs"
        )
    )

    // ---- "Take me to what I was doing" (A-01). ----
    OperationRegistry.register(
        Operation(
            id = "nav.resume",
            title = "Go back to what I was doing",
            description = "Takes you back to the last device or case you had open.",
            area = OperationArea.SYSTEM,
            execute = { context ->
                val ble = context.ble
                val intel = context.intel
                val selected = ble?.selectedTarget()
                if (selected != null) {
                    context.navigate("target/${selected.id}")
                    return@Operation OpResult(
                        true,
                        "Back to ${selected.name.ifBlank { selected.id }}.",
                        selected.id
                    )
                }
                val buId = intel?.bringUpTargetId?.takeIf { it.isNotBlank() }
                if (buId != null && ble?.allTargets?.any { it.id == buId } == true) {
                    context.navigate("target/$buId")
                    return@Operation OpResult(true, "Back to the device you were setting up.", buId)
                }
                val active = intel?.activeInvestigation
                if (active != null) {
                    context.navigate("investigations")
                    return@Operation OpResult(
                        true,
                        "Back to the case you had open: \"${active.title}\".",
                        "investigations"
                    )
                }
                OpResult(
                    false,
                    "Nothing to go back to yet — pick something from 'What do you want to do?'"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Scan as an outcome (A-04, A-08, B-001). ----
    OperationRegistry.register(
        Operation(
            id = "scan.find-strongest",
            title = "Find the strongest device nearby",
            description = "Searches for about 10 seconds, then names the strongest device found.",
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
                val found = ble.allTargets
                if (found.isEmpty()) {
                    return@Operation OpResult(
                        false,
                        "No devices found — move closer to the device and try again."
                    )
                }
                val strongest = found.filter { it.rssi != null }.maxByOrNull { it.rssi!! }
                val newest = found.maxByOrNull { it.lastSeenMs }
                val pick: ScanTarget
                val label: String
                if (strongest != null) {
                    pick = strongest
                    label = "Strongest device found"
                } else {
                    pick = newest!!
                    label = "Newest device found"
                }
                AreaAState.highlightedTargetId = pick.id
                AreaAState.highlightedLabel = label
                OpResult(
                    true,
                    "Found ${found.size} device(s). $label: ${pick.name.ifBlank { pick.id }} (${signalWord(pick.rssi)}).",
                    pick.id
                )
            },
            verify = { context, result ->
                val ble = context.ble
                if (ble != null && ble.allTargets.any { it.id == result.data }) {
                    VerificationOutcome.Verified(result.summary)
                } else {
                    VerificationOutcome.NotVerified(
                        "That device isn't around any more — find devices again."
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "scan.bring-up-highlighted",
            title = "Bring up the strongest device found",
            description = "Connects to the device the last search highlighted and gets it ready to control.",
            area = OperationArea.BLE,
            prerequisites = listOf("Run 'Find the strongest device nearby' first"),
            preparation = listOf(
                PreparationStep("Checking what was found") { context ->
                    if (AreaAState.highlightedTargetId.isNullOrBlank()) {
                        PreparationOutcome.Failed(
                            "Nothing highlighted yet — run 'Find the strongest device nearby' first."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't connect — the app isn't ready yet.")
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't connect — the app isn't ready yet.")
                val id = AreaAState.highlightedTargetId
                    ?: return@Operation OpResult(false, "Nothing highlighted yet — find devices first.")
                val target = ble.allTargets.firstOrNull { it.id == id }
                    ?: return@Operation OpResult(
                        false,
                        "That device isn't around any more — find devices again."
                    )
                if (intel.bringUpState != BringUpState.IDLE && intel.bringUpTargetId != id) {
                    return@Operation OpResult(
                        false,
                        "Still getting another device ready — one at a time."
                    )
                }
                intel.bringUpTarget(
                    BringUpTarget(
                        kind = BringUpKind.BLE,
                        id = id,
                        title = target.name.ifBlank { id }
                    )
                )
                waitForBringUp(intel)
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Baseline, named (A-05). ----
    OperationRegistry.register(
        Operation(
            id = "baseline.compare",
            title = "Compare with the saved list",
            description = "Checks the devices around now against the saved list and names what is new, gone, or changed.",
            area = OperationArea.SYSTEM,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't compare — the app isn't ready yet.")
                val app = ble.getApplication<Application>()
                val snapshot = runCatching { BaselineStore(app).load() }.getOrNull()
                    ?: return@Operation OpResult(
                        false,
                        "No list saved yet — tap Save this list first."
                    )
                ble.compareWithBaseline()
                val diff = ble.baselineDiff
                val currentNames = ble.allTargets.associate { it.id to it.name.ifBlank { it.id } }
                fun names(ids: List<String>): String {
                    if (ids.isEmpty()) return "none"
                    return ids.joinToString(", ") { id ->
                        currentNames[id]
                            ?: snapshot.targets[id]?.name?.ifBlank { id }
                            ?: id
                    }
                }
                if (diff.newTargets.isEmpty() && diff.missingTargets.isEmpty() &&
                    diff.changedTargets.isEmpty() && diff.signalChangedTargets.isEmpty()
                ) {
                    return@Operation OpResult(true, "Nothing changed since the saved list.", "no-change")
                }
                OpResult(
                    true,
                    "New: ${names(diff.newTargets)}. Gone: ${names(diff.missingTargets)}. " +
                        "Changed: ${names(diff.changedTargets)}. " +
                        "Signal changed: ${names(diff.signalChangedTargets)}.",
                    "new=${diff.newTargets.size} gone=${diff.missingTargets.size}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Target moves (A-09 / A-23, A-13, A-14, A-16, A-18, A-20). ----
    val bluetoothFilter: (ScanTarget) -> Boolean =
        { it.type == TargetType.BLE || it.type == TargetType.BLUETOOTH_CLASSIC }
    OperationRegistry.register(
        Operation(
            id = "target.connect",
            title = "Connect to a device",
            description = "Connects to the device you pick and checks the link is really open.",
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
                if (target.type == TargetType.BLUETOOTH_CLASSIC) {
                    ble.connectSpp(target.id)
                    if (waitForSppConnected(ble, target.id)) {
                        OpResult(
                            true,
                            "Text link open to ${target.name.ifBlank { target.id }} — you can send text now.",
                            target.id
                        )
                    } else {
                        OpResult(
                            false,
                            ble.sppStatusFor(target.id).ifBlank { "Couldn't open the text link." }
                        )
                    }
                } else {
                    ble.connectBle(target.id)
                    if (waitForBleConnected(ble, target.id)) {
                        OpResult(
                            true,
                            "Connected to ${target.name.ifBlank { target.id }} — its controls are on its screen.",
                            target.id
                        )
                    } else {
                        OpResult(
                            false,
                            ble.gattStatusText(target.id).ifBlank { "Couldn't connect — the device didn't answer." }
                        )
                    }
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "ble.read-interesting",
            title = "Read the most interesting thing",
            description = "Reads the one part of the device that looks most useful, without reading everything.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    { it.type == TargetType.BLE && it.connected },
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't read — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.BLE && it.connected }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val summary = ble.deviceSummary(target.id)
                val channel = summary.mostInterestingChannel
                    ?: return@Operation OpResult(
                        false,
                        "Nothing stood out on this device yet — try Read everything on its screen."
                    )
                ble.bleRead(target.id, channel.first, channel.second)
                OpResult(
                    true,
                    "Asked ${summary.mostInteresting ?: "the most interesting part"} for its reading — the answer lands in the history.",
                    target.id
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "ble.pair-then-connect",
            title = "Pair, then connect",
            description = "Starts pairing with a device that asked for it. Your phone asks for the code — then tap Connect on the device screen.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device that needs pairing",
                    { it.type == TargetType.BLE },
                    "No Bluetooth devices found yet — tap Find devices first."
                ),
                PreparationStep("Checking it needs pairing") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val target = peekChosenTarget(context) { it.type == TargetType.BLE }
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Couldn't find that device any more."
                        )
                    if (ble.needsPairing(target.id)) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "This device doesn't need pairing — just tap Connect."
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't pair — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.BLE }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                ble.pairBle(target.id)
                OpResult(
                    true,
                    "Pairing started for ${target.name.ifBlank { target.id }} — your phone will ask for a code. Answer it, then tap Connect on the device screen.",
                    target.id
                )
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "ble.map-round",
            title = "Test what changes when I use the device",
            description = "Starts a guided test: do one thing on the device, then stop — Ghost Tap reports what changed.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected device",
                    { it.type == TargetType.BLE && it.connected },
                    "Nothing connected — connect to a device first."
                )
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't test — the app isn't ready yet.")
                val target = chosenTarget(context) { it.type == TargetType.BLE && it.connected }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                val status = ble.startMapRound(target.id)
                context.navigate("target/${target.id}")
                OpResult(
                    true,
                    "$status Now do one thing on the device, then tap Stop.",
                    target.id
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "target.open-details",
            title = "See the technical details",
            description = "Opens a device's screen with the technical detail panels already showing.",
            area = OperationArea.SYSTEM,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a device",
                    { true },
                    "No devices found yet — tap Find devices first."
                )
            ),
            execute = { context ->
                val target = chosenTarget(context) { true }
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                AreaAState.detailsRequestedFor = target.id
                context.navigate("target/${target.id}")
                OpResult(
                    true,
                    "Opened the technical details for ${target.name.ifBlank { target.id }}.",
                    target.id
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "spp.try-text",
            title = "Try talking to it in text",
            description = "Opens a text link to the device you pick and checks it really answers.",
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
                    ?: return@Operation OpResult(false, "Can't try — the app isn't ready yet.")
                val target = chosenTarget(context, bluetoothFilter)
                    ?: return@Operation OpResult(false, "Couldn't find that device any more.")
                ble.connectSpp(target.id)
                if (waitForSppConnected(ble, target.id)) {
                    OpResult(
                        true,
                        "The text link is open to ${target.name.ifBlank { target.id }} — you can send text now.",
                        target.id
                    )
                } else {
                    OpResult(
                        false,
                        ble.sppStatusFor(target.id).ifBlank {
                            "This device doesn't answer in text."
                        }
                    )
                }
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "print.find-language",
            title = "Find the printer's language",
            description = "Starts the printer language check on a connected printer — watch the paper and answer Yes or No.",
            area = OperationArea.BLE,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a connected printer",
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
                    else PreparationOutcome.Failed(
                        "No printer link open — connect with Try text link first."
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't start — the app isn't ready yet.")
                val target = chosenTarget(context) {
                    bluetoothFilter(it) && ble.isSppConnected(it.id)
                } ?: return@Operation OpResult(false, "Couldn't find that printer any more.")
                context.navigate("target/${target.id}")
                ble.startPrinterProbe(target.id)
                OpResult(
                    true,
                    "Trying printer languages — watch the paper and answer Yes or No on the device screen.",
                    target.id
                )
            },
            riskLevel = "CONFIGURE",
            verified = false
        )
    )

    // ---- Network tools (A-24). ----
    OperationRegistry.register(
        Operation(
            id = "net.scan-wifi",
            title = "Find devices on my Wi-Fi",
            description = "Finds every live device on your Wi-Fi and lists them.",
            area = OperationArea.NETWORK,
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Network tools aren't available right now.")
                if (net.isBusy) {
                    return@Operation OpResult(
                        false,
                        "Ghost Tap is busy with another check — wait a moment and try again."
                    )
                }
                net.scanMyNetwork()
                val deadline = System.currentTimeMillis() + 20000
                while (net.isScanningNetwork && System.currentTimeMillis() < deadline) {
                    delay(500)
                }
                val hosts = net.liveHosts
                if (hosts.isNotEmpty()) {
                    OpResult(
                        true,
                        "Found ${hosts.size} device(s) on your Wi-Fi: ${hosts.take(5).joinToString(", ")}.",
                        hosts.joinToString(",")
                    )
                } else {
                    OpResult(
                        false,
                        "Nothing answered on your Wi-Fi — check you're on Wi-Fi and try again."
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "net.talk-to-host",
            title = "Check a device on my Wi-Fi",
            description = "Checks which connection points answer on a device you found on your Wi-Fi.",
            area = OperationArea.NETWORK,
            preparation = listOf(
                PreparationStep("Checking what was found on your Wi-Fi") { context ->
                    val net = context.net
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    if (net.liveHosts.isEmpty()) {
                        return@PreparationStep PreparationOutcome.Failed(
                            "Find devices on your Wi-Fi first."
                        )
                    }
                    val picked = context.pendingChoice
                    if (picked != null && picked in net.liveHosts) {
                        return@PreparationStep PreparationOutcome.Ready
                    }
                    PreparationOutcome.NeedsUserChoice(
                        prompt = "Which device?",
                        choices = net.liveHosts
                    )
                }
            ),
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val host = context.pendingChoice?.takeIf { it in net.liveHosts }
                    ?: net.liveHosts.firstOrNull()
                    ?: return@Operation OpResult(false, "Nothing found on your Wi-Fi any more.")
                context.pendingChoice = null
                net.scanPortsForHost(host)
                val deadline = System.currentTimeMillis() + 15000
                while (net.openPortsByHost[host] == null && System.currentTimeMillis() < deadline) {
                    delay(500)
                }
                val ports = net.openPortsByHost[host]
                    ?: return@Operation OpResult(false, "The port check didn't finish — try again.")
                if (ports.isEmpty()) {
                    OpResult(
                        false,
                        "Nothing answered on the common connection points — nothing to control here."
                    )
                } else {
                    OpResult(
                        true,
                        "$host answered on: ${ports.joinToString(", ")}.",
                        host
                    )
                }
            },
            riskLevel = "READ",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "net.check-spotted-address",
            title = "Check the address from the photo",
            description = "Checks the address Visual Intel spotted in a photo — or the port you just tapped.",
            area = OperationArea.NETWORK,
            execute = { context ->
                val net = context.net
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                val before = net.log.size
                val result = when (val pre = consumePrefill(net)) {
                    is PrefillOutcome.Carried -> {
                        if (pre.port != null) net.tcpTest(pre.host, pre.port, "HELLO")
                        else net.ping(pre.host)
                        pre.host
                    }
                    is PrefillOutcome.NothingPending -> return@Operation OpResult(
                        false,
                        "Nothing spotted yet — point the camera at an address first, or tap a port you found on your Wi-Fi."
                    )
                }
                context.navigate("network_tools")
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    val fresh = net.log.drop(before)
                    // Skip the in-progress lines ("Checking $host…",
                    // "Connecting to $host:$port…") — only an outcome
                    // line counts, success or failure.
                    val line = fresh.firstOrNull {
                        result in it && !it.trimEnd().endsWith("…")
                    }
                    if (line != null) {
                        val good = "answered in" in line || "Connected to" in line || "Received" in line
                        return@Operation if (good) OpResult(true, line, result)
                        else OpResult(false, line)
                    }
                    delay(500)
                }
                OpResult(
                    false,
                    "No answer from $result yet — see the RESULTS log on the NETWORK TOOLS screen."
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- NFC (A-22, A-31). ----
    OperationRegistry.register(
        Operation(
            id = "nfc.try-next-command",
            title = "Try the next check on the card",
            description = "Sends the next quick check the card hasn't tried yet and shows what it answers.",
            area = OperationArea.NFC,
            preparation = listOf(
                chooseTargetStep(
                    "Pick a smart card",
                    { it.type == TargetType.NFC },
                    "No tap card found — hold a card against the phone first."
                ),
                PreparationStep("Checking it is a smart card") { context ->
                    val ble = context.ble
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Can't check — the app isn't ready yet."
                        )
                    val target = peekChosenTarget(context) { it.type == TargetType.NFC }
                        ?: return@PreparationStep PreparationOutcome.Failed(
                            "Couldn't find that card any more."
                        )
                    if (ble.nfcFactsFor(target.id)?.isIsoDep == true) PreparationOutcome.Ready
                    else PreparationOutcome.Failed(
                        "That card isn't a smart card — these checks only work on smart cards."
                    )
                }
            ),
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't try — the app isn't ready yet.")
                val target = chosenTarget(context) {
                    it.type == TargetType.NFC && ble.nfcFactsFor(it.id)?.isIsoDep == true
                } ?: return@Operation OpResult(false, "Couldn't find that card any more.")
                val history = ble.isoDepHistoryFor(target.id)
                val tried = history.map { it.commandHex.replace(" ", "").uppercase() }.toSet()
                val next = APDU_QUICK_CHECKS.firstOrNull {
                    it.second.replace(" ", "") !in tried
                } ?: return@Operation OpResult(
                    false,
                    "All three quick checks have been tried — the answers are in the card's history on its screen."
                )
                val answer = ble.isoDepTransceive(target.id, next.second)
                    ?: return@Operation OpResult(
                        false,
                        "No answer from the card — hold it steady against the phone and try again."
                    )
                OpResult(
                    true,
                    "Tried '${next.first}' — the card answered ${answer.statusHex} (${answer.statusMeaning}).",
                    answer.responseHex
                )
            },
            riskLevel = "WRITE",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "nfc.emulation-status",
            title = "Is the phone pretending to be a card?",
            description = "Says whether 'Pretend to be a tag' is on right now, and what it is broadcasting.",
            area = OperationArea.NFC,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't check — the app isn't ready yet.")
                OpResult(
                    true,
                    if (ble.isEmulating) "Pretending to be a card is ON — broadcasting: ${ble.emulatedSummary()}."
                    else "Pretending to be a card is OFF.",
                    "emulating=${ble.isEmulating}"
                )
            },
            riskLevel = "READ",
            verified = false
        )
    )

    // ---- Investigate handoffs (A-29) and visual fallback (A-28). ----
    OperationRegistry.register(
        Operation(
            id = "investigate.unknown",
            title = "Look into the unknown device",
            description = "Starts an investigation of the strongest device Ghost Tap couldn't name.",
            area = OperationArea.INVESTIGATIONS,
            execute = { context ->
                val ble = context.ble
                    ?: return@Operation OpResult(false, "Can't look — the app isn't ready yet.")
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't look — the app isn't ready yet.")
                val unknown = ble.allTargets
                    .filter {
                        it.likely.isNullOrBlank() || it.likely == "—" || it.confidence == "Low"
                    }
                    .maxByOrNull { it.rssi ?: Int.MIN_VALUE }
                    ?: return@Operation OpResult(
                        false,
                        "Everything found so far already has a name — nothing unknown to look into."
                    )
                val kind = when (unknown.type) {
                    TargetType.NFC -> "NFC"
                    TargetType.USB -> "USB"
                    else -> "BLE"
                }
                intel.investigateAndAutoStart(
                    IntelTarget(
                        kind = kind,
                        title = unknown.name.ifBlank { unknown.id },
                        text = targetEvidenceText(unknown)
                    )
                )
                context.navigate("investigations")
                OpResult(
                    true,
                    "Looking into '${unknown.name.ifBlank { unknown.id }}' — the plan is on the investigations screen.",
                    unknown.id
                )
            },
            verify = { context, result -> verifyInvestigationOpened(context) },
            riskLevel = "READ",
            verified = false
        )
    )
    OperationRegistry.register(
        Operation(
            id = "visual.investigate-photo",
            title = "Look into the last photo",
            description = "Starts an investigation of what the camera last found, when nothing it recognised matched a tool.",
            area = OperationArea.VISUAL,
            preparation = listOf(
                PreparationStep("Checking for a photo") { context ->
                    if (context.visual?.lastFinding == null) {
                        PreparationOutcome.Failed(
                            "Take a photo first — nothing to look into yet."
                        )
                    } else {
                        PreparationOutcome.Ready
                    }
                }
            ),
            execute = { context ->
                val visual = context.visual
                    ?: return@Operation OpResult(false, "Can't look — the app isn't ready yet.")
                val intel = context.intel
                    ?: return@Operation OpResult(false, "Can't look — the app isn't ready yet.")
                val finding = visual.lastFinding
                    ?: return@Operation OpResult(false, "Take a photo first — nothing to look into yet.")
                intel.newInvestigation(
                    IntelTarget(
                        kind = "VISUAL",
                        title = "Visual target",
                        text = finding.fullText,
                        markings = finding.markings,
                        barcodes = finding.barcodes.map { it.rawValue }
                    )
                )
                context.navigate("investigations")
                OpResult(
                    true,
                    "Looking into the photo — the plan is on the investigations screen.",
                    finding.id
                )
            },
            verify = { context, result -> verifyInvestigationOpened(context) },
            riskLevel = "READ",
            verified = false
        )
    )
}
