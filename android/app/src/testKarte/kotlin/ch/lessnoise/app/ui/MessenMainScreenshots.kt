package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * red logo, the «Unterwegs aufnehmen» button and a stand-in of the red Labor banner — EditionUi.Banner needs the
 * app container). [widthDp] × [heightDp] at 480 dpi (3 px per dp) and [fontScale]; no system bars (a phone has
 * about 24 dp status bar and 24–48 dp navigation more). Synthetic values only. Written to
 * $stadtlaerm.screenshotDir/0.9.4.4-messen as messen-<edition>-<state>-<suffix>.png, for review only.
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
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.4.4-messen")),
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
    private val events = listOf(
        event(3, ms(25, 18, 52, 10), 71.3, "loud_vehicle"), event(2, ms(25, 18, 41, 5), 74.0, "loud_vehicle"),
        event(1, ms(25, 18, 36, 40), 66.5, "rail_tram"),
    )

    private fun render(state: String, dark: Boolean, running: Boolean) =
        paparazzi.snapshot("messen-$edition-$state-$suffix") {
            LessNoiseTheme(dark = dark) {
                Scaffold(
                    topBar = {
                        if (labor) {
                            Column(Modifier.fillMaxWidth().background(Color(0xFFB3261E)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(
                                    "WENIGER LÄRM LABOR · KARTE – kann Audio aufzeichnen", color = Color.White,
                                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    },
                    bottomBar = { AppBottomBar(bar, bar.indexOf(Screen.MEASURE)) {} },
                ) { padding ->
                    MeasureContent(
                        live = if (running) runningLive else LiveState(),
                        events = if (running) events else emptyList(),
                        calibrating = false, categoryName = { names[it] ?: "Sonstiges" },
                        onStart = {}, onStop = {}, onReport = {},
                        modifier = Modifier.padding(padding).fillMaxSize(),
                        shareStatus = if (running) runningStatus else idleStatus,
                        onUnterwegs = if (labor) ({}) else null,
                    )
                }
            }
        }

    @Test fun idle() = render("idle", dark = false, running = false)
    @Test fun idleDark() = render("idle-dark", dark = true, running = false)
    @Test fun running() = render("running", dark = false, running = true)
    @Test fun runningDark() = render("running-dark", dark = true, running = true)
}

/** The owner's phone, approximately (412 × 915 dp), font 1.0. */
class MessenMain412x915Screenshots : MessenMainDeviceScreenshots(412, 915, 1.0f, "412x915")

/** A small phone, font 1.0. */
class MessenMain360x740Screenshots : MessenMainDeviceScreenshots(360, 740, 1.0f, "360x740")

/** A very small phone with a large font: may scroll; the big «Laut! Melden» must be on screen without scrolling. */
class MessenMain360x640Font130Screenshots : MessenMainDeviceScreenshots(360, 640, 1.3f, "360x640-font130")
