package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.app.ui.sentReportLine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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

/**
 * 0.9.4.5 (Labor·Karte first): only the place of a report is required — the category may be missing — and «Anzahl»
 * (`count`, 1–9) counts several vehicles. The payload, the queue on the phone (old entries read as before), the
 * «Meldungen» line, and a server older than schema v9 (FakeServer.beforeV9): `count` refused → sent again as one
 * vehicle; a missing category refused → kept queued unchanged, «Server zu alt – wird später gesendet».
 */
class ReportOptionalTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val server = FakeServer()
    private val now = ZonedDateTime.of(2026, 10, 28, 14, 23, 5, 0, zone).toInstant().toEpochMilli()

    @AfterTest
    fun stop() = server.close()

    private fun report(i: Int = 0, category: String? = "auto", count: Int = 1, direction: ReportDirection? = null) = PendingReport(
        epochMs = now + i * 1000L, timeIso = PendingReport.isoSeconds(now + i * 1000L, zone),
        lat = 47.37691, lon = 8.54169, accuracyM = 12.0, category = category, cell = "h26833_12479",
        direction = direction, count = count,
    )

    private val emptySource = object : UploadSource {
        override suspend fun minutesAfter(afterMs: Long, limit: Int) = emptyList<MinuteEntity>()
        override suspend fun eventsAfter(afterMs: Long, limit: Int) = emptyList<EventEntity>()
        override suspend fun measurements() = emptyList<MeasurementEntity>()
        override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = true, calibrationOffsetDb = 121.9)
    }

    private fun setup(reports: ReportStore): Pair<Uploader, MemoryUploadStore> {
        val store = MemoryUploadStore(UploadSnapshot(explanationSeen = true))
        val up = Uploader(UploadClient(server.baseUrl), store, emptySource, reports, "0.9.4.5-labor-karte", 50, clock = { now })
        assertTrue(up.enable())
        return up to store
    }

    // ---- the payload ------------------------------------------------------------------------------

    @Test
    fun noCategoryKeyWithoutACategoryAndACountOnlyAboveOne() {
        assertEquals(setOf("time", "lat", "lon", "accuracy_m", "category"), Payloads.report(report()).keys, "as 0.9.4.4")
        assertEquals(setOf("time", "lat", "lon", "accuracy_m"), Payloads.report(report(category = null)).keys)
        val three = Payloads.report(report(count = 3))
        assertEquals(3, three["count"]!!.jsonPrimitive.int)
        assertEquals(setOf("time", "lat", "lon", "accuracy_m", "count"), Payloads.report(report(category = null, count = 9)).keys)
        assertEquals("hupen", Payloads.report(report(category = ReportCategory.HUPEN.id))["category"]!!.jsonPrimitive.content)
        for (bad in listOf(0, 10, -1)) assertFailsWith<IllegalArgumentException> { report(count = bad) }
        assertEquals(9, ReportCategory.MAX_COUNT)
    }

    // ---- the queue on the phone -----------------------------------------------------------------------

    @Test
    fun theQueueKeepsNoCategoryAndTheCountAndReadsOldEntriesAsBefore() {
        val list = listOf(report(0), report(1, category = null), report(2, count = 4), report(3, category = null, count = 2))
        val json = ReportJson.pending(list)
        assertEquals(list, ReportJson.parsePending(json))
        // Written only when needed: no "cat" without a category, no "n" for one vehicle.
        assertFalse(ReportJson.pending(listOf(report(category = null))).contains("\"cat\""))
        assertFalse(ReportJson.pending(listOf(report())).contains("\"n\""))
        // An entry of 0.9.4.4 (always a category, never a count) reads as one vehicle of that category.
        val old = """[{"t":$now,"iso":"2026-10-28T14:23:05+01:00","lat":47.37691,"lon":8.54169,"acc":12.0,"cat":"motorrad","cell":"h26833_12479"}]"""
        val parsed = ReportJson.parsePending(old).single()
        assertEquals("motorrad", parsed.category)
        assertEquals(1, parsed.count)
        // A count out of range (hand-edited, or a future version) is read as one, the report is kept.
        val odd = old.replace("\"cell\"", "\"n\":12,\"cell\"")
        assertEquals(1, ReportJson.parsePending(odd).single().count)
        // Sent reports keep both, and old ones read as before.
        val sent = list.map { it.toSent() }
        assertEquals(sent, ReportJson.parseSent(ReportJson.sent(sent)))
        assertEquals(listOf(null, 1), ReportJson.parseSent("""[{"t":1,"cat":"auto"},{"t":2}]""").let { s -> listOf(s[1].category, s[0].count) })
    }

    @Test
    fun meldungenSaysOhneAngabeAndTheNumberOfVehicles() {
        assertEquals("12.10. 16:05 · Auto", sentReportLine("12.10. 16:05", report().toSent()))
        assertEquals("12.10. 16:05 · Ohne Angabe", sentReportLine("12.10. 16:05", report(category = null).toSent()))
        assertEquals("12.10. 16:05 · Auto · 3 Fahrzeuge", sentReportLine("12.10. 16:05", report(count = 3).toSent()))
        assertEquals("12.10. 16:05 · Gartengeräte", sentReportLine("12.10. 16:05", report(category = "garten").toSent()))
    }

    // ---- the server ---------------------------------------------------------------------------------

    @Test
    fun aV9ServerGetsEverything() = runBlocking {
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0, category = null))
        ReportQueue.add(reports, report(1, count = 3))
        val (up, store) = setup(reports)
        val o = up.run()
        assertIs<Uploader.Outcome.Done>(o)
        assertEquals(2, o.reports)
        val stored = server.reports[store.state.value.deviceId]!!.values.sortedBy { it["time"].toString() }
        assertFalse("category" in stored[0])
        assertEquals(3, stored[1]["count"]!!.jsonPrimitive.int)
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(listOf(null, "auto"), reports.state.value.sent.map { it.category })
        assertEquals(listOf(1, 3), reports.state.value.sent.map { it.count })
        assertNull(store.state.value.lastError)
    }

    @Test
    fun anOldServerGetsSeveralVehiclesAgainAsOneInsteadOfLosingTheReport() = runBlocking {
        server.beforeV9 = true
        server.rejectDirection = true // older still: direction refused too, both dropped in the same run
        val east = ReportDirection(87, ReportDirection.North.TRUE, ReportDirection.Accuracy.HIGH)
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0, count = 3))
        ReportQueue.add(reports, report(1, count = 2, direction = east))
        val (up, store) = setup(reports)
        assertIs<Uploader.Outcome.Done>(up.run())
        val stored = server.reports[store.state.value.deviceId]!!.values.toList()
        assertEquals(2, stored.size)
        assertTrue(stored.none { "count" in it || "direction" in it })
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(0, store.state.value.refusedTotal, "not counted as refused")
        assertEquals(2, server.count("POST", "/reports"), "one more request, in the same run")
        assertEquals(listOf(1, 1), reports.state.value.sent.map { it.count }, "the list keeps what the server got")
        assertNull(store.state.value.lastError)
    }

    @Test
    fun anOldServerCannotTakeAReportWithoutCategorySoItWaitsUnchanged() = runBlocking {
        server.beforeV9 = true
        val reports = MemoryReportStore()
        val none = report(0, category = null, count = 2)
        ReportQueue.add(reports, none)
        ReportQueue.add(reports, report(1))
        val (up, store) = setup(reports)
        val o = up.run()
        assertIs<Uploader.Outcome.Done>(o)
        assertEquals(1, o.reports, "the one with a category went out")
        assertEquals(listOf(none), reports.state.value.pending, "kept as it was: no category invented, count kept")
        assertEquals(listOf("auto"), reports.state.value.sent.map { it.category })
        assertEquals(1, server.count("POST", "/reports"), "not sent again in the same run")
        assertEquals(0, store.state.value.refusedTotal)
        assertEquals("Server zu alt – wird später gesendet", store.state.value.lastError)
        assertEquals(Uploader.SERVER_TOO_OLD_TEXT, store.state.value.lastError)
        // A later run against the same old server: still waiting, still one request per run.
        up.run()
        assertEquals(listOf(none), reports.state.value.pending)
        assertEquals(2, server.count("POST", "/reports"))
        // The server is updated (deployed from main): the report goes out with its count, the note is gone.
        server.beforeV9 = false
        up.run()
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(listOf("auto", null), reports.state.value.sent.map { it.category })
        assertEquals(2, reports.state.value.sent.last().count)
        assertNull(store.state.value.lastError)
    }

    @Test
    fun whichRefusalsAreRetriedOrHeld() {
        assertTrue(Uploader.onlyOptional(report(count = 3), listOf("count")))
        assertFalse(Uploader.onlyOptional(report(), listOf("count")), "one vehicle sends no count to drop")
        assertTrue(Uploader.serverTooOld(report(category = null), listOf("category")))
        assertTrue(Uploader.serverTooOld(report(category = null, count = 2), listOf("category", "count")))
        assertFalse(Uploader.serverTooOld(report(), listOf("category")), "an unknown id is refused, not held")
        assertFalse(Uploader.serverTooOld(report(category = null), listOf("lat")))
        // The status chip of the measurements does not turn into «abgelehnt» for it; «Meldungen» shows the line.
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val status = File(app, "src/main/kotlin/ch/lessnoise/app/ui/ShareStatus.kt").readText()
        assertTrue(status.contains("val error = s.lastError?.takeUnless { it == Uploader.SERVER_TOO_OLD_TEXT }"))
        val screen = File(app, "src/main/kotlin/ch/lessnoise/app/ui/ShareScreen.kt").readText()
        assertTrue(screen.contains("ReportsCard(reports, mediaPreview, waitingForServer = s.lastError == Uploader.SERVER_TOO_OLD_TEXT)"))
        assertTrue(screen.contains("if (r.pending.isNotEmpty() && waitingForServer) {"))
    }
}
