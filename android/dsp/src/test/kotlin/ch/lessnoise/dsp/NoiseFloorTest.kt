package ch.lessnoise.dsp

import ch.lessnoise.dsp.calibration.Recalibration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's noise floor («Messgrenze», 0.6.5): percentile, minimum count, flags, calibration, audio source. */
class NoiseFloorTest {
    private val default = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB

    private fun minute(
        i: Int, l90: Double, offset: Double = default, source: String = "UNPROCESSED",
        coverage: Double = 1.0, laeq: Double = l90 + 5, calId: Long? = null,
    ) = MinuteRecord(
        i * 60_000L, "m$i", 60.0, laeq, laeq + 10, l90 - 1, laeq + 8, laeq + 3, laeq, l90, 0,
        null, emptyMap(), 0, calId, offset, source, calId != null, validSeconds = 60.0 * coverage,
    )

    /** [n] minutes with raw L90 = base, base + 0.1, … (shown with the default offset). */
    private fun ramp(n: Int, baseRaw: Double, source: String = "UNPROCESSED") =
        (0 until n).map { minute(it, baseRaw + 0.1 * it + default, source = source) }

    @Test
    fun rankIsTheSecondPercentileByNearestRankAndNeverTheMinimumFrom60On() {
        assertEquals(0, NoiseFloor.rankIndex(1))
        assertEquals(0, NoiseFloor.rankIndex(50))
        assertEquals(1, NoiseFloor.rankIndex(60))
        assertEquals(1, NoiseFloor.rankIndex(100))
        assertEquals(2, NoiseFloor.rankIndex(101))
        assertEquals(19, NoiseFloor.rankIndex(1000))
        for (n in NoiseFloor.MIN_MINUTES..5000) assertTrue(NoiseFloor.rankIndex(n) >= 1, "n=$n")
    }

    @Test
    fun fewerThan60ValidMinutesGiveNoFloor() {
        assertNull(NoiseFloor.estimate("UNPROCESSED", emptyList()))
        assertNull(NoiseFloor.estimate("UNPROCESSED", ramp(59, -80.0)))
        // 60 minutes, but one of them invalid (< 50 % valid audio), one without LAeq, one without L90: still no floor.
        val withInvalid = ramp(57, -80.0) + listOf(
            minute(100, -80.0 + default, coverage = 0.4),
            minute(101, -80.0 + default, laeq = Double.NaN),
            minute(102, Double.NaN),
        )
        assertNull(NoiseFloor.estimate("UNPROCESSED", withInvalid))
        val e = assertNotNull(NoiseFloor.estimate("UNPROCESSED", ramp(60, -80.0)))
        assertEquals(60, e.minutes)
        assertEquals(-79.9, e.floorRawDb, 1e-9) // the second-lowest of 60
    }

    @Test
    fun oneGlitchMinuteFarBelowIsIgnored() {
        val normal = ramp(80, -80.0)
        val glitch = minute(999, -130.0 + default)
        val e = assertNotNull(NoiseFloor.estimate("UNPROCESSED", normal + glitch))
        assertEquals(81, e.minutes)
        // 81 values: rank index 1 = the lowest normal minute, not the glitch.
        assertEquals(-80.0, e.floorRawDb, 1e-9)
    }

    @Test
    fun estimateUsesRawLevelsSoMinutesWithDifferentOffsetsAgree() {
        // The same raw levels stored with two different calibrations give the same floor.
        val a = (0 until 70).map { minute(it, -80.0 + 0.1 * it + 121.0, offset = 121.0, calId = 3L) }
        val b = (0 until 70).map { minute(it, -80.0 + 0.1 * it + default, offset = default) }
        assertEquals(NoiseFloor.estimate("UNPROCESSED", a)!!.floorRawDb, NoiseFloor.estimate("UNPROCESSED", b)!!.floorRawDb, 1e-9)
    }

    @Test
    fun recalibrationShiftsTheShownFloorButNotTheFlags() {
        val minutes = ramp(100, -80.0) // floorRaw = -79.9
        val before = NoiseFloor.Limit(NoiseFloor.estimate("UNPROCESSED", minutes)!!, default)
        assertEquals(-79.9 + default, before.floorDb, 1e-9)
        assertEquals(-79.9 + default + 3.0, before.limitDb, 1e-9)
        val flagsBefore = minutes.map { before.atFloor(it) }
        // rawL90 ≤ -76.9 → the first 32 minutes (-80.0 … -76.9).
        assertEquals(32, flagsBefore.count { it })

        // A calibration (+9.6 dB) is saved and the old minutes are re-evaluated with it (0.3.2-style).
        val target = Recalibration.Target(7L, default + 9.6, "UNPROCESSED")
        val recalibrated = minutes.map { Recalibration.recalibrate(it, target) }
        val after = NoiseFloor.Limit(NoiseFloor.estimate("UNPROCESSED", recalibrated)!!, target.offsetDb)
        assertEquals(before.floorRawDb, after.floorRawDb, 1e-9)
        assertEquals(before.floorDb + 9.6, after.floorDb, 1e-9)
        assertEquals(flagsBefore, recalibrated.map { after.atFloor(it) })
        // Without re-evaluating, the old minutes keep their old offset: same raw values, same flags too.
        val notReevaluated = NoiseFloor.Limit(before.estimate, target.offsetDb)
        assertEquals(flagsBefore, minutes.map { notReevaluated.atFloor(it) })
        assertEquals(before.floorDb + 9.6, notReevaluated.floorDb, 1e-9)
    }

    @Test
    fun audioSourcesAreSeparate() {
        val unprocessed = ramp(70, -80.0, "UNPROCESSED")
        val voice = ramp(70, -60.0, "VOICE_RECOGNITION")
        val all = unprocessed + voice
        assertEquals(-79.9, NoiseFloor.estimate("UNPROCESSED", all)!!.floorRawDb, 1e-9)
        assertEquals(-59.9, NoiseFloor.estimate("VOICE_RECOGNITION", all)!!.floorRawDb, 1e-9)
        // 59 of one source + 70 of the other: no floor for the first.
        assertNull(NoiseFloor.estimate("UNPROCESSED", unprocessed.take(59) + voice))
        // A minute of the other source is never flagged, however quiet.
        val limit = NoiseFloor.Limit(NoiseFloor.estimate("UNPROCESSED", all)!!, default)
        assertFalse(limit.atFloor(minute(1, -100.0 + default, source = "VOICE_RECOGNITION")))
        assertTrue(limit.atFloor(minute(1, -100.0 + default, source = "UNPROCESSED")))
    }

    @Test
    fun flagBoundaryIsFloorPlusThreeInclusive() {
        val limit = NoiseFloor.Limit(NoiseFloor.Estimate("UNPROCESSED", -80.0, 100), default)
        assertTrue(limit.atFloorRaw(-77.0, "UNPROCESSED"))
        assertFalse(limit.atFloorRaw(-76.99, "UNPROCESSED"))
        assertFalse(limit.atFloorRaw(Double.NaN, "UNPROCESSED"))
        // Invalid minutes are never flagged.
        assertFalse(limit.atFloor(minute(1, -90.0 + default, coverage = 0.3)))
    }

    @Test
    fun mostlyAtFloorNeedsAtLeastHalfOfTheValidMinutes() {
        val limit = NoiseFloor.Limit(NoiseFloor.Estimate("UNPROCESSED", -80.0, 100), default)
        val quiet = { i: Int -> minute(i, -79.0 + default) }
        val loud = { i: Int -> minute(i, -60.0 + default) }
        assertTrue(limit.mostlyAtFloor(listOf(quiet(0), quiet(1), loud(2), loud(3))))
        assertFalse(limit.mostlyAtFloor(listOf(quiet(0), loud(1), loud(2))))
        // Invalid minutes do not count either way.
        assertTrue(limit.mostlyAtFloor(listOf(quiet(0), minute(1, -60.0 + default, coverage = 0.2))))
        assertFalse(limit.mostlyAtFloor(emptyList()))
    }

    @Test
    fun summaryShowsTheLimitOnlyWhenTheHumIsAtIt() {
        val limit = NoiseFloor.Limit(NoiseFloor.Estimate("UNPROCESSED", -80.0, 100), default)
        val span = MeasurementSpan(0, 10 * 60_000L)
        val quiet = (0 until 10).map { minute(it, -79.0 + default) }
        val s = MeasurementSummarizer.summarize(span, quiet, emptyList(), noiseFloor = limit)
        assertEquals(limit.limitDb, s.humLimitDb!!, 1e-9)
        assertNull(MeasurementSummarizer.summarize(span, quiet, emptyList()).humLimitDb)
        val loud = (0 until 10).map { minute(it, -60.0 + default) }
        assertNull(MeasurementSummarizer.summarize(span, loud, emptyList(), noiseFloor = limit).humLimitDb)
    }

    @Test
    fun csvAppendsAtFloorColumn() {
        val limit = NoiseFloor.Limit(NoiseFloor.Estimate("UNPROCESSED", -80.0, 100), default)
        val rows = listOf(minute(0, -79.0 + default), minute(1, -60.0 + default), minute(2, -79.0 + default, coverage = 0.2))
        val csv = Csv.minutes(rows, emptyList(), limit).trim().lines()
        val header = csv[0].split(",")
        assertEquals(listOf("local_floor_db", "wind_event_count", "at_floor"), header.takeLast(3))
        assertEquals(listOf("1", "0", "0"), csv.drop(1).map { it.split(",").last() })
        csv.drop(1).forEach { assertEquals(header.size, it.split(",").size) }
        // Without a floor: the column is there, 0 everywhere.
        assertEquals(listOf("0", "0", "0"), Csv.minutes(rows, emptyList()).trim().lines().drop(1).map { it.split(",").last() })
    }
}
