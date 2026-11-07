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
import com.ghosttap.core.investigate.ControlKind
import com.ghosttap.core.investigate.FindingsReport
import com.ghosttap.core.investigate.InvestigatedControl

/**
 * NFC auto-investigation panel. Shows the latest report the NFC read
 * trigger put in InvestigationLedger for the tapped tag.
 *
 * FILTER CHOICE (documented, as required): FindingsReportCard turns
 * every capability it receives into a button, so only capabilities
 * with a real, existing NFC action behind them are passed through:
 * read-only READ controls, which the caller routes to the existing
 * "Read this card" path. Every other capability is NOT passed to the
 * card, so it never becomes a button; it is listed below as plain
 * text instead, so nothing is hidden silently. Today the only such
 * capability is the framework's "Change a setting" WRITE control on
 * writable tags — there is no single matching NFC action for it, since
 * writing needs its own write card and the user's own words, so it
 * must not become a button here.
 *
 * Wiring, all passed in as parameters — this panel invents no action:
 * - onConnect: the caller wires it to the EXISTING NFC session/open
 *   path (for NFC the "session" is the tapped tag itself, so re-running
 *   the existing read is the honest connect).
 * - onControl: only ever receives read-only READ controls (see filter
 *   above); the caller routes those to the existing "Read this card"
 *   path. Nothing here writes to a tag or emulates anything.
 */
@Composable
fun NfcInvestigationPanel(
    report: FindingsReport,
    onConnect: () -> Unit,
    onControl: (InvestigatedControl) -> Unit
) {
    val buttonControls =
        report.capabilities.filter { it.kind == ControlKind.READ && it.readOnly }
    val listedOnly =
        report.capabilities.filterNot { it.kind == ControlKind.READ && it.readOnly }
    Column {
        Text(
            "What we found on this tag:",
            color = Accent,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium
        )
        FindingsReportCard(
            report = report.copy(capabilities = buttonControls),
            onConnect = onConnect,
            onControl = onControl
        )
        if (listedOnly.isNotEmpty()) {
            Text(
                "Also found, but not buttons here — these need their own card on this screen:",
                color = Dim,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            for (control in listedOnly) {
                Text(
                    "· ${control.label} — ${control.plainDescription}",
                    color = Dim,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
