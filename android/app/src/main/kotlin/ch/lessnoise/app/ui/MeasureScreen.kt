package ch.lessnoise.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.R
import ch.lessnoise.app.container
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.edition.EditionUi
import ch.lessnoise.app.icons.AppIcons
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.service.MeasurementService
import kotlinx.coroutines.launch

@Composable
fun MeasureScreen(
    modifier: Modifier = Modifier,
    onOpen: (Screen) -> Unit = {},
    reportPreset: ReportPreset? = null,
    onReportPresetUsed: () -> Unit = {},
) {
    val context = LocalContext.current
    val c = context.container
    val live by c.live.collectAsStateWithLifecycle()
    val events by remember { c.measurements.recentEvents(30) }.collectAsStateWithLifecycle(emptyList())
    val calibrating by c.calibrationActive.collectAsStateWithLifecycle()
    val noiseFloor by c.noiseFloor.limit.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    // Karten editions: «Auf die Lärmkarte?» once, and the fix while measuring (ShareFlow.kt, 0.6.3).
    val share = LocalShareController.current
    // «Unterwegs» (0.6.4): a measurement that shares nothing, with the «Sehr laut» notification. Since
    // 0.8.4 Labor·Karte only (LoudAlerts.AVAILABLE; the button «Unterwegs aufnehmen»); the Online-Version
    // has no measurement on the road (0.8.3's mode switch is gone): measuring means the window.
    val askUnterwegs = LoudAlerts.rememberUnterwegsPermission()
    var pendingUnterwegs by remember { mutableStateOf(false) }
    fun startMeasurement(unterwegs: Boolean) {
        val road = unterwegs && LoudAlerts.AVAILABLE
        when {
            share != null -> share.start(road)
            else -> MeasurementService.start(context, unterwegs = road)
        }
        if (road) askUnterwegs()
    }
    val requestPermission = rememberMicPermissionLauncher { granted ->
        if (granted) startMeasurement(pendingUnterwegs)
    }
    fun onStart(unterwegs: Boolean) {
        // 0.7.5, Labor editions only (always null in the public editions): the audio budget is used up.
        EditionUi.startRefusal(context)?.let { msg -> scope.launch { snackbar.showSnackbar(msg) }; return }
        pendingUnterwegs = unterwegs
        if (hasMicPermission(context)) startMeasurement(unterwegs) else requestPermission()
    }
    val report = if (BuildConfig.UPLOAD_AVAILABLE) {
        rememberReportAction(
            onQueued = { scope.launch { snackbar.showSnackbar("Meldung wird gesendet.") } },
            // From the shortcut / widget (0.8.4) and, Labor·Karte, the «Sehr laut» and «Melden» notification
            // taps: the dialog opens directly, also while measuring.
            preset = reportPreset, onPresetUsed = onReportPresetUsed,
        )
    } else null
    val status = if (share != null) rememberShareStatus() else null

    MeasureContent(
        live = live, events = events, calibrating = calibrating, modifier = modifier,
        noiseFloor = noiseFloor,
        categoryName = { categoryName(context, it) },
        // «Messung starten»: the window measurement (shared if sharing is on), as «Messen» always did.
        onStart = { onStart(unterwegs = false) },
        onStop = { MeasurementService.stop(context) },
        onReport = report,
        updateLine = { UpdateReminderLine() },
        eventMarker = { EditionUi.EventMarker(it) },
        shareStatus = status,
        onShareStatus = { a -> handleShareAction(a, share, onOpen) },
        // Labor·Karte only: «Unterwegs aufnehmen» starts with EXTRA_UNTERWEGS, as 0.8.3's «Unterwegs» did.
        onUnterwegs = if (LoudAlerts.AVAILABLE) ({ onStart(unterwegs = true) }) else null,
        storageNotice = { EditionUi.StorageNotice() },
    )
}

/** What a tap on the status chip does: open a tab, or let the controller act. */
fun handleShareAction(a: ShareAction, share: ShareController?, onOpen: (Screen) -> Unit) {
    when (a) {
        ShareAction.OPEN_MAP -> onOpen(Screen.MAP)
        ShareAction.OPEN_SHARE -> onOpen(Screen.SHARE)
        else -> share?.onStatus(a)
    }
}

/** The status chip for the current state (Karten editions), from the live state, the upload and the fix. */
@Composable
fun rememberShareStatus(): ShareStatus? {
    val c = LocalContext.current.container
    val live by c.live.collectAsStateWithLifecycle()
    val s by c.upload.store.state.collectAsStateWithLifecycle()
    val locate by c.shareLocate.collectAsStateWithLifecycle()
    val online = rememberOnline()
    return shareStatus(
        s = s, running = live.running, measurementStartMs = live.measurementStartMs,
        hasPoint = live.sharedPoint != null, locate = locate, online = online, unterwegs = live.unterwegs, hm = { Fmt.sentAt(it) },
        moving = live.moving,
    )
}

/** The Material icon of a status chip. */
fun statusIcon(icon: StatusIcon): ImageVector = when (icon) {
    StatusIcon.ON_MAP -> AppIcons.CloudDone
    StatusIcon.SHARING -> AppIcons.CloudUpload
    StatusIcon.OFF_MAP -> AppIcons.CloudOff
    StatusIcon.LOCATING -> AppIcons.MyLocation
    StatusIcon.NO_LOCATION -> AppIcons.LocationOff
    StatusIcon.UNTERWEGS -> AppIcons.Walk
}

/**
 * A chip-shaped row (8 dp corners, outline, leading icon, short label; the look of Material's assist
 * and suggestion chips), tappable only with [onClick]. Shared by the status chip and «Klingt nach».
 * Not Material's SuggestionChip itself: that one always takes a click (or is drawn and announced as
 * disabled), and «Klingt nach» is a label, not an action.
 */
@Composable
fun InfoChip(
    text: String,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    problem: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = RoundedCornerShape(8.dp),
        // A problem shows in the icon and the outline (error colour), not as a pink fill.
        color = cs.surfaceContainerLow,
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, if (problem) cs.error else cs.outlineVariant),
        modifier = modifier.heightIn(min = 36.dp),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = if (trailing != null) 8.dp else 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                trailing()
            }
        }
    }
}

/**
 * The status chip (0.8.3; replaces the beige/pink status card of 0.6.3): icon, short text,
 * «›» when a tap does something. Problems (no location, not sent) in the error colours. Also the
 * banner above the map (MapScreen.kt) and the line in «Teilen».
 */
@Composable
fun StatusChip(status: ShareStatus, onTap: (ShareAction) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tappable = status.action != ShareAction.NONE
    InfoChip(
        text = status.text,
        modifier = modifier,
        onClick = if (tappable) ({ onTap(status.action) }) else null,
        problem = status.problem,
        leading = {
            Icon(
                statusIcon(status.icon), contentDescription = null, modifier = Modifier.size(18.dp),
                tint = when {
                    status.problem -> cs.error
                    status.icon == StatusIcon.ON_MAP -> cs.tertiary
                    else -> cs.primary
                },
            )
        },
        trailing = when {
            status.icon == StatusIcon.LOCATING -> ({ CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) })
            tappable -> ({
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            })
            else -> null
        },
    )
}

/**
 * «Messen» without the app container (also rendered on the JVM for the screenshots). Since 0.8.4 no
 * mode switch: idle means the person holds the phone (on the road), running means it lies at a
 * window. Idle ([IdleMeasure], 0.8.7): the title, the app's logo with its name, one line and the
 * status chip in the middle of the free space, the actions at the bottom within thumb reach — editions
 * with the map ([onReport] set): «Laut! Melden» (filled), «Messung starten» (tonal) with one line,
 * (Labor·Karte, [onUnterwegs] set) «Unterwegs aufnehmen» (outlined) with its Labor line, «Mehr dazu»
 * (a bottom sheet). Without reports (Offline, Labor): «Messung starten» filled. While measuring: the
 * level card (level, bar, Grundrauschen, «Klingt nach»), «Stoppen», «Läuft seit …», the status chip,
 * the last Lärmspitzen — and no «Laut! Melden» (the phone lies at the window; the shortcut and the
 * widget still open it).
 */
@Composable
fun MeasureContent(
    live: LiveState,
    events: List<EventEntity>,
    calibrating: Boolean,
    categoryName: (String?) -> String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onReport: (() -> Unit)? = null,
    updateLine: @Composable () -> Unit = {},
    eventMarker: @Composable (Long) -> Unit = {},
    shareStatus: ShareStatus? = null,
    onShareStatus: (ShareAction) -> Unit = {},
    /** 0.8.4, Labor·Karte only: «Unterwegs aufnehmen» (EXTRA_UNTERWEGS); null: no such button (every other edition). */
    onUnterwegs: (() -> Unit)? = null,
    /** 0.6.5: the phone's noise floor; «Grundrauschen» shows «≤ …» at it. Null: no floor yet. */
    noiseFloor: ch.lessnoise.dsp.NoiseFloor.Limit? = null,
    /** 0.7.5, Labor editions: the red storage line («Speicher fast voll …»); nothing in the public editions. */
    storageNotice: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme

    Scaffold(
        modifier = modifier,
        containerColor = cs.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        if (!live.running) {
            IdleMeasure(
                live = live, calibrating = calibrating, onStart = onStart, onReport = onReport, onUnterwegs = onUnterwegs,
                shareStatus = shareStatus, onShareStatus = onShareStatus, updateLine = updateLine, storageNotice = storageNotice,
                modifier = Modifier.padding(inner),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(inner).fillMaxWidth()) {
            item { ScreenTitle("Messen") }

            if (live.micSilenced || live.invalidAudio) {
                item {
                    SectionCard {
                        Text(
                            if (live.micSilenced) "Mikrofon ist stumm (z. B. Anruf). Diese Zeit zählt nicht."
                            else "Kein Mikrofonsignal. Diese Zeit zählt nicht.",
                            color = cs.error, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                SectionCard {
                    Text("Jetzt", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Fmt.db(live.lafDb), fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("dB(A)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    LevelBar(live.lafDb)
                    Spacer(Modifier.height(2.dp))
                    StatRow("Grundrauschen", humText(live.backgroundDb, liveHumLimit(live, noiseFloor)))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Klingt nach", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        CategoryChip(live.dominantCategory, categoryName(live.dominantCategory))
                    }
                }
            }

            item {
                // Running: «Stoppen» (STACK_BUTTON_HEIGHT, stackButtonLabel as the idle buttons), the line
                // 4 dp under it, 16 dp before the status chip.
                Column(Modifier.fillMaxWidth().padding(horizontal = SCREEN_GUTTER, vertical = 8.dp)) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth().height(STACK_BUTTON_HEIGHT),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                    ) {
                        Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(STOP_BUTTON, style = stackButtonLabel())
                    }
                    Spacer(Modifier.height(4.dp))
                    HelperText(runningLine(Fmt.hm(live.startedAtMs)))
                    // 0.7.2: which microphone, unless it is the plain default (built-in, nothing chosen).
                    microphoneNote(live)?.let { HelperText(it) }
                    storageNotice()
                    shareStatus?.let {
                        Spacer(Modifier.height(16.dp))
                        StatusChip(it, onShareStatus)
                    }
                    // 0.6.9, Labor·Karte only (null elsewhere): motion value, distance from the start, state.
                    live.unterwegsReadout?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    live.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                }
            }

            item { SectionHeading("Letzte Lärmspitzen") }
            if (events.isEmpty()) {
                item { HelperText("Noch keine.", Modifier.padding(horizontal = SCREEN_GUTTER, vertical = 8.dp)) }
            }
            items(events, key = { it.id }) { e -> EventRow(e, categoryName, eventMarker) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 0.8.7: the logo's circle on the idle «Messen» screen. */
val IDLE_LOGO_SIZE = 120.dp

/**
 * 0.8.7: the app's logo as on the home screen: the launcher foreground (the edition's own artwork,
 * R.drawable.ic_launcher_foreground: the level bars; Labor with the record dot) on a circle of the
 * launcher background (R.color.logo_background: dark blue, Labor red). An adaptive icon shows the
 * middle 72 of its 108 dp, so the foreground is drawn at 1.5 × the circle and clipped to it.
 */
@Composable
fun AppLogo(modifier: Modifier = Modifier, size: Dp = IDLE_LOGO_SIZE) {
    Box(
        modifier.size(size).clip(CircleShape).background(colorResource(R.color.logo_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}

/**
 * 0.8.7, the idle «Messen» screen in three zones: the title on top (as on every tab); in the middle of
 * the free space the logo, «Weniger Lärm», one line and the status chip; at the bottom (16 dp
 * margins, within thumb reach) the actions. The column is at least as tall as the screen, so the
 * weighted spacers share the free space; when the content is taller (small phone, large font) it
 * scrolls instead of clipping and the spacers shrink to their fixed minimum.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdleMeasure(
    live: LiveState,
    calibrating: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    onReport: (() -> Unit)? = null,
    onUnterwegs: (() -> Unit)? = null,
    shareStatus: ShareStatus? = null,
    onShareStatus: (ShareAction) -> Unit = {},
    updateLine: @Composable () -> Unit = {},
    storageNotice: @Composable () -> Unit = {},
) {
    var more by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScreenTitle("Messen", Modifier.align(Alignment.Start))
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.weight(IDLE_WEIGHT_ABOVE))
            // Middle: logo, name, line, chip.
            AppLogo()
            Spacer(Modifier.height(16.dp))
            Text(APP_NAME, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                idleTagline(reports = onReport != null), style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = SCREEN_GUTTER),
            )
            // 0.8.8: only when there is something to act on (showOnIdle); «Teilen» shows the full status.
            // Not while it starts.
            if (!live.starting) shareStatus?.takeIf(::showOnIdle)?.let {
                Spacer(Modifier.height(20.dp))
                StatusChip(it, onShareStatus, Modifier.padding(horizontal = SCREEN_GUTTER))
            }
            Spacer(Modifier.weight(IDLE_WEIGHT_BELOW))
            Spacer(Modifier.height(24.dp))
            // Bottom: the actions.
            // 0.8.6: one button height (STACK_BUTTON_HEIGHT) and one label style for every stacked
            // button, the hierarchy only by style (filled → tonal → outlined); each helper line 4 dp
            // under its button, 16 dp between the groups.
            Column(Modifier.fillMaxWidth().padding(start = SCREEN_GUTTER, end = SCREEN_GUTTER, bottom = 8.dp)) {
                // Editions with the map: «Laut! Melden» is the first action (idle = on the road);
                // the FAB of 0.8.3 is gone, this button does its job.
                if (onReport != null) {
                    Button(
                        onClick = onReport,
                        enabled = !live.starting,
                        modifier = Modifier.fillMaxWidth().height(STACK_BUTTON_HEIGHT),
                    ) {
                        Icon(AppIcons.Campaign, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(REPORT_BUTTON, style = stackButtonLabel())
                    }
                    Spacer(Modifier.height(4.dp))
                    HelperText(REPORT_HINT)
                    Spacer(Modifier.height(16.dp))
                }
                val startEnabled = !live.starting && !calibrating
                val startContent: @Composable RowScope.() -> Unit = {
                    if (!live.starting) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    Text(if (live.starting) STARTING_BUTTON else START_BUTTON, style = stackButtonLabel())
                }
                if (onReport != null) {
                    FilledTonalButton(onClick = onStart, enabled = startEnabled, modifier = Modifier.fillMaxWidth().height(STACK_BUTTON_HEIGHT), content = startContent)
                } else {
                    Button(onClick = onStart, enabled = startEnabled, modifier = Modifier.fillMaxWidth().height(STACK_BUTTON_HEIGHT), content = startContent)
                }
                Spacer(Modifier.height(4.dp))
                HelperText(START_HINT)
                // Labor·Karte only: the road recording, to compare the «Sehr laut» prompt with manual taps.
                if (onUnterwegs != null) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onUnterwegs, enabled = startEnabled, modifier = Modifier.fillMaxWidth().height(STACK_BUTTON_HEIGHT)) {
                        Icon(AppIcons.Walk, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(UNTERWEGS_BUTTON, style = stackButtonLabel())
                    }
                    Spacer(Modifier.height(4.dp))
                    HelperText(UNTERWEGS_LABOR_HINT)
                }
                if (calibrating) HelperText("Erst nach dem Kalibrieren möglich.", Modifier.padding(top = 4.dp))
                storageNotice()
                live.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                updateLine()
                Spacer(Modifier.height(8.dp))
                // «Mehr dazu» opens a bottom sheet (0.8.7; it folded out under the buttons until 0.8.6):
                // the layout above stays where it is.
                TextButton(
                    onClick = { more = true },
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(MORE_LABEL)
                }
            }
        }
    }
    if (more) {
        ModalBottomSheet(onDismissRequest = { more = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            MoreSheetContent()
        }
    }
}

/** 0.8.7: the free space above and below the logo block; a little more below, so the block sits optically centred. */
const val IDLE_WEIGHT_ABOVE = 1f
const val IDLE_WEIGHT_BELOW = 1.2f

/** 0.8.7: the content of the «Mehr dazu» bottom sheet (the texts that folded out under the buttons until 0.8.6). */
@Composable
fun MoreSheetContent() {
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(MORE_LABEL, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(PLACEMENT_HINT, style = MaterialTheme.typography.bodyMedium)
        Text(EditionUi.MIC_LINE, style = MaterialTheme.typography.bodyMedium)
        MoreLink(Links.MEASURE, GUIDE_LINK)
    }
}

/** «Klingt nach»: the category as a chip with its colour dot. */
@Composable
fun CategoryChip(id: String?, name: String) {
    InfoChip(
        text = name,
        leading = { Box(Modifier.size(12.dp).clip(CircleShape).background(categoryColor(id))) },
    )
}

/**
 * The level bar (0.8.3: rounded 8 dp, Material's LinearProgressIndicator), 20–100 dB. Colours from
 * the scheme: primary below 60 dB, tertiary from 60, error from 75 dB.
 */
@Composable
fun LevelBar(db: Double?) {
    val lo = 20.0
    val hi = 100.0
    val p = if (db == null || db.isNaN()) 0f else ((db - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()
    val cs = MaterialTheme.colorScheme
    val color = when {
        db == null || db.isNaN() || db < 60 -> cs.primary
        db < 75 -> cs.tertiary
        else -> cs.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { p },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = cs.outlineVariant,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Row(Modifier.fillMaxWidth()) {
            Text("20", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("60", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("100 dB", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

/** One Lärmspitze (0.8.3: a Material list item): category dot, category, time, level, edition marker. */
@Composable
fun EventRow(e: EventEntity, categoryName: (String?) -> String, marker: @Composable (Long) -> Unit = {}) {
    ListItem(
        headlineContent = { Text(categoryName(e.dominantCategory ?: "n/a"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(Fmt.hm(e.startEpochMs)) },
        leadingContent = { Box(Modifier.size(12.dp).clip(CircleShape).background(categoryColor(e.dominantCategory))) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${Fmt.db(e.lafMaxDb)} dB(A)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                marker(e.id)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * 0.6.5: the «≤» value of the live «Grundrauschen» (the 5-min L90, [LiveState.backgroundDb]) if it
 * is at the phone's noise floor: converted to raw with the running measurement's offset and
 * compared with floorRaw + 3 dB of the same audio source. Null otherwise.
 */
fun liveHumLimit(live: LiveState, floor: ch.lessnoise.dsp.NoiseFloor.Limit?): Double? {
    // 0.7.2: the floor is that of the built-in microphone.
    if (!ch.lessnoise.app.audio.MicChoice.isBuiltIn(live.micKey)) return null
    val bg = live.backgroundDb ?: return null
    val offset = live.calibrationOffsetDb ?: return null
    val source = live.audioSource ?: return null
    if (floor == null || !floor.atFloorRaw(bg - offset, source)) return null
    // Shown with the running measurement's offset, like the level next to it.
    return floor.floorRawDb + offset + ch.lessnoise.dsp.NoiseFloor.MARGIN_DB
}

/**
 * 0.7.2: the line «Mikrofon: …» under «Läuft seit …» while measuring — only when it says something:
 * another microphone is in use, or the chosen one is not connected / not usable. Null for the plain
 * default «Mikrofon: eingebaut».
 */
fun microphoneNote(live: LiveState): String? =
    live.microphone?.takeIf { live.running && it != ch.lessnoise.app.audio.MicChoice.STATUS_BUILTIN }
