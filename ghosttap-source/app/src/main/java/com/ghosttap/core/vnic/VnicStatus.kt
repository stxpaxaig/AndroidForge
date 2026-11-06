package com.ghosttap.core.vnic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.ui.Accent
import com.ghosttap.ui.Dim
import com.ghosttap.ui.PanelBox
import com.ghosttap.ui.TerminalButton

/**
 * VNIC operational visibility — a panel inside the existing Investigations
 * screen, NOT a separate chatbot screen. It shows what the VNIC observed,
 * identified, researched, learned, selected, attempted, verified and
 * retained, plus real runtime telemetry. Reference-profile values are never
 * shown here: the panel reports measured counts only.
 *
 * Package 7 (E-29 / E-31 / F-26 / F-55) — the panel ACTS now, in the
 * same places it used to only report: a device the brain got ready stays
 * visible as "Ready for you" until the user opens its controls or
 * dismisses it (the outcome survives screen changes and restarts); a
 * FAILED bring-up gets a "Try again" button; a CONTROLLABLE device gets
 * an "Open the controls" button. Everything else stays read-only, and
 * the title keeps its original wording.
 */
@Composable
fun VnicStatusPanel(
    engine: VnicEngine,
    /** Ask the brain about the open case file (Investigations screen wires this; null = no button). */
    onAskBrain: (() -> Unit)? = null,
    /** Retry the last FAILED bring-up (null = no button). */
    onRetryBringUp: (() -> Unit)? = null,
    /** Open the controls of the ready device (null = no button). */
    onOpenControls: (() -> Unit)? = null,
    /** Dismiss the "ready for you" note (null = no button). */
    onDismissReady: (() -> Unit)? = null
) {
    val status by engine.status.collectAsState()
    val driveOutcome by engine.driveOutcome.collectAsState()
    PanelBox("VNIC — NEURAL CORE") {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (status.storeBackedByRoom) "Memory: saved on this phone" else "Memory: temporary only (saved memory not available)",
                color = Accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Things it can do: ${status.capabilityCount}  ·  Saved plans: ${status.procedureCount}",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            val counts = status.memoryCounts
            Text(
                "What it remembers — past events ${counts["episodic"] ?: 0} · facts ${counts["semantic"] ?: 0} · how-to steps ${counts["procedural"] ?: 0} · right now ${counts["working"] ?: 0}",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            val t = status.telemetry
            Text(
                "Rounds done ${t["cycles_run"] ?: 0} · lookups ${t["research_queries"] ?: 0} (${t["findings_stored"] ?: 0} things found) · " +
                    "tries ${t["executions_attempted"] ?: 0} · connected ${t["executions_connected"] ?: 0} · checked OK ${t["executions_verified"] ?: 0} · failed ${t["executions_failed"] ?: 0}",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "When the app closes, the brain's practice patterns start fresh — what it remembers about devices and plans stays saved.",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            // Package 7 (E-29 / F-26) — the persistent "ready for you"
            // note: the last drive outcome, kept until opened or
            // dismissed. It is NOT overwritten by the next cycle's
            // status lines — it lives in its own store.
            val outcome = driveOutcome
            if (outcome != null) {
                Spacer(modifier = Modifier.height(6.dp))
                if (outcome.state == "CONTROLLABLE") {
                    Text(
                        "Ready for you: ${outcome.subject}",
                        color = Accent,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    outcome.controls.forEach { c ->
                        Text(
                            "• $c",
                            color = Dim,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (onOpenControls != null) {
                        TerminalButton("Open the controls", onOpenControls)
                    }
                    if (onDismissReady != null) {
                        TerminalButton("Dismiss", onDismissReady)
                    }
                } else if (outcome.state == "FAILED") {
                    Text(
                        "Couldn't get it ready: ${outcome.subject} — ${outcome.evidence.take(200)}",
                        color = Color(0xFFFFAA44),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (onRetryBringUp != null) {
                        TerminalButton("Try again", onRetryBringUp)
                    }
                    if (onDismissReady != null) {
                        TerminalButton("Dismiss", onDismissReady)
                    }
                } else {
                    Text(
                        "Last try: ${outcome.subject} — ${outcome.state.lowercase()}",
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (onDismissReady != null) {
                        TerminalButton("Dismiss", onDismissReady)
                    }
                }
            } else if (status.lastState == "FAILED" && status.lastSubject.isNotBlank() && onRetryBringUp != null) {
                Spacer(modifier = Modifier.height(6.dp))
                TerminalButton("Try again", onRetryBringUp)
            }
            if (status.lastSubject.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Last thing it looked at: ${status.lastSubject}",
                    color = Accent,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "What it did: ${status.lastAction} · how it went: ${status.lastState} · how sure: ${"%.2f".format(status.lastConfidence)} · took ${status.lastCycleMs} ms",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                status.lastFindings.take(3).forEach { f ->
                    Text(
                        "• $f",
                        color = Color(0xFFFFAA44),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                status.lastPhases.takeLast(4).forEach { p ->
                    Text(
                        "  $p",
                        color = Dim,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (status.lastBringUpRoute.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Getting it ready: how it got in=${status.lastBringUpRoute} · still connected=${if (status.lastBringUpHeld) "Yes" else "No"}",
                        color = if (status.lastBringUpHeld) Accent else Color(0xFFFFAA44),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    status.lastBringUpControls.forEach { c ->
                        Text(
                            "• $c",
                            color = Dim,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                Text(
                    "Nothing checked yet. The app's built-in brain records every round automatically and can look up anything on demand.",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // Package 7 (E-30 / F-07) — ask the brain from this panel.
            // Only shown when the screen wires it (an open case file to
            // ask about); the button asks about that case file.
            if (onAskBrain != null) {
                Spacer(modifier = Modifier.height(6.dp))
                TerminalButton("Ask the brain", onAskBrain)
            }
        }
    }
}
