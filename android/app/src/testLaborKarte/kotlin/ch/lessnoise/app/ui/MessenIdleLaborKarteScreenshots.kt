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
import ch.lessnoise.app.edition.EditionUi
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.chart.LessNoiseTheme
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 0.8.7: the idle «Messen» of Labor·Karte with its own resources (the red logo with the record dot,
 * R.color.logo_background = the Labor red), under a stand-in of the red Labor banner (EditionUi.Banner
 * needs the app container, which the JVM render does not have; same colour, text and padding).
 * Synthetic values only. Written to $stadtlaerm.screenshotDir/0.8.8-idle (default
 * app/build/screenshots/0.8.8-idle), for review only.
 */
class MessenIdleLaborKarteScreenshots {
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
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, xdpi = 420, ydpi = 420, density = Density(420)),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.2-idle")),
        useDeviceResolution = true,
    )

    private val zone = ZoneId.of("Europe/Zurich")
    private fun ms(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val bar = appScreens(uploadAvailable = true, mapAvailable = true)
    private val status = shareStatus(
        UploadSnapshot(enabled = true, shareDecision = ShareDecision.YES, deviceId = "d", token = "t", lastSuccessAtMs = ms(24, 18, 42)),
        false, 0, false, LocateState.Idle, true,
    ) { Fmt.sentAt(it, ms(25, 8, 23), zone) }

    private fun render(name: String, dark: Boolean) = paparazzi.snapshot(name) {
        LessNoiseTheme(dark = dark) {
            Scaffold(
                topBar = {
                    Column(Modifier.fillMaxWidth().background(Color(0xFFB3261E)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(EditionUi.BANNER_TEXT, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                },
                bottomBar = { AppBottomBar(bar, bar.indexOf(Screen.MEASURE)) {} },
            ) { padding ->
                MeasureContent(
                    live = LiveState(), events = emptyList(), calibrating = false, categoryName = { "Sonstiges" },
                    onStart = {}, onStop = {}, onReport = {}, modifier = Modifier.padding(padding).fillMaxSize(),
                    shareStatus = status, onUnterwegs = {},
                )
            }
        }
    }

    @Test fun labor() = render("messen-idle-labor-karte-red", dark = false)
    @Test fun laborDark() = render("messen-idle-labor-karte-red-dark", dark = true)
}
