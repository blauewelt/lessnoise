package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import ch.lessnoise.app.data.CalibrationEntity
import ch.lessnoise.chart.LessNoiseTheme
import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.calibration.CalibrationWarning
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 0.9.5.1: «Kalibrieren» with the deviation from the standard — the active line, «Mit Messgerät
 * vergleichen» with a result, «Korrektur von Hand», a history row and «Wirklich speichern?» — on the
 * owner's phone (412 × 915 dp at 480 dpi), light. The owner's case of 2026-11-01: the phone shows 50,0 dB(A)
 * with the standard, the meter 70,1 dB(A) → +20,1 dB. Paparazzi draws no Dialog windows: the dialog is
 * drawn in a test-only frame of Material's AlertDialog look with the app's texts. Written to
 * $stadtlaerm.screenshotDir/0.9.5.1-calibration, for review only. (0.9.6: the banner renders, CalibrationBannerDeviceScreenshots, below.)
 */
private class CalPngWriter(private val dir: File) : SnapshotHandler {
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

class Calibration412x915Screenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = 412 * 3, screenHeight = 915 * 3, xdpi = 480, ydpi = 480, density = Density.XXHIGH, fontScale = 1.0f),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = CalPngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.5.1-calibration")),
        useDeviceResolution = true,
    )

    private val std = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
    private val owner = std + 20.07
    private val at = LocalDateTime.of(2026, 11, 1, 15, 45).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Composable
    private fun Screen(content: @Composable () -> Unit) = LessNoiseTheme(dark = false) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) { content() }
    }

    @Composable
    private fun Status(calibrated: Boolean, offset: Double) = SectionCard {
        Text(
            CalibrationDeviation.activeLine(calibrated, at, CalibrationEntity.METHOD_REFERENCE, offset),
            style = MaterialTheme.typography.bodyMedium,
        )
        StatRow("Gerät", "Google Pixel 9")
        StatRow("Audioquelle", "Unbearbeitet")
        StatRow("Mikrofon", "eingebaut")
    }

    @Composable
    private fun History(offset: Double, method: String) = Text(
        "#7 · ${Fmt.dateTime(at)} · ${methodName(method)} · ${CalibrationDeviation.historyPart(offset)} · Unbearbeitet",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )

    // Calibrated with the owner's +20,1 dB: the active line, a fresh result with the meter's 70,1 → +20,1 dB and
    // the warning, «Korrektur von Hand» prefilled with the active deviation, the history.
    @Test fun calibratedReferenceResult() = paparazzi.snapshot("calibration-reference-result") {
        Screen {
            CalibrateIntro()
            Status(calibrated = true, offset = owner)
            ReferenceCard(
                windowSeconds = 30, busy = false, phoneDb = 50.0 + (owner - std), reading = "70,1", onReading = {}, notes = "", onNotes = {},
                offsetDb = std + 20.1, warnings = listOf(CalibrationWarning.IMPLAUSIBLE_OFFSET), onMeasure = {}, onSave = {},
            )
            ManualCard(deviation = CalibrationDeviation.manualPrefill(true, owner), onDeviation = {}, notes = "", onNotes = {}, busy = false, onSave = {})
            SectionCard("Verlauf") {}
            History(owner, CalibrationEntity.METHOD_REFERENCE)
            History(std, CalibrationEntity.METHOD_RESET)
        }
    }

    // Within 20 dB: +3,4 dB, no warning (and no dialog on «Übernehmen»).
    @Test fun withinLimit() = paparazzi.snapshot("calibration-within-20db") {
        Screen {
            Status(calibrated = true, offset = std + 3.4)
            ReferenceCard(
                windowSeconds = 30, busy = false, phoneDb = 52.0, reading = "55,4", onReading = {}, notes = "", onNotes = {},
                offsetDb = std + 3.4, warnings = emptyList(), onMeasure = {}, onSave = {},
            )
            ManualCard(deviation = "−3,0", onDeviation = {}, notes = "", onNotes = {}, busy = false, onSave = {})
        }
    }

    @Test fun notCalibrated() = paparazzi.snapshot("calibration-not-calibrated") {
        Screen {
            Status(calibrated = false, offset = std)
            ReferenceCard(30, false, null, "", {}, "", {}, null, emptyList(), {}, {})
            ManualCard(deviation = "", onDeviation = {}, notes = "", onNotes = {}, busy = false, onSave = {})
        }
    }

    // «Übernehmen» at +20,1 dB: «Wirklich speichern?» over the screen.
    @Test fun confirmDialog() = paparazzi.snapshot("calibration-confirm-dialog") {
        LessNoiseTheme(dark = false) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Status(calibrated = true, offset = owner)
                    ReferenceCard(30, false, 50.0 + (owner - std), "70,1", {}, "", {}, std + 20.1, listOf(CalibrationWarning.IMPLAUSIBLE_OFFSET), {}, {})
                }
                AlertFrame(
                    CalibrationDeviation.CONFIRM_TITLE,
                    listOf(CalibrationDeviation.CONFIRM_CANCEL, CalibrationDeviation.CONFIRM_SAVE),
                ) { Text(CalibrationDeviation.confirmText(std + 20.1, CalibrationEntity.METHOD_REFERENCE)) }
            }
        }
    }

    /** Test-only: Material's AlertDialog look over the dimmed screen (as in ClarityScreenshots). */
    @Composable
    private fun AlertFrame(title: String, buttons: List<String>, text: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation, modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = AlertDialogDefaults.titleContentColor)
                    Spacer(Modifier.height(16.dp))
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        buttons.forEach { TextButton(onClick = {}) { Text(it) } }
                    }
                }
            }
        }
    }
}

/**
 * 0.9.6: the top of «Kalibrieren» (where the banner's tap on «Messen» lands, AdvancedScreen at CALIBRATE_ITEM): the
 * heading, the same banner without a pointer (green −2,3 dB on 01.11.2026, red «Telefon nicht kalibriert …»), then the
 * status card with the detail, light and dark, at 412 × 915 and 360 × 740 dp. Written to
 * $stadtlaerm.screenshotDir/0.9.6-calibration, for review only.
 */
abstract class CalibrationBannerDeviceScreenshots(widthDp: Int, heightDp: Int, private val suffix: String) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenWidth = widthDp * 3, screenHeight = heightDp * 3, xdpi = 480, ydpi = 480, density = Density.XXHIGH, fontScale = 1.0f),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = CalPngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.6-calibration")),
        useDeviceResolution = true,
    )

    private val std = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
    private val at = LocalDateTime.of(2026, 11, 1, 15, 45).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun render(name: String, dark: Boolean, calibrated: Boolean) = paparazzi.snapshot("kalibrieren-top-$name-$suffix") {
        LessNoiseTheme(dark = dark) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                val offset = if (calibrated) std - 2.3 else std
                CalibrateIntro()
                // As AdvancedScreen draws it: no onClick, so no pointer.
                CalibrationBanner(
                    if (calibrated) CalibrationStatus.Calibrated(offset, at) else CalibrationStatus.NotCalibrated,
                    Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                SectionCard {
                    Text(
                        if (calibrated) CalibrationDeviation.activeLine(true, at, CalibrationEntity.METHOD_REFERENCE, offset) else CalibrationDeviation.NOT_CALIBRATED,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    StatRow("Gerät", "Google Pixel 9")
                    StatRow("Audioquelle", "Unbearbeitet")
                    StatRow("Mikrofon", "eingebaut")
                }
            }
        }
    }

    @Test fun calibrated() = render("calibrated", dark = false, calibrated = true)
    @Test fun calibratedDark() = render("calibrated-dark", dark = true, calibrated = true)
    @Test fun notCalibrated() = render("uncalibrated", dark = false, calibrated = false)
    @Test fun notCalibratedDark() = render("uncalibrated-dark", dark = true, calibrated = false)
}

class CalibrationBanner412x915Screenshots : CalibrationBannerDeviceScreenshots(412, 915, "412x915")
class CalibrationBanner360x740Screenshots : CalibrationBannerDeviceScreenshots(360, 740, "360x740")
