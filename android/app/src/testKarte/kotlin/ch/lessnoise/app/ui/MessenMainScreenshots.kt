package ch.lessnoise.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.chart.LessNoiseTheme
import ch.lessnoise.dsp.calibration.CalibrationMath
import ch.lessnoise.dsp.geo.LocationNoise
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 0.9.4.4: «Messen» with «Laut! Melden» as the main surface, idle and while measuring, in both editions with the
 * map (src/testKarte runs for public and laborKarte, each with its own resources: the Labor·Karte render has the
 * red logo). 0.9.4.6: the three ways idle («Laut! Melden», «Langzeitmessung starten», «Unterwegs messen» — the same
 * in both editions, no Labor banner any more), and three running states: a window run on the map, an «Unterwegs
 * messen» run, and (Labor·Karte) a window run while audio is stored («Audio wird gespeichert», drawn with
 * [AudioNoteLine], the composable EditionUi.RecordingNote calls — that one needs the app container). [widthDp] × [heightDp]
 * at 480 dpi (3 px per dp) and [fontScale]; no system bars (a phone has about 24 dp status bar and 24–48 dp
 * navigation more). Synthetic values only. Written to $stadtlaerm.screenshotDir/0.9.4.6-messen as
 * messen-<edition>-<state>-<suffix>.png, for review only. 0.9.6: every state with the calibration banner — idle and
 * running, calibrated (green, −2,3 dB on 01.11.2026) and not (red), light and dark; the «unterwegs» run calibrated, the
 * Labor·Karte recording run not — written to 0.9.6-messen.
 */
abstract class MessenMainDeviceScreenshots(widthDp: Int, heightDp: Int, fontScale: Float, private val suffix: String) {
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
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = widthDp * 3, screenHeight = heightDp * 3, xdpi = 480, ydpi = 480, density = Density.XXHIGH, fontScale = fontScale,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.6-messen")),
        useDeviceResolution = true,
    )

    private val labor = BuildConfig.FLAVOR == "laborKarte"
    private val edition = if (labor) "labor-karte" else "online"
    private val zone = ZoneId.of("Europe/Zurich")
    private fun ms(d: Int, h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, d, h, m, s).atZone(zone).toInstant().toEpochMilli()
    private val bar = appScreens(uploadAvailable = true, mapAvailable = true)
    private val on = UploadSnapshot(enabled = true, shareDecision = ShareDecision.YES, deviceId = "d", token = "t")
    private val names = mapOf("road_traffic" to "Strassenverkehr", "loud_vehicle" to "Lautes Fahrzeug", "rail_tram" to "Tram & Bahn")
    private val point = LocationNoise.Point(2683351, 1247988, 50)

    private fun event(id: Long, at: Long, db: Double, category: String) = EventEntity(
        id = id, startEpochMs = at, startIso = "", durationSeconds = 4.0, lafMaxDb = db, selDb = db, backgroundDb = 46.0,
        thresholdDb = 6.5, dominantCategory = category, dominantScore = 0.6f, topLabelsJson = "[]", classifierFrames = 4,
        calibrationId = null, audioSource = "UNPROCESSED", calibrated = false,
    )

    private val idleStatus = shareStatus(on.copy(lastSuccessAtMs = ms(24, 18, 42)), false, 0, false, LocateState.Idle, true) {
        Fmt.sentAt(it, ms(25, 8, 23), zone)
    }
    private val runningStatus = shareStatus(
        on.copy(lastSuccessAtMs = ms(25, 18, 42), minutesSentUpToMs = ms(25, 18, 41)), true, ms(25, 18, 30), true, LocateState.Idle, true,
    ) { "18:42" }!!
    private val runningLive = LiveState(
        running = true, startedAtMs = ms(25, 18, 30), measurementStartMs = ms(25, 18, 30), lafDb = 58.2, backgroundDb = 46.2,
        dominantCategory = "road_traffic", dominantScore = 0.5f, classifierEnabled = true, sharedPoint = point,
    )
    // 0.9.4.6: an «Unterwegs messen» run (never shared: no point; the chip «Unterwegs · nichts auf die Karte»).
    private val unterwegsLive = runningLive.copy(sharedPoint = null, unterwegs = true, lafDb = 71.4, dominantCategory = "loud_vehicle")
    private val unterwegsStatus = shareStatus(on, true, ms(25, 18, 30), false, LocateState.Idle, true, unterwegs = true) { "18:42" }!!
    private val events = listOf(
        event(3, ms(25, 18, 52, 10), 71.3, "loud_vehicle"), event(2, ms(25, 18, 41, 5), 74.0, "loud_vehicle"),
        event(1, ms(25, 18, 36, 40), 66.5, "rail_tram"),
    )

    private enum class Run { IDLE, WINDOW, UNTERWEGS, WINDOW_RECORDING }

    // 0.9.6: the banner's two states — the owner's example (−2,3 dB from the standard, 01.11.2026) and the standard value.
    private val calibrated = CalibrationStatus.Calibrated(
        CalibrationMath.offsetFromDeviation(-2.3), LocalDateTime.of(2026, 11, 1, 15, 45).atZone(zone).toInstant().toEpochMilli(),
    )

    private fun render(state: String, dark: Boolean, run: Run, cal: Boolean) =
        paparazzi.snapshot("messen-$edition-$state-$suffix") {
            LessNoiseTheme(dark = dark) {
                // 0.9.4.6: no Labor banner (topBar empty in both editions, as EditionUi.Banner draws nothing).
                Scaffold(bottomBar = { AppBottomBar(bar, bar.indexOf(Screen.MEASURE)) {} }) { padding ->
                    MeasureContent(
                        live = when (run) {
                            Run.IDLE -> LiveState()
                            Run.WINDOW, Run.WINDOW_RECORDING -> runningLive
                            Run.UNTERWEGS -> unterwegsLive
                        },
                        events = if (run == Run.IDLE) emptyList() else events,
                        calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
                        onStart = {}, onStop = {}, onReport = {},
                        modifier = Modifier.padding(padding).fillMaxSize(),
                        shareStatus = when (run) {
                            Run.IDLE -> idleStatus
                            Run.UNTERWEGS -> unterwegsStatus
                            else -> runningStatus
                        },
                        // 0.9.4.6: «Unterwegs messen» in both editions with the map (MeasureScreen: LoudAlerts.AVAILABLE).
                        onUnterwegs = {},
                        // Labor's EditionUi.RecordingNote draws exactly this line while audio is stored (it needs the app
                        // container itself); the public editions never draw it.
                        recordingNote = { if (run == Run.WINDOW_RECORDING) AudioNoteLine("Audio wird gespeichert") },
                        calibration = if (cal) calibrated else CalibrationStatus.NotCalibrated,
                    )
                }
            }
        }

    @Test fun idle() = render("idle-uncalibrated", dark = false, run = Run.IDLE, cal = false)
    @Test fun idleDark() = render("idle-uncalibrated-dark", dark = true, run = Run.IDLE, cal = false)
    @Test fun idleCalibrated() = render("idle-calibrated", dark = false, run = Run.IDLE, cal = true)
    @Test fun idleCalibratedDark() = render("idle-calibrated-dark", dark = true, run = Run.IDLE, cal = true)
    @Test fun running() = render("running-uncalibrated", dark = false, run = Run.WINDOW, cal = false)
    @Test fun runningDark() = render("running-uncalibrated-dark", dark = true, run = Run.WINDOW, cal = false)
    @Test fun runningCalibrated() = render("running-calibrated", dark = false, run = Run.WINDOW, cal = true)
    @Test fun runningCalibratedDark() = render("running-calibrated-dark", dark = true, run = Run.WINDOW, cal = true)
    @Test fun runningUnterwegs() = render("running-unterwegs-calibrated", dark = false, run = Run.UNTERWEGS, cal = true)
    @Test fun runningUnterwegsDark() = render("running-unterwegs-calibrated-dark", dark = true, run = Run.UNTERWEGS, cal = true)
    // Labor·Karte only: the window run while «Audio speichern» stores audio (the one Labor line on «Messen»).
    @Test fun runningRecording() { if (labor) render("running-audio-uncalibrated", dark = false, run = Run.WINDOW_RECORDING, cal = false) }
}

/** The owner's phone, approximately (412 × 915 dp), font 1.0. */
class MessenMain412x915Screenshots : MessenMainDeviceScreenshots(412, 915, 1.0f, "412x915")

/** A small phone, font 1.0. */
class MessenMain360x740Screenshots : MessenMainDeviceScreenshots(360, 740, 1.0f, "360x740")

/** A very small phone with a large font: may scroll; the big «Laut! Melden» must be on screen without scrolling. */
class MessenMain360x640Font130Screenshots : MessenMainDeviceScreenshots(360, 640, 1.3f, "360x640-font130")
