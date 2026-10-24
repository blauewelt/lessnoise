package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Laut! Melden» 0.6.7: the optional direction of travel — arithmetic, payload, queue, old servers. */
class ReportDirectionTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val server = FakeServer()
    private val now = ZonedDateTime.of(2026, 10, 17, 18, 5, 3, 0, zone).toInstant().toEpochMilli()

    @AfterTest
    fun stop() = server.close()

    private fun report(i: Int = 0, direction: ReportDirection? = null) = PendingReport(
        epochMs = now + i * 1000L, timeIso = PendingReport.isoSeconds(now + i * 1000L, zone),
        lat = 47.376912345, lon = 8.541694321, accuracyM = 12.34, category = "poser", cell = "h26833_12479",
        direction = direction,
    )

    private val east = ReportDirection(87, ReportDirection.North.TRUE, ReportDirection.Accuracy.HIGH)

    // ---- arithmetic ---------------------------------------------------------------------------

    @Test
    fun trueNorthAddsTheEastDeclination() {
        // Zürich 2026: declination ≈ +3° (east). Magnetic 84° → true 87°.
        val d = ReportDirection.of(84.0, 3.1, 3)!!
        assertEquals(87, d.bearingDeg)
        assertEquals(ReportDirection.North.TRUE, d.north)
        assertEquals(ReportDirection.Accuracy.HIGH, d.accuracy)
        // A west declination subtracts.
        assertEquals(81, ReportDirection.of(84.0, -3.0, 3)!!.bearingDeg)
    }

    @Test
    fun withoutDeclinationTheBearingStaysMagneticAndSaysSo() {
        val d = ReportDirection.of(84.4, null, 2)!!
        assertEquals(84, d.bearingDeg)
        assertEquals(ReportDirection.North.MAGNETIC, d.north)
        assertEquals(ReportDirection.North.MAGNETIC, ReportDirection.of(84.4, Double.NaN, 2)!!.north)
        assertNull(ReportDirection.of(Double.NaN, 3.0, 3))
        assertNull(ReportDirection.of(Double.POSITIVE_INFINITY, 3.0, 3))
    }

    @Test
    fun wrapAroundAndRoundingStayIn0To359() {
        assertEquals(1, ReportDirection.of(358.0, 3.0, 3)!!.bearingDeg)   // 361 → 1
        assertEquals(358, ReportDirection.of(1.0, -3.0, 3)!!.bearingDeg)  // −2 → 358
        assertEquals(0, ReportDirection.roundDeg(359.5))                  // rounds up to 360 → 0
        assertEquals(359, ReportDirection.roundDeg(359.49))
        assertEquals(0, ReportDirection.roundDeg(-0.4))
        assertEquals(359, ReportDirection.roundDeg(-0.6))
        assertEquals(0, ReportDirection.roundDeg(720.0))
        assertEquals(90, ReportDirection.roundDeg(-270.0))
        for (x in -1000..1000) assertTrue(ReportDirection.roundDeg(x * 0.73) in 0..359)
        assertFailsWith<IllegalArgumentException> { ReportDirection(360, ReportDirection.North.TRUE, ReportDirection.Accuracy.HIGH) }
        assertFailsWith<IllegalArgumentException> { ReportDirection(-1, ReportDirection.North.TRUE, ReportDirection.Accuracy.HIGH) }
    }

    @Test
    fun accuracyClassesAreAndroidsSensorStatus() {
        assertEquals(ReportDirection.Accuracy.HIGH, ReportDirection.Accuracy.ofSensorStatus(3))
        assertEquals(ReportDirection.Accuracy.MEDIUM, ReportDirection.Accuracy.ofSensorStatus(2))
        assertEquals(ReportDirection.Accuracy.LOW, ReportDirection.Accuracy.ofSensorStatus(1))
        assertEquals(ReportDirection.Accuracy.UNRELIABLE, ReportDirection.Accuracy.ofSensorStatus(0))
        assertEquals(ReportDirection.Accuracy.UNRELIABLE, ReportDirection.Accuracy.ofSensorStatus(-1)) // NO_CONTACT
        // The server's ids (server/stadtlaerm_server/schemas.py DIRECTION_ACCURACIES / DIRECTION_NORTHS).
        assertEquals(listOf("high", "medium", "low", "unreliable"), ReportDirection.Accuracy.entries.map { it.id })
        assertEquals(listOf("true", "magnetic"), ReportDirection.North.entries.map { it.id })
    }

    @Test
    fun compassPointLabels() {
        fun l(d: Int) = ReportDirection(d, ReportDirection.North.TRUE, ReportDirection.Accuracy.HIGH).label
        assertEquals("Richtung Nord", l(0))
        assertEquals("Richtung Nord", l(22))
        assertEquals("Richtung Nordost", l(23))
        assertEquals("Richtung Ost", l(87))
        assertEquals("Richtung Süd", l(180))
        assertEquals("Richtung Nordwest", l(315))
        assertEquals("Richtung Nord", l(338))
        assertEquals("Richtung Ost", east.label)
    }

    // ---- payload and queue --------------------------------------------------------------------

    @Test
    fun payloadWithoutDirectionIsExactlyTheOldOne() {
        val o = Payloads.report(report())
        assertEquals(setOf("time", "lat", "lon", "accuracy_m", "category"), o.keys)
    }

    @Test
    fun payloadWithDirectionAddsOneObjectOfThreeFields() {
        val o = Payloads.report(report(direction = ReportDirection(4, ReportDirection.North.MAGNETIC, ReportDirection.Accuracy.LOW)))
        assertEquals(setOf("time", "lat", "lon", "accuracy_m", "category", "direction"), o.keys)
        val d = o["direction"]!!.jsonObject
        assertEquals(setOf("bearing_deg", "north", "accuracy"), d.keys)
        assertEquals("4", d["bearing_deg"].toString(), "a whole number, not 4.0")
        assertEquals("magnetic", d["north"]!!.jsonPrimitive.content)
        assertEquals("low", d["accuracy"]!!.jsonPrimitive.content)
    }

    @Test
    fun theQueueKeepsTheDirectionUntilSentAndNotAfter() {
        val r = listOf(report(0, east), report(1))
        assertEquals(r, ReportJson.parsePending(ReportJson.pending(r)))
        // A queue written by 0.6.6 (no "dir") still reads.
        val old = """[{"t":$now,"iso":"x","lat":47.0,"lon":8.0,"acc":5.0,"cat":"auto"}]"""
        assertNull(ReportJson.parsePending(old).single().direction)
        // A garbled direction drops only the direction.
        val bad = """[{"t":$now,"iso":"x","lat":47.0,"lon":8.0,"acc":5.0,"cat":"auto","dir":{"deg":400,"n":"true","a":"high"}}]"""
        assertEquals("auto", ReportJson.parsePending(bad).single().category)
        assertNull(ReportJson.parsePending(bad).single().direction)
        // After sending only category, time and cell remain.
        assertEquals(SentReport(now, "poser", "h26833_12479"), report(0, east).toSent())
    }

    // ---- against the servers ------------------------------------------------------------------

    private val emptySource = object : UploadSource {
        override suspend fun minutesAfter(afterMs: Long, limit: Int) = emptyList<MinuteEntity>()
        override suspend fun eventsAfter(afterMs: Long, limit: Int) = emptyList<EventEntity>()
        override suspend fun measurements() = emptyList<MeasurementEntity>()
        override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = true, calibrationOffsetDb = 121.9)
    }

    private fun setup(reports: ReportStore): Pair<Uploader, MemoryUploadStore> {
        val store = MemoryUploadStore(UploadSnapshot(explanationSeen = true))
        val up = Uploader(UploadClient(server.baseUrl), store, emptySource, reports, "0.6.7-dev", 20, clock = { now })
        assertTrue(up.enable())
        return up to store
    }

    @Test
    fun aNewServerStoresTheDirection() = runBlocking {
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0, east))
        ReportQueue.add(reports, report(1))
        val (up, store) = setup(reports)
        assertIs<Uploader.Outcome.Done>(up.run())
        val stored = server.reports[store.state.value.deviceId]!!.values.toList()
        assertEquals(listOf(true, false), stored.map { "direction" in it })
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(2, reports.state.value.sent.size)
        assertEquals(1, server.count("POST", "/reports"))
    }

    @Test
    fun anOldServerGetsTheReportAgainWithoutTheDirectionInsteadOfLosingIt() = runBlocking {
        server.rejectDirection = true
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0, east))
        ReportQueue.add(reports, report(1))
        ReportQueue.add(reports, report(2, east))
        val (up, store) = setup(reports)
        val o = up.run()
        assertIs<Uploader.Outcome.Done>(o)
        assertEquals(3, o.reports)
        val stored = server.reports[store.state.value.deviceId]!!.values.toList()
        assertEquals(3, stored.size)
        assertTrue(stored.none { "direction" in it })
        assertTrue(reports.state.value.pending.isEmpty(), "nothing left, coordinates gone")
        assertEquals(listOf(now, now + 1000, now + 2000), reports.state.value.sent.map { it.epochMs }.sorted())
        assertEquals(0, store.state.value.refusedTotal, "not counted as refused")
        assertEquals(2, server.count("POST", "/reports"), "one more request, in the same run")
    }

    @Test
    fun aReportRefusedForAnotherReasonIsStillDroppedAsBefore() = runBlocking {
        server.rejectDirection = false
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0, east))
        server.refuseStarts += report(0).timeIso
        val (up, store) = setup(reports)
        up.run()
        assertTrue(reports.state.value.pending.isEmpty())
        assertTrue(reports.state.value.sent.isEmpty())
        assertEquals(1, store.state.value.refusedTotal)
        assertEquals(1, server.count("POST", "/reports"))
    }

    @Test
    fun onlyDirectionFieldsCountAsTheOldServersRefusal() {
        assertTrue(Uploader.onlyDirection(listOf("direction")))
        assertTrue(Uploader.onlyDirection(listOf("direction.bearing_deg", "direction")))
        assertFalse(Uploader.onlyDirection(emptyList()))
        assertFalse(Uploader.onlyDirection(listOf("direction", "lat")))
        assertFalse(Uploader.onlyDirection(listOf("directions")))
    }
}
