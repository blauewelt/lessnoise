package ch.lessnoise.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lessnoise.app.data.Recalibrator

/**
 * «Alte Messungen anpassen?» — asked after saving a calibration (Einstellungen › Erweitert ›
 * Kalibrieren, which also has the card). While a measurement runs, the re-evaluation is not
 * offered (the running measurement still writes minutes with the old calibration).
 */
@Composable
fun RecalibrationDialog(minutes: Int, measuring: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(CalibrationTexts.RECAL_QUESTION) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(CalibrationTexts.recalScope(minutes))
                if (measuring) {
                    Text(
                        "Zuerst die Messung stoppen.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !measuring) { Text("Anpassen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Nicht jetzt") } },
    )
}

/** Progress and result of a running or finished re-evaluation; nothing when idle. */
@Composable
fun RecalibrationStatus(state: Recalibrator.State, onAcknowledge: () -> Unit) {
    when (state) {
        Recalibrator.State.Idle -> Unit
        is Recalibrator.State.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (state.total == 0) "Wird angepasst …"
                else "Wird angepasst … ${state.done} von ${state.total}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
        }
        is Recalibrator.State.Done -> Column {
            Text(
                CalibrationTexts.recalDone(state.minutes),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onAcknowledge) { Text("OK") }
        }
        is Recalibrator.State.Failed -> Column {
            Text(
                "Anpassen fehlgeschlagen, nichts geändert: ${state.message}",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onAcknowledge) { Text("OK") }
        }
    }
}
