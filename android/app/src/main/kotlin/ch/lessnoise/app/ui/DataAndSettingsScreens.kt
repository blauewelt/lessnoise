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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import ch.lessnoise.app.icons.AppIcons
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
    val c = context.container
    val minutes by remember { c.measurements.minuteCount() }.collectAsStateWithLifecycle(0)
    val events by remember { c.measurements.eventCount() }.collectAsStateWithLifecycle(0)
    val live by c.live.collectAsStateWithLifecycle()
    val recal by c.recalibrator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    SettingsContent(
        modifier = modifier,
        minutes = minutes, events = events, running = live.running,
        canClear = !live.running && recal !is Recalibrator.State.Running,
        info = info,
        updateCard = { if (updateReminderAgeDays() != null) SectionCard { UpdateReminderLine() } },
        onBattery = {
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
        },
        onExport = {
            scope.launch {
                val files = withContext(Dispatchers.IO) {
                    c.measurements.exportCsv(File(context.cacheDir, "exports"), c.categoryMapper.bucketIds, c.noiseFloor.limit.value)
                }
                shareFiles(context, files, "text/csv", "Weniger Lärm Messdaten")
            }
        },
        onClear = { confirmClear = true },
        onAdvanced = { advanced = true },
        // Labor: the recording settings (nothing in the public editions).
        editionSection = { EditionUi.SettingsSection() },
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Alle Messungen löschen?") },
            text = { Text("Das kann nicht rückgängig gemacht werden. Kalibrierungen bleiben.") },
            confirmButton = {
                DestructiveTextButton("Löschen") {
                    confirmClear = false
                    scope.launch {
                        c.measurements.clear()
                        c.noiseFloor.refresh()
                        File(context.cacheDir, "exports").listFiles()?.forEach { it.delete() }
                        info = "Gelöscht."
                    }
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

/**
 * «Einstellungen» without the app container (0.8.6; also rendered on the JVM, SettingsScreenshots):
 * «Lange messen» (a navigation row to Android's battery settings), «Deine Messungen» (export tonal,
 * «Alle löschen» outlined in the error colour), «Über die App», «Datenschutz», the edition's section,
 * then «Erweitert» as a navigation row.
 */
@Composable
fun SettingsContent(
    minutes: Int,
    events: Int,
    running: Boolean,
    canClear: Boolean,
    info: String?,
    onBattery: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
    updateCard: @Composable () -> Unit = {},
    editionSection: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ScreenTitle("Einstellungen")
        updateCard()
        SectionCard("Lange messen", rows = true) {
            NavRow(
                "Akku-Optimierung ausschalten", AppIcons.Battery, onBattery,
                supporting = "Damit Android die Messung nicht stoppt.",
            )
        }
        // «Deine Messungen»: what is stored on this phone, export (CSV via the share sheet), delete.
        // 0.9.2: «Exportieren» and «Alle löschen» as list rows with a line each saying what they do (until
        // 0.9.1 two buttons side by side without one); the labels stay (docs/anleitung.html quotes them).
        SectionCard("Deine Messungen", rows = true) {
            CardBlock { HelperText("Gespeichert auf diesem Telefon: $minutes Minuten, $events Lärmspitzen") }
            ActionRow(EXPORT_LABEL, AppIcons.Download, onExport, supporting = EXPORT_SUBTITLE)
            ActionRow(
                CLEAR_LABEL, Icons.Outlined.Delete, onClear, enabled = canClear, destructive = true,
                supporting = if (running) CLEAR_WHILE_RUNNING else CLEAR_SUBTITLE,
            )
            CardBlock {
                info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                MoreLink(Links.DATA, icon = Icons.Outlined.Info)
            }
        }
        AboutAppCard()
        PrivacyCard()
        editionSection()
        SectionCard(rows = true) {
            NavRow("Erweitert", AppIcons.Tune, onAdvanced, supporting = ADVANCED_SUBTITLE)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 0.9.2: «Deine Messungen»: the two rows and what each does. */
const val EXPORT_LABEL = "Exportieren"
const val EXPORT_SUBTITLE = "Alle Messungen als Datei teilen"
const val CLEAR_LABEL = "Alle löschen"
const val CLEAR_SUBTITLE = "Messungen auf diesem Telefon; Kalibrierungen bleiben"
/** While a measurement runs the row is disabled and says why (until 0.9.1 a line under the buttons). */
const val CLEAR_WHILE_RUNNING = "Zum Löschen zuerst die Messung stoppen."

/** 0.9.2: what «Erweitert» holds (until 0.9.1 «Lärmspitzen, Kalibrieren», without the «Mikrofon» card). */
const val ADVANCED_SUBTITLE = "Lärmspitzen, Mikrofon, Kalibrieren"

/** «Datenschutz»: the audio promise of this edition, what goes online, the tolerance, the model credit. */
@Composable
private fun PrivacyCard() {
    SectionCard("Datenschutz") {
        // Edition-specific (public: never audio; Labor: can store audio locally).
        Text(EditionUi.ABOUT_AUDIO, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (BuildConfig.UPLOAD_AVAILABLE) "Ins Internet geht nur, was du unter «Teilen» einschaltest, und die Karte, wenn du sie öffnest."
            else "Diese Version hat keinen Internetzugang.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Ohne Kalibrierung sind die Werte Richtwerte (± 5 dB).", style = MaterialTheme.typography.bodyMedium)
        Text("Geräuscherkennung: YAMNet (Google, Apache-2.0).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoreLink(Links.PRIVACY, icon = Icons.Outlined.Info)
    }
}
