package com.ghosttap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigatedControl

/**
 * NETWORK findings panel — shows what Ghost Tap's investigator made of a
 * device found on the network, in plain words. It takes no network action
 * itself: it only renders the report and hands the user's taps back to the
 * caller. The user presses every connect/control button.
 *
 * Wiring contract for the caller (the network screen in GhostTapApp.kt):
 * - onConnect: the EXISTING network connect/open path for the host the
 *   report describes — tapping a live device, i.e. scanPortsForHost(host),
 *   which checks its common connection points and lists which ones answer.
 * - onControl: ONLY the existing network actions for that host:
 *   ping(host) ("check if it answers"), scanPortsForHost(host) ("check its
 *   connection points"), tcpTest(host, port, "HELLO") ("talk to it"),
 *   httpGet(url) ("read the page it serves"). No other action may be wired
 *   here, and no new action is invented by this panel.
 * - Filtering: this panel passes the report and both lambdas straight
 *   through to FindingsReportCard, so the caller is the filter point — a
 *   report carrying a capability outside the four actions above must have
 *   that capability dropped before it is passed in
 *   (report.copy(capabilities = performable ones only)). Capabilities with
 *   no existing action must never reach the card as a tappable control;
 *   they render as plain text only. The filter predicate itself lives at
 *   the call site because InvestigatedControl's discriminator fields are
 *   defined by the shared framework, not by this panel.
 *
 * All new strings here are plain words. No existing section title is
 * touched by this file.
 */
@Composable
fun NetworkInvestigationPanel(
    report: FindingsReport,
    onConnect: () -> Unit,
    onControl: (InvestigatedControl) -> Unit
) {
    // Only read-only capabilities become buttons. Anything that could
    // change the host renders as plain text below the card, never hidden.
    val shown = report.copy(capabilities = report.capabilities.filter { it.readOnly })
    val dropped = report.capabilities.filter { !it.readOnly }
    Column {
        Text(
            "What we found on your network:",
            color = Accent,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(6.dp))
        FindingsReportCard(
            report = shown,
            onConnect = onConnect,
            onControl = onControl
        )
        if (dropped.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Also possible, but no button here:",
                color = Accent,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            for (control in dropped) {
                Text(
                    control.label + " — " + control.plainDescription,
                    // Explicit color: with no Compose MaterialTheme set,
                    // a bare Text() renders near-black on this app's
                    // near-black background and cannot be read.
                    color = Dim,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
