package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.upload.ReportSnapshot
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
 * Sharing in the Online-Version (0.6.3), rendered on the JVM (Paparazzi) at phone width, light
 * theme: the question «Auf die Lärmkarte?», «Messen» with the status line in three states, and the
 * simplified «Teilen». Written to $stadtlaerm.screenshotDir/share (default
 * app/build/screenshots/share) for review; nothing here is compared against a golden image.
 */
class ShareScreenshots {
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
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1082, screenHeight = 2200, xdpi = 420, ydpi = 420, density = Density(420)),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "share")),
        useDeviceResolution = true,
    )

    private val zone = ZoneId.systemDefault()
    private fun ms(h: Int, m: Int) = LocalDateTime.of(2026, 10, 15, h, m).atZone(zone).toInstant().toEpochMilli()
    private val point = LocationNoise.Point(2683351, 1247988, 50)

    @Test
    fun consentDialog() {
        paparazzi.snapshot("share-consent-dialog") {
            LessNoiseTheme(dark = false) {
                Box(Modifier.fillMaxSize().background(Color(0xFF7A7A7A)).padding(24.dp), contentAlignment = Alignment.Center) {
                    ConsentCard(sigmaM = 50, onYes = {}, onNo = {})
                }
            }
        }
    }

    private fun messen(name: String, live: LiveState, status: ShareStatus) {
        paparazzi.snapshot(name) {
            LessNoiseTheme(dark = false) {
                MeasureContent(
                    live = live, events = emptyList(), calibrating = false,
                    categoryName = { if (it == "road_traffic") "Strassenverkehr" else "Sonstiges" }, onStart = {}, onStop = {}, onReport = {},
                    shareLine = { ShareStatusLine(status) {} },
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
        }
    }

    private val running = LiveState(
        running = true, startedAtMs = ms(16, 0), measurementStartMs = ms(16, 0), lafDb = 58.2, backgroundDb = 47.5,
        dominantCategory = "road_traffic", dominantScore = 0.5f, classifierEnabled = true,
    )
    private val on = ShareStatusInput(decision = ShareDecision.YES, enabled = true, running = true, measurementStartMs = ms(16, 0))

    @Test
    fun messenLocating() = messen("share-messen-locating", running, shareStatus(on.copy(locate = LocateState.BUSY)))

    @Test
    fun messenNoLocation() = messen("share-messen-no-location", running, shareStatus(on.copy(locate = LocateState.FAILED)))

    @Test
    fun messenOnTheMap() = messen(
        "share-messen-on-the-map", running.copy(sharedPoint = point),
        shareStatus(on.copy(hasPoint = true, lastSuccessAtMs = ms(16, 12), ackUpToMs = ms(16, 11))),
    )

    @Test
    fun messenIdleOff() = messen(
        "share-messen-idle-off", LiveState(),
        shareStatus(ShareStatusInput(decision = ShareDecision.NO, enabled = false)),
    )

    @Test
    fun teilen() {
        val s = UploadSnapshot(enabled = true, decision = ShareDecision.YES, deviceId = "d", token = "t", lastSuccessAtMs = ms(16, 12))
        paparazzi.snapshot("share-teilen") {
            LessNoiseTheme(dark = false) {
                ShareContent(
                    s = s, status = shareStatus(on.copy(hasPoint = true, lastSuccessAtMs = ms(16, 12), ackUpToMs = ms(16, 11))),
                    pending = 3, reports = ReportSnapshot(), busy = null, result = null,
                    onStatusTap = {}, onSharing = {}, onWifiOnly = {}, onSigma = {}, onSite = {}, onDelete = {}, onNewId = {},
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
        }
    }
}
