package ch.lessnoise.chart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import com.android.resources.Density
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import java.io.File
import java.time.LocalDate

/**
 * JVM renders of the history screen (Paparazzi, no device): `./gradlew :chart:testDebugUnitTest`.
 * PNGs go to the directory in STADTLAERM_SCREENSHOT_DIR (default chart/build/screenshots). They are
 * for looking at, not golden images: nothing is compared.
 *
 * Renders with the owner's real data run only when STADTLAERM_REAL_DATA is set.
 */
class ChartScreenshots {
    /**
     * Writes each snapshot as <name>.png. java.awt is not on the Android unit-test compile
     * classpath (android.jar hides it), so the frame handler and ImageIO are reached reflectively.
     */
    private class PngWriter(private val dir: File) : SnapshotHandler {
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

    @get:Rule
    val paparazzi = Paparazzi(
        // Pixel 7-like: 412 × 915 dp at 2.625 (420 dpi).
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1082, screenHeight = 2402, xdpi = 420, ydpi = 420, density = Density(420)),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots")),
        useDeviceResolution = true,
    )

    private val zone = SyntheticData.zone

    private object NoActions : HistoryActions {
        override fun onMode(mode: RangeMode) {}
        override fun onShift(delta: Int) {}
        override fun onHighlight(category: String) {}
        override fun onSelect(selection: Selection?) {}
    }

    @Composable
    private fun Screen(
        data: ChartData, dark: Boolean, highlight: String = "loud_vehicle", selection: Selection? = null, floor: Double = 30.0,
        onPlayClip: ((String) -> Unit)? = null,
    ) {
        LessNoiseTheme(dark = dark) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Messungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                )
                HistorySection(
                    window = data.window, data = data, zone = zone, highlight = highlight, eventFloorDb = floor,
                    canGoNext = false, selection = selection, actions = NoActions,
                    onPlayClip = onPlayClip,
                )
            }
        }
    }

    private fun snap(name: String, content: @Composable () -> Unit) = paparazzi.snapshot(name) { content() }

    @Test fun messungSynthetic() {
        val d = SyntheticData.fridayNight()
        snap("messung_synth_light") { Screen(d, dark = false) }
    }

    @Test fun messungSyntheticDark() {
        val d = SyntheticData.fridayNight()
        snap("messung_synth_dark") { Screen(d, dark = true) }
    }

    @Test fun messung24hSynthetic() {
        snap("messung_24h_synth_light") { Screen(SyntheticData.dayData(), dark = false) }
    }

    /** The website's picture (docs/img/messung-tag*.png, cropped to 1082 × 1850). */
    @Test fun messung24hSyntheticDark() {
        snap("messung_24h_synth_dark") { Screen(SyntheticData.dayData(), dark = true) }
    }

    @Test fun messung7TageSynthetic() {
        snap("messung_7tage_synth_light") { Screen(SyntheticData.weekData(), dark = false) }
    }

    @Test fun tagesverlaufSynthetic() {
        val d = SyntheticData.weekData()
        val p = d.copy(window = TimeWindow.profile(d.window.startMs, d.window.endMs))
        snap("tagesverlauf_synth_light") { Screen(p, dark = false) }
        snap("tagesverlauf_synth_dark") { Screen(p, dark = true) }
    }

    @Test fun empty() {
        snap("empty_light") { Screen(SyntheticData.emptyNight(), dark = false) }
    }

    @Test fun eventTooltip() {
        val d = SyntheticData.fridayNight()
        val loudest = d.events.maxBy { it.lafMaxDb }
        snap("tooltip_event_light") { Screen(d, dark = false, selection = Selection.Event(loudest, highlighted = true)) }
    }

    /** Labor build: every third event has a clip (ring accent); the tooltip gets «Abspielen». */
    @Test fun eventTooltipWithClip() {
        val base = SyntheticData.fridayNight()
        val sorted = base.events.sortedBy { it.startEpochMs }
        val loudest = base.events.maxBy { it.lafMaxDb }
        val refs = sorted.filterIndexed { i, _ -> i % 3 == 0 }.associate { it.startEpochMs to "clips/ev_${it.startEpochMs}.wav" } +
            (loudest.startEpochMs to "clips/ev_loudest.wav")
        val d = base.copy(clipRefs = refs)
        snap("tooltip_event_clip_light") {
            Screen(d, dark = false, selection = Selection.Event(loudest, highlighted = true, clipRef = "clips/ev_loudest.wav"), onPlayClip = {})
        }
        snap("tooltip_event_clip_dark") {
            Screen(d, dark = true, selection = Selection.Event(loudest, highlighted = true, clipRef = "clips/ev_loudest.wav"), onPlayClip = {})
        }
    }

    @Test fun minuteTooltipNearRightEdge() {
        val d = SyntheticData.fridayNight()
        val m = ChartModel.minutePoint(d.minutes.last { it.startEpochMs < d.window.endMs - 10 * 60_000 })
        snap("tooltip_minute_dark") { Screen(d, dark = true, selection = Selection.Point(m), highlight = "voices") }
    }

    /** The synthetic night re-evaluated with a later calibration (+9.6 dB): «nachträglich kalibriert». */
    private fun recalibratedNight(): ChartData {
        val d = SyntheticData.fridayNight()
        val t = ch.lessnoise.dsp.calibration.Recalibration.Target(4L, ch.lessnoise.dsp.Acoustics.DEFAULT_CALIBRATION_OFFSET_DB + 9.6, "UNPROCESSED")
        return d.copy(
            minutes = d.minutes.map { ch.lessnoise.dsp.calibration.Recalibration.recalibrate(it, t) },
            events = d.events.map { ch.lessnoise.dsp.calibration.Recalibration.recalibrate(it, ch.lessnoise.dsp.Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, t) },
        )
    }

    @Test fun messungRecalibrated() {
        snap("messung_recal_light") { Screen(recalibratedNight(), dark = false, floor = 39.5) }
    }

    @Test fun minuteTooltipRecalibrated() {
        val d = recalibratedNight()
        val m = ChartModel.minutePoint(d.minutes[d.minutes.size / 3])
        snap("tooltip_minute_recal_dark") { Screen(d, dark = true, selection = Selection.Point(m), floor = 39.5) }
    }

    // ---- The owner's first real measurement (skipped unless STADTLAERM_REAL_DATA is set) -------

    private fun real(mode: RangeMode, from: java.time.LocalDateTime, to: java.time.LocalDateTime): ChartData {
        val dir = CsvFixtures.realDataDir()
        assumeTrue("STADTLAERM_REAL_DATA not set", dir != null)
        val w = TimeWindow(mode, SyntheticData.ms(from), SyntheticData.ms(to))
        return CsvFixtures.realData(dir!!, w, nowMs = SyntheticData.ms(LocalDate.of(2026, 10, 7).atTime(9, 0)))
    }

    private val realFrom = LocalDate.of(2026, 10, 6).atTime(22, 0)
    private val realTo = LocalDate.of(2026, 10, 7).atTime(6, 0)

    @Test fun realMeasurement() {
        val d = real(RangeMode.SPAN, realFrom, realTo)
        snap("real_messung_light") { Screen(d, dark = false) }
        snap("real_messung_dark") { Screen(d, dark = true) }
    }

    @Test fun realProfile() {
        val d = real(RangeMode.PROFILE, realFrom, realTo)
        snap("real_tagesverlauf_light") { Screen(d, dark = false, highlight = "voices") }
    }
}
