package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.dsp.Iso
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 0.6.3: while a shared measurement runs the app uploads about 2 minutes after the point is known,
 * then every 10 minutes, once after «Stoppen», and the hourly job stays as the fallback
 * ([UploadCadence], server/DESIGN.md §4.6). The server allows 60 requests per device and hour
 * (DESIGN.md §8, server/stadtlaerm_server/ratelimit.py); this test runs the real Uploader at exactly
 * these times against the fake server and counts the requests.
 */
class UploadCadenceTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val server = FakeServer()

    @AfterTest
    fun stop() = server.close()

    private val min = 60_000L

    @Test
    fun liveScheduleIsTwoMinutesAfterThePointThenEveryTenAndOnceAfterStop() {
        assertEquals(
            listOf(2, 12, 22, 32, 42, 52).map { it * min } + (60 * min + 15_000L),
            UploadCadence.liveRunTimes(pointAtMs = 0, stopMs = 60 * min),
        )
        // A short measurement: only the run after «Stoppen».
        assertEquals(listOf(90 * 1000L + 15_000L), UploadCadence.liveRunTimes(0, 90 * 1000L))
        val t = ZonedDateTime.of(2026, 10, 15, 17, 52, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(listOf(t + 13 * min, t + 73 * min), UploadCadence.hourlyRunTimes(t, t + 2 * 60 * min, zone))
    }

    private fun minute(startMs: Long, id: Long) = MinuteEntity(
        id = id, startEpochMs = startMs, startIso = Iso.format(startMs, zone), durationSeconds = 60.0,
        laeqDb = 52.0, lafMaxDb = 70.0, lafMinDb = 40.0, l1Db = 66.0, l10Db = 58.0, l50Db = 50.0, l90Db = 44.0,
        eventCount = 1, dominantCategory = "road_traffic", categorySharesJson = """{"road_traffic":1.0}""",
        classifierFrames = 60, calibrationId = null, calibrationOffsetDb = 112.0, audioSource = "UNPROCESSED",
        calibrated = false, validSeconds = 60.0, coverage = 1.0, clockCorrections = 0,
    )

    private fun event(startMs: Long, id: Long) = EventEntity(
        id = id, startEpochMs = startMs, startIso = Iso.format(startMs, zone, millis = true), durationSeconds = 3.0,
        lafMaxDb = 78.0, selDb = 80.0, backgroundDb = 45.0, thresholdDb = 10.0, dominantCategory = "loud_vehicle",
        dominantScore = 0.4f, topLabelsJson = "[]", classifierFrames = 3, calibrationId = null,
        audioSource = "UNPROCESSED", calibrated = false,
    )

    @Test
    fun twoHoursOfMeasuringStayFarBelowTheServersHourlyLimit() = runBlocking {
        val start = ZonedDateTime.of(2026, 10, 15, 17, 52, 0, 0, zone).toInstant().toEpochMilli()
        val pointAt = start + 40_000L // the fix took 40 s
        val stop = start + 120 * min
        var now = start
        val point = LocationNoise.Point(2682437, 1247391, 50)
        // A minute row every minute, a loud moment every 3 minutes: written when they are complete.
        val minutes = (0 until 120).map { minute(start + it * min, it + 1L) }
        val events = (0 until 40).map { event(start + it * 3 * min + 20_000L, it + 1L) }
        val source = object : UploadSource {
            override suspend fun minutesAfter(afterMs: Long, limit: Int) =
                minutes.filter { it.startEpochMs > afterMs && it.startEpochMs + min <= now }.take(limit)

            override suspend fun eventsAfter(afterMs: Long, limit: Int) =
                events.filter { it.startEpochMs > afterMs && it.startEpochMs + 3_000L <= now }.take(limit)

            override suspend fun measurements() = listOf(
                MeasurementEntity(id = 1, startMs = start, endMs = if (now >= stop) stop else null, cell = point.cell, pointE = point.e, pointN = point.n, sigmaM = 50),
            )

            override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = false, calibrationOffsetDb = 112.0)
        }
        val reports = MemoryReportStore()
        val store = MemoryUploadStore()
        val up = Uploader(UploadClient(server.baseUrl), store, source, reports, "0.6.3", 16, clock = { now })
        now = start
        up.enable() // «Ja, auf die Karte» at «Starten»

        val runs = (UploadCadence.liveRunTimes(pointAt, stop) + UploadCadence.hourlyRunTimes(start, stop + 60 * min, zone)).sorted()
        val requestTimes = mutableListOf<Long>()
        var maxPerRun = 0
        for ((i, t) in runs.withIndex()) {
            now = t
            // One «Laut! Melden» during the measurement is impossible (only while none runs); one
            // right after the stop goes with the next run.
            if (t > stop && reports.state.value.pending.isEmpty() && i == runs.indexOfFirst { it > stop }) {
                ReportQueue.add(reports, PendingReport(t - 5_000, PendingReport.isoSeconds(t - 5_000, zone), 47.3769, 8.5417, 8.0, ReportCategory.MOTORRAD.id, "h26833_12479"))
            }
            val before = server.requests.size
            assertIs<Uploader.Outcome.Done>(up.run(), "run at +${(t - start) / min} min")
            val n = server.requests.size - before
            maxPerRun = maxOf(maxPerRun, n)
            repeat(n) { requestTimes += t }
        }
        val id = store.state.value.deviceId!!
        assertEquals(120, server.minutes[id]!!.size, "every minute of the measurement arrived")
        assertEquals(40, server.events[id]!!.size)
        // The busiest hour (any 60-minute window) against the server's 60 per device and hour.
        val busiest = requestTimes.maxOf { t0 -> requestTimes.count { it >= t0 && it < t0 + 60 * min } }
        println("UploadCadenceTest: ${runs.size} runs, ${requestTimes.size} requests, busiest hour $busiest, at most $maxPerRun per run")
        assertTrue(busiest <= 20, "busiest hour $busiest requests; the server allows 60")
        assertTrue(maxPerRun <= 5, "at most $maxPerRun requests in one run")
        // Even a run that uses its whole budget stays below the limit with the hourly fallback and 6 live runs.
        assertTrue(up.requestBudgetPerRun < 60)
    }

    @Test
    fun aRateLimitedRunLosesNothingAndTheNextSlotSendsIt() = runBlocking {
        val start = ZonedDateTime.of(2026, 10, 15, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        var now = start + 12 * min
        val point = LocationNoise.Point(2682437, 1247391, 50)
        val minutes = (0 until 12).map { minute(start + it * min, it + 1L) }
        val source = object : UploadSource {
            override suspend fun minutesAfter(afterMs: Long, limit: Int) =
                minutes.filter { it.startEpochMs > afterMs && it.startEpochMs + min <= now }.take(limit)
            override suspend fun eventsAfter(afterMs: Long, limit: Int) = emptyList<EventEntity>()
            override suspend fun measurements() = listOf(
                MeasurementEntity(id = 1, startMs = start, endMs = null, cell = point.cell, pointE = point.e, pointN = point.n, sigmaM = 50),
            )
            override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = false, calibrationOffsetDb = 112.0)
        }
        val store = MemoryUploadStore()
        val up = Uploader(UploadClient(server.baseUrl), store, source, MemoryReportStore(), "0.6.3", 16, clock = { now })
        up.enable()
        server.failNext += Triple("POST", "/minutes", 429)
        // 429: Retry (the worker returns Result.retry(): WorkManager backs off, from 10 minutes).
        assertIs<Uploader.Outcome.Retry>(up.run())
        assertEquals(Uploader.RETRY_TEXT, store.state.value.lastError)
        assertEquals(0L, store.state.value.minutesSentUpToMs, "the marker did not move")
        now += 10 * min
        assertIs<Uploader.Outcome.Done>(up.run())
        assertEquals(12, server.minutes[store.state.value.deviceId]!!.size)
    }
}
