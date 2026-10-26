package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.data.EventEntity
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
 * Screens of the Online-Version, rendered on the JVM (Paparazzi) at phone width (1080 px at 420 dpi ≈
 * 411 dp), synthetic values: since 0.8.4 «Messen» without a mode switch (idle with «Laut! Melden» on
 * top, light and dark, «Mehr dazu» open, the Labor·Karte variant with «Unterwegs aufnehmen»; running
 * on the map, carried («Unterwegs erkannt»), no location, light and dark), the consent dialog, the
 * simplified «Teilen», and the bottom bar with «Karte» over the map tab's error state and its banner
 * (the WebView itself does not render on the JVM). Written to $stadtlaerm.screenshotDir/0.8.4
 * (default app/build/screenshots/0.8.4), for review only; the website's PNGs in docs/ are not made here.
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
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 1080, screenHeight = 2340, xdpi = 420, ydpi = 420, density = Density(420)),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.2-share")),
        useDeviceResolution = true,
    )

    private val zone = ZoneId.of("Europe/Zurich")
    private fun ms(h: Int, m: Int, s: Int = 0) = LocalDateTime.of(2026, 10, 15, h, m, s).atZone(zone).toInstant().toEpochMilli()

    private fun event(id: Long, at: Long, db: Double, category: String) = EventEntity(
        id = id, startEpochMs = at, startIso = "", durationSeconds = 4.0, lafMaxDb = db, selDb = db, backgroundDb = 46.0,
        thresholdDb = 6.5, dominantCategory = category, dominantScore = 0.6f, topLabelsJson = "[]", classifierFrames = 4,
        calibrationId = null, audioSource = "UNPROCESSED", calibrated = false,
    )

    private val names = mapOf("road_traffic" to "Strassenverkehr", "loud_vehicle" to "Lautes Fahrzeug", "rail_tram" to "Tram & Bahn")
    private val point = LocationNoise.Point(2683351, 1247988, 50)
    private val on = UploadSnapshot(enabled = true, shareDecision = ShareDecision.YES, deviceId = "d", token = "t")

    private val bar = appScreens(uploadAvailable = true, mapAvailable = true)

    /** «Messen» as the app shows it: inside the bottom bar. */
    private fun messenScreen(
        name: String,
        live: LiveState,
        status: ShareStatus?,
        unterwegsButton: Boolean = false,
        dark: Boolean = false,
        events: List<EventEntity> = emptyList(),
        moreOpen: Boolean = false,
    ) {
        paparazzi.snapshot(name) {
            LessNoiseTheme(dark = dark) {
                Box {
                    Scaffold(bottomBar = { AppBottomBar(bar, bar.indexOf(Screen.MEASURE)) {} }) { padding ->
                        MeasureContent(
                            live = live, events = events, calibrating = false,
                            categoryName = { names[it] ?: "Sonstiges" }, onStart = {}, onStop = {}, onReport = {},
                            modifier = Modifier.padding(padding).fillMaxSize(),
                            shareStatus = status,
                            onUnterwegs = if (unterwegsButton) ({}) else null,
                        )
                    }
                    // 0.8.7: «Mehr dazu» is a ModalBottomSheet, a window Paparazzi does not draw: its content in a
                    // frame drawn like the sheet (scrim, 28 dp top corners, surfaceContainerLow, drag handle), over the bar.
                    if (moreOpen) SheetFrame { MoreSheetContent() }
                }
            }
        }
    }

    private fun runningLive(status: ShareStatus, unterwegs: Boolean = false, moving: Boolean = false, laf: Double = 58.2) = LiveState(
        running = true, startedAtMs = ms(18, 30), measurementStartMs = ms(18, 30), lafDb = laf, backgroundDb = 46.2,
        dominantCategory = "road_traffic", dominantScore = 0.5f, classifierEnabled = true, unterwegs = unterwegs, moving = moving,
        sharedPoint = if (unterwegs || status.icon == StatusIcon.LOCATING || status.icon == StatusIcon.NO_LOCATION) null else point,
    )

    private val runningEvents = listOf(event(2, ms(18, 41, 5), 74.0, "loud_vehicle"), event(1, ms(18, 36, 40), 66.5, "rail_tram"))

    private fun messen(name: String, status: ShareStatus, dark: Boolean = false) =
        messenScreen(name, runningLive(status), status, dark = dark, events = runningEvents)

    private val idleSent = shareStatus(on.copy(lastSuccessAtMs = ms(18, 42)), false, 0, false, LocateState.Idle, true) { "18:42" }
    private val runningSent =
        shareStatus(on.copy(lastSuccessAtMs = ms(18, 42), minutesSentUpToMs = ms(18, 41)), true, ms(18, 30), true, LocateState.Idle, true) { "18:42" }!!

    // 0.8.4: no mode switch. Idle = on the road: «Laut! Melden» (primary) on top, «Messung starten» (tonal), one line, the chip.
    @Test
    fun messenIdle() = messenScreen("messen-idle", LiveState(), idleSent)

    @Test
    fun messenIdleDark() = messenScreen("messen-idle-dark", LiveState(), idleSent, dark = true)

    @Test
    fun messenIdleMehrDazu() = messenScreen("messen-idle-mehr-dazu", LiveState(), idleSent, moreOpen = true)

    // Labor·Karte only: the third button «Unterwegs aufnehmen» (rendered here with the Online-Version's
    // composable and onUnterwegs set, as MeasureScreen does when LoudAlerts.AVAILABLE).
    @Test
    fun messenIdleLaborKarte() = messenScreen("messen-idle-labor-karte", LiveState(), idleSent, unterwegsButton = true)

    // A window run while the phone is carried: the chip «Unterwegs erkannt · nichts auf die Karte» is the only sign.
    @Test
    fun messenRunningErkannt() {
        val status = shareStatus(on, true, ms(18, 30), true, LocateState.Idle, true, moving = true) { "18:42" }!!
        messenScreen("messen-running-erkannt", runningLive(status, moving = true, laf = 66.0), status, events = runningEvents)
    }

    @Test
    fun messenLocating() = messen("messen-status-locating", shareStatus(on, true, ms(18, 30), false, LocateState.Locating, true, hm = Fmt::hm)!!)

    @Test
    fun messenNoPoint() = messen(
        "messen-status-no-point",
        shareStatus(on, true, ms(18, 30), false, LocateState.Failed(ch.lessnoise.app.location.LocationPolicy.Outcome.LocationOff), true, hm = Fmt::hm)!!,
    )

    // Running = at the window: level card, «Stoppen», «Läuft seit …», the chip, Lärmspitzen; no «Laut! Melden».
    @Test
    fun messenOnTheMap() = messen("messen-running-on-the-map", runningSent)

    @Test
    fun messenDark() = messen("messen-dark", runningSent, dark = true)

    @Test
    fun messenNotSharingDark() = messen(
        "messen-status-not-sharing-dark",
        shareStatus(UploadSnapshot(shareDecision = ShareDecision.NO), true, ms(18, 30), false, LocateState.Idle, true) { "18:42" }!!,
        dark = true,
    )

    @Test
    fun consentDialog() {
        paparazzi.snapshot("consent-dialog") {
            LessNoiseTheme(dark = false) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    Box(Modifier.padding(horizontal = 24.dp)) {
                        ConsentDialogContent(sigmaM = 50, onYes = {}, onNo = {})
                    }
                }
            }
        }
    }

    @Test
    fun teilen() {
        val s = on.copy(lastSuccessAtMs = ms(18, 42), minutesSentUpToMs = ms(18, 41))
        paparazzi.snapshot("teilen") {
            LessNoiseTheme(dark = false) {
                ShareContent(
                    s = s,
                    status = shareStatus(s, false, 0, false, LocateState.Idle, true) { "18:42" },
                    pending = 0, reports = ReportSnapshot(), busy = null, result = null,
                    onSwitch = {}, onStatus = {}, onWifiOnly = {}, onSigma = {}, onSaveSite = {}, onDelete = {}, onNewId = {},
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
        }
    }

    @Test
    fun karteTabOffline() {
        val screens = appScreens(uploadAvailable = true, mapAvailable = true)
        paparazzi.snapshot("karte-tab-error") {
            LessNoiseTheme(dark = false) {
                Scaffold(bottomBar = { AppBottomBar(screens, screens.indexOf(Screen.MAP)) {} }) { padding ->
                    Column(Modifier.padding(padding).fillMaxWidth()) {
                        MapStatusContent(load = MapLoad.ERROR, progress = 0, onRetry = {})
                    }
                }
            }
        }
    }

    // 0.6.3 map tab fix: the banner above the map in its four states. The WebView does not render
    // on the JVM; a grey area stands for the map below the banner.
    private fun karteBanner(name: String, banner: MapBanner?, dark: Boolean = false) {
        val screens = appScreens(uploadAvailable = true, mapAvailable = true)
        paparazzi.snapshot(name) {
            LessNoiseTheme(dark = dark) {
                Scaffold(bottomBar = { AppBottomBar(screens, screens.indexOf(Screen.MAP)) {} }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        banner?.let { MapBannerContent(it, onTap = {}) }
                        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFDDE3E0)))
                    }
                }
            }
        }
    }

    private fun banner(s: UploadSnapshot, running: Boolean, hasPoint: Boolean, locate: LocateState = LocateState.Idle, now: Long = ms(18, 45)) =
        mapBanner(s, running, ms(18, 30), hasPoint, hasPoint, locate, true, now) { "18:42" }

    @Test
    fun karteBannerNotSharing() = karteBanner("karte-banner-not-sharing", banner(UploadSnapshot(shareDecision = ShareDecision.NO), true, false))

    @Test
    fun karteBannerNoPoint() = karteBanner("karte-banner-no-point", banner(on, true, false, LocateState.Locating))

    @Test
    fun karteBannerNotSharingDark() =
        karteBanner("karte-banner-not-sharing-dark", banner(UploadSnapshot(shareDecision = ShareDecision.NO), true, false), dark = true)

    @Test
    fun karteBannerFirstValues() = karteBanner("karte-banner-first-values", banner(on, true, true))

    @Test
    fun karteBannerSent() = karteBanner(
        "karte-banner-sent",
        banner(on.copy(lastSuccessAtMs = ms(18, 42), minutesSentUpToMs = ms(18, 41)), true, true, now = ms(18, 43)).also { check(it?.text == bannerSent("18:42")) },
    )

    @Test
    fun karteTabLoading() {
        val screens = appScreens(uploadAvailable = true, mapAvailable = true)
        paparazzi.snapshot("karte-tab-loading") {
            LessNoiseTheme(dark = false) {
                Scaffold(bottomBar = { AppBottomBar(screens, screens.indexOf(Screen.MAP)) {} }) { padding ->
                    Column(Modifier.padding(padding).fillMaxWidth()) {
                        MapStatusContent(load = MapLoad.LOADING, progress = 40, onRetry = {})
                    }
                }
            }
        }
    }
}

/** A ModalBottomSheet as Material 3 draws it, for the JVM renders (Paparazzi does not draw its window). */
@Composable
internal fun SheetFrame(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(cs.scrim.copy(alpha = 0.32f)), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = cs.surfaceContainerLow,
            tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(vertical = 22.dp).size(width = 32.dp, height = 4.dp).background(cs.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
                content()
            }
        }
    }
}
