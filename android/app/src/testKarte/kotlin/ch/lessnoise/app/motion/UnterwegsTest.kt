package ch.lessnoise.app.motion

import ch.lessnoise.app.motion.UnterwegsCombiner.Change
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.9: «unterwegs» = motion OR position, and the Labor·Karte readout/minute numbers (src/testKarte). */
class UnterwegsTest {
    @Test
    fun motionOnlyGivesExactlyThe067Spans() {
        // No location permission (or the Online-Version): only the motion signal ever reports.
        val c = UnterwegsCombiner()
        assertEquals(Change.Begin(1_000), c.onMotion(true, 25_000, 1_000))
        assertTrue(c.moving)
        assertEquals(Change.End(400_000), c.onMotion(false, 400_000, 400_000))
        assertFalse(c.moving)
        assertEquals(Change.Begin(500_000), c.onMotion(true, 520_000, 500_000))
    }

    @Test
    fun eitherSignalMakesTheRunUnterwegsAndTheLastOneEndsIt() {
        val c = UnterwegsCombiner()
        // Position first (a smooth train the accelerometer misses); its span starts at the last near fix.
        assertEquals(Change.Begin(100_000), c.onPosition(true, 280_000, 100_000))
        // Motion joins later: the span is already open from earlier, nothing to write.
        assertNull(c.onMotion(true, 300_000, 290_000))
        // Motion says still (the train stands): position still says away → stays unterwegs.
        assertNull(c.onMotion(false, 600_000, 600_000))
        assertTrue(c.moving)
        // Position comes back to the start: now both still → the span ends.
        assertEquals(Change.End(900_000), c.onPosition(false, 900_000, 900_000))
        assertFalse(c.moving)
    }

    @Test
    fun aLaterSignalWithAnEarlierStartMovesTheSpanStartEarlier() {
        val c = UnterwegsCombiner()
        assertEquals(Change.Begin(200_000), c.onMotion(true, 225_000, 200_000))
        // The position decides minutes later that the phone had left already at 120 s.
        assertEquals(Change.Begin(120_000), c.onPosition(true, 400_000, 120_000))
        // A later start never moves it later.
        assertNull(c.onMotion(true, 460_000, 440_000))
        assertTrue(c.moving)
    }

    @Test
    fun minuteSummaryAndReadout() {
        val m = MinuteSummary()
        val t0 = 1_760_000_040_000L - (1_760_000_040_000L % 60_000L)
        for (i in 0 until 60) assertNull(m.add(t0 + i * 1000L, if (i == 10) Double.NaN else 0.01 * (i % 7)))
        val out = m.add(t0 + 60_000L, 0.2)!!
        assertEquals(t0, out.startMs)
        assertEquals(60, out.seconds)
        assertEquals(0.06, out.maxMs2!!, 1e-9)
        assertEquals(0.03, out.medianMs2!!, 1e-9)
        assertEquals(MinuteSummary.Minute(t0 + 60_000L, 0.2, 0.2, 1), m.flush())
        assertNull(m.flush())

        assertEquals(400, Distance.rounded(420.0))
        assertEquals(450, Distance.rounded(425.0))
        assertEquals(0, Distance.rounded(20.0))
        assertNull(Distance.rounded(null))
        assertEquals("Bewegung 0,12 m/s² · 400 m · unterwegs", Readout.line(0.123, 420.0, true))
        assertEquals("Bewegung 0,01 m/s² · – · ruhig", Readout.line(0.01, null, false))
        assertEquals("Bewegung – m/s² · – · ruhig", Readout.line(null, null, false))
        val r = RecentMotion(3)
        listOf(0.1, Double.NaN, 0.3, 0.2, 0.5).forEach { r.add(it) }
        assertEquals(0.3, r.medianMs2)
    }
}
