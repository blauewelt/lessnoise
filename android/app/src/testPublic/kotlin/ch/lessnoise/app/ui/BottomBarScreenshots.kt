package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.chart.LessNoiseTheme
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * 0.6.3: the five tabs (Messen · Messungen · Karte · Teilen · Einstellungen) on narrow phones, the
 * frame exactly the phone's width (420 dpi: 945 px = 360 dp, 840 px = 320 dp). All five labels
 * share one size. Written to $stadtlaerm.screenshotDir/0.6.3 (default app/build/screenshots/0.6.3).
 */
private class BarPngWriter(private val dir: File) : SnapshotHandler {
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

private fun barPaparazzi(widthPx: Int) = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = widthPx, screenHeight = 1200, xdpi = 420, ydpi = 420, density = Density(420)),
    theme = "android:Theme.Material.Light.NoActionBar",
    snapshotHandler = BarPngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.7.2")),
    useDeviceResolution = true,
)

private fun snapshotBar(p: Paparazzi, name: String) {
    val screens = appScreens(uploadAvailable = true, mapAvailable = true)
    p.snapshot(name) {
        LessNoiseTheme(dark = false) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.BottomCenter) {
                AppBottomBar(screens, screens.indexOf(Screen.MAP)) {}
            }
        }
    }
}

class BottomBar360Screenshot {
    @get:Rule
    val paparazzi = barPaparazzi(945)

    @Test
    fun bottomBar360dp() = snapshotBar(paparazzi, "bottom-bar-360dp")
}

class BottomBar320Screenshot {
    @get:Rule
    val paparazzi = barPaparazzi(840)

    @Test
    fun bottomBar320dp() = snapshotBar(paparazzi, "bottom-bar-320dp")
}
