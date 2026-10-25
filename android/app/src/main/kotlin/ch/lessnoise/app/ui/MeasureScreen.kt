package ch.lessnoise.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.BuildConfig
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
    // «Unterwegs» (0.6.4, editions with the map): a start mode that shares nothing. Since 0.8.3 chosen
    // with the mode switch at the top (remembered, MeasureModeStore) instead of a second start button.
    val askUnterwegs = LoudAlerts.rememberUnterwegsPermission()
    var mode by remember { mutableStateOf(if (LoudAlerts.AVAILABLE) MeasureModeStore.load(context) else MeasureMode.WINDOW) }
    var pendingUnterwegs by remember { mutableStateOf(false) }
    fun startMeasurement(unterwegs: Boolean) {
        when {
            share != null -> share.start(unterwegs)
            else -> MeasurementService.start(context, unterwegs = unterwegs && LoudAlerts.AVAILABLE)
        }
        if (unterwegs) askUnterwegs()
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
            // From the «Sehr laut» notification (0.6.3, editions with the map): also while measuring.
            preset = reportPreset, onPresetUsed = onReportPresetUsed,
        )
    } else null
    val status = if (share != null) rememberShareStatus() else null

    MeasureContent(
        live = live, events = events, calibrating = calibrating, modifier = modifier,
        noiseFloor = noiseFloor,
        categoryName = { categoryName(context, it) },
        // «Am Fenster» starts as «Messen» did, «Unterwegs» with EXTRA_UNTERWEGS as the «Unterwegs» button did.
        onStart = { onStart(unterwegs = LoudAlerts.AVAILABLE && mode == MeasureMode.ROAD) },
        onStop = { MeasurementService.stop(context) },
        onReport = report,
        updateLine = { UpdateReminderLine() },
        eventMarker = { EditionUi.EventMarker(it) },
        shareStatus = status,
        onShareStatus = { a -> handleShareAction(a, share, onOpen) },
        // The mode switch: only in the editions with the map (src/karte); none elsewhere.
        mode = if (LoudAlerts.AVAILABLE) mode else null,
        onMode = { m -> mode = m; MeasureModeStore.save(context, m) },
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
        hasPoint = live.sharedPoint != null, locate = locate, online = online, unterwegs = live.unterwegs, hm = Fmt::hm,
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
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
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
 * 0.8.3: the mode switch «Am Fenster» | «Unterwegs» (Material segmented buttons). Disabled while a
 * measurement runs or starts; the selected segment keeps its colours then, so the mode stays readable,
 * and a tap on the row says «Zum Wechseln zuerst stoppen».
 */
@Composable
fun ModeSwitch(shown: ShownMode, enabled: Boolean, onMode: (MeasureMode) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var lockedTap by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (enabled) lockedTap = false }
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = cs.primaryContainer,
        activeContentColor = cs.onPrimaryContainer,
        activeBorderColor = cs.outline,
        disabledActiveContainerColor = cs.primaryContainer,
        disabledActiveContentColor = cs.onPrimaryContainer,
        disabledActiveBorderColor = cs.outline,
        disabledInactiveContainerColor = Color.Transparent,
        disabledInactiveContentColor = cs.onSurfaceVariant.copy(alpha = 0.6f),
        disabledInactiveBorderColor = cs.outline,
    )
    Column(modifier) {
        Box {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MeasureMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = shown.segment == m,
                        onClick = { onMode(m) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = MeasureMode.entries.size),
                        colors = colors,
                        icon = {
                            Icon(
                                if (m == MeasureMode.WINDOW) AppIcons.Window else AppIcons.Walk,
                                contentDescription = null, modifier = Modifier.size(18.dp),
                            )
                        },
                    ) { Text(modeLabel(m, shown), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (!enabled) {
                // Disabled segments take no taps; this layer does, to say why nothing changes.
                Box(
                    Modifier.matchParentSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { lockedTap = true },
                )
            }
        }
        if (lockedTap && !enabled) {
            Text(
                MODE_LOCKED, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

/**
 * «Messen» without the app container (also rendered on the JVM for the screenshots). Since 0.8.3: the
 * mode switch (editions with the map), one primary button with one line under it, the status chip,
 * «Laut! Melden» (a floating button in «Unterwegs», an outlined button under «Messung starten» at the
 * window), «Mehr dazu» folded. While measuring: the level card (level, bar, Grundrauschen, «Klingt
 * nach») and the last Lärmspitzen.
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
    /** 0.8.3, editions with the map: the stored mode (the switch at the top); null: no switch (Offline, Labor). */
    mode: MeasureMode? = null,
    onMode: (MeasureMode) -> Unit = {},
    /** 0.6.5: the phone's noise floor; «Grundrauschen» shows «≤ …» at it. Null: no floor yet. */
    noiseFloor: ch.lessnoise.dsp.NoiseFloor.Limit? = null,
    /** 0.7.5, Labor editions: the red storage line («Speicher fast voll …»); nothing in the public editions. */
    storageNotice: @Composable () -> Unit = {},
    /** For the screenshots: «Mehr dazu» unfolded at first. */
    moreOpen: Boolean = false,
) {
    val active = live.running || live.starting
    val shown = mode?.let { shownMode(it, active, live.unterwegs, live.moving) }
    val road = shown?.segment == MeasureMode.ROAD
    // «Laut! Melden» is the obvious action on the road: a floating button (also while it measures,
    // also while «Unterwegs erkannt»). At the window: an outlined button while idle, as before.
    val fab = onReport != null && road && !live.starting
    var more by remember { mutableStateOf(moreOpen) }
    val cs = MaterialTheme.colorScheme

    Scaffold(
        modifier = modifier,
        containerColor = cs.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            onReport?.takeIf { fab }?.let { report ->
                ExtendedFloatingActionButton(
                    onClick = report,
                    icon = { Icon(AppIcons.Campaign, contentDescription = null) },
                    text = { Text(REPORT_BUTTON) },
                )
            }
        },
    ) { inner ->
        LazyColumn(Modifier.padding(inner).fillMaxWidth()) {
            item {
                Text(
                    "Messen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            if (mode != null && shown != null) {
                item {
                    ModeSwitch(
                        shown = shown, enabled = !active, onMode = onMode,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    )
                }
            }

            if (live.running && (live.micSilenced || live.invalidAudio)) {
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

            if (live.running) {
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
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (live.running) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError),
                        ) {
                            Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(STOP_BUTTON, fontSize = 18.sp)
                        }
                        Text(runningLine(Fmt.hm(live.startedAtMs)), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        // 0.7.2: which microphone, unless it is the plain default (built-in, nothing chosen).
                        microphoneNote(live)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    } else {
                        Button(
                            onClick = onStart,
                            enabled = !live.starting && !calibrating,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            if (!live.starting) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            }
                            Text(if (live.starting) STARTING_BUTTON else START_BUTTON, fontSize = 18.sp)
                        }
                        Text(idleHint(shown?.segment), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        // «Laut! Melden» at the window (Karten editions only), while no measurement runs.
                        if (onReport != null && !road && !live.starting) {
                            OutlinedButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                                Icon(AppIcons.Campaign, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(REPORT_BUTTON)
                            }
                        }
                    }
                    if (calibrating) Text("Erst nach dem Kalibrieren möglich.", style = MaterialTheme.typography.bodySmall)
                    storageNotice()
                    // Running: always; idle: the short form (e.g. «Auf der Karte · 14:05»), not while it starts.
                    if (live.running || !live.starting) shareStatus?.let { StatusChip(it, onShareStatus) }
                    // 0.6.9, Labor·Karte only (null elsewhere): motion value, distance from the start, state.
                    if (live.running) live.unterwegsReadout?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (!live.running) updateLine()
                    live.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium) }
                    if (!live.running) {
                        TextButton(
                            onClick = { more = !more },
                            contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                            // The label in line with the text above (the button's own 12 dp start padding).
                            modifier = Modifier.offset(x = (-12).dp),
                        ) {
                            Text(MORE_LABEL)
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Icon(
                                if (more) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                        }
                        AnimatedVisibility(more) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(PLACEMENT_HINT, style = MaterialTheme.typography.bodyMedium)
                                Text(EditionUi.MIC_LINE, style = MaterialTheme.typography.bodyMedium)
                                MoreLink(Links.MEASURE, GUIDE_LINK)
                            }
                        }
                    }
                }
            }

            if (live.running) {
                item {
                    Text(
                        "Letzte Lärmspitzen",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
                if (events.isEmpty()) {
                    item {
                        Text(
                            "Noch keine.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(events, key = { it.id }) { e -> EventRow(e, categoryName, eventMarker) }
            }
            // Room under the floating button, so the last row can be scrolled clear of it.
            item { Spacer(Modifier.height(if (fab) 96.dp else 24.dp)) }
        }
    }
}

/** The button «Laut! Melden» (floating in «Unterwegs», outlined at the window). */
const val REPORT_BUTTON = "Laut! Melden"

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
