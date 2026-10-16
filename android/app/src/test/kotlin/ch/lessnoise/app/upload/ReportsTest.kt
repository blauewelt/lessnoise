package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.app.ui.REPORT_CONSENT_TEXT
import ch.lessnoise.app.ui.REPORT_SPOT_WARNING
import ch.lessnoise.app.ui.reportConfirmDialogText
import ch.lessnoise.app.ui.reportConfirmText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** «Laut! Melden» (app 0.6.0): categories, payload, queue, and that coordinates leave the phone after sending. */
class ReportsTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val server = FakeServer()
    private val now = ZonedDateTime.of(2026, 10, 12, 16, 5, 3, 789_000_000, zone).toInstant().toEpochMilli()

    @AfterTest
    fun stop() = server.close()

    private val emptySource = object : UploadSource {
        override suspend fun minutesAfter(afterMs: Long, limit: Int) = emptyList<MinuteEntity>()
        override suspend fun eventsAfter(afterMs: Long, limit: Int) = emptyList<EventEntity>()
        override suspend fun measurements() = emptyList<MeasurementEntity>()
        override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = true, calibrationOffsetDb = 121.9)
    }

    private fun report(i: Int = 0, category: ReportCategory = ReportCategory.MOTORRAD) = PendingReport(
        epochMs = now + i * 1000L, timeIso = PendingReport.isoSeconds(now + i * 1000L, zone),
        lat = 47.376912345, lon = 8.541694321, accuracyM = 12.34, category = category.id, cell = "h26833_12479",
    )

    private fun setup(reports: ReportStore): Pair<Uploader, MemoryUploadStore> {
        val store = MemoryUploadStore(UploadSnapshot(explanationSeen = true))
        val up = Uploader(UploadClient(server.baseUrl), store, emptySource, reports, "0.6.0-dev", 13, clock = { now })
        assertTrue(up.enable())
        return up to store
    }

    @Test
    fun categoriesMapToTheServersIdsAndShortLabels() {
        assertEquals(listOf("motorrad", "auto", "lastwagen", "poser", "andere"), ReportCategory.entries.map { it.id })
        // 0.6.4: «anderes Fahrzeug» (was «anderes»); the id stays «andere».
        assertEquals(listOf("Töff", "Auto", "Lastwagen", "Poser", "anderes Fahrzeug"), ReportCategory.entries.map { it.label })
        assertEquals("andere", ReportCategory.ANDERE.id)
        val t = java.time.ZonedDateTime.of(2026, 10, 16, 18, 42, 5, 0, java.time.ZoneId.of("Europe/Zurich")).toInstant().toEpochMilli()
        assertEquals(
            "Meldung: anderes Fahrzeug, 18:42, Standort ± 12 m – senden?",
            ch.lessnoise.app.ui.reportConfirmText(ReportCategory.ANDERE, t, 12, java.time.ZoneId.of("Europe/Zurich")),
        )
        // What is sent is the id, never the label.
        assertEquals("andere", Payloads.report(report().copy(category = ReportCategory.ANDERE.id))["category"]!!.jsonPrimitive.content)
        for (c in ReportCategory.entries) assertEquals(c, ReportCategory.fromId(c.id))
        assertEquals(null, ReportCategory.fromId("velo"))
    }

    @Test
    fun payloadIsExactlyTheAgreedContract() {
        val r = report()
        assertEquals("2026-10-12T16:05:03+02:00", r.timeIso, "ISO-8601 with offset, second precision (cut, not rounded)")
        val o = Payloads.report(r)
        assertEquals(setOf("time", "lat", "lon", "accuracy_m", "category"), o.keys, "no cell, no device details")
        assertEquals("2026-10-12T16:05:03+02:00", o["time"]!!.jsonPrimitive.content)
        assertEquals("47.37691", o["lat"].toString(), "5 decimals")
        assertEquals("8.54169", o["lon"].toString(), "5 decimals")
        assertEquals("12.3", o["accuracy_m"].toString())
        assertEquals("motorrad", o["category"]!!.jsonPrimitive.content)
        // Winter time: +01:00.
        val jan = ZonedDateTime.of(2026, 1, 5, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026-01-05T07:00:00+01:00", PendingReport.isoSeconds(jan, zone))
    }

    @Test
    fun aSentReportKeepsOnlyCategoryTimeAndCellOnThePhone() = runBlocking {
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0, ReportCategory.POSER))
        ReportQueue.add(reports, report(1, ReportCategory.LASTWAGEN))
        val (up, store) = setup(reports)
        val o = up.run()
        assertIs<Uploader.Outcome.Done>(o)
        assertEquals(2, o.reports)
        val id = store.state.value.deviceId!!
        assertEquals(2, server.reports[id]!!.size)
        val req = server.requests.single { it.path.endsWith("/reports") }
        assertEquals("Bearer ${server.tokens[id]}", req.headers["authorization"])
        assertEquals("gzip", req.headers["content-encoding"])
        assertEquals("poser", server.reports[id]!!.values.first().jsonObject["category"]!!.jsonPrimitive.content)
        // Coordinates are gone: nothing pending, the sent list has category, time and cell only.
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(
            listOf(SentReport(now, "poser", "h26833_12479"), SentReport(now + 1000, "lastwagen", "h26833_12479")),
            reports.state.value.sent,
        )
        val stored = ReportJson.sent(reports.state.value.sent)
        assertFalse(stored.contains("47.37") || stored.contains("8.54") || stored.contains("lat"), stored)
        // Nothing is sent twice.
        up.run()
        assertEquals(1, server.count("POST", "/reports"))
    }

    @Test
    fun atMostFiftyReportsPerRequest() = runBlocking {
        val reports = MemoryReportStore()
        repeat(120) { ReportQueue.add(reports, report(it)) }
        val (up, store) = setup(reports)
        up.run()
        assertEquals(3, server.count("POST", "/reports"))
        assertEquals(120, server.reports[store.state.value.deviceId]!!.size)
        assertTrue(reports.state.value.pending.isEmpty())
    }

    @Test
    fun aServerWithoutTheReportsCallKeepsThemAndStillSendsMeasurements() = runBlocking {
        server.reportsAvailable = false
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report())
        val (up, _) = setup(reports)
        assertIs<Uploader.Outcome.Done>(up.run())
        assertEquals(1, reports.state.value.pending.size, "kept for a later run")
        server.reportsAvailable = true
        up.run()
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(1, reports.state.value.sent.size)
    }

    @Test
    fun aRefusedBatchIsDroppedInsteadOfBlockingEveryUpload() = runBlocking {
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report())
        val (up, store) = setup(reports)
        server.failNext += Triple("POST", "/reports", 422)
        assertIs<Uploader.Outcome.Done>(up.run())
        assertTrue(reports.state.value.pending.isEmpty(), "coordinates gone")
        assertTrue(reports.state.value.sent.isEmpty(), "a refused report was not sent")
        assertEquals(1, store.state.value.refusedTotal)
    }

    @Test
    fun aRejectedReportLeavesTheQueueButIsNotListedAsSent() = runBlocking {
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0))
        ReportQueue.add(reports, report(1))
        server.refuseStarts += report(1).timeIso
        val (up, _) = setup(reports)
        up.run()
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(listOf(now), reports.state.value.sent.map { it.epochMs })
    }

    @Test
    fun switchingOffOrDeletingDropsPendingReportsWithTheirCoordinates() = runBlocking {
        val reports = MemoryReportStore()
        val (up, _) = setup(reports)
        up.run() // registered
        ReportQueue.add(reports, report())
        up.disable()
        assertTrue(reports.state.value.pending.isEmpty())

        assertTrue(up.enable())
        ReportQueue.add(reports, report(1))
        server.reportsAvailable = false
        up.run()
        assertEquals(1, reports.state.value.pending.size)
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
        assertTrue(reports.state.value.pending.isEmpty())
    }

    @Test
    fun theQueueSurvivesARestartAndAGarbledFileIsEmpty() {
        val r = listOf(report(0), report(1, ReportCategory.ANDERE).copy(cell = null))
        assertEquals(r, ReportJson.parsePending(ReportJson.pending(r)))
        val sent = r.map { it.toSent() }
        assertEquals(sent, ReportJson.parseSent(ReportJson.sent(sent)))
        assertEquals(emptyList(), ReportJson.parsePending("{garbled"))
        assertEquals(emptyList(), ReportJson.parsePending(null))
    }

    @Test
    fun textsAreShortAndSayWhatIsSent() {
        assertEquals("Meldung: Töff, 16:05, Standort ± 12 m – senden?", reportConfirmText(ReportCategory.MOTORRAD, now, 12, zone))
        for (part in listOf("Zeit", "genauer Standort", "Art des Lärms", "nie Audio", "Lärmkarte", "gleichen Ort", "«Vom Server löschen»")) {
            assertTrue(REPORT_CONSENT_TEXT.contains(part), part)
        }
        assertFalse(REPORT_CONSENT_TEXT.contains('ß'))
        // DESIGN.md §2 rule 10: the report screen states the risk, on every confirmation (0.6.3:
        // the consent above is skipped once sharing is on).
        assertEquals("Wer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar.", REPORT_SPOT_WARNING)
        assertEquals(
            "Meldung: Töff, 16:05, Standort ± 12 m – senden?\nWer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar.",
            reportConfirmDialogText(ReportCategory.MOTORRAD, now, 12, zone),
        )
        val ui = java.io.File("src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        assertTrue(ui.contains("Text(reportConfirmDialogText("), "the «Meldung» dialog shows the warning")
        assertFalse(ui.contains("Text(reportConfirmText("), "no confirmation without the warning")
    }
}
