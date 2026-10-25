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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import ch.lessnoise.app.R
import ch.lessnoise.app.container
import ch.lessnoise.app.edition.EditionDiagnostics
import ch.lessnoise.app.location.LocationFetcher
import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.CellInput
import ch.lessnoise.app.upload.CompassReading
import ch.lessnoise.app.upload.PendingReport
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportLevel
import ch.lessnoise.app.upload.ReportPlate
import ch.lessnoise.app.upload.ReportQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.hypot

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
 * line [REPORT_SPOT_WARNING]; then «Abbrechen» and [REPORT_SEND]. Since 0.8.2 (owner's decision)
 * **tapping a category selects it** (exactly one, drawn filled, the others outlined) and
 * **«Senden» sends the report**: that category, the compass reading of the «Senden» tap (true
 * north with the declination at the report's place; none without a compass or before its first
 * reading), the plate (on the phone only), the time [tapMs] and the fix. «Senden» is enabled with
 * a category selected and a fix present or still searched ([reportSendEnabled]); tapped before the
 * fix, everything is disabled under a progress bar and the report goes out as soon as the fix
 * arrives ([reportNext]); without a fix at all: «Kein genauer Standort» with «Nochmals» (back to
 * the dialog, the selected category kept, the fix tried again). In 0.8.1 the category tap itself
 * sent. The time is the tap on «Laut! Melden», to the second. [onQueued] is called once the report
 * is queued.
 *
 * Since 0.6.3 the same flow also starts from the «Sehr laut» notification (src/karte/…/alert,
 * «Unterwegs»), also while a measurement runs: [preset] then carries the event's time (used as the
 * report time instead of the tap) and the categories to suggest (first, marked «erkannt»; since
 * 0.8.2 a single suggestion is preselected, so «Senden» is the one tap left, [reportPreselected]);
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
    // 0.8.2: the SELECTED category (a tap selects; «Senden» sends), whether «Senden» was tapped
    // (then it waits for the fix if needed), and the compass reading of the «Senden» tap.
    var chosen by remember { mutableStateOf<ReportCategory?>(null) }
    var sending by remember { mutableStateOf(false) }
    var chosenReading by remember { mutableStateOf<CompassReading?>(null) }
    // 0.7.6: «Kontrollschild (optional)»; on the phone only, never sent.
    var plateText by remember { mutableStateOf("") }
    // 0.8.2: the line under the field after a photo («Kein Kontrollschild erkannt», …); null: none.
    var plateNote by remember { mutableStateOf<String?>(null) }
    val plateCamera = rememberPlateCamera { r ->
        when (r) {
            is PlatePhoto.Found -> { plateText = ReportPlate.typing(r.plate); plateNote = null }
            is PlatePhoto.Note -> plateNote = r.text
            PlatePhoto.Cancelled -> Unit
        }
    }
    // 0.8.1 (same build): the place under the dot once the map was moved by hand (null: the fix).
    var moved by remember { mutableStateOf<ReportPlace?>(null) }
    // 0.8.5: the level while the dialog is open (ReportLevel.kt), reset when it opens; the microphone
    // permission (asked when the dialog opens if missing; without it there is no level); the level of
    // the «Senden» tap.
    val meter = remember { ReportLevelMeter() }
    var micAllowed by remember { mutableStateOf(ReportLevelUi.hasMic(context)) }
    var chosenLevel by remember { mutableStateOf<ReportLevel?>(null) }
    // Whether the pending permission request includes the location (it may ask for the microphone only).
    var askedLocation by remember { mutableStateOf(false) }
    fun cancel() {
        job?.cancel(); step = null; fixOutcome = null; chosen = null; sending = false; chosenReading = null; plateText = ""; plateNote = null; moved = null; chosenLevel = null
    }

    fun send(category: ReportCategory, fix: LocationPolicy.ReportOutcome.Fix, reading: CompassReading?, level: ReportLevel?) {
        // The fix, or the place moved under the dot (with MANUAL_ACCURACY_M and its own cell).
        val place = reportPlace(fix, moved)
        // True north from the declination at the report's own place (magnetic if that fails).
        val direction = reading?.toDirection(ReportDirectionUi.declination(place.lat, place.lon, tapMs))
        val plate = ReportPlate.normalize(plateText)
        val zone = ZoneId.systemDefault()
        val report = PendingReport(
            epochMs = tapMs, timeIso = PendingReport.isoSeconds(tapMs, zone),
            lat = place.lat, lon = place.lon, accuracyM = place.accuracyM,
            category = category.id, cell = place.cell, direction = direction, plate = plate, level = level,
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

    /** After «Senden» or a fix: send, keep waiting, or say why there is no fix ([reportNext]). */
    fun advance() {
        val o = fixOutcome
        when (reportNext(chosen, o, sending)) {
            ReportNext.SEND -> send(chosen!!, o as LocationPolicy.ReportOutcome.Fix, chosenReading, chosenLevel)
            ReportNext.FAILED -> step = ReportStep.Failed((o as LocationPolicy.ReportOutcome.Problem).outcome)
            ReportNext.CHOOSE, ReportNext.READY, ReportNext.WAIT -> Unit
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
        micAllowed = ReportLevelUi.hasMic(context)
        if (!askedLocation) return@rememberLauncherForActivityResult
        askedLocation = false
        when (LocationFetcher.permission(context)) {
            LocationPolicy.Permission.PRECISE -> locate()
            LocationPolicy.Permission.APPROXIMATE_ONLY -> { fixOutcome = LocationPolicy.ReportOutcome.Problem(LocationPolicy.Outcome.ApproximateOnly); advance() }
            LocationPolicy.Permission.DENIED -> { fixOutcome = LocationPolicy.ReportOutcome.Problem(LocationPolicy.Outcome.PermissionDenied); advance() }
        }
    }

    /**
     * The one dialog, with the fix started at once (the permission asked first if needed). 0.8.5: the
     * level starts from zero, and a missing microphone permission is asked in the same request.
     */
    fun open() {
        step = ReportStep.Report
        fixOutcome = null
        moved = null
        meter.reset()
        micAllowed = ReportLevelUi.hasMic(context)
        val needLocation = !LocationFetcher.hasPreciseLocation(context)
        val ask = reportPermissions(needLocation, micAllowed)
        if (!needLocation) locate()
        if (ask.isNotEmpty()) {
            askedLocation = needLocation
            permission.launch(ask)
        }
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
            // 0.8.5: the level, as long as this dialog shows (its own engine, or the running measurement's values).
            ReportLevelEffect(meter, micAllowed)
            val measuring = c.live.collectAsState().value.running
            val fix = fixOutcome as? LocationPolicy.ReportOutcome.Fix
            // «Senden» tapped before the fix: everything waits for it.
            val waiting = sending
            fun select(category: ReportCategory) {
                if (!waiting) chosen = category
            }
            fun sendTap() {
                if (!reportSendEnabled(chosen, fixOutcome, sending)) return
                sending = true
                chosenReading = reading?.value
                chosenLevel = meter.level()
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
                        if (reading != null) {
                            ReportDirectionUi.Panel(reading, fix?.let { it.lat to it.lon }, moved?.let { it.lat to it.lon }) { p ->
                                // Taken only within 2 km of the fix and in Switzerland; else the map stops.
                                val next = fix?.let { reportMovedPlace(it, p.first, p.second) }
                                if (next != null && !waiting) moved = next
                                next != null && !waiting
                            }
                        }
                        // 0.8.5: the level line under the disc (none without the microphone and no measurement).
                        if (micAllowed || measuring) ReportLevelLine(meter)
                        // While a tapped report waits for the fix, the line under the grid says so instead.
                        if (!waiting) Text(
                            reportFixLine(fix?.accuracyText, moved != null),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (!waiting && moved != null) {
                            TextButton(onClick = { moved = null }) { Text(REPORT_MOVE_BACK) }
                        }
                        OutlinedTextField(
                            value = plateText,
                            onValueChange = { plateText = ReportPlate.typing(it); plateNote = null },
                            label = { Text(ReportPlate.LABEL) },
                            singleLine = true,
                            enabled = !waiting,
                            // 0.8.2: the phone's camera app (no camera permission; PlateCamera).
                            trailingIcon = {
                                IconButton(onClick = { plateCamera() }, enabled = !waiting) {
                                    Icon(painterResource(R.drawable.ic_plate_camera), contentDescription = PlateCamera.BUTTON)
                                }
                            },
                            supportingText = plateNote?.let { n -> { Text(n) } },
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
                                    CategoryButton(cat, cat in suggested, selected = cat == chosen, enabled = !waiting, modifier = Modifier.weight(1f).fillMaxHeight()) { select(cat) }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        if (waiting) {
                            // «Senden» was tapped before the fix: it goes out as soon as the fix is there.
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(REPORT_LOCATING, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                        }
                        // DESIGN.md §2 rule 10: the report screen states the risk, on every report.
                        Text(REPORT_SPOT_WARNING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                    }
                },
                // 0.8.2: the explicit send (owner's decision), enabled with a category and a fix (or one being searched).
                confirmButton = {
                    TextButton(onClick = { sendTap() }, enabled = reportSendEnabled(chosen, fixOutcome, sending)) { Text(REPORT_SEND) }
                },
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
            // Back to the one dialog; the selected category stays selected, and if «Senden» was already
            // tapped the report goes out with the new fix.
            confirmButton = { TextButton(onClick = { open() }) { Text("Nochmals") } },
            dismissButton = { TextButton(onClick = { cancel() }) { Text("Abbrechen") } },
        )
    }

    fun begin(timeMs: Long, suggest: List<ReportCategory>) {
        cancel()
        tapMs = timeMs
        suggested = suggest
        // 0.8.2: exactly one suggestion (from the «Sehr laut» notification) is preselected; two: none.
        chosen = reportPreselected(suggest)
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
 * One category button of the grid (0.8.1): the label centred, a suggested one with the small
 * «erkannt» under its label (two columns leave no room beside it). 0.8.2: the [selected] one is
 * drawn filled, the others outlined (0.7.7); the selection is also told to TalkBack.
 */
@Composable
private fun CategoryButton(cat: ReportCategory, suggested: Boolean, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    val m = modifier.semantics { this.selected = selected }
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cat.label, textAlign = TextAlign.Center)
            if (suggested) {
                Text(
                    REPORT_SUGGESTED_MARK,
                    style = MaterialTheme.typography.labelSmall,
                    // On the filled button the primary colour would vanish: the button's own text colour.
                    color = if (selected) LocalContentColor.current else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = m, contentPadding = padding) { content() }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = m, contentPadding = padding) { content() }
    }
}

/**
 * A report started from the «Sehr laut» notification (0.6.3): [epochMs] the detected event's time
 * (the report's time), [suggested] the categories that fit the detected sound (highlighted).
 */
data class ReportPreset(val epochMs: Long, val suggested: List<ReportCategory>)

/**
 * 0.8.5: what [rememberReportAction]'s one permission request asks for when the dialog opens: the
 * location ([needLocation]: no «Genauer Standort» yet) and the microphone (not granted yet, for the
 * level). Empty: nothing to ask.
 */
fun reportPermissions(needLocation: Boolean, micAllowed: Boolean): Array<String> =
    (if (needLocation) LocationFetcher.PERMISSIONS.toList() else emptyList<String>()).plus(
        if (micAllowed) emptyList() else listOf(android.Manifest.permission.RECORD_AUDIO),
    ).toTypedArray()

/** The small marker on a suggested category's button (0.7.7; before: a filled button; 0.8.1: under the label; 0.8.2: filled now means selected). */
const val REPORT_SUGGESTED_MARK = "erkannt"

/** The category buttons with [suggested] first, in their own order; the rest as always. */
fun reportCategoryOrder(suggested: List<ReportCategory>): List<ReportCategory> =
    suggested.filter { it in ReportCategory.entries } + ReportCategory.entries.filter { it !in suggested }

/** 0.8.1: the grid of two columns, row by row (the order of [reportCategoryOrder] read left to right). */
fun reportCategoryRows(order: List<ReportCategory>): List<List<ReportCategory>> = order.chunked(2)

/** 0.8.1: the title of the one dialog. */
const val REPORT_TITLE = "Laut! Melden"

/** 0.8.2: the dialog's confirm button (0.8.1 had none: the category tap sent, and a line under the grid said so). */
const val REPORT_SEND = "Senden"

/**
 * 0.8.2: the category selected when the dialog opens: the one suggestion of the «Sehr laut»
 * notification, so that «Senden» is the only tap left; with two suggestions (or none) nothing.
 */
fun reportPreselected(suggested: List<ReportCategory>): ReportCategory? = suggested.singleOrNull()

/** While the fix is searched (0.7.8 under the disc in Labor·Karte; 0.8.1 in the one dialog). */
const val REPORT_LOCATING = "Standort wird gesucht …"

/**
 * «Standort wird gesucht …» while there is no fix, then «Standort ± 12 m»; 0.8.1 (same build):
 * [REPORT_MOVED_LINE] once the map was moved under the dot ([moved]).
 */
fun reportFixLine(accuracyM: Int?, moved: Boolean = false): String = when {
    accuracyM == null -> REPORT_LOCATING
    moved -> REPORT_MOVED_LINE
    else -> "Standort ± $accuracyM m"
}

// ---- 0.8.1 (same build, Labor·Karte): the map moved under the dot -------------------------------
// The disc (src/karte ReportDirectionUi.Panel) lets the person drag the map under the dot when
// the car drove on or the fix is off; the place under the dot is then the report's place.

/** Under the disc once the map was moved, instead of «Standort ± n m». */
const val REPORT_MOVED_LINE = "Standort von Hand gesetzt"

/** The button under that line: back to the fix. */
const val REPORT_MOVE_BACK = "Zurück zum Standort"

/** The accuracy sent with a place set by hand (the dot is placed on a 300 m map by a finger). */
const val MANUAL_ACCURACY_M = 15.0

/** At most this far from the fix (owner: a report 10 km away is not what this is for). */
const val REPORT_MAX_MOVE_M = 2_000.0

/**
 * Slack on [REPORT_MAX_MOVE_M] for this check, which measures in LV95 metres (LV95 = the Swiss
 * national grid, close to true ground metres), while the disc stops the map at 2 km in Web
 * Mercator metres (a sphere of 6378 km): the two differ by up to about 2 m per km here (east–west),
 * so the disc's own limit always passes this one.
 */
const val REPORT_MOVE_SLACK_M = 10.0

/** The place a report is sent with: latitude, longitude, accuracy (m) and the 100 m cell kept on the phone. */
data class ReportPlace(val lat: Double, val lon: Double, val accuracyM: Double, val cell: String)

/**
 * The map moved under the dot to ([lat], [lon]): that place with [MANUAL_ACCURACY_M] and its own
 * 100 m cell (the same `CellInput.fromLatLon` as [LocationPolicy.decideReport]), or null when it is
 * outside Switzerland or more than [REPORT_MAX_MOVE_M] from [fix] (then the map stops moving).
 */
fun reportMovedPlace(fix: LocationPolicy.ReportOutcome.Fix, lat: Double, lon: Double): ReportPlace? {
    val at = CellInput.fromLatLon(lat, lon, how = "Standort") as? CellInput.Parsed.Ok ?: return null
    val from = CellInput.fromLatLon(fix.lat, fix.lon, how = "Standort") as? CellInput.Parsed.Ok ?: return null
    if (hypot(at.e - from.e, at.n - from.n) > REPORT_MAX_MOVE_M + REPORT_MOVE_SLACK_M) return null
    return ReportPlace(lat, lon, MANUAL_ACCURACY_M, at.cell)
}

/** What «Senden» sends: the place moved under the dot, or (not moved) the fix as it is. */
fun reportPlace(fix: LocationPolicy.ReportOutcome.Fix, moved: ReportPlace?): ReportPlace =
    moved ?: ReportPlace(fix.lat, fix.lon, fix.accuracyM, fix.cell)

/** What the one dialog does next (0.8.1; 0.8.2: with «Senden»). */
enum class ReportNext {
    /** No category selected: not sendable, the dialog waits (the fix may or may not be there). */
    CHOOSE,

    /** 0.8.2: a category selected, «Senden» not tapped yet: the dialog waits for «Senden». */
    READY,

    /** «Senden» tapped, the fix is still searched: everything is disabled, it goes out with the fix. */
    WAIT,

    /** A category, «Senden» and a fix: the report is queued now. */
    SEND,

    /** No fix (no permission, location off, none found, outside Switzerland): «Kein genauer Standort». */
    FAILED,
}

/**
 * «Send now or wait»: [chosen] the selected category (null: none), [fix] the outcome of the one fix
 * (null: still searched), [sendTapped] whether «Senden» was tapped (0.8.2). A failed fix is shown
 * at once, sent or not, since nothing can be sent without one.
 */
fun reportNext(chosen: ReportCategory?, fix: LocationPolicy.ReportOutcome?, sendTapped: Boolean): ReportNext = when {
    fix is LocationPolicy.ReportOutcome.Problem -> ReportNext.FAILED
    chosen == null -> ReportNext.CHOOSE
    !sendTapped -> ReportNext.READY
    fix == null -> ReportNext.WAIT
    else -> ReportNext.SEND
}

/**
 * 0.8.2: whether «Senden» can be tapped: a category selected, the fix present or still searched
 * (then the report waits for it), and not already sent and waiting.
 */
fun reportSendEnabled(chosen: ReportCategory?, fix: LocationPolicy.ReportOutcome?, sendTapped: Boolean): Boolean =
    chosen != null && !sendTapped && fix !is LocationPolicy.ReportOutcome.Problem

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
 * since 0.8.1 in the one «Laut! Melden» dialog under the buttons (0.8.2: above «Senden»).
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
