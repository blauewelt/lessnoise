package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.app.ui.REPORT_CONSENT_TEXT
import ch.lessnoise.app.ui.REPORT_SEND
import ch.lessnoise.app.ui.REPORT_SPOT_WARNING
import ch.lessnoise.app.ui.reportFixLine
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
        // 0.7.7: «Flugzeug» after «Lastwagen».
        assertEquals(listOf("motorrad", "auto", "lastwagen", "flugzeug", "poser", "andere"), ReportCategory.entries.map { it.id })
        // 0.6.4: «anderes Fahrzeug» (was «anderes»); the id stays «andere».
        assertEquals(listOf("Töff", "Auto", "Lastwagen", "Flugzeug", "Poser", "anderes Fahrzeug"), ReportCategory.entries.map { it.label })
        assertEquals("andere", ReportCategory.ANDERE.id)
        // What is sent is the id, never the label.
        assertEquals("andere", Payloads.report(report().copy(category = ReportCategory.ANDERE.id))["category"]!!.jsonPrimitive.content)
        for (c in ReportCategory.entries) assertEquals(c, ReportCategory.fromId(c.id))
        assertEquals(null, ReportCategory.fromId("velo"))
    }

    /** The server rejects a report whose category it does not know (server/stadtlaerm_server/schemas.py REPORT_CATEGORIES). */
    @Test
    fun serverAcceptsEveryCategoryInTheSameOrder() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        var schemas: java.io.File? = null
        while (dir != null && schemas == null) {
            schemas = java.io.File(dir, "server/stadtlaerm_server/schemas.py").takeIf { it.isFile }
            dir = dir.parentFile
        }
        val text = schemas!!.readText()
        val list = Regex("""REPORT_CATEGORIES\s*=\s*\(([^)]*)\)""").find(text)!!.groupValues[1]
        val accepted = Regex(""""([^"]+)"""").findAll(list).map { it.groupValues[1] }.toList()
        assertEquals(ReportCategory.entries.map { it.id }, accepted)
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
    fun aPoorFixIsReportedAsItIsWithItsAccuracy() {
        // 0.6.7: the owner's «Zu ungenau (± 107 m)» case. The fix becomes the report, the
        // dialog says «Standort ± 107 m» (0.8.1: the fix line of the one dialog), and the body
        // carries the accuracy as given.
        val fix = ch.lessnoise.app.location.LocationPolicy.decideReport(47.376912345, 8.541694321, 106.24, precise = true)
        assertIs<ch.lessnoise.app.location.LocationPolicy.ReportOutcome.Fix>(fix)
        assertEquals("Standort ± 107 m", reportFixLine(fix.accuracyText))
        val r = PendingReport(
            epochMs = now, timeIso = PendingReport.isoSeconds(now, zone),
            lat = fix.lat, lon = fix.lon, accuracyM = fix.accuracyM, category = ReportCategory.MOTORRAD.id, cell = fix.cell,
        )
        val o = Payloads.report(r)
        assertEquals("106.2", o["accuracy_m"].toString())
        assertEquals("47.37691", o["lat"].toString(), "the place as the phone has it, not shifted")
        // The flow (0.8.1, one dialog; 0.8.2 with «Senden»): a Fix with a selected category is sent; only a Problem shows
        // «Kein genauer Standort» (ReportFlowTest pins the decision itself).
        val ui = java.io.File("src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        assertTrue(ui.contains("ReportNext.SEND -> send(chosen!!, o as LocationPolicy.ReportOutcome.Fix, chosenReading, chosenLevel)"))
        assertTrue(ui.contains("ReportNext.FAILED -> step = ReportStep.Failed((o as LocationPolicy.ReportOutcome.Problem).outcome)"))
        // 0.7.0: the direction of travel (if read) goes with it; 0.7.6: and the licence plate (kept, never sent).
        // 0.8.1 (same build): the place is the fix, or the one moved under the dot (ReportFlowTest).
        assertTrue(ui.contains("val place = reportPlace(fix, moved)"))
        assertTrue(ui.contains("lat = place.lat, lon = place.lon, accuracyM = place.accuracyM,"))
        assertTrue(ui.contains("category = category.id, cell = place.cell, direction = direction, plate = plate, level = level,"))
        val policy = java.io.File("src/main/kotlin/ch/lessnoise/app/location/LocationPolicy.kt").readText()
        assertFalse(policy.contains("REPORT_MAX_ACCURACY_M"), "no accuracy limit for reports any more")
    }

    @Test
    fun textsAreShortAndSayWhatIsSent() {
        for (part in listOf("Zeit", "genauer Standort", "Art des Lärms", "nie Audio", "Lärmkarte", "gleichen Ort", "«Vom Server löschen»")) {
            assertTrue(REPORT_CONSENT_TEXT.contains(part), part)
        }
        assertFalse(REPORT_CONSENT_TEXT.contains('ß'))
        // DESIGN.md §2 rule 10: the report screen states the risk, on every report (0.6.3: the
        // consent above is skipped once sharing is on). Since 0.8.1 there is no separate confirmation: the
        // line is in the one «Laut! Melden» dialog, unconditionally, above «Senden» (0.8.2).
        assertEquals("Wer oft vom gleichen Ort meldet, macht ihn auf der Karte sichtbar.", REPORT_SPOT_WARNING)
        assertEquals("Senden", REPORT_SEND)
        val ui = java.io.File("src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        val dialog = ui.substringAfter("ReportStep.Report -> {").substringBefore("is ReportStep.Failed ->")
        val line = dialog.lines().single { it.contains("Text(REPORT_SPOT_WARNING") }
        assertTrue(line.trimStart().startsWith("Text(REPORT_SPOT_WARNING"), "shown on every report, not behind a condition: $line")
        assertTrue(dialog.contains("Text(REPORT_SEND)"), "0.8.2: the explicit «Senden»")
        assertFalse(ui.contains("Antippen sendet die Meldung."), "0.8.2: a tap no longer sends")
        assertFalse(ui.contains("reportConfirm"), "0.8.1: no separate confirmation dialog (owner's decision)")
    }

    // ---- 0.7.6: «Kontrollschild», on the phone only ------------------------------------------------

    @Test
    fun thePlateIsNormalisedAndCapped() {
        assertEquals("ZH 123", ReportPlate.normalize("  zh   123 "))
        assertEquals("ZH 123", ReportPlate.normalize("zh\t123"))
        assertEquals(null, ReportPlate.normalize("   "))
        assertEquals(null, ReportPlate.normalize(null))
        assertEquals("BE 123 456 A", ReportPlate.normalize("be 123 456 abc"), "at most 12 characters")
        assertEquals(12, ReportPlate.MAX_LENGTH)
        assertEquals("ZH 1", ReportPlate.typing("zh 1"))
        assertEquals(12, ReportPlate.typing("x".repeat(20)).length)
        // No trailing space when the cut lands on one.
        assertEquals("ABCDEFGHIJK", ReportPlate.normalize("abcdefghijk lmn"))
    }

    @Test
    fun thePlateIsStoredRoundTripsAndSurvivesSending() {
        val r = listOf(report(0).copy(plate = "ZH 123"), report(1))
        val json = ReportJson.pending(r)
        assertEquals(r, ReportJson.parsePending(json))
        assertTrue(json.contains("\"plate\":\"ZH 123\""), json)
        assertEquals(1, Regex("\"plate\"").findAll(json).count(), "absent when null")
        // Files written before 0.7.6 have no plate.
        assertEquals(listOf<String?>(null), ReportJson.parsePending("""[{"t":1,"iso":"x","lat":47.0,"lon":8.0,"acc":5.0,"cat":"auto"}]""").map { it.plate })
        val sent = r.map { it.toSent() }
        assertEquals(listOf<String?>("ZH 123", null), sent.map { it.plate })
        assertEquals(sent, ReportJson.parseSent(ReportJson.sent(sent)))
        assertEquals(1, Regex("\"plate\"").findAll(ReportJson.sent(sent)).count())
    }

    @Test
    fun thePlateIsNeverSent() = runBlocking {
        val r = report().copy(plate = "ZH 123")
        val o = Payloads.report(r)
        assertEquals(setOf("time", "lat", "lon", "accuracy_m", "category"), o.keys)
        assertFalse(o.toString().contains("plate") || o.toString().contains("ZH 123"), o.toString())
        // Through the real upload: what the server receives has no plate; the phone keeps it.
        val reports = MemoryReportStore()
        ReportQueue.add(reports, r)
        val (up, _) = setup(reports)
        assertIs<Uploader.Outcome.Done>(up.run())
        val bodies = server.requests.map { it.body.toString() }
        assertTrue(server.requests.any { it.path.endsWith("/reports") })
        for (b in bodies) assertFalse(b.contains("plate") || b.contains("ZH 123"), b)
        assertEquals(listOf<String?>("ZH 123"), reports.state.value.sent.map { it.plate })
    }
}
