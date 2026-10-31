package ch.lessnoise.app.labor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.ui.AppBottomBar
import ch.lessnoise.app.ui.Screen
import ch.lessnoise.app.ui.SettingsContent
import ch.lessnoise.app.ui.appScreens
import ch.lessnoise.chart.LessNoiseTheme
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * 0.9.4.6: «Einstellungen» of the Labor builds with the one «Labor» card (LaborCardContent, the composable
 * EditionUi.SettingsSection draws — that one needs the app container and an activity-result registry, which the JVM
 * render does not have). Switch off without recordings (only the switch), and switch on with recordings and a ZIP
 * export running (the space, «Clips anhören», the export block with the progress, the two deletes, disabled while
 * exporting). The card is at the end of the screen, so the «on» renders are also drawn scrolled to the end.
 * 412 × 915 dp at 480 dpi, light and dark. src/testLabor runs for labor and laborKarte: file names carry the flavour.
 * Synthetic values only. Written to $stadtlaerm.screenshotDir/0.9.4.6-labor, for review only.
 */
class LaborCardScreenshots {
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
            screenWidth = 412 * 3, screenHeight = 915 * 3, xdpi = 480, ydpi = 480, density = Density.XXHIGH, fontScale = 1.0f,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.4.6-labor")),
        useDeviceResolution = true,
    )

    private val edition = if (BuildConfig.FLAVOR == "laborKarte") "labor-karte" else "labor"
    private val bar = appScreens(BuildConfig.UPLOAD_AVAILABLE, mapAvailable = BuildConfig.UPLOAD_AVAILABLE)
    private val gb = 1_000_000_000L

    @Composable
    private fun card(on: Boolean) {
        val job: ExportJobState = if (on) {
            ExportJobState.Running(ExportKind.ZIP, ExportProgress(412, 1830, 1_234_000_000, 2_800_000_000, "continuous/20261031_02.m4a"))
        } else ExportJobState.Idle
        LaborCardContent(
            recordAudio = on, onRecordAudio = {}, switchSupporting = LaborCardTexts.SWITCH_SUPPORTING,
            showFiles = LaborCardTexts.showsFiles(on, clips = if (on) 3826 else 0, hourFiles = if (on) 61 else 0, classifierFiles = if (on) 63 else 0),
            used = LaborCardTexts.used(if (on) 1_234_000_000 else 0, 5 * gb), clips = if (on) 3826 else 0, onClips = {},
            export = LaborExportBlock(
                scope = ExportScope.LAST_24H, onScope = {}, withoutHourFiles = true, onWithoutHourFiles = {},
                estimate = ExportScopes.estimateLine(ExportScope.LAST_24H, true, ExportEstimate(412, 165_000_000), false),
                folderNote = null, job = job, onFolder = {}, onZip = {}, onPickFolder = {}, onCancel = {}, onShareZip = {},
            ),
            deleteEnabled = !on, deleteNote = if (on) "Löschen ist während eines Exports nicht möglich." else null,
            onDeleteExported = {}, onDeleteAll = {}, message = null,
        )
    }

    private fun render(name: String, on: Boolean, dark: Boolean, scrolledToEnd: Boolean = false) =
        paparazzi.snapshot("einstellungen-$edition-$name") {
            LessNoiseTheme(dark = dark) {
                Scaffold(bottomBar = { AppBottomBar(bar, bar.indexOf(Screen.SETTINGS)) {} }) { padding ->
                    val scroll = rememberScrollState()
                    if (scrolledToEnd) LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
                    SettingsContent(
                        minutes = 1290, events = 214, running = false, canClear = true, info = null,
                        onBattery = {}, onExport = {}, onClear = {}, onAdvanced = {},
                        modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background),
                        editionSection = { card(on) },
                        scroll = scroll,
                    )
                }
            }
        }

    @Test fun off() = render("labor-off", on = false, dark = false, scrolledToEnd = true)
    @Test fun offDark() = render("labor-off-dark", on = false, dark = true, scrolledToEnd = true)
    @Test fun onExporting() = render("labor-on-export", on = true, dark = false, scrolledToEnd = true)
    @Test fun onExportingDark() = render("labor-on-export-dark", on = true, dark = true, scrolledToEnd = true)
    // The top of the same screen (the card starts below «Datenschutz»): what the owner sees on opening «Einstellungen».
    @Test fun onTop() = render("labor-on-top", on = true, dark = false)
}
