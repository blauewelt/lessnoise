package ch.lessnoise.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The «Messungen» screenshots for wenigerlaerm.ch, from synthetic data only
 * ([SyntheticData.busyStreet]: 8 hours at a busy street, never a real recording). Phone portrait,
 * 1082 px wide like the former docs/img/chart-nacht.png. Written to
 * $STADTLAERM_SCREENSHOT_DIR/website (default chart/build/screenshots/website) as app-messung.png
 * and app-messung-dark.png; copy them to docs/img/ by hand.
 */
class WebsiteScreenshots {
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
        // Pixel 7-like width (412 dp at 2.625 = 1082 px); cut to the height of the content.
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1082, screenHeight = 1700, xdpi = 420, ydpi = 420, density = Density(420)),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "website")),
        useDeviceResolution = true,
    )

    private object NoActions : HistoryActions {
        override fun onMode(mode: RangeMode) {}
        override fun onShift(delta: Int) {}
        override fun onHighlight(category: String) {}
        override fun onSelect(selection: Selection?) {}
    }

    private fun render(name: String, dark: Boolean) {
        val d = SyntheticData.busyStreet()
        paparazzi.snapshot(name) {
            LessNoiseTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Text(
                        "Messungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                    HistorySection(
                        window = d.window, data = d, zone = SyntheticData.zone, highlight = "loud_vehicle", eventFloorDb = 30.0,
                        canGoNext = false, selection = null, actions = NoActions,
                    )
                }
            }
        }
    }

    @Test fun messungLight() = render("app-messung", dark = false)

    @Test fun messungDark() = render("app-messung-dark", dark = true)
}
