package com.ghosttap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.core.ops.AreaBState
import com.ghosttap.core.ops.Operation
import com.ghosttap.core.ops.OperationArea
import com.ghosttap.core.ops.OperationRegistry
import com.ghosttap.core.ops.OperationResult
import com.ghosttap.core.ops.OperationRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Package 0b — the per-function "pick what you want to do" screen.
 *
 * ZERO-CONFIG (2026-11-06): this screen shows ONE section only, and
 * there is no standalone global list any more — the "what_to_do"
 * route with no area was REMOVED, and [area] is now required. The
 * content that used to live in the global list lives here, inside
 * each function, already configured for that section's devices.
 * Each row shows the outcome and what it needs. Tapping one runs it
 * through the [OperationRunner] with live step progress shown; a
 * screen-first operation opens its screen through the same runner.
 *
 * Title is the area's own label — no original title to preserve
 * here. The six main-screen buttons keep their original wording.
 */
@Composable
fun OperationEntryScreen(
    runner: OperationRunner,
    onBack: () -> Unit,
    area: OperationArea
) {
    val operations = OperationRegistry.byArea(area)
    val scope = rememberCoroutineScope()
    val progress by runner.progress.collectAsState()
    val result by runner.result.collectAsState()
    var runningId by remember { mutableStateOf<String?>(null) }
    var lastOp by remember { mutableStateOf<Operation?>(null) }
    // Package 2 (A-04) — live count while the "find the strongest" scan
    // runs: the runner only reports step labels, so a one-second ticker
    // re-reads how many devices the scan has found so far.
    var scanTick by remember { mutableStateOf(0) }
    LaunchedEffect(runningId) {
        if (runningId == "scan.find-strongest") {
            while (true) {
                delay(1000)
                scanTick++
            }
        }
    }

    fun startRun(op: Operation) {
        if (runningId != null) return
        runningId = op.id
        lastOp = op
        runner.clearResult()
        scope.launch {
            runner.run(op)
            runningId = null
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
        Text(
            area.label,
            color = Accent,
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace
        )

        TerminalButton("Back", onBack)

        ExplainerCard(
            "Pick what you want to happen. Ghost Tap gets everything ready and does it — you just tap once.",
            "If something is needed first, like Bluetooth or a card, it will tell you in plain words."
        )

        if (runningId != null) {
            PanelBox("WORKING") {
                Text(
                    progress.step.label,
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.step.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = Color(0xFF101010)
                )
                if (runningId == "scan.find-strongest") {
                    val foundSoFar = remember(scanTick) {
                        runner.operationContext.ble?.allTargets?.size ?: 0
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Devices found so far: $foundSoFar",
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Package 3 (B-012/B-013) — live progress of a bulk
                // read/listen run: the operation updates AreaBState as
                // channels finish, and Compose re-reads it here.
                if (runningId == "ble.read-everything" || runningId == "ble.listen-everything") {
                    if (AreaBState.bulkProgressLabel.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            if (AreaBState.bulkProgressTotal > 0) {
                                "${AreaBState.bulkProgressLabel} ${AreaBState.bulkProgressDone} of ${AreaBState.bulkProgressTotal}"
                            } else {
                                AreaBState.bulkProgressLabel
                            },
                            color = Dim,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        val currentResult = result
        if (currentResult != null) {
            PanelBox("RESULT") {
                when (currentResult) {
                    is OperationResult.Done -> {
                        Text(
                            currentResult.reason,
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (currentResult.evidence.isNotBlank()) {
                            Text(
                                currentResult.evidence,
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TerminalButton("Got it") { runner.clearResult() }
                    }
                    is OperationResult.Failed -> {
                        Text(
                            "Didn't work: ${currentResult.reason}",
                            color = Color.Red,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (currentResult.evidence.isNotBlank()) {
                            Text(
                                currentResult.evidence,
                                color = Dim,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TerminalButton("Got it") { runner.clearResult() }
                    }
                    is OperationResult.NeedsUserAction -> {
                        Text(
                            currentResult.prompt,
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (currentResult.actionLabel != null) {
                            TerminalButton(currentResult.actionLabel) {
                                currentResult.action?.invoke()
                                runner.clearResult()
                            }
                        }
                        TerminalButton("Got it") { runner.clearResult() }
                    }
                    is OperationResult.NeedsUserChoice -> {
                        Text(
                            currentResult.reason,
                            color = Accent,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        currentResult.choices.forEach { choice ->
                            TerminalButton(choice) {
                                runner.operationContext.pendingChoice = choice
                                runner.clearResult()
                                lastOp?.let { startRun(it) }
                            }
                        }
                        TerminalButton("Not now") { runner.clearResult() }
                    }
                }
            }
        }

        if (operations.isEmpty()) {
            PanelBox("NOTHING HERE YET") {
                Text(
                    "No operations are registered for this section yet. The tool screens are still on the home screen.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            // One section only — the heading above already says the area,
            // so the rows go straight on the screen with no extra box.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                operations.forEach { op -> OperationRow(op) { startRun(it) } }
            }
        }
    }
}

/**
 * One pickable outcome row. Shared by the all-areas list and the
 * single-area list so both show the same thing.
 */
@Composable
private fun OperationRow(op: Operation, onRun: (Operation) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101010), RoundedCornerShape(6.dp))
            .clickable { onRun(op) }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            op.title,
            color = Accent,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            op.description,
            color = Dim,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
        if (op.prerequisites.isNotEmpty()) {
            Text(
                "Needs: ${op.prerequisites.joinToString(", ")}",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
