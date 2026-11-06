package com.ghosttap.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ghosttap.core.investigate.InvestigatedControl
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.plainWords

/**
 * Shows one investigation report in plain words only.
 *
 * This card performs no control actions itself: every button only
 * calls the callbacks the caller wires to existing Ghost Tap paths.
 * Read-only controls are marked "just reads, changes nothing" so it
 * is always clear which buttons cannot change the device.
 *
 * READABILITY — every Text here carries an explicit color on purpose.
 * The app sets no Compose MaterialTheme, so Compose falls back to its
 * default LIGHT color scheme and a bare Text() renders near-black
 * (onSurface) — on this app's near-black backgrounds (0xFF050505) that
 * is black on black and unreadable. That was the 2026-11-04 report:
 * the findings card appeared on its own but its writing was all black.
 * Headings use Accent and body text uses Dim, the app's existing
 * readable colors; text inside Buttons uses white, matching the
 * Button's own content color. Never remove these colors.
 */
@Composable
fun FindingsReportCard(
    report: FindingsReport,
    onConnect: () -> Unit,
    onControl: (InvestigatedControl) -> Unit,
    showConnectButton: Boolean = true,
    capabilitiesAsText: Boolean = false
) {
    // Plain non-scrolling Column: every parent screen already scrolls.
    // A scrollable card inside a scrolling parent throws
    // IllegalStateException at measure time ("measured with an infinity
    // maximum height") and kills the app on launch — that was the
    // 2026-11-04 startup crash. The card must never scroll itself.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(text = "What we think this is:", color = Accent)
        Spacer(modifier = Modifier.height(4.dp))
        if (report.believedIdentity == "UNKNOWN") {
            Text(text = "UNKNOWN — not enough proof to name it", color = Dim)
        } else {
            Text(text = report.believedIdentity, color = Dim)
        }
        Text(text = report.confidence.plainWords(), color = Dim)
        Text(text = report.targetKind, color = Dim)

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
        } else if (capabilitiesAsText) {
            for (control in report.capabilities) {
                Text(text = control.label + " — " + control.plainDescription, color = Dim)
                if (control.readOnly) {
                    Text(text = "just reads, changes nothing", color = Dim)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        } else {
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
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Risks:", color = Accent)
        Spacer(modifier = Modifier.height(4.dp))
        for (risk in report.risks) {
            Text(text = risk.level.plainWords() + ": " + risk.plainText, color = Dim)
        }

        Spacer(modifier = Modifier.height(16.dp))
        if (showConnectButton) {
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = report.connectLabel, color = Color.White)
            }
        }
    }
}
