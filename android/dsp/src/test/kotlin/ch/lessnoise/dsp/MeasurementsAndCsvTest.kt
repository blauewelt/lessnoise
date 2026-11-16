package ch.lessnoise.dsp

import ch.lessnoise.dsp.classify.ClassifierPreprocessor
import ch.lessnoise.dsp.classify.LabelScore
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasurementsAndCsvTest {
    private val zone = ZoneId.of("Europe/Zurich")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun minute(t: Long, laeq: Double, shares: Map<String, Double> = mapOf("road_traffic" to 1.0)) = MinuteRecord(
        t, Iso.format(t, zone), 60.0, laeq, laeq + 5, laeq - 5, laeq + 4, laeq + 2, laeq, laeq - 2, 0,
        shares.maxByOrNull { it.value }?.key, shares, 60, 1L, 110.0, "UNPROCESSED", true,
    )

    private fun event(t: Long, max: Double, bg: Double, cat: String) = NoiseEvent(
        t, Iso.format(t, zone, true), 2.0, max, max + 1, bg, 10.0, cat, 0.8f,
        listOf(LabelScore("Motorcycle", 0.8f)), 2, 1L, "UNPROCESSED", true,
    )

    private fun span(from: Long, to: Long) = MeasurementSpan(from, to)

    @Test
    fun measurementSummaryUsesOnlyItsOwnTimeSpanAtAnyTimeOfDay() {
        // A daytime measurement 14:00–14:03 (nothing about nights is special) and a later one.
        val minutes = listOf(
            minute(ms(2026, 10, 2, 13, 59), 70.0), // before the measurement
            minute(ms(2026, 10, 2, 14, 0), 50.0),
            minute(ms(2026, 10, 2, 14, 1), 60.0, mapOf("loud_vehicle" to 0.5, "unclassified" to 0.5)),
            minute(ms(2026, 10, 2, 14, 2), 50.0),
            minute(ms(2026, 10, 3, 22, 30), 40.0), // another measurement
        )
        val events = listOf(
            event(ms(2026, 10, 2, 14, 0) + 10_000, 80.0, 50.0, "loud_vehicle"), // strong (≥ bg + 15)
            event(ms(2026, 10, 2, 14, 2) + 5_000, 62.0, 50.0, "road_traffic"), // not strong
            event(ms(2026, 10, 2, 13, 59) + 5_000, 99.0, 50.0, "music"), // before: ignored
        )
        val first = span(ms(2026, 10, 2, 14, 0), ms(2026, 10, 2, 14, 3))
        val second = span(ms(2026, 10, 3, 22, 30), ms(2026, 10, 3, 22, 31))
        val list = MeasurementSummarizer.summarizeAll(listOf(first, second), minutes, events)
        assertEquals(listOf(second, first), list.map { it.span }) // newest first
        val m = list[1]
        assertEquals(180.0, m.measuredSeconds)
        assertEquals(1.0, m.coverage, 1e-12)
        val expected = 10 * log10((1e5 + 1e6 + 1e5) / 3)
        assertEquals(expected, m.laeqDb, 1e-9)
        assertEquals(2, m.eventCount)
        assertEquals(1, m.strongEventCount)
        assertEquals(80.0, m.loudestEvent!!.lafMaxDb)
        assertEquals(mapOf("loud_vehicle" to 1, "road_traffic" to 1), m.eventsByCategory)
        assertEquals(1.0 / 6, m.categoryShares.getValue("loud_vehicle"), 1e-9)
        assertEquals(2 / (180.0 / 3600), m.eventsPerHour, 1e-9)
        assertTrue(m.allCalibrated)
        // summarize() on one span gives the same as summarizeAll().
        assertEquals(m, MeasurementSummarizer.summarize(first, minutes, events))
    }

    @Test
    fun measurementSummaryAppliesEventFloorAtReadTime() {
        val minutes = listOf(minute(ms(2026, 10, 2, 23, 0), 30.0))
        val events = listOf(
            event(ms(2026, 10, 2, 23, 10), 38.0, 22.0, "unclassified"), // keystroke-like, old event (no floor stored)
            event(ms(2026, 10, 2, 23, 20), 45.0, 22.0, "voices"), // exactly at the floor: kept
            event(ms(2026, 10, 3, 1, 0), 72.0, 30.0, "loud_vehicle"),
        )
        val s = span(ms(2026, 10, 2, 23, 0), ms(2026, 10, 3, 2, 0))
        val all = MeasurementSummarizer.summarize(s, minutes, events)
        assertEquals(3, all.eventCount)
        val floored = MeasurementSummarizer.summarize(s, minutes, events, eventMinLevelDb = 45.0)
        assertEquals(2, floored.eventCount)
        assertEquals(mapOf("voices" to 1, "loud_vehicle" to 1), floored.eventsByCategory)
        assertEquals(72.0, floored.loudestEvent!!.lafMaxDb)
        // Only events below the floor: 0 events, no loudest.
        val high = MeasurementSummarizer.summarize(s, minutes, events, eventMinLevelDb = 80.0)
        assertEquals(0, high.eventCount)
        assertNull(high.loudestEvent)
    }

    @Test
    fun splitStartsANewMeasurementAfterAGapOfMoreThanTenMinutes() {
        val t0 = ms(2026, 10, 2, 9, 0)
        val min = 60_000L
        val minutes = listOf(
            // 09:00–09:03 (three minutes), then 10 min gap exactly → still the same measurement
            t0 to 60.0, t0 + min to 60.0, t0 + 2 * min to 60.0,
            t0 + 13 * min to 60.0,
            // 11 min gap → a new measurement, with a partial last minute
            t0 + 25 * min to 60.0, t0 + 26 * min to 20.5,
        ).shuffled(java.util.Random(1))
        val spans = MeasurementSplitter.split(minutes)
        assertEquals(
            listOf(MeasurementSpan(t0, t0 + 14 * min), MeasurementSpan(t0 + 25 * min, t0 + 26 * min + 20_500)),
            spans,
        )
        assertTrue(MeasurementSplitter.split(emptyList()).isEmpty())
        // A measurement over midnight and several days stays one as long as the minutes continue.
        val long = (0 until 3 * 1440).map { ms(2026, 10, 2, 20, 0) + it * min to 60.0 }
        assertEquals(1, MeasurementSplitter.split(long).size)
    }

    @Test
    fun ownerIndexFindsTheMeasurementARecordBelongsTo() {
        val starts = longArrayOf(1_000_000L, 2_000_000L, 3_000_000L)
        assertEquals(-1, MeasurementSplitter.ownerIndex(starts, 900_000L))
        assertEquals(0, MeasurementSplitter.ownerIndex(starts, 1_000_000L))
        // The first minute may carry a time a few seconds before the recorded start (clock anchor).
        assertEquals(0, MeasurementSplitter.ownerIndex(starts, 1_000_000L - 4_000))
        assertEquals(0, MeasurementSplitter.ownerIndex(starts, 1_999_000L - MeasurementSplitter.START_TOLERANCE_MS - 1))
        assertEquals(1, MeasurementSplitter.ownerIndex(starts, 2_500_000L))
        assertEquals(2, MeasurementSplitter.ownerIndex(starts, Long.MAX_VALUE / 2))
        assertEquals(-1, MeasurementSplitter.ownerIndex(LongArray(0), 5L))
    }

    @Test
    fun eventsPerHourAndDynamics() {
        // 30 valid minutes (0.5 h) with L10 − L90 spreads 2, 4, 6 … (minute() gives L10 − L90 = 4 dB).
        val base = ms(2026, 10, 2, 23, 0)
        val minutes = (0 until 30).map { i ->
            minute(base + i * 60_000L, 40.0).copy(l10Db = 40.0 + (i % 3) * 2, l90Db = 38.0 - (i % 3) * 2)
        } + minute(base + 30 * 60_000L, 40.0).copy(validSeconds = 10.0) // < 50 %: not counted
        val events = (0 until 12).map { event(base + it * 120_000L, 60.0, 40.0, "road_traffic") } +
            event(base + 61_000L, 35.0, 30.0, "voices") // below the floor
        val n = MeasurementSummarizer.summarize(span(base, base + 31 * 60_000L), minutes, events, eventMinLevelDb = 45.0)
        assertEquals(24.0, n.eventsPerHour, 1e-9) // 12 events in 0.5 h of valid measurement
        // Spreads: 2, 6, 10 each ten times → median 6.
        assertEquals(6.0, n.dynamicsDb, 1e-9)
        assertEquals(5.0, Dynamics.medianSpread(listOf(minute(base, 40.0).copy(l10Db = 44.0, l90Db = 40.0), minute(base, 40.0).copy(l10Db = 46.0, l90Db = 40.0))))
        assertTrue(Dynamics.eventsPerHour(3, 0.0).isNaN())
        assertTrue(Dynamics.medianSpread(emptyList()).isNaN())
    }

    @Test
    fun csvQuotesLabelsWithCommas() {
        val t = ms(2026, 10, 2, 23, 10)
        val e = event(t, 80.0, 50.0, "road_traffic").copy(
            topLabels = listOf(LabelScore("Vehicle horn, car horn, honking", 0.5f), LabelScore("Car", 0.3f)),
        )
        val csv = Csv.events(listOf(e)).lines()
        assertTrue(csv[1].contains("\"Vehicle horn, car horn, honking\",0.500,Car,0.300,,"), csv[1])
        // Events recorded before v0.3.0 have no floor: empty min_level_db.
        assertTrue(csv[1].startsWith("2026-10-02T23:10:00.000+02:00,2.000,80.0,81.0,50.0,10.0,,road_traffic"), csv[1])
        assertTrue(csv[0].startsWith("start,duration_s,lafmax_db,sel_db,background_db,threshold_db,min_level_db,dominant_category,"), csv[0])
        val withFloor = Csv.events(listOf(e.copy(minLevelDb = 45.0))).lines()
        assertTrue(withFloor[1].startsWith("2026-10-02T23:10:00.000+02:00,2.000,80.0,81.0,50.0,10.0,45.0,road_traffic"), withFloor[1])
        val mcsv = Csv.minutes(listOf(minute(t, 55.0)), listOf("road_traffic", "unclassified")).lines()
        assertEquals(
            "start,duration_s,valid_s,coverage,laeq_db,lafmax_db,lafmin_db,l1_db,l10_db,l50_db,l90_db,event_count,dominant_category," +
                "share_road_traffic,share_unclassified,classifier_frames,calibration_id,calibration_offset_db,audio_source,calibrated," +
                "clock_corrections,orig_laeq_db,orig_lafmax_db,orig_lafmin_db,orig_l1_db,orig_l10_db,orig_l50_db,orig_l90_db," +
                "recalibrated_from_id,recalibration_offset_db,local_floor_db,wind_event_count,at_floor",
            mcsv[0],
        )
        assertEquals("2026-10-02T23:10:00+02:00,60.0,60.0,1.000,55.0,60.0,50.0,59.0,57.0,55.0,53.0,0,road_traffic,1.000,,60,1,110.00,UNPROCESSED,true,0,,,,,,,,,,,0,0", mcsv[1])
    }

    @Test
    fun csvAppendsRecalibrationColumns() {
        val t = ms(2026, 10, 2, 23, 10)
        val target = ch.lessnoise.dsp.calibration.Recalibration.Target(7L, 121.95, "UNPROCESSED")
        val raw = minute(t, 55.0).copy(calibrationId = null, calibrationOffsetDb = 112.35, calibrated = false)
        val m = ch.lessnoise.dsp.calibration.Recalibration.recalibrate(raw, target)
        val mcsv = Csv.minutes(listOf(m), listOf("road_traffic")).lines()
        val header = mcsv[0].split(",")
        // Existing columns keep their order; the new ones are appended.
        assertEquals(
            listOf("orig_laeq_db", "orig_lafmax_db", "orig_lafmin_db", "orig_l1_db", "orig_l10_db", "orig_l50_db", "orig_l90_db",
                "recalibrated_from_id", "recalibration_offset_db"),
            header.dropLast(3).takeLast(9),
        )
        assertEquals("clock_corrections", header[header.size - 13])
        // v0.4.0 then v0.6.5 (appended).
        assertEquals(listOf("local_floor_db", "wind_event_count", "at_floor"), header.takeLast(3))
        val row = mcsv[1].split(",")
        assertEquals(header.size, row.size)
        assertEquals("64.6", row[header.indexOf("laeq_db")])
        assertEquals("7", row[header.indexOf("calibration_id")])
        assertEquals("121.95", row[header.indexOf("calibration_offset_db")])
        assertEquals("true", row[header.indexOf("calibrated")])
        assertEquals(listOf("55.0", "60.0", "50.0", "59.0", "57.0", "55.0", "53.0", "default", "112.35"), row.dropLast(3).takeLast(9))

        val e = ch.lessnoise.dsp.calibration.Recalibration.recalibrate(event(t, 60.0, 40.0, "road_traffic"), 110.0, target)
        val ecsv = Csv.events(listOf(e)).lines()
        val eh = ecsv[0].split(",")
        val nf = Csv.EVENT_FEATURE_COLUMNS.size
        assertEquals(Csv.EVENT_FEATURE_COLUMNS, eh.takeLast(nf)) // v0.4.0, appended after the v0.3.2 columns
        assertEquals(listOf("orig_lafmax_db", "orig_sel_db", "orig_background_db", "recalibrated_from_id", "recalibration_offset_db"), eh.dropLast(nf).takeLast(5))
        assertEquals("calibrated", eh[eh.size - nf - 6])
        val er = ecsv[1].split(",")
        assertEquals(eh.size, er.size)
        assertEquals("72.0", er[eh.indexOf("lafmax_db")])
        assertEquals(listOf("60.0", "61.0", "40.0", "1", "110.00"), er.dropLast(nf).takeLast(5))
        // Not re-evaluated, no v0.4.0 features: the new columns are empty (wind false).
        assertTrue(Csv.events(listOf(event(t, 60.0, 40.0, "road_traffic"))).lines()[1].endsWith(",true,,,,,,,,,,,,,,false,"))
    }

    @Test
    fun classifierNormalisation() {
        fun rmsDb(x: FloatArray) = 10 * log10(x.map { it.toDouble() * it }.average())
        val quiet = TestSignals.whiteNoise(1e-3, 0.975, fs = 16_000) // −60 dBFS
        assertEquals(30.0, ClassifierPreprocessor.normalize(quiet), 0.2)
        assertEquals(-30.0, rmsDb(quiet), 0.2)
        val veryQuiet = TestSignals.whiteNoise(1e-5, 0.975, fs = 16_000) // −100 dBFS → capped +40 dB
        assertEquals(40.0, ClassifierPreprocessor.normalize(veryQuiet), 1e-9)
        val loud = TestSignals.whiteNoise(0.3, 0.975, fs = 16_000)
        val copy = loud.copyOf()
        assertEquals(0.0, ClassifierPreprocessor.normalize(loud)) // never attenuates
        assertTrue(loud.contentEquals(copy))
    }

    /** The classifier gain is applied to a copied window only and never reaches the levels. */
    @Test
    fun classifierNormalisationDoesNotAffectLevels() {
        val signal = TestSignals.whiteNoise(3e-4, 70.0) // ≈ −70 dBFS: maximum classifier gain
        fun run(takeWindows: Boolean): List<MinuteRecord> {
            val minutes = ArrayList<MinuteRecord>()
            val clock = SimClock(1_700_000_000_000L)
            val engine = MeasurementEngine(
                EngineConfig(zone = zone), TestSignals.mapper(),
                object : MeasurementEngine.Listener { override fun onMinute(minute: MinuteRecord) { minutes += minute } },
                clock::now,
            )
            val window = FloatArray(ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
            var p = 0
            var gains = 0
            while (p < signal.size) {
                val n = minOf(6000, signal.size - p)
                clock.advance(n)
                engine.process(signal.copyOfRange(p, p + n), n)
                p += n
                if (takeWindows && engine.classifierDue() && engine.copyClassifierWindow(window) > 0) {
                    if (ClassifierPreprocessor.normalize(window) > 0.0) gains++
                }
            }
            engine.stop()
            if (takeWindows) assertTrue(gains > 30, "normalisation ran $gains times")
            return minutes
        }
        val plain = run(false)
        val withClassifierWindows = run(true)
        assertTrue(plain.isNotEmpty())
        assertEquals(plain.map { it.laeqDb }, withClassifierWindows.map { it.laeqDb })
        assertEquals(plain.map { it.l90Db }, withClassifierWindows.map { it.l90Db })
        assertEquals(plain.map { it.lafMaxDb }, withClassifierWindows.map { it.lafMaxDb })
    }
}
