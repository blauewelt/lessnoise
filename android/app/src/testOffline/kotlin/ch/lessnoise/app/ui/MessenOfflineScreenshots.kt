package ch.lessnoise.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.chart.LessNoiseTheme
import ch.lessnoise.dsp.calibration.CalibrationMath
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 0.9.6: «Messen» of the Offline-Version (no reports, no map: «Langzeitmessung starten» filled, the 0.9.0 level card,
 * «Stoppen» filled) with the calibration banner — idle and running, calibrated (green, −2,3 dB on 01.11.2026) and not
 * (red), light and dark. [widthDp] × [heightDp] at 480 dpi (3 px per dp) and [fontScale]; no system bars. Synthetic values
 * only. Written to $stadtlaerm.screenshotDir/0.9.6-messen as messen-offline-<state>-<suffix>.png, for review only.
 */
abstract class MessenOfflineDeviceScreenshots(widthDp: Int, heightDp: Int, fontScale: Float, private val suffix: String) {
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

    private val zone = ZoneId.of("Europe/Zurich")
    private fun ms(d: Int, h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, d, h, m, s).atZone(zone).toInstant().toEpochMilli()
    // The Offline-Version's tabs: Messen · Messungen · Einstellungen.
    private val bar = appScreens(uploadAvailable = false, mapAvailable = false)
    private val names = mapOf("road_traffic" to "Strassenverkehr", "loud_vehicle" to "Lautes Fahrzeug", "rail_tram" to "Tram & Bahn")

    private fun event(id: Long, at: Long, db: Double, category: String) = EventEntity(
        id = id, startEpochMs = at, startIso = "", durationSeconds = 4.0, lafMaxDb = db, selDb = db, backgroundDb = 46.0,
        thresholdDb = 6.5, dominantCategory = category, dominantScore = 0.6f, topLabelsJson = "[]", classifierFrames = 4,
        calibrationId = null, audioSource = "UNPROCESSED", calibrated = false,
    )

    private val runningLive = LiveState(
        running = true, startedAtMs = ms(25, 18, 30), measurementStartMs = ms(25, 18, 30), lafDb = 58.2, backgroundDb = 46.2,
        dominantCategory = "road_traffic", dominantScore = 0.5f, classifierEnabled = true,
    )
    private val events = listOf(
        event(3, ms(25, 18, 52, 10), 71.3, "loud_vehicle"), event(2, ms(25, 18, 41, 5), 74.0, "loud_vehicle"),
        event(1, ms(25, 18, 36, 40), 66.5, "rail_tram"),
    )
    private val calibrated = CalibrationStatus.Calibrated(
        CalibrationMath.offsetFromDeviation(-2.3), LocalDateTime.of(2026, 11, 1, 15, 45).atZone(zone).toInstant().toEpochMilli(),
    )

    private fun render(state: String, dark: Boolean, running: Boolean, cal: Boolean) =
        paparazzi.snapshot("messen-offline-$state-$suffix") {
            LessNoiseTheme(dark = dark) {
                Scaffold(bottomBar = { AppBottomBar(bar, bar.indexOf(Screen.MEASURE)) {} }) { padding ->
                    MeasureContent(
                        live = if (running) runningLive else LiveState(),
                        events = if (running) events else emptyList(),
                        calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
                        onStart = {}, onStop = {},
                        modifier = Modifier.padding(padding).fillMaxSize(),
                        calibration = if (cal) calibrated else CalibrationStatus.NotCalibrated,
                    )
                }
            }
        }

    @Test fun idle() = render("idle-uncalibrated", dark = false, running = false, cal = false)
    @Test fun idleDark() = render("idle-uncalibrated-dark", dark = true, running = false, cal = false)
    @Test fun idleCalibrated() = render("idle-calibrated", dark = false, running = false, cal = true)
    @Test fun idleCalibratedDark() = render("idle-calibrated-dark", dark = true, running = false, cal = true)
    @Test fun running() = render("running-uncalibrated", dark = false, running = true, cal = false)
    @Test fun runningDark() = render("running-uncalibrated-dark", dark = true, running = true, cal = false)
    @Test fun runningCalibrated() = render("running-calibrated", dark = false, running = true, cal = true)
    @Test fun runningCalibratedDark() = render("running-calibrated-dark", dark = true, running = true, cal = true)
}

/** The owner's phone, approximately (412 × 915 dp), font 1.0. */
class MessenOffline412x915Screenshots : MessenOfflineDeviceScreenshots(412, 915, 1.0f, "412x915")

/** A small phone, font 1.0. */
class MessenOffline360x740Screenshots : MessenOfflineDeviceScreenshots(360, 740, 1.0f, "360x740")

/** A very small phone with a large font: may scroll; «Langzeitmessung starten» must be on screen without scrolling. */
class MessenOffline360x640Font130Screenshots : MessenOfflineDeviceScreenshots(360, 640, 1.3f, "360x640-font130")
