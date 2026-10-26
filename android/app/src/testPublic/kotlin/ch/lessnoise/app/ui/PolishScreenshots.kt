package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.Measurement
import ch.lessnoise.app.icons.AppIcons
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.upload.CompassReading
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportSnapshot
import ch.lessnoise.app.upload.SentReport
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.chart.ChartData
import ch.lessnoise.chart.HistoryActions
import ch.lessnoise.chart.HistorySection
import ch.lessnoise.chart.LessNoiseTheme
import ch.lessnoise.chart.RangeMode
import ch.lessnoise.chart.Selection
import ch.lessnoise.chart.TimeWindow
import ch.lessnoise.dsp.Iso
import ch.lessnoise.dsp.MeasurementSpan
import ch.lessnoise.dsp.MeasurementSummary
import ch.lessnoise.dsp.MinuteRecord
import ch.lessnoise.dsp.geo.LocationNoise
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.sin

/**
 * 0.8.6 visual polish, rendered on the JVM (Paparazzi) with synthetic values only (no sensors, no
 * microphone, no tiles from the network): the «Laut! Melden» dialog (its content, ReportDialogBody, in
 * a frame drawn like Material's AlertDialog — Paparazzi does not draw Dialog windows), and «Messen»
 * idle/running, «Teilen», «Messungen», «Einstellungen», each light and dark. Written to
 * $stadtlaerm.screenshotDir/0.9.2-polish (default app/build/screenshots/0.9.2-polish), for review only.
 */
internal class PolishPngWriter(private val dir: File) : SnapshotHandler {
    init { dir.mkdirs() }
    override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int): SnapshotHandler.FrameHandler {
        val file = File(dir, "${snapshot.name ?: snapshot.testName.methodName}.png")
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val write = imageIo.getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SnapshotHandler.FrameHandler::class.java)) { _, method, args ->
            if (method.name == "handle") write.invoke(null, args!![0], "png", file)
            null
        } as SnapshotHandler.FrameHandler
    }
    override fun close() {}
}

private fun polishPaparazzi(heightPx: Int) = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = heightPx, xdpi = 420, ydpi = 420, density = Density(420)),
    theme = "android:Theme.Material.Light.NoActionBar",
    snapshotHandler = PolishPngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.2-polish")),
    useDeviceResolution = true,
)

private val zone: ZoneId = ZoneId.of("Europe/Zurich")
private fun ms(d: Int, h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, d, h, m, s).atZone(zone).toInstant().toEpochMilli()

/** A grey street map for the disc: a few streets drawn into each tile around the place (like swisstopo's grey map). */
private fun fakeTiles(lat: Double, lon: Double): Map<ReportMapTiles.Tile, ImageBitmap> =
    ReportMapTiles.tilesAround(lat, lon).associateWith { t ->
        val img = ImageBitmap(256, 256)
        val c = androidx.compose.ui.graphics.Canvas(img)
        c.drawRect(0f, 0f, 256f, 256f, Paint().apply { color = Color(0xFFF2F2F0) })
        val road = Paint().apply { color = Color(0xFFBDBDBD); style = PaintingStyle.Stroke; strokeWidth = 14f; strokeCap = StrokeCap.Square }
        val lane = Paint().apply { color = Color(0xFFD0D0D0); style = PaintingStyle.Stroke; strokeWidth = 6f }
        // Streets continuous across tiles: lines at fixed world positions.
        val ox = (t.x % 2) * 256f
        val oy = (t.y % 2) * 256f
        c.drawLine(Offset(0f, 180f - oy / 4), Offset(256f, 120f - oy / 4), road)
        c.drawLine(Offset(140f - ox / 8, 0f), Offset(110f - ox / 8, 256f), road)
        c.drawLine(Offset(0f, 40f), Offset(256f, 60f), lane)
        img
    }

/**
 * The «Laut! Melden» dialog over the dimmed screen. 0.9.1: the frame is the app's own ReportDialogLayout
 * (until 0.9.0 a copy of AlertDialog's look drawn here); Paparazzi does not draw Dialog windows. The 24 dp
 * vertical margin stands for the dialog window's insets, so a short screen constrains the height.
 */
@Composable
internal fun ReportDialogFrame(body: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        ReportDialogLayout(onCancel = {}, modifier = Modifier.fillMaxWidth(0.94f)) { body() }
    }
}

/** 0.9.1: one «Laut! Melden» dialog with synthetic values (the renders of ReportDialogDeviceScreenshots and ClarityDialogScreenshots). */
@Composable
internal fun ReportDialogSample(
    chosen: ReportCategory?,
    fixState: ReportFixState = ReportFixState.FIX,
    // 0.9.2: with the streets drawn the app appends «· Karte verschiebbar» (reportFixLine(…, draggable = true)).
    fixLine: String = reportFixLine(100, draggable = true),
    suggested: List<ReportCategory> = emptyList(),
    level: Boolean = true,
    waiting: Boolean = false,
    scrolledToEnd: Boolean = false,
    /** 0.9.2: the compass's accuracy status (0–1: unreliable, the figure-eight line under the hint). */
    sensorStatus: Int = 3,
    /** 0.9.2: a photo taken (thumbnail with «×» and «Neues Foto»); 0.9.4.3: with [video] a video («….mp4»). */
    photo: java.io.File? = null,
    /** 0.9.4.3: the Labor·Karte dialog: «Video» instead of «Foto». */
    video: Boolean = false,
    /** 0.9.4.3: the decoded first frame and length of the video [photo] (MediaMetadataRetriever needs a phone). */
    videoPreview: ReportVideoThumb? = null,
) {
    val lat = 47.3769
    val lon = 8.5417
    val meter = ReportLevelMeter()
    if (level) {
        meter.step(68.9, 75.8, 66.0, valid = true)
        meter.step(68.9, 70.0, 66.0, valid = true)
    }
    val reading = mutableStateOf<CompassReading?>(CompassReading(358.0, sensorStatus))
    val fix = if (fixState == ReportFixState.SEARCHING) null else lat to lon
    val tiles = if (fix != null) fakeTiles(lat, lon) else null
    ReportDialogFrame {
        ReportDialogBody(
            erkanntAt = if (suggested.isNotEmpty()) "22:36" else null,
            disc = { size, measureOnly -> ReportDirectionUi.Panel(reading, fix, null, previewTiles = tiles, discSize = size, measureOnly = measureOnly) { false } },
            level = if (level) meter else null,
            fixLine = fixLine, fixState = fixState, waiting = waiting, onMoveBack = {},
            photo = {
                ReportMediaRow(photo, video = video, enabled = !waiting, onTake = {}, onRemove = {}, onOpen = if (video) ({}) else null, preview = videoPreview)
            },
            photoBesideStatus = photo == null,
            photoNote = null,
            suggested = suggested, chosen = chosen, onSelect = {},
            sendEnabled = chosen != null && !waiting, onSend = {},
            // ScrollState clamps the value to the end once the layout knows the height.
            scroll = rememberScrollState(if (scrolledToEnd) Int.MAX_VALUE else 0),
        )
    }
}

/**
 * 0.9.4.2: the «Laut! Melden» dialog in its states on one phone size: [widthDp] × [heightDp] at 480 dpi
 * (3 px per dp) and [fontScale]. Written to $stadtlaerm.screenshotDir/0.9.4.2-dialog as
 * report-<state>-<suffix>.png. The three sizes: the owner's phone (about 412 × 915 dp), a small phone
 * (360 × 740 dp, font 1.0: everything must fit without scrolling) and a very small one with a large font
 * (360 × 640 dp, font 1.3: may scroll, but the category row and «Senden» are on screen).
 */
abstract class ReportDialogDeviceScreenshots(widthDp: Int, heightDp: Int, fontScale: Float, private val suffix: String) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = widthDp * 3, screenHeight = heightDp * 3, xdpi = 480, ydpi = 480, density = Density.XXHIGH, fontScale = fontScale,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PolishPngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.4.2-dialog")),
        useDeviceResolution = true,
    )

    private fun dialog(
        state: String,
        dark: Boolean,
        chosen: ReportCategory?,
        fixState: ReportFixState = ReportFixState.FIX,
        fixLine: String = reportFixLine(100, draggable = true),
        suggested: List<ReportCategory> = emptyList(),
        level: Boolean = true,
        waiting: Boolean = false,
        scrolledToEnd: Boolean = false,
    ) = paparazzi.snapshot("report-$state-$suffix") {
        LessNoiseTheme(dark = dark) {
            ReportDialogSample(chosen, fixState, fixLine, suggested, level, waiting, scrolledToEnd)
        }
    }

    @Test fun nothingChosen() = dialog("nothing", dark = false, chosen = null)
    @Test fun nothingChosenDark() = dialog("nothing-dark", dark = true, chosen = null)
    @Test fun autoChosen() = dialog("auto", dark = false, chosen = ReportCategory.AUTO)
    @Test fun autoChosenDark() = dialog("auto-dark", dark = true, chosen = ReportCategory.AUTO)

    // «Andere» tapped, then «Lastwagen»: the second row stays open, «Andere» shows «Lastwagen ▴».
    @Test fun andereLastwagen() = dialog("andere-lastwagen", dark = false, chosen = ReportCategory.LASTWAGEN)
    @Test fun andereLastwagenDark() = dialog("andere-lastwagen-dark", dark = true, chosen = ReportCategory.LASTWAGEN)

    // From the «Sehr laut» notification (Labor·Karte): one suggestion Lastwagen, preselected, the row open, «erkannt» there.
    @Test fun erkanntLastwagen() = dialog("erkannt-lastwagen", dark = false, chosen = ReportCategory.LASTWAGEN, suggested = listOf(ReportCategory.LASTWAGEN))

    // road_traffic suggests Auto and Lastwagen (LoudAlertRule): nothing preselected, «erkannt» on both, the row open.
    @Test fun erkanntRoad() = dialog("erkannt-auto-lastwagen", dark = false, chosen = null, suggested = listOf(ReportCategory.AUTO, ReportCategory.LASTWAGEN))

    // «Senden» tapped before the fix, no microphone: «Senden» shows the progress and «Standort wird gesucht …».
    @Test fun waiting() = dialog(
        "waiting", dark = false, chosen = ReportCategory.AUTO,
        fixState = ReportFixState.SEARCHING, fixLine = REPORT_LOCATING, level = false, waiting = true,
    )

    @Test fun movedDark() = dialog(
        "moved-dark", dark = true, chosen = ReportCategory.FLUGZEUG, fixState = ReportFixState.MOVED, fixLine = REPORT_MOVED_LINE,
    )

    // Scrolled to the end (only differs where the content scrolls).
    @Test fun movedScrolled() = dialog(
        "moved-scrolled", dark = false, chosen = ReportCategory.FLUGZEUG, fixState = ReportFixState.MOVED, fixLine = REPORT_MOVED_LINE,
        scrolledToEnd = true,
    )
}

/** The owner's phone, approximately (412 × 915 dp), font 1.0. */
class ReportDialog412x915Screenshots : ReportDialogDeviceScreenshots(412, 915, 1.0f, "412x915")

/** A small phone, font 1.0: the whole dialog without scrolling. */
class ReportDialog360x740Screenshots : ReportDialogDeviceScreenshots(360, 740, 1.0f, "360x740")

/** A very small phone with a large font (0.9.1's case): may scroll; the category row and «Senden» on screen. */
class ReportDialog360x640Font130Screenshots : ReportDialogDeviceScreenshots(360, 640, 1.3f, "360x640-font130")

class PolishScreenshots {
    @get:Rule
    val paparazzi = polishPaparazzi(2340)

    private val bar = appScreens(uploadAvailable = true, mapAvailable = true)
    private val on = UploadSnapshot(enabled = true, shareDecision = ShareDecision.YES, deviceId = "d", token = "t")
    private val names = mapOf("road_traffic" to "Strassenverkehr", "loud_vehicle" to "Lautes Fahrzeug", "rail_tram" to "Tram & Bahn")
    private val point = LocationNoise.Point(2683351, 1247988, 50)

    private fun event(id: Long, at: Long, db: Double, category: String) = EventEntity(
        id = id, startEpochMs = at, startIso = "", durationSeconds = 4.0, lafMaxDb = db, selDb = db, backgroundDb = 46.0,
        thresholdDb = 6.5, dominantCategory = category, dominantScore = 0.6f, topLabelsJson = "[]", classifierFrames = 4,
        calibrationId = null, audioSource = "UNPROCESSED", calibrated = false,
    )

    private fun tab(name: String, dark: Boolean, screen: Screen, content: @Composable (Modifier) -> Unit) {
        paparazzi.snapshot(name) {
            LessNoiseTheme(dark = dark) {
                Scaffold(bottomBar = { AppBottomBar(bar, bar.indexOf(screen)) {} }) { padding ->
                    content(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background))
                }
            }
        }
    }

    // The chip as the app shows it the next morning (0.8.6: with the day).
    private val idleStatus = shareStatus(on.copy(lastSuccessAtMs = ms(24, 18, 42)), false, 0, false, LocateState.Idle, true) {
        Fmt.sentAt(it, ms(25, 8, 23), zone)
    }

    private fun messenIdle(name: String, dark: Boolean, labor: Boolean) = tab(name, dark, Screen.MEASURE) { m ->
        MeasureContent(
            live = LiveState(), events = emptyList(), calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
            onStart = {}, onStop = {}, onReport = {}, modifier = m, shareStatus = idleStatus,
            onUnterwegs = if (labor) ({}) else null,
        )
    }

    // 0.8.7: the logo in the middle, the actions at the bottom. The Labor·Karte variant here has the Online-Version's
    // logo (testPublic resources); MessenIdleLaborKarteScreenshots (testLaborKarte) renders it with the red one.
    @Test fun messenIdle() = messenIdle("messen-idle", dark = false, labor = false)
    @Test fun messenIdleDark() = messenIdle("messen-idle-dark", dark = true, labor = false)
    @Test fun messenIdleLaborKarte() = messenIdle("messen-idle-labor-karte", dark = false, labor = true)
    @Test fun messenIdleLaborKarteDark() = messenIdle("messen-idle-labor-karte-dark", dark = true, labor = true)

    // The Offline-Version: no «Laut! Melden», no chip; «Messung starten» filled (the bar without «Teilen» and «Karte»).
    private fun messenIdleOffline(name: String, dark: Boolean) = paparazzi.snapshot(name) {
        val offlineBar = appScreens(uploadAvailable = false, mapAvailable = false)
        LessNoiseTheme(dark = dark) {
            Scaffold(bottomBar = { AppBottomBar(offlineBar, offlineBar.indexOf(Screen.MEASURE)) {} }) { padding ->
                MeasureContent(
                    live = LiveState(), events = emptyList(), calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
                    onStart = {}, onStop = {}, modifier = Modifier.padding(padding).fillMaxSize(),
                )
            }
        }
    }

    @Test fun messenIdleOffline() = messenIdleOffline("messen-idle-offline", dark = false)
    @Test fun messenIdleOfflineDark() = messenIdleOffline("messen-idle-offline-dark", dark = true)

    private fun messenRunning(name: String, dark: Boolean) {
        val runningSent = shareStatus(
            on.copy(lastSuccessAtMs = ms(15, 18, 42), minutesSentUpToMs = ms(15, 18, 41)), true, ms(15, 18, 30), true, LocateState.Idle, true,
        ) { "18:42" }!!
        val live = LiveState(
            running = true, startedAtMs = ms(15, 18, 30), measurementStartMs = ms(15, 18, 30), lafDb = 58.2, backgroundDb = 46.2,
            dominantCategory = "road_traffic", dominantScore = 0.5f, classifierEnabled = true, sharedPoint = point,
        )
        val events = listOf(event(2, ms(15, 18, 41, 5), 74.0, "loud_vehicle"), event(1, ms(15, 18, 36, 40), 66.5, "rail_tram"))
        tab(name, dark, Screen.MEASURE) { m ->
            MeasureContent(
                live = live, events = events, calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
                onStart = {}, onStop = {}, onReport = {}, modifier = m, shareStatus = runningSent,
            )
        }
    }

    @Test fun messenRunning() = messenRunning("messen-running", dark = false)
    @Test fun messenRunningDark() = messenRunning("messen-running-dark", dark = true)

    private fun teilen(name: String, dark: Boolean, sharing: Boolean = true, withReports: Boolean = true) =
        tab(name, dark, Screen.SHARE) { m -> TeilenSample(sharing, withReports, m) }

    // 0.8.8: «Messungen am Fenster» (switch, chip, settings always visible) and «Meldungen unterwegs».
    @Test fun teilen() = teilen("teilen", dark = false)
    @Test fun teilenDark() = teilen("teilen-dark", dark = true)
    // Sharing off: the window settings drawn disabled; no report sent yet.
    @Test fun teilenOff() = teilen("teilen-off", dark = false, sharing = false, withReports = false)
    @Test fun teilenOffDark() = teilen("teilen-off-dark", dark = true, sharing = false, withReports = false)

    // 0.8.8: the idle «Messen» shows the chip only when there is something to do (showOnIdle): interrupted.
    @Test fun messenIdleProblem() = tab("messen-idle-problem", false, Screen.MEASURE) { m ->
        MeasureContent(
            live = LiveState(), events = emptyList(), calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
            onStart = {}, onStop = {}, onReport = {}, modifier = m,
            shareStatus = shareStatus(on.copy(authFailed = true), false, 0, false, LocateState.Idle, true) { "18:42" },
        )
    }

    private fun minutes(from: Long, to: Long): List<MinuteRecord> = (from until to step 60_000L).mapIndexed { i, t ->
        val bg = 46.0 + 4 * sin(i / 40.0) + (i % 7) * 0.3
        MinuteRecord(
            startEpochMs = t, startIso = Iso.format(t, zone), durationSeconds = 60.0,
            laeqDb = bg + 6 + (i % 5), lafMaxDb = bg + 16 + (i % 11), lafMinDb = bg - 2, l1Db = bg + 14, l10Db = bg + 5, l50Db = bg + 2,
            l90Db = bg, eventCount = if (i % 9 == 0) 1 else 0, dominantCategory = "road_traffic", categoryShares = mapOf("road_traffic" to 1.0),
            classifierFrames = 60, calibrationId = null, calibrationOffsetDb = 112.35, audioSource = "UNPROCESSED",
            calibrated = false, validSeconds = 60.0,
        )
    }

    private fun item(id: Long, start: Long, end: Long, shared: Boolean, laeq: Double, hum: Double, perHour: Double) = MeasurementItem(
        Measurement(id, MeasurementSpan(start, end), if (shared) point else null, running = false),
        MeasurementSummary(
            span = MeasurementSpan(start, end), measuredSeconds = (end - start) / 1000.0, laeqDb = laeq, eventCount = 12, strongEventCount = 3,
            categoryShares = emptyMap(), eventsByCategory = emptyMap(), loudestEvent = null, allCalibrated = false,
            eventsPerHour = perHour, humDb = hum,
        ),
    )

    private fun messungen(name: String, dark: Boolean, empty: Boolean = false) {
        val a = item(3, ms(14, 21, 30), ms(15, 6, 45), shared = true, laeq = 58.4, hum = 44.1, perHour = 6.3)
        val b = item(2, ms(12, 22, 5), ms(13, 7, 0), shared = false, laeq = 55.0, hum = 41.8, perHour = 3.9)
        val c = item(1, ms(10, 20, 15), ms(11, 1, 30), shared = true, laeq = 61.2, hum = 47.5, perHour = 11.0)
        val w = TimeWindow.span(a.measurement.span.startMs, a.measurement.span.endMs)
        val data = ChartData(w, minutes(w.startMs, w.endMs), emptyList(), w.endMs + 60_000)
        val actions = object : HistoryActions {
            override fun onMode(mode: RangeMode) {}
            override fun onShift(delta: Int) {}
            override fun onHighlight(category: String) {}
            override fun onSelect(selection: Selection?) {}
        }
        tab(name, dark, Screen.MEASUREMENTS) { m ->
            MeasurementsContent(
                items = if (empty) emptyList() else listOf(a, b, c), selectedId = 3, modifier = m, zone = zone,
                chart = if (empty) null else ({
                    HistorySection(
                        window = w, data = data, zone = zone, highlight = "loud_vehicle", eventFloorDb = 45.0,
                        canGoNext = false, selection = null, actions = actions,
                    )
                }),
                onSelect = {},
            )
        }
    }

    @Test fun messungen() = messungen("messungen", dark = false)
    @Test fun messungenDark() = messungen("messungen-dark", dark = true)
    @Test fun messungenEmpty() = messungen("messungen-empty", dark = false, empty = true)

    private fun einstellungen(name: String, dark: Boolean) = tab(name, dark, Screen.SETTINGS) { m ->
        SettingsContent(
            minutes = 1290, events = 214, running = false, canClear = true, info = null,
            onBattery = {}, onExport = {}, onClear = {}, onAdvanced = {}, modifier = m,
        )
    }

    @Test fun einstellungen() = einstellungen("einstellungen", dark = false)
    @Test fun einstellungenDark() = einstellungen("einstellungen-dark", dark = true)
}

/**
 * 0.8.7: the idle «Messen» on small screens with a large font: a 360 × 640 dp phone and a Pixel 5
 * (393 × 851 dp), font scale 1.3, the tallest variant (Labor·Karte's three buttons). The column must
 * scroll rather than clip; with room the actions stay at the bottom.
 */
class MessenIdleSmallScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = 1080, screenHeight = 1920, xdpi = 480, ydpi = 480, density = Density.XXHIGH, fontScale = 1.3f,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PolishPngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.2-idle")),
        useDeviceResolution = true,
    )

    private val bar = appScreens(uploadAvailable = true, mapAvailable = true)
    private val status = shareStatus(
        UploadSnapshot(enabled = true, shareDecision = ShareDecision.YES, deviceId = "d", token = "t", lastSuccessAtMs = ms(24, 18, 42)),
        false, 0, false, LocateState.Idle, true,
    ) { Fmt.sentAt(it, ms(25, 8, 23), zone) }

    internal fun render(name: String, labor: Boolean) = paparazzi.snapshot(name) {
        LessNoiseTheme(dark = false) {
            Scaffold(bottomBar = { AppBottomBar(bar, bar.indexOf(Screen.MEASURE)) {} }) { padding ->
                MeasureContent(
                    live = LiveState(), events = emptyList(), calibrating = false, categoryName = { "Sonstiges" },
                    onStart = {}, onStop = {}, onReport = {}, modifier = Modifier.padding(padding).fillMaxSize(),
                    shareStatus = status, onUnterwegs = if (labor) ({}) else null,
                )
            }
        }
    }

    @Test fun small360x640LaborKarte() = render("messen-idle-360x640-font130-labor-karte", labor = true)
    @Test fun small360x640() = render("messen-idle-360x640-font130", labor = false)
}

class MessenIdlePixel5Screenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 1.3f),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PolishPngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.2-idle")),
        useDeviceResolution = true,
    )

    private val bar = appScreens(uploadAvailable = true, mapAvailable = true)

    @Test fun pixel5LaborKarte() = paparazzi.snapshot("messen-idle-pixel5-font130-labor-karte") {
        LessNoiseTheme(dark = false) {
            Scaffold(bottomBar = { AppBottomBar(bar, bar.indexOf(Screen.MEASURE)) {} }) { padding ->
                MeasureContent(
                    live = LiveState(), events = emptyList(), calibrating = false, categoryName = { "Sonstiges" },
                    onStart = {}, onStop = {}, onReport = {}, modifier = Modifier.padding(padding).fillMaxSize(),
                    shareStatus = null, onUnterwegs = {},
                )
            }
        }
    }
}

/** 0.8.8: «Teilen» with synthetic values: sharing on (sent 18:42) or off (answered «no»); three sent reports or none. */
@Composable
internal fun TeilenSample(sharing: Boolean, withReports: Boolean, modifier: Modifier) {
    val base = if (sharing) {
        UploadSnapshot(enabled = true, shareDecision = ShareDecision.YES, deviceId = "d", token = "t", lastSuccessAtMs = ms(15, 18, 42), minutesSentUpToMs = ms(15, 18, 41))
    } else {
        UploadSnapshot(enabled = false, shareDecision = ShareDecision.NO)
    }
    val reports = if (!withReports) ReportSnapshot() else ReportSnapshot(
        sent = listOf(
            SentReport(ms(14, 22, 10), "poser", "c1", lafMaxDb = 84.6),
            SentReport(ms(15, 7, 55), "lastwagen", "c1", lafMaxDb = 78.2),
            SentReport(ms(15, 16, 5), "motorrad", "c1", lafMaxDb = 81.4),
        ),
    )
    ShareContent(
        s = base, status = shareStatus(base, false, 0, false, LocateState.Idle, true) { "18:42" },
        pending = 0, reports = reports, busy = null, result = null,
        onSwitch = {}, onStatus = {}, onWifiOnly = {}, onSigma = {}, onSaveSite = {}, onDelete = {}, onNewId = {},
        modifier = modifier,
    )
}

/** 0.8.8: the whole «Teilen» page in one tall render (a phone shows it by scrolling), light and dark, on and off. */
class TeilenTallScreenshots {
    @get:Rule
    val paparazzi = polishPaparazzi(4200)

    private fun render(name: String, dark: Boolean, sharing: Boolean, withReports: Boolean) = paparazzi.snapshot(name) {
        LessNoiseTheme(dark = dark) {
            TeilenSample(sharing, withReports, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
    }

    @Test fun onWithReports() = render("teilen-full-on", dark = false, sharing = true, withReports = true)
    @Test fun onWithReportsDark() = render("teilen-full-on-dark", dark = true, sharing = true, withReports = true)
    @Test fun offWithReports() = render("teilen-full-off-reports", dark = false, sharing = false, withReports = true)
    @Test fun offNoReportsDark() = render("teilen-full-off-dark", dark = true, sharing = false, withReports = false)
}
