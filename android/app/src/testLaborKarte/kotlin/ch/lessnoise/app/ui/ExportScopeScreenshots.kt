package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.labor.ExportEstimate
import ch.lessnoise.app.labor.ExportJobState
import ch.lessnoise.app.labor.ExportKind
import ch.lessnoise.app.labor.ExportProgress
import ch.lessnoise.app.labor.ExportScope
import ch.lessnoise.app.labor.ExportScopes
import ch.lessnoise.app.labor.LaborCardContent
import ch.lessnoise.app.labor.LaborCardTexts
import ch.lessnoise.app.labor.LaborExportBlock
import ch.lessnoise.chart.LessNoiseTheme
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * 0.9.4: the «Export» row, «Ohne Stundendateien» and the size line above the export buttons. 0.9.4.6: in the real
 * «Labor» card (LaborCardContent, stateless; until 0.9.4.5 a stand-in of the old section, which needed the app
 * container and an activity-result registry), switch on.
 * 360 dp wide (a small phone) and 411 dp. Written to $stadtlaerm.screenshotDir/0.9.4-export (default
 * app/build/screenshots/0.9.4-export), for review only.
 */
class ExportScopeScreenshots {
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
        // 720 px at 320 dpi = 360 dp. 0.9.4.6: 1000 dp high (was 700), so the whole «Labor» card fits (switch to the deletes).
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 720, screenHeight = 2000, xdpi = 320, ydpi = 320, density = Density.XHIGH),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.4-export")),
        useDeviceResolution = true,
    )

    private fun render(
        name: String, scope: ExportScope, withoutHours: Boolean, estimate: String, folderNote: String?, dark: Boolean = false,
        job: ExportJobState = ExportJobState.Idle,
    ) =
        paparazzi.snapshot(name) {
            LessNoiseTheme(dark = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    // 0.9.4.6: the real card (LaborCardContent), switch on (until 0.9.4.5 a stand-in of the old section).
                    LaborCardContent(
                        recordAudio = true, onRecordAudio = {}, switchSupporting = LaborCardTexts.SWITCH_SUPPORTING, showFiles = true,
                        used = LaborCardTexts.used(2_480_000_000, 5_000_000_000), clips = 6000, onClips = {},
                        export = LaborExportBlock(scope, {}, withoutHours, {}, estimate, folderNote, job, {}, {}, {}, {}, {}),
                        deleteEnabled = job !is ExportJobState.Running,
                        deleteNote = if (job is ExportJobState.Running) "Löschen ist während eines Exports nicht möglich." else null,
                        onDeleteExported = {}, onDeleteAll = {}, message = null,
                    )
                }
            }
        }

    // The texts as the app builds them (ExportScopes: no-break space between number and unit).
    private fun line(sc: ExportScope, noHours: Boolean, n: Int, bytes: Long, never: Boolean = false) =
        ExportScopes.estimateLine(sc, noHours, ExportEstimate(n, bytes), never)

    @Test fun last24h() = render(
        "export-24h", ExportScope.LAST_24H, true, line(ExportScope.LAST_24H, true, 412, 165_000_000),
        "Davon neu für den Export-Ordner: ${ExportScopes.files(30)}, ${ExportScopes.size(12_000_000)}.",
    )
    @Test fun sinceNever() = render(
        "export-seit-nie", ExportScope.SINCE_LAST, false, line(ExportScope.SINCE_LAST, false, 6169, 3_370_000_000, never = true), null,
    )
    @Test fun allDark() = render("export-alles-dark", ExportScope.ALL, true, line(ExportScope.ALL, true, 6138, 2_480_000_000), null, dark = true)

    // 0.9.4.1: the export running in the service, finished, and refused for lack of space.
    @Test fun running() = render(
        "export-running", ExportScope.ALL, false, line(ExportScope.ALL, false, 1830, 2_800_000_000), null,
        job = ExportJobState.Running(ExportKind.ZIP, ExportProgress(412, 1830, 1_234_000_000, 2_800_000_000, "continuous/20261025_23_2.m4a")),
    )
    @Test fun preparingDark() = render(
        "export-preparing-dark", ExportScope.LAST_24H, true, line(ExportScope.LAST_24H, true, 412, 165_000_000), null, dark = true,
        job = ExportJobState.Running(ExportKind.FOLDER, null),
    )
    @Test fun zipDone() = render(
        "export-zip-done", ExportScope.LAST_24H, true, line(ExportScope.LAST_24H, true, 412, 165_000_000), null,
        job = ExportJobState.Done(ExportKind.ZIP, "ZIP fertig: lessnoise-labor-20261026-24h.zip, ${ExportScopes.files(412)}, ${ExportScopes.size(165_000_000)}.", File("x.zip"), 0),
    )
    @Test fun lowSpace() = render(
        "export-low-space", ExportScope.ALL, false, line(ExportScope.ALL, false, 1830, 2_800_000_000), null,
        job = ExportJobState.Failed(ExportKind.ZIP, "Zu wenig Speicher für ZIP: 2821 MB nötig, 1500 MB frei – Ordner-Export nutzen", suggestFolder = true),
    )
}
