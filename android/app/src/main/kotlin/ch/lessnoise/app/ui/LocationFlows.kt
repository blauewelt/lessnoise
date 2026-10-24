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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import ch.lessnoise.app.container
import ch.lessnoise.app.edition.EditionDiagnostics
import ch.lessnoise.app.location.LocationFetcher
import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.CompassReading
import ch.lessnoise.app.upload.PendingReport
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportPlate
import ch.lessnoise.app.upload.ReportQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId

// Karten editions only (BuildConfig.UPLOAD_AVAILABLE): «Laut! Melden». The fix for a shared
// measurement is in ShareFlow.kt (0.6.3). The Offline and Labor editions never show either (no
// location permission in their manifests).

/** Steps of «Laut! Melden» (0.8.1: one dialog, [Report]; before: Category → direction → Locating → Confirm). */
private sealed interface ReportStep {
    data object Consent : ReportStep
    data object Report : ReportStep
    data class Failed(val outcome: LocationPolicy.Outcome) : ReportStep
}

/**
 * «Laut! Melden» (Karten editions, while no measurement runs): consent (only while «Teilen» is
 * off, i.e. the person has not said «Ja, auf die Karte») → ONE dialog «Laut! Melden» (0.8.1, branch
 * labor-one-tap; until 0.7.8 three dialogs: «Was war laut?», «Wohin fuhr es?», «Meldung … –
 * senden?»). The dialog opens the location fix at once (ONE fix, any accuracy since 0.7.0) and
 * shows, top to bottom: the compass disc (ReportDirectionUi, editions with the map and a compass;
 * Labor·Karte draws the streets around the fix), the fix line («Standort wird gesucht …» /
 * «Standort ± n m»), «Kontrollschild (optional)», the category buttons in two columns and the
 * lines [REPORT_SEND_NOTE] and [REPORT_SPOT_WARNING]. **Tapping a category sends the report**:
 * that category, the compass reading of that instant (true north with the declination at the
 * fix; none without a compass or before its first reading), the plate (on the phone only), the
 * time [tapMs] and the fix. Without a fix yet the buttons are disabled and the report goes out
 * as soon as the fix arrives ([reportNext]); without a fix at all: «Kein genauer Standort» with
 * «Nochmals» (back to the dialog, the chosen category kept, the fix tried again). There is no
 * confirmation any more (owner's decision, 0.8.1). The time is the tap on «Laut! Melden», to the
 * second. [onQueued] is called once the report is queued.
 *
 * Since 0.6.3 the same flow also starts from the «Sehr laut» notification (src/karte/…/alert,
 * «Unterwegs»), also while a measurement runs: [preset] then carries the event's time (used as the
 * report time instead of the tap) and the categories to suggest (first, marked «erkannt», nothing is picked);
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
    // The fix: null while it is searched.
    var fixOutcome by remember { mutableStateOf<LocationPolicy.ReportOutcome?>(null) }
    // The category tapped while the fix was still missing, and the compass reading of that tap.
    var chosen by remember { mutableStateOf<ReportCategory?>(null) }
    var chosenReading by remember { mutableStateOf<CompassReading?>(null) }
    // 0.7.6: «Kontrollschild (optional)»; on the phone only, never sent.
    var plateText by remember { mutableStateOf("") }
    fun cancel() {
        job?.cancel(); step = null; fixOutcome = null; chosen = null; chosenReading = null; plateText = ""
    }

    fun send(category: ReportCategory, fix: LocationPolicy.ReportOutcome.Fix, reading: CompassReading?) {
        // True north from the declination at the report's own fix (magnetic if that fails).
        val direction = reading?.toDirection(ReportDirectionUi.declination(fix.lat, fix.lon, tapMs))
        val plate = ReportPlate.normalize(plateText)
        val zone = ZoneId.systemDefault()
        val report = PendingReport(
            epochMs = tapMs, timeIso = PendingReport.isoSeconds(tapMs, zone),
            lat = fix.lat, lon = fix.lon, accuracyM = fix.accuracyM,
            category = category.id, cell = fix.cell, direction = direction, plate = plate,
        )
        ReportQueue.add(c.upload.reports, report)
        // Labor·Karte: one `report` line in the local manifest (no-op in every other edition).
        EditionDiagnostics.report(
            context, report.epochMs, report.category, report.plate,
            direction?.bearingDeg, direction?.north?.id, direction?.accuracy?.id,
        )
        c.upload.uploadSoon(5)
        cancel()
        onQueued()
    }

    /** After a tap or a fix: send, keep waiting, or say why there is no fix ([reportNext]). */
    fun advance() {
        val o = fixOutcome
        when (reportNext(chosen, o)) {
            ReportNext.SEND -> send(chosen!!, o as LocationPolicy.ReportOutcome.Fix, chosenReading)
            ReportNext.FAILED -> step = ReportStep.Failed((o as LocationPolicy.ReportOutcome.Problem).outcome)
            ReportNext.CHOOSE, ReportNext.WAIT -> Unit
        }
    }

    fun locate() {
        job?.cancel()
        job = scope.launch {
            val o = LocationFetcher.locateForReport(context)
            if (step != ReportStep.Report) return@launch
            fixOutcome = o
            advance()
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (step != ReportStep.Report) return@rememberLauncherForActivityResult
        when (LocationFetcher.permission(context)) {
            LocationPolicy.Permission.PRECISE -> locate()
            LocationPolicy.Permission.APPROXIMATE_ONLY -> { fixOutcome = LocationPolicy.ReportOutcome.Problem(LocationPolicy.Outcome.ApproximateOnly); advance() }
            LocationPolicy.Permission.DENIED -> { fixOutcome = LocationPolicy.ReportOutcome.Problem(LocationPolicy.Outcome.PermissionDenied); advance() }
        }
    }

    /** The one dialog, with the fix started at once (the permission asked first if needed). */
    fun open() {
        step = ReportStep.Report
        fixOutcome = null
        if (LocationFetcher.hasPreciseLocation(context)) locate() else permission.launch(LocationFetcher.PERMISSIONS)
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
                    // The same «yes» as «Ja, auf die Karte» (ShareDecision.YES).
                    if (m.uploader.enable()) m.applySchedule()
                    open()
                }) { Text("Ja, einschalten") }
            },
            dismissButton = { TextButton(onClick = { step = null }) { Text("Abbrechen") } },
        )
        ReportStep.Report -> {
            // The compass lives as long as this dialog (null: no compass on this phone / edition).
            val reading = ReportDirectionUi.rememberReading()
            val fix = fixOutcome as? LocationPolicy.ReportOutcome.Fix
            val waiting = chosen != null
            fun tap(category: ReportCategory) {
                if (chosen != null) return
                chosen = category
                chosenReading = reading?.value
                advance()
            }
            AlertDialog(
                onDismissRequest = { cancel() },
                // A tap outside does nothing, so waving the phone cannot cancel by accident; 94 %
                // wide (0.7.8) so that the disc and two columns of buttons fit on a narrow phone.
                properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
                modifier = Modifier.fillMaxWidth(0.94f),
                title = { Text(REPORT_TITLE) },
                text = {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (suggested.isNotEmpty()) {
                            Text("Erkannt um ${Fmt.hm(tapMs)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                        }
                        if (reading != null) ReportDirectionUi.Panel(reading, fix?.let { it.lat to it.lon })
                        // While a tapped report waits for the fix, the line under the grid says so instead.
                        if (!waiting) Text(
                            reportFixLine(fix?.accuracyText),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedTextField(
                            value = plateText,
                            onValueChange = { plateText = ReportPlate.typing(it) },
                            label = { Text(ReportPlate.LABEL) },
                            singleLine = true,
                            enabled = !waiting,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        for (row in reportCategoryRows(reportCategoryOrder(suggested))) {
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (cat in row) {
                                    CategoryButton(cat, cat in suggested, enabled = !waiting, modifier = Modifier.weight(1f).fillMaxHeight()) { tap(cat) }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (waiting) {
                            // A category was tapped before the fix: it goes out as soon as the fix is there.
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(REPORT_LOCATING, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(REPORT_SEND_NOTE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                        }
                        // DESIGN.md §2 rule 10: the report screen states the risk, on every report.
                        Text(REPORT_SPOT_WARNING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { cancel() }) { Text("Abbrechen") } },
            )
        }
        is ReportStep.Failed -> AlertDialog(
            onDismissRequest = { cancel() },
            title = { Text("Kein genauer Standort") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LocationPolicy.message(s.outcome))
                    LocationSettingsButton(context, s.outcome)
                }
            },
            // Back to the one dialog; a category already tapped stays chosen and is sent with the new fix.
            confirmButton = { TextButton(onClick = { open() }) { Text("Nochmals") } },
            dismissButton = { TextButton(onClick = { cancel() }) { Text("Abbrechen") } },
        )
    }

    fun begin(timeMs: Long, suggest: List<ReportCategory>) {
        cancel()
        tapMs = timeMs
        suggested = suggest
        // Skipped when sharing is already on (the person said «Ja, auf die Karte» or switched it on).
        if (c.upload.store.state.value.enabled) open() else step = ReportStep.Consent
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
 * One category button of the grid (0.8.1): outlined like all of them (0.7.7), the label centred,
 * a suggested one with the small «erkannt» under its label (two columns leave no room beside it).
 */
@Composable
private fun CategoryButton(cat: ReportCategory, suggested: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cat.label, textAlign = TextAlign.Center)
            if (suggested) {
                Text(
                    REPORT_SUGGESTED_MARK,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * A report started from the «Sehr laut» notification (0.6.3): [epochMs] the detected event's time
 * (the report's time), [suggested] the categories that fit the detected sound (highlighted).
 */
data class ReportPreset(val epochMs: Long, val suggested: List<ReportCategory>)

/** The small marker on a suggested category's button (0.7.7; before: a filled button; 0.8.1: under the label). */
const val REPORT_SUGGESTED_MARK = "erkannt"

/** The category buttons with [suggested] first, in their own order; the rest as always. */
fun reportCategoryOrder(suggested: List<ReportCategory>): List<ReportCategory> =
    suggested.filter { it in ReportCategory.entries } + ReportCategory.entries.filter { it !in suggested }

/** 0.8.1: the grid of two columns, row by row (the order of [reportCategoryOrder] read left to right). */
fun reportCategoryRows(order: List<ReportCategory>): List<List<ReportCategory>> = order.chunked(2)

/** 0.8.1: the title of the one dialog. */
const val REPORT_TITLE = "Laut! Melden"

/** 0.8.1: under the grid, so that the single tap is not a surprise. */
const val REPORT_SEND_NOTE = "Antippen sendet die Meldung."

/** While the fix is searched (0.7.8 under the disc in Labor·Karte; 0.8.1 in the one dialog). */
const val REPORT_LOCATING = "Standort wird gesucht …"

/** «Standort wird gesucht …» while there is no fix, then «Standort ± 12 m». */
fun reportFixLine(accuracyM: Int?): String = accuracyM?.let { "Standort ± $it m" } ?: REPORT_LOCATING

/** What the one dialog does next (0.8.1). */
enum class ReportNext {
    /** No category tapped yet: the dialog waits for the tap (the fix may or may not be there). */
    CHOOSE,

    /** A category was tapped, the fix is still searched: the buttons are disabled, it goes out with the fix. */
    WAIT,

    /** A category and a fix: the report is queued now. */
    SEND,

    /** No fix (no permission, location off, none found, outside Switzerland): «Kein genauer Standort». */
    FAILED,
}

/**
 * «Send now or wait for the fix»: [chosen] the tapped category (null: none yet), [fix] the outcome
 * of the one fix (null: still searched). A failed fix is shown at once, tapped or not, since
 * nothing can be sent without one.
 */
fun reportNext(chosen: ReportCategory?, fix: LocationPolicy.ReportOutcome?): ReportNext = when {
    fix is LocationPolicy.ReportOutcome.Problem -> ReportNext.FAILED
    chosen == null -> ReportNext.CHOOSE
    fix == null -> ReportNext.WAIT
    else -> ReportNext.SEND
}

/**
 * The short consent before the first report while «Teilen» is off: what is sent, never audio, that
 * the point is public (server/DESIGN.md §2 rule 10), and how to delete.
 */
const val REPORT_CONSENT_TEXT =
    "Gesendet werden Zeit, genauer Standort und Art des Lärms, nie Audio. " +
        "Der Punkt erscheint auf der Lärmkarte; wer oft vom gleichen Ort meldet, macht ihn sichtbar. " +
        "Dafür wird «Teilen» eingeschaltet; löschen kannst du alles mit «Vom Server löschen»."

/**
 * The risk server/DESIGN.md §2 rule 10 asks the report screen to state. Since 0.6.3 on the
 * confirmation of EVERY report (the consent, REPORT_CONSENT_TEXT, is skipped once sharing is on);
 * since 0.8.1, without a confirmation, in the one «Laut! Melden» dialog under the buttons.
 */
const val REPORT_SPOT_WARNING = "Wer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar."

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
