package ch.lessnoise.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.GeomagneticField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.lessnoise.app.compass.Compass
import ch.lessnoise.app.upload.CompassReading
import ch.lessnoise.app.upload.ReportDirection
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import ch.lessnoise.app.icons.AppIcons
import androidx.compose.ui.draw.alpha

/**
 * The compass of «Laut! Melden» (0.6.7, editions with the map; the Offline and Labor builds get the
 * stand-in in src/nomap). Until 0.7.8 it was its own step «Wohin fuhr es?» after «Was war laut?»;
 * since 0.8.1 (Labor·Karte, branch labor-one-tap) it is the top of the one «Laut! Melden» dialog
 * (LocationFlows.kt): that dialog owns the compass ([rememberReading]) and reads the current value
 * at the instant «Senden» is tapped (0.8.2; in 0.8.1 the category tap, which sent); [Panel] draws the disc. No new
 * permission. On a phone without a compass [rememberReading] is null and no disc is drawn.
 */
object ReportDirectionUi {
    const val AVAILABLE = true

    /**
     * 0.8.1: the line under the live label; 0.9.2: above the disc, under «Wohin fuhr es?». 0.9.2 (owner): always shown — until 0.9.1 [CALIBRATE_HINT]
     * replaced it while the compass was unreliable, and then nothing said what the arrow is for.
     */
    const val POINT_HINT = "Telefon in die Fahrtrichtung halten"

    /** 0.9.4.5: the «×» at the disc (TalkBack), what the heading says instead of the bearing, and the button that turns it back on. */
    const val DIRECTION_OFF = "Richtung weglassen"
    const val DIRECTION_NONE = "Ohne Richtung"
    const val DIRECTION_ON = "Richtung angeben"

    /** 0.9.4.5: the hint line while the direction is off (one line, like [POINT_HINT]). */
    const val POINT_HINT_OFF = "Es wird keine Fahrtrichtung gesendet."

    /** 0.9.4.5: the disc's opacity while the direction is off. */
    private const val OFF_ALPHA = 0.38f

    /** 0.9.2: an additional line under [POINT_HINT] (error colour) while the compass is unreliable, never instead of it. */
    const val CALIBRATE_HINT = "Kompass ungenau: Telefon in Form einer Acht bewegen."

    /**
     * 0.8.1: the disc's size (0.7.8: 240 dp in its own dialog); it still shows 300 m across. 0.9.4.2: the
     * largest size; the dialog passes the size that fits the screen (`reportDiscDp`, [REPORT_DISC_MIN_DP]–[REPORT_DISC_MAX_DP]).
     * 0.9.6.1 (Labor·Karte first): the map card's largest HEIGHT (its width is the dialog's), 300 dp.
     */
    const val CARD_DP = REPORT_DISC_MAX_DP

    /** 0.9.6.1: the map card's corners (and its 1 dp outline in outlineVariant). */
    const val CARD_CORNER_DP = 16

    /**
     * 0.9.6.1: the chip at the map card's bottom while the map can be dragged and has not been yet in this dialog
     * (the pattern of ride-hailing and delivery apps: a fixed marker, the map moves under it). Replaces
     * «· Karte verschiebbar» (0.9.2–0.9.6) at the end of the status line.
     */
    const val PAN_HINT = "Karte verschieben"

    /**
     * Magnetic declination (degrees, east positive) at the report's own place and time, from
     * Android's built-in World Magnetic Model (GeomagneticField: computed on the phone, no network).
     * Height 0: the declination hardly changes with altitude.
     */
    fun declination(lat: Double, lon: Double, timeMs: Long): Double? =
        try {
            GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, timeMs).declination.toDouble().takeIf { it.isFinite() }
        } catch (_: Exception) {
            null
        }

    /** The figure-eight hint only while the compass reports low or unreliable accuracy (status 0 or 1). */
    fun showCalibrationHint(sensorStatus: Int): Boolean = sensorStatus in 0..1

    /** «Nordost · 47°» under the arrow (magnetic, while pointing; the report stores true north). */
    fun liveLabel(magneticDeg: Double): String {
        val d = ReportDirection.roundDeg(magneticDeg)
        return ReportDirection(d, ReportDirection.North.MAGNETIC, ReportDirection.Accuracy.HIGH).label.removePrefix("Richtung ") + " · $d°"
    }


    /** 0.7.8: the credit under the disc while swisstopo's tiles are drawn; 0.9.6.1: inside the map card's bottom-right corner. */
    const val TILE_ATTRIBUTION = "© swisstopo"

    /**
     * The live compass for as long as the calling dialog is in the composition (0.8.1; until 0.7.8
     * inside the step dialog): the latest reading as state, or null when the phone has no compass
     * (then the report carries no direction). While it is composed the screen keeps its rotation
     * (turning the phone flat or upright must not recreate the activity, which would end the report,
     * nor turn the arrow); the sensors run only while the app is in front.
     */
    @Composable
    fun rememberReading(): State<CompassReading?>? {
        val context = LocalContext.current
        val reading = remember { mutableStateOf<CompassReading?>(null) }
        val compass = remember {
            Compass(context, displayRotation = { displayRotation(context) }) { deg, status -> reading.value = CompassReading(deg, status) }
        }
        DisposableEffect(Unit) {
            val activity = context.findActivity()
            val before = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            onDispose { if (activity != null && before != null) activity.requestedOrientation = before }
        }
        LifecycleResumeEffect(compass) {
            compass.start()
            onPauseOrDispose { compass.stop() }
        }
        return if (compass.available) reading else null
    }

    /** 0.8.1: after a drag ends, wait this long before the tiles around the new centre are requested (a new touch cancels it). */
    const val TILE_DEBOUNCE_MS = 300L

    /**
     * The disc (0.8.1, 200 dp; the drawing of 0.7.8) with the live label «Nordost · 47°» under it; 0.9.2:
     * above it [POINT_HINT], always, plus [CALIBRATE_HINT] while the compass is unreliable (until 0.9.1 one
     * line under the label, the figure-eight hint instead of [POINT_HINT]). [reading] is the
     * caller's [rememberReading] (read here, so that only this panel follows the sensor). [fix]
     * (latitude, longitude) is the report's location fix once it is there, null while it is
     * searched: the real streets around it are drawn under the arrow (0.7.8 Labor·Karte; since
     * 0.8.0 also the Online-Version — every edition with the map, no edition flag).
     *
     * 0.8.1 (Labor·Karte; published in 0.9.0): once the streets are shown, a one-finger drag on the
     * disc moves the map under the dot, which stays at the centre (the place to report when the
     * car drove on or the fix is off). [moved] is the place under the dot (null: the fix, not
     * moved); every move calls [onMove] with the new place, which the caller takes (true) or
     * refuses (false, e.g. outside Switzerland: the map stops there); the caller resets by passing
     * null again. The drag is turned back by the disc's
     * rotation ([ReportMapTiles.screenDragToMapOffset]), the disc does not follow the compass while
     * the finger is down, and the map stops [ReportMapTiles.MAX_DRAG_M] from the fix.
     *
     * 0.9.6.1 (Labor·Karte first; owner: the arrow did not say «the vehicle», and nothing showed that the map can be
     * dragged): the round disc is a **map card** — a rounded rectangle the full width of the dialog, [cardHeight]
     * high, its width still [ReportMapTiles.VIEW_M] across — with a fixed marker at its centre and the map moving
     * under it, the pattern of ride-hailing and delivery apps. The marker is a **car seen from above**, nose up the
     * screen (where the phone points = where it drove), with two chevrons ahead of it ([MapCanvas], [drawCar]); its
     * centre is the reported place. Over the map: no compass rose, a small north badge top-left ([NorthBadge]),
     * «×» top-right, «© swisstopo» bottom-right and, until the first drag of this dialog, the chip [PAN_HINT]
     * bottom-centre. Once moved, the reporter's own fix is the blue dot on the map. Without the map (no fix or
     * reading yet, outside Switzerland, no tiles) the rose of 0.7.6 with the car at its centre. 0.9.2's
     * `onDraggable` (for «· Karte verschiebbar» in the status line) is gone with that text: the chip says it at
     * the map itself.
     */
    @Composable
    fun Panel(
        reading: State<CompassReading?>,
        fix: Pair<Double, Double>?,
        moved: Pair<Double, Double>?,
        /** 0.8.6, for the JVM renders only (ReportDialogDeviceScreenshots): tiles drawn instead of downloading any; null in the app. */
        previewTiles: Map<ReportMapTiles.Tile, ImageBitmap>? = null,
        /** 0.9.4.2: the disc's side, from the height the dialog has left (ReportDialogBody, `reportDiscDp`); 0.9.6.1: the map card's height. */
        cardHeight: Dp = CARD_DP.dp,
        /**
         * 0.9.4.2: true for the dialog's measuring pass only: the hint lines and the live label without the disc
         * (no canvas, no tiles requested, no effects), so the dialog learns how much height the rest needs.
         */
        measureOnly: Boolean = false,
        /**
         * 0.9.4.5 (Labor·Karte first): whether a direction is given. «×» ([DIRECTION_OFF], a 40 dp button; 0.9.6.1 inside
         * the card's top-right corner on a round surface) calls [onDirectionOn] with false: the card is then drawn faint
         * without the car, a place dot at its centre, with [DIRECTION_NONE] and the button [DIRECTION_ON] over it, which
         * calls it with true. The map can still be dragged (the place stays correctable); the caller sends no direction.
         * Null: no «×» (the renders of older states).
         */
        directionOn: Boolean = true,
        onDirectionOn: ((Boolean) -> Unit)? = null,
        onMove: (Pair<Double, Double>) -> Boolean,
    ) {
        if (measureOnly) {
            PanelLines(reading.value, directionOn) {}
            return
        }
        // While the finger is down: the heading at touch-down (the disc is frozen), and no tile requests.
        var frozenDeg by remember(fix) { mutableStateOf<Double?>(null) }
        var dragging by remember(fix) { mutableStateOf(false) }
        // 0.9.6.1: whether the map was dragged in this dialog — the chip [PAN_HINT] then goes and does not come back
        // (also not after «Zurück zum Standort»). A place already moved when the panel appears counts as dragged.
        var dragged by remember { mutableStateOf(moved != null) }
        // 0.9.6.1: the card's size in pixels, for the tiles that cover it in every rotation (its half-diagonal).
        var cardPx by remember { mutableStateOf(IntSize.Zero) }
        val centre = moved ?: fix
        val cover = ReportMapTiles.coverRadiusM(cardPx.width.toDouble(), cardPx.height.toDouble())
        val tiles = previewTiles ?: rememberStreetTiles(fix, centre.takeUnless { dragging || cardPx.width == 0 }, cover)
        // Map north is true north; the compass reads magnetic north. The tiles are turned by the
        // true heading (declination at the fix, Android's built-in model, no network).
        val declination = remember(fix) { fix?.let { declination(it.first, it.second, System.currentTimeMillis()) } ?: 0.0 }
        val r = reading.value
        val heading by rememberUpdatedState(r?.magneticDeg)
        val movedNow by rememberUpdatedState(moved)
        val onMoveNow by rememberUpdatedState(onMove)
        val canDrag = fix != null && tiles.isNotEmpty() && r != null
        val drag = if (!canDrag) Modifier else Modifier
            // Touch-down to lift: the disc keeps the heading of the touch-down (else the map would
            // slide under the finger as the compass turns it) and no tiles are requested. Consumes nothing.
            .pointerInput(fix) {
                try {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        frozenDeg = heading
                        dragging = true
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        frozenDeg = null
                        dragging = false
                    }
                } finally {
                    // The disc follows the compass again also if the gesture ends because the map went away.
                    frozenDeg = null
                    dragging = false
                }
            }
            .pointerInput(fix) {
                // The map offset of this drag (canvas pixels, north-up), kept here so that several
                // move events within one frame add up before the caller's state comes back.
                var off = 0.0 to 0.0
                detectDragGestures(
                    onDragStart = {
                        dragged = true
                        val scale = ReportMapTiles.canvasScale(size.width.toDouble(), fix.first)
                        off = movedNow?.let { ReportMapTiles.latLonToOffset(fix, it, ReportMapTiles.ZOOM, scale) } ?: (0.0 to 0.0)
                    },
                ) { change, amount ->
                    change.consume()
                    val scale = ReportMapTiles.canvasScale(size.width.toDouble(), fix.first)
                    // The rotation the map is drawn with (frozen since touch-down).
                    val rotation = -((frozenDeg ?: heading ?: 0.0) + declination)
                    val d = ReportMapTiles.screenDragToMapOffset(amount.x.toDouble(), amount.y.toDouble(), rotation)
                    val next = ReportMapTiles.clampOffset(off.first + d.first to off.second + d.second, fix.first, scale)
                    if (onMoveNow(ReportMapTiles.offsetToLatLon(fix, next, ReportMapTiles.ZOOM, scale))) off = next
                }
            }
        val shownDeg = frozenDeg ?: r?.magneticDeg
        // The streets are drawn once the fix, a reading and the first tiles are there (fading in).
        val fade by animateFloatAsState(if (tiles.isNotEmpty() && shownDeg != null && fix != null) 1f else 0f, tween(400), label = "tiles")
        val chip by animateFloatAsState(if (canDrag && !dragged) 1f else 0f, tween(250), label = "chip")
        val cs = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(CARD_CORNER_DP.dp)
        // 0.9.2: the hint lines ABOVE the card, left-aligned under the dialog's heading «Wohin fuhr es?»: on a short
        // screen the card fills the visible part, and the line saying what it is for must not be scrolled away.
        PanelLines(r, directionOn) {
            // 0.9.6.1: the drag is on the whole card, so that the overlays (chip, badge, credit) take no touch:
            // a drag that starts on the chip drags the map. Only «×» / «Richtung angeben» are buttons.
            Box(
                Modifier.fillMaxWidth().height(cardHeight).clip(shape).border(1.dp, cs.outlineVariant, shape)
                    .onSizeChanged { cardPx = it }.then(drag),
            ) {
                MapCanvas(
                    shownDeg, declination, centre, fix, moved != null, tiles, fade, car = directionOn,
                    dim = if (directionOn) 1f else OFF_ALPHA,
                )
                if (fade > 0f && shownDeg != null) {
                    NorthBadge(shownDeg + declination, Modifier.align(Alignment.TopStart).padding(6.dp).alpha(fade * (if (directionOn) 1f else OFF_ALPHA)))
                    Text(
                        TILE_ATTRIBUTION, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = cs.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp).alpha(fade)
                            .background(cs.surface.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                    )
                }
                if (chip > 0f) PanChip(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).alpha(chip))
                if (onDirectionOn != null) {
                    if (directionOn) {
                        // 0.9.4.5: «×»: the direction off. 0.9.6.1: inside the card's top-right corner, on a round surface.
                        IconButton(onClick = { onDirectionOn(false) }, modifier = Modifier.align(Alignment.TopEnd).size(40.dp)) {
                            Box(Modifier.size(30.dp).background(cs.surface, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, contentDescription = DIRECTION_OFF, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        // Over the faint card, the way back. «Ohne Richtung» stands where the live bearing stood
                        // (PanelLines). 0.9.6.1: ABOVE the centre (was under it): the place dot stays visible and the
                        // chip at the bottom is not covered, also on the 140 dp card.
                        FilledTonalButton(
                            onClick = { onDirectionOn(true) },
                            modifier = Modifier.align(Alignment.Center).padding(bottom = 64.dp).wrapContentWidth(unbounded = true),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) { Text(DIRECTION_ON, maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }

    /**
     * 0.9.6.1: [PAN_HINT] as a pill at the map card's bottom: the four-arrows «move» icon (AppIcons.OpenWith) and the
     * text, on the surface at 90 %. No click, no pointer input: touches go through to the card's drag.
     */
    @Composable
    private fun PanChip(modifier: Modifier) {
        val cs = MaterialTheme.colorScheme
        Row(
            modifier.background(cs.surface.copy(alpha = 0.9f), CircleShape).padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.OpenWith, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(PAN_HINT, style = MaterialTheme.typography.labelMedium, color = cs.onSurface, maxLines = 1, softWrap = false)
        }
    }

    /**
     * 0.9.6.1: map north over the map card, like the compass button of map apps (not a button here): a round 28 dp
     * badge on the surface, a red tip on its rim and «N» inside, both turned by −[trueHeadingDeg] — the rotation the
     * map is drawn with —, so they point to the map's north. Replaces the ring, ticks and letters over the map.
     */
    @Composable
    private fun NorthBadge(trueHeadingDeg: Double, modifier: Modifier) {
        val cs = MaterialTheme.colorScheme
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelSmall.copy(color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Canvas(modifier.size(28.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val u = 1.dp.toPx()
            drawCircle(cs.surface, size.minDimension / 2, c)
            drawCircle(cs.outlineVariant, size.minDimension / 2 - 0.5f * u, c, style = Stroke(1f * u))
            rotate(-trueHeadingDeg.toFloat(), c) {
                val tip = Path().apply {
                    moveTo(c.x, c.y - 12.5f * u)
                    lineTo(c.x + 4f * u, c.y - 7.5f * u)
                    lineTo(c.x - 4f * u, c.y - 7.5f * u)
                    close()
                }
                drawPath(tip, cs.error)
                val n = measurer.measure("N", style)
                drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y + 1.5f * u - n.size.height / 2f))
            }
        }
    }

    /**
     * 0.9.4.2: the panel's lines around [disc]: the heading «Wohin fuhr es?» ([REPORT_WHERE]; 0.9.2–0.9.4.1 drawn
     * by the dialog above the panel) with the live label «Nordost · 47°» at its right (until 0.9.4.1 a line of
     * its own under the disc: 24 dp the dialog needs to fit one screen), [POINT_HINT], always, and
     * [CALIBRATE_HINT] in addition while the compass is unreliable (0.9.2), then the disc. Shared by the real
     * panel and the dialog's measuring pass (an empty [disc]), so both have the same height but the disc's.
     */
    @Composable
    private fun PanelLines(r: CompassReading?, directionOn: Boolean, disc: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    REPORT_WHERE, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 0.9.4.5: no live bearing while the direction is off (nothing of it is sent).
                Text(
                    if (!directionOn) DIRECTION_NONE else if (r == null) "…" else liveLabel(r.magneticDeg),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(2.dp))
            // What the arrow is for, always; the figure-eight hint only in addition, under it (0.9.2; until
            // 0.9.1 it replaced this line).
            Text(
                if (directionOn) POINT_HINT else POINT_HINT_OFF, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            if (directionOn && r != null && showCalibrationHint(r.sensorStatus)) {
                Text(
                    CALIBRATE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
            disc()
        }
    }

    /**
     * 0.7.8 (Labor·Karte; since 0.8.0 also the Online-Version): swisstopo's tiles around [fix],
     * downloaded while the dialog is on screen and kept only in this map (memory), which is dropped
     * with the dialog. Nothing is requested without a fix, or outside Switzerland (the grey map has no data
     * there). Leaving the dialog closes the open connections at once.
     *
     * 0.8.1 (same build): the tiles touching the 150 m circle around [centre] — the fix, or the
     * place moved under the dot — [TILE_DEBOUNCE_MS] after the drag ended ([centre] is null while
     * the finger is down: nothing is requested on the move events). Tiles already in memory or
     * already requested are not requested again; same host, same caps (4 at a time, 512 kB, only
     * a centre in Switzerland). At most [ReportMapTiles.CACHE_MAX] stay, the farthest dropped.
     *
     * 0.9.6.1 (Labor·Karte first): the tiles touching the circle of [radiusM] (the map card's half-diagonal,
     * [ReportMapTiles.coverRadiusM], at most [ReportMapTiles.MAX_COVER_M]) instead of the 150 m circle, so that the
     * turning rectangle is covered; a new radius (the card grows or shrinks) requests only the tiles not there yet.
     */
    @Composable
    private fun rememberStreetTiles(fix: Pair<Double, Double>?, centre: Pair<Double, Double>?, radiusM: Double): Map<ReportMapTiles.Tile, ImageBitmap> {
        val tiles = remember(fix) { mutableStateMapOf<ReportMapTiles.Tile, ImageBitmap>() }
        val fetcher = remember(fix) { ReportTileFetcher() }
        // Requested and not answered yet (touched on the main thread only).
        val pending = remember(fix) { mutableSetOf<ReportMapTiles.Tile>() }
        val gate = remember(fix) { Semaphore(ReportMapTiles.PARALLEL) }
        // The downloads outlive a new centre (a running one is not thrown away); they end with the dialog.
        val scope = rememberCoroutineScope()
        DisposableEffect(fetcher) { onDispose { fetcher.close() } }
        LaunchedEffect(fetcher, centre, radiusM) {
            val f = fix
            val c = centre
            if (f == null || c == null || !ReportMapTiles.inSwitzerland(c.first, c.second)) return@LaunchedEffect
            if (c != f) delay(TILE_DEBOUNCE_MS)
            val at = ReportMapTiles.tilePoint(c.first, c.second)
            for (t in ReportMapTiles.tilesAround(c.first, c.second, radiusM)) {
                if (t in tiles || t in pending) continue
                pending += t
                scope.launch {
                    val image = gate.withPermit { withContext(Dispatchers.IO) { fetcher.fetch(t) } }
                    pending -= t
                    if (image != null) {
                        tiles[t] = image
                        for (old in ReportMapTiles.evict(tiles.keys, at)) tiles.remove(old)
                    }
                }
            }
        }
        return tiles
    }

    /**
     * The live compass (0.7.6; 0.7.8 with the streets): a schematic compass rose that turns with the
     * phone, so that it is obvious that the reading is live. The arrow stays fixed and points up the
     * screen, i.e. where the phone points (accent colour). Around it the rose is turned by
     * −[magneticDeg]: the ring, 16 ticks (the four cardinal ones longer) and the letters N, O, S, W
     * (German: Ost) on north, east, south and west — N larger and in the accent colour. The letters
     * move with the rose but stay upright so they can be read.
     *
     * Under it (0.7.8; since 0.8.0 in the Online-Version too; once [fix] and the first tiles are there): the real streets,
     * swisstopo's grey map, turned by the true heading (magnetic + [declination]) so that the map
     * lies like the ground in front of the reporter, 300 m across, fading in; dark theme: inverted like the
     * website's map. Before the first reading ([magneticDeg] null) only the ring and the ticks, faint.
     *
     * 0.9.6.1 (Labor·Karte first): the canvas of the map card (the card's Box clips it to the rounded rectangle).
     * Ground: surfaceVariant. With the map ([fade] > 0): the tiles around [centre] (the fix, or the place moved
     * to), at [ReportMapTiles.TILE_ALPHA], and **no rose** — the north badge of the Panel says where north is;
     * once [moved], the reporter's own [fix] as the familiar blue dot with a white ring, wherever it lies on the
     * turned map (outside the card it is clipped away). Without the map: the rose as before, inscribed in the
     * card's height, fading out as the map fades in. At the centre — the reported place — the car ([drawCar]; [car]
     * true: the direction is given), in the accent colour with its chevrons once there is a reading, faint grey
     * without them before; with the direction off a place dot instead (0.9.4.5's dot under the arrow, a bit larger).
     * [dim] (the direction off) makes the map and the rose faint, never the marker.
     */
    @Composable
    private fun MapCanvas(
        magneticDeg: Double?,
        declination: Double,
        centre: Pair<Double, Double>?,
        fix: Pair<Double, Double>?,
        moved: Boolean,
        tiles: Map<ReportMapTiles.Tile, ImageBitmap>,
        fade: Float,
        car: Boolean,
        dim: Float,
    ) {
        val cs = MaterialTheme.colorScheme
        val ink = cs.onSurface
        val accent = cs.primary
        val surface = cs.surface
        val ground = cs.surfaceVariant
        val dark = surface.luminance() < 0.5f
        val measurer = rememberTextMeasurer()
        val letterStyle = MaterialTheme.typography.labelLarge.copy(color = ink, fontWeight = FontWeight.Medium)
        val northStyle = MaterialTheme.typography.titleMedium.copy(color = accent, fontWeight = FontWeight.Bold)
        val point = remember(centre) { centre?.let { ReportMapTiles.tilePoint(it.first, it.second) } }
        val fixPoint = remember(fix) { fix?.let { ReportMapTiles.tilePoint(it.first, it.second) } }
        // swisstopo's grey map is light grey; in the dark theme it is inverted (and dimmed) like
        // the website's map (docs/map/karte.css: invert(1) brightness(0.82)).
        val tileFilter = remember(dark) { if (dark) ColorFilter.colorMatrix(INVERT_DIM) else null }
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val live = magneticDeg != null
            drawRect(ground)
            // The real streets, turned by the true heading.
            if (live && point != null && fix != null && fade > 0f) {
                // The scale of the fix's latitude, as the drag uses: the map stays under the finger.
                val scale = ReportMapTiles.canvasScale(size.width.toDouble(), fix.first)
                rotate(-((magneticDeg ?: 0.0) + declination).toFloat(), c) {
                    for ((t, image) in tiles) {
                        val b = ReportMapTiles.placement(t, point, scale)
                        val left = (c.x + b[0]).roundToInt()
                        val top = (c.y + b[1]).roundToInt()
                        drawImage(
                            image,
                            srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(left, top),
                            dstSize = IntSize((c.x + b[2]).roundToInt() - left, (c.y + b[3]).roundToInt() - top),
                            alpha = ReportMapTiles.TILE_ALPHA * fade * dim, colorFilter = tileFilter, filterQuality = FilterQuality.Medium,
                        )
                    }
                    // Once the map was moved: where the reporter is, the blue dot of map apps (it turns and moves with the map).
                    if (moved && fixPoint != null) {
                        val k = (ReportMapTiles.TILE_PX * scale).toFloat()
                        val at = Offset(c.x + (fixPoint.x - point.x).toFloat() * k, c.y + (fixPoint.y - point.y).toFloat() * k)
                        drawCircle(Color.White, 8.dp.toPx(), at, alpha = fade)
                        drawCircle(OWN_BLUE, 5.5f.dp.toPx(), at, alpha = fade)
                    }
                }
            }
            // Without the map: the rose, inscribed in the card's height (it fades out as the map fades in).
            val rose = (1f - fade) * dim
            if (rose > 0f) {
                val radius = size.minDimension / 2 - 4.dp.toPx()
                val turn = -(magneticDeg ?: 0.0).toFloat()
                val faint = (if (live) 1f else 0.4f) * rose
                rotate(turn, c) {
                    drawCircle(ink.copy(alpha = 0.35f * faint), radius, c, style = Stroke(2.dp.toPx()))
                    // 16 ticks every 22.5°, inward from the ring; the cardinal ones (every 4th) longer.
                    for (i in 0 until 16) {
                        val cardinal = i % 4 == 0
                        val a = Math.toRadians(i * 22.5)
                        val len = if (cardinal) 12.dp.toPx() else 6.dp.toPx()
                        val dx = sin(a).toFloat()
                        val dy = -cos(a).toFloat()
                        drawLine(
                            ink.copy(alpha = (if (cardinal) 0.7f else 0.4f) * faint),
                            Offset(c.x + dx * radius, c.y + dy * radius),
                            Offset(c.x + dx * (radius - len), c.y + dy * (radius - len)),
                            (if (cardinal) 2.dp else 1.dp).toPx(),
                        )
                    }
                }
                // The letters: their places turn with the rose, the letters themselves stay upright.
                if (live) {
                    val letterRadius = radius - 24.dp.toPx()
                    for ((i, letter) in CARDINALS.withIndex()) {
                        val layout = measurer.measure(letter, if (i == 0) northStyle else letterStyle)
                        val a = Math.toRadians(i * 90.0 + turn)
                        val p = Offset(c.x + sin(a).toFloat() * letterRadius, c.y - cos(a).toFloat() * letterRadius)
                        drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f), alpha = rose)
                    }
                }
            }
            // The marker at the centre: the reported place.
            if (car) {
                drawCar(c, body = if (live) accent else ink.copy(alpha = 0.3f), window = surface, halo = surface, chevrons = live)
            } else {
                drawCircle(surface, 7.dp.toPx(), c)
                drawCircle(ink, 4.5f.dp.toPx(), c)
            }
        }
    }

    /**
     * 0.9.6.1: a car seen from above at [c] (its centre), nose up the screen: 22 dp wide and 44 dp long, the body in
     * [body] with side mirrors, a 2 dp [halo] round it (so it stands out on any map), the windscreen (larger, the
     * long bonnet in front of it) and the rear window (smaller, the short boot behind it) in [window] so that front and
     * back are obvious; with [chevrons] two chevrons ahead of the nose: «drives this way». Paths only, no bitmap.
     */
    private fun DrawScope.drawCar(c: Offset, body: Color, window: Color, halo: Color, chevrons: Boolean) {
        val u = 1.dp.toPx()
        // Points in dp from the car's centre (x right, y down the screen).
        fun Path.mv(x: Float, y: Float) = moveTo(c.x + x * u, c.y + y * u)
        fun Path.ln(x: Float, y: Float) = lineTo(c.x + x * u, c.y + y * u)
        val shell = Path().apply {
            addRoundRect(
                RoundRect(
                    left = c.x - 11f * u, top = c.y - 22f * u, right = c.x + 11f * u, bottom = c.y + 22f * u,
                    topLeftCornerRadius = CornerRadius(8f * u, 10f * u), topRightCornerRadius = CornerRadius(8f * u, 10f * u),
                    bottomRightCornerRadius = CornerRadius(6f * u, 6f * u), bottomLeftCornerRadius = CornerRadius(6f * u, 6f * u),
                ),
            )
            // The side mirrors, at the front of the doors.
            addRoundRect(RoundRect(c.x - 14f * u, c.y - 8f * u, c.x - 10f * u, c.y - 5f * u, CornerRadius(1.5f * u)))
            addRoundRect(RoundRect(c.x + 10f * u, c.y - 8f * u, c.x + 14f * u, c.y - 5f * u, CornerRadius(1.5f * u)))
        }
        val arrows = if (!chevrons) null else Path().apply {
            for (apex in listOf(-35f, -28.5f)) {
                mv(-6.5f, apex + 5f)
                ln(0f, apex)
                ln(6.5f, apex + 5f)
            }
        }
        // The halo first, then the shapes.
        drawPath(shell, halo, style = Stroke(4f * u, join = StrokeJoin.Round))
        arrows?.let { drawPath(it, halo, style = Stroke(6.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        drawPath(shell, body)
        arrows?.let { drawPath(it, body, style = Stroke(2.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        // Windscreen: wide at the bonnet, narrower at the roof.
        drawPath(Path().apply {
            mv(-8.5f, -9f); ln(8.5f, -9f)
            ln(7f, -2f); ln(-7f, -2f); close()
        }, window)
        // Rear window: smaller, narrower at the roof.
        drawPath(Path().apply {
            mv(-7f, 11f); ln(7f, 11f)
            ln(8f, 15f); ln(-8f, 15f); close()
        }, window)
        // Side windows: thin strips between the two.
        drawRect(window, topLeft = Offset(c.x - 9.5f * u, c.y), size = Size(1.5f * u, 9f * u))
        drawRect(window, topLeft = Offset(c.x + 8f * u, c.y), size = Size(1.5f * u, 9f * u))
    }

    /** 0.9.6.1: the reporter's own position once the map was moved: the blue of map apps' location dot. */
    private val OWN_BLUE = Color(0xFF1A73E8)

    /** Colour inversion, dimmed to 82 % (RGB' = 0.82 · (255 − RGB)), alpha kept. */
    private val INVERT_DIM = ColorMatrix(
        floatArrayOf(
            -0.82f, 0f, 0f, 0f, 209f,
            0f, -0.82f, 0f, 0f, 209f,
            0f, 0f, -0.82f, 0f, 209f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

    /** The rose's letters, clockwise from north: Nord, Ost, Süd, West. */
    private val CARDINALS = listOf("N", "O", "S", "W")

    private fun displayRotation(context: Context): Int =
        try {
            ContextCompat.getDisplayOrDefault(context).rotation
        } catch (_: Exception) {
            0
        }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
