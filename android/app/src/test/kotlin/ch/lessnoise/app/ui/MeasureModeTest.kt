package ch.lessnoise.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.8.3: the mode switch «Am Fenster» | «Unterwegs» and the one primary action on «Messen» (MeasureMode.kt). */
class MeasureModeTest {
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }

    @Test
    fun idleTheSwitchShowsTheStoredChoice() {
        for (m in MeasureMode.entries) {
            // Whatever the detector said last: idle means the choice.
            for (moving in listOf(false, true)) assertEquals(ShownMode(m), shownMode(m, running = false, unterwegs = false, moving = moving))
        }
    }

    @Test
    fun runningTheSwitchShowsTheRunsMode() {
        // An «Unterwegs» run is «Unterwegs», also if the stored choice says otherwise.
        for (m in MeasureMode.entries) assertEquals(ShownMode(MeasureMode.ROAD), shownMode(m, running = true, unterwegs = true, moving = false))
        // An «Am Fenster» run at rest.
        assertEquals(ShownMode(MeasureMode.WINDOW), shownMode(MeasureMode.WINDOW, running = true, unterwegs = false, moving = false))
        // Carried: «Unterwegs erkannt»; the stored choice is a parameter only, nothing writes it.
        assertEquals(ShownMode(MeasureMode.ROAD, detected = true), shownMode(MeasureMode.WINDOW, running = true, unterwegs = false, moving = true))
        // Still again: back to «Am Fenster».
        assertEquals(ShownMode(MeasureMode.WINDOW), shownMode(MeasureMode.WINDOW, running = true, unterwegs = false, moving = false))
    }

    @Test
    fun labels() {
        val detected = ShownMode(MeasureMode.ROAD, detected = true)
        assertEquals("Am Fenster", modeLabel(MeasureMode.WINDOW, detected))
        assertEquals("Unterwegs erkannt", modeLabel(MeasureMode.ROAD, detected))
        assertEquals("Unterwegs", modeLabel(MeasureMode.ROAD, ShownMode(MeasureMode.ROAD)))
        assertEquals("Unterwegs", modeLabel(MeasureMode.ROAD, ShownMode(MeasureMode.WINDOW)))
        assertEquals("Messung starten", START_BUTTON)
        assertEquals("Stoppen", STOP_BUTTON)
        assertEquals("Zum Wechseln zuerst stoppen", MODE_LOCKED)
    }

    @Test
    fun oneLineUnderTheButton() {
        assertEquals("Am Fenster: Telefon ans offene Fenster, am Ladegerät.", idleHint(MeasureMode.WINDOW))
        assertEquals("Unterwegs: Mitteilung bei sehr lautem Verkehr. Nichts kommt auf die Karte.", idleHint(MeasureMode.ROAD))
        // Offline and Labor builds: no switch, the window line without its prefix.
        assertEquals("Telefon ans offene Fenster, am Ladegerät.", idleHint(null))
        assertEquals("Läuft seit 18:30 · Bildschirm darf aus sein.", runningLine("18:30"))
        for (t in listOf(WINDOW_HINT, UNTERWEGS_HINT, PLAIN_HINT, runningLine("18:30"), MODE_LOCKED, PLACEMENT_HINT, GUIDE_LINK)) {
            assertTrue(t.length <= 80, "one line: $t")
            assertFalse(t.contains('ß') || Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
        }
    }

    @Test
    fun theChoiceIsStoredAsTwoWordsDefaultAmFenster() {
        assertEquals(MeasureMode.WINDOW, MeasureModeStore.parse(null))
        assertEquals(MeasureMode.WINDOW, MeasureModeStore.parse("anything"))
        for (m in MeasureMode.entries) assertEquals(m, MeasureModeStore.parse(MeasureModeStore.value(m)))
    }

    @Test
    fun startingInAModeIsTheOldButtonsPath() {
        val screen = File(app, "src/main/kotlin/ch/lessnoise/app/ui/MeasureScreen.kt").readText()
        // «Unterwegs» starts with EXTRA_UNTERWEGS (ShareController.start(true) / MeasurementService.start(unterwegs = true))
        // and asks for the notification permission, exactly as the 0.6.4 button did; «Am Fenster» as «Messen».
        assertTrue(screen.contains("onStart = { onStart(unterwegs = LoudAlerts.AVAILABLE && mode == MeasureMode.ROAD) }"))
        assertTrue(screen.contains("share != null -> share.start(unterwegs)"))
        assertTrue(screen.contains("else -> MeasurementService.start(context, unterwegs = unterwegs && LoudAlerts.AVAILABLE)"))
        assertTrue(screen.contains("if (unterwegs) askUnterwegs()"))
        // The switch exists only where «Unterwegs» does.
        assertTrue(screen.contains("mode = if (LoudAlerts.AVAILABLE) mode else null"))
        // The detector never writes the choice: only a tap on a segment does.
        assertEquals(1, Regex("""MeasureModeStore\.save\(""").findAll(screen).count())
        // The beige/pink card is gone; colours only from the scheme (category dots aside, Common.kt).
        assertFalse(screen.contains("ShareStatusLine"))
        assertFalse(screen.contains("Color(0x"))
        assertFalse(screen.contains("secondaryContainer"))
    }
}
