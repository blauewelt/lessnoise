package ch.lessnoise.app.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.audio.MicChoice
import ch.lessnoise.app.data.Measurement
import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportSnapshot
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.chart.ChartData
import ch.lessnoise.chart.ChartModel
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
 * 0.9.2 clarity pass: the states the earlier renders did not cover, rendered on the JVM (Paparazzi) with
 * synthetic values — the «Laut! Melden» dialog with an unreliable compass and with a photo, the report
 * consent, «Kein genauer Standort», the location options, «Erweitert» (Lärmspitzen, Mikrofon, the
 * Kalibrieren heading), the whole «Einstellungen» (also while measuring), the first start of «Messen», its
 * error states, «Messungen» with a point selected, «Teilen» with minutes waiting and no location, the photo
 * full-screen, the update line and the home-screen widget. Paparazzi draws no Dialog windows: the
 * AlertDialogs are drawn here in a test-only frame of Material's AlertDialog look ([AlertFrame]) with the
 * app's own texts. Written to $stadtlaerm.screenshotDir/0.9.2-clarity, for review only.
 */
private class ClarityPngWriter(private val dir: File) : SnapshotHandler {
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

private fun clarityPaparazzi(heightPx: Int) = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = heightPx, xdpi = 420, ydpi = 420, density = Density(420)),
    theme = "android:Theme.Material.Light.NoActionBar",
    snapshotHandler = ClarityPngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.2-clarity")),
    useDeviceResolution = true,
)

private val clarityZone: ZoneId = ZoneId.of("Europe/Zurich")
private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(clarityZone).toInstant().toEpochMilli()

/** Test-only: Material's AlertDialog look (shape, colours, 24 dp inside, buttons at the end) over the dimmed screen. */
@Composable
private fun AlertFrame(title: String, buttons: List<String>, text: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation, modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = AlertDialogDefaults.titleContentColor)
                Spacer(Modifier.height(16.dp))
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    buttons.forEach { TextButton(onClick = {}) { Text(it) } }
                }
            }
        }
    }
}

/** The «Laut! Melden» dialog in states the 0.9.1 renders lacked (tall screen: the whole dialog without scrolling). */
class ClarityDialogScreenshots {
    @get:Rule
    val paparazzi = clarityPaparazzi(3000)

    // The owner's finding: with an unreliable compass the figure-eight line came INSTEAD of «Telefon in die Fahrtrichtung halten».
    @Test fun compassInaccurate() = paparazzi.snapshot("report-dialog-compass-inaccurate") {
        LessNoiseTheme(dark = false) { ReportDialogSample(chosen = null, sensorStatus = 0) }
    }

    @Test fun compassInaccurateDark() = paparazzi.snapshot("report-dialog-compass-inaccurate-dark") {
        LessNoiseTheme(dark = true) { ReportDialogSample(chosen = ReportCategory.AUTO, sensorStatus = 1) }
    }

    // A photo taken: thumbnail with «×» (40 dp target since 0.9.2) and «Neues Foto». The thumbnail is decoded
    // asynchronously, so the first frame shows its empty 56 dp box; the file only has to exist.
    @Test fun withPhoto() = paparazzi.snapshot("report-dialog-photo") {
        val f = File.createTempFile("report-draft", ".jpg").apply { deleteOnExit() }
        LessNoiseTheme(dark = false) { ReportDialogSample(chosen = ReportCategory.LASTWAGEN, photo = f, scrolledToEnd = true) }
    }
}

class ClarityScreenshots {
    @get:Rule
    val paparazzi = clarityPaparazzi(2340)

    private val bar = appScreens(uploadAvailable = true, mapAvailable = true)
    private val names = mapOf("road_traffic" to "Strassenverkehr", "loud_vehicle" to "Töff & Poser", "rail_tram" to "Tram & Bahn")
    private val on = UploadSnapshot(enabled = true, shareDecision = ShareDecision.YES, deviceId = "d", token = "t")

    private fun screen(name: String, tab: Screen?, dark: Boolean = false, content: @Composable (Modifier) -> Unit) = paparazzi.snapshot(name) {
        LessNoiseTheme(dark = dark) {
            if (tab == null) {
                content(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            } else {
                Scaffold(bottomBar = { AppBottomBar(bar, bar.indexOf(tab)) {} }) { padding ->
                    content(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background))
                }
            }
        }
    }

    // ---- dialogs (test-only AlertDialog frame, the app's texts) ----

    @Test fun reportConsent() = screen("report-consent", null) {
        AlertFrame("Meldungen senden?", listOf("Abbrechen", "Ja, einschalten")) { Text(REPORT_CONSENT_TEXT) }
    }

    @Test fun reportNoFix() = screen("report-no-fix", null) {
        AlertFrame("Kein genauer Standort", listOf("Abbrechen", "Nochmals")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(LocationPolicy.message(LocationPolicy.Outcome.ApproximateOnly))
                LocationSettingsButton(LocalContext.current, LocationPolicy.Outcome.ApproximateOnly)
            }
        }
    }

    @Test fun locationOptions() = screen("location-options", null) {
        AlertFrame(STATUS_NO_POINT, listOf("Schliessen")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(LocationPolicy.message(LocationPolicy.Outcome.NoFix))
                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Nochmals") }
                OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Koordinaten einfügen") }
            }
        }
    }

    // ---- Messen ----

    // First start of the Online-Version: sharing never answered, no chip, nothing measured.
    @Test fun messenFirstStart() = screen("messen-first-start", Screen.MEASURE) { m ->
        MeasureContent(
            live = LiveState(), events = emptyList(), calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
            onStart = {}, onStop = {}, onReport = {}, modifier = m, shareStatus = null,
        )
    }

    // Error after a «no» to the microphone, and the update reminder line.
    @Test fun messenIdleErrorAndUpdate() = screen("messen-idle-error-update", Screen.MEASURE) { m ->
        MeasureContent(
            live = LiveState(error = MIC_DENIED), events = emptyList(), calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
            onStart = {}, onStop = {}, onReport = {}, modifier = m, updateLine = { UpdateReminderContent(onOpen = {}, onLater = {}) },
        )
    }

    // Running with the microphone silenced (a call), no Lärmspitze yet, «Kein Standort für die Karte».
    @Test fun messenRunningSilencedNoPoint() = screen("messen-running-silenced-no-point", Screen.MEASURE) { m ->
        val live = LiveState(
            running = true, startedAtMs = at(15, 18, 30), measurementStartMs = at(15, 18, 30), lafDb = 41.0, backgroundDb = 39.5,
            dominantCategory = "road_traffic", dominantScore = 0.5f, classifierEnabled = true, micSilenced = true,
        )
        val status = shareStatus(on, true, at(15, 18, 30), false, LocateState.Failed(LocationPolicy.Outcome.NoFix), true) { "18:42" }
        MeasureContent(
            live = live, events = emptyList(), calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
            onStart = {}, onStop = {}, onReport = {}, modifier = m, shareStatus = status,
        )
    }

    // ---- Messungen: a point selected (the tooltip) ----

    private fun minutes(from: Long, to: Long): List<MinuteRecord> = (from until to step 60_000L).mapIndexed { i, t ->
        val bg = 46.0 + 4 * sin(i / 40.0) + (i % 7) * 0.3
        MinuteRecord(
            startEpochMs = t, startIso = Iso.format(t, clarityZone), durationSeconds = 60.0,
            laeqDb = bg + 6 + (i % 5), lafMaxDb = bg + 16 + (i % 11), lafMinDb = bg - 2, l1Db = bg + 14, l10Db = bg + 5, l50Db = bg + 2,
            l90Db = bg, eventCount = 0, dominantCategory = "road_traffic", categoryShares = mapOf("road_traffic" to 1.0),
            classifierFrames = 60, calibrationId = null, calibrationOffsetDb = 112.35, audioSource = "UNPROCESSED",
            calibrated = false, validSeconds = 60.0,
        )
    }

    @Test fun messungenSelected() = screen("messungen-selected", Screen.MEASUREMENTS) { m ->
        val start = at(14, 21, 30)
        val end = at(15, 6, 45)
        val w = TimeWindow.span(start, end)
        val data = ChartData(w, minutes(start, end), emptyList(), end + 60_000)
        val model = ChartModel.build(data, clarityZone, "loud_vehicle", 45.0)
        val item = MeasurementItem(
            Measurement(3, MeasurementSpan(start, end), LocationNoise.Point(2683351, 1247988, 50), running = false),
            MeasurementSummary(
                span = MeasurementSpan(start, end), measuredSeconds = (end - start) / 1000.0, laeqDb = 58.4, eventCount = 0, strongEventCount = 0,
                categoryShares = emptyMap(), eventsByCategory = emptyMap(), loudestEvent = null, allCalibrated = false,
                eventsPerHour = 0.0, humDb = 44.1,
            ),
        )
        val actions = object : HistoryActions {
            override fun onMode(mode: RangeMode) {}
            override fun onShift(delta: Int) {}
            override fun onHighlight(category: String) {}
            override fun onSelect(selection: Selection?) {}
        }
        MeasurementsContent(
            items = listOf(item), selectedId = 3, modifier = m, zone = clarityZone,
            chart = {
                HistorySection(
                    window = w, data = data, zone = clarityZone, highlight = "loud_vehicle", eventFloorDb = 45.0,
                    canGoNext = false, canGoPrevious = false, selection = Selection.Point(model.points[model.points.size / 3]), actions = actions,
                )
            },
            onSelect = {},
        )
    }

    // ---- Teilen: minutes waiting, no location for the running measurement ----

    @Test fun teilenPendingNoPoint() = screen("teilen-pending-no-point", Screen.SHARE) { m ->
        val status = shareStatus(on, true, at(15, 18, 30), false, LocateState.Failed(LocationPolicy.Outcome.NoFix), true) { "18:42" }
        ShareContent(
            s = on, status = status, pending = 12, reports = ReportSnapshot(), busy = null, result = null,
            onSwitch = {}, onStatus = {}, onWifiOnly = {}, onSigma = {}, onSaveSite = {}, onDelete = {}, onNewId = {}, modifier = m,
        )
    }

    // ---- Einstellungen ----

    @Test fun einstellungenRunning() = screen("einstellungen-running", Screen.SETTINGS) { m ->
        SettingsContent(
            minutes = 1290, events = 214, running = true, canClear = false, info = null,
            onBattery = {}, onExport = {}, onClear = {}, onAdvanced = {}, modifier = m,
        )
    }

    // «Erweitert» without its view model: «Lärmspitzen», «Mikrofon» (built-in chosen, a USB one not connected), «Kalibrieren».
    @Test fun erweitert() = screen("erweitert", Screen.SETTINGS) { m ->
        val rows = listOf(
            MicChoice.Row(MicChoice.Option(MicChoice.BUILTIN, MicChoice.BUILTIN_LABEL, usable = true, standard = true), "Standard", selected = true, selectable = true),
            MicChoice.Row(MicChoice.Option("usb:1", "USB-Mikrofon (Rode NT-USB)", usable = true, standard = false), "nicht verbunden", selected = false, selectable = false),
        )
        Column(m.verticalScroll(rememberScrollState())) {
            ScreenTitle("Erweitert")
            EventSettingsCard(excessDb = 6.5, minLevelDb = 30.0, onExcess = {}, onMinLevel = {})
            MicrophoneCardContent(rows, running = false) {}
            CalibrateIntro()
        }
    }

    // ---- the photo full-screen («Teilen» › «Meldungen», tap on a thumbnail) ----

    @Test fun photoViewer() = screen("photo-viewer", null) {
        val img = ImageBitmap(400, 300)
        androidx.compose.ui.graphics.Canvas(img).drawRect(0f, 0f, 400f, 300f, Paint().apply { color = Color(0xFF8A9199) })
        ReportPhotoViewerContent(img, onDelete = {}, onDismiss = {})
    }
}

/** The home-screen widget «Laut! Melden» (RemoteViews layout, src/karte/res/layout/report_widget.xml) at 4 × 1 cells. */
class WidgetScreenshots {
    @get:Rule
    val paparazzi = clarityPaparazzi(600)

    @Test fun widget() {
        val ctx = paparazzi.context
        val d = ctx.resources.displayMetrics.density
        val root = FrameLayout(ctx).apply { setBackgroundColor(0xFF5E7186.toInt()) }
        val w = paparazzi.inflate<View>(ch.lessnoise.app.R.layout.report_widget)
        root.addView(w, FrameLayout.LayoutParams((280 * d).toInt(), (72 * d).toInt(), Gravity.CENTER))
        paparazzi.snapshot(root, "report-widget")
    }
}
