package ch.lessnoise.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.container
import ch.lessnoise.app.data.Recalibrator
import ch.lessnoise.app.edition.EditionUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Einstellungen» (0.6.1): «Lange messen», «Deine Messungen», «Über die App», «Datenschutz», then
 * «Erweitert» (the two detector options and Kalibrieren). The settings removed in 0.6.1 are fixed
 * defaults, see AppSettings.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    if (advanced) {
        AdvancedScreen(onBack = { advanced = false }, modifier = modifier)
        return
    }
    val context = LocalContext.current

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "Einstellungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        if (updateReminderAgeDays() != null) SectionCard { UpdateReminderLine() }
        SectionCard("Lange messen") {
            Text(
                "Damit Android die Messung nicht stoppt: Akku-Optimierung für Weniger Lärm ausschalten.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                // Not every vendor ROM implements this screen; fall back to the app's details page.
                try {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    } catch (_: ActivityNotFoundException) {}
                }
            }) { Text("Akku-Optimierung ausschalten") }
        }
        MeasurementsDataCard()
        AboutAppCard()
        PrivacyCard()
        // Labor: the recording settings (nothing in the public editions).
        EditionUi.SettingsSection()
        OutlinedButton(onClick = { advanced = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Erweitert ›")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** «Deine Messungen»: what is stored on this phone, export (CSV via the share sheet), delete. */
@Composable
private fun MeasurementsDataCard() {
    val context = LocalContext.current
    val c = context.container
    val minutes by remember { c.measurements.minuteCount() }.collectAsStateWithLifecycle(0)
    val events by remember { c.measurements.eventCount() }.collectAsStateWithLifecycle(0)
    val live by c.live.collectAsStateWithLifecycle()
    val recal by c.recalibrator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    SectionCard("Deine Messungen") {
        Text("Gespeichert auf diesem Telefon: $minutes Minuten, $events Lärmspitzen", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val files = withContext(Dispatchers.IO) {
                        c.measurements.exportCsv(File(context.cacheDir, "exports"), c.categoryMapper.bucketIds, c.noiseFloor.limit.value)
                    }
                    shareFiles(context, files, "text/csv", "Weniger Lärm Messdaten")
                }
            }) { Text("Exportieren") }
            Button(
                onClick = { confirmClear = true },
                enabled = !live.running && recal !is Recalibrator.State.Running,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Alle löschen") }
        }
        if (live.running) Text("Zum Löschen zuerst die Messung stoppen.", style = MaterialTheme.typography.bodySmall)
        info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        MoreLink(Links.DATA)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Alle Messungen löschen?") },
            text = { Text("Das kann nicht rückgängig gemacht werden. Kalibrierungen bleiben.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        c.measurements.clear()
                        c.noiseFloor.refresh()
                        File(context.cacheDir, "exports").listFiles()?.forEach { it.delete() }
                        info = "Gelöscht."
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

/** «Datenschutz»: the audio promise of this edition, what goes online, the tolerance, the model credit. */
@Composable
private fun PrivacyCard() {
    SectionCard("Datenschutz") {
        // Edition-specific (public: never audio; Labor: can store audio locally).
        Text(EditionUi.ABOUT_AUDIO, style = MaterialTheme.typography.bodySmall)
        Text(
            if (BuildConfig.UPLOAD_AVAILABLE) "Ins Internet geht nur, was du unter «Teilen» einschaltest, und die Karte, wenn du sie öffnest."
            else "Diese Version hat keinen Internetzugang.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Ohne Kalibrierung sind die Werte Richtwerte (± 5 dB).", style = MaterialTheme.typography.bodySmall)
        Text("Geräuscherkennung: YAMNet (Google, Apache-2.0).", style = MaterialTheme.typography.bodySmall)
        MoreLink(Links.PRIVACY)
    }
}
