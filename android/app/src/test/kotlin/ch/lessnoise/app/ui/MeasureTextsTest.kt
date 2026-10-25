package ch.lessnoise.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.8.4 (was MeasureModeTest, 0.8.3): «Messen» without a mode switch — running means the window, idle
 * means the road. «Laut! Melden» first while idle (editions with the map), «Messung starten» under it,
 * one line; Labor·Karte's «Unterwegs aufnehmen» as a third button (MeasureTexts.kt, MeasureScreen.kt).
 */
class MeasureTextsTest {
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
    private val screen by lazy { File(app, "src/main/kotlin/ch/lessnoise/app/ui/MeasureScreen.kt").readText() }

    @Test
    fun texts() {
        assertEquals("Laut! Melden", REPORT_BUTTON)
        assertEquals("Messung starten", START_BUTTON)
        assertEquals("Stoppen", STOP_BUTTON)
        assertEquals("Zum Messen: Telefon ans offene Fenster, am Ladegerät.", idleHint(reports = true))
        // Offline and Labor builds: «Messung starten» is the only button, the line without «Zum Messen:».
        assertEquals("Telefon ans offene Fenster, am Ladegerät.", idleHint(reports = false))
        assertEquals("Unterwegs aufnehmen", UNTERWEGS_BUTTON)
        assertEquals("Labor: nimmt Audio auf, teilt nichts.", UNTERWEGS_LABOR_HINT)
        assertEquals("Läuft seit 18:30 · Bildschirm darf aus sein.", runningLine("18:30"))
        for (t in listOf(WINDOW_HINT, PLAIN_HINT, UNTERWEGS_LABOR_HINT, runningLine("18:30"), PLACEMENT_HINT, GUIDE_LINK)) {
            assertTrue(t.length <= 80, "one line: $t")
            assertFalse(t.contains('ß') || Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
        }
    }

    @Test
    fun theModeSwitchAndItsStoredChoiceAreGone() {
        assertFalse(File(app, "src/main/kotlin/ch/lessnoise/app/ui/MeasureMode.kt").exists())
        for (bad in listOf("ModeSwitch", "MeasureMode", "SegmentedButton", "ExtendedFloatingActionButton", "floatingActionButton", "getSharedPreferences")) {
            assertFalse(screen.contains(bad), bad)
        }
        val texts = File(app, "src/main/kotlin/ch/lessnoise/app/ui/MeasureTexts.kt").readText()
        for (bad in listOf("enum class", "SharedPreferences", "\"Am Fenster\"", "Zum Wechseln")) assertFalse(texts.contains(bad), bad)
    }

    @Test
    fun idleReportFirstThenStartTonal() {
        // Order in the idle branch: the primary «Laut! Melden» (56 dp), then the tonal «Messung starten», the line,
        // Labor·Karte's outlined «Unterwegs aufnehmen» with its line.
        val idle = screen.substring(screen.indexOf("// Editions with the map: «Laut! Melden» is the first action"))
        val report = idle.indexOf("Text(REPORT_BUTTON, fontSize = 18.sp)")
        val tonal = idle.indexOf("FilledTonalButton(onClick = onStart")
        val hint = idle.indexOf("Text(idleHint(reports = onReport != null)")
        val road = idle.indexOf("Text(UNTERWEGS_BUTTON)")
        val roadHint = idle.indexOf("Text(UNTERWEGS_LABOR_HINT")
        assertTrue(report in 0 until tonal && tonal < hint && hint < road && road < roadHint, "$report $tonal $hint $road $roadHint")
        assertTrue(idle.substring(0, report).contains("onClick = onReport,") && idle.substring(0, report).contains("Modifier.fillMaxWidth().height(56.dp)"))
        // Without reports «Messung starten» stays the primary button.
        assertTrue(idle.contains("Button(onClick = onStart, enabled = startEnabled, modifier = Modifier.fillMaxWidth().height(56.dp), content = startContent)"))
        assertTrue(idle.contains("if (onUnterwegs != null) {\n                            OutlinedButton(onClick = onUnterwegs"))
    }

    @Test
    fun noLautMeldenWhileRunning() {
        // The running branch («Stoppen» …) has no report button; the report dialog still opens from a preset
        // (shortcut, widget, Labor·Karte's notification «Melden»).
        val running = screen.substring(screen.indexOf("if (live.running) {\n                        Button(\n                            onClick = onStop,"), screen.indexOf("} else {\n                        // Editions with the map"))
        assertFalse(running.contains("onReport"), running)
        assertTrue(screen.contains("preset = reportPreset, onPresetUsed = onReportPresetUsed,"))
    }

    @Test
    fun startingIsTheWindowExceptLaborKartesUnterwegsButton() {
        // «Messung starten» is the window measurement; only the Labor·Karte button starts with EXTRA_UNTERWEGS
        // (ShareController.start(true) / MeasurementService.start(unterwegs = true)) and asks for notifications.
        assertTrue(screen.contains("onStart = { onStart(unterwegs = false) },"))
        assertTrue(screen.contains("onUnterwegs = if (LoudAlerts.AVAILABLE) ({ onStart(unterwegs = true) }) else null,"))
        assertTrue(screen.contains("val road = unterwegs && LoudAlerts.AVAILABLE"))
        assertTrue(screen.contains("share != null -> share.start(road)"))
        assertTrue(screen.contains("else -> MeasurementService.start(context, unterwegs = road)"))
        assertTrue(screen.contains("if (road) askUnterwegs()"))
        // The notification permission of the measurement itself is asked with the microphone's (Android 13+).
        val common = File(app, "src/main/kotlin/ch/lessnoise/app/ui/Common.kt").readText()
        assertTrue(common.contains("if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS"))
        // Colours only from the scheme (category dots aside, Common.kt).
        assertFalse(screen.contains("Color(0x"))
        assertFalse(screen.contains("secondaryContainer"))
    }
}
