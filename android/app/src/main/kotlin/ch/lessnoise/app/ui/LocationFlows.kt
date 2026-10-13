package ch.lessnoise.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.lessnoise.app.container
import ch.lessnoise.app.location.LocationFetcher
import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.CellInput
import ch.lessnoise.app.upload.PendingReport
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId

// Karten editions only (BuildConfig.UPLOAD_AVAILABLE): the location fix at «Start» of a shared
// measurement, and «Laut! Melden». The Offline and Labor editions never show either (no location
// permission in their manifests).

/** Steps of the location fix at «Start». */
private sealed interface StartStep {
    data object Locating : StartStep
    data class Failed(val outcome: LocationPolicy.Outcome) : StartStep
    data object Paste : StartStep
}

/**
 * «Starten» with «Teilen» on: one precise location fix, turned into the 100 m
 * cell that is stored with the measurement. If it fails, ONE dialog: «Nochmals», «Koordinaten
 * einfügen» or «Ohne Teilen starten». [start] gets the cell, or null for a measurement that is not shared.
 *
 * Returns the action for the start button.
 */
@Composable
fun rememberSharedStart(start: (cell: String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<StartStep?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun locate() {
        step = StartStep.Locating
        job?.cancel()
        job = scope.launch {
            when (val o = LocationFetcher.locate(context)) {
                is LocationPolicy.Outcome.Hectare -> { step = null; start(o.cell) }
                else -> step = StartStep.Failed(o)
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        when (LocationFetcher.permission(context)) {
            LocationPolicy.Permission.PRECISE -> locate()
            LocationPolicy.Permission.APPROXIMATE_ONLY -> step = StartStep.Failed(LocationPolicy.Outcome.ApproximateOnly)
            LocationPolicy.Permission.DENIED -> step = StartStep.Failed(LocationPolicy.Outcome.PermissionDenied)
        }
    }

    fun begin() {
        if (LocationFetcher.hasPreciseLocation(context)) locate() else permission.launch(LocationFetcher.PERMISSIONS)
    }

    when (val s = step) {
        null -> Unit
        StartStep.Locating -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Standort für die Karte") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LocationPolicy.PROGRESS_TEXT)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job?.cancel(); step = null }) { Text("Abbrechen") } },
        )
        is StartStep.Failed -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Kein Standort") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LocationPolicy.message(s.outcome))
                    Button(onClick = { begin() }, modifier = Modifier.fillMaxWidth()) { Text("Nochmals") }
                    SettingsButton(context, s.outcome)
                    OutlinedButton(onClick = { step = StartStep.Paste }, modifier = Modifier.fillMaxWidth()) { Text("Koordinaten einfügen") }
                    OutlinedButton(onClick = { step = null; start(null) }, modifier = Modifier.fillMaxWidth()) { Text("Ohne Teilen starten") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
        StartStep.Paste -> {
            var text by remember { mutableStateOf("") }
            val parsed = if (text.isBlank()) null else CellInput.parse(text)
            AlertDialog(
                onDismissRequest = { step = null },
                title = { Text("Koordinaten einfügen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Z. B. aus einer Karten-App: Punkt lange drücken, Koordinaten kopieren.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, singleLine = true,
                            label = { Text("Koordinaten, z. B. 47.3769, 8.5417") }, modifier = Modifier.fillMaxWidth(),
                        )
                        when (parsed) {
                            is CellInput.Parsed.Ok -> Text("100-m-Quadrat gefunden.", style = MaterialTheme.typography.bodySmall)
                            is CellInput.Parsed.Error -> Text(parsed.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            null -> Unit
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = parsed is CellInput.Parsed.Ok,
                        onClick = { (parsed as? CellInput.Parsed.Ok)?.let { step = null; start(it.cell) } },
                    ) { Text("Starten") }
                },
                dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
            )
        }
    }
    return ::begin
}

/** Steps of «Laut! Melden». */
private sealed interface ReportStep {
    data object Consent : ReportStep
    data object Category : ReportStep
    data class Locating(val category: ReportCategory) : ReportStep
    data class Failed(val category: ReportCategory, val outcome: LocationPolicy.Outcome) : ReportStep
    data class Confirm(val category: ReportCategory, val fix: LocationPolicy.ReportOutcome.Fix) : ReportStep
}

/**
 * «Laut! Melden» (Karten editions, while no measurement runs): consent (only while «Teilen» is
 * off) → «Was war laut?» → ONE precise fix (≤ 50 m) → «Meldung: …, Standort ± n m –
 * Senden?» → queued and uploaded (UploadModule.uploadSoon). The time is the tap on the button,
 * to the second. [onQueued] is called after «Senden».
 *
 * Returns the action for the button.
 */
@Composable
fun rememberReportAction(onQueued: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<ReportStep?>(null) }
    var tapMs by remember { mutableStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun locate(category: ReportCategory) {
        step = ReportStep.Locating(category)
        job?.cancel()
        job = scope.launch {
            step = when (val o = LocationFetcher.locateForReport(context)) {
                is LocationPolicy.ReportOutcome.Fix -> ReportStep.Confirm(category, o)
                is LocationPolicy.ReportOutcome.Problem -> ReportStep.Failed(category, o.outcome)
            }
        }
    }

    var pendingCategory by remember { mutableStateOf<ReportCategory?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val cat = pendingCategory ?: return@rememberLauncherForActivityResult
        when (LocationFetcher.permission(context)) {
            LocationPolicy.Permission.PRECISE -> locate(cat)
            LocationPolicy.Permission.APPROXIMATE_ONLY -> step = ReportStep.Failed(cat, LocationPolicy.Outcome.ApproximateOnly)
            LocationPolicy.Permission.DENIED -> step = ReportStep.Failed(cat, LocationPolicy.Outcome.PermissionDenied)
        }
    }

    fun choose(category: ReportCategory) {
        pendingCategory = category
        if (LocationFetcher.hasPreciseLocation(context)) locate(category) else permission.launch(LocationFetcher.PERMISSIONS)
    }

    when (val s = step) {
        null -> Unit
        ReportStep.Consent -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Meldungen senden?") },
            text = { Text(REPORT_CONSENT_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    val m = c.upload
                    m.store.update { it.copy(explanationSeen = true) }
                    if (m.uploader.enable()) m.applySchedule()
                    step = ReportStep.Category
                }) { Text("Ja, einschalten") }
            },
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
        ReportStep.Category -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Was war laut?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReportCategory.entries.forEach { cat ->
                        Button(onClick = { choose(cat) }, modifier = Modifier.fillMaxWidth()) { Text(cat.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
        is ReportStep.Locating -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Meldung: ${s.category.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LocationPolicy.PROGRESS_TEXT)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job?.cancel(); step = null }) { Text("Abbrechen") } },
        )
        is ReportStep.Failed -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Kein genauer Standort") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LocationPolicy.message(s.outcome))
                    SettingsButton(context, s.outcome)
                }
            },
            confirmButton = { TextButton(onClick = { choose(s.category) }) { Text("Nochmals") } },
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
        is ReportStep.Confirm -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Meldung") },
            text = { Text(reportConfirmText(s.category, tapMs, s.fix.accuracyText)) },
            confirmButton = {
                TextButton(onClick = {
                    val zone = ZoneId.systemDefault()
                    ReportQueue.add(
                        c.upload.reports,
                        PendingReport(
                            epochMs = tapMs, timeIso = PendingReport.isoSeconds(tapMs, zone),
                            lat = s.fix.lat, lon = s.fix.lon, accuracyM = s.fix.accuracyM,
                            category = s.category.id, cell = s.fix.cell,
                        ),
                    )
                    c.upload.uploadSoon(5)
                    step = null
                    onQueued()
                }) { Text("Senden") }
            },
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
    }

    return {
        tapMs = System.currentTimeMillis()
        step = if (c.upload.store.state.value.enabled) ReportStep.Category else ReportStep.Consent
    }
}

/**
 * The short consent before the first report while «Teilen» is off: what is sent, never audio, that
 * the point is public (server/DESIGN.md §2 rule 10), and how to delete.
 */
const val REPORT_CONSENT_TEXT =
    "Gesendet werden Zeit, genauer Standort und Art des Lärms, nie Audio. " +
        "Der Punkt erscheint auf der Lärmkarte; wer oft vom gleichen Ort meldet, macht ihn sichtbar. " +
        "Dafür wird «Teilen» eingeschaltet; löschen kannst du alles mit «Vom Server löschen»."

/** «Meldung: Töff, 16:05, Standort ± 12 m – senden?» */
fun reportConfirmText(category: ReportCategory, tapMs: Long, accuracyM: Int, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = java.time.Instant.ofEpochMilli(tapMs).atZone(zone)
    return "Meldung: ${category.label}, ${"%02d:%02d".format(t.hour, t.minute)}, Standort ± $accuracyM m – senden?"
}

@Composable
private fun SettingsButton(context: Context, o: LocationPolicy.Outcome) {
    when (o) {
        LocationPolicy.Outcome.ApproximateOnly, LocationPolicy.Outcome.PermissionDenied ->
            OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("App-Einstellungen") }
        LocationPolicy.Outcome.LocationOff ->
            OutlinedButton(onClick = { openLocationSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("Standort einschalten") }
        else -> Unit
    }
}

fun openAppSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {}
}

fun openLocationSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {}
}
