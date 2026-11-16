package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MigrationSql
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.app.data.MovingSpanEntity
import ch.lessnoise.app.data.MovingSql
import ch.lessnoise.dsp.Iso
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.DriverManager
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.6.7 «Unterwegs erkannt»: minutes and events of a time span in which the phone was being carried
 * (moving_spans) never reach an upload payload, also when the span is decided after the records were
 * stored (the upload's hold); before and after the span the measurement is shared as before. Runs
 * in every flavour (the upload code is shared; only the editions with the map ever write spans).
 */
class MovingSpanUploadTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val server = FakeServer()
    private val t0 = ZonedDateTime.of(2026, 10, 17, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
    private var now = t0 + 3 * 3_600_000L
    private val min = 60_000L
    private val point = LocationNoise.Point(2682437, 1247391, 50)

    @AfterTest
    fun stop() = server.close()

    private val minuteRows = mutableListOf<MinuteEntity>()
    private val eventRows = mutableListOf<EventEntity>()
    private val spans = mutableListOf<MovingSpanEntity>()
    private val measurement = mutableListOf(
        MeasurementEntity(id = 1, startMs = t0, endMs = null, cell = point.cell, pointE = point.e, pointN = point.n, sigmaM = point.sigmaM),
    )

    private val source = object : UploadSource {
        override suspend fun minutesAfter(afterMs: Long, limit: Int) =
            minuteRows.filter { it.startEpochMs > afterMs }.sortedWith(compareBy({ it.startEpochMs }, { it.id })).take(limit)

        override suspend fun eventsAfter(afterMs: Long, limit: Int) =
            eventRows.filter { it.startEpochMs > afterMs }.sortedWith(compareBy({ it.startEpochMs }, { it.id })).take(limit)

        override suspend fun measurements() = measurement.toList()
        override suspend fun movingSpans() = spans.toList()
        override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = true, calibrationOffsetDb = 121.9)
    }

    private fun minute(startMs: Long) = MinuteEntity(
        id = minuteRows.size + 1L, startEpochMs = startMs, startIso = Iso.format(startMs, zone), durationSeconds = 60.0,
        laeqDb = 50.0, lafMaxDb = 70.0, lafMinDb = 40.0, l1Db = 65.0, l10Db = 55.0, l50Db = 48.0, l90Db = 44.0,
        eventCount = 1, dominantCategory = "road_traffic", categorySharesJson = """{"road_traffic":1.0}""",
        classifierFrames = 60, calibrationId = 7, calibrationOffsetDb = 121.9, audioSource = "UNPROCESSED",
        calibrated = true, validSeconds = 60.0, coverage = 1.0, clockCorrections = 0,
    )

    private fun event(startMs: Long) = EventEntity(
        id = eventRows.size + 1L, startEpochMs = startMs, startIso = Iso.format(startMs, zone, millis = true), durationSeconds = 4.0,
        lafMaxDb = 82.0, selDb = 85.0, backgroundDb = 50.0, thresholdDb = 10.0, dominantCategory = "loud_vehicle",
        dominantScore = 0.6f, topLabelsJson = "[]", classifierFrames = 4, calibrationId = 7, audioSource = "UNPROCESSED",
        calibrated = true, minLevelDb = 30.0,
    )

    private fun setup(hold: () -> Long = { 0L }): Pair<Uploader, MemoryUploadStore> {
        val store = MemoryUploadStore(UploadSnapshot(explanationSeen = true, site = SiteSettings(placement = "open_window")))
        val up = Uploader(UploadClient(server.baseUrl), store, source, MemoryReportStore(), "0.6.7", 20, clock = { now }, holdMs = hold)
        assertTrue(up.enable())
        return up to store
    }

    private fun sentStarts(kind: String): Set<String> =
        (if (kind == "minutes") server.minutes else server.events).values.flatMap { it.values }.map { it["start"]!!.jsonPrimitive.content }.toSet()

    @Test
    fun recordsOfAMovingSpanNeverReachAPayloadAndSharingResumesAfterIt() = runBlocking {
        // 60 minutes of a «Messen» measurement; carried from 18:20:30 to 18:35:00.
        repeat(60) { minuteRows += minute(t0 + it * min) }
        repeat(30) { eventRows += event(t0 + it * 2 * min + 10_000) }
        val spanStart = t0 + 20 * min + 30_000
        val spanEnd = t0 + 35 * min
        spans += MovingSpanEntity(1, spanStart, spanEnd)
        val (up, _) = setup()
        val o = up.run()
        // Minutes overlapping [18:20:30, 18:35): 18:20 … 18:34 = 15 of them; events starting 18:22:10 … 18:34:10 = 7.
        assertEquals(Uploader.Outcome.Done(minutes = 45, events = 23, refused = 0, more = false), o)
        val sentMinutes = sentStarts("minutes")
        val sentEvents = sentStarts("events")
        for (m in minuteRows) {
            val overlaps = m.startEpochMs < spanEnd && m.startEpochMs + min > spanStart
            assertEquals(!overlaps, m.startIso in sentMinutes, m.startIso)
        }
        for (e in eventRows) {
            val overlaps = e.startEpochMs < spanEnd && e.startEpochMs + 4_000 > spanStart
            assertEquals(!overlaps, e.startIso in sentEvents, e.startIso)
        }
        // Nothing of the span went out in ANY request body (all requests, raw).
        val bodies = server.requests.mapNotNull { it.body?.toString() }.joinToString("\n")
        for (m in minuteRows.filter { it.startEpochMs >= spanStart - 30_000 && it.startEpochMs < spanEnd }) assertTrue(m.startIso !in bodies, m.startIso)
        // Never later either (the markers moved past them).
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
    }

    @Test
    fun anOpenSpanKeepsEverythingAfterItsStartOff() = runBlocking {
        repeat(30) { minuteRows += minute(t0 + it * min) }
        spans += MovingSpanEntity(1, t0 + 10 * min, null)
        val (up, _) = setup()
        assertEquals(Uploader.Outcome.Done(minutes = 10, events = 0, refused = 0, more = false), up.run())
    }

    @Test
    fun aSpanDecidedAfterTheRecordsWereStoredStillKeepsThemOff() = runBlocking {
        // The live situation: minutes and a loud event are stored while the phone already moves, the
        // detector decides «moving» only ~25 s later and its span reaches back. The upload holds the
        // newest records for MOTION_HOLD_MS, so they are never sent before the span exists.
        val hold = { Uploader.MOTION_HOLD_MS }
        now = t0 + 10 * min + 2_000
        repeat(10) { minuteRows += minute(t0 + it * min) }   // 18:00 … 18:09, the last ended 2 s ago
        eventRows += event(t0 + 9 * min + 50_000)           // ended 8 s ago
        val (up, store) = setup(hold)
        up.run()
        assertEquals(9, server.minutes.values.sumOf { it.size }, "the minute that ended 2 s ago waits")
        assertEquals(0, server.events.values.sumOf { it.size }, "the event that ended 8 s ago waits")
        assertTrue(store.state.value.minutesSentUpToMs < t0 + 9 * min)
        // 25 s later the detector decides: moving since 18:09:30 (the walk started there).
        now += 25_000
        spans += MovingSpanEntity(1, t0 + 9 * min + 30_000, null)
        repeat(3) { minuteRows += minute(t0 + (10 + it) * min) }
        now = t0 + 13 * min + 40_000
        up.run()
        assertEquals(9, server.minutes.values.sumOf { it.size })
        assertEquals(0, server.events.values.sumOf { it.size })
        // The detector returns to STILL at 18:14:00 (its decision time ends the span): shared again from there.
        spans[0] = spans[0].copy(endMs = t0 + 14 * min)
        repeat(5) { minuteRows += minute(t0 + (13 + it) * min) }
        now = t0 + 20 * min
        up.run()
        val sent = sentStarts("minutes")
        assertEquals((0..8).map { Iso.format(t0 + it * min, zone) }.toSet() + (14..17).map { Iso.format(t0 + it * min, zone) }, sent)
        assertTrue(sentStarts("events").isEmpty())
    }

    @Test
    fun theHoldOnlyDelaysAndLosesNothing() = runBlocking {
        val hold = { Uploader.MOTION_HOLD_MS }
        now = t0 + 5 * min + 10_000
        repeat(5) { minuteRows += minute(t0 + it * min) }
        eventRows += event(t0 + 4 * min + 50_000)
        val (up, _) = setup(hold)
        up.run()
        assertEquals(4, server.minutes.values.sumOf { it.size })
        now += Uploader.MOTION_HOLD_MS
        up.run()
        assertEquals(5, server.minutes.values.sumOf { it.size })
        assertEquals(1, server.events.values.sumOf { it.size })
        // The live map's events run waits until the hold has passed (UploadModule.liveLoop).
        val worker = File(app(), "src/main/kotlin/ch/lessnoise/app/upload/UploadWorker.kt").readText()
        assertTrue(worker.contains("LiveUploadPlan.triggers(e) && !live.value.moving"))
        assertTrue(worker.contains("uploader.holdNowMs() - LiveUploadPlan.EVENT_DELAY_MS"))
        assertTrue(worker.contains("override suspend fun movingSpans(): List<MovingSpanEntity> = db.measurements().allMovingSpans()"))
    }

    @Test
    fun theSqlCountOfMinutesToSendUsesTheSameOverlapRule() {
        val rnd = Random(7)
        val spans = (0 until 6).map { i ->
            val s = t0 + rnd.nextLong(0, 120) * 30_000
            MovingSpanEntity(i + 1L, s, if (i == 5) null else s + rnd.nextLong(1, 20) * 15_000)
        }
        val minutes = (0 until 80).map { t0 + it * min + rnd.nextLong(0, 5_000) to (if (it % 17 == 0) 0.0 else 60.0) }
        val index = PlaceIndex(emptyList(), spans)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(MigrationSql.CREATE_MOVING_SPANS_V9)
                st.execute("CREATE TABLE minutes (startEpochMs INTEGER NOT NULL, durationSeconds REAL NOT NULL)")
                for (s in spans) st.execute("INSERT INTO moving_spans (start_ms, end_ms) VALUES (${s.startMs}, ${s.endMs ?: "NULL"})")
                for ((t, d) in minutes) st.execute("INSERT INTO minutes VALUES ($t, $d)")
                val sql = mutableListOf<Long>()
                st.executeQuery("SELECT m.startEpochMs FROM minutes AS m WHERE ${MovingSql.MINUTE_NOT_MOVING} ORDER BY m.startEpochMs").use { rs ->
                    while (rs.next()) sql += rs.getLong(1)
                }
                val kotlin = minutes.filter { (t, d) -> !index.moving(t, (d * 1000).toLong()) }.map { it.first }
                assertEquals(kotlin, sql)
                assertTrue(sql.size in 1 until minutes.size, "the sample has moving and still minutes")
            }
        }
        val dao = File(app(), "src/main/kotlin/ch/lessnoise/app/data/Database.kt").readText()
        assertTrue(dao.contains("\" AND \" + MovingSql.MINUTE_NOT_MOVING"), "sharedMinuteCountAfter uses the rule")
    }

    @Test
    fun migration8to9AddsTheEmptyTableAndKeepsEverything() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(MigrationSql.CREATE_MEASUREMENTS_V8)
                st.execute("INSERT INTO measurements (start_ms, end_ms, unterwegs) VALUES (0, 1000, 0), (2000, NULL, 1)")
                MigrationSql.MIGRATE_8_9.forEach { st.execute(it) }
                st.executeQuery("SELECT COUNT(*) FROM measurements").use { rs -> rs.next(); assertEquals(2, rs.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM moving_spans").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
                // endMovingSpans: an open span ends at the given time, never before its start.
                st.execute("INSERT INTO moving_spans (start_ms) VALUES (5000)")
                assertEquals(1, st.executeUpdate("UPDATE moving_spans SET end_ms = MAX(4000, start_ms) WHERE end_ms IS NULL"))
                st.executeQuery("SELECT end_ms FROM moving_spans").use { rs -> rs.next(); assertEquals(5000L, rs.getLong(1)) }
            }
        }
        val dao = File(app(), "src/main/kotlin/ch/lessnoise/app/data/Database.kt").readText()
        assertTrue(dao.contains("\"UPDATE moving_spans SET end_ms = MAX(:endMs, start_ms) WHERE end_ms IS NULL\""))
    }

    private fun app(): File = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
}
