package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.chart.LessNoiseTheme
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * «Messen» while measuring, rendered on the JVM (Paparazzi) for wenigerlaerm.ch: synthetic values
 * only (no recording). Written to $STADTLAERM_SCREENSHOT_DIR/website (default
 * app/build/screenshots/website) as app-messen.png; copy it to docs/img/ by hand.
 */
class MeasureScreenshot {
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
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1082, screenHeight = 1700, xdpi = 420, ydpi = 420, density = Density(420)),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "website")),
        useDeviceResolution = true,
    )

    private val zone = ZoneId.of("Europe/Zurich")
    private fun ms(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, 10, h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun event(id: Long, at: Long, db: Double, category: String) = EventEntity(
        id = id, startEpochMs = at, startIso = "", durationSeconds = 4.0, lafMaxDb = db, selDb = db, backgroundDb = 46.0,
        thresholdDb = 6.5, dominantCategory = category, dominantScore = 0.6f, topLabelsJson = "[]", classifierFrames = 4,
        calibrationId = null, audioSource = "UNPROCESSED", calibrated = false,
    )

    private val names = mapOf(
        "road_traffic" to "Strassenverkehr", "loud_vehicle" to "Lautes Fahrzeug", "rail_tram" to "Tram & Bahn", "voices" to "Stimmen",
    )

    @Test
    fun messenWhileMeasuring() {
        val live = LiveState(
            running = true, startedAtMs = ms(18, 30), lafDb = 63.4, backgroundDb = 46.2, dominantCategory = "road_traffic",
            dominantScore = 0.5f, classifierEnabled = true,
        )
        val events = listOf(
            event(6, ms(22, 36, 20), 88.0, "loud_vehicle"), event(5, ms(22, 31, 5), 66.5, "road_traffic"),
            event(4, ms(22, 24, 40), 64.0, "rail_tram"), event(3, ms(22, 17, 12), 61.5, "road_traffic"),
            event(2, ms(22, 9, 3), 59.0, "voices"), event(1, ms(21, 58, 30), 74.0, "loud_vehicle"),
        )
        paparazzi.snapshot("app-messen") {
            LessNoiseTheme(dark = false) {
                MeasureContent(
                    live = live, events = events, calibrating = false,
                    categoryName = { names[it] ?: "Sonstiges" }, onStart = {}, onStop = {},
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
        }
    }
}
