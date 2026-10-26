package ch.lessnoise.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.vector.ImageVector
import ch.lessnoise.app.icons.AppIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import ch.lessnoise.app.container
import ch.lessnoise.app.edition.EditionDiagnostics
import ch.lessnoise.app.location.LocationFetcher
import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.CellInput
import ch.lessnoise.app.upload.CompassReading
import ch.lessnoise.app.upload.PendingReport
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
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
 * shows, top to bottom (0.9.4.2, [ReportDialogBody]): «Was war es?» with Töff · Auto · «Andere» (Lastwagen ·
 * Flugzeug fold out), «Wohin fuhr es?» with the compass disc (ReportDirectionUi, editions with the map and a
 * compass; the streets around the fix), the status line with the level (ReportLevel.kt) and the place
 * («Standort wird gesucht …» / «Standort ± n m») and «Foto» beside it (ReportPhotos.kt; until 0.8.4
 * «Kontrollschild (optional)»), the line [REPORT_SPOT_WARNING]; then [REPORT_SEND] (0.9.1: full width in the
 * content) and «Abbrechen» (0.9.1: centred under it, [ReportDialogLayout]). Until 0.9.4.1 the disc came
 * first and the categories (a 2 × 2 grid) last. Since 0.8.2 (owner's decision)
 * **tapping a category selects it** (exactly one, drawn filled, the others outlined) and
 * **«Senden» sends the report**: that category, the compass reading of the «Senden» tap (true
 * north with the declination at the report's place; none without a compass or before its first
 * reading), the level of the «Senden» tap, the photo (on the phone only), the time [tapMs] and the fix. «Senden» is enabled with
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
@OptIn(ExperimentalMaterial3Api::class)
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
    // 0.8.5: the photo (ReportPhotos.kt) instead of the typed plate (0.7.6–0.8.4): the re-encoded
    // draft in the cache until «Senden» moves it into the app's files; on the phone only, never sent.
    var draft by remember { mutableStateOf<File?>(null) }
    // A short line under the button when the photo failed («Keine Kamera-App gefunden», …); null: none.
    var photoNote by remember { mutableStateOf<String?>(null) }
    fun dropDraft() {
        draft?.delete(); draft = null
    }
    val camera = rememberReportPhotoCamera { r ->
        when (r) {
            // A new photo replaces the one taken before (also if the dialog was closed meanwhile: then it is dropped).
            is ReportPhotoResult.Draft -> if (step == ReportStep.Report) { dropDraft(); draft = r.file; photoNote = null } else r.file.delete()
            is ReportPhotoResult.Note -> photoNote = r.text
            ReportPhotoResult.Cancelled -> Unit
        }
    }
    // 0.8.1 (same build): the place under the dot once the map was moved by hand (null: the fix).
    var moved by remember { mutableStateOf<ReportPlace?>(null) }
    // 0.9.2: whether the disc's map can be dragged now (streets drawn), for «· Karte verschiebbar» in the location row.
    var draggable by remember { mutableStateOf(false) }
    // 0.8.5: the level while the dialog is open (ReportLevel.kt), reset when it opens; the microphone
    // permission (asked when the dialog opens if missing; without it there is no level); the level of
    // the «Senden» tap.
    val meter = remember { ReportLevelMeter() }
    var micAllowed by remember { mutableStateOf(ReportLevelUi.hasMic(context)) }
    var chosenLevel by remember { mutableStateOf<ReportLevel?>(null) }
    // Whether the pending permission request includes the location (it may ask for the microphone only).
    var askedLocation by remember { mutableStateOf(false) }
    fun cancel() {
        job?.cancel(); step = null; fixOutcome = null; chosen = null; sending = false; chosenReading = null; dropDraft(); photoNote = null; moved = null; chosenLevel = null; draggable = false
    }

    fun send(category: ReportCategory, fix: LocationPolicy.ReportOutcome.Fix, reading: CompassReading?, level: ReportLevel?) {
        // The fix, or the place moved under the dot (with MANUAL_ACCURACY_M and its own cell).
        val place = reportPlace(fix, moved)
        // True north from the declination at the report's own place (magnetic if that fails).
        val direction = reading?.toDirection(ReportDirectionUi.declination(place.lat, place.lon, tapMs))
        val zone = ZoneId.systemDefault()
        val report = PendingReport(
            epochMs = tapMs, timeIso = PendingReport.isoSeconds(tapMs, zone),
            lat = place.lat, lon = place.lon, accuracyM = place.accuracyM,
            category = category.id, cell = place.cell, direction = direction, level = level,
        )
        // 0.8.5: the photo moves into files/reports/ and the report is queued with its name, in one step.
        val queued = ReportPhotos.queue(context, c.upload.reports, report, draft)
        draft = null
        // Labor·Karte: one `report` line in the local manifest (no-op in every other edition).
        EditionDiagnostics.report(
            context, queued.epochMs, queued.category, queued.photo != null,
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
            // 0.9.1: BasicAlertDialog + ReportDialogLayout instead of AlertDialog: «Senden» is the main button in
            // the content and «Abbrechen» sits centred under it (AlertDialog has only its action row for that).
            BasicAlertDialog(
                onDismissRequest = { cancel() },
                // A tap outside does nothing, so waving the phone cannot cancel by accident; 94 %
                // wide (0.7.8) so that the disc and two columns of buttons fit on a narrow phone.
                properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
                modifier = Modifier.fillMaxWidth(0.94f),
            ) {
                // 0.8.6: the megaphone of the «Laut! Melden» button with the title (in ReportDialogLayout).
                ReportDialogLayout(onCancel = { cancel() }) {
                    // 0.9.4.2: the content (ReportDialogBody): Was war es? · Wohin fuhr es? · level and place · Foto
                    ReportDialogBody(
                        erkanntAt = if (suggested.isNotEmpty()) Fmt.hm(tapMs) else null,
                        disc = if (reading == null) null else ({ size, measureOnly ->
                            ReportDirectionUi.Panel(
                                reading, fix?.let { it.lat to it.lon }, moved?.let { it.lat to it.lon },
                                onDraggable = { draggable = it }, discSize = size, measureOnly = measureOnly,
                            ) { p ->
                                // Taken only within 2 km of the fix and in Switzerland; else the map stops.
                                val next = fix?.let { reportMovedPlace(it, p.first, p.second) }
                                if (next != null && !waiting) moved = next
                                next != null && !waiting
                            }
                        }),
                        // 0.8.5: the level (none without the microphone and no measurement).
                        level = if (micAllowed || measuring) meter else null,
                        fixLine = reportFixLine(fix?.accuracyText, moved != null, draggable = draggable && !waiting),
                        fixState = reportFixState(fix != null, moved != null),
                        waiting = waiting,
                        onMoveBack = { moved = null },
                        // 0.8.5: «Foto» (the phone's camera app, no camera permission) instead of «Kontrollschild».
                        // 0.9.4.2: the compact «Foto» beside the status line; a photo taken gets its own row under it.
                        photo = { ReportPhotoRow(draft, enabled = !waiting, onTake = { camera() }, onRemove = { dropDraft() }) },
                        photoBesideStatus = draft == null,
                        photoNote = photoNote,
                        suggested = suggested,
                        chosen = chosen,
                        onSelect = { cat -> select(cat) },
                        // 0.8.2: the explicit send (owner's decision), enabled with a category and a fix (or one
                        // being searched). 0.9.1: the dialog's main button, full width at the bottom (ReportSendButton).
                        sendEnabled = reportSendEnabled(chosen, fixOutcome, sending),
                        onSend = { sendTap() },
                    )
                }
            }
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

/** 0.8.6: which icon the location row of the dialog shows. */
enum class ReportFixState { SEARCHING, FIX, MOVED }

/** 0.8.6: the location row's state: no fix yet, the fix, or the place set by hand on the map. */
fun reportFixState(hasFix: Boolean, moved: Boolean): ReportFixState = when {
    !hasFix -> ReportFixState.SEARCHING
    moved -> ReportFixState.MOVED
    else -> ReportFixState.FIX
}

/** 0.8.6: the space between the dialog's groups (0.9.4.2: 14 dp, was 20), and inside a group (0.9.4.2: 6 dp, was 8). */
val REPORT_GROUP_GAP = 14.dp
val REPORT_INNER_GAP = 6.dp

/** 0.9.4.2: the disc's side in the dialog: at least this (a short screen then scrolls) … */
const val REPORT_DISC_MIN_DP = 140

/** … and at most this (0.8.1–0.9.4.1: always 200 dp). */
const val REPORT_DISC_MAX_DP = 220

/**
 * 0.9.4.2 (owner: the dialog was taller than one screen): the disc gets the height that is left, [availableDp]
 * (what the dialog's scrolling part may take) minus [restDp] (that part's content without the disc), between
 * [REPORT_DISC_MIN_DP] and [REPORT_DISC_MAX_DP]. Below the minimum the content scrolls; «Senden» never does.
 */
fun reportDiscDp(availableDp: Float, restDp: Float): Int =
    if (availableDp.isInfinite() || availableDp.isNaN()) REPORT_DISC_MAX_DP
    else (availableDp - restDp).toInt().coerceIn(REPORT_DISC_MIN_DP, REPORT_DISC_MAX_DP)

/** 0.8.6: the icon of the place in the status line (0.9.4.2; until 0.9.4.1 of the location row). */
fun reportFixIcon(state: ReportFixState): ImageVector = when (state) {
    ReportFixState.SEARCHING -> AppIcons.LocationSearching
    ReportFixState.FIX -> AppIcons.MyLocation
    ReportFixState.MOVED -> AppIcons.EditLocation
}

/**
 * The content of the «Laut! Melden» dialog (0.8.6; until 0.8.5 a column of centred lines inside
 * rememberReportAction). Pure: no sensor, microphone, tile or container is touched here, so it is also
 * rendered on the JVM (ReportDialogDeviceScreenshots). 0.9.4.2 (owner: «this kind of vehicle went that way», and
 * the whole dialog on one screen), top to bottom:
 *
 * 1. «Was war es?» ([REPORT_WHAT]; «Erkannt um 22:36» at its right when [erkanntAt]) — ONE row Töff · Auto ·
 *    «Andere ▾»; «Andere» folds out a second row Lastwagen · Flugzeug ([reportCategoryButtons]);
 * 2. «Wohin fuhr es?» — [disc] (the compass Panel with its heading [REPORT_WHERE], the live label and the
 *    hint lines; null without a compass), its side from the height left ([reportDiscDp], [ReportFitDisc]);
 * 3. the status line, level and place in one ([ReportStatusLine]; «Zurück zum Standort» under it once the map
 *    was moved), with [photo] («Foto») beside it, or under it once a photo is taken ([photoBesideStatus] false),
 *    and [photoNote].
 *
 * These scroll on a short screen (0.9.1; since 0.9.4.2 only when the disc at [REPORT_DISC_MIN_DP] does not
 * fit either). Below them, never scrolled away: the line of server/DESIGN.md §2 rule 10 with an info icon, on
 * every report, and directly under it «Senden» ([ReportSendButton], [sendEnabled], [onSend]). [waiting]:
 * «Senden» was tapped before the fix (everything disabled, the progress on «Senden»).
 */
@Composable
fun ReportDialogBody(
    erkanntAt: String?,
    disc: (@Composable (size: Dp, measureOnly: Boolean) -> Unit)?,
    level: ReportLevelMeter?,
    fixLine: String,
    fixState: ReportFixState,
    waiting: Boolean,
    onMoveBack: () -> Unit,
    photo: @Composable () -> Unit,
    photoBesideStatus: Boolean,
    photoNote: String?,
    suggested: List<ReportCategory>,
    chosen: ReportCategory?,
    onSelect: (ReportCategory) -> Unit,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
) {
    val cs = MaterialTheme.colorScheme
    // 0.9.4.2: whether the row Lastwagen · Flugzeug is open — here, above both passes of ReportFitDisc, which share it.
    var moreOpen by remember { mutableStateOf(reportMoreOpenAtStart(suggested, chosen)) }
    val rows = reportCategoryButtons(suggested, chosen, moreOpen)
    val onTap: (ReportCategoryButton) -> Unit = { b ->
        moreOpen = reportMoreOpenAfter(b.category, moreOpen)
        b.category?.let(onSelect)
    }
    // 0.9.1: the groups scroll ([scroll]; the JVM renders pass one scrolled to the end); the line of rule 10 and
    // «Senden» stay below them, always on screen.
    Column(modifier.fillMaxWidth()) {
        ReportFitDisc(scroll, Modifier.weight(1f, fill = false)) { discSize, measureOnly ->
            // 1. Was war es? — the category (required) first (0.9.4.2; until 0.9.4.1 last, under the disc and the level).
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(REPORT_WHAT, style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (erkanntAt != null) {
                    Text("Erkannt um $erkanntAt", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                }
            }
            Spacer(Modifier.height(REPORT_INNER_GAP))
            ReportCategoryRows(rows, enabled = !waiting, onTap = onTap)
            Spacer(Modifier.height(REPORT_GROUP_GAP))
            // 2. Wohin fuhr es? — 0.9.2: with its own heading; 0.9.4.2: the Panel draws it ([REPORT_WHERE]), with the
            // live «Nord · 358°» at its right, then the hint line, then the disc.
            if (disc != null) {
                disc(discSize, measureOnly)
                Spacer(Modifier.height(REPORT_GROUP_GAP))
            }
            // 3. Level and place in one line (0.9.4.2; until 0.9.4.1 a card), «Foto» beside it.
            // Once the map was moved: «Zurück zum Standort» at the end of the place line (0.9.4.2; was a line of its own).
            val moveBack: (@Composable () -> Unit)? = if (!waiting && fixState == ReportFixState.MOVED) ({
                TextButton(onClick = onMoveBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(REPORT_MOVE_BACK) }
            }) else null
            ReportStatusLine(
                level, fixLine, reportFixIcon(fixState), Modifier.fillMaxWidth(),
                trailing = if (photoBesideStatus) photo else null, placeTrailing = moveBack,
            )
            // 0.9.1: «Senden» tapped before the fix: the progress is on «Senden» itself (ReportSendButton).
            if (!photoBesideStatus) {
                Spacer(Modifier.height(REPORT_INNER_GAP))
                photo()
            }
            photoNote?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
        }
        // 0.9.1: a line where the scrolling part ends, only when it does scroll (small screens, large font).
        if (scroll.canScrollForward || scroll.canScrollBackward) HorizontalDivider(color = cs.outlineVariant)
        Spacer(Modifier.height(10.dp))
        // DESIGN.md §2 rule 10: the report screen states the risk, on every report (0.9.1: directly above «Senden», never scrolled away).
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(REPORT_SPOT_WARNING, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        ReportSendButton(onClick = onSend, enabled = sendEnabled, waiting = waiting, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * 0.9.4.2: the scrolling part of the dialog with the disc sized to fit. Two passes in one measure: [content]
 * once with `measureOnly` true and a zero disc (the Panel then draws only its lines: no canvas, no tiles, no
 * effects; this pass is measured, never placed or drawn), which gives the height of everything else; then for
 * real, with the disc's side [reportDiscDp] of the height this part may take (the dialog minus the title, the
 * rule-10 line, «Senden» and «Abbrechen»), in a column that scrolls with [scroll] if even the smallest disc
 * does not fit.
 */
@Composable
private fun ReportFitDisc(scroll: ScrollState, modifier: Modifier, content: @Composable ColumnScope.(Dp, Boolean) -> Unit) {
    SubcomposeLayout(modifier) { c ->
        val loose = Constraints(maxWidth = c.maxWidth)
        val rest = subcompose("measure") { Column(Modifier.fillMaxWidth()) { content(0.dp, true) } }
            .maxOfOrNull { it.measure(loose).height } ?: 0
        val available = if (c.hasBoundedHeight) c.maxHeight.toDp().value else Float.POSITIVE_INFINITY
        val disc = reportDiscDp(available, rest.toDp().value).dp
        val real = subcompose("content") { Column(Modifier.fillMaxWidth().verticalScroll(scroll)) { content(disc, false) } }
            .map { it.measure(c) }
        val w = real.maxOfOrNull { it.width } ?: c.minWidth
        val h = real.maxOfOrNull { it.height } ?: c.minHeight
        layout(w, h) { real.forEach { it.place(0, 0) } }
    }
}

/**
 * 0.9.1: the frame of the «Laut! Melden» dialog, drawn as Material's AlertDialog draws its own
 * (AlertDialogDefaults: shape, container colour, tonal elevation, icon/title/text colours), but without an
 * action row: [content] (ReportDialogBody, which ends with «Senden») and «Abbrechen» as a text button centred
 * under it. 0.9.4.2 (to fit one screen): the megaphone left of [REPORT_TITLE] in one compact row (titleLarge;
 * until 0.9.4.1 the icon above a headlineSmall title, 56 dp more), 20 dp inside (was 24), 8 dp under
 * «Abbrechen». Pure, so the JVM renders (ReportDialogDeviceScreenshots) draw exactly this frame.
 */
@Composable
fun ReportDialogLayout(onCancel: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation, modifier = modifier,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Campaign, contentDescription = null, tint = AlertDialogDefaults.iconContentColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(REPORT_TITLE, style = MaterialTheme.typography.titleLarge, color = AlertDialogDefaults.titleContentColor)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f, fill = false)) {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { content() }
                }
            }
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(REPORT_CANCEL) }
        }
    }
}

/** 0.9.1: the one text button of the dialog, centred under «Senden». */
const val REPORT_CANCEL = "Abbrechen"

/** 0.8.6: the heading of the category grid. */
const val REPORT_WHAT = "Was war es?"

/** 0.9.2: the heading above the compass disc (the step's title until 0.7.8), in the style of [REPORT_WHAT]. */
const val REPORT_WHERE = "Wohin fuhr es?"

/** 0.9.1: the height of «Senden» (Material's large button; 0.9.4.2: as high as the category buttons, [REPORT_CATEGORY_HEIGHT]). */
val REPORT_SEND_HEIGHT = 56.dp

/**
 * 0.8.6: «Senden» as a filled button with the send icon (until 0.8.5 a text button). 0.9.1: the dialog's
 * main button, in the content under the grid, full width and [REPORT_SEND_HEIGHT] high (was the small
 * confirm button of the action row). Disabled until a category is chosen; [waiting] («Senden» tapped
 * before the fix): a small progress circle and [REPORT_LOCATING] instead of the icon and label.
 */
@Composable
fun ReportSendButton(onClick: () -> Unit, enabled: Boolean, waiting: Boolean = false, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    // Waiting: not tappable again, but drawn in the button's own colours (not greyed out) — the report is on its way.
    val colors = if (waiting) ButtonDefaults.buttonColors(disabledContainerColor = cs.primary, disabledContentColor = cs.onPrimary) else ButtonDefaults.buttonColors()
    Button(
        onClick = onClick, enabled = enabled, colors = colors, modifier = modifier.heightIn(min = REPORT_SEND_HEIGHT),
        contentPadding = if (waiting) PaddingValues(horizontal = 16.dp) else ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        if (waiting) {
            CircularProgressIndicator(
                Modifier.size(16.dp), color = LocalContentColor.current, strokeWidth = 2.dp,
                trackColor = LocalContentColor.current.copy(alpha = 0.3f),
            )
            Spacer(Modifier.width(8.dp))
            // labelLarge and one line: «Standort wird gesucht …» fits a 360 dp phone at font scale 1.3.
            Text(
                REPORT_LOCATING, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(REPORT_SEND, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 0.8.5: the photo row of the dialog. 0.8.6: without a photo one full-width outlined button «Foto vom
 * Fahrzeug (optional)» with the camera icon; with one its 56 dp rounded thumbnail with an «×» (removes
 * it) and «Neues Foto» as a text button. 0.9.4.2: without a photo a compact outlined button «Foto» beside the
 * status line (TalkBack still hears «Foto vom Fahrzeug (optional)»); with one the row as before, under it.
 */
@Composable
fun ReportPhotoRow(draft: File?, enabled: Boolean, onTake: () -> Unit, onRemove: () -> Unit) {
    if (draft == null) {
        OutlinedButton(
            onClick = onTake, enabled = enabled, contentPadding = PaddingValues(start = 12.dp, end = 14.dp),
            modifier = Modifier.semantics { contentDescription = ReportPhotos.BUTTON },
        ) {
            Icon(AppIcons.PhotoCamera, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(6.dp))
            Text(reportPhotoButton(false), maxLines = 1, softWrap = false)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 0.9.2: the «×» keeps its 22 dp circle at the thumbnail's top-right corner, but the tap target is
        // REPORT_REMOVE_TOUCH_DP (was the 22 dp circle itself); the box is 8 dp wider and taller than the
        // thumbnail so that the whole target lies inside it.
        Box(Modifier.size((REPORT_THUMB_DP + 8).dp)) {
            Box(Modifier.align(Alignment.BottomStart)) { ReportPhotoThumbnail(draft, REPORT_THUMB_DP.dp) }
            Box(
                Modifier.align(Alignment.TopEnd).size(REPORT_REMOVE_TOUCH_DP.dp).clip(CircleShape)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = ReportPhotos.REMOVE, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), modifier = Modifier.size(22.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, contentDescription = ReportPhotos.REMOVE, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        TextButton(onClick = onTake, enabled = enabled, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
            Icon(AppIcons.PhotoCamera, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(reportPhotoButton(true))
        }
    }
}

/** 0.8.5: the photo thumbnail in the dialog, dp (0.8.6: 56, was 64). */
const val REPORT_THUMB_DP = 56

/** 0.9.2: the tap target of the photo's «×» (the visible circle stays 22 dp). */
const val REPORT_REMOVE_TOUCH_DP = 40

/**
 * 0.8.5: «Foto», or «Neues Foto» once one is taken (0.8.6–0.9.4.1: «Foto vom Fahrzeug (optional)», which stays the
 * button's spoken label, [ReportPhotos.BUTTON]; 0.9.4.2: «Foto» again, the button sits beside the status line).
 */
fun reportPhotoButton(hasPhoto: Boolean): String = if (hasPhoto) ReportPhotos.BUTTON_AGAIN else ReportPhotos.BUTTON_SHORT

/**
 * 0.8.6: the icon of each category (Material: two_wheeler, directions_car, local_shipping, flight, speed, more_horiz).
 * 0.9.1: speed and more_horiz are no longer in the dialog; kept so the enum stays exhaustive.
 */
fun reportCategoryIcon(cat: ReportCategory): ImageVector = when (cat) {
    ReportCategory.MOTORRAD -> AppIcons.TwoWheeler
    ReportCategory.AUTO -> AppIcons.Car
    ReportCategory.LASTWAGEN -> AppIcons.Truck
    ReportCategory.FLUGZEUG -> AppIcons.Flight
    ReportCategory.POSER -> AppIcons.Speed
    ReportCategory.ANDERE -> AppIcons.MoreHoriz
}

/** 0.9.4.2: the category buttons' height (0.8.6–0.9.4.1: at least 72 dp, the icon over the label). */
val REPORT_CATEGORY_HEIGHT = 56.dp

/** 0.9.4.2: the space between the segments of a row, and between the two rows. */
val REPORT_SEGMENT_GAP = 4.dp
val REPORT_ROW_GAP = 8.dp

/**
 * 0.9.4.2: the segment shape of a button in a row of [count]: 12 dp at the row's outer corners, 4 dp where it
 * meets its neighbour (a connected, segmented look; the buttons stay separate, [REPORT_SEGMENT_GAP] apart).
 */
fun reportSegmentShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 12.dp
    val inner = 4.dp
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer else inner, bottomStart = if (first) outer else inner,
        topEnd = if (last) outer else inner, bottomEnd = if (last) outer else inner,
    )
}

/**
 * 0.9.4.2: the category rows: Töff · Auto · «Andere ▾», and, while open, Lastwagen · Flugzeug under it
 * (sliding open and shut, AnimatedVisibility).
 */
@Composable
private fun ReportCategoryRows(rows: ReportCategoryRowsModel, enabled: Boolean, onTap: (ReportCategoryButton) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        ReportSegmentRow(rows.main, enabled, open = rows.expanded, onTap = onTap)
        AnimatedVisibility(
            visible = rows.expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(REPORT_ROW_GAP))
                ReportSegmentRow(rows.more, enabled, open = null, onTap = onTap)
            }
        }
    }
}

@Composable
private fun ReportSegmentRow(buttons: List<ReportCategoryButton>, enabled: Boolean, open: Boolean?, onTap: (ReportCategoryButton) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(REPORT_SEGMENT_GAP)) {
        buttons.forEachIndexed { i, b ->
            CategoryButton(
                b, reportSegmentShape(i, buttons.size), enabled, open = if (b.category == null) open else null,
                modifier = Modifier.weight(1f),
            ) { onTap(b) }
        }
    }
}

/**
 * One category button (0.8.1; 0.9.4.2: a segment of a row, [REPORT_CATEGORY_HEIGHT] high, the icon left of
 * the label). A suggested one carries the small «erkannt» under its label. The [ReportCategoryButton.selected]
 * one is drawn tonal (secondaryContainer) with a 2 dp primary outline and its icon in primary (0.8.6; the
 * thicker outline is the cue that does not rely on colour), the others outlined; the selection is told to
 * TalkBack. «Andere» ([open] not null) ends with ▾, turned to ▴ while its row is open. On a narrow phone with
 * a large font the icon gives way first (or, for «Andere» showing a choice, the label: the icon then says it).
 */
@Composable
private fun CategoryButton(b: ReportCategoryButton, shape: RoundedCornerShape, enabled: Boolean, open: Boolean?, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val padding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    val m = modifier.heightIn(min = REPORT_CATEGORY_HEIGHT).semantics {
        this.selected = b.selected
        contentDescription = reportCategoryDescription(b)
        if (open != null) stateDescription = if (open) REPORT_MORE_OPEN else REPORT_MORE_SHUT
    }
    val content: @Composable RowScope.() -> Unit = {
        val turn by animateFloatAsState(if (open == true) 180f else 0f, label = "more")
        SegmentContent(
            iconBeforeText = b.category == null && b.icon != null,
            icon = {
                Icon(
                    b.icon?.let { reportCategoryIcon(it) } ?: AppIcons.MoreHoriz, contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = if (b.selected && enabled) cs.primary else LocalContentColor.current,
                )
            },
            text = {
                Column {
                    Text(b.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    if (b.suggested) {
                        Text(REPORT_SUGGESTED_MARK, style = MaterialTheme.typography.labelSmall, color = cs.primary, maxLines = 1, softWrap = false)
                    }
                }
            },
            trailing = if (open == null) null else ({ Icon(AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp).rotate(turn)) }),
        )
    }
    if (b.selected) {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = m, shape = shape, contentPadding = padding, border = BorderStroke(2.dp, cs.primary), content = content)
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = m, shape = shape, contentPadding = padding, content = content)
    }
}

/**
 * 0.9.4.2: icon, label and (for «Andere») ▾ in one line, centred by the button. All three when they fit; else
 * without the icon; for «Andere» showing a chosen category ([iconBeforeText]) whose label does not fit even
 * then, the icon and ▾ alone (the truck says «Lastwagen» better than «Lastw…»); a label that still does not fit
 * is cut with «…». The full label is the button's content description either way.
 */
@Composable
private fun SegmentContent(iconBeforeText: Boolean, icon: @Composable () -> Unit, text: @Composable () -> Unit, trailing: (@Composable () -> Unit)?) {
    Layout(contents = listOf(icon, text, trailing ?: {})) { (iconM, textM, trailM), c ->
        val gap = 6.dp.roundToPx()
        val free = Constraints()
        val trail = trailM.firstOrNull()?.measure(free)
        val trailW = trail?.let { it.width + 2.dp.roundToPx() } ?: 0
        val ic = iconM.first().measure(free)
        val room = if (c.hasBoundedWidth) (c.maxWidth - trailW).coerceAtLeast(0) else Constraints.Infinity
        val want = textM.first().maxIntrinsicWidth(Constraints.Infinity)
        val showIcon: Boolean
        val showText: Boolean
        when {
            want + gap + ic.width <= room -> { showIcon = true; showText = true }
            want <= room || !iconBeforeText -> { showIcon = false; showText = true }
            else -> { showIcon = true; showText = false }
        }
        val t = if (showText) textM.first().measure(Constraints(maxWidth = if (showIcon) room - gap - ic.width else room)) else null
        val w = (if (showIcon) ic.width else 0) + (if (showIcon && t != null) gap else 0) + (t?.width ?: 0) + trailW
        val h = maxOf(if (showIcon) ic.height else 0, t?.height ?: 0, trail?.height ?: 0)
        layout(w, h) {
            var x = 0
            if (showIcon) { ic.place(x, (h - ic.height) / 2); x += ic.width + (if (t != null) gap else 0) }
            if (t != null) { t.place(x, (h - t.height) / 2); x += t.width }
            if (trail != null) trail.place(x + 2.dp.roundToPx(), (h - trail.height) / 2)
        }
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

/** 0.9.4.2 (owner: trucks and aircraft are rare): the main choices, always in the first row. */
val REPORT_MAIN_CATEGORIES: List<ReportCategory> = listOf(ReportCategory.MOTORRAD, ReportCategory.AUTO)

/** 0.9.4.2: the rarer choices, in the row «Andere» folds out. With [REPORT_MAIN_CATEGORIES]: exactly [ReportCategory.OFFERED]. */
val REPORT_MORE_CATEGORIES: List<ReportCategory> = listOf(ReportCategory.LASTWAGEN, ReportCategory.FLUGZEUG)

/** 0.9.4.2: the third button of the first row; it folds out [REPORT_MORE_CATEGORIES]. Not a category: nothing is sent as «Andere». */
const val REPORT_MORE = "Andere"

/** 0.9.4.2: what TalkBack says of «Andere»'s state. */
const val REPORT_MORE_OPEN = "ausgeklappt"
const val REPORT_MORE_SHUT = "eingeklappt"

/**
 * 0.9.4.2: one button of the category rows. [category] is what a tap selects, null for «Andere» (a tap only
 * folds its row out or in); [label] its text («Andere», or the rarer category chosen in its row, e.g.
 * «Lastwagen», so the choice stays visible with the row shut); [icon] the category whose icon it shows (null:
 * «Andere»'s own); [selected] drawn as chosen; [suggested] carries «erkannt».
 */
data class ReportCategoryButton(
    val category: ReportCategory?,
    val label: String,
    val icon: ReportCategory?,
    val selected: Boolean,
    val suggested: Boolean,
)

/** 0.9.4.2: the first row ([main]: Töff, Auto, «Andere»), the second ([more]: Lastwagen, Flugzeug), and whether the second is open. */
data class ReportCategoryRowsModel(val main: List<ReportCategoryButton>, val more: List<ReportCategoryButton>, val expanded: Boolean)

/**
 * 0.9.4.2 (owner): the category rows for [chosen] and the [suggested] categories of the «Sehr laut»
 * notification (Labor·Karte; each marked «erkannt» where it stands), with the second row [expanded] or not.
 * Until 0.9.4.1 a 2 × 2 grid with the suggested ones first (`reportCategoryOrder`, `reportCategoryRows`); now
 * the order is fixed, so a button never moves. «Andere» shows a rarer [chosen] with its label and icon and is
 * then drawn selected; with its row shut it also carries «erkannt» if a rarer one is suggested.
 */
fun reportCategoryButtons(suggested: List<ReportCategory>, chosen: ReportCategory?, expanded: Boolean): ReportCategoryRowsModel {
    fun button(c: ReportCategory) = ReportCategoryButton(c, c.label, c, selected = c == chosen, suggested = c in suggested)
    val rare = chosen?.takeIf { it in REPORT_MORE_CATEGORIES }
    val more = ReportCategoryButton(
        null, rare?.label ?: REPORT_MORE, rare, selected = rare != null,
        suggested = !expanded && suggested.any { it in REPORT_MORE_CATEGORIES },
    )
    return ReportCategoryRowsModel(REPORT_MAIN_CATEGORIES.map(::button) + more, REPORT_MORE_CATEGORIES.map(::button), expanded)
}

/**
 * 0.9.4.2: whether the row Lastwagen · Flugzeug is open when the dialog opens: when the notification suggests
 * one of them (so its «erkannt» is seen) or one is already chosen (the preselected suggestion; back from
 * «Nochmals»).
 */
fun reportMoreOpenAtStart(suggested: List<ReportCategory>, chosen: ReportCategory?): Boolean =
    chosen in REPORT_MORE_CATEGORIES || suggested.any { it in REPORT_MORE_CATEGORIES }

/**
 * 0.9.4.2: the row after a tap on [tapped]: «Andere» (null) folds it out or in; Töff or Auto shut it; Lastwagen
 * or Flugzeug leave it open (the choice is seen in its row and on «Andere»).
 */
fun reportMoreOpenAfter(tapped: ReportCategory?, open: Boolean): Boolean = when {
    tapped == null -> !open
    tapped in REPORT_MAIN_CATEGORIES -> false
    else -> open
}

/** 0.9.4.2: the button's spoken label: «Lastwagen», «Auto, erkannt», «Andere», «Andere: Lastwagen». */
fun reportCategoryDescription(b: ReportCategoryButton): String =
    (if (b.category == null && b.label != REPORT_MORE) "$REPORT_MORE: ${b.label}" else b.label) +
        (if (b.suggested) ", $REPORT_SUGGESTED_MARK" else "")

/** 0.8.1: the title of the one dialog. */
const val REPORT_TITLE = "Laut! Melden"

/** 0.8.2: the dialog's confirm button (0.8.1 had none: the category tap sent, and a line under the grid said so); 0.9.1: its main button in the content. */
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
 * [REPORT_MOVED_LINE] once the map was moved under the dot ([moved]); 0.9.2: «Standort ± 12 m · Karte
 * verschiebbar» while the map can be dragged ([draggable]).
 */
fun reportFixLine(accuracyM: Int?, moved: Boolean = false, draggable: Boolean = false): String = when {
    accuracyM == null -> REPORT_LOCATING
    moved -> REPORT_MOVED_LINE
    draggable -> "Standort ± $accuracyM m · $REPORT_DRAG_HINT"
    else -> "Standort ± $accuracyM m"
}

/**
 * 0.9.2: appended to «Standort ± n m» while the disc's map can be dragged (streets drawn, not moved yet):
 * the only sign of the drag. Once moved, [REPORT_MOVED_LINE] and «Zurück zum Standort» take over.
 */
const val REPORT_DRAG_HINT = "Karte verschiebbar"

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
    "Gesendet werden Zeit, genauer Standort, Fahrtrichtung, Pegel und Art des Lärms, nie Audio. " +
        "Der Punkt erscheint auf der Lärmkarte; wer oft vom gleichen Ort meldet, macht ihn sichtbar. " +
        "Dafür wird «Teilen» eingeschaltet; löschen kannst du alles mit «Vom Server löschen»."

/**
 * The risk server/DESIGN.md §2 rule 10 asks the report screen to state. Since 0.6.3 on the
 * confirmation of EVERY report (the consent, REPORT_CONSENT_TEXT, is skipped once sharing is on);
 * since 0.8.1 in the one «Laut! Melden» dialog under the buttons (0.8.2: above «Senden»; 0.9.1: directly
 * above the full-width «Senden», outside the scrolling part).
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
