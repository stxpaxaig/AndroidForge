package com.ghosttap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ghosttap.GhostTapViewModel
import com.ghosttap.data.BaselineSnapshot
import com.ghosttap.data.BaselineStore
import com.ghosttap.data.LogEntry
import com.ghosttap.data.ScanTarget
import com.ghosttap.data.TargetType
import com.ghosttap.core.IntelEvent
import com.ghosttap.core.IntelEventBus
import com.ghosttap.core.BringUpKind
import com.ghosttap.core.BringUpState
import com.ghosttap.core.BringUpTarget
import com.ghosttap.core.IntentRouter
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.LiveTargetStore
import com.ghosttap.core.ops.OperationResult
import com.ghosttap.core.describeRssi
import com.ghosttap.core.prettyIdentityKey
import com.ghosttap.core.translateIdentityValue
import com.ghosttap.core.ChannelRanker
import com.ghosttap.core.SigUuids
import com.ghosttap.core.DeviceAction
import com.ghosttap.core.friendlyTechName
import com.ghosttap.core.toHexString
import com.ghosttap.core.gateForPanel
import com.ghosttap.core.deepReadChoice
import com.ghosttap.core.ultralightPageWarning
import com.ghosttap.core.classicTrailerBlock
import com.ghosttap.core.unknownCardNextStep
import com.ghosttap.core.nextApduCheck
import com.ghosttap.core.NAMED_NFC_COMMANDS
import com.ghosttap.core.NAMED_NFC_SCRIPTS
import com.ghosttap.core.buildNamedScript
import com.ghosttap.core.ops.AreaAState
import com.ghosttap.core.ops.AreaNfcState
import com.ghosttap.core.ops.OperationArea
import com.ghosttap.core.ops.OperationContext
import com.ghosttap.core.ops.OperationRegistry
import com.ghosttap.core.ops.OperationRunner
import com.ghosttap.core.ops.rebuildBringUpTarget
import com.ghosttap.core.ops.registerAreaAOperations
import com.ghosttap.core.ops.registerAreaBOperations
import com.ghosttap.core.ops.registerAreaHardwareOperations
import com.ghosttap.core.ops.registerAreaIntelOperations
import com.ghosttap.core.ops.registerAreaVnicOperations
import com.ghosttap.core.ops.registerNfcOperations
import com.ghosttap.core.ops.registerSeedOperations
import com.ghosttap.core.printer.ALL_LANGUAGES
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

internal val Bg = Color(0xFF050505)
private val Panel = Color(0xFF101010)
internal val Accent = Color(0xFF44FF99)
internal val Dim = Color(0xFF7AFFC4)

@Composable
fun GhostTapApp(
    viewModel: GhostTapViewModel,
    usbViewModel: com.ghosttap.UsbUartViewModel,
    netViewModel: com.ghosttap.NetworkToolsViewModel,
    visualIntelVm: com.ghosttap.VisualIntelViewModel,
    vesp32Client: com.ghosttap.core.esp32.VirtualEsp32Client,
    onNeedPermissions: () -> Unit,
    onSelectFirmware: () -> Unit,
    onPickImage: () -> Unit,
    intelVm: com.ghosttap.IntelViewModel
) {
    val nav = rememberNavController()
    // Package 0 — operation framework: one shared context + runner for
    // the "pick what you want to do" entry screen. Additive only: nothing
    // existing changes. Seeds register once; the runner is the single
    // execution path the entry screen uses.
    val opScope = rememberCoroutineScope()
    val operationContext = remember(viewModel, usbViewModel, netViewModel, visualIntelVm, intelVm) {
        OperationContext(
            ble = viewModel,
            usb = usbViewModel,
            net = netViewModel,
            visual = visualIntelVm,
            intel = intelVm,
            engine = intelVm.engine,
            vesp32 = vesp32Client,
            navigate = { route -> opScope.launch { nav.navigate(route) } },
            requestPermissions = onNeedPermissions,
            selectFirmware = onSelectFirmware,
            pickImage = onPickImage
        )
    }
    val operationRunner = remember(operationContext) { OperationRunner(operationContext) }
    LaunchedEffect(operationContext) {
        registerSeedOperations()
        // Package 2 — Area A operations overwrite the six seed screen
        // operations with preconfigured versions and add the rest.
        registerAreaAOperations()
        // Package 3 — Area B (scan / BLE / SPP / printer) operations.
        registerAreaBOperations()
        // Package 4 — Area NFC operations (B-027–B-044, D-32–D-48, A-25).
        registerNfcOperations()
        // Package 5 — USB / ESP32 / Virtual ESP32 / Network / Visual
        // operations (D-01–D-31, B-084–B-126, A-28, A-30).
        registerAreaHardwareOperations()
        // Package 6 — Core engines + Investigations operations
        // (C-01–C-23, B-063–B-083, E-38–E-45, D-31).
        registerAreaIntelOperations()
        // Package 7 — VNIC as a capability (E-01–E-33, E-34–E-37).
        registerAreaVnicOperations()
        OperationRegistry.publishToCapabilityRegistry(operationContext)
    }
    // Package 2 (A-06) — the six function buttons run their operations
    // through the shared runner (preconfiguration + navigation), never a
    // bare navigate. Home-triggered runs don't own the entry screen's
    // result panel, so the result is cleared when the run finishes.
    fun runScreenOp(id: String, fallbackRoute: String) {
        val op = OperationRegistry.get(id)
        if (op == null) {
            nav.navigate(fallbackRoute)
            return
        }
        opScope.launch {
            operationRunner.run(op)
            operationRunner.clearResult()
        }
    }
    // Package 6 (D-31) — the Investigations screen's no-input buttons run
    // their operations through the same runner (mirrors runScreenOp above):
    // the choice, when the button names one, is handed in as pendingChoice
    // so the preparation sees it; the result is cleared when the run
    // finishes, since the screen shows the outcome in its own status line.
    fun runIntelOp(id: String, choice: String?) {
        val op = OperationRegistry.get(id) ?: return
        opScope.launch {
            operationContext.pendingChoice = choice
            operationRunner.run(op)
            operationRunner.clearResult()
            operationContext.pendingChoice = null
        }
    }
    // MASTER PROMPT — run one command from a live target's COMMAND
    // CENTER. Same runner as runIntelOp, plus two things the command
    // center needs: the target is handed in as pendingChoice so a
    // command that asks "which device?" picks THIS target without
    // asking, and the outcome is reported to the IntelViewModel in
    // plain words so the card shows the answer (or the failure) under
    // the commands. The runner still runs preparation → execution →
    // verification; a command whose checks fail reports that honestly
    // instead of running.
    fun runLiveCommand(id: String, choice: String?) {
        val op = OperationRegistry.get(id) ?: return
        opScope.launch {
            operationContext.pendingChoice = choice
            val outcome = operationRunner.run(op)
            intelVm.noteCommandResult(
                op.title,
                when (outcome) {
                    is OperationResult.Done ->
                        outcome.reason + " " + outcome.evidence
                    is OperationResult.Failed ->
                        "It didn't work: " + outcome.reason +
                            if (outcome.evidence.isNotBlank()) " " + outcome.evidence else ""
                    is OperationResult.NeedsUserAction -> outcome.prompt
                    is OperationResult.NeedsUserChoice ->
                        outcome.reason + " — pick one from the choices on this screen."
                }.trim().take(600)
            )
            operationRunner.clearResult()
            operationContext.pendingChoice = null
        }
    }
    // ZERO-CONFIG — the intent box (2026-11-06): "what I'm tryna do"
    // in plain words. The words are matched against the REAL
    // registered operations only (IntentRouter — never an invented
    // id) and the match runs through the same OperationRunner as
    // every other command, with the outcome reported in plain words
    // on the screen the user is on. An intent that matches nothing
    // says so honestly instead of running something wrong. This is
    // intent ("do X"), not configuration — it never asks for a host,
    // port, address, or password.
    fun runIntent(text: String) {
        val caps = OperationRegistry.all().map { op ->
            IntentRouter.CapabilityRef(op.id, op.title, op.description, op.area.name)
        }
        val match = IntentRouter.resolve(text, caps)
        opScope.launch {
            if (match == null) {
                intelVm.noteCommandResult(
                    "You asked: \"$text\"",
                    "I couldn't match that to anything Ghost Tap can do — try different words, or tap one of the buttons on this screen."
                )
                return@launch
            }
            val op = OperationRegistry.get(match)
            if (op == null) {
                intelVm.noteCommandResult(
                    "You asked: \"$text\"",
                    "That matched something that isn't there any more — nothing ran."
                )
                return@launch
            }
            val outcome = operationRunner.run(op)
            intelVm.noteCommandResult(
                op.title,
                when (outcome) {
                    is OperationResult.Done ->
                        outcome.reason + " " + outcome.evidence
                    is OperationResult.Failed ->
                        "It didn't work: " + outcome.reason +
                            if (outcome.evidence.isNotBlank()) " " + outcome.evidence else ""
                    is OperationResult.NeedsUserAction -> outcome.prompt
                    is OperationResult.NeedsUserChoice ->
                        outcome.reason + " — pick one: " + outcome.choices.joinToString(", ")
                }.trim().take(600)
            )
            operationRunner.clearResult()
        }
    }
    // Package 7 (E-30 / F-07) — "ask the brain about this device" from
    // a device row: runs vnic.ask-about-device with the target's real
    // id, then lands on the investigations screen where the brain's
    // answer shows in its panel.
    fun runVnicAsk(target: ScanTarget) {
        val op = OperationRegistry.get("vnic.ask-about-device") ?: return
        opScope.launch {
            operationContext.pendingChoice = target.id
            operationRunner.run(op)
            operationRunner.clearResult()
            operationContext.pendingChoice = null
            nav.navigate("investigations")
        }
    }
    // Zero-tap bring-up: on READY the target's control screen becomes the
    // destination. launchSingleTop keeps an already-open screen from stacking.
    val bringUpDest = intelVm.bringUpDestination
    LaunchedEffect(bringUpDest) {
        if (bringUpDest != null) {
            try {
                nav.navigate(bringUpDest) { launchSingleTop = true }
            } catch (_: Exception) {
            }
            intelVm.consumeBringUpDestination()
        }
    }
    val tick = viewModel.uiTick
    val targets = remember(tick) { viewModel.allTargets }
    val logs = viewModel.logs
    val status = viewModel.scanStatus
    val btEnabled = viewModel.isBluetoothEnabled()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            Box(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {
                    // Package 2 (A-05) — saved baseline names come from the
                    // same store the ViewModel writes; re-read when it ticks.
                    val appContext = LocalContext.current.applicationContext
                    val baselineSnapshot = remember(tick) {
                        runCatching { BaselineStore(appContext).load() }.getOrNull()
                    }
                    HomeScreen(
                        targets = targets,
                        status = status,
                        btEnabled = btEnabled,
                        baselineText = formatBaselineDiff(viewModel, baselineSnapshot),
                        isScanning = viewModel.isScanning(),
                        highlightTargetId = AreaAState.highlightedTargetId,
                        highlightLabel = AreaAState.highlightedLabel,
                        isEmulating = viewModel.isEmulating,
                        onScan = {
                            onNeedPermissions()
                            viewModel.startScan()
                        },
                        onDiscoverBluetooth = {
                            onNeedPermissions()
                            viewModel.discoverBluetooth()
                        },
                        onStop = viewModel::stopScan,
                        onSaveBaseline = viewModel::saveBaseline,
                        onCompareBaseline = viewModel::compareWithBaseline,
                        onOpenTarget = { id ->
                            viewModel.selectTarget(id)
                            nav.navigate("target/$id")
                        },
                        onOpenLogs = { runScreenOp("screen.logs", "logs") },
                        onOpenUsbUart = { runScreenOp("screen.usb_uart", "usb_uart") },
                        onOpenVesp32 = { runScreenOp("screen.vesp32", "vesp32") },
                        onOpenNetworkTools = { runScreenOp("screen.network_tools", "network_tools") },
                        onOpenNfcTools = { runScreenOp("screen.nfc_tools", "nfc_tools") },
                        onOpenVisualIntel = { runScreenOp("screen.visual_intel", "visual_intel") },
                        onOpenInvestigations = { runScreenOp("screen.investigations", "investigations") },
                        intelVm = intelVm,
                        onRunLiveCommand = { id, choice -> runLiveCommand(id, choice) },
                        onOpenRoute = { route -> nav.navigate(route) },
                        onIntent = { text -> runIntent(text) },
                        intentAnswer = intelVm.lastCommandAnswer,
                        onConnect = viewModel::connectBle,
                        // ZERO-CONFIG FIX (2026-11-06): a disconnect
                        // tapped on the home row is the user's call —
                        // remember it so opening the device's page
                        // later never auto-connects it again.
                        onDisconnect = { id -> intelVm.noteManualDisconnect(id); viewModel.disconnectBle(id) },
                        // Package 5 (A-30) — recent firmware shortcut.
                        recentFirmware = usbViewModel.recentFirmware,
                        onOpenRecentFirmware = {
                            if (usbViewModel.reopenRecentFirmware()) nav.navigate("usb_uart")
                        }
                    )
                }

                composable(
                    route = "target/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.StringType })
                ) {
                    val selected = viewModel.selectedTarget()
                    if (selected != null) {
                        TargetScreen(
                            target = selected,
                            viewModel = viewModel,
                            onBack = { nav.popBackStack() },
                            intelVm = intelVm,
                            onIntent = { text -> runIntent(text) },
                            intentAnswer = intelVm.lastCommandAnswer,
                            // Package 7 (E-30 / F-07) — ask the brain about
                            // this device, from the device row itself.
                            onAskBrain = { t -> runVnicAsk(t) },
                            onInvestigate = { t ->
                                val kind = when (t.type) {
                                    TargetType.NFC -> "NFC"
                                    TargetType.USB -> "USB"
                                    else -> "BLE"
                                }
                                intelVm.investigateAndAutoStart(
                                    com.ghosttap.core.IntelTarget(
                                        kind = kind,
                                        title = t.name.ifBlank { t.id },
                                        text = buildTargetText(t)
                                    )
                                )
                                nav.navigate("investigations")
                            }
                        )
                    } else {
                        // Package 2 (A-01) — a target that disappeared
                        // mid-navigation used to dead-end here with only a
                        // Back button. Name it, and offer a fresh search.
                        val missingId = it.arguments?.getString("id") ?: ""
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Text("Device not found", color = Accent)
                            if (missingId.isNotBlank()) {
                                Text(
                                    "Couldn't find: $missingId",
                                    color = Dim, fontFamily = FontFamily.Monospace
                                )
                            }
                            Text(
                                "It may have moved out of range or been forgotten. Find devices again to look for it.",
                                color = Dim, fontFamily = FontFamily.Monospace
                            )
                            Button(onClick = { nav.popBackStack() }) { Text("Back") }
                            TerminalButton("Find devices again") {
                                onNeedPermissions()
                                viewModel.startScan()
                                nav.popBackStack()
                            }
                        }
                    }
                }

                composable("logs") {
                    val list = logs.value
                    LogsScreen(
                        entries = list,
                        targets = targets,
                        onBack = { nav.popBackStack() },
                        onClear = viewModel::clearLogs,
                        onExport = viewModel::exportLogsToFile
                    )
                }

                composable("usb_uart") {
                    UsbUartScreen(
                        viewModel = usbViewModel,
                        intelVm = intelVm,
                        onRunLiveCommand = { id, choice -> runLiveCommand(id, choice) },
                        onOpenRoute = { route -> nav.navigate(route) },
                        onBack = { nav.popBackStack() },
                        onSelectFirmware = onSelectFirmware,
                        onOpenWhatToDo = { nav.navigate("what_to_do/USB") },
                        onIntent = { text -> runIntent(text) },
                        intentAnswer = intelVm.lastCommandAnswer
                    )
                }

                composable("vesp32") {
                    VirtualEsp32Screen(
                        client = vesp32Client,
                        intelVm = intelVm,
                        onRunLiveCommand = { id, choice -> runLiveCommand(id, choice) },
                        onOpenRoute = { route -> nav.navigate(route) },
                        onBack = { nav.popBackStack() },
                        onOpenWhatToDo = { nav.navigate("what_to_do/USB") },
                        onIntent = { text -> runIntent(text) },
                        intentAnswer = intelVm.lastCommandAnswer
                    )
                }

                composable("network_tools") {
                    NetworkToolsScreen(
                        viewModel = netViewModel,
                        intelVm = intelVm,
                        onRunLiveCommand = { id, choice -> runLiveCommand(id, choice) },
                        onOpenRoute = { route -> nav.navigate(route) },
                        // DISCOVER (Network): Wi-Fi join/settings live on
                        // the Visual Intel ViewModel — passed through so a
                        // found network's Connect button can join it.
                        visualVm = visualIntelVm,
                        onBack = { nav.popBackStack() },
                        onOpenWhatToDo = { nav.navigate("what_to_do/NETWORK") },
                        onIntent = { text -> runIntent(text) },
                        intentAnswer = intelVm.lastCommandAnswer,
                        onInvestigateHost = { host ->
                            intelVm.investigateAndAutoStart(
                                com.ghosttap.core.IntelTarget(
                                    kind = "NETWORK",
                                    title = host,
                                    text = "Network device $host found by Find devices on my Wi-Fi on the local network."
                                )
                            )
                            nav.navigate("investigations")
                        }
                    )
                }

                composable("nfc_tools") {
                    NfcToolsScreen(
                        viewModel = viewModel,
                        intelVm = intelVm,
                        onRunLiveCommand = { id, choice -> runLiveCommand(id, choice) },
                        onOpenRoute = { route -> nav.navigate(route) },
                        onBack = { nav.popBackStack() },
                        onOpenWhatToDo = { nav.navigate("what_to_do/NFC") },
                        onIntent = { text -> runIntent(text) },
                        intentAnswer = intelVm.lastCommandAnswer,
                        onInvestigate = {
                            intelVm.investigateAndAutoStart(
                                com.ghosttap.core.IntelTarget(
                                    kind = "NFC",
                                    title = "NFC tag",
                                    text = viewModel.lastNfcReadSummary.ifBlank {
                                        "Tap card from NFC TOOLS — nothing read yet; tap a card first."
                                    }
                                )
                            )
                            nav.navigate("investigations")
                        }
                    )
                }

                composable("visual_intel") {
                    VisualIntelScreen(
                        viewModel = visualIntelVm,
                        mainViewModel = viewModel,
                        netViewModel = netViewModel,
                        onBack = { nav.popBackStack() },
                        onNeedPermissions = onNeedPermissions,
                        onPickImage = onPickImage,
                        onNavigate = { nav.navigate(it) },
                        intelVm = intelVm,
                        onRunLiveCommand = { id, choice -> runLiveCommand(id, choice) },
                        onOpenRoute = { route -> nav.navigate(route) },
                        onInvestigate = { f ->
                            intelVm.newInvestigation(
                                com.ghosttap.core.IntelTarget(
                                    kind = "VISUAL",
                                    title = "Visual target",
                                    text = f.fullText,
                                    markings = f.markings,
                                    barcodes = f.barcodes.map { it.rawValue }
                                )
                            )
                            nav.navigate("investigations")
                        },
                        onOpenWhatToDo = { nav.navigate("what_to_do/VISUAL") },
                        onIntent = { text -> runIntent(text) },
                        intentAnswer = intelVm.lastCommandAnswer
                    )
                }

                composable("investigations") {
                    InvestigationsScreen(
                        vm = intelVm,
                        // DISCOVER (Investigations): the ViewModels the
                        // "Investigate the unfamiliar" button can reach.
                        // Pictures, the practice board, and tap cards
                        // are NOT reachable from here — see the button.
                        bleVm = viewModel,
                        usbVm = usbViewModel,
                        netVm = netViewModel,
                        onBack = { nav.popBackStack() },
                        onRunOperation = { id, choice -> runIntelOp(id, choice) },
                        onRunLiveCommand = { id, choice -> runLiveCommand(id, choice) },
                        onOpenRoute = { route -> nav.navigate(route) },
                        onOpenWhatToDo = { nav.navigate("what_to_do/INVESTIGATIONS") },
                        onIntent = { text -> runIntent(text) },
                        intentAnswer = intelVm.lastCommandAnswer
                    )
                }

                // ZERO-CONFIG (2026-11-06): the standalone global picker
                // is REMOVED — there is no "what_to_do" route any more.
                // Its content lives ONLY inside each function, at the
                // per-area route below (what_to_do/{area}), already
                // configured for that section's devices. This explicitly
                // reverses the earlier "home global button untouched"
                // rule, per the user's corrected directive.
                //
                // Per-section picker: one area only, so the list stays short.
                // An unknown area name has nowhere to go — the global
                // list it used to fall back to is removed — so it goes
                // back instead of showing a list that no longer exists.
                composable(
                    "what_to_do/{area}",
                    arguments = listOf(navArgument("area") { type = NavType.StringType })
                ) { backStackEntry ->
                    val area = backStackEntry.arguments?.getString("area")?.let { name ->
                        runCatching { OperationArea.valueOf(name) }.getOrNull()
                    }
                    if (area == null) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        OperationEntryScreen(
                            runner = operationRunner,
                            onBack = { nav.popBackStack() },
                            area = area
                        )
                    }
                }
            }
            // Zero-tap bring-up status banner — plain words, dismissible.
            // The intelligence works the bring-up; the user lands on control.
            // The READY headline is a LIVE check, never a stale one-shot: if
            // the link dropped after bring-up, the banner says so.
            val buState = intelVm.bringUpState
            if (buState != BringUpState.IDLE) {
                val tick = viewModel.uiTick // read to recompose when connection state changes
                @Suppress("UNUSED_VARIABLE")
                val _tick = tick
                val buStatus = intelVm.bringUpStatus
                val headline = when (buState) {
                    BringUpState.CONNECTING -> "Getting it ready…"
                    BringUpState.ENUMERATING -> "Finding what it can do…"
                    BringUpState.READY ->
                        if (intelVm.bringUpStillReady()) "Ready — you're in control."
                        else "The connection dropped."
                    BringUpState.FAILED -> "Couldn't get it ready."
                    else -> ""
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xFF0B1F14).copy(alpha = 0.96f))
                        .padding(12.dp)
                ) {
                    Text(
                        headline,
                        color = Accent, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (buStatus.isNotBlank() && buStatus != headline &&
                        !(buState == BringUpState.READY && buStatus == "Ready — you're in control.")
                    ) {
                        Text(
                            if (buState == BringUpState.READY && !intelVm.bringUpStillReady())
                                "Tap the device again to retry."
                            else buStatus,
                            color = Dim, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (buState == BringUpState.READY || buState == BringUpState.FAILED) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { intelVm.dismissBringUp() }) {
                                Text("Got it", color = Accent, fontFamily = FontFamily.Monospace)
                            }
                            // Package 2 (A-02) — one-tap retry for the same
                            // target, through the same bring-up path.
                            if (buState == BringUpState.FAILED ||
                                (buState == BringUpState.READY && !intelVm.bringUpStillReady())
                            ) {
                                TextButton(onClick = {
                                    rebuildBringUpTarget(intelVm, viewModel)?.let {
                                        intelVm.bringUpTarget(it)
                                    }
                                }) {
                                    Text("Try again", color = Accent, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    targets: List<ScanTarget>,
    status: String,
    btEnabled: Boolean,
    baselineText: String,
    isScanning: Boolean,
    highlightTargetId: String? = null,
    highlightLabel: String = "",
    isEmulating: Boolean = false,
    onScan: () -> Unit,
    // DISCOVER (Home / Bluetooth): starts a fresh scan and checks
    // EVERYTHING it finds, including devices already known.
    onDiscoverBluetooth: () -> Unit = {},
    onStop: () -> Unit,
    onSaveBaseline: () -> Unit,
    onCompareBaseline: () -> Unit,
    onOpenTarget: (String) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenUsbUart: () -> Unit,
    onOpenVesp32: () -> Unit,
    onOpenNetworkTools: () -> Unit,
    onOpenNfcTools: () -> Unit,
    onOpenVisualIntel: () -> Unit,
    onOpenInvestigations: () -> Unit,
    // MASTER PROMPT: the live-target card needs the thinking engine
    // (for AI reasoning), the live-command runner, and navigation to
    // a target's own page. Null = standalone use: the card still
    // renders, with the app's own rules instead of the AI.
    intelVm: com.ghosttap.IntelViewModel? = null,
    onRunLiveCommand: ((String, String?) -> Unit)? = null,
    onOpenRoute: ((String) -> Unit)? = null,
    // ZERO-CONFIG — the intent box ("what I'm tryna do"), on the home
    // screen as on every function screen. Null = no intent box.
    onIntent: ((String) -> Unit)? = null,
    intentAnswer: String = "",
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    // Package 5 (A-30) — the last software file picked, offered as one
    // tap to load it again. Null when no file was ever picked: then
    // nothing shows, and nothing is invented.
    recentFirmware: com.ghosttap.data.RecentFirmware? = null,
    onOpenRecentFirmware: () -> Unit = {}
) {
    // ZERO-CONFIG FIX (2026-11-06): the intent answer line is shared
    // across screens — clear it when this screen opens so a stale
    // outcome from another screen never shows here.
    LaunchedEffect(Unit) { intelVm?.clearCommandAnswer() }
    // Package 2 (A-01) — find one device by name instead of scrolling the list.
    var targetFilter by remember { mutableStateOf("") }
    val shownTargets = if (targetFilter.isBlank()) targets else targets.filter {
        it.name.contains(targetFilter, ignoreCase = true) ||
            (it.likely?.contains(targetFilter, ignoreCase = true) == true)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .background(Bg),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("GHOST TAP", color = Accent, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)

        ExplainerCard(
            "This screen listens for nearby Bluetooth gadgets.",
            "Tap Find devices, wait about 10 seconds, then tap a device to see what it might be.",
            "Connect tries to talk to it — phones, earbuds and beacons usually refuse. That's the device saying no, not the app failing. If it hangs, it gives up after 15 seconds and tells you."
        )

        Text("STATUS: $status", color = if (btEnabled) Dim else Color.Red, fontFamily = FontFamily.Monospace)

        // Package 2 (A-30) — a denied permission used to surface only as a
        // status line, with no reason why and no way back. Say why each
        // permission has a job, and offer to ask again.
        if (status.startsWith("Permission denied")) {
            PanelBox("PERMISSIONS NEEDED") {
                Text(
                    "Ghost Tap needs a few permissions to work — each one has a job:",
                    color = Dim, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Text("· Bluetooth — to find and talk to devices.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("· Location — Android needs it to find Bluetooth devices.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("· Camera — to read boards, chips, and labels.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("· Microphone — to listen for device sounds.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(6.dp))
                TerminalButton("Ask again", onScan)
            }
        }

        // Top bubble buttons — wrap to the next line, never run off-screen.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton(if (isScanning) "Finding devices…" else "Find devices", onScan)
            // DISCOVER (Home / Bluetooth): one tap finds devices AND
            // checks every one found, including ones already known.
            // Findings land on the card below and the Investigations
            // timeline; nothing connects until you tap a device.
            TerminalButton("Investigate the unfamiliar", onDiscoverBluetooth)
            TerminalButton("Stop finding", onStop)
            TerminalButton("Activity log", onOpenLogs)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("USB UART / ESP32", onOpenUsbUart)
            TerminalButton("VIRTUAL ESP32", onOpenVesp32)
            TerminalButton("NETWORK TOOLS", onOpenNetworkTools)
            TerminalButton("NFC TOOLS", onOpenNfcTools)
            TerminalButton("VISUAL INTEL", onOpenVisualIntel)
            TerminalButton("INVESTIGATIONS", onOpenInvestigations)
        }

        // ZERO-CONFIG — the intent box lives here, on the home screen,
        // and on every function screen. The standalone "What do you
        // want to do?" list is gone; this box + the per-function
        // pickers are what replace it.
        IntentBox(onIntent = onIntent, answer = intentAnswer)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("Save this list", onSaveBaseline)
            TerminalButton("Compare with saved list", onCompareBaseline)
        }

        // Plain-English: what each button does.
        Text(
            "Find devices finds Bluetooth devices nearby. USB UART / ESP32 talks to a wired board. VIRTUAL ESP32 runs pretend hardware with no board needed. NETWORK TOOLS tests Wi-Fi connections. NFC TOOLS reads, writes, copies, and pretends to be tap cards. VISUAL INTEL points the camera at boards, chips, labels, and devices to read text and scan barcodes. INVESTIGATIONS turns what the camera saw into a planned, step-by-step check using Ghost Tap's real tools.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )

        PanelBox(title = "BASELINE") {
            Text(baselineText, color = Dim, fontFamily = FontFamily.Monospace)
        }

        // Package 5 (A-30) — cross-session shortcut: the last software
        // file picked, one tap to load it onto the board again. Only
        // shown when a file really was picked before.
        recentFirmware?.let { recent ->
            PanelBox(title = "LAST SOFTWARE FILE") {
                Text(
                    "Last file: ${recent.name} — picked " +
                        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
                            .format(java.util.Date(recent.pickedMs)) +
                        ". Tap to load it onto the board again.",
                    color = Dim, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                TerminalButton("LOAD THIS FILE AGAIN", onOpenRecentFirmware)
            }
        }

        OutlinedTextField(
            value = targetFilter,
            onValueChange = { targetFilter = it },
            label = { Text("Find a device by name") },
            modifier = Modifier.fillMaxWidth()
        )

        PanelBox(title = "TARGETS (${targets.size})") {
            if (targets.isEmpty()) {
                Text(
                    "No devices found yet. Tap Find devices above to search.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
            } else if (shownTargets.isEmpty()) {
                Text(
                    "Nothing matches \"$targetFilter\".",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    "Tap a device to see what you can do with it.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
                if (targetFilter.isNotBlank()) {
                    Text(
                        "Showing ${shownTargets.size} of ${targets.size}.",
                        color = Dim,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    shownTargets.forEach { target ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Panel, RoundedCornerShape(6.dp))
                                .clickable { onOpenTarget(target.id) }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    target.name,
                                    color = Accent,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (target.connected) "Connected" else "Not connected",
                                    color = if (target.connected) Accent else Color.Gray,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                            Text(
                                plainDeviceType(target.type),
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            val likely = target.likely
                            if (!likely.isNullOrBlank() && likely != "—") {
                                Text(
                                    "Probably: $likely",
                                    color = Dim,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                plainSignal(target.rssi),
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                            // Package 2 (A-04/A-08) — the device the last
                            // "find the strongest" search highlighted.
                            if (target.id == highlightTargetId && highlightLabel.isNotBlank()) {
                                Text(
                                    "★ $highlightLabel",
                                    color = Accent,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (target.connected) {
                                    TerminalButton("Disconnect") { onDisconnect(target.id) }
                                } else if (target.type == TargetType.BLE || target.type == TargetType.BLUETOOTH_CLASSIC) {
                                    TerminalButton("Connect") { onConnect(target.id) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // MASTER PROMPT — LIVE TARGET, not history: the newest live
        // Bluetooth target, with what the thinking engine says about
        // it. CONNECT runs the existing bring-up (connect → check it
        // answers → land on its page) instead of a bare connect; the
        // device rows above keep their own Connect buttons untouched.
        // The command center shows once the bring-up reports READY.
        val bleLive = LiveTargetStore.newestFor(InvestigationSource.BLE)
        val bleLiveTarget = bleLive?.report?.targetId?.let { id ->
            targets.firstOrNull { it.id == id }
        }
        if (bleLive != null && bleLiveTarget != null && intelVm != null) {
            val bleMac = bleLive.report.targetId?.removePrefix("ble:").orEmpty()
            LiveTargetCard(
                target = bleLive,
                intelVm = intelVm,
                // HOLD THE CONNECTION: the card shows commands only
                // while the link is actually up right now — a bring-up
                // that said READY and then dropped must not keep
                // offering commands on a dead link.
                isConnected = intelVm.bringUpState == BringUpState.READY &&
                    intelVm.bringUpTargetId == bleMac &&
                    intelVm.bringUpStillReady(),
                onConnect = {
                    intelVm.bringUpTarget(
                        BringUpTarget(
                            kind = BringUpKind.BLE,
                            id = bleMac,
                            title = bleLiveTarget.name.ifBlank { bleMac }
                        )
                    )
                },
                onControl = { onOpenTarget(bleLiveTarget.id) },
                onRunCommand = onRunLiveCommand,
                onOpenRoute = onOpenRoute,
                commandAnswer = intelVm.lastCommandAnswer
            )
        }

        // NFC note — the NFC TOOLS screen does the work; taps also land here automatically.
        Text(
            "NFC TOOLS: tap NFC TOOLS above to read, write, copy, or pretend to be a card. Tapped cards also appear in TARGETS automatically.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        // Package 2 (A-31) — whether "pretend to be a tag" is on, without
        // opening NFC TOOLS card 7 to check.
        if (isEmulating) {
            Text(
                "Pretending to be a card is ON — tap NFC TOOLS to stop it.",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Honest hardware notes — small, not full sections.
        Text(
            "Infrared remote: this phone has no infrared sender, so it can't send or receive infrared signals. Long-range radio: this phone has no long-range radio — an extra plug-in module would be needed.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * Wi-Fi & Network Tools screen — real TCP/UDP/HTTP/ping/port-scan.
 * Every tool explains what it does, what success looks like, and
 * what a failure means. No fake results.
 */
@Composable
private fun NetworkToolsScreen(
    viewModel: com.ghosttap.NetworkToolsViewModel,
    visualVm: com.ghosttap.VisualIntelViewModel? = null,
    onBack: () -> Unit,
    onOpenWhatToDo: (() -> Unit)? = null,
    onInvestigateHost: (String) -> Unit = {},
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
    // ZERO-CONFIG FIX (2026-11-06): the intent answer line is shared
    // across screens — clear it when this screen opens so a stale
    // outcome from another screen never shows here.
    LaunchedEffect(Unit) { intelVm?.clearCommandAnswer() }
    // Each tool has its own inputs right above its own button.
    // No shared TARGET panel — what you type is what that button uses.
    var pingHost by remember { mutableStateOf("") }
    var tcpHost by remember { mutableStateOf("") }
    var tcpPort by remember { mutableStateOf("80") }
    var tcpMessage by remember { mutableStateOf("HELLO") }
    var udpHost by remember { mutableStateOf("") }
    var udpPort by remember { mutableStateOf("80") }
    var udpMessage by remember { mutableStateOf("HELLO") }
    var scanHost by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    // Visual Intel can hand us an IP it spotted in a photo — prefill PING with it.
    LaunchedEffect(Unit) {
        viewModel.consumePingPrefill()?.let { pingHost = it }
    }
    // Tapping an open port from SCAN MY NETWORK fills the TCP box below.
    viewModel.tcpPrefill?.let { pre ->
        LaunchedEffect(pre) {
            tcpHost = pre.first
            tcpPort = pre.second.toString()
            viewModel.consumeTcpPrefill()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExplainerCard(
            "Talk to things on your network or the internet.",
            "Type an address into a tool's box, tap the tool — or tap Find devices on my Wi-Fi to find every live device on your Wi-Fi with zero typing.",
            "A timeout means no answer — never a success. Nothing here changes the target."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("Back", onBack)
            TerminalButton("Clear results") { viewModel.clearLog() }
        }
        onOpenWhatToDo?.let { TerminalButton("What do you want to do?", it) }
        // DISCOVER (Network): one tap finds networks and devices — the
        // Wi-Fi networks the phone can see, every live device on your
        // Wi-Fi, the services talking on it, and (while Ghost Tap's own
        // hotspot is on) who joined it. No typing, no setup. Findings
        // list below; nothing joins or connects until you tap one.
        TerminalButton("Investigate the unfamiliar") { viewModel.discoverNetwork() }
        viewModel.wifiDiscoverNote?.let { note ->
            Text(
                note,
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // ZERO-CONFIG — the intent box: type what you're trying to do
        // and Ghost Tap does it. The Ping / TCP / UDP / Port scan /
        // HTTP boxes below stay as manual fallbacks for a device
        // discovery never saw — they are NOT the way to connect to a
        // found device; tapping a found device does that by itself.
        IntentBox(onIntent = onIntent, answer = intentAnswer)
        Text("NETWORK TOOLS", color = Accent, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
        Text(
            "Test real network connections from this phone. Only use these on devices and networks you own or are authorized to test.",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )

        // ---- Scan my network: zero typing, finds live devices itself ----
        PanelBox("SCAN MY NETWORK") {
            Text(
                "Finds every live device on your Wi-Fi by itself — no typing.",
                color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (viewModel.isScanningNetwork) {
                TerminalButton("Finding devices…", { })
            } else {
                TerminalButton("Find devices on my Wi-Fi") { viewModel.scanMyNetwork() }
            }
        }

        // ---- Live devices: tap one to connect and see its open ports ----
        if (viewModel.liveHosts.isNotEmpty()) {
            PanelBox("LIVE DEVICES (${viewModel.liveHosts.size}) — TAP ONE TO CONNECT") {
                viewModel.liveHosts.forEach { host ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // ZERO-CONFIG — SELECT → AUTO-CONNECT: tapping a
                        // found device connects it by itself — address
                        // and port come from discovery, nothing typed.
                        TerminalButton(host) { viewModel.connectDiscoveredHost(host) }
                        TerminalButton("Investigate") { onInvestigateHost(host) }
                    }
                    Text(
                        "Tap to connect — Ghost Tap checks its common connection points, fills in the address and port by itself, and talks to it right away. Investigate hands the device to Ghost Tap's investigator.",
                        color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
                    )
                    viewModel.openPortsByHost[host]?.let { ports ->
                        if (ports.isEmpty()) {
                            Text(
                                "Nothing answered on the common connection points — nothing to control here.",
                                color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                "OPEN PORTS on $host:",
                                color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
                            )
                            ports.forEach { port ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (port == 80 || port == 443 || port == 8080) {
                                        TerminalButton("$host:$port") {
                                            val scheme = if (port == 443) "https" else "http"
                                            viewModel.httpGet("$scheme://$host:$port")
                                        }
                                        Text(
                                            "  Tap it: reads the page it serves.",
                                            color = Dim, fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        // Package 2 (A-24) — a port tap now
                                        // talks to it right away with HELLO
                                        // instead of only filling the box
                                        // below for a second tap.
                                        TerminalButton("$host:$port") {
                                            viewModel.prefillTcp(host, port)
                                            viewModel.tcpTest(host, port, "HELLO")
                                        }
                                        Text(
                                            "  Tap it: talks to it right away with HELLO — the box below shows what was sent.",
                                            color = Dim, fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // MASTER PROMPT — LIVE TARGETS, not history: every network
        // finding, newest first — a re-check refreshes a device's
        // report in place, so the list never stacks duplicates. The
        // connect paths are Ghost Tap's own:
        // - a live device on your Wi-Fi (net:<address>): Connect checks
        //   its common connection points, the existing path.
        // - a Wi-Fi network the phone saw (wifi:<network>): Connect
        //   joins Ghost Tap to that one network on its own terms — an
        //   open network straight away, a secured one with the password
        //   typed right here in Ghost Tap. Nothing joins until you tap.
        // New on the card: what the thinking engine says, and — once
        // a host has answered — the commands you can tap on it.
        val networkLive = remember(viewModel.latestNetworkReport, viewModel.wifiNetworks) {
            LiveTargetStore.all().filter { it.source == InvestigationSource.NETWORK }
        }
        networkLive.forEach { live ->
            val report = live.report
            val wifiId = report.targetId?.takeIf { it.startsWith("wifi:") }
            if (wifiId != null) {
                val security = viewModel.wifiSecurityById[wifiId]
                val ssid = report.targetLabel
                // Join directly only for an open network with a real
                // name. Anything else (needs a password, name unknown,
                // or security not known from the last scan) asks for
                // the password right here in Ghost Tap.
                val joinDirectly = security == "OPEN" &&
                    ssid.isNotBlank() && ssid != "Unknown device"
                if (!joinDirectly) {
                    Text(
                        "This one needs its password — tap Connect and type it right here.",
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                LiveTargetCard(
                    target = live,
                    intelVm = intelVm,
                    // HOLD THE CONNECTION: commands show once Ghost Tap
                    // is actually on this network — connectedWifiSsid is
                    // set the moment the network answers and cleared the
                    // moment it drops, so a dropped network never keeps
                    // offering commands.
                    isConnected = visualVm?.connectedWifiSsid == ssid,
                    onConnect = {
                        if (joinDirectly) {
                            visualVm?.joinWifi(ssid, null, "")
                            viewModel.noteWifiJoinAttempt(ssid, open = true)
                        } else {
                            viewModel.askWifiPassword(ssid)
                            viewModel.noteWifiJoinAttempt(ssid, open = false)
                        }
                    },
                    onControl = { viewModel.scanWifiNetworks() },
                    onRunCommand = onRunLiveCommand,
                    onOpenRoute = onOpenRoute,
                    commandAnswer = intelVm?.lastCommandAnswer.orEmpty()
                )
                if (viewModel.wifiPasswordPromptSsid == ssid) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = viewModel.wifiPasswordText,
                        onValueChange = { viewModel.enterWifiPasswordText(it) },
                        label = { Text("Password for '$ssid'") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    viewModel.wifiPasswordNote?.let { note ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            note,
                            color = Dim,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TerminalButton("Connect to this network") {
                        if (viewModel.wifiPasswordText.isBlank()) {
                            viewModel.showWifiPasswordNote("Type the password first.")
                        } else {
                            visualVm?.joinWifi(
                                ssid,
                                viewModel.wifiPasswordText,
                                security ?: ""
                            )
                            viewModel.cancelWifiPassword()
                        }
                    }
                    TerminalButton("Never mind") { viewModel.cancelWifiPassword() }
                }
            } else {
                val host = report.targetId?.removePrefix("net:") ?: report.targetLabel
                LiveTargetCard(
                    target = live,
                    intelVm = intelVm,
                    // HOLD THE CONNECTION: for a device on the Wi-Fi
                    // network, the held connection IS the network
                    // itself — each command opens its own check over it
                    // when tapped (the card says so in plain words),
                    // so commands are there from the start and nothing
                    // pretends a per-device link is held.
                    isConnected = true,
                    // ZERO-CONFIG — tapping a found device connects it
                    // by itself (address + port from discovery).
                    onConnect = { viewModel.connectDiscoveredHost(host) },
                    onControl = { viewModel.connectDiscoveredHost(host) },
                    onRunCommand = onRunLiveCommand,
                    onOpenRoute = onOpenRoute,
                    commandAnswer = intelVm?.lastCommandAnswer.orEmpty()
                )
            }
        }

        // ---- What's talking: mDNS/NSD service discovery, zero typing ----
        PanelBox("WHAT'S TALKING") {
            Text(
                "Finds every service announcing itself on your network — no typing, no guessing connection numbers.",
                color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (viewModel.isDiscoveringNsd) {
                TerminalButton("Stop listening") { viewModel.stopDiscoverServices() }
            } else {
                TerminalButton("What's talking") { viewModel.discoverServices() }
            }
            if (viewModel.nsdServices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "ADVERTISED SERVICES (${viewModel.nsdServices.size}):",
                    color = Accent, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                viewModel.nsdServices.forEach { svc ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TerminalButton("${svc.host}:${svc.port}") {
                            if (svc.type.contains("http")) {
                                val scheme = if (svc.type.contains("https")) "https" else "http"
                                viewModel.httpGet("$scheme://${svc.host}:${svc.port}")
                            } else {
                                // Package 2 (A-24) — talks to it right away
                                // instead of only filling the box below.
                                viewModel.prefillTcp(svc.host, svc.port)
                                viewModel.tcpTest(svc.host, svc.port, "HELLO")
                            }
                        }
                        Text(
                            "  ${svc.name} (${svc.type}) — " +
                                if (svc.type.contains("http")) "tap it: reads the page it serves."
                                else "tap it: talks to it right away with HELLO — the box below shows what was sent.",
                            color = Dim, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ---- Ghost AP: Ghost Tap's own hotspot ----
        PanelBox("GHOST AP") {
            Text(
                "Ghost Tap's own Wi-Fi hotspot — made by the app, not the phone's settings. " +
                    "Connect a device to it for testing. Local only: no internet is shared through it.",
                color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (viewModel.ghostApLive) {
                Text(
                    "Hotspot on — Wi-Fi name: ${viewModel.ghostApSsid} / Password: ${viewModel.ghostApPassword}",
                    color = Accent, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                TerminalButton("Stop hotspot") { viewModel.stopGhostAp() }
                // Package 5 (B-094) — who joined, by address as seen on
                // the network while the hotspot is on. Addresses only —
                // never a guessed name.
                Spacer(modifier = Modifier.height(6.dp))
                TerminalButton("CHECK WHO JOINED") { viewModel.refreshGhostApClients() }
                if (viewModel.ghostApClients.isEmpty()) {
                    Text(
                        "No device has joined yet — tap CHECK WHO JOINED after connecting one.",
                        color = Dim, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "DEVICES THAT JOINED:",
                        color = Accent, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    viewModel.ghostApClients.forEach { client ->
                        Text(
                            client,
                            color = Dim, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                TerminalButton("Start hotspot") { viewModel.startGhostAp() }
            }
        }

        // ---- Ping: own host field + own button ----
        PanelBox("PING") {
            Text("Check if a device answers. Success means you get a reply with a time. No reply may mean the device ignores this kind of check.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = pingHost,
                onValueChange = { pingHost = it },
                label = { Text("Type the device address here, then tap Check") },
                modifier = Modifier.fillMaxWidth()
            )
            TerminalButton("Check", { if (pingHost.isNotBlank()) viewModel.ping(pingHost.trim()) })
        }

        // ---- TCP: own host + port + message + button ----
        PanelBox("TCP — CONNECT + SEND") {
            Text("Connect, send your message, read any reply. Success means it connects and shows a reply.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = tcpHost,
                onValueChange = { tcpHost = it },
                label = { Text("Device address") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = tcpPort,
                onValueChange = { tcpPort = it },
                label = { Text("Port number") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = tcpMessage,
                onValueChange = { tcpMessage = it },
                label = { Text("Message to send") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Sends exactly what you type.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            TerminalButton("Connect and send", {
                val p = tcpPort.toIntOrNull()
                if (tcpHost.isNotBlank() && p != null) viewModel.tcpTest(tcpHost.trim(), p, tcpMessage)
            })
        }

        // ---- UDP: own host + port + message + button ----
        PanelBox("UDP — SEND + LISTEN") {
            Text("Send one short message, listen 3 seconds for a reply. This kind of message never confirms it arrived — no reply is normal.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = udpHost,
                onValueChange = { udpHost = it },
                label = { Text("Device address") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = udpPort,
                onValueChange = { udpPort = it },
                label = { Text("Port number") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = udpMessage,
                onValueChange = { udpMessage = it },
                label = { Text("Message to send") },
                modifier = Modifier.fillMaxWidth()
            )
            TerminalButton("Send", {
                val p = udpPort.toIntOrNull()
                if (udpHost.isNotBlank() && p != null) viewModel.udpTest(udpHost.trim(), p, udpMessage)
            })
        }

        // ---- Port scan: own host + button ----
        PanelBox("PORT SCAN") {
            Text("Check which common connection points answer on the device. Only check devices you own or are allowed to test.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = scanHost,
                onValueChange = { scanHost = it },
                label = { Text("Type the device address here, then tap Check") },
                modifier = Modifier.fillMaxWidth()
            )
            TerminalButton("Check common connection points", { if (scanHost.isNotBlank()) viewModel.portScan(scanHost.trim()) })
        }

        // ---- HTTP: own URL + button ----
        PanelBox("HTTP — FETCH PAGE") {
            Text("Fetch a web page and see what it returns. Success means the page loads and shows its text.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Type the web address here, then tap Fetch") },
                modifier = Modifier.fillMaxWidth()
            )
            TerminalButton("Fetch page", { if (url.isNotBlank()) viewModel.httpGet(url.trim()) })
        }

        // ---- Log ----
        PanelBox("RESULTS") {
            if (viewModel.log.isEmpty()) {
                Text("No tests run yet. Pick a tool above.", color = Dim, fontFamily = FontFamily.Monospace)
            } else {
                viewModel.log.forEach { line ->
                    Text(line, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (viewModel.isBusy) {
            Text("Working…", color = Accent, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * NFC TOOLS — Flipper-style execution. Every card says what it is,
 * what the action does, what it unlocks, the current state, and what
 * to do next. No jargon, no dead buttons.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NfcToolsScreen(
    viewModel: GhostTapViewModel,
    onBack: () -> Unit,
    onOpenWhatToDo: (() -> Unit)? = null,
    onInvestigate: () -> Unit = {},
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
    // ZERO-CONFIG FIX (2026-11-06): the intent answer line is shared
    // across screens — clear it when this screen opens so a stale
    // outcome from another screen never shows here.
    LaunchedEffect(Unit) { intelVm?.clearCommandAnswer() }
    var writeText by remember { mutableStateOf("") }
    var writeUrl by remember { mutableStateOf("") }
    var emulateText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    // DISCOVER (NFC): true after the user taps "Investigate the unfamiliar"
    // tag" — the reader is armed and the screen says to hold a tag to
    // the phone. A tag cannot be found from a distance, so this arms
    // the reader for the next tap; it invents nothing.
    var nfcDiscoverArmed by remember { mutableStateOf(false) }
    // Recompose when the ViewModel ticks (tag tapped, emulation toggled).
    @Suppress("UNUSED_VARIABLE")
    val tick = viewModel.uiTick
    // Package 4 (D-35 / F-32) — the tapped card's facts, once, shared
    // by all 10 panels for gating: a panel whose action this card
    // cannot do is disabled with a plain-words reason instead of
    // failing at runtime. Null when no card has been tapped yet.
    val nfcFacts = viewModel.lastNfcTag?.let { tag ->
        viewModel.nfcFactsFor("nfc:${tag.id.toHexString()}")
    }

    // Every NFC tool result flows to the intelligence engine through here —
    // one choke point for the whole NFC TOOLS screen. ok is judged from the
    // message text: the NFC functions return plain failure sentences
    // ("No tag in range…", "Write failed…") on failure.
    fun say(msg: String, op: String = "nfc.tool") {
        result = msg
        try {
            IntelEventBus.publish(
                IntelEvent.ToolOpResult(op, !looksLikeNfcFailure(msg), msg.take(1500))
            )
        } catch (_: Exception) {
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TerminalButton("Back", onBack)
        Text("NFC TOOLS", color = Accent, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
        onOpenWhatToDo?.let { TerminalButton("What do you want to do?", it) }
        // DISCOVER (NFC): a tap card cannot be found from a distance —
        // the phone only reads one when it is held to the back. This
        // button gets the reader ready and says so; the next tag tapped
        // is checked automatically through the existing read path, and
        // its findings list below. Nothing is faked, nothing is written.
        TerminalButton("Investigate the unfamiliar") { nfcDiscoverArmed = true }
        if (nfcDiscoverArmed) {
            Text(
                "Ready — hold a tag to the back of the phone. The next tag you tap will be checked here.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // ZERO-CONFIG — the intent box: type what you're trying to do
        // and Ghost Tap does it.
        IntentBox(onIntent = onIntent, answer = intentAnswer)
        ExplainerCard(
            "Tap a card or tag to the back of your phone.",
            "Card 1 reads it. The other cards copy it, write to it, or make your phone pretend to be a tag.",
            "Never write to or erase a card you actually use — practice on blank tags only."
        )
        Text(
            "Your phone can read and write tap cards. Tap cards to read them, write your own words onto blank cards, copy one card to another, or make your phone pretend to be a card.",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )

        // ---- 1. READ ----
        PanelBox("1. READ A TAG OR CARD") {
            Text("What this is: the phone listens for any tap card.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it unlocks: once read, you can copy it or write to it below.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Right now:", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(viewModel.lastNfcReadSummary, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Deep read: Read all memory reads every stored page off the card, shown as code.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("For this card, the deep read is: ${deepReadChoice(nfcFacts)}.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            TerminalButton("Read this card") { say(viewModel.nfcReadCardGuided(), "nfc.read_card") }
            TerminalButton("Read all memory") { say(viewModel.nfcDumpMemory(), "nfc.dump_memory") }
            TerminalButton("Investigate") {
                // Package 2 (A-29) — investigating with nothing read yet
                // used to open an investigation of an empty summary.
                if (viewModel.lastNfcTag == null) {
                    say("Tap a card first — hold a card against the back of the phone, then tap Investigate.")
                } else {
                    onInvestigate()
                }
            }
            Text("Investigate hands the last read to Ghost Tap — it researches the card, checks it, and learns.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("Long-range cards: read their stored blocks.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            TerminalButton("Read card blocks") { say(viewModel.nfcReadNfcVBlocks(0, 16), "nfc.read_nfcv") }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Next: hold a tag or card against the back of your phone.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }

        // MASTER PROMPT — LIVE TARGETS, not history: every tag
        // checked, newest first — a re-check refreshes a tag's report
        // in place, so the list never stacks duplicates of the same
        // tag. Connect / control still re-run the existing read path;
        // new on the card: what the thinking engine says, and — once
        // a tag has been tapped — the commands you can tap on it.
        val nfcLive = remember(tick) {
            LiveTargetStore.all().filter { it.source == InvestigationSource.NFC }
        }
        nfcLive.forEach { live ->
            LiveTargetCard(
                target = live,
                intelVm = intelVm,
                isConnected = viewModel.lastNfcTag != null,
                onConnect = { say(viewModel.nfcReadCardGuided(), "nfc.read_card") },
                onControl = { say(viewModel.nfcReadCardGuided(), "nfc.read_card") },
                onRunCommand = onRunLiveCommand,
                onOpenRoute = onOpenRoute,
                commandAnswer = intelVm?.lastCommandAnswer.orEmpty()
            )
        }

        // ---- 2. COPY ----
        PanelBox("2. COPY TAG") {
            Text("What this does: saves the last tapped tag's data inside the app.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it unlocks: Write saved below — a real card-to-card copy.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            // Package 4 (D-35 / F-32) — this card can only be copied when
            // it has something saved on it; otherwise the button is off
            // with the reason shown, instead of failing at runtime.
            val gate2 = gateForPanel(2, nfcFacts, viewModel.hasSavedNdef())
            if (!gate2.usable) {
                Text(gate2.reason ?: "", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (viewModel.hasSavedNdef()) "Saved: ${viewModel.savedNdefSummary()}" else "Nothing saved yet.",
                color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
            )
            if (AreaNfcState.copyReady && viewModel.hasSavedNdef()) {
                Text("Ready for the second card — hold a different card against the phone, then use Write saved below.", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            TerminalButton("Copy last tapped card", enabled = gate2.usable, onClick = {
                say(viewModel.nfcCopyContent(), "nfc.copy")
                if (viewModel.hasSavedNdef()) {
                    AreaNfcState.copyReady = true
                    AreaNfcState.copiedFromUid = viewModel.lastNfcTag?.id?.toHexString()
                }
            })
        }

        // ---- 3. WRITE TEXT ----
        PanelBox("3. WRITE TEXT TO TAG") {
            Text("What this does: stores your text on the tag that's touching the phone.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it unlocks: any phone that taps that tag reads your text.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            // Package 4 (D-35 / F-32) — only a writable card can take words.
            val gate3 = gateForPanel(3, nfcFacts, viewModel.hasSavedNdef())
            if (!gate3.usable) {
                Text(gate3.reason ?: "", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = writeText,
                onValueChange = {
                    writeText = it
                    AreaNfcState.stagedText = it
                },
                label = { Text("Type words, then tap Write words") },
                modifier = Modifier.fillMaxWidth()
            )
            TerminalButton("Write words", enabled = gate3.usable, onClick = {
                // Package 4 (B-030 / D-38) — capacity is checked BEFORE
                // writing: too big refuses plainly instead of failing
                // halfway through the write.
                val tooBig = viewModel.nfcFitsOnCard(writeText, false)
                say(when {
                    writeText.isBlank() -> "Type some text first."
                    tooBig != null -> tooBig
                    viewModel.writeNdefText(writeText) -> "Words written onto the card."
                    else -> "Didn't work — the card may not be close enough or can't be written to."
                }, "nfc.write_text")
            })
            TerminalButton("Write and check", enabled = gate3.usable, onClick = {
                val tooBig = viewModel.nfcFitsOnCard(writeText, false)
                say(if (tooBig != null) tooBig else viewModel.nfcWriteTextVerified(writeText), "nfc.write_text")
            })
        }

        // ---- 4. WRITE LINK ----
        PanelBox("4. WRITE LINK TO TAG") {
            Text("What this does: stores a web link on the tag that's touching the phone.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it unlocks: any phone that taps that tag opens your link.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            // Package 4 (D-35 / F-32) — only a writable card can take a link.
            val gate4 = gateForPanel(4, nfcFacts, viewModel.hasSavedNdef())
            if (!gate4.usable) {
                Text(gate4.reason ?: "", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = writeUrl,
                onValueChange = {
                    writeUrl = it
                    AreaNfcState.stagedUrl = it
                },
                label = { Text("Type a link, then tap Write link") },
                modifier = Modifier.fillMaxWidth()
            )
            TerminalButton("Write link", enabled = gate4.usable, onClick = {
                // Package 4 (B-030 / D-38) — capacity checked before writing.
                val tooBig = viewModel.nfcFitsOnCard(writeUrl, true)
                say(when {
                    writeUrl.isBlank() -> "Type a link first."
                    tooBig != null -> tooBig
                    viewModel.nfcWriteUrl(writeUrl) -> "Link written onto the card."
                    else -> "Didn't work — the card may not be close enough or can't be written to."
                }, "nfc.write_url")
            })
            TerminalButton("Write and check", enabled = gate4.usable, onClick = {
                val tooBig = viewModel.nfcFitsOnCard(writeUrl, true)
                say(if (tooBig != null) tooBig else viewModel.nfcWriteUrlVerified(writeUrl), "nfc.write_url")
            })
        }

        // ---- 5. WRITE SAVED (CLONE) ----
        PanelBox("5. WRITE SAVED — CLONE A TAG") {
            Text("What this does: writes your copied tag data onto the tag that's touching the phone.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it unlocks: a working copy of the original tag.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            // Package 4 (D-35 / F-32) — needs a saved copy and a writable card.
            val gate5 = gateForPanel(5, nfcFacts, viewModel.hasSavedNdef())
            if (!gate5.usable) {
                Text(gate5.reason ?: "", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (viewModel.hasSavedNdef()) "Ready to write: ${viewModel.savedNdefSummary()}" else "Nothing copied yet — use Copy a card first.",
                color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
            )
            if (AreaNfcState.copyReady && viewModel.hasSavedNdef()) {
                Text("Ready for the second card — this is step 2 of the copy. Hold the second card against the phone.", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            TerminalButton("Write saved onto this card", enabled = gate5.usable, onClick = {
                // Package 4 (B-029 / D-37) — the copy can never be written
                // back onto the card it came from.
                val currentUid = viewModel.lastNfcTag?.id?.toHexString()
                val msg = when {
                    !viewModel.hasSavedNdef() -> "Nothing copied yet — use Copy a card first."
                    currentUid != null && currentUid == AreaNfcState.copiedFromUid ->
                        "That's the same card you copied from — hold a different card against the phone."
                    viewModel.nfcWriteSaved() -> "Saved data written — that's your copy."
                    else -> "Didn't work — the card may not be close enough or can't be written to."
                }
                say(msg, "nfc.write_saved")
                if (msg.startsWith("Saved data written")) AreaNfcState.clearCopyFlow()
            })
        }

        // ---- 6. ERASE ----
        PanelBox("6. ERASE TAG") {
            var eraseArmed by remember { mutableStateOf(false) }
            Text("What this does: wipes everything off the tag that's touching the phone.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it unlocks: a blank tag, ready for your own data.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            // Package 4 (D-35 / F-32) — only a writable card can be wiped.
            val gate6 = gateForPanel(6, nfcFacts, viewModel.hasSavedNdef())
            if (!gate6.usable) {
                Text(gate6.reason ?: "", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            // Package 4 (B-031 / D-39, F-49) — one tap no longer erases:
            // the first tap arms it, the second tap wipes, same double-
            // tap confirm the target screen's erase/format actions use.
            if (eraseArmed) {
                Text("This wipes everything off the card — there's no undo. Tap again to wipe it.", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            TerminalButton(if (eraseArmed) "Tap again to wipe it" else "Erase card", enabled = gate6.usable, onClick = {
                if (!eraseArmed) {
                    eraseArmed = true
                } else {
                    eraseArmed = false
                    say(if (viewModel.nfcErase()) "Card erased — it's blank now."
                        else "Didn't work — the card may not be close enough or is locked.", "nfc.erase")
                }
            })
        }

        // ---- 7. EMULATE ----
        PanelBox("7. PRETEND TO BE A TAG") {
            Text("What this is: your phone acts like a tap card, broadcasting your text or link.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it unlocks: tap your phone to another phone or reader and it reads your data like a real tag.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("Honest limit: this broadcasts YOUR content only — it can't copy bank cards or locked tags.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (viewModel.isEmulating) "ON — broadcasting: ${viewModel.emulatedSummary()}" else "OFF",
                color = if (viewModel.isEmulating) Accent else Dim,
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = emulateText,
                onValueChange = {
                    emulateText = it
                    viewModel.setEmulatedText(it)
                },
                label = { Text("Words to share as a card") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton(if (viewModel.isEmulating) "Stop pretending" else "Start pretending") {
                    val turningOn = !viewModel.isEmulating
                    viewModel.setEmulationEnabled(turningOn)
                    say(if (turningOn) "Pretending is on — tap this phone to a reader." else "Pretending is off.")
                }
            }
            // Package 4 (B-043 / D-40) — "pretend to be the card I just
            // read": the card's own words or link become the emulated
            // tag. Needs no card type — pretending is the phone itself.
            TerminalButton("Pretend to be the card I just read") {
                say(viewModel.nfcEmulateLastReadCard(), "nfc.emulate_card")
            }
            if (viewModel.isEmulating) {
                Text(
                    "Readers that tapped this phone: ${viewModel.emulationReaderTaps()}",
                    color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
                )
            }
            Text("Next: with it ON, hold this phone to another phone's tap spot.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }

        // ---- 8. RAW CONSOLE ----
        PanelBox("8. RAW TAG CONSOLE — TALK DIRECTLY TO THE TAG") {
            var rawHex by remember { mutableStateOf("") }
            var rawScript by remember { mutableStateOf("") }
            var rawTech by remember { mutableStateOf("AUTO") }
            var rawTimeout by remember { mutableStateOf("1500") }
            Text("What this is: a direct line into the tapped card. You type codes, the card answers with codes.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it does: sends anything — memory reads, status checks, card commands — with nothing in between.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it unlocks: the deepest level of tag control this phone physically supports.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("Honest limit: the tag's own chip decides what it answers. Locked cards stay locked — nothing overrides the chip.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Right now:", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            val rawTechLine = viewModel.lastNfcTag?.techList
                ?.map { it.substringAfterLast('.') }?.joinToString(", ")
            Text(
                if (rawTechLine != null) "Tag in range — channel auto-picks from: $rawTechLine" else "No tag tapped yet — tap one first (card 1).",
                color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("Channel (which card language to use):", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AUTO", "IsoDep", "NfcA").forEach { t ->
                    TerminalButton(if (rawTech == t) "> $t" else t) { rawTech = t }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("NfcB", "NfcF", "NfcV").forEach { t ->
                    TerminalButton(if (rawTech == t) "> $t" else t) { rawTech = t }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = rawHex,
                onValueChange = { rawHex = it },
                label = { Text("Codes to send, e.g. 30 00") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("Send codes") { say(viewModel.nfcRawTransceiveTech(rawHex, rawTech), "nfc.raw") }
                TerminalButton("Clear") { viewModel.clearRawConsole() }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Script mode — one command per line, runs in ONE connected session:", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = rawScript,
                onValueChange = { rawScript = it },
                label = { Text("e.g.\n00 A4 04 00 07 D2 76 00 00 85 01 01 00\n00 B0 00 00 00") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TerminalButton("Run script") { say(viewModel.nfcRunScript(rawScript, rawTech), "nfc.script") }
                OutlinedTextField(
                    value = rawTimeout,
                    onValueChange = { v ->
                        rawTimeout = v
                        v.toIntOrNull()?.let { viewModel.setNfcTimeout(it) }
                    },
                    label = { Text("How long to wait (milliseconds)") },
                    modifier = Modifier.weight(1f)
                )
            }
            if (viewModel.rawConsoleHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("History:", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                viewModel.rawConsoleHistory.forEach { h ->
                    Text(h, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Try this: 30 00 reads memory page 0 on these kinds of cards.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            // Package 4 (B-033 / B-034 / D-41 / D-42) — ready-made
            // commands and scripts built from named blocks, so nothing
            // here needs typed hex. They run through the same console
            // and script runner as the typed boxes above.
            val gate8 = gateForPanel(8, nfcFacts, viewModel.hasSavedNdef())
            if (!gate8.usable) {
                Text(gate8.reason ?: "", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Ready-made commands — no codes to type:", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NAMED_NFC_COMMANDS.forEach { block ->
                    TerminalButton(block.name, enabled = gate8.usable, onClick = {
                        say(viewModel.nfcRawTransceiveTech(block.hex, block.tech), "nfc.raw")
                    })
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Ready-made scripts — several commands in one go:", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NAMED_NFC_SCRIPTS.forEach { script ->
                    TerminalButton(script.name, enabled = gate8.usable, onClick = {
                        val text = buildNamedScript(script.name) ?: ""
                        say(viewModel.nfcRunScript(text, script.tech), "nfc.script")
                    })
                }
            }
            // Package 4 (B-033) — "what to try next": the first named
            // command not yet sent in this console's history, same
            // pattern as the APDU console's next-check suggestion.
            val triedRawHex = viewModel.rawConsoleHistory.mapNotNull { h ->
                Regex("""Sent \[[^\]]+\] ([0-9A-F ]+)""").find(h)?.groupValues?.get(1)
            }.map { it.replace(" ", "") }.toSet()
            val nextRaw = NAMED_NFC_COMMANDS.firstOrNull { it.hex.replace(" ", "") !in triedRawHex }
            if (nextRaw != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TerminalButton("Try next: ${nextRaw.name}", enabled = gate8.usable, onClick = {
                    say(viewModel.nfcRawTransceiveTech(nextRaw.hex, nextRaw.tech), "nfc.raw")
                })
                Text(nextRaw.explains, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            // ---- TAP-RUN: saved scripts that fire on tag tap ----
            Spacer(modifier = Modifier.height(6.dp))
            Text("TAP-RUN — saved scripts that run by themselves when you tap a card:", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            var scriptName by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = scriptName,
                    onValueChange = { scriptName = it },
                    label = { Text("Script name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TerminalButton("Save script") {
                    say(viewModel.saveNfcScript(scriptName, rawScript, rawTech), "nfc.save_script")
                    scriptName = ""
                }
            }
            val armed = viewModel.tapRunArmed
            if (armed != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Tap-run ready: $armed — tap a card to run it.",
                    color = Accent, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "It runs on the next tap of ANY card, and it is forgotten when the app closes.",
                    color = Dim, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                TerminalButton("Turn off") { viewModel.disarmTapRun() }
            }
            if (viewModel.nfcSavedScripts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                viewModel.nfcSavedScripts.forEach { (name, _, tech) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TerminalButton(if (armed == name) "> Ready" else "Set ready") {
                            viewModel.armTapRun(name)
                        }
                        Text(
                            "$name ($tech)",
                            color = Dim, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TerminalButton("Delete") { viewModel.deleteNfcScript(name) }
                    }
                }
            } else {
                Text(
                    "No saved scripts yet — write one above, name it, tap Save script, then tap Set ready.",
                    color = Dim, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ---- 9. MIFARE CLASSIC TOOLS ----
        PanelBox("9. MIFARE CLASSIC TOOLS") {
            var classicBlock by remember { mutableStateOf("") }
            var classicBlockHex by remember { mutableStateOf("") }
            var classicTrailerArmed by remember { mutableStateOf(false) }
            Text("What this is: deep tools for the most common kind of door and access card.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it does: Try common passwords tests 14 well-known factory passwords on every section. Read open sections shows the stored data in sections that opened. Write block writes 16 bytes to one block in an open section.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("Honest limit: only factory passwords are tried — cards with changed passwords stay locked, and that's reported, not bypassed.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            // Package 4 (D-35 / F-32) — these tools only work on a
            // Mifare Classic card; otherwise the buttons are off.
            val gate9 = gateForPanel(9, nfcFacts, viewModel.hasSavedNdef())
            if (!gate9.usable) {
                Text(gate9.reason ?: "", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text("Right now:", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(viewModel.mifareStatus, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            // Package 4 (B-041 / D-45) — one tap tries the usual
            // passwords AND reads what opened, instead of two taps
            // with a wait in between.
            TerminalButton("Try the usual passwords, then read what opened", enabled = gate9.usable, onClick = {
                viewModel.mifareTryAndRead()
                say("Checking passwords, then reading what opened — watch the status above.", "nfc.mifare_keys")
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("Try common passwords", enabled = gate9.usable, onClick = {
                    viewModel.mifareCheckKeys()
                    say("Checking passwords — watch the status above.", "nfc.mifare_keys")
                })
                TerminalButton("Read open sections", enabled = gate9.usable, onClick = {
                    viewModel.mifareReadSectors()
                    say("Reading — watch the status above.", "nfc.mifare_read")
                })
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Write 16 bytes to a block in an OPEN section (the last block in a section holds its passwords — be careful):", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = classicBlock,
                    onValueChange = {
                        classicBlock = it
                        AreaNfcState.stagedClassicBlock = it
                    },
                    label = { Text("Block #") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = classicBlockHex,
                    onValueChange = {
                        classicBlockHex = it
                        AreaNfcState.stagedClassicBlockHex = it
                    },
                    label = { Text("16 bytes of code") },
                    modifier = Modifier.weight(2f)
                )
            }
            if (classicTrailerArmed) {
                Text("That block holds the section's passwords — writing there can lock the section. Tap again to write it anyway.", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            TerminalButton(if (classicTrailerArmed) "Tap again to write the key area" else "Write block", enabled = gate9.usable, onClick = {
                val b = classicBlock.toIntOrNull()
                val tag = viewModel.lastNfcTag
                // Package 4 (B-037 / F-49) — writing the last block of a
                // section (its passwords) asks first instead of only
                // warning afterwards.
                val isTrailer = b != null && tag != null && classicTrailerBlock(tag, b) == true
                when {
                    b == null -> say("Type a block number first.", "nfc.write_classic")
                    isTrailer && !classicTrailerArmed -> {
                        classicTrailerArmed = true
                        say("Block $b holds this section's passwords — writing there can lock the section. Tap again to write it anyway.", "nfc.write_classic")
                    }
                    else -> {
                        classicTrailerArmed = false
                        say(viewModel.nfcWriteClassicBlock(b, classicBlockHex), "nfc.write_classic")
                    }
                }
            })
            Spacer(modifier = Modifier.height(4.dp))
            Text("Next: tap a door card, then tap Try common passwords.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }

        // ---- 10. TAG MEMORY WRITE ----
        PanelBox("10. TAG MEMORY WRITE — ULTRALIGHT / NTAG") {
            var ultraPage by remember { mutableStateOf("") }
            var ultraPageHex by remember { mutableStateOf("") }
            var ntagPwd by remember { mutableStateOf("") }
            var magicHex by remember { mutableStateOf("") }
            Text("What this is: writing directly to a card's memory — for your own cards.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("What it does: write one small 4-byte page (checked by reading it back), unlock password-protected cards with your password, or write the first block on special copy cards.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("Honest limit: locked pages refuse. The special write only works on special copy cards — normal cards ignore it.", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            // Package 4 (D-35 / F-32) — only an Ultralight card takes
            // these writes; otherwise the buttons are off.
            val gate10 = gateForPanel(10, nfcFacts, viewModel.hasSavedNdef())
            if (!gate10.usable) {
                Text(gate10.reason ?: "", color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text("Write 4 bytes to one page (checked after):", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ultraPage,
                    onValueChange = {
                        ultraPage = it
                        AreaNfcState.stagedPage = it
                    },
                    label = { Text("Page #") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = ultraPageHex,
                    onValueChange = {
                        ultraPageHex = it
                        AreaNfcState.stagedPageHex = it
                    },
                    label = { Text("4 bytes of code") },
                    modifier = Modifier.weight(2f)
                )
            }
            // Package 4 (B-036 / D-48) — pages 0–3 hold the card's ID
            // and lock settings; the safe pages are 4 and up.
            ultraPage.toIntOrNull()?.let { p ->
                ultralightPageWarning(p)?.let { warning ->
                    Text(warning, color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            TerminalButton("Write page", enabled = gate10.usable, onClick = {
                val p = ultraPage.toIntOrNull()
                say(if (p == null) "Type a page number first." else viewModel.nfcWriteUltralightPage(p, ultraPageHex), "nfc.write_ultralight")
            })
            Spacer(modifier = Modifier.height(4.dp))
            Text("Card password unlock:", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ntagPwd,
                    onValueChange = {
                        ntagPwd = it
                        AreaNfcState.stagedNtagPwd = it
                    },
                    label = { Text("4-byte password code") },
                    modifier = Modifier.weight(1f)
                )
                TerminalButton("Unlock", enabled = gate10.usable, onClick = { say(viewModel.nfcNtagPwdAuth(ntagPwd), "nfc.ntag_auth") })
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Special copy card — write the first block (card ID area):", color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = magicHex,
                    onValueChange = {
                        magicHex = it
                        AreaNfcState.stagedMagicHex = it
                    },
                    label = { Text("16 bytes of code") },
                    modifier = Modifier.weight(1f)
                )
                TerminalButton("Special write", enabled = gate10.usable, onClick = { say(viewModel.nfcMagicWriteBlock0(magicHex), "nfc.magic_write") })
            }
        }

        // ---- Result line ----
        if (result.isNotBlank()) {
            PanelBox("LAST RESULT") {
                Text(result, color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Plain-English evidence bundle for one-tap INVESTIGATE entries. */
private fun buildTargetText(t: ScanTarget): String = buildString {
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

@Composable
private fun TargetScreen(
    target: ScanTarget,
    viewModel: GhostTapViewModel,
    onBack: () -> Unit,
    onInvestigate: (ScanTarget) -> Unit = {},
    /** Package 7 (E-30 / F-07) — ask the brain about this device. */
    onAskBrain: (ScanTarget) -> Unit = {},
    // ZERO-CONFIG — auto-connect needs the thinking engine (bring-up),
    // and the control page carries the intent box. Null = standalone
    // use: the page still renders, without auto-connect or intent.
    intelVm: com.ghosttap.IntelViewModel? = null,
    onIntent: ((String) -> Unit)? = null,
    intentAnswer: String = ""
) {
    // ZERO-CONFIG FIX (2026-11-06): the intent answer line is shared
    // across screens — clear it when this screen opens so a stale
    // outcome from another screen never shows here.
    LaunchedEffect(Unit) { intelVm?.clearCommandAnswer() }
    // Package 2 (A-09/A-12/A-20) — one shared panel runner for this
    // screen, and the technical-details toggle hoisted so a panel link
    // can open the details and jump to them.
    val scrollState = rememberScrollState()
    val panelRunner = remember(scrollState) { PanelRunner().also { it.scrollState = scrollState } }
    val jumpScope = rememberCoroutineScope()
    var showAdvanced by remember { mutableStateOf(false) }
    fun jumpToPanel(key: String) = panelRunner.jump(jumpScope, key)
    // An operation ("See the technical details") can ask for the details
    // to open; the jump waits a beat for the panels to lay out.
    val detailsRequested = AreaAState.detailsRequestedFor
    LaunchedEffect(detailsRequested) {
        if (detailsRequested == target.id) {
            showAdvanced = true
            AreaAState.detailsRequestedFor = null
            delay(150)
            panelRunner.jump(jumpScope, "details")
        }
    }
    // ZERO-CONFIG — SELECT → AUTO-CONNECT (2026-11-06): opening a
    // Bluetooth device's page IS selecting it, so Ghost Tap connects
    // it to a controllable state on its own, through the existing
    // bring-up (connect → check it answers → land on control). Once
    // per device page: a failed bring-up is retried from the banner's
    // "Try again", never by re-entering the page. Already-connected
    // devices and non-Bluetooth targets are untouched. A device the
    // user deliberately disconnected is NEVER auto-connected again:
    // that choice lives in the thinking engine's ViewModel
    // (IntelViewModel.manuallyDisconnectedIds), so it survives
    // leaving and re-opening the page — until the user taps Connect
    // himself, which clears it. Classic Bluetooth keeps its manual
    // text-link button: GATT and the text link are two different
    // transports and the choice between them is real, not config.
    var autoBringUpFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target.id, target.connected) {
        // Connected again (by any path) clears a stale manual-
        // disconnect entry, so a later drop that was NOT the user's
        // choice can still auto-reconnect.
        if (target.connected) intelVm?.clearManualDisconnect(target.id)
        if (target.type == TargetType.BLE && !target.connected &&
            autoBringUpFor != target.id &&
            intelVm?.wasManuallyDisconnected(target.id) != true
        ) {
            autoBringUpFor = target.id
            intelVm?.bringUpTarget(
                BringUpTarget(
                    kind = BringUpKind.BLE,
                    id = target.id.removePrefix("ble:"),
                    title = target.name.ifBlank { target.id }
                )
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ---- Guided workflow: where you are, what to do next ----
        PanelBox("WHAT TO DO") {
            Text(
                plainConnectionState(target.connected, target.type),
                color = Accent,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (!target.connected) {
                Text(
                    "Ghost Tap is connecting to this device on its own — no setup needed. When it's ready, use the actions below — each one explains what it does before you run it. If it couldn't connect, the note at the bottom says why and how to try again.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    "You're connected. Scroll down to see what this device can do. Each action explains itself — tap one to try it.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("Back", onBack)
            TerminalButton("Investigate") { onInvestigate(target) }
            TerminalButton("Ask the brain") { onAskBrain(target) }
            TerminalButton("Refresh readings") { viewModel.refreshUsbAndSensorsWatch() }
            if (target.type == TargetType.BLE && !target.connected) {
                val tick = viewModel.uiTick
                val gattState = remember(target.id, tick) { viewModel.gattStatusText(target.id) }
                if (gattState.startsWith("CONNECTING")) {
                    TerminalButton("Cancel") { viewModel.cancelBleConnect(target.id) }
                } else {
                    TerminalButton("Connect") { viewModel.connectBle(target.id) }
                }
            }
            if (target.type == TargetType.BLE && target.connected) {
                // ZERO-CONFIG FIX (2026-11-06): a manual Disconnect is
                // the user's decision — remember it so re-opening this
                // page never auto-connects the device again.
                TerminalButton("Disconnect") { intelVm?.noteManualDisconnect(target.id); viewModel.disconnectBle(target.id) }
            }
            if (target.type == TargetType.BLUETOOTH_CLASSIC) {
                val tick = viewModel.uiTick
                val sppConnected = remember(target.id, tick) { viewModel.isSppConnected(target.id) }
                if (!sppConnected) {
                    TerminalButton("Connect (text link)") { viewModel.connectSpp(target.id) }
                } else {
                    TerminalButton("Disconnect") { viewModel.disconnectSpp(target.id) }
                }
            }
            // SPP is also offered on BLE targets: many devices (printers, OBD-II)
            // advertise over BLE but speak SPP over Classic. The RFCOMM connect
            // will fail honestly if the device doesn't support it.
            if (target.type == TargetType.BLE) {
                val tick = viewModel.uiTick
                val sppConnected = remember(target.id, tick) { viewModel.isSppConnected(target.id) }
                if (!sppConnected) {
                    TerminalButton("Try text link") { viewModel.connectSpp(target.id) }
                } else {
                    TerminalButton("Disconnect text link") { viewModel.disconnectSpp(target.id) }
                }
            }
        }

        // ZERO-CONFIG — the intent box on the connected-control page:
        // type what you're trying to do and Ghost Tap does it, through
        // the same commands below.
        IntentBox(onIntent = onIntent, answer = intentAnswer)

        // Package 2 (A-12) — "do this" shortlist at the top: the same
        // device actions, shown first, so the user never has to scroll a
        // long page to find what to do. Every panel below stays exactly
        // where it was, unchanged and in order.
        ActionsPanel(
            viewModel = viewModel,
            target = target,
            compact = true,
            onJumpToPanel = ::jumpToPanel,
            onShowDetails = if (target.type == TargetType.BLE) {
                {
                    showAdvanced = true
                    jumpScope.launch {
                        delay(150)
                        panelRunner.jump(jumpScope, "details")
                    }
                }
            } else null
        )

        if (target.type == TargetType.BLE) {
            // "What is this thing?" comes before everything else.
            PanelAnchor("identify", panelRunner) {
                WhatIsThisDevicePanel(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
            // Plain-English interpretation…
            PanelAnchor("guide", panelRunner) {
                GuidePanel(viewModel = viewModel, target = target)
            }
            // …then what Ghost Tap can actually do…
            PanelAnchor("actions", panelRunner) {
                ActionsPanel(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
            // …but hands-on control stays front and center: the interactive
            // GATT explorer, MAP INTERFACE and smart RAW are NOT buried under ADVANCED.
            if (target.connected) {
                // WHAT YOU CAN CONTROL — computed from the REAL discovered
                // characteristics only. "Connect" means a controllable state:
                // this line says plainly what control you actually have.
                val ctlTick = viewModel.uiTick
                val discovered = remember(target.id, ctlTick) {
                    viewModel.discoveredServices(target.id)
                }
                val gattState = remember(target.id, ctlTick) {
                    viewModel.gattStatusText(target.id)
                }
                val chars = discovered.flatMap { it.characteristics }
                val readN = chars.count { "READ" in it.properties }
                val writeN = chars.count {
                    "WRITE" in it.properties || "WRITE_NO_RESPONSE" in it.properties ||
                        "SIGNED_WRITE" in it.properties
                }
                val notifyN = chars.count {
                    "NOTIFY" in it.properties || "INDICATE" in it.properties
                }
                PanelAnchor("control", panelRunner) {
                PanelBox("WHAT YOU CAN CONTROL") {
                    when {
                        chars.isEmpty() && gattState.startsWith("CONNECTED — discovering") ->
                            Text(
                                "Reading what this device can do — give it a moment…",
                                color = Dim, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        readN == 0 && writeN == 0 && notifyN == 0 ->
                            Text(
                                "Connected, but this device exposed nothing you can read " +
                                    "or write — nothing to control here.",
                                color = Dim, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        else ->
                            Text(
                                "You can: read $readN values · send to $writeN " +
                                    "· get live updates from $notifyN — tap one below.",
                                color = Accent, fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                    }
                    // One-tap whole-device moves, through the existing read/subscribe paths.
                    if (readN > 0 || notifyN > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (readN > 0) {
                                TerminalButton("Read everything") {
                                    viewModel.bleDumpAll(target.id)
                                }
                            }
                            if (notifyN > 0) {
                                TerminalButton("Turn on all live updates") {
                                    viewModel.bleSubscribeAll(target.id)
                                }
                            }
                        }
                        Text(
                            "Read everything reads every readable value into the history. " +
                                "Turn on all live updates switches on every live update.",
                            color = Dim, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    // Package 2 (A-13) — read just the interesting one
                    // instead of all-or-nothing, and see the latest
                    // readings right here instead of in another panel.
                    val summary = remember(target.id, ctlTick) { viewModel.deviceSummary(target.id) }
                    val interestingChannel = summary.mostInterestingChannel
                    if (interestingChannel != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TerminalButton("Read the most interesting one") {
                            viewModel.bleRead(target.id, interestingChannel.first, interestingChannel.second)
                        }
                        Text(
                            "Reads ${summary.mostInteresting ?: "the part that looks most useful"} — the part that looks most useful.",
                            color = Dim, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val recent = remember(target.id, ctlTick) {
                        viewModel.endpointHistoryFor(target.id).take(3)
                    }
                    if (recent.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Latest readings:", color = Accent, fontFamily = FontFamily.Monospace)
                        recent.forEach { h ->
                            Text(
                                "${h.kind}: ${h.hex}",
                                color = Dim, fontFamily = FontFamily.Monospace
                            )
                            h.ascii?.let {
                                Text("  as text: $it", color = Dim, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                }
            }
            // Pairing steps — only shown after a REAL auth failure, never preemptively.
            if (remember(target.id, viewModel.uiTick) { viewModel.needsPairing(target.id) }) {
                PanelAnchor("pairing", panelRunner) {
                PanelBox("NEEDS PAIRING FIRST") {
                    Text(
                        "This device won't talk until your phone pairs with it.",
                        color = Accent, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "1. Tap Pair below — your phone will ask for a code.",
                        color = Dim, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "2. Then tap Connect again.",
                        color = Dim, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // Package 2 (A-14) — the Connect tap is right here,
                    // where the instruction is, instead of only at the
                    // top of a long screen.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TerminalButton("Pair") { viewModel.pairBle(target.id) }
                        TerminalButton("Try Connect again") { viewModel.connectBle(target.id) }
                    }
                }
                }
            }
            PanelAnchor("gatt", panelRunner) {
                PanelBox("CONTROL") { BleGattPanel(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel) }
            }
            PanelAnchor("map", panelRunner) {
                MapInterfacePanel(viewModel = viewModel, target = target)
            }
            PanelAnchor("raw", panelRunner) {
                BleRawPanel(viewModel = viewModel, target = target)
            }
            // SPP terminal also available here: TRY SPP button is in the top row.
            PanelAnchor("spp_terminal", panelRunner) {
                SppTerminalPanel(viewModel = viewModel, target = target)
            }
            PanelAnchor("print", panelRunner) {
                PrinterPanel(viewModel = viewModel, target = target)
            }
            TerminalButton(if (showAdvanced) "Hide more options" else "Show more options") {
                showAdvanced = !showAdvanced
            }
            if (showAdvanced) {
                PanelAnchor("details", panelRunner) {
                    TargetDetailPanels(
                        viewModel = viewModel,
                        target = target,
                        hideBleControlRaw = true,
                        onJumpToPanel = ::jumpToPanel
                    )
                }
            }
        } else if (target.type == TargetType.NFC) {
            PanelAnchor("nfc_detected", panelRunner) {
                NfcDetectedPanel(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
            PanelAnchor("actions", panelRunner) {
                ActionsPanel(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
            if (viewModel.nfcFactsFor(target.id)?.isIsoDep == true) {
                PanelAnchor("apdu", panelRunner) {
                    ApduConsolePanel(viewModel = viewModel, target = target)
                }
            }
            PanelAnchor("details", panelRunner) {
                TargetDetailPanels(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
        } else if (target.type == TargetType.USB) {
            PanelAnchor("actions", panelRunner) {
                ActionsPanel(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
            PanelAnchor("details", panelRunner) {
                TargetDetailPanels(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
        } else if (target.type == TargetType.BLUETOOTH_CLASSIC) {
            PanelAnchor("actions", panelRunner) {
                ActionsPanel(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
            PanelAnchor("spp_terminal", panelRunner) {
                SppTerminalPanel(viewModel = viewModel, target = target)
            }
            PanelAnchor("print", panelRunner) {
                PrinterPanel(viewModel = viewModel, target = target)
            }
            PanelAnchor("details", panelRunner) {
                TargetDetailPanels(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
        } else {
            PanelAnchor("details", panelRunner) {
                TargetDetailPanels(viewModel = viewModel, target = target, onJumpToPanel = ::jumpToPanel)
            }
        }
    }
}

/** The technical detail panels (IDENTIFY / CAPABILITIES / CONTROL / WATCH / RAW / LOG). */
@Composable
private fun TargetDetailPanels(
    viewModel: GhostTapViewModel,
    target: ScanTarget,
    hideBleControlRaw: Boolean = false,
    onJumpToPanel: (String) -> Unit = {},
) {
    var rawInput by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var nfcText by remember { mutableStateOf("") }
    var networkPath by remember { mutableStateOf("/") }
    var networkBody by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // When the BLE CONTROL + RAW panels are shown outside ADVANCED, skip them here.
    val skipBleControlRaw = hideBleControlRaw && target.type == TargetType.BLE

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // BLE gets WHAT IS THIS DEVICE? on the main screen; the old IDENTIFY
        // box stays for other protocols only.
        if (!skipBleControlRaw) {
        PanelBox("IDENTIFY") {
            target.likely?.let { likely ->
                Text(
                    "Probably: $likely",
                    color = Accent,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "Confidence: ${target.confidence ?: "Low"}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
                target.evidence.take(3).forEach { ev ->
                    Text("· $ev", color = Dim, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(target.name, color = Accent, fontFamily = FontFamily.Monospace)
            target.identity.forEach { (k, v) ->
                Text(
                    "${prettyIdentityKey(k)}: ${translateIdentityValue(k, v)}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (target.requiresAuth) {
                Text("Needs a password or login", color = Color.Red, fontFamily = FontFamily.Monospace)
            }
        }
        }

        PanelBox("CAPABILITIES") {
            target.capabilities.forEach { cap ->
                val props = cap.metadata["properties"]
                val flags = if (props != null) "[$props]"
                else "[Can read=${cap.readable} Can write=${cap.writable} Live updates=${cap.notifiable}]"
                Text(
                    "${cap.title} $flags",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!skipBleControlRaw) {
            PanelBox("CONTROL") {
                when (target.type) {
                    TargetType.NFC -> {
                    OutlinedTextField(
                        value = nfcText,
                        onValueChange = { nfcText = it },
                        label = { Text("Words to write") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TerminalButton("Write onto card") {
                            output = if (viewModel.writeNdefText(nfcText)) "Written onto the card" else "Couldn't write — try again"
                        }
                    }
                }

                TargetType.BLE -> {
                    BleGattPanel(viewModel = viewModel, target = target, onJumpToPanel = onJumpToPanel)
                }

                else -> {
                    Text("Controls for this kind of device are in the Direct commands section below", color = Dim, fontFamily = FontFamily.Monospace)
                }
            }
        }
        }

        PanelBox("WATCH") {
            Text("Signal: ${describeRssi(target.rssi)}", color = Dim, fontFamily = FontFamily.Monospace)
            Text("Connected: ${target.connected}", color = Dim, fontFamily = FontFamily.Monospace)
            target.watchValues.forEach { (k, v) ->
                Text("$k => $v", color = Dim, fontFamily = FontFamily.Monospace)
            }
        }

        if (!skipBleControlRaw) {
        PanelBox("RAW") {
            when (target.type) {
                TargetType.USB -> {
                    Text("ENDPOINTS (from the real device descriptor):", color = Accent, fontFamily = FontFamily.Monospace)
                    target.capabilities.forEach { cap ->
                        val dir = when {
                            cap.title.contains(" IN") -> "In — Ghost Tap can receive here"
                            cap.title.contains(" OUT") -> "Out — Ghost Tap can send here"
                            else -> ""
                        }
                        val xfer = when (cap.metadata["type"]) {
                            "0" -> "control"
                            "1" -> "steady stream"
                            "2" -> "large transfers"
                            "3" -> "quick alerts"
                            else -> "unknown type"
                        }
                        Text(
                            "${cap.title}: $dir ($xfer, ${cap.metadata["maxPacketSize"] ?: "?"} bytes per message)",
                            color = Dim,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rawInput,
                        onValueChange = { rawInput = it },
                        label = { Text("Codes to send to the Out point") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Send writes your codes to the Out point, then reads the reply from the In point.",
                        color = Dim,
                        fontFamily = FontFamily.Monospace
                    )
                    TerminalButton("Send and read reply") {
                        output = viewModel.usbRawTransfer(target.id, rawInput)
                    }
                }

                TargetType.NETWORK -> {
                    OutlinedTextField(
                        value = networkPath,
                        onValueChange = { networkPath = it },
                        label = { Text("Page address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = networkBody,
                        onValueChange = { networkBody = it },
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TerminalButton("Get") {
                            scope.launch { output = viewModel.networkRawRequest(target.id, "GET", networkPath, "") }
                        }
                        TerminalButton("Send") {
                            scope.launch { output = viewModel.networkRawRequest(target.id, "POST", networkPath, networkBody) }
                        }
                    }
                }

                TargetType.BLE -> BleRawContent(viewModel = viewModel, target = target)

                TargetType.NFC -> {
                    if (viewModel.nfcFactsFor(target.id)?.isIsoDep == true) {
                        Text(
                            "Direct card commands live in the Card commands panel above — " +
                                "real commands, real card answers.",
                            color = Dim,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            "Card writing: use Read, Write words, Write link, Copy, and Erase in " +
                                "What Ghost Tap can do above.",
                            color = Dim,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                else -> {
                    OutlinedTextField(
                        value = rawInput,
                        onValueChange = { rawInput = it },
                        label = { Text("Direct input") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Package 2 (A-20) — this input never had anywhere to
                    // send to for this kind of device. Say so plainly
                    // instead of leaving a dead box.
                    Text(
                        "Direct mode depends on what this kind of device allows — for this kind there is no direct-command box yet. Use the actions in What Ghost Tap can do above.",
                        color = Dim, fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(output, color = Accent, fontFamily = FontFamily.Monospace)
            }
        }

        PanelBox("LOG") {
            Text("Open the Activity log from the home screen for the full history", color = Dim, fontFamily = FontFamily.Monospace)
        }
    }
}

// ======================================================================

/**
 * Smart RAW panel content for a BLE target: explains the selected channel,
 * offers LISTEN / READ / LEARN, lists learned commands with REPLAY, and keeps
 * a clearly-separated RAW WRITE for user-supplied bytes.
 */
@Composable
private fun BleRawContent(viewModel: GhostTapViewModel, target: ScanTarget) {
    var rawInput by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var asciiMode by remember { mutableStateOf(false) }
    val tick = viewModel.uiTick
    val selection = remember(tick) { viewModel.rawSelection(target.id) }
    if (selection != null) {
        val selProps = remember(tick) {
            viewModel.discoveredServices(target.id)
                .firstOrNull { it.uuid == selection.first }
                ?.characteristics?.firstOrNull { it.uuid == selection.second }
                ?.properties ?: emptyList()
        }
        val rank = remember(selProps) { ChannelRanker.rank(selProps) }
        val chName = SigUuids.characteristicName(selection.second)
        Text("RAW TARGET:", color = Accent, fontFamily = FontFamily.Monospace)
        if (chName != null) {
            Text(chName, color = Dim, fontFamily = FontFamily.Monospace)
        }
        Text(
            selection.second,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text("WHAT THIS CHANNEL MEANS", color = Accent, fontFamily = FontFamily.Monospace)
        Text(rank.plainEnglish, color = Dim, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TerminalButton("Listen") {
                viewModel.listenToChannel(target.id, selection.first, selection.second)
            }
            if (selProps.contains("READ")) {
                TerminalButton("Read") {
                    viewModel.bleRead(target.id, selection.first, selection.second)
                }
            }
            TerminalButton("Learn") {
                viewModel.startLearning(target.id)
            }
        }
        val learnedHere = remember(tick) {
            viewModel.learnedActionsFor(target.id)
                .filter { it.serviceUuid == selection.first && it.charUuid == selection.second }
        }
        if (learnedHere.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("OBSERVED COMMANDS ON THIS CHANNEL", color = Accent, fontFamily = FontFamily.Monospace)
            learnedHere.forEach { action ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${action.name} (${action.packetHex})",
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TerminalButton("Play again") {
                        output = viewModel.replayLearned(target.id, action.id)
                    }
                    // Package 2 (A-17) — fill the write box with bytes
                    // this channel was actually seen sending, instead of
                    // typing them by hand.
                    TerminalButton("Use these bytes") {
                        rawInput = action.packetHex
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("RAW WRITE — you supply the bytes (Source: you typed this)",
            color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text(
            "Group: ${selection.first}\nFeature: ${selection.second}\nCan do: send — Ghost Tap can send data here.",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
        OutlinedTextField(
            value = rawInput,
            onValueChange = { rawInput = it },
            label = { Text(if (asciiMode) "Plain words" else "Code") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton(if (asciiMode) "Words ✓" else "Code ✓") {
                asciiMode = !asciiMode
            }
            TerminalButton("Send") {
                output = viewModel.bleWritePayload(target.id, selection.first, selection.second, rawInput, asciiMode)
            }
        }
        Text(
            if (asciiMode) "Sending as plain words — no code knowledge needed."
            else "Sending as code pairs (e.g. A1 03).",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
    } else {
        Text(
            "Tap something you can write to in Control to pick it for direct commands.",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(output, color = Accent, fontFamily = FontFamily.Monospace)
}

/** BLE RAW panel: interactive GATT control surface, shown outside ADVANCED. */
@Composable
private fun BleRawPanel(viewModel: GhostTapViewModel, target: ScanTarget) {
    PanelBox("RAW") {
        BleRawContent(viewModel = viewModel, target = target)
    }
}

/**
 * SPP TERMINAL — Classic Bluetooth serial console.
 * Real RFCOMM connection, ASCII/HEX transmit, live RX log with timestamps.
 * Only real data from the socket appears here — never simulated.
 */
@Composable
private fun SppTerminalPanel(viewModel: GhostTapViewModel, target: ScanTarget) {
    val tick = viewModel.uiTick
    val connected = remember(target.id, tick) { viewModel.isSppConnected(target.id) }
    val status = remember(target.id, tick) { viewModel.sppStatusFor(target.id) }
    val logLines = remember(target.id, tick) { viewModel.sppLogFor(target.id) }
    var input by remember { mutableStateOf("") }
    var hexMode by remember { mutableStateOf(false) }
    var sendNote by remember { mutableStateOf("") }

    PanelBox("SEND TEXT TO THIS DEVICE") {
        Text(
            "What this does: opens a text chat with the device. Type something and the device receives it — like texting it.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Status: ${if (connected) "Connected — you can send text." else plainError(status)}",
            color = if (connected) Accent else Dim,
            fontFamily = FontFamily.Monospace
        )
        if (!connected) {
            Text(
                "Tap Connect (or Try text link) above first. Not every Bluetooth device accepts text — if this one refuses, you'll see why here.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            // Package 2 (A-18) — the link tap is right here instead of
            // only at the top of a long screen.
            TerminalButton("Try the text link") { viewModel.connectSpp(target.id) }
            return@PanelBox
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TerminalButton(if (hexMode) "Code mode" else "Text mode") { hexMode = !hexMode }
            TerminalButton("Clear log") { viewModel.clearSppLog(target.id) }
        }
        Text(
            if (hexMode) "Code mode: for advanced codes only. Type pairs like 41 54."
            else "Text mode: just type words. No codes needed.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Sends exactly what you type.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(if (hexMode) "Codes (e.g. 41 54)" else "Type your message") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
        )
        TerminalButton("Send") {
            val bytes: ByteArray? = if (hexMode) {
                runCatching {
                    input.trim().split(Regex("\\s+"))
                        .filter { it.isNotEmpty() }
                        .map { it.toInt(16).toByte() }
                        .toByteArray()
                }.getOrNull()
            } else {
                // Append newline like a real terminal. Goes through the
                // text language layer (RawBytes today: exactly what was typed).
                viewModel.sppTextLanguage.encode(input + "\n")
            }
            if (bytes != null) {
                val sent = viewModel.sendSpp(target.id, bytes)
                sendNote = plainSendResult(sent, bytes.size)
                input = ""
            } else {
                sendNote = plainError("invalid hex payload")
            }
        }
        // Package 2 (A-18) — one tap to see if the device answers in
        // text at all, without typing anything first.
        TerminalButton("Send a test hello") {
            val bytes = viewModel.sppTextLanguage.encode("HELLO\n")
            val sent = viewModel.sendSpp(target.id, bytes)
            sendNote = plainSendResult(sent, bytes.size)
        }
        if (sendNote.isNotEmpty()) {
            Text(sendNote, color = Dim, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Text("— Conversation log —", color = Dim, fontFamily = FontFamily.Monospace)
        Text(
            "TX means what you sent. RX means what the device sent back.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        logLines.takeLast(50).forEach { line ->
            Text(
                line,
                color = when {
                    line.contains("TX:") -> Accent
                    line.contains("RX") -> Color(0xFF7EE787)
                    else -> Dim
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * PRINT — print plain words on a Bluetooth printer.
 * No codes needed: the app learns how the printer talks by trying each known
 * printer language and asking whether anything printed. The winning language
 * is remembered for that printer.
 */
@Composable
private fun PrinterPanel(viewModel: GhostTapViewModel, target: ScanTarget) {
    val tick = viewModel.uiTick
    val connected = remember(target.id, tick) { viewModel.isSppConnected(target.id) }
    val address = target.identity["address"] as? String ?: ""
    val lockedId = remember(address, tick) { viewModel.lockedPrinterLang[address] }
    val probeActive = viewModel.printerProbeActive
    val probeIdx = viewModel.printerProbeIndex
    val probeNote = viewModel.printerProbeNote
    var printText by remember { mutableStateOf("") }
    var printNote by remember { mutableStateOf("") }

    PanelBox("PRINT") {
        Text(
            "Type normal words, tap PRINT, and they come out on paper. No codes needed — the app learns how your printer talks.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (!connected) {
            Text(
                "Connect with Try text link first.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            return@PanelBox
        }
        if (lockedId == null) {
            Text(
                "The app doesn't know your printer's language yet. Find printer language tries each one and asks you whether anything printed.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (!probeActive) {
                TerminalButton("Find printer language") { viewModel.startPrinterProbe(target.id) }
            } else {
                val n = (probeIdx ?: 0) + 1
                Text(
                    "Trying printer language $n of ${ALL_LANGUAGES.size} — did anything print?",
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerminalButton("Yes, it printed") { viewModel.answerPrinterProbe(target.id, true) }
                    TerminalButton("No, nothing printed") { viewModel.answerPrinterProbe(target.id, false) }
                }
                // Package 2 (A-19) — the probe used to have no way out
                // except answering every language. "No, nothing printed"
                // already skips to the next language; Stop ends the probe.
                Spacer(modifier = Modifier.height(4.dp))
                TerminalButton("Stop") { viewModel.cancelPrinterProbe() }
            }
            if (probeNote.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    probeNote,
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            OutlinedTextField(
                value = printText,
                onValueChange = { printText = it },
                label = { Text("Words to print") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("Print") {
                    if (printText.isBlank()) {
                        printNote = "Type something first."
                    } else {
                        val ok = viewModel.printTextWithCheck(target.id, printText)
                        printNote = if (ok) "Sent to the printer." else "Couldn't send — check the connection."
                        // Package 3 (A-19): keep the words in the box — if
                        // the user answers "No, nothing printed", the retry
                        // is one tap. The box clears when they answer Yes.
                    }
                }
                TerminalButton("Forget and find again") { viewModel.forgetPrinterLanguage(target.id) }
            }
            // Package 3 (A-19 / D-50): one "Did it print?" check after a
            // successful print. Yes clears it; No tries the next printer
            // language once.
            val printCheck = viewModel.printCheckPending?.takeIf { it.targetId == target.id }
            if (printCheck != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Did it print?",
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerminalButton("Yes, it printed") {
                        viewModel.answerPrintCheck(target.id, true)
                        printText = ""
                        printNote = "Printed — all good."
                    }
                    TerminalButton("No, nothing printed") {
                        viewModel.answerPrintCheck(target.id, false)
                        printNote = "Trying the next printer language — watch the paper."
                    }
                }
            }
            if (printNote.isNotEmpty()) {
                Text(
                    printNote,
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (probeNote.isNotEmpty()) {
                Text(
                    probeNote,
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * WHAT IS THIS DEVICE? — plain-English verdict for a BLE result.
 * Every piece of identification Android provided is translated; raw MACs,
 * UUIDs and bytes stay under TECHNICAL DETAILS. Uncertain results get a
 * HELP IDENTIFY button that narrates the full analysis. Never invents.
 */
@Composable
private fun WhatIsThisDevicePanel(
    viewModel: GhostTapViewModel,
    target: ScanTarget,
    onJumpToPanel: (String) -> Unit = {}
) {
    val tick = viewModel.uiTick
    var showHelp by remember { mutableStateOf(false) }
    var showTechnical by remember { mutableStateOf(false) }
    val proximity = remember(target.id, tick) { viewModel.deviceProximity(target.id) }
    val needsHelp = remember(target.id, tick) { viewModel.needsHelpIdentify(target.id) }
    val deep = remember(target.id, tick, showHelp) {
        if (showHelp) viewModel.explainDeviceDeep(target.id) else emptyList()
    }
    val summary = remember(target.id, tick) { viewModel.deviceSummary(target.id) }
    val interestingChannel = summary.mostInterestingChannel

    PanelBox("WHAT IS THIS DEVICE?") {
        Text(
            "Probably: ${target.likely ?: "Unknown device"}",
            color = Accent,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "Confidence: ${target.confidence ?: "Low"}",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
        Text(
            "Signal: $proximity" + (target.rssi?.let { "" } ?: ""),
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text("Why:", color = Accent, fontFamily = FontFamily.Monospace)
        if (target.evidence.isEmpty()) {
            Text("No clues yet — tap Help identify below.", color = Dim, fontFamily = FontFamily.Monospace)
        } else {
            target.evidence.take(4).forEach { ev ->
                Text("· $ev", color = Dim, fontFamily = FontFamily.Monospace)
            }
        }
        if (needsHelp) {
            Spacer(modifier = Modifier.height(6.dp))
            TerminalButton(if (showHelp) "Hide analysis" else "Help identify") {
                showHelp = !showHelp
            }
            if (showHelp) {
                deep.forEach { line ->
                    Text(line, color = Dim, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            // Package 2 (A-10) — when the verdict is uncertain, the next
            // step is a concrete probe, not just reading the analysis.
            Spacer(modifier = Modifier.height(6.dp))
            Text("WHAT TO TRY NEXT", color = Accent, fontFamily = FontFamily.Monospace)
            Text(
                nextStepForUnknownDevice(target.connected, summary.mostInteresting),
                color = Dim, fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (!target.connected) {
                TerminalButton("Connect and check again") { viewModel.connectBle(target.id) }
            } else if (interestingChannel != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerminalButton("Listen to it and learn") {
                        viewModel.listenToChannel(target.id, interestingChannel.first, interestingChannel.second)
                        viewModel.startLearning(target.id)
                    }
                    TerminalButton("See it on the map") { onJumpToPanel("map") }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        TerminalButton(if (showTechnical) "Hide technical details" else "Technical details") {
            showTechnical = !showTechnical
        }
        if (showTechnical) {
            target.identity.forEach { (k, v) ->
                Text(
                    "${prettyIdentityKey(k)}: ${translateIdentityValue(k, v)}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// v1.7 multitool panels: Action Engine, MAP INTERFACE, NFC, APDU console.
// ======================================================================

private fun actionPanelName(key: String): String = when (key) {
    "map" -> "MAP INTERFACE"
    "apdu" -> "APDU CONSOLE"
    "raw" -> "RAW"
    "spp_terminal" -> "SEND TEXT TO THIS DEVICE"
    "learned" -> "LEARNED CONTROLS (in GUIDE above)"
    else -> key.uppercase()
}

/**
 * WHAT CAN GHOST TAP DO? — the shared Action Engine rendered as a panel.
 * Every row is a real operation: ✓ runs it or points at its control,
 * ✗ explains honestly why it isn't available. No fake controls.
 *
 * Package 2 (A-12): rows that open a panel now JUMP to it through the
 * shared panel runner instead of only pointing down at it. With
 * [compact] the same list renders as the "DO THIS FIRST" shortlist at
 * the top of the target screen.
 */
@Composable
private fun ActionsPanel(
    viewModel: GhostTapViewModel,
    target: ScanTarget,
    compact: Boolean = false,
    onJumpToPanel: (String) -> Unit = {},
    onShowDetails: (() -> Unit)? = null
) {
    val tick = viewModel.uiTick
    val actions = remember(target.id, tick) { viewModel.deviceActions(target.id) }
    var result by remember { mutableStateOf("") }
    var inputAction by remember { mutableStateOf<DeviceAction?>(null) }
    var inputText by remember { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<String?>(null) }
    if (actions.isEmpty() && onShowDetails == null) return

    PanelBox(if (compact) "DO THIS FIRST" else "WHAT CAN GHOST TAP DO?") {
        actions.forEach { action ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        result = ""
                        if (!action.available) {
                            result = "✗ ${action.label}: ${action.unavailableWhy}"
                            return@clickable
                        }
                        when {
                            action.id == "nfc_write_text" || action.id == "nfc_write_url" -> {
                                inputAction = action
                                inputText = ""
                                confirmAction = null
                            }
                            action.id == "nfc_erase" || action.id == "nfc_format" -> {
                                if (confirmAction == action.id) {
                                    confirmAction = null
                                    result = viewModel.performDeviceAction(target.id, action.id)
                                } else {
                                    confirmAction = action.id
                                    result = "Tap ${action.label} again to confirm — this changes the card."
                                }
                            }
                            action.opensPanel != null -> {
                                result = "→ See ${actionPanelName(action.opensPanel!!)} below ↓"
                                // Package 2 (A-12) — jump to the panel
                                // instead of leaving the user to scroll
                                // and find it. "learned" lives in GUIDE.
                                val jumpKey = when (action.opensPanel) {
                                    "learned" -> "guide"
                                    "map", "apdu", "spp_terminal", "raw" -> action.opensPanel!!
                                    else -> null
                                }
                                if (jumpKey != null) onJumpToPanel(jumpKey)
                            }
                            else -> {
                                result = viewModel.performDeviceAction(target.id, action.id)
                            }
                        }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (action.available) "✓" else "✗",
                    color = if (action.available) Accent else Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(action.label, color = Accent, fontFamily = FontFamily.Monospace)
                    if (!compact) {
                        Text(
                            if (action.available) action.detail else action.unavailableWhy,
                            color = Dim,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            if (inputAction?.id == action.id) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text(if (action.id == "nfc_write_url") "Web link to write" else "Words to write") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerminalButton("Send") {
                        result = if (action.id == "nfc_write_url") {
                            if (viewModel.nfcWriteUrl(inputText)) "Link written onto the card."
                            else "Write failed — tap a card you can write to and try again."
                        } else {
                            if (viewModel.writeNdefText(inputText)) "Words written onto the card."
                            else "Write failed — tap a card you can write to and try again."
                        }
                        inputAction = null
                    }
                    TerminalButton("Cancel") { inputAction = null }
                }
            }
        }
        // Package 2 (A-20) — the technical detail panels are one tap
        // behind "Show more options" on BLE targets; the shortlist offers
        // that tap up front instead of at the bottom of a long screen.
        if (compact && onShowDetails != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowDetails() }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "→",
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("See the technical details", color = Accent, fontFamily = FontFamily.Monospace)
                    Text(
                        "IDENTIFY, CAPABILITIES, WATCH, RAW, and the log.",
                        color = Dim,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        if (result.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(result, color = Accent, fontFamily = FontFamily.Monospace)
        }
    }
}

/**
 * MAP INTERFACE — hands-on endpoint lab for a connected BLE device.
 * Every discovered characteristic gets READ / WRITE / SUBSCRIBE /
 * UNSUBSCRIBE with HEX+ASCII display and response history, plus multi-round
 * TEST captures that surface repeatable byte changes.
 */
@Composable
private fun MapInterfacePanel(viewModel: GhostTapViewModel, target: ScanTarget) {
    val tick = viewModel.uiTick
    val discovered = remember(target.id, tick) { viewModel.discoveredServices(target.id) }
    val roundActive = viewModel.mapRoundActiveFor(target.id)
    val captures = remember(target.id, tick) { viewModel.mapCapturesFor(target.id) }
    val correlations = remember(target.id, tick) { viewModel.mapCorrelations(target.id) }
    var status by remember { mutableStateOf("") }

    PanelBox("MAP INTERFACE") {
        Text(
            "Figure out what each connection does — read it, write to it, watch it live. Nothing here is pretend.",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (roundActive) {
                TerminalButton("Stop ${viewModel.mapRoundLabelFor(target.id) ?: "Test"}") {
                    val cap = viewModel.stopMapRound(target.id)
                    status = when {
                        cap == null -> "No capture running."
                        cap.changes.isEmpty() -> "${cap.label}: no changes observed — try the action again."
                        else -> "${cap.label}: ${cap.changes.size} connection(s) changed."
                    }
                }
            } else {
                TerminalButton("Start test") {
                    status = viewModel.startMapRound(target.id)
                }
            }
            if (captures.isNotEmpty()) {
                TerminalButton("Clear") {
                    viewModel.clearMapCaptures(target.id)
                    status = "Capture history cleared."
                }
            }
        }
        if (status.isNotBlank()) {
            Text(status, color = Accent, fontFamily = FontFamily.Monospace)
        }
        // Package 2 (A-16) — a map round used to run with no word on what
        // to do while it runs. Say it, plainly, while it runs.
        if (roundActive) {
            Text(
                "Test running — now do ONE thing on the real device (press a button, flip a switch, trigger a sensor), then tap Stop.",
                color = Accent, fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        captures.forEach { cap ->
            Text(
                "${cap.label}: " + if (cap.changes.isEmpty()) "no changes"
                else cap.changes.joinToString(" | ") { ch ->
                    val nm = SigUuids.characteristicName(ch.charUuid)
                        ?: SigUuids.shortForm(ch.charUuid)
                    "$nm → ${ch.byteChanges}"
                },
                color = Dim,
                fontFamily = FontFamily.Monospace
            )
        }
        if (correlations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("REPEATABLE SIGNAL:", color = Accent, fontFamily = FontFamily.Monospace)
            correlations.forEach { c ->
                Text("· $c", color = Dim, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        discovered.forEach { svc ->
            svc.characteristics.forEach { ch ->
                MapEndpointRow(
                    viewModel = viewModel,
                    targetId = target.id,
                    serviceUuid = svc.uuid,
                    charUuid = ch.uuid,
                    properties = ch.properties
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        if (discovered.isEmpty()) {
            Text("Connect first — connections appear after the device shares what it can do.", color = Dim, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun MapEndpointRow(
    viewModel: GhostTapViewModel,
    targetId: String,
    serviceUuid: String,
    charUuid: String,
    properties: List<String>
) {
    val tick = viewModel.uiTick
    var writeInput by remember { mutableStateOf("") }
    var asciiMode by remember { mutableStateOf(false) }
    // Package 3 (A-15) — same as BleGattPanel: the toggle reads the
    // ViewModel's notify state instead of a local flag.
    val subscribed = viewModel.isNotifyOn(targetId, serviceUuid, charUuid)
    var showHistory by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    val history = remember(targetId, charUuid, tick, showHistory) {
        viewModel.endpointHistoryFor(targetId).filter { it.charUuid == charUuid }.take(5)
    }
    val latest = remember(targetId, charUuid, tick) {
        viewModel.endpointHistoryFor(targetId).firstOrNull { it.charUuid == charUuid }
    }
    val readable = "READ" in properties
    val writable = "WRITE" in properties || "WRITE_NO_RESPONSE" in properties
    val notifiable = "NOTIFY" in properties || "INDICATE" in properties

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101418), RoundedCornerShape(6.dp))
            .padding(8.dp)
    ) {
        UuidTitle(charUuid, false)
        Text(
            "Can do: " + properties.joinToString(" · ") {
                when (it) {
                    "READ" -> "READ (Ghost Tap can read this)"
                    "WRITE", "WRITE_NO_RESPONSE" -> "WRITE (Ghost Tap can send data here)"
                    "NOTIFY", "INDICATE" -> "LIVE (device can push updates)"
                    else -> it
                }
            },
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
        if (subscribed) {
            Text(
                "● Live now" + (latest?.let { " — latest: ${it.hex}" } ?: ""),
                color = Accent,
                fontFamily = FontFamily.Monospace
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (readable) {
                TerminalButton("Read") {
                    viewModel.bleRead(targetId, serviceUuid, charUuid)
                    result = "Read requested — answer lands below."
                }
            }
            if (notifiable) {
                TerminalButton(if (subscribed) "Turn off live updates" else "Turn on live updates") {
                    val next = !subscribed
                    viewModel.bleSetNotify(targetId, serviceUuid, charUuid, next)
                    result = if (next) "Live updates on — new readings appear above."
                    else "Live updates off."
                }
            }
            TerminalButton(if (showHistory) "Hide history" else "History") {
                showHistory = !showHistory
            }
        }
        if (writable) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = writeInput,
                onValueChange = { writeInput = it },
                label = { Text(if (asciiMode) "Plain words to send" else "Codes to send") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton(if (asciiMode) "Words ✓" else "Code ✓") {
                    asciiMode = !asciiMode
                }
                TerminalButton("Send") {
                    result = viewModel.bleWritePayload(targetId, serviceUuid, charUuid, writeInput, asciiMode)
                }
            }
            Text(
                if (asciiMode) "Sending as plain words."
                else "Sending as code pairs (e.g. A1 03).",
                color = Dim,
                fontFamily = FontFamily.Monospace
            )
        }
        if (showHistory) {
            if (history.isEmpty()) {
                Text("No replies recorded on this connection yet.", color = Dim, fontFamily = FontFamily.Monospace)
            }
            history.forEach { h ->
                Text("${h.kind}: ${h.hex}", color = Dim, fontFamily = FontFamily.Monospace)
                h.ascii?.let { Text("  as text: $it", color = Dim, fontFamily = FontFamily.Monospace) }
            }
        }
        if (result.isNotBlank()) {
            Text(result, color = Accent, fontFamily = FontFamily.Monospace)
        }
    }
}

/** NFC DETECTED — plain-English readout of everything Android reports about the tag. */
@Composable
private fun NfcDetectedPanel(
    viewModel: GhostTapViewModel,
    target: ScanTarget,
    onJumpToPanel: (String) -> Unit = {}
) {
    val tick = viewModel.uiTick
    val facts = remember(target.id, tick) { viewModel.nfcFactsFor(target.id) } ?: return
    val ops = remember(target.id, tick) { viewModel.nfcOpsFor(target.id) }

    PanelBox("NFC DETECTED") {
        Text("Likely type: ${facts.likelyType}", color = Accent, fontFamily = FontFamily.Monospace)
        Text(
            "Card type: ${facts.techs.joinToString(", ") { friendlyTechName(it) }}",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
        Text("Card ID: ${facts.uidHex}", color = Dim, fontFamily = FontFamily.Monospace)
        Text(
            "Can write to it: ${if (facts.ndefWritable) "Yes — Ghost Tap can write to this card" else "No"}",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
        Text("Stored data: ${facts.ndefType ?: "none"}", color = Dim, fontFamily = FontFamily.Monospace)
        facts.capacityLine?.let {
            Text("Memory: $it", color = Dim, fontFamily = FontFamily.Monospace)
        }
        facts.isoDepHistorical?.let {
            Text("Card's answer (first bytes): $it", color = Dim, fontFamily = FontFamily.Monospace)
        }
        facts.isoDepHiLayer?.let {
            Text("Card's answer (extra bytes): $it", color = Dim, fontFamily = FontFamily.Monospace)
        }
        Text(
            "Things you can do: ${ops.joinToString(", ")}",
            color = Accent,
            fontFamily = FontFamily.Monospace
        )
        // Package 4 (D-33) — an unknown card gets a concrete next step
        // instead of a dead end.
        unknownCardNextStep(facts.likelyType)?.let {
            Text(it, color = Dim, fontFamily = FontFamily.Monospace)
        }
        if (viewModel.hasSavedNdef()) {
            Text("Saved records ready to write:", color = Accent, fontFamily = FontFamily.Monospace)
            viewModel.savedNdefSummary().lines().take(4).forEach { line ->
                Text("· $line", color = Dim, fontFamily = FontFamily.Monospace)
            }
        }
        // Package 2 (A-21) — this panel used to be read-only, with every
        // action a screen away. The actions are on this same screen, in
        // What Ghost Tap can do below — jump straight there.
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "What you can do with this card is listed in What Ghost Tap can do below.",
            color = Dim, fontFamily = FontFamily.Monospace
        )
        TerminalButton("See what you can do") { onJumpToPanel("actions") }
    }
}

/**
 * APDU CONSOLE — raw smart-card commands for user-owned/test IsoDep cards.
 * Shows COMMAND / RESPONSE / SW1-SW2 / plain-English meaning / HEX + ASCII.
 * Cards that need authentication answer 69 82 — reported, never bypassed.
 */
@Composable
private fun ApduConsolePanel(viewModel: GhostTapViewModel, target: ScanTarget) {
    val tick = viewModel.uiTick
    var apduInput by remember { mutableStateOf("00 A4 04 00") }
    var aidInput by remember { mutableStateOf("") }
    var breakdown by remember { mutableStateOf<List<String>>(emptyList()) }
    val history = remember(target.id, tick) { viewModel.isoDepHistoryFor(target.id) }

    PanelBox("APDU CONSOLE") {
        Text("Card: smart card", color = Dim, fontFamily = FontFamily.Monospace)
        Text(
            "Direct card commands. Answers come from the real card — never made up.",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = apduInput,
            onValueChange = { apduInput = it },
            label = { Text("Card command code") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("Explain") {
                breakdown = viewModel.isoDepDescribeCommand(apduInput)
            }
            TerminalButton("Send to card") {
                viewModel.isoDepTransceive(target.id, apduInput)
                // Package 2 (A-22) — sending also explains, so one tap
                // gives the answer and what it means.
                breakdown = viewModel.isoDepDescribeCommand(apduInput)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("Quick commands:", color = Accent, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("Pick stored data") { apduInput = "00 A4 04 00 07 D2 76 00 00 85 01 01 00" }
            TerminalButton("Read data") { apduInput = "00 B0 00 00 00" }
            TerminalButton("Get info") { apduInput = "00 CA 00 00 00" }
        }
        // Package 2 (A-22) — guided "what to try next" for an unknown
        // smart card: the first quick check not tried yet, with its
        // explanation filled in. Tried checks are read from the history.
        // Package 4 (B-042 / D-47) — the list lives in the shared
        // NfcTagSession helper now, so this panel and the
        // `nfc.try-next-command` operation can never drift apart.
        val triedChecks = remember(history) {
            history.map { it.commandHex.replace(" ", "").uppercase() }.toSet()
        }
        val nextCheck = nextApduCheck(triedChecks)
        if (nextCheck != null) {
            Spacer(modifier = Modifier.height(4.dp))
            TerminalButton("Try the next check: ${nextCheck.first}") {
                apduInput = nextCheck.second
                breakdown = viewModel.isoDepDescribeCommand(nextCheck.second)
            }
        } else if (history.isNotEmpty()) {
            Text(
                "All three quick checks tried — the answers are in the history below.",
                color = Dim, fontFamily = FontFamily.Monospace
            )
        }
        breakdown.forEach { line ->
            Text("· $line", color = Dim, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = aidInput,
                onValueChange = { aidInput = it },
                label = { Text("App ID code") },
                modifier = Modifier.weight(1f)
            )
            TerminalButton("Pick app") {
                viewModel.isoDepSelectAid(target.id, aidInput)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        history.reversed().take(8).forEach { r ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101418), RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Text("Sent: ${r.commandHex}", color = Dim, fontFamily = FontFamily.Monospace)
                Text(
                    "Reply: ${r.responseHex.ifBlank { "—" }}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace
                )
                Text("Card's result code: ${r.statusHex}", color = Accent, fontFamily = FontFamily.Monospace)
                Text("Meaning: ${r.statusMeaning}", color = Accent, fontFamily = FontFamily.Monospace)
                r.ascii?.let {
                    Text("As text: $it", color = Dim, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

// Plain-English interpreter + guided control (BLE).
// Simple interpretation first (GUIDE); hands-on CONTROL + RAW stay visible;
// IDENTIFY / CAPABILITIES / WATCH / LOG live under ADVANCED.
// ======================================================================

/**
 * Friendly UUID title: recognized Bluetooth features show their plain
 * name. Custom features get a plain-English explanation; the raw UUID
 * stays under Advanced Details.
 */
@Composable
private fun UuidTitle(uuid: String, isService: Boolean) {
    val name = if (isService) SigUuids.serviceName(uuid) else SigUuids.characteristicName(uuid)
    var showRaw by remember { mutableStateOf(false) }
    if (name != null) {
        Text(name, color = Accent, fontFamily = FontFamily.Monospace)
        Text(
            "A standard Bluetooth feature.",
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        Text(
            "Custom ${if (isService) "group of features" else "feature"}",
            color = Accent,
            fontFamily = FontFamily.Monospace
        )
        Text(
            plainUuidName(uuid),
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
    TerminalButton(if (showRaw) "Hide technical ID" else "Show technical ID") {
        showRaw = !showRaw
    }
    if (showRaw) {
        Text(
            uuid,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GuidePanel(viewModel: GhostTapViewModel, target: ScanTarget) {
    val tick = viewModel.uiTick
    val summary = remember(tick) { viewModel.deviceSummary(target.id) }

    PanelBox("GUIDE") {
        // 1. WHAT DID I FIND?
        Text("WHAT WE FOUND", color = Accent, fontFamily = FontFamily.Monospace)
        if (summary.totalServices == 0) {
            Text(
                if (summary.connected) "Connected — reading the device's functions…"
                else "Not connected yet. Tap Connect and Ghost Tap will map this device's functions in plain words.",
                color = Dim, fontFamily = FontFamily.Monospace
            )
        } else {
            Text(
                "This device shares ${summary.totalServices} Bluetooth functions.",
                color = Dim, fontFamily = FontFamily.Monospace
            )
            Text(
                "${summary.standardServices} are standard Bluetooth functions. " +
                    "${summary.customServices} look made for this device.",
                color = Dim, fontFamily = FontFamily.Monospace
            )
            Text(
                "Best guess at device type: ${summary.deviceType} (${summary.typeConfidence} confidence)",
                color = Dim, fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. WHAT DOES IT PROBABLY MEAN?
        if (summary.mostInteresting != null) {
            Text("WHAT IT PROBABLY MEANS", color = Accent, fontFamily = FontFamily.Monospace)
            Text("Most interesting: ${summary.mostInteresting}", color = Accent, fontFamily = FontFamily.Monospace)
            Text(
                "Why it matters: ${summary.mostInterestingWhy ?: ""}",
                color = Dim, fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Device map (tappable hierarchy).
        if (summary.totalServices > 0) {
            DeviceMapTree(viewModel = viewModel, target = target)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 3. WHAT SHOULD I DO NEXT?
        Text("WHAT TO DO NEXT", color = Accent, fontFamily = FontFamily.Monospace)
        val channel = summary.mostInterestingChannel
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (channel != null) {
                TerminalButton("Listen and learn") {
                    viewModel.listenToChannel(target.id, channel.first, channel.second)
                    viewModel.startLearning(target.id)
                }
            } else if (!summary.connected) {
                val tick = viewModel.uiTick
                val gattState = remember(target.id, tick) { viewModel.gattStatusText(target.id) }
                if (gattState.startsWith("CONNECTING")) {
                    TerminalButton("Cancel") { viewModel.cancelBleConnect(target.id) }
                } else {
                    TerminalButton("Connect") { viewModel.connectBle(target.id) }
                }
            }
        }
        if (channel == null && summary.connected) {
            Text("Waiting for the device to share what it can do…", color = Dim, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(8.dp))
        LearnPanel(viewModel = viewModel, target = target)
        Spacer(modifier = Modifier.height(8.dp))
        LearnedControlsList(viewModel = viewModel, target = target)
        Spacer(modifier = Modifier.height(8.dp))
        LiveTranslatorFeed(viewModel = viewModel, target = target)
    }
}

/** Tappable simplified hierarchy: standard vs device-specific functions. */
@Composable
private fun DeviceMapTree(viewModel: GhostTapViewModel, target: ScanTarget) {
    val tick = viewModel.uiTick
    val services = remember(tick) { viewModel.discoveredServices(target.id) }
    var expanded by remember { mutableStateOf<String?>(null) }

    Text("DEVICE MAP", color = Accent, fontFamily = FontFamily.Monospace)
    Text(target.name, color = Dim, fontFamily = FontFamily.Monospace,
        maxLines = 1, overflow = TextOverflow.Ellipsis)

    val standard = services.filter { SigUuids.isStandardService(it.uuid) }
    val custom = services.filter { !SigUuids.isStandardService(it.uuid) }

    @Composable
    fun mapRow(key: String, indent: String, label: String, detail: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = if (expanded == key) null else key }
                .padding(vertical = 2.dp)
        ) {
            Text("$indent$label", color = Dim, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (expanded == key) {
                Column(modifier = Modifier.padding(start = 16.dp)) { detail() }
            }
        }
    }

    Text("├── Standard Bluetooth (${standard.size})", color = Dim, fontFamily = FontFamily.Monospace)
    standard.forEach { svc ->
        val name = SigUuids.serviceName(svc.uuid) ?: "Unknown standard function"
        mapRow("std:${svc.uuid}", "│   ├── ", name) {
            UuidTitle(uuid = svc.uuid, isService = true)
            Text("${svc.characteristics.size} features", color = Color.Gray, fontFamily = FontFamily.Monospace)
        }
    }
    Text("└── Device-Specific (${custom.size})", color = Dim, fontFamily = FontFamily.Monospace)
    custom.forEach { svc ->
        mapRow("cus:${svc.uuid}", "    ├── ", "Custom group ${SigUuids.shortForm(svc.uuid)}") {
            Text("Full technical ID: ${svc.uuid}", color = Color.Gray, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            svc.characteristics.forEach { ch ->
                val rank = ChannelRanker.rank(ch.properties)
                val chName = SigUuids.characteristicName(ch.uuid) ?: "Custom feature"
                Text("• $chName — ${rank.label}", color = Accent, fontFamily = FontFamily.Monospace)
                Text(rank.plainEnglish, color = Color.Gray, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/** Learn mode: START LEARNING → perform one action → STOP LEARNING → name it. */
@Composable
private fun LearnPanel(viewModel: GhostTapViewModel, target: ScanTarget) {
    val tick = viewModel.uiTick
    val active = remember(tick) { viewModel.learningActiveFor(target.id) }
    val pending = remember(tick) { viewModel.pendingDiscoveryFor(target.id) }
    var nameInput by remember { mutableStateOf("") }
    var guidance by remember { mutableStateOf("") }

    Text("LEARN MODE", color = Accent, fontFamily = FontFamily.Monospace)
    if (active) {
        Text(
            "Recording… do ONE thing now (press a button, change a setting, trigger a sensor), then stop.",
            color = Accent, fontFamily = FontFamily.Monospace
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("Stop learning") { viewModel.stopLearning(target.id) }
            TerminalButton("Cancel") { viewModel.cancelLearning() }
        }
    } else {
        if (guidance.isNotBlank()) {
            Text(guidance, color = Dim, fontFamily = FontFamily.Monospace)
        }
        TerminalButton("Start learning") {
            guidance = viewModel.startLearning(target.id)
        }
    }

    pending?.let { disc ->
        Spacer(modifier = Modifier.height(6.dp))
        Text("LIKELY DISCOVERY", color = Accent, fontFamily = FontFamily.Monospace)
        if (disc.packetHex.isBlank()) {
            Text(disc.reason, color = Dim, fontFamily = FontFamily.Monospace)
        } else {
            Text("Seen command: ${disc.packetHex}", color = Dim, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Change seen: ${disc.byteChanges}", color = Dim, fontFamily = FontFamily.Monospace)
            Text("Confidence: ${disc.confidence}", color = Dim, fontFamily = FontFamily.Monospace)
            Text("Reason: ${disc.reason}", color = Color.Gray, fontFamily = FontFamily.Monospace)
            // Package 2 (A-11) — suggest a name from the feature that
            // actually changed, instead of a blank box. The user can
            // edit it or keep it; saving still needs their tap.
            val suggestedName = remember(disc) {
                SigUuids.characteristicName(disc.charUuid)?.let { "$it changed" }
            }
            LaunchedEffect(disc) {
                if (suggestedName != null && nameInput.isBlank()) nameInput = suggestedName
            }
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Name this action (e.g. Power ON)") },
                modifier = Modifier.fillMaxWidth()
            )
            if (suggestedName != null) {
                Text(
                    "Suggested from the feature that changed — change it if it's wrong.",
                    color = Dim, fontFamily = FontFamily.Monospace
                )
            }
            TerminalButton("Save learned control") {
                viewModel.saveDiscoveryAsAction(target.id, nameInput)
                nameInput = ""
            }
        }
    }
}

/** Learned controls: replay buttons built only from observed evidence. */
@Composable
private fun LearnedControlsList(viewModel: GhostTapViewModel, target: ScanTarget) {
    val tick = viewModel.uiTick
    val actions = remember(tick) { viewModel.learnedActionsFor(target.id) }
    var replayOutput by remember { mutableStateOf("") }

    Text("LEARNED CONTROLS (${actions.size})", color = Accent, fontFamily = FontFamily.Monospace)
    if (actions.isEmpty()) {
        Text("Nothing learned yet — use Learn mode above.", color = Color.Gray, fontFamily = FontFamily.Monospace)
    }
    actions.forEach { action ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            Text(action.name, color = Accent, fontFamily = FontFamily.Monospace)
            Text("Command seen: ${action.packetHex}", color = Dim, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Source: ${action.source}", color = Color.Gray, fontFamily = FontFamily.Monospace)
            Text("Confidence: ${action.confidence}", color = Color.Gray, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalButton("Play again") {
                    replayOutput = viewModel.replayLearned(target.id, action.id)
                }
                TerminalButton("Forget") {
                    viewModel.removeLearnedAction(target.id, action.id)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
    if (replayOutput.isNotBlank()) {
        Text(replayOutput, color = Accent, fontFamily = FontFamily.Monospace)
    }
}

/** Live activity feed: DEVICE → PHONE / PHONE → DEVICE with interpretations. */
@Composable
private fun LiveTranslatorFeed(viewModel: GhostTapViewModel, target: ScanTarget) {
    val tick = viewModel.uiTick
    val feed = remember(tick) { viewModel.packetFeedFor(target.id).takeLast(30).reversed() }
    var sendNote by remember { mutableStateOf("") }

    Text("LIVE TRANSLATOR", color = Accent, fontFamily = FontFamily.Monospace)
    if (feed.isEmpty()) {
        Text("Nothing yet — tap Listen on a connection or use the device.",
            color = Color.Gray, fontFamily = FontFamily.Monospace)
        return
    }
    feed.forEach { ev ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C0C0C), RoundedCornerShape(4.dp))
                .padding(6.dp)
        ) {
            val arrow = if (ev.direction == "RX") "DEVICE → PHONE" else "PHONE → DEVICE"
            val chName = SigUuids.characteristicName(ev.charUuid) ?: SigUuids.shortForm(ev.charUuid)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(arrow, color = Accent, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                if (ev.repeatCount > 1) {
                    Text("×${ev.repeatCount}", color = Color.Gray, fontFamily = FontFamily.Monospace)
                }
            }
            Text("via $chName", color = Color.Gray, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ev.hex, color = Dim, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Plain-English: what the bytes mean, not just what they look like.
            Text(plainPayload(ev.hex), color = Dim, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            if (ev.repeatCount > 1) {
                Text("Repeated ${ev.repeatCount} times", color = Color.Gray, fontFamily = FontFamily.Monospace)
            }
            if (ev.changedBytes.isNotEmpty() && ev.prevHex != null) {
                Text("Interesting change seen", color = Accent, fontFamily = FontFamily.Monospace)
                val desc = ev.changedBytes.joinToString(", ") { i ->
                    val oldB = ev.prevHex!!.split(" ").getOrNull(i) ?: "??"
                    val newB = ev.hex.split(" ").getOrNull(i) ?: "??"
                    "part $i: $oldB → $newB"
                }
                Text(desc, color = Dim, fontFamily = FontFamily.Monospace)
            }
            Text("Interpretation: ${ev.interpretation}", color = Dim, fontFamily = FontFamily.Monospace)
            Text("Confidence: ${ev.confidence}", color = Color.Gray, fontFamily = FontFamily.Monospace)
            // Package 2 (A-11) — "do that again" right on the event,
            // sending back the exact bytes that were seen.
            TerminalButton("Send this again") {
                sendNote = viewModel.bleWritePayload(target.id, ev.serviceUuid, ev.charUuid, ev.hex, false)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
    if (sendNote.isNotBlank()) {
        Text(sendNote, color = Accent, fontFamily = FontFamily.Monospace)
    }
}

/**
 * CONTROL panel for a BLE target. Shows the live connection state plus every
 * service and characteristic UUID returned by the real GATT discovery, with
 * their actual properties (READ, WRITE, WRITE_NO_RESPONSE, NOTIFY, INDICATE).
 * Tapping a writable characteristic selects it as the RAW command target.
 * Nothing here is simulated: an empty list means the device returned nothing.
 */
@Composable
private fun BleGattPanel(
    viewModel: GhostTapViewModel,
    target: ScanTarget,
    onJumpToPanel: (String) -> Unit = {}
) {
    // Read uiTick so this panel recomposes as discovery progresses.
    val tick = viewModel.uiTick
    val status = viewModel.gattStatusText(target.id)
    val services = remember(tick) { viewModel.discoveredServices(target.id) }
    val selection = remember(tick) { viewModel.rawSelection(target.id) }
    // Package 3 (A-15) — the live-updates toggle reads the ViewModel's
    // notify state instead of a local set, so it can no longer drift
    // from the real subscription state when the screen recomposes.

    Text("Connection: $status", color = Accent, fontFamily = FontFamily.Monospace)

    if (services.isEmpty()) {
        Text(
            if (target.connected) "Connected — waiting for the device to share what it can do…"
            else "Not connected — tap Connect and the device's real functions appear here.",
            color = Dim,
            fontFamily = FontFamily.Monospace
        )
        return
    }

    services.forEach { svc ->
        Spacer(modifier = Modifier.height(4.dp))
        UuidTitle(uuid = svc.uuid, isService = true)
        if (svc.characteristics.isEmpty()) {
            Text("  (no features reported)", color = Dim, fontFamily = FontFamily.Monospace)
        }
        svc.characteristics.forEach { ch ->
            val isWritable = ch.properties.any { it == "WRITE" || it == "WRITE_NO_RESPONSE" }
            val isReadable = ch.properties.contains("READ")
            val isNotifiable = ch.properties.any { it == "NOTIFY" || it == "INDICATE" }
            val isSelected = selection?.first == svc.uuid && selection?.second == ch.uuid
            val notifyEnabled = viewModel.isNotifyOn(target.id, svc.uuid, ch.uuid)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) Color(0xFF14301F) else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .then(
                        if (isWritable) Modifier.clickable {
                            viewModel.selectRawCharacteristic(target.id, svc.uuid, ch.uuid)
                            // Package 2 (A-15) — picking a channel used to
                            // be a separate tap from using it: the pick
                            // lands straight on RAW now.
                            onJumpToPanel("raw")
                        } else Modifier
                    )
                    .padding(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val chName = SigUuids.characteristicName(ch.uuid)
                        if (chName != null) {
                            Text(chName, color = if (isSelected) Accent else Dim, fontFamily = FontFamily.Monospace)
                            Text(
                                SigUuids.shortForm(ch.uuid),
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                ch.uuid,
                                color = if (isSelected) Accent else Dim,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Picked ✓", color = Accent, fontFamily = FontFamily.Monospace, maxLines = 1)
                    }
                }
                Text(
                    ch.properties.joinToString(" ") { "[$it]" }.ifEmpty { "[Nothing reported]" },
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (SigUuids.characteristicName(ch.uuid) == null) {
                    ChannelRanker.capabilityLines(ch.properties).forEach { line ->
                        Text(line, color = Color.Gray, fontFamily = FontFamily.Monospace)
                    }
                    // Package 2 (A-27) — what to DO about this feature,
                    // not just what it is.
                    Text(
                        "Try this next: ${nextProbeFor(ch.properties)}",
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (isWritable && !isSelected) {
                    Text("tap to pick for direct commands", color = Color.Gray, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isReadable) {
                        TerminalButton("Read") { viewModel.bleRead(target.id, svc.uuid, ch.uuid) }
                    }
                    if (isNotifiable) {
                        TerminalButton(if (notifyEnabled) "Live updates off" else "Live updates on") {
                            val next = !notifyEnabled
                            viewModel.bleSetNotify(target.id, svc.uuid, ch.uuid, next)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogsScreen(
    entries: List<LogEntry>,
    targets: List<ScanTarget>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> java.io.File?
) {
    var exportResult by remember { mutableStateOf("") }
    // Package 2 (A-07) — clock times instead of millisecond numbers, and
    // filters by device and category. The stored records already carry
    // timestampMs, so no stored schema changes.
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var deviceFilter by remember { mutableStateOf<String?>(null) }
    val nameById = remember(targets) {
        targets.associate { it.id to it.name.ifBlank { it.id } }
    }
    val categories = remember(entries) { entries.map { it.category }.distinct() }
    val deviceIds = remember(entries) { entries.mapNotNull { it.targetId }.distinct() }
    val shown = entries.filter { e ->
        (categoryFilter == null || e.category == categoryFilter) &&
            (deviceFilter == null || e.targetId == deviceFilter)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalButton("Back", onBack)
            TerminalButton("Clear", onClear)
            TerminalButton("Save to file") {
                val file = onExport()
                exportResult = file?.absolutePath ?: "Couldn't save the file"
            }
        }
        if (exportResult.isNotBlank()) {
            Text(exportResult, color = Accent, fontFamily = FontFamily.Monospace)
        }
        if (categories.isNotEmpty()) {
            PanelBox("FILTER BY TYPE") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerminalButton(if (categoryFilter == null) "> All" else "All") {
                        categoryFilter = null
                    }
                    categories.forEach { cat ->
                        TerminalButton(if (categoryFilter == cat) "> $cat" else cat) {
                            categoryFilter = if (categoryFilter == cat) null else cat
                        }
                    }
                }
            }
        }
        if (deviceIds.isNotEmpty()) {
            PanelBox("FILTER BY DEVICE") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TerminalButton(if (deviceFilter == null) "> All devices" else "All devices") {
                        deviceFilter = null
                    }
                    deviceIds.forEach { id ->
                        val label = nameById[id] ?: id
                        TerminalButton(if (deviceFilter == id) "> $label" else label) {
                            deviceFilter = if (deviceFilter == id) null else id
                        }
                    }
                }
            }
        }
        PanelBox("LOG") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (shown.isEmpty()) {
                    Text(
                        if (entries.isEmpty()) "No log entries yet."
                        else "Nothing matches these filters.",
                        color = Dim, fontFamily = FontFamily.Monospace
                    )
                }
                shown.forEach { e ->
                    val time = timeFormat.format(java.util.Date(e.timestampMs))
                    val who = e.targetId?.let { nameById[it] ?: it } ?: "-"
                    Text(
                        "$time [${e.category}] ($who) ${e.message}",
                        color = Dim, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/** Heuristic: NFC op functions return plain-English failure sentences on failure. */
internal fun looksLikeNfcFailure(msg: String): Boolean {
    val m = msg.lowercase()
    return listOf(
        "fail", "no tag", "not in range", "unavailable", "can't", "cannot",
        "error", "nothing", "type some", "type a", "not an ", "not writable",
        "invalid", "unexpected"
    ).any { it in m }
}

@Composable
internal fun TerminalButton(text: String, onClick: () -> Unit) {
    TerminalButton(text, onClick, enabled = true)
}

@Composable
internal fun TerminalButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF161616),
            contentColor = Accent
        )
    ) {
        Text(text, fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun PanelBox(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Accent, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        Spacer(modifier = Modifier.height(6.dp))
        content()
    }
}

// Package 2 (A-05) — the baseline diff names the devices, not just
// counts them. Names for devices still around come from the live list;
// names for gone devices come from the saved snapshot. A new device
// also raises a plain-words alert line.
private fun formatBaselineDiff(viewModel: GhostTapViewModel, snapshot: BaselineSnapshot?): String {
    val diff = viewModel.baselineDiff
    val currentNames = viewModel.allTargets.associate { it.id to it.name.ifBlank { it.id } }
    fun names(ids: List<String>): String {
        if (ids.isEmpty()) return "none"
        return ids.joinToString(", ") { id ->
            currentNames[id]
                ?: snapshot?.targets?.get(id)?.name?.ifBlank { id }
                ?: id
        }
    }
    return buildString {
        appendLine("New devices (${diff.newTargets.size}): ${names(diff.newTargets)}")
        appendLine("Gone (${diff.missingTargets.size}): ${names(diff.missingTargets)}")
        appendLine("Changed (${diff.changedTargets.size}): ${names(diff.changedTargets)}")
        appendLine("Signal changed (${diff.signalChangedTargets.size}): ${names(diff.signalChangedTargets)}")
        if (diff.newTargets.isNotEmpty()) {
            appendLine("New device spotted: ${names(diff.newTargets)} — see TARGETS below.")
        }
    }
}
