package ch.lessnoise.app.labor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// LABOR BUILD ONLY (app/src/labor/). 0.9.4: the «Export» row and «Ohne Stundendateien» above
// «In Ordner exportieren» / «Als ZIP teilen» (Edition.kt). Stateless, so it renders in a JVM test.

/**
 * [estimate]: «Letzte 24 h ohne Stundendateien: 412 Dateien, 165 MB» (null while it is computed);
 * [folderNote]: what of it is new for the export folder, when that differs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScopeControls(
    scope: ExportScope,
    onScope: (ExportScope) -> Unit,
    withoutHourFiles: Boolean,
    onWithoutHourFiles: (Boolean) -> Unit,
    estimate: String?,
    folderNote: String?,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Export", style = MaterialTheme.typography.bodyMedium)
        val all = ExportScope.values()
        // Equal heights: «Seit letztem Export» takes two lines on a small phone, the others follow it.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            all.forEachIndexed { i, sc ->
                SegmentedButton(
                    selected = scope == sc,
                    onClick = { onScope(sc) },
                    shape = SegmentedButtonDefaults.itemShape(i, all.size),
                    modifier = Modifier.fillMaxHeight(),
                    enabled = enabled,
                    // No check mark: «Seit letztem Export» needs the width.
                    icon = {},
                ) { Text(sc.label, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ohne Stundendateien", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = withoutHourFiles, onCheckedChange = onWithoutHourFiles, enabled = enabled)
        }
        Text(estimate ?: "Grösse wird berechnet …", style = MaterialTheme.typography.bodySmall)
        folderNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * 0.9.4.1: the running export, live from [LaborExportJobs.state] (the service): a determinate bar by
 * bytes, «Export · 412 von 1830 Dateien · 165 von 820 MB», the current file and «Abbrechen»; when done
 * the result, for a ZIP with «ZIP teilen»; «Zu wenig Speicher für ZIP …» with «In Ordner exportieren».
 * Stateless, so it renders in a JVM test.
 */
@Composable
fun ExportJobPanel(
    state: ExportJobState,
    onCancel: () -> Unit,
    onShareZip: () -> Unit,
    onFolderExport: () -> Unit,
) {
    when (state) {
        ExportJobState.Idle -> {}
        is ExportJobState.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val p = state.progress
            if (p == null || (p.bytesTotal == 0L && p.filesTotal == 0)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(ExportProgressText.PREPARING, style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(progress = { ExportProgressText.fraction(p) }, modifier = Modifier.fillMaxWidth())
                Text(ExportProgressText.line(p), style = MaterialTheme.typography.bodySmall)
                if (p.current.isNotEmpty()) {
                    Text(p.current.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Läuft weiter, auch wenn du die App verlässt.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
        }
        is ExportJobState.Done -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            if (state.zip != null) OutlinedButton(onClick = onShareZip) { Text("ZIP teilen") }
        }
        is ExportJobState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (state.suggestFolder) TextButton(onClick = onFolderExport) { Text("In Ordner exportieren") }
        }
        is ExportJobState.Cancelled -> Text(state.message, style = MaterialTheme.typography.bodySmall)
    }
}
