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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import ch.lessnoise.app.upload.CompassReading
import ch.lessnoise.app.upload.PendingReport
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportDirection
import ch.lessnoise.app.upload.ReportQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId

// Karten editions only (BuildConfig.UPLOAD_AVAILABLE): «Laut! Melden». The fix for a shared
// measurement is in ShareFlow.kt (0.6.3). The Offline and Labor editions never show either (no
// location permission in their manifests).

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
 * off, i.e. the person has not said «Ja, auf die Karte») → «Was war laut?» → ONE precise fix (≤ 50 m) → «Meldung: …, Standort ± n m –
 * Senden?» → queued and uploaded (UploadModule.uploadSoon). The time is the tap on the button,
 * to the second. [onQueued] is called after «Senden».
 *
 * Since 0.6.7 (editions with the map, phones with a compass) one optional step follows «Was war
 * laut?»: «Wohin fuhr es?» (ReportDirectionUi, src/karte). The location fix is searched meanwhile;
 * «Richtung übernehmen» keeps the compass reading, «Überspringen» goes on without one, exactly as
 * before 0.6.7. At «Senden» the reading is turned into true north with the declination at the fix.
 *
 * Since 0.6.3 the same flow also starts from the «Sehr laut» notification (src/karte/…/alert,
 * «Unterwegs»), also while a measurement runs: [preset] then carries the event's time (used as the
 * report time instead of the tap) and the categories to suggest (highlighted, nothing is picked);
 * [onPresetUsed] is called once the flow has taken it. Since 0.6.4 the button is also shown while
 * a measurement started as «Unterwegs» runs (MeasureContent).
 *
 * Returns the action for the button.
 */
@Composable
fun rememberReportAction(onQueued: () -> Unit, preset: ReportPreset? = null, onPresetUsed: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<ReportStep?>(null) }
    var tapMs by remember { mutableStateOf(0L) }
    var suggested by remember { mutableStateOf<List<ReportCategory>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }
    // 0.6.7: the direction step is open while true; the reading taken there (null: skipped / none).
    var askDirection by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<CompassReading?>(null) }
    fun cancel() {
        job?.cancel(); step = null; askDirection = false; reading = null
    }

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
        if (step == ReportStep.Category) {
            reading = null
            askDirection = ReportDirectionUi.AVAILABLE && ReportDirectionUi.supported(context)
        }
        if (LocationFetcher.hasPreciseLocation(context)) locate(category) else permission.launch(LocationFetcher.PERMISSIONS)
    }

    val s0 = step
    if (askDirection && (s0 is ReportStep.Locating || s0 is ReportStep.Confirm)) {
        // While the fix is searched (or already there): «Wohin fuhr es?».
        ReportDirectionUi.Step(
            onTake = { reading = it; askDirection = false },
            onSkip = { reading = null; askDirection = false },
            onCancel = { cancel() },
        )
    } else when (val s = step) {
        null -> Unit
        ReportStep.Consent -> AlertDialog(
            onDismissRequest = { step = null },
            title = { Text("Meldungen senden?") },
            text = { Text(REPORT_CONSENT_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    val m = c.upload
                    // The same «yes» as «Ja, auf die Karte» (ShareDecision.YES).
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
                    if (suggested.isEmpty()) {
                        ReportCategory.entries.forEach { cat ->
                            Button(onClick = { choose(cat) }, modifier = Modifier.fillMaxWidth()) { Text(cat.label) }
                        }
                    } else {
                        // From the «Sehr laut» notification: the categories that fit the detected
                        // sound first and filled, the others outlined; the person still chooses.
                        Text("Erkannt um ${Fmt.hm(tapMs)}", style = MaterialTheme.typography.bodySmall)
                        reportCategoryOrder(suggested).forEach { cat ->
                            if (cat in suggested) {
                                Button(onClick = { choose(cat) }, modifier = Modifier.fillMaxWidth()) { Text(cat.label) }
                            } else {
                                OutlinedButton(onClick = { choose(cat) }, modifier = Modifier.fillMaxWidth()) { Text(cat.label) }
                            }
                        }
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
            dismissButton = { TextButton(onClick = { cancel() }) { Text("Abbrechen") } },
        )
        is ReportStep.Failed -> AlertDialog(
            onDismissRequest = { cancel() },
            title = { Text("Kein genauer Standort") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LocationPolicy.message(s.outcome))
                    LocationSettingsButton(context, s.outcome)
                }
            },
            confirmButton = { TextButton(onClick = { choose(s.category) }) { Text("Nochmals") } },
            dismissButton = { TextButton(onClick = { cancel() }) { Text("Abbrechen") } },
        )
        is ReportStep.Confirm -> {
            // True north from the declination at the report's own fix (magnetic if that fails).
            val direction = remember(s, reading) {
                reading?.toDirection(ReportDirectionUi.declination(s.fix.lat, s.fix.lon, tapMs))
            }
            AlertDialog(
                onDismissRequest = { cancel() },
                title = { Text("Meldung") },
                text = { Text(reportConfirmDialogText(s.category, tapMs, s.fix.accuracyText, direction = direction)) },
                confirmButton = {
                    TextButton(onClick = {
                        val zone = ZoneId.systemDefault()
                        ReportQueue.add(
                            c.upload.reports,
                            PendingReport(
                                epochMs = tapMs, timeIso = PendingReport.isoSeconds(tapMs, zone),
                                lat = s.fix.lat, lon = s.fix.lon, accuracyM = s.fix.accuracyM,
                                category = s.category.id, cell = s.fix.cell, direction = direction,
                            ),
                        )
                        c.upload.uploadSoon(5)
                        cancel()
                        onQueued()
                    }) { Text("Senden") }
                },
                dismissButton = { TextButton(onClick = { cancel() }) { Text("Abbrechen") } },
            )
        }
    }

    fun begin(timeMs: Long, suggest: List<ReportCategory>) {
        askDirection = false
        reading = null
        tapMs = timeMs
        suggested = suggest
        // Skipped when sharing is already on (the person said «Ja, auf die Karte» or switched it on).
        step = if (c.upload.store.state.value.enabled) ReportStep.Category else ReportStep.Consent
    }

    LaunchedEffect(preset) {
        if (preset != null) {
            begin(preset.epochMs, preset.suggested)
            onPresetUsed()
        }
    }

    return { begin(System.currentTimeMillis(), emptyList()) }
}

/**
 * A report started from the «Sehr laut» notification (0.6.3): [epochMs] the detected event's time
 * (the report's time), [suggested] the categories that fit the detected sound (highlighted).
 */
data class ReportPreset(val epochMs: Long, val suggested: List<ReportCategory>)

/** The category buttons with [suggested] first, in their own order; the rest as always. */
fun reportCategoryOrder(suggested: List<ReportCategory>): List<ReportCategory> =
    suggested.filter { it in ReportCategory.entries } + ReportCategory.entries.filter { it !in suggested }

/**
 * The short consent before the first report while «Teilen» is off: what is sent, never audio, that
 * the point is public (server/DESIGN.md §2 rule 10), and how to delete.
 */
const val REPORT_CONSENT_TEXT =
    "Gesendet werden Zeit, genauer Standort und Art des Lärms, nie Audio. " +
        "Der Punkt erscheint auf der Lärmkarte; wer oft vom gleichen Ort meldet, macht ihn sichtbar. " +
        "Dafür wird «Teilen» eingeschaltet; löschen kannst du alles mit «Vom Server löschen»."

/**
 * The risk server/DESIGN.md §2 rule 10 asks the report screen to state. Since 0.6.3 it is on the
 * confirmation of EVERY report: the consent (REPORT_CONSENT_TEXT) is skipped once sharing is on.
 */
const val REPORT_SPOT_WARNING = "Wer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar."

/** The text of the «Meldung» dialog: what is sent, then [REPORT_SPOT_WARNING]. */
fun reportConfirmDialogText(
    category: ReportCategory, tapMs: Long, accuracyM: Int, zone: ZoneId = ZoneId.systemDefault(), direction: ReportDirection? = null,
): String = reportConfirmText(category, tapMs, accuracyM, zone, direction) + "\n" + REPORT_SPOT_WARNING

/** «Meldung: Töff, 16:05, Standort ± 12 m – senden?»; with a direction (0.6.7) «…, Standort ± 12 m, Richtung Nordost – senden?» */
fun reportConfirmText(
    category: ReportCategory, tapMs: Long, accuracyM: Int, zone: ZoneId = ZoneId.systemDefault(), direction: ReportDirection? = null,
): String {
    val t = java.time.Instant.ofEpochMilli(tapMs).atZone(zone)
    val dir = direction?.let { ", ${it.label}" } ?: ""
    return "Meldung: ${category.label}, ${"%02d:%02d".format(t.hour, t.minute)}, Standort ± $accuracyM m$dir – senden?"
}

/** «App-Einstellungen» or «Standort einschalten», depending on why there is no fix. */
@Composable
internal fun LocationSettingsButton(context: Context, o: LocationPolicy.Outcome) {
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
