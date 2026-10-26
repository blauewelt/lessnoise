package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.labor.ExportEstimate
import ch.lessnoise.app.labor.ExportJobPanel
import ch.lessnoise.app.labor.ExportJobState
import ch.lessnoise.app.labor.ExportKind
import ch.lessnoise.app.labor.ExportProgress
import ch.lessnoise.app.labor.ExportScope
import ch.lessnoise.app.labor.ExportScopes
import ch.lessnoise.app.labor.ExportScopeControls
import ch.lessnoise.chart.LessNoiseTheme
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * 0.9.4: the «Export» row, «Ohne Stundendateien» and the size line above the export buttons, in a
 * stand-in of the end of the Labor section (the section itself needs the app container and an
 * activity-result registry, which the JVM render does not have; same card, same buttons and texts).
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
        // 720 px at 320 dpi = 360 dp.
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 720, screenHeight = 1400, xdpi = 320, ydpi = 320, density = Density.XHIGH),
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
                    SectionCard("Labor") {
                        Text("…", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {}) { Text("Clips anhören") }
                        ExportScopeControls(scope, {}, withoutHours, {}, estimate, folderNote, enabled = job !is ExportJobState.Running)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {}) { Text("In Ordner exportieren") }
                            OutlinedButton(onClick = {}) { Text("Als ZIP teilen") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {}) { Text("Ordner wählen") }
                            TextButton(onClick = {}) { Text("Wo sind die Dateien?") }
                        }
                        ExportJobPanel(job, {}, {}, {})
                        OutlinedButton(onClick = {}) { Text("Exportierte Aufnahmen löschen") }
                        OutlinedButton(onClick = {}) { Text("Alle Aufnahmen löschen") }
                    }
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
