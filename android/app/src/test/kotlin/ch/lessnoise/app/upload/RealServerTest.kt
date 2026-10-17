package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.dsp.Iso
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Optional round trip against the real contribution server (`server/`, run locally):
 *
 *     cd server && DB_PATH=/tmp/s.sqlite MAP_DIR=/tmp/map BACKGROUND_JOBS=0 HOST=127.0.0.1 PORT=18765 \
 *         .venv/bin/python -m stadtlaerm_server serve
 *     STADTLAERM_TEST_SERVER=http://127.0.0.1:18765 ./gradlew :app:testPublicDebugUnitTest --tests '*RealServerTest*'
 *
 * Skipped unless STADTLAERM_TEST_SERVER is set (only a loopback URL is accepted by UploadClient
 * besides https). Checks that the server's schema validation accepts exactly what the app sends.
 */
class RealServerTest {
    private val base: String? = System.getenv("STADTLAERM_TEST_SERVER")
    private val zone = ZoneId.of("Europe/Zurich")

    @Test
    fun theRealServerAcceptsEverythingTheAppSendsAndDeletesIt() = runBlocking {
        assumeTrue("STADTLAERM_TEST_SERVER not set", base != null)
        val now = System.currentTimeMillis()
        val firstMinute = (now - 3 * 3_600_000) / 60_000 * 60_000
        val minutes = (0 until 120).map { i ->
            val t = firstMinute + i * 60_000L
            MinuteEntity(
                id = i + 1L, startEpochMs = t, startIso = Iso.format(t, zone), durationSeconds = 60.0,
                laeqDb = if (i == 5) null else 42.34 + i % 7, lafMaxDb = if (i == 5) null else 61.04, lafMinDb = 33.1,
                l1Db = 55.0, l10Db = 47.2, l50Db = 41.0, l90Db = 36.4, eventCount = i % 3,
                dominantCategory = if (i % 2 == 0) "road_traffic" else null,
                categorySharesJson = """{"road_traffic":0.41,"loud_vehicle":0.05,"unclassified":0.54}""",
                classifierFrames = 60, calibrationId = if (i < 60) null else 7, calibrationOffsetDb = 112.35,
                audioSource = "UNPROCESSED", calibrated = i >= 60, validSeconds = if (i == 5) 0.0 else 59.5,
                coverage = if (i == 5) 0.0 else 59.5 / 60, clockCorrections = 0,
            )
        }
        val events = (0 until 40).map { i ->
            val t = firstMinute + i * 97_000L + 375
            EventEntity(
                id = i + 1L, startEpochMs = t, startIso = Iso.format(t, zone, millis = true), durationSeconds = 2.75,
                lafMaxDb = 71.44, selDb = 74.0, backgroundDb = 38.1, thresholdDb = 10.0,
                dominantCategory = if (i % 4 == 0) null else "loud_vehicle", dominantScore = 0.31f,
                topLabelsJson = "[]", classifierFrames = 3, calibrationId = 7, audioSource = "UNPROCESSED",
                calibrated = true, minLevelDb = if (i % 5 == 0) Double.NaN else 30.0,
            )
        }
        val tooOld = minutes.first().copy(id = 999, startEpochMs = now - 8L * 86_400_000, startIso = Iso.format(now - 8L * 86_400_000, zone))
        val source = object : UploadSource {
            val all = listOf(tooOld) + minutes
            override suspend fun minutesAfter(afterMs: Long, limit: Int) = all.filter { it.startEpochMs > afterMs }.take(limit)
            override suspend fun eventsAfter(afterMs: Long, limit: Int) = events.filter { it.startEpochMs > afterMs }.take(limit)
            override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = false, calibrationOffsetDb = 112.35)
            // One shared measurement covering everything (also the too-old minute).
            override suspend fun measurements() =
                listOf(ch.lessnoise.app.data.MeasurementEntity(id = 1, startMs = tooOld.startEpochMs, endMs = null, cell = "h26833_12479", pointE = 2683351, pointN = 1247988, sigmaM = 50))
        }
        val store = MemoryUploadStore(
            UploadSnapshot(explanationSeen = true, site = SiteSettings("balcony", floor = 0, streetFacing = false, note = "Test"))
        )
        val up = Uploader(UploadClient(base!!), store, source, MemoryReportStore(), "0.6.0-dev", 13, minuteBatch = 50, eventBatch = 15)
        up.enable()
        val o = up.run()
        // 0.6.4: the 10 events without a category (classifier off) are not map events and stay on the phone.
        assertEquals(Uploader.Outcome.Done(minutes = 120, events = 30, refused = 0, more = false), o, "the 8-day-old minute is not even sent")
        // Again after a re-evaluation: the server reports duplicates, stores nothing twice.
        up.rewindAfterReevaluation()
        val again = up.run()
        assertIs<Uploader.Outcome.Done>(again)
        assertEquals(120, again.minutes)
        assertNull(store.state.value.lastError)
        assertTrue(store.state.value.refused.isEmpty())
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
    }

    /** 0.6.3: the measurement starts without a point; the point comes later; the server gets it all. */
    @Test
    fun theRealServerAcceptsARunningMeasurementWhosePointCameLater() = runBlocking {
        assumeTrue("STADTLAERM_TEST_SERVER not set", base != null)
        val start = (System.currentTimeMillis() - 30 * 60_000L) / 60_000 * 60_000
        val minutes = (0 until 25).map { i ->
            val t = start + i * 60_000L
            MinuteEntity(
                id = i + 1L, startEpochMs = t, startIso = Iso.format(t, zone), durationSeconds = 60.0,
                laeqDb = 51.0, lafMaxDb = 66.0, lafMinDb = 40.0, l1Db = 63.0, l10Db = 56.0, l50Db = 50.0, l90Db = 44.0,
                eventCount = 1, dominantCategory = "road_traffic", categorySharesJson = """{"road_traffic":1.0}""",
                classifierFrames = 60, calibrationId = null, calibrationOffsetDb = 112.35, audioSource = "UNPROCESSED",
                calibrated = false, validSeconds = 60.0, coverage = 1.0, clockCorrections = 0,
            )
        }
        val events = (0 until 5).map { i ->
            val t = start + i * 5 * 60_000L + 12_345
            EventEntity(
                id = i + 1L, startEpochMs = t, startIso = Iso.format(t, zone, millis = true), durationSeconds = 3.0,
                lafMaxDb = 77.0, selDb = 79.0, backgroundDb = 45.0, thresholdDb = 10.0, dominantCategory = "loud_vehicle",
                dominantScore = 0.4f, topLabelsJson = "[]", classifierFrames = 3, calibrationId = null,
                audioSource = "UNPROCESSED", calibrated = false, minLevelDb = 30.0,
            )
        }
        var row = ch.lessnoise.app.data.MeasurementEntity(id = 1, startMs = start, endMs = null)
        val source = object : UploadSource {
            override suspend fun minutesAfter(afterMs: Long, limit: Int) = minutes.filter { it.startEpochMs > afterMs }.take(limit)
            override suspend fun eventsAfter(afterMs: Long, limit: Int) = events.filter { it.startEpochMs > afterMs }.take(limit)
            override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = false, calibrationOffsetDb = 112.35)
            override suspend fun measurements() = listOf(row)
        }
        val store = MemoryUploadStore()
        val up = Uploader(UploadClient(base!!), store, source, MemoryReportStore(), "0.6.3-dev", 16)
        up.enable()
        // Before the fix: registers, sends nothing, the markers move past the records.
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
        assertTrue(store.state.value.registered)
        // The fix arrives: the running measurement gets its shifted point.
        row = row.copy(cell = "h26833_12479", pointE = 2683351, pointN = 1247988, sigmaM = 50)
        up.pointAttached(start)
        assertEquals(Uploader.Outcome.Done(minutes = 25, events = 5, refused = 0, more = false), up.run())
        assertNull(store.state.value.lastError)
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
    }

    /**
     * 0.6.7: reports with and without a direction. Against a server with schema v6 all are stored
     * with what was sent; against one before v6 (STADTLAERM_TEST_SERVER_OLD=1) the reports with a
     * direction are sent again without it and none is lost.
     */
    @Test
    fun reportsWithAndWithoutDirectionArriveOnTheRealServer() = runBlocking {
        assumeTrue("STADTLAERM_TEST_SERVER not set", base != null)
        val old = System.getenv("STADTLAERM_TEST_SERVER_OLD") == "1"
        val now = System.currentTimeMillis() / 1000 * 1000 - 120_000
        val reports = MemoryReportStore()
        fun r(i: Int, d: ReportDirection?) = PendingReport(
            epochMs = now + i * 1000L, timeIso = PendingReport.isoSeconds(now + i * 1000L, zone),
            lat = 47.376912345, lon = 8.541694321, accuracyM = 12.34, category = "poser", cell = "h26833_12479", direction = d,
        )
        ReportQueue.add(reports, r(0, ReportDirection(87, ReportDirection.North.TRUE, ReportDirection.Accuracy.HIGH)))
        ReportQueue.add(reports, r(1, null))
        ReportQueue.add(reports, r(2, ReportDirection(359, ReportDirection.North.MAGNETIC, ReportDirection.Accuracy.UNRELIABLE)))
        val store = MemoryUploadStore()
        val source = object : UploadSource {
            override suspend fun minutesAfter(afterMs: Long, limit: Int) = emptyList<MinuteEntity>()
            override suspend fun eventsAfter(afterMs: Long, limit: Int) = emptyList<EventEntity>()
            override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = false, calibrationOffsetDb = 112.35)
            override suspend fun measurements() = emptyList<ch.lessnoise.app.data.MeasurementEntity>()
        }
        val up = Uploader(UploadClient(base!!), store, source, reports, "0.6.7-dev", 20)
        up.enable()
        val o = up.run()
        assertIs<Uploader.Outcome.Done>(o)
        assertEquals(3, o.reports, if (old) "old server" else "new server")
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(3, reports.state.value.sent.size)
        assertEquals(0, store.state.value.refusedTotal)
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
    }
}
