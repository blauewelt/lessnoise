package ch.lessnoise.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.GeomagneticField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
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
import kotlin.math.roundToInt

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

    /** 0.8.1: the one line under the live label (replaced by [CALIBRATE_HINT] while the compass is unreliable). */
    const val POINT_HINT = "Telefon in die Fahrtrichtung halten"
    const val CALIBRATE_HINT = "Kompass ungenau: Telefon in Form einer Acht bewegen."

    /** 0.8.1: the disc's size (0.7.8: 240 dp in its own dialog); it still shows 300 m across. */
    const val DISC_DP = 200

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

    /** 0.7.8: the credit under the disc while swisstopo's tiles are drawn. */
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
     * The disc (0.8.1, 200 dp; the drawing of 0.7.8), the live label «Nordost · 47°» and one line
     * under it: [POINT_HINT], or [CALIBRATE_HINT] while the compass is unreliable. [reading] is the
     * caller's [rememberReading] (read here, so that only this panel follows the sensor). [fix]
     * (latitude, longitude) is the report's location fix once it is there, null while it is
     * searched: in Labor·Karte ([ReportMapTiles.ENABLED]) the real streets around it are drawn
     * under the arrow; the Online-Version ignores it and shows the rose alone.
     *
     * 0.8.1 (same build), Labor·Karte only: once the streets are shown, a one-finger drag on the
     * disc moves the map under the dot, which stays at the centre (the place to report when the
     * car drove on or the fix is off). [moved] is the place under the dot (null: the fix, not
     * moved); every move calls [onMove] with the new place, which the caller takes (true) or
     * refuses (false, e.g. outside Switzerland: the map stops there); the caller resets by passing
     * null again. The drag is turned back by the disc's
     * rotation ([ReportMapTiles.screenDragToMapOffset]), the disc does not follow the compass while
     * the finger is down, and the map stops [ReportMapTiles.MAX_DRAG_M] from the fix.
     */
    @Composable
    fun Panel(
        reading: State<CompassReading?>,
        fix: Pair<Double, Double>?,
        moved: Pair<Double, Double>?,
        /** 0.8.6, for the JVM renders only (ReportDialogScreenshots): tiles drawn instead of downloading any; null in the app. */
        previewTiles: Map<ReportMapTiles.Tile, ImageBitmap>? = null,
        onMove: (Pair<Double, Double>) -> Boolean,
    ) {
        // While the finger is down: the heading at touch-down (the disc is frozen), and no tile requests.
        var frozenDeg by remember(fix) { mutableStateOf<Double?>(null) }
        var dragging by remember(fix) { mutableStateOf(false) }
        val centre = moved ?: fix
        val tiles = previewTiles ?: rememberStreetTiles(fix, centre.takeUnless { dragging })
        // Map north is true north; the compass reads magnetic north. The tiles are turned by the
        // true heading (declination at the fix, Android's built-in model, no network).
        val declination = remember(fix) { fix?.let { declination(it.first, it.second, System.currentTimeMillis()) } ?: 0.0 }
        val r = reading.value
        val heading by rememberUpdatedState(r?.magneticDeg)
        val movedNow by rememberUpdatedState(moved)
        val onMoveNow by rememberUpdatedState(onMove)
        val canDrag = ReportMapTiles.ENABLED && fix != null && tiles.isNotEmpty() && r != null
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
        // 0.8.6: the disc with the credit tucked into its bottom-right corner (outside the circle), the
        // heading in titleMedium 8 dp under it, the hint in bodySmall right under the heading.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.widthIn(max = DISC_DP.dp).fillMaxWidth().aspectRatio(1f)) {
                DirectionArrow(frozenDeg ?: r?.magneticDeg, declination, centre, fix?.first, tiles, drag)
                if (tiles.isNotEmpty() && r != null) {
                    Text(
                        TILE_ATTRIBUTION, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (r == null) "…" else liveLabel(r.magneticDeg),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            if (r != null && showCalibrationHint(r.sensorStatus)) {
                Text(CALIBRATE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            } else {
                Text(POINT_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }

    /**
     * 0.7.8, Labor·Karte only: swisstopo's tiles around [fix], downloaded while the dialog is on
     * screen and kept only in this map (memory), which is dropped with the dialog. Nothing is
     * requested without the flag, without a fix, or outside Switzerland (the grey map has no data
     * there). Leaving the dialog closes the open connections at once.
     *
     * 0.8.1 (same build): the tiles touching the 150 m circle around [centre] — the fix, or the
     * place moved under the dot — [TILE_DEBOUNCE_MS] after the drag ended ([centre] is null while
     * the finger is down: nothing is requested on the move events). Tiles already in memory or
     * already requested are not requested again; same host, same caps (4 at a time, 512 kB, only
     * a centre in Switzerland). At most [ReportMapTiles.CACHE_MAX] stay, the farthest dropped.
     */
    @Composable
    private fun rememberStreetTiles(fix: Pair<Double, Double>?, centre: Pair<Double, Double>?): Map<ReportMapTiles.Tile, ImageBitmap> {
        val tiles = remember(fix) { mutableStateMapOf<ReportMapTiles.Tile, ImageBitmap>() }
        val fetcher = remember(fix) { ReportTileFetcher() }
        // Requested and not answered yet (touched on the main thread only).
        val pending = remember(fix) { mutableSetOf<ReportMapTiles.Tile>() }
        val gate = remember(fix) { Semaphore(ReportMapTiles.PARALLEL) }
        // The downloads outlive a new centre (a running one is not thrown away); they end with the dialog.
        val scope = rememberCoroutineScope()
        DisposableEffect(fetcher) { onDispose { fetcher.close() } }
        LaunchedEffect(fetcher, centre) {
            val f = fix
            val c = centre
            if (!ReportMapTiles.ENABLED || f == null || c == null || !ReportMapTiles.inSwitzerland(c.first, c.second)) return@LaunchedEffect
            if (c != f) delay(TILE_DEBOUNCE_MS)
            val at = ReportMapTiles.tilePoint(c.first, c.second)
            for (t in ReportMapTiles.tilesAround(c.first, c.second)) {
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
     * Under it (0.7.8, Labor·Karte, once [fix] and the first tiles are there): the real streets,
     * swisstopo's grey map, turned by the true heading (magnetic + [declination]) so that the map
     * lies like the ground in front of the reporter, the fix at the centre (a small dot), 300 m
     * across, faint (60 %) and fading in, clipped to the disc; dark theme: inverted like the
     * website's map. The 0.7.6 decorative street grid is gone in every edition. Before the first
     * reading ([magneticDeg] null) only the ring and the ticks, faint, and a grey arrow.
     *
     * 0.8.1 (same build): [fix] is the place at the centre — the fix, or the place the map was
     * moved to under the dot; [scaleLat] the fix's own latitude, for the 300 m scale (the same one
     * the drag converts with); [drag] is the panel's drag handler (or nothing).
     */
    @Composable
    private fun DirectionArrow(
        magneticDeg: Double?,
        declination: Double,
        fix: Pair<Double, Double>?,
        scaleLat: Double?,
        tiles: Map<ReportMapTiles.Tile, ImageBitmap>,
        drag: Modifier,
    ) {
        val ink = MaterialTheme.colorScheme.onSurface
        val accent = MaterialTheme.colorScheme.primary
        val surface = MaterialTheme.colorScheme.surface
        val dark = surface.luminance() < 0.5f
        val measurer = rememberTextMeasurer()
        val letterStyle = MaterialTheme.typography.labelLarge.copy(color = ink, fontWeight = FontWeight.Medium)
        val northStyle = MaterialTheme.typography.titleMedium.copy(color = accent, fontWeight = FontWeight.Bold)
        val point = remember(fix) { fix?.let { ReportMapTiles.tilePoint(it.first, it.second) } }
        val fade by animateFloatAsState(if (tiles.isNotEmpty() && magneticDeg != null) 1f else 0f, tween(400), label = "tiles")
        // swisstopo's grey map is light grey; in the dark theme it is inverted (and dimmed) like
        // the website's map (docs/map/karte.css: invert(1) brightness(0.82)).
        val tileFilter = remember(dark) { if (dark) ColorFilter.colorMatrix(INVERT_DIM) else null }
        Canvas(Modifier.fillMaxSize().then(drag)) {
            val c = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 4.dp.toPx()
            val live = magneticDeg != null
            val turn = -(magneticDeg ?: 0.0).toFloat()
            val faint = if (live) 1f else 0.4f
            val onMap = fade > 0f
            // The real streets, clipped to the disc, turned by the true heading.
            if (live && point != null && fix != null && onMap) {
                val disc = Path().apply { addOval(Rect(c, radius)) }
                // The scale of the fix's latitude, as the drag uses: the map stays under the finger.
                val scale = ReportMapTiles.canvasScale(size.width.toDouble(), scaleLat ?: fix.first)
                clipPath(disc) {
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
                                alpha = ReportMapTiles.TILE_ALPHA * fade, colorFilter = tileFilter, filterQuality = FilterQuality.Medium,
                            )
                        }
                    }
                }
            }
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
                    // Over the map a soft disc behind each letter keeps it legible.
                    if (onMap) drawCircle(surface.copy(alpha = 0.75f * fade), 11.dp.toPx(), p)
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }
            }
            // The fixed arrow: where the phone points.
            val r = radius * 0.62f
            val arrow = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x - r * 0.36f, c.y - r * 0.25f)
                lineTo(c.x - r * 0.12f, c.y - r * 0.25f)
                lineTo(c.x - r * 0.12f, c.y + r * 0.7f)
                lineTo(c.x + r * 0.12f, c.y + r * 0.7f)
                lineTo(c.x + r * 0.12f, c.y - r * 0.25f)
                lineTo(c.x + r * 0.36f, c.y - r * 0.25f)
                close()
            }
            drawPath(arrow, if (live) accent else ink.copy(alpha = 0.3f))
            // The reporter: a small dot at the centre, where the fix lies on the map.
            if (onMap) {
                drawCircle(surface, 5.dp.toPx(), c)
                drawCircle(ink, 3.dp.toPx(), c)
            }
        }
    }

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
