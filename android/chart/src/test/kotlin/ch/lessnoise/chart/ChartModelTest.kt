package ch.lessnoise.chart

import ch.lessnoise.dsp.EventFeatures
import ch.lessnoise.dsp.EventShape
import ch.lessnoise.dsp.Iso
import ch.lessnoise.dsp.WindRule
import ch.lessnoise.dsp.MinuteRecord
import ch.lessnoise.dsp.MeasurementSpan
import ch.lessnoise.dsp.MeasurementSummarizer
import ch.lessnoise.dsp.NoiseEvent

import java.time.LocalDateTime
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChartModelTest {
    private val zone = SyntheticData.zone
    private val win = Windows(zone)
    /** A measurement Friday 22:00 → Saturday 06:00. */
    private val night = TimeWindow.span(SyntheticData.ms(LocalDateTime.of(2026, 10, 9, 22, 0)), SyntheticData.ms(LocalDateTime.of(2026, 10, 10, 6, 0)))
    private val weekWindow = TimeWindow.span(SyntheticData.ms(LocalDateTime.of(2026, 10, 5, 0, 0)), SyntheticData.ms(LocalDateTime.of(2026, 10, 12, 0, 0)))
    private fun spanOf(w: TimeWindow) = MeasurementSpan(w.startMs, w.endMs)
    private val after = night.endMs + 3 * 3_600_000L

    private fun minute(t: Long, laeq: Double, l10: Double = laeq + 3, l90: Double = laeq - 3, coverage: Double = 1.0, calibrated: Boolean = true) =
        MinuteRecord(
            t, Iso.format(t, zone), 60.0, laeq, laeq + 10, l90 - 2, laeq + 8, l10, laeq, l90, 0, null, emptyMap(), 0, null, 112.35,
            "UNPROCESSED", calibrated, validSeconds = 60.0 * coverage,
        )

    private fun event(t: Long, max: Double, cat: String = "road_traffic") = NoiseEvent(
        t, Iso.format(t, zone, true), 2.0, max, max, 40.0, 10.0, cat, 0.5f, emptyList(), 1, null, "UNPROCESSED", true,
    )

    private fun windEvent(t: Long, max: Double) = event(t, max, WindRule.CATEGORY).copy(
        thresholdDb = 6.5, features = EventFeatures(38.0, max - 38.0, 0.2, 0.5, 0.4, 3.0, 0.98, 2.5, true, EventShape.IMPULSE),
    )

    private fun at(h: Int, m: Int) = SyntheticData.ms(if (h >= 12) LocalDateTime.of(2026, 10, 9, h, m) else LocalDateTime.of(2026, 10, 10, h, m))
    private fun minutes(fromH: Int, fromM: Int, count: Int, laeq: (Int) -> Double = { 40.0 }) =
        (0 until count).map { i -> minute(at(fromH, fromM) + i * 60_000L, laeq(i)) }

    private fun build(ms: List<MinuteRecord>, evs: List<NoiseEvent> = emptyList(), w: TimeWindow = night, highlight: String = "loud_vehicle", floor: Double = 45.0, now: Long = after) =
        ChartModel.build(ChartData(w, ms, evs, now), zone, highlight, floor)

    // ---- The phone's noise floor («Messgrenze», 0.6.5) -----------------------------------------

    /** floorRaw such that the shown floor (offset 112.35, the minutes' offset) is [shownFloor]. */
    private fun floorLimit(shownFloor: Double, activeOffset: Double = 112.35) = ch.lessnoise.dsp.NoiseFloor.Limit(
        ch.lessnoise.dsp.NoiseFloor.Estimate("UNPROCESSED", shownFloor - 112.35, 500), activeOffset,
    )

    @Test
    fun noFloorMeansNoBandAndNoLimit() {
        val m = build(minutes(22, 0, 30) { 35.0 })
        assertEquals(null, m.floorBandTopDb)
        assertEquals(null, m.summary.humLimitDb)
        assertEquals("32,0 dB(A)", m.summary.humText)
    }

    @Test
    fun humAtTheFloorShowsLessOrEqualAndTheBand() {
        // L90 = 32 everywhere; shown floor 30 → limit 33: every minute is at the floor.
        val ms = minutes(22, 0, 30) { 35.0 }
        val m = ChartModel.build(ChartData(night, ms, emptyList(), after), zone, "loud_vehicle", 45.0, noiseFloor = floorLimit(30.0))
        assertEquals(33.0, m.summary.humLimitDb!!, 1e-9)
        assertEquals("≤ 33,0 dB(A)", m.summary.humText)
        // y range: floor5(32 − 5) = 25 → the band 25…33 is inside.
        assertEquals(25.0, m.yRange.lo)
        assertEquals(33.0, m.floorBandTopDb!!, 1e-9)
        // Same y range and data as without a floor.
        val plain = build(ms)
        assertEquals(plain.yRange, m.yRange)
        assertEquals(plain.points, m.points)
    }

    @Test
    fun loudMeasurementKeepsItsHumButMayShowTheBand() {
        // L90 = 47; limit 33 is below the y range's bottom (40): no band, no «≤».
        val ms = minutes(22, 0, 30) { 50.0 }
        val m = ChartModel.build(ChartData(night, ms, emptyList(), after), zone, "loud_vehicle", 45.0, noiseFloor = floorLimit(30.0))
        assertEquals(null, m.summary.humLimitDb)
        assertEquals("47,0 dB(A)", m.summary.humText)
        assertEquals(40.0, m.yRange.lo)
        assertEquals(null, m.floorBandTopDb)
        // Limit above the bottom but the hum above the limit: band yes, «≤» no.
        val m2 = ChartModel.build(ChartData(night, ms, emptyList(), after), zone, "loud_vehicle", 45.0, noiseFloor = floorLimit(40.0))
        assertEquals(43.0, m2.floorBandTopDb!!, 1e-9)
        assertEquals(null, m2.summary.humLimitDb)
    }

    @Test
    fun floorBandTopIsClampedAndNeedsToBeAboveTheBottom() {
        assertEquals(null, ChartModel.floorBandTop(30.0, YRange(30.0, 60.0)))
        assertEquals(30.5, ChartModel.floorBandTop(30.5, YRange(30.0, 60.0)))
        assertEquals(60.0, ChartModel.floorBandTop(70.0, YRange(30.0, 60.0)))
        assertEquals(null, ChartModel.floorBandTop(Double.NaN, YRange(30.0, 60.0)))
    }

    @Test
    fun calibrationMovesTheBandWithTheShownFloor() {
        // Minutes stored with offset 112.35; the active calibration is now 9.6 dB higher (not re-evaluated):
        // the shown floor moves up by 9.6, the flags (raw comparison) stay.
        val ms = minutes(22, 0, 30) { 35.0 }
        val before = floorLimit(30.0)
        val after9 = floorLimit(30.0, activeOffset = 112.35 + 9.6)
        val a = ChartModel.build(ChartData(night, ms, emptyList(), after), zone, "loud_vehicle", 45.0, noiseFloor = before)
        val b = ChartModel.build(ChartData(night, ms, emptyList(), after), zone, "loud_vehicle", 45.0, noiseFloor = after9)
        assertEquals(a.summary.humLimitDb!! + 9.6, b.summary.humLimitDb!!, 1e-9)
        assertTrue(ms.all { before.atFloor(it) == after9.atFloor(it) })
    }

    // ---- y range ---------------------------------------------------------------------------

    @Test
    fun yRangeFollowsTheRule() {
        // L90 min 37 → floor5(32) = 30; top = max(LAeq 45, L10 48, event 77) + 5 = 82 → 85.
        val m = build(minutes(22, 0, 30) { 40.0 + it % 6 }, listOf(event(at(22, 10), 77.0)))
        assertEquals(YRange(30.0, 85.0), m.yRange)
        assertEquals(listOf(30.0, 40.0, 50.0, 60.0, 70.0, 80.0), m.yRange.gridlines())
    }

    @Test
    fun yRangeIsAtLeast30dBAndClamped() {
        val quiet = build(minutes(22, 0, 10) { 40.0 })
        // 37 − 5 = 32 → 30; 43 + 5 = 48 → 50; widened to 30 dB.
        assertEquals(YRange(30.0, 60.0), quiet.yRange)
        val veryQuiet = ChartModel.yRange(listOf(SeriesPoint(0, 60_000, 5.0, 6.0, 2.0, 10.0, true, true, 60.0)), emptyList())
        assertEquals(YRange(0.0, 30.0), veryQuiet)
        val veryLoud = ChartModel.yRange(listOf(SeriesPoint(0, 60_000, 110.0, 112.0, 105.0, 118.0, true, true, 60.0)), listOf(event(0, 130.0)))
        assertEquals(YRange(90.0, 120.0), veryLoud)
    }

    @Test
    fun yRangeUsesOnlyDrawnEvents() {
        // A 90 dB event below… no: an event below the floor is not drawn and must not stretch the axis.
        val ms = minutes(22, 0, 30) { 30.0 }
        val m = build(ms, listOf(event(at(22, 5), 44.0)), floor = 45.0)
        assertEquals(0, m.otherEvents.size)
        assertEquals(YRange(20.0, 50.0), m.yRange)
    }

    // ---- gaps --------------------------------------------------------------------------------

    @Test
    fun lineBreaksAtEveryMissingOrInvalidMinute() {
        val ms = minutes(22, 0, 10).toMutableList()
        ms.removeAt(4) // one missing minute
        ms[7] = ms[7].copy(validSeconds = 20.0) // coverage 0.33 < 0.5
        val m = build(ms)
        // 22:00–22:03 | (22:04 missing) 22:05–22:07 | (22:08 invalid) 22:09
        assertEquals(listOf(4, 3, 1), m.segments.map { it.size })
        // Never interpolated: no segment spans the missing minute.
        for (seg in m.segments) seg.zipWithNext { a, b -> assertTrue(b.startMs - a.endMs <= 30_000) }
        // Short breaks (< 10 min) are not "gaps" with a rectangle.
        assertTrue(m.gaps.none { it.span.startMs < at(22, 10) })
    }

    @Test
    fun minutesWithNaNLevelsAreGaps() {
        val ms = minutes(22, 0, 5).toMutableList()
        ms[2] = ms[2].copy(laeqDb = Double.NaN, validSeconds = 0.0)
        assertEquals(listOf(2, 2), build(ms).segments.map { it.size })
    }

    @Test
    fun gapSpansAreAtLeastTenMinutesAndClipToNow() {
        val ms = minutes(22, 0, 60) + minutes(23, 9, 51) /* 9 min hole */ + minutes(0, 25, 60) /* 25 min hole */
        val m = build(ms, now = at(1, 40))
        val spans = m.gaps.map { it.span }
        // Holes: 23:00–23:09 (9 min, no rectangle), 00:00–00:25, 01:25–now (01:40 → 15 min, at data end).
        assertEquals(listOf(Span(at(0, 0), at(0, 25)), Span(at(1, 25), at(1, 40))), spans)
        assertTrue(m.gaps.last().atDataEnd)
        // The time after "now" is not a gap.
        assertTrue(spans.all { it.endMs <= at(1, 40) })
    }

    @Test
    fun gapTextsTellInterruptionsFromStartAndEnd() {
        // Measurement 22–06, data 23:54–05:00, earlier data until 20:55, nothing after. Over two dates: with weekday.
        val ms = (0 until (5 * 60 + 6)).map { i -> minute(at(23, 54) + i * 60_000L, 30.0) }
        val prev = SyntheticData.ms(LocalDateTime.of(2026, 10, 9, 20, 55))
        val m = ChartModel.build(ChartData(night, ms, emptyList(), after, prevValidEndMs = prev, nextValidStartMs = null), zone, "loud_vehicle", 45.0)
        assertEquals(listOf("unterbrochen Fr 20:55–Fr 23:54", "Messung bis Sa 05:00"), m.summary.gapTexts)
        val first = ChartModel.build(ChartData(night, ms, emptyList(), after), zone, "loud_vehicle", 45.0)
        assertEquals("Messung ab Fr 23:54", first.summary.gapTexts.first())
    }

    @Test
    fun gapLabelRule() {
        assertEquals(GapLabel.ONE_LINE, GapLabel.choose(100f, 80f, 45f, 8f))
        assertEquals(GapLabel.TWO_LINES, GapLabel.choose(60f, 80f, 45f, 8f))
        assertEquals(GapLabel.NONE, GapLabel.choose(50f, 80f, 45f, 8f))
    }

    // ---- events --------------------------------------------------------------------------------

    @Test
    fun eventFloorFiltersStoredEventsAtReadTime() {
        val evs = listOf(event(at(22, 1), 38.0), event(at(22, 2), 45.0), event(at(22, 3), 70.0, "loud_vehicle"))
        val m = build(minutes(22, 0, 10), evs, floor = 45.0)
        assertEquals(1, m.otherEvents.size)
        assertEquals(1, m.highlightedEvents.size)
        assertEquals(2, m.summary.eventCount)
        assertEquals(1, m.summary.highlightCount)
        // Same count as the measurement list for the same floor.
        val n = MeasurementSummarizer.summarize(spanOf(night), minutes(22, 0, 10), evs, eventMinLevelDb = 45.0)
        assertEquals(n.eventCount, m.summary.eventCount)
    }

    @Test
    fun highlightSplitsEvents() {
        val evs = listOf(event(at(22, 1), 60.0, "voices"), event(at(22, 2), 61.0, "loud_vehicle"), event(at(22, 3), 62.0, "voices"))
        val m = build(minutes(22, 0, 10), evs, highlight = "voices")
        assertEquals(2, m.highlightedEvents.size)
        assertEquals(listOf("loud_vehicle"), m.otherEvents.map { it.dominantCategory })
    }

    @Test
    fun longMeasurementsDrawOnlyLoudHighlightedEventsCappedAt300() {
        val d = SyntheticData.weekData()
        val w = d.window
        val many = (0 until 400).map { i -> event(w.startMs + i * 1_200_000L, 61.0 + (i % 20), "loud_vehicle") } +
            (0 until 50).map { i -> event(w.startMs + i * 3_600_000L + 600_000, 55.0, "loud_vehicle") } + // below 60
            (0 until 30).map { i -> event(w.startMs + i * 3_600_000L + 900_000, 80.0, "voices") } // not highlighted
        val m = ChartModel.build(d.copy(events = many), zone, "loud_vehicle", 45.0)
        assertTrue(m.hourly)
        assertTrue(m.otherEvents.isEmpty()) // hourly views draw no grey dots
        assertEquals(300, m.highlightedEvents.size)
        assertTrue(m.eventsCapped)
        assertTrue(m.highlightedEvents.all { it.lafMaxDb >= 66.0 }) // the loudest 300
        assertEquals(480, m.summary.eventCount) // the summary counts all events ≥ the floor
        assertEquals(450, m.summary.highlightCount)
        val few = ChartModel.build(d.copy(events = many.take(10)), zone, "loud_vehicle", 45.0)
        assertTrue(!few.eventsCapped)
        // Highlighting voices draws only those.
        val voices = ChartModel.build(d.copy(events = many), zone, "voices", 45.0)
        assertEquals(30, voices.highlightedEvents.size)
        assertTrue(voices.otherEvents.isEmpty())
        // A floor above 60 dB raises the hourly threshold too.
        val high = ChartModel.build(d.copy(events = many), zone, "loud_vehicle", 75.0)
        assertTrue(high.highlightedEvents.all { it.lafMaxDb >= 75.0 })
        // Measurements up to 36 h still draw all events, minute by minute.
        val short = ChartModel.build(SyntheticData.fridayNight(), zone, "loud_vehicle", 45.0)
        assertTrue(!short.hourly)
        assertEquals(13, short.otherEvents.size)
        assertTrue(!ChartModel.build(SyntheticData.dayData(), zone, "loud_vehicle", 45.0).hourly)
    }

    // ---- hit testing -----------------------------------------------------------------------------

    @Test
    fun tapNearAnEventPrefersTheEvent() {
        val ms = minutes(22, 0, 60) { 40.0 }
        val e = event(at(22, 30), 70.0, "loud_vehicle")
        val m = build(ms, listOf(e))
        val g = ChartGeometry(m, PlotRect(40f, 10f, 1040f, 610f))
        val ex = g.xOf(e.startEpochMs)
        val ey = g.yOf(70.0)
        val hit = g.hitTest(ex + 20f, ey + 20f, eventRadiusPx = 42f) // 16 dp at 2.625
        assertIs<Selection.Event>(hit)
        assertEquals(e, hit.event)
        // Farther away: the nearest minute.
        val miss = g.hitTest(ex + 20f, g.yOf(40.0), eventRadiusPx = 42f)
        assertIs<Selection.Point>(miss)
        val t = g.timeAt(ex + 20f)
        assertTrue(t in miss.point.startMs until miss.point.endMs)
        // Outside the plot: nothing.
        assertEquals(null, g.hitTest(5f, 5f, 42f))
    }

    @Test
    fun highlightedEventWinsATie() {
        val t = at(22, 30)
        val a = event(t, 70.0, "road_traffic")
        val b = event(t, 70.0, "loud_vehicle")
        val g = ChartGeometry(build(minutes(22, 0, 60), listOf(a, b)), PlotRect(40f, 10f, 1040f, 610f))
        val hit = g.hitTest(g.xOf(t), g.yOf(70.0), 42f)
        assertIs<Selection.Event>(hit)
        assertEquals("loud_vehicle", hit.event.dominantCategory)
        assertTrue(hit.highlighted)
    }

    @Test
    fun tapInAGapSaysNoData() {
        val ms = minutes(22, 0, 30) + minutes(1, 0, 30)
        val g = ChartGeometry(build(ms), PlotRect(0f, 0f, 960f, 600f))
        assertIs<Selection.NoData>(g.hitTest(g.xOf(at(23, 30)), 300f, 42f))
    }

    @Test
    fun geometryPutsEventsInsideThePlotAndBandsTheRightWayUp() {
        val d = SyntheticData.fridayNight()
        val m = ChartModel.build(d, zone, "loud_vehicle", 45.0)
        val p = PlotRect(60f, 20f, 1000f, 600f)
        val g = ChartGeometry(m, p)
        for (dot in g.otherDots + g.highlightDots) assertTrue(p.contains(dot.x, dot.y), "dot off plot: $dot")
        for (band in g.bands) {
            val n = band.size / 4
            for (i in 0 until n) {
                val upper = band[2 * i + 1]
                val lower = band[band.size - 2 * i - 1]
                assertTrue(upper <= lower + 0.01f, "band inverted") // smaller y = higher level
            }
        }
        // The 25-minute gap at 03:10 is a gap and splits the line.
        assertTrue(m.gaps.any { it.span.startMs == SyntheticData.ms(SyntheticData.fridayGap.first) && it.span.endMs == SyntheticData.ms(SyntheticData.fridayGap.second) })
        assertEquals(2, m.segments.size)
        assertEquals(18, m.summary.eventCount)
        assertEquals(5, m.summary.highlightCount)
    }

    // ---- hourly aggregation (measurements longer than 36 h) --------------------------------------

    @Test
    fun weeklyHourAggregation() {
        val w = weekWindow
        val h0 = w.startMs
        // Hour 0: 40 valid minutes, 20 at 40 dB and 20 at 50 dB, plus 20 invalid ones (ignored).
        val ms = (0 until 20).map { minute(h0 + it * 60_000L, 40.0, l10 = 42.0 + it % 3, l90 = 36.0 + it % 2) } +
            (20 until 40).map { minute(h0 + it * 60_000L, 50.0, l10 = 55.0, l90 = 45.0) } +
            (40 until 60).map { minute(h0 + it * 60_000L, 90.0, coverage = 0.2) } +
            // Hour 1: only 29 valid minutes → gap.
            (60 until 89).map { minute(h0 + it * 60_000L, 45.0) }
        val pts = ChartModel.hourlyPoints(w, ms, nowMs = w.startMs + 3 * 3_600_000L)
        val p0 = pts[0]
        assertTrue(p0.valid)
        assertEquals(10 * log10((10.0.pow(4) * 20 + 10.0.pow(5) * 20) / 40), p0.laeq, 1e-9)
        assertEquals(55.0, p0.l10) // max of minute L10
        assertEquals(36.0, p0.l90) // min of minute L90
        assertEquals(40 * 60.0, p0.validSeconds)
        assertTrue(!pts[1].valid)
        // Hours up to now only (plus none after).
        assertEquals(4, pts.size)
    }

    @Test
    fun weeklyEnergyMeanIsCoverageWeighted() {
        val w = weekWindow
        val ms = (0 until 30).map { minute(w.startMs + it * 60_000L, 40.0) } +
            (30 until 60).map { minute(w.startMs + it * 60_000L, 60.0, coverage = 0.5) }
        val p = ChartModel.hourlyPoints(w, ms, w.endMs)[0]
        assertEquals(10 * log10((1e4 * 60 * 30 + 1e6 * 30 * 30) / (60.0 * 30 + 30.0 * 30)), p.laeq, 1e-9)
    }

    @Test
    fun summaryLaeqMatchesMeasurementSummary() {
        val d = SyntheticData.fridayNight()
        val m = ChartModel.build(d, zone, "loud_vehicle", 45.0)
        val n = MeasurementSummarizer.summarize(spanOf(d.window), d.minutes, d.events, eventMinLevelDb = 45.0)
        assertEquals(n.laeqDb, m.summary.laeqDb, 1e-9)
        assertEquals(n.coverage, m.summary.coverage, 1e-9)
        assertNotNull(m.summary.loudest)
        assertEquals(85.0, m.summary.loudest!!.lafMaxDb)
    }

    @Test
    fun tooltipTexts() {
        val d = SyntheticData.fridayNight()
        val m = ChartModel.build(d, zone, "loud_vehicle", 45.0)
        val e = d.events.maxBy { it.lafMaxDb }
        val lines = tooltipLines(Selection.Event(e, true), m)
        // Over midnight: weekday and date first.
        assertEquals("Fr 9.10. 23:40:30 · Lautes Fahrzeug", lines[0])
        // Two lines: time and source, then peak and duration (0.6.1).
        assertEquals(2, lines.size, lines.toString())
        assertTrue(lines[1].startsWith("85,0 dB(A) · "), lines.toString())
        // The Labor build keeps the detector details.
        val detailed = tooltipLines(Selection.Event(e, true), m, detailed = true)
        assertTrue("LAFmax 85,0 dB(A)" in detailed && "unkalibriert" in detailed, detailed.toString())
        val minute = tooltipLines(Selection.Point(m.points.first { it.valid }), m)
        assertEquals(2, minute.size, minute.toString())
        assertTrue(minute[1].startsWith("Mittel ") && minute[1].contains(" · Spitze "), minute.toString())
        assertEquals(listOf("Sa 10.10. 03:20", "keine Messung"), tooltipLines(Selection.NoData(SyntheticData.ms(LocalDateTime.of(2026, 10, 10, 3, 20))), m))
        // Within one date: the time is enough.
        val evening = ChartModel.build(d.copy(window = TimeWindow.span(SyntheticData.ms(LocalDateTime.of(2026, 10, 9, 22, 0)), SyntheticData.ms(LocalDateTime.of(2026, 10, 9, 23, 59)))), zone, "loud_vehicle", 45.0)
        assertEquals("23:40:30 · Lautes Fahrzeug", tooltipLines(Selection.Event(e, true), evening)[0])
        val weekModel = ChartModel.build(SyntheticData.weekData(), zone, "loud_vehicle", 45.0)
        val p = weekModel.points.first { it.valid }
        assertEquals("Mo 5.10. 00:00–01:00", tooltipLines(Selection.Point(p), weekModel)[0])
    }

    // ---- dynamics -------------------------------------------------------------------------------

    @Test
    fun summaryLineWithEventsPerHourAndDynamics() {
        // 60 valid minutes with L10 − L90 = 6 dB (minute() helper: l10 = laeq + 3, l90 = laeq − 3).
        val ms = minutes(22, 0, 60) { 40.0 }
        val evs = (0 until 48).map { event(at(22, 0) + it * 75_000L, 50.0) } + event(at(22, 30), 25.0) // last one below floor
        val m = build(ms, evs, floor = 30.0)
        assertEquals(48.0, m.summary.eventsPerHour, 1e-9)
        assertEquals(6.0, m.summary.dynamicsDb, 1e-9)
        // Measurement 22–06 with data only 22–23: coverage 12.5 %. The gaps and the dynamics are no
        // longer in the line (0.6.1), but still computed.
        assertEquals("Mittel 40,0 dB(A) · 48 Lärmspitzen · 13 % gemessen", m.summary.line())
        // 0.9.3: «Lautes Fahrzeug» (renamed; the id stays); after a count the plural / singular.
        assertEquals("Mittel 40,0 dB(A) · 48 Lärmspitzen, davon 0 laute Fahrzeuge · 13 % gemessen", m.summary.line(highlightName = ChartCategories.countName("loud_vehicle", m.summary.highlightCount)))
        assertEquals("laute Fahrzeuge", ChartCategories.countName("loud_vehicle", 5))
        assertEquals("lautes Fahrzeug", ChartCategories.countName("loud_vehicle", 1))
        assertEquals("Strassenverkehr", ChartCategories.countName("road_traffic", 5))
        assertEquals("Lautes Fahrzeug", ChartCategories.name("loud_vehicle"))
        assertEquals("Laute Fahrzeuge", ChartCategories.legendName("loud_vehicle"))
        assertEquals("Tram & Bahn", ChartCategories.legendName("rail_tram"))
        assertEquals(listOf("Messung bis Fr 23:00"), m.summary.gapTexts)
        assertEquals("48", m.summary.eventsPerHourText)
        val few = build(ms, evs.take(5), floor = 30.0)
        assertEquals("5,0", few.summary.eventsPerHourText)
        assertTrue(few.summary.line().contains("5 Lärmspitzen"), few.summary.line())
        assertTrue(build(ms, evs.take(1), floor = 30.0).summary.line().contains("1 Lärmspitze ·"))
    }

    // ---- wind (detector v2) ---------------------------------------------------------------------

    @Test
    fun windEventsAreDrawnApartAndCountedNowhere() {
        val evs = listOf(
            event(at(22, 1), 60.0, "voices"), event(at(22, 2), 61.0, "loud_vehicle"),
            windEvent(at(22, 3), 90.0), windEvent(at(22, 4), 50.0), windEvent(at(22, 5), 30.0), // the last one below the floor
        )
        val ms = minutes(22, 0, 60) { 40.0 }
        val m = build(ms, evs)
        assertEquals(2, m.summary.eventCount)
        assertEquals(1, m.summary.highlightCount)
        assertEquals(2, m.summary.windCount)
        assertEquals(61.0, m.summary.loudest!!.lafMaxDb) // not the 90 dB gust
        assertEquals(2.0, m.summary.eventsPerHour, 1e-9)
        assertEquals(listOf(90.0, 50.0), m.windEvents.map { it.lafMaxDb })
        assertTrue((m.otherEvents + m.highlightedEvents).none { it.wind })
        assertEquals(37.0, m.summary.humDb) // the minutes' L90 (laeq − 3)
        // Shown: the y axis makes room for them; hidden: neither drawn nor in the range.
        assertEquals(95.0, m.yRange.hi)
        val hidden = ChartModel.build(ChartData(night, ms, evs, after), zone, "loud_vehicle", 45.0, showWind = false)
        assertTrue(hidden.windEvents.isEmpty())
        assertEquals(2, hidden.summary.windCount)
        assertTrue(hidden.yRange.hi < 95.0)
        // The measurement list counts the same way.
        val n = MeasurementSummarizer.summarize(spanOf(night), ms, evs, eventMinLevelDb = 45.0)
        assertEquals(n.eventCount, m.summary.eventCount)
        assertEquals(n.windEventCount, m.summary.windCount)
        assertEquals(n.humDb, m.summary.humDb)
        // Hourly views draw no wind.
        val week = ChartModel.build(ChartData(weekWindow, ms, evs, after), zone, "loud_vehicle", 45.0)
        assertTrue(week.windEvents.isEmpty())
        // Hit test and tooltip.
        val g = ChartGeometry(m, PlotRect(40f, 10f, 1040f, 410f))
        val dot = g.windDots.first()
        val sel = g.hitTest(dot.x, dot.y, 20f)
        assertIs<Selection.Event>(sel)
        assertTrue(sel.event.wind)
        val lines = tooltipLines(sel, m, detailed = true)
        assertTrue(lines[0].endsWith("· $WIND_LEGEND"), lines[0])
        assertTrue(tooltipLines(sel, m)[0].endsWith("· Wind"), tooltipLines(sel, m)[0])
        assertTrue(lines.any { it.startsWith("Lokaler Hintergrund 38,0 dB(A) (+52,0 dB)") }, lines.toString())
        assertTrue(lines.any { it.startsWith("Tieftonanteil 98 %") }, lines.toString())
        assertEquals("Wind", ChartCategories.name(WindRule.CATEGORY))
        assertTrue(ChartCategories.all.none { it.first == WindRule.CATEGORY }) // not a highlight chip
    }

    @Test
    fun dynamicsMatchMeasurementSummary() {
        val d = SyntheticData.fridayNight()
        val m = ChartModel.build(d, zone, "loud_vehicle", 30.0)
        val n = MeasurementSummarizer.summarize(spanOf(d.window), d.minutes, d.events, eventMinLevelDb = 30.0)
        assertEquals(n.eventsPerHour, m.summary.eventsPerHour, 1e-9)
        assertEquals(n.dynamicsDb, m.summary.dynamicsDb, 1e-9)
    }

    // ---- «Tagesverlauf» -------------------------------------------------------------------------

    @Test
    fun profileLaysAllDaysOverOneDayByHour() {
        // Two days: 14:00–14:30 at 40 dB on Monday and at 50 dB on Tuesday (valid), plus 5 minutes
        // at 03:00 (too few for the hour → gap) and invalid minutes (ignored).
        fun t(d: Int, h: Int, m: Int) = SyntheticData.ms(LocalDateTime.of(2026, 10, 5 + d, h, m))
        val ms = (0 until 30).map { minute(t(0, 14, 0) + it * 60_000L, 40.0, l10 = 44.0, l90 = 36.0) } +
            (0 until 30).map { minute(t(1, 14, 0) + it * 60_000L, 50.0, l10 = 56.0, l90 = 46.0) } +
            (0 until 5).map { minute(t(1, 3, 0) + it * 60_000L, 30.0) } +
            (0 until 20).map { minute(t(1, 15, 0) + it * 60_000L, 90.0, coverage = 0.2) }
        val pts = ChartModel.profilePoints(ms, zone)
        assertEquals(24, pts.size)
        assertEquals(14 * 3_600_000L, pts[14].startMs)
        assertTrue(pts[14].valid)
        assertEquals(10 * log10((1e4 + 1e5) / 2), pts[14].laeq, 1e-9)
        assertEquals((44.0 + 56.0) / 2, pts[14].l10, 1e-9) // median of the minute L10s
        assertEquals((36.0 + 46.0) / 2, pts[14].l90, 1e-9)
        assertTrue(!pts[3].valid) // 5 minutes < 10
        assertTrue(!pts[15].valid) // only invalid minutes
        // The model: 0–24 h axis in clock time, events at their clock time, same summary as the span view.
        val w = TimeWindow.span(t(0, 13, 0), t(1, 16, 0))
        val e = event(t(1, 14, 10), 80.0, "loud_vehicle")
        val span = ChartModel.build(ChartData(w, ms, listOf(e), t(2, 0, 0)), zone, "loud_vehicle", 45.0)
        val prof = ChartModel.build(ChartData(TimeWindow.profile(w.startMs, w.endMs), ms, listOf(e), t(2, 0, 0)), zone, "loud_vehicle", 45.0)
        assertEquals(Windows.PROFILE_WINDOW, prof.window)
        assertTrue(prof.hourly)
        assertEquals(listOf("0", "3", "6", "9", "12", "15", "18", "21", "24"), prof.axisLabels.map { it.text })
        assertEquals((14 * 60 + 10) * 60_000L, prof.highlightedEvents.single().startEpochMs)
        assertEquals("14:10:00 · Lautes Fahrzeug", tooltipLines(Selection.Event(prof.highlightedEvents.single(), true), prof)[0])
        assertEquals("14:00–15:00", tooltipLines(Selection.Point(prof.points[14]), prof)[0])
        assertEquals(span.summary, prof.summary)
    }
}
