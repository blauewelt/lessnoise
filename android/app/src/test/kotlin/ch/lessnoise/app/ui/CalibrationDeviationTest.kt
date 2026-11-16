package ch.lessnoise.app.ui

import ch.lessnoise.app.data.CalibrationEntity
import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.calibration.CalibrationMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.9.5.1: «Kalibrieren» shows the deviation from the standard; «Wirklich speichern?» above 20 dB. */
class CalibrationDeviationTest {
    private val std = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB

    @Test
    fun signedFormatting() {
        assertEquals("+12,3", CalibrationDeviation.signed(12.3))
        assertEquals("−3,0", CalibrationDeviation.signed(-3.0))
        assertEquals("0,0", CalibrationDeviation.signed(0.0))
        assertEquals("0,0", CalibrationDeviation.signed(-0.04)) // no «−0,0»
        assertEquals("0,0", CalibrationDeviation.signed(0.049))
        assertEquals("+0,1", CalibrationDeviation.signed(0.05))
        assertEquals("+20,1", CalibrationDeviation.signed(20.07))
        assertEquals("−125,0", CalibrationDeviation.signed(-124.96))
    }

    @Test
    fun shownForAStoredOffset() {
        assertEquals("0,0 dB", CalibrationDeviation.ofOffset(std))
        assertEquals("+12,3 dB", CalibrationDeviation.ofOffset(std + 12.3))
        assertEquals("−3,0 dB", CalibrationDeviation.ofOffset(std - 3.0))
        assertEquals("Abweichung +12,3 dB", CalibrationDeviation.historyPart(std + 12.3))
        assertEquals("Nicht kalibriert (Standardwert).", CalibrationDeviation.activeLine(false, 0L, CalibrationEntity.METHOD_RESET, std))
        val line = CalibrationDeviation.activeLine(true, 0L, CalibrationEntity.METHOD_REFERENCE, std + 20.07)
        assertTrue(line.startsWith("Kalibriert am "), line)
        assertTrue(line.endsWith(": Abweichung vom Standard +20,1 dB (Messgerät)"), line)
        assertFalse(line.contains("132") || line.contains("112"), line)
    }

    @Test
    fun manualEntryRoundTrip() {
        for (typed in listOf("+12,3", "12.3", " 12,3 ", "12,3 dB")) {
            assertEquals(std + 12.3, CalibrationDeviation.manualOffset(typed)!!, 1e-9, typed)
        }
        assertEquals(std - 3.0, CalibrationDeviation.manualOffset("−3,0")!!, 1e-9) // minus sign
        assertEquals(std - 3.5, CalibrationDeviation.manualOffset("-3,5")!!, 1e-9)
        assertEquals(std, CalibrationDeviation.manualOffset("0")!!, 1e-9)
        assertNull(CalibrationDeviation.manualOffset(""))
        assertNull(CalibrationDeviation.manualOffset("abc"))
        assertNull(CalibrationDeviation.manualOffset("NaN"))
        // Prefill: the active calibration's deviation, which typed back gives the same offset (to 0,05 dB).
        assertEquals("", CalibrationDeviation.manualPrefill(false, std + 5))
        assertEquals("", CalibrationDeviation.manualPrefill(true, null))
        val stored = std + 7.84
        val prefill = CalibrationDeviation.manualPrefill(true, stored)
        assertEquals("+7,8", prefill)
        assertEquals(stored, CalibrationDeviation.manualOffset(prefill)!!, 0.05)
        assertEquals(prefill, CalibrationDeviation.signed(CalibrationMath.deviationFromDefault(CalibrationDeviation.manualOffset(prefill)!!)))
    }

    @Test
    fun confirmOnlyAbove20Db() {
        assertFalse(CalibrationDeviation.needsConfirm(std))
        assertFalse(CalibrationDeviation.needsConfirm(std + 20.0))
        assertFalse(CalibrationDeviation.needsConfirm(std - 20.0))
        assertTrue(CalibrationDeviation.needsConfirm(std + 20.1))
        assertTrue(CalibrationDeviation.needsConfirm(std - 20.1))
        assertTrue(CalibrationDeviation.needsConfirm(132.42 + 0.5)) // well above
    }

    @Test
    fun within20DbSavesAtOnceWithoutDialog() {
        val c = SaveConfirmation()
        var saves = 0
        c.request(std + 19.9, CalibrationEntity.METHOD_REFERENCE) { saves++ }
        c.request(std - 20.0, CalibrationEntity.METHOD_MANUAL) { saves++ }
        assertEquals(2, saves)
        assertNull(c.pending)
    }

    @Test
    fun above20DbWaitsForConfirm() {
        val c = SaveConfirmation()
        var saved = 0
        c.request(std + 20.1, CalibrationEntity.METHOD_CALIBRATOR) { saved++ }
        assertEquals(0, saved)
        assertEquals(std + 20.1, c.pending!!.offsetDb, 1e-12)
        c.confirm()
        assertEquals(1, saved)
        assertNull(c.pending)
        c.confirm() // nothing pending: nothing more
        assertEquals(1, saved)
    }

    @Test
    fun cancelledConfirmSavesNothing() {
        val c = SaveConfirmation()
        var saved = 0
        c.request(std - 25.0, CalibrationEntity.METHOD_MANUAL) { saved++ }
        c.cancel()
        assertNull(c.pending)
        c.confirm()
        assertEquals(0, saved)
    }

    @Test
    fun confirmTexts() {
        assertEquals("Wirklich speichern?", CalibrationDeviation.CONFIRM_TITLE)
        assertEquals(
            "Abweichung vom Standard +20,1 dB – mehr als 20 dB, vermutlich ein Fehler (Messgerät auf A-Bewertung und Mittelwert über 30 s?).",
            CalibrationDeviation.confirmText(std + 20.1, CalibrationEntity.METHOD_REFERENCE),
        )
        assertTrue(CalibrationDeviation.confirmText(std - 22.0, CalibrationEntity.METHOD_CALIBRATOR).startsWith("Abweichung vom Standard −22,0 dB – mehr als 20 dB"))
        assertTrue(CalibrationDeviation.confirmText(std + 30.0, CalibrationEntity.METHOD_MANUAL).contains("Abweichung eingetragen"))
        assertEquals("Abweichung vom Standard über 20 dB – vermutlich ein Fehler.", warningText(ch.lessnoise.dsp.calibration.CalibrationWarning.IMPLAUSIBLE_OFFSET))
    }
}
