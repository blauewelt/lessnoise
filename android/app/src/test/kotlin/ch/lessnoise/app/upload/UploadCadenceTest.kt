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
 * 0.6.3 live map: while a shared measurement runs, [LiveUploadPlan] drives the real Uploader against
 * the fake server in simulated time — a loud event about 30 s after it ended (events-only runs,
 * coalesced, at most one run per 30 s), minutes every 2 minutes, once after «Stoppen», the hourly
 * job as the fallback. The fake server limits requests per device like the real one
 * (server/stadtlaerm_server/ratelimit.py, a token bucket): 360 per hour from the server-live
 * change, 60 per hour before it.
 */
class UploadCadenceTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val server = FakeServer()

    @AfterTest
    fun stop() = server.close()

    private val min = 60_000L

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

    private class Stats(
        val runs: Int, val requests: Int, val busiestHour: Int, val rateLimited: Int, val maxEventDelayMs: Long,
        val bodyBytes: Long, val minutesArrived: Int, val eventsArrived: Int, val hours: Int, val eventsRuns: Int,
    ) {
        override fun toString() =
            "$hours h: $runs runs ($eventsRuns events-only), $requests requests, busiest hour $busiestHour, " +
                "429: $rateLimited, slowest event ${maxEventDelayMs / 1000} s after its end, " +
                "request bodies ${bodyBytes / hours / 1024} KiB per hour (gzip)"
    }

    /**
     * [hours] of a shared measurement with a minute row every minute and a loud event (3 s) every
     * [eventEveryMs], the server limited to [limitPerHour]; afterwards the run after «Stoppen» and
     * the hourly fallback until everything is on the server.
     */
    private fun simulate(hours: Int, eventEveryMs: Long, limitPerHour: Int?): Stats = runBlocking {
        val start = ZonedDateTime.of(2026, 10, 15, 17, 52, 0, 0, zone).toInstant().toEpochMilli()
        val pointAt = start + 40_000L // the fix took 40 s
        val stop = start + hours * 60 * min
        var now = start
        server.clock = { now }
        server.limitPerHour = limitPerHour
        val point = LocationNoise.Point(2682437, 1247391, 50)
        val minutes = (0 until hours * 60).map { minute(start + it * min, it + 1L) }
        val events = generateSequence(start + 20_000L) { it + eventEveryMs }.takeWhile { it + 3_000L < stop }
            .mapIndexed { i, t -> event(t, i + 1L) }.toList()
        val ends = events.map { it.startEpochMs + 3_000L }
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
        val store = MemoryUploadStore()
        val up = Uploader(UploadClient(server.baseUrl), store, source, MemoryReportStore(), "0.6.3", 16, clock = { now })
        up.enable()

        val requestTimes = mutableListOf<Long>()
        val sentAt = LongArray(events.size) { -1 }
        var runs = 0
        var eventsRuns = 0
        fun noteArrivals() {
            val id = store.state.value.deviceId ?: return
            val n = server.events[id]?.size ?: 0
            for (i in 0 until n) if (sentAt[i] < 0) sentAt[i] = now
        }
        suspend fun runAt(t: Long, withMinutes: Boolean): Uploader.Outcome {
            now = t
            val before = server.requests.size
            val o = up.run(withMinutes)
            repeat(server.requests.size - before) { requestTimes += t }
            runs++
            if (!withMinutes) eventsRuns++
            noteArrivals()
            return o
        }

        val plan = LiveUploadPlan(pointAt)
        var fed = 0
        while (true) {
            val r = plan.next()
            if (fed < ends.size && ends[fed] <= r.atMs && ends[fed] < stop) {
                plan.onEvent(ends[fed++])
                continue
            }
            if (r.atMs >= stop) break
            val o = runAt(r.atMs, r.kind == LiveUploadPlan.Kind.FULL)
            val result = when {
                o is Uploader.Outcome.Done -> LiveUploadPlan.Result.OK
                o is Uploader.Outcome.Retry && o.rateLimited -> LiveUploadPlan.Result.RATE_LIMITED
                else -> LiveUploadPlan.Result.FAILED
            }
            plan.done(r, result, now)
        }
        // «Stoppen», then the hourly fallback job (WorkManager) until all is sent.
        val after = listOf(stop + UploadCadence.AFTER_STOP_MS) + UploadCadence.hourlyRunTimes(stop, stop + 6 * 60 * min, zone)
        for (t in after) runAt(t, true)
        val id = store.state.value.deviceId!!
        val busiest = requestTimes.maxOf { t0 -> requestTimes.count { it >= t0 && it < t0 + 60 * min } }
        val inMeasurement = ends.indices.filter { sentAt[it] in 0 until stop }
        Stats(
            runs = runs, requests = requestTimes.size, busiestHour = busiest, rateLimited = server.rateLimited.get(),
            maxEventDelayMs = inMeasurement.maxOfOrNull { sentAt[it] - ends[it] } ?: -1,
            bodyBytes = server.requests.sumOf { it.rawSize.toLong() },
            minutesArrived = server.minutes[id]?.size ?: 0, eventsArrived = server.events[id]?.size ?: 0,
            hours = hours, eventsRuns = eventsRuns,
        ).also { s ->
            assertEquals(minutes.size, s.minutesArrived, "every minute arrived")
            assertEquals(events.size, s.eventsArrived, "every event arrived")
        }
    }

    @Test
    fun aLoudEventEveryMinuteForTwoHoursStaysWithin150RequestsPerHour() {
        val s = simulate(hours = 2, eventEveryMs = min, limitPerHour = 360)
        println("UploadCadenceTest (event every minute, limit 360): $s")
        assertTrue(s.busiestHour <= 150, "busiest hour ${s.busiestHour}")
        assertEquals(0, s.rateLimited)
        // Each event on its way within about a minute of its end (30 s, or the next run 30 s later).
        assertTrue(s.maxEventDelayMs in 0..65_000L, "slowest event ${s.maxEventDelayMs} ms")
        assertTrue(s.eventsRuns > 0)
        // Mobile data, the worst case measured round the clock: the gzip bodies plus about 1 KiB of
        // HTTP headers per request (TLS kept alive between requests): the «höchstens etwa 3 MB pro Tag».
        val perDay = (s.bodyBytes / s.hours + s.busiestHour * 1024L) * 24
        println("UploadCadenceTest: about ${perDay / 1024} KiB per day of measuring (estimate)")
        assertTrue(perDay < 3_000_000, "$perDay bytes per day")
    }

    @Test
    fun theOldServerLimitOf60DegradesGracefully() {
        val s = simulate(hours = 3, eventEveryMs = min, limitPerHour = 60)
        println("UploadCadenceTest (event every minute, OLD limit 60): $s")
        // Backs off after 429 instead of hammering: few refusals, and (asserted in simulate) nothing lost.
        assertTrue(s.rateLimited in 1..12, "429 answers: ${s.rateLimited}")
        assertTrue(s.busiestHour <= 100, "busiest hour ${s.busiestHour} (incl. refused requests)")
    }

    @Test
    fun aQuietEveningForTheDataEstimate() {
        // A loud event every 5 minutes: the usual case.
        val s = simulate(hours = 2, eventEveryMs = 5 * min, limitPerHour = 360)
        println("UploadCadenceTest (event every 5 minutes): $s")
        assertTrue(s.busiestHour <= 60, "busiest hour ${s.busiestHour}")
        assertTrue(s.maxEventDelayMs in 0..65_000L)
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
        val o = up.run()
        assertIs<Uploader.Outcome.Retry>(o)
        assertTrue(o.rateLimited)
        assertEquals(Uploader.RETRY_TEXT, store.state.value.lastError)
        assertEquals(0L, store.state.value.minutesSentUpToMs, "the marker did not move")
        now += 4 * min
        assertIs<Uploader.Outcome.Done>(up.run())
        assertEquals(12, server.minutes[store.state.value.deviceId]!!.size)
    }

    @Test
    fun anEventsOnlyRunIsOneRequestAndLeavesTheMinutes() = runBlocking {
        val start = ZonedDateTime.of(2026, 10, 15, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        var now = start + 3 * min
        val point = LocationNoise.Point(2682437, 1247391, 50)
        val minutes = (0 until 3).map { minute(start + it * min, it + 1L) }
        val events = mutableListOf(event(start + 2 * min + 10_000, 1), event(start + 2 * min + 30_000, 2))
        val source = object : UploadSource {
            override suspend fun minutesAfter(afterMs: Long, limit: Int) = minutes.filter { it.startEpochMs > afterMs }.take(limit)
            override suspend fun eventsAfter(afterMs: Long, limit: Int) = events.filter { it.startEpochMs > afterMs }.take(limit)
            override suspend fun measurements() = listOf(
                MeasurementEntity(id = 1, startMs = start, endMs = null, cell = point.cell, pointE = point.e, pointN = point.n, sigmaM = 50),
            )
            override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = false, calibrationOffsetDb = 112.0)
        }
        val store = MemoryUploadStore()
        val up = Uploader(UploadClient(server.baseUrl), store, source, MemoryReportStore(), "0.6.3", 16, clock = { now })
        up.enable()
        assertIs<Uploader.Outcome.Done>(up.run()) // registers, site, minutes, events
        events += event(start + 2 * min + 50_000, 3)
        val before = server.requests.size
        val minutesBefore = server.minutes[store.state.value.deviceId]!!.size
        now += 30_000
        assertIs<Uploader.Outcome.Done>(up.run(withMinutes = false))
        assertEquals(1, server.requests.size - before, "one request: the events")
        assertEquals("/events", server.requests.last().path.substringAfterLast('/').let { "/$it" })
        assertEquals(3, server.events[store.state.value.deviceId]!!.size)
        assertEquals(minutesBefore, server.minutes[store.state.value.deviceId]!!.size)
    }
}
