package com.ghosttap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ghosttap.IntelViewModel
import com.ghosttap.core.investigate.AiReasoningState
import com.ghosttap.core.investigate.InvestigatedControl
import com.ghosttap.core.investigate.InvestigationSource
import com.ghosttap.core.investigate.LiveTarget
import com.ghosttap.core.investigate.LiveTargetStore
import com.ghosttap.core.investigate.localReasoningText
import com.ghosttap.core.investigate.plainWords
import com.ghosttap.core.ops.OperationRegistry

/**
 * MASTER PROMPT — one LIVE TARGET card.
 *
 * This is what he sees instead of a history entry: a live thing
 * Ghost Tap can connect to right now, with what the thinking engine
 * says about it, what it can do with it, a CONNECT button, and —
 * once connected — the commands he can tap on it.
 *
 * - The thinking-engine line ALWAYS says which engine produced the
 *   answer: his AI, or the app's own rules (with the plain-words
 *   reason why — no key yet, or the AI could not be reached). An
 *   answer from the app's own rules is never shown as the AI's.
 * - "What Ghost Tap can do with it" lists the mapped controls as
 *   plain text, unless the caller wires a real [onControl] — a
 *   control only becomes a button when the caller routes it to an
 *   existing action. Nothing becomes a button with nothing behind it.
 * - CONNECT only ever runs the caller's existing connect path.
 * - The COMMAND CENTER only lists commands from [LiveTarget.commands]
 *   that resolve in the [OperationRegistry]; each tap runs through
 *   the existing OperationRunner and the answer is shown in plain
 *   words underneath. Commands show once the target is connected —
 *   except pictures and typed things, which have nothing to connect
 *   to, so their commands (which act on the finding itself) show
 *   right away. A Wi-Fi network shows no commands until it is joined.
 * - Nothing here connects, reads, writes, or sends on its own. Every
 *   action waits for his tap.
 *
 * READABILITY — every Text here carries an explicit color on purpose
 * (see FindingsReportCard): with no Compose MaterialTheme set, a
 * bare Text() renders near-black on this app's near-black
 * backgrounds and cannot be read. Never remove these colors.
 */
@Composable
fun LiveTargetCard(
    target: LiveTarget,
    intelVm: IntelViewModel?,
    isConnected: Boolean = false,
    onConnect: (() -> Unit)? = null,
    onControl: ((InvestigatedControl) -> Unit)? = null,
    onRunCommand: ((String, String?) -> Unit)? = null,
    onOpenRoute: ((String) -> Unit)? = null,
    commandAnswer: String = ""
) {
    // Re-read the live target from the store, subscribed to the
    // ViewModel's version bump: the ViewModel bumps findingsVersion
    // when a thinking-engine answer lands, so this card refreshes
    // and shows it. Without this read, an AI answer landing would
    // never repaint the card.
    @Suppress("UNUSED_VARIABLE")
    val engineVersion = intelVm?.findingsVersion
    val live = if (intelVm != null) LiveTargetStore.get(target.key) ?: target else target
    val report = live.report
    // Plain non-scrolling Column: every parent screen already scrolls.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(text = "LIVE TARGET — ${report.targetLabel}", color = Accent)
        Text(text = report.targetKind, color = Dim)
        Text(text = report.confidence.plainWords(), color = Dim)

        Spacer(modifier = Modifier.height(12.dp))
        AiReasoningBlock(target = live, intelVm = intelVm)

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "What we think this is:", color = Accent)
        Spacer(modifier = Modifier.height(4.dp))
        if (report.believedIdentity == "UNKNOWN") {
            Text(text = "UNKNOWN — not enough proof to name it", color = Dim)
        } else {
            Text(text = report.believedIdentity, color = Dim)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Why we think so:", color = Accent)
        Spacer(modifier = Modifier.height(4.dp))
        for (line in report.evidence) {
            Text(text = line, color = Dim)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "What Ghost Tap can do with it:", color = Accent)
        Spacer(modifier = Modifier.height(4.dp))
        if (report.capabilities.isEmpty()) {
            Text(text = "Nothing known yet — connect first to find out more", color = Dim)
        } else if (onControl != null) {
            for (control in report.capabilities) {
                Button(
                    onClick = { onControl(control) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(text = control.label, color = Color.White)
                        Text(text = control.plainDescription, color = Color.White)
                        if (control.readOnly) {
                            Text(text = "just reads, changes nothing", color = Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        } else {
            for (control in report.capabilities) {
                Text(text = control.label + " — " + control.plainDescription, color = Dim)
                if (control.readOnly) {
                    Text(text = "just reads, changes nothing", color = Dim)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Risks:", color = Accent)
        Spacer(modifier = Modifier.height(4.dp))
        for (risk in report.risks) {
            Text(text = risk.level.plainWords() + ": " + risk.plainText, color = Dim)
        }

        Spacer(modifier = Modifier.height(16.dp))
        if (onConnect != null) {
            TerminalButton(report.connectLabel) { onConnect() }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Text(
                text = "No direct way to connect to this one from here — open its own screen to connect.",
                color = Dim
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (live.route != null && onOpenRoute != null) {
            TerminalButton("Open its page") { onOpenRoute(live.route) }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ---- COMMAND CENTER ----
        // Shows once connected — except pictures and typed things,
        // which have nothing to connect to, so their commands show
        // right away. A Wi-Fi network has no commands until joined.
        val showCommands = isConnected ||
            live.source == InvestigationSource.VISUAL ||
            live.source == InvestigationSource.MANUAL
        if (showCommands) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "COMMANDS YOU CAN TAP NOW:", color = Accent)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Every button below runs on this device when you tap it. Nothing runs until you tap.",
                color = Dim
            )
            Spacer(modifier = Modifier.height(8.dp))
            // HOLD THE CONNECTION — honestly one-shot: a device on
            // the Wi-Fi network has no per-device link to hold, so
            // each command opens its own check over the network when
            // tapped and nothing stays open between taps. A tap card
            // is the same — each command needs the tag held to the
            // phone when tapped, because there is no link to hold
            // once the tag is gone.
            if (live.source == InvestigationSource.NETWORK &&
                !(live.report.targetId?.startsWith("wifi:") ?: false)
            ) {
                Text(
                    text = "Each command opens its own check over the Wi-Fi network when you tap it — nothing stays open between taps.",
                    color = Dim
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (live.source == InvestigationSource.NFC) {
                Text(
                    text = "Each command needs the tag held to the back of the phone when you tap it — hold it there until it answers.",
                    color = Dim
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            val resolved = live.commands.mapNotNull { id ->
                OperationRegistry.get(id)?.let { id to it }
            }
            if (resolved.isEmpty()) {
                // A Wi-Fi network has no commands of its own: once
                // Ghost Tap is on it, the work happens on the NETWORK
                // TOOLS screen, not on this card.
                val isWifi = live.source == InvestigationSource.NETWORK &&
                    (live.report.targetId?.startsWith("wifi:") ?: false)
                Text(
                    text = if (isWifi && isConnected)
                        "You're on this network now — use NETWORK TOOLS to see the devices on it and what you can do with them."
                    else
                        "No commands here yet — join this network first, then its commands show here.",
                    color = Dim
                )
            } else if (onRunCommand == null) {
                for ((_, op) in resolved) {
                    Text(text = op.title + " — " + op.description, color = Dim)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "Commands are listed here but this screen can't run them — use the Investigations screen to tap one.",
                    color = Dim
                )
            } else {
                for ((id, op) in resolved) {
                    Button(
                        onClick = { onRunCommand(id, live.connectChoice) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(text = op.title, color = Color.White)
                            Text(text = op.description, color = Color.White)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (commandAnswer.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Last answer:", color = Accent)
                Text(text = commandAnswer, color = Dim)
            }
        } else if (onConnect != null) {
            Text(
                text = "Connect first — the commands you can tap on this device show here once you're connected.",
                color = Dim
            )
        }
    }
}

/**
 * Just the thinking-engine answer for one live target — the same
 * block the live card shows, reusable where a screen already renders
 * the report itself (the current photo on the Visual screen).
 *
 * Asks the thinking engine about this target exactly once — the
 * ViewModel guards re-runs (it only reasons while the card is still
 * WAITING). Async, never on the UI thread; the screen shows the
 * local report immediately and the AI answer lands when it arrives,
 * marked with which engine made it. Subscribes to the ViewModel's
 * version bump so the answer repaints the moment it lands.
 */
@Composable
fun AiReasoningBlock(target: LiveTarget, intelVm: IntelViewModel?) {
    LaunchedEffect(target.key) {
        intelVm?.reasonAboutLiveTarget(target.key)
    }
    @Suppress("UNUSED_VARIABLE")
    val engineVersion = intelVm?.findingsVersion
    val live = if (intelVm != null) LiveTargetStore.get(target.key) ?: target else target
    val report = live.report
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "What the thinking engine says:", color = Accent)
        Spacer(modifier = Modifier.height(4.dp))
        when (live.aiState) {
            AiReasoningState.WAITING -> {
                if (intelVm == null) {
                    Text(text = "Checked by the app's own rules.", color = Dim)
                    Text(text = localReasoningText(report), color = Dim)
                } else {
                    Text(text = "Asking the thinking engine…", color = Dim)
                    Text(text = localFallbackLine(report), color = Dim)
                }
            }
            AiReasoningState.AI -> {
                Text(text = "Checked by your AI (your key)", color = Accent)
                Text(text = live.aiText.orEmpty(), color = Dim)
            }
            AiReasoningState.LOCAL_NO_KEY -> {
                Text(
                    text = "Checked by the app's own rules — your AI key isn't in the app yet. " +
                        "Add it under THINKING ENGINE on the Investigations screen.",
                    color = Dim
                )
                Text(text = live.aiText.orEmpty(), color = Dim)
            }
            AiReasoningState.LOCAL_AI_FAILED -> {
                Text(
                    text = "Your AI couldn't be reached — checked by the app's own rules instead.",
                    color = Dim
                )
                live.aiFailureReason?.let { reason ->
                    Text(
                        text = "Why it didn't work: ${reason.plainWords()}",
                        color = Dim
                    )
                }
                Text(text = live.aiText.orEmpty(), color = Dim)
            }
        }
    }
}

/** Local words shown while the thinking engine is still answering. */
private fun localFallbackLine(report: com.ghosttap.core.investigate.FindingsReport): String =
    if (report.evidence.isEmpty()) {
        "Nothing seen yet."
    } else {
        "Seen so far: " + report.evidence.joinToString("; ")
    }
