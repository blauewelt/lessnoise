package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.dsp.Iso
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The opt-in upload against a fake server on 127.0.0.1 (server/DESIGN.md §4, server/README.md). */
class UploadTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val server = FakeServer()
    private var now = ZonedDateTime.of(2026, 10, 8, 7, 5, 0, 0, zone).toInstant().toEpochMilli()

    @AfterTest
    fun stop() = server.close()

    // ---- fixtures ------------------------------------------------------------------------

    private val minuteRows = mutableListOf<MinuteEntity>()
    private val eventRows = mutableListOf<EventEntity>()

    /** Shifted points as LocationPolicy makes them at «Start» (their display cells: h26824_12473, h26833_12479). */
    private val pointA = LocationNoise.Point(2682437, 1247391, 50)
    private val pointC = LocationNoise.Point(2683351, 1247988, 100)

    private fun shared(id: Long, startMs: Long, endMs: Long?, p: LocationNoise.Point) =
        MeasurementEntity(id = id, startMs = startMs, endMs = endMs, cell = p.cell, pointE = p.e, pointN = p.n, sigmaM = p.sigmaM)

    /** By default one shared measurement covering every record; tests add others. */
    private val measurementRows = mutableListOf(shared(1, 0, null, pointA))

    private fun JsonObject.point(): Triple<Int, Int, Int> = this["point"]!!.jsonObject.let {
        Triple(it["e"]!!.jsonPrimitive.content.toInt(), it["n"]!!.jsonPrimitive.content.toInt(), it["sigma_m"]!!.jsonPrimitive.content.toInt())
    }

    private val a = Triple(pointA.e, pointA.n, 50)
    private val c = Triple(pointC.e, pointC.n, 100)
    private val reports = MemoryReportStore()

    private val source = object : UploadSource {
        override suspend fun minutesAfter(afterMs: Long, limit: Int) =
            minuteRows.filter { it.startEpochMs > afterMs }.sortedWith(compareBy({ it.startEpochMs }, { it.id })).take(limit)

        override suspend fun eventsAfter(afterMs: Long, limit: Int) =
            eventRows.filter { it.startEpochMs > afterMs }.sortedWith(compareBy({ it.startEpochMs }, { it.id })).take(limit)

        override suspend fun measurements() = measurementRows.toList()

        override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = true, calibrationOffsetDb = 121.9)
    }

    private fun minute(startMs: Long, laeq: Double? = 42.34, id: Long = minuteRows.size + 1L) = MinuteEntity(
        id = id, startEpochMs = startMs, startIso = Iso.format(startMs, zone), durationSeconds = 60.0,
        laeqDb = laeq, lafMaxDb = 61.04, lafMinDb = 33.1, l1Db = 55.0, l10Db = 47.2, l50Db = 41.0, l90Db = 36.4,
        eventCount = 2, dominantCategory = "road_traffic",
        categorySharesJson = """{"road_traffic":0.41,"loud_vehicle":0.05,"unclassified":0.54}""",
        classifierFrames = 60, calibrationId = 7, calibrationOffsetDb = 121.9, audioSource = "UNPROCESSED",
        calibrated = true, validSeconds = 59.5, coverage = 59.5 / 60, clockCorrections = 0,
        origLaeqDb = 30.0, recalibratedFromId = "default", recalibrationOffsetDb = 112.35,
    )

    private fun event(startMs: Long, category: String? = "loud_vehicle", id: Long = eventRows.size + 1L) = EventEntity(
        id = id, startEpochMs = startMs, startIso = Iso.format(startMs, zone, millis = true), durationSeconds = 2.75,
        lafMaxDb = 71.44, selDb = 74.0, backgroundDb = 38.1, thresholdDb = 10.0, dominantCategory = category,
        dominantScore = 0.31f, topLabelsJson = """[{"label":"Motorcycle","score":0.31}]""", classifierFrames = 3,
        calibrationId = 7, audioSource = "UNPROCESSED", calibrated = true, minLevelDb = 30.0,
    )

    /** [n] consecutive minutes ending one hour before now. */
    private fun addMinutes(n: Int, endMs: Long = now - 3_600_000) {
        val first = endMs - n * 60_000L
        repeat(n) { minuteRows += minute(first + it * 60_000L) }
    }

    private fun addEvents(n: Int, endMs: Long = now - 3_600_000) {
        val first = endMs - n * 7_000L
        repeat(n) { eventRows += event(first + it * 7_000L + 375) }
    }

    private val site = SiteSettings(placement = "open_window", floor = 3, streetFacing = true, note = "2. OG")

    private fun setup(enabled: Boolean = true): Pair<Uploader, MemoryUploadStore> {
        val store = MemoryUploadStore(UploadSnapshot(explanationSeen = true, site = site))
        val up = Uploader(UploadClient(server.baseUrl), store, source, reports, "0.4.0-dev", 9, clock = { now })
        if (enabled) assertTrue(up.enable())
        return up to store
    }

    // ---- tests ---------------------------------------------------------------------------

    @Test
    fun registersSendsSiteMinutesAndEventsAsGzipJsonWithBearerToken() = runBlocking {
        addMinutes(90); addEvents(12)
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 90, events = 12, refused = 0, more = false), o)

        val s = store.state.value
        assertTrue(s.registered)
        assertEquals(minuteRows.last().startEpochMs, s.minutesSentUpToMs)
        assertEquals(eventRows.last().startEpochMs, s.eventsSentUpToMs)
        assertEquals(now, s.lastSuccessAtMs)
        assertNull(s.lastError)

        val reqs = server.requests
        assertEquals(listOf("POST /v1/devices", "PUT /site", "POST /minutes", "POST /events"),
            reqs.map { r -> "${r.method} " + if (r.path == "/v1/devices") r.path else r.path.substringAfterLast("/").let { "/$it" } })
        assertNull(reqs[0].headers["authorization"], "registration carries no token")
        for (r in reqs.drop(1)) assertEquals("Bearer ${server.tokens[s.deviceId]}", r.headers["authorization"])
        for (r in reqs) {
            assertEquals("gzip", r.headers["content-encoding"])
            assertEquals("LessNoise", r.headers["user-agent"], "no device details in the User-Agent")
        }
        assertEquals("""{"app_version":"0.4.0-dev","app_build":9}""", reqs[0].body.toString())
        assertEquals(90, server.minutes[s.deviceId]!!.size)
        assertEquals(12, server.events[s.deviceId]!!.size)
    }

    @Test
    fun recordsOfAMeasurementWithoutPointAreNeverSentAndEachRecordCarriesItsOwnMeasurementsPoint() = runBlocking {
        // Three measurements: A shared (point a), B not shared, C shared elsewhere (point c); a gap
        // after A's stop that belongs to no running measurement.
        val t0 = now - 6 * 3_600_000L
        val aEnd = t0 + 30 * 60_000L
        val bStart = t0 + 60 * 60_000L
        val cStart = t0 + 120 * 60_000L
        measurementRows.clear()
        measurementRows += shared(1, t0, aEnd, pointA)
        measurementRows += MeasurementEntity(id = 2, startMs = bStart, endMs = bStart + 30 * 60_000L, cell = null)
        measurementRows += shared(3, cStart, null, pointC)
        for (i in 0 until 30) minuteRows += minute(t0 + i * 60_000L)              // A
        minuteRows += minute(aEnd + 5 * 60_000L)                                    // after A's stop, before B: no measurement
        for (i in 0 until 30) minuteRows += minute(bStart + i * 60_000L)          // B
        for (i in 0 until 30) minuteRows += minute(cStart + i * 60_000L)          // C
        eventRows += event(t0 + 1_000L); eventRows += event(bStart + 1_000L); eventRows += event(cStart + 1_000L)
        val (up, store) = setup()
        assertEquals(Uploader.Outcome.Done(minutes = 60, events = 2, refused = 0, more = false), up.run())
        val id = store.state.value.deviceId!!
        val sent = server.minutes[id]!!.values
        assertEquals(30, sent.count { it.point() == a })
        assertEquals(30, sent.count { it.point() == c })
        assertTrue(sent.none { "cell" in it }, "the true hectare is never sent")
        assertEquals(setOf(a, c), server.events[id]!!.values.map { it.point() }.toSet())
        // The markers moved past the unshared records: they are never sent later either.
        assertEquals(minuteRows.maxOf { it.startEpochMs }, store.state.value.minutesSentUpToMs)
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
        // The site call carries the newest shared measurement's point.
        assertEquals(c, server.sites[id]!!.point())
    }

    @Test
    fun aMeasurementWithACellButNoPointIsNotSentAnyMore() = runBlocking {
        // A row of 0.6.0/0.6.1 before the location noise: the true hectare, no point.
        measurementRows.clear()
        measurementRows += MeasurementEntity(id = 1, startMs = 0, endMs = null, cell = "h26833_12479")
        addMinutes(20); addEvents(3)
        val (up, _) = setup()
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
        assertEquals(listOf("POST /v1/devices"), server.requests.map { "${it.method} ${it.path}" })
    }

    @Test
    fun withoutAnySharedMeasurementNothingButTheRegistrationIsSent() = runBlocking {
        measurementRows.clear()
        measurementRows += MeasurementEntity(id = 1, startMs = 0, endMs = null, cell = null)
        addMinutes(20); addEvents(3)
        val (up, _) = setup()
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
        assertEquals(listOf("POST /v1/devices"), server.requests.map { "${it.method} ${it.path}" })
    }

    @Test
    fun placeIndexFindsTheOwnerMeasurement() {
        val idx = PlaceIndex(
            listOf(
                MeasurementEntity(id = 2, startMs = 10_000, endMs = 20_000, cell = null),
                shared(1, 0, 5_000, pointA),
                shared(3, 30_000, null, pointC),
                // A cell without a point (0.6.0/0.6.1, before the noise) counts as not shared.
                MeasurementEntity(id = 4, startMs = 40_000_000, endMs = null, cell = "h26833_12479"),
            )
        )
        assertNull(idx.pointAt(-10_000), "before the first measurement")
        assertEquals(pointA, idx.pointAt(-4_000), "first minute may start a few seconds before the recorded start")
        assertEquals(pointA, idx.pointAt(4_999))
        assertNull(idx.pointAt(5_000), "after its stop")
        assertNull(idx.pointAt(15_000), "not shared")
        assertEquals(pointC, idx.pointAt(10_000_000), "running")
        assertNull(idx.pointAt(40_000_001), "cell only")
        assertEquals(pointC, idx.latest)
    }

    @Test
    fun siteBodyIsExactlyDesign42AndCarriesTheNewestMeasurementsPoint() = runBlocking {
        val (up, store) = setup()
        up.run()
        val body = server.sites[store.state.value.deviceId]!!
        assertEquals(
            setOf("point", "placement", "floor", "street_facing", "device_model", "audio_source", "calibrated", "calibration_offset_db", "note"),
            body.keys,
        )
        assertEquals("""{"e":2682437,"n":1247391,"sigma_m":50}""", body["point"].toString())
        assertEquals("open_window", body["placement"]!!.jsonPrimitive.content)
        assertEquals("3", body["floor"].toString())
        assertEquals("true", body["street_facing"].toString())
        assertEquals("Pixel 8", body["device_model"]!!.jsonPrimitive.content)
        assertEquals("121.9", body["calibration_offset_db"].toString())
        assertEquals("2. OG", body["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun minuteAndEventBodiesFollowDesignAndLeaveOutLabelsAndOriginals() = runBlocking {
        minuteRows += minute(now - 7_200_000, laeq = Double.NaN)
        eventRows += event(now - 7_100_000, category = null)
        val (up, store) = setup()
        up.run()
        val id = store.state.value.deviceId!!
        val m = server.minutes[id]!!.values.single()
        assertEquals(
            setOf(
                "start", "duration_s", "valid_s", "coverage", "laeq_db", "lafmax_db", "lafmin_db", "l1_db", "l10_db", "l50_db",
                "l90_db", "event_count", "dominant_category", "category_shares", "calibration_id", "calibration_offset_db",
                "calibrated", "audio_source", "clock_corrections", "point",
            ),
            m.keys,
        )
        assertEquals("""{"e":2682437,"n":1247391,"sigma_m":50}""", m["point"].toString(), "the measurement's shifted point, integers")
        assertEquals(JsonNull, m["laeq_db"], "NaN is sent as null, never as invalid JSON")
        assertEquals("61.0", m["lafmax_db"].toString(), "one decimal")
        assertEquals("0.992", m["coverage"].toString())
        assertEquals("\"7\"", m["calibration_id"].toString(), "calibration id as text (server: ShortText)")
        assertTrue(m["start"]!!.jsonPrimitive.content.endsWith("+02:00"), "ISO-8601 with offset")
        assertEquals(setOf("road_traffic", "loud_vehicle", "unclassified"), m["category_shares"]!!.jsonObject.keys)

        val e = server.events[id]!!.values.single()
        assertEquals(
            setOf("start", "duration_s", "lafmax_db", "sel_db", "background_db", "threshold_db", "min_level_db", "category",
                "category_score", "calibration_id", "calibrated", "point"),
            e.keys,
        )
        assertEquals(a, e.point())
        assertEquals("unclassified", e["category"]!!.jsonPrimitive.content, "no category → unclassified")
        assertTrue(e["start"]!!.jsonPrimitive.content.contains("."), "event start with milliseconds")
        // Nothing of the raw classifier labels or the re-evaluation originals leaves the phone.
        for (r in server.requests) {
            val text = r.body.toString()
            assertFalse(text.contains("Motorcycle") || text.contains("label") || text.contains("orig_") || text.contains("recalibrat"), text)
        }
    }

    @Test
    fun windEventsAreNotSentAndMinuteEventCountExcludesWind() = runBlocking {
        // Detector v2: a minute with 1 counted event and 3 wind events; the minute's eventCount is
        // already the count without wind (what the app shows), windEventCount is not sent.
        minuteRows += minute(now - 7_200_000).copy(eventCount = 1, windEventCount = 3, localFloorDb = 35.2)
        eventRows += event(now - 7_190_000).copy(localFloorDb = 35.0, excessDb = 6.5, lfShare = 0.4, shape = "hump")
        eventRows += event(now - 7_180_000, category = "wind").copy(wind = true, lfShare = 0.97, lfFlutterDb = 5.0, shape = "impulse")
        eventRows += event(now - 7_170_000, category = "wind").copy(wind = true, lfShare = 0.95, shape = "jagged")
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 1, events = 1, refused = 0, more = false), o)
        val id = store.state.value.deviceId!!
        val m = server.minutes[id]!!.values.single()
        assertEquals("1", m["event_count"].toString(), "event_count without wind")
        val e = server.events[id]!!.values.single()
        assertEquals("loud_vehicle", e["category"]!!.jsonPrimitive.content)
        assertEquals(1, server.events[id]!!.size, "wind events are not sent")
        // The marker moves past the wind events, so they are not fetched again.
        assertEquals(eventRows.last().startEpochMs, store.state.value.eventsSentUpToMs)
        // No detector-v2 feature leaks into a body.
        for (r in server.requests.filter { it.path.endsWith("/minutes") || it.path.endsWith("/events") }) {
            val text = r.body.toString()
            for (k in listOf("\"wind", "local_floor", "excess", "lf_share", "lf_flutter", "shape", "rise_s", "decay_s", "jagged", "mid_band"))
                assertFalse(text.contains(k), "$k in $text")
        }
    }

    @Test
    fun aBatchOfOnlyWindEventsSendsNoEventRequest() = runBlocking {
        addMinutes(5)
        repeat(4) { eventRows += event(now - 7_000_000 + it * 5_000L, category = "wind").copy(wind = true, shape = "jagged") }
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 5, events = 0, refused = 0, more = false), o)
        assertEquals(0, server.count("POST", "/events"))
        assertEquals(eventRows.last().startEpochMs, store.state.value.eventsSentUpToMs)
        assertFalse(Payloads.isShared(eventRows.first()))
        assertTrue(Payloads.isShared(event(now - 1)))
    }

    @Test
    fun batchesAreAtMost1440MinutesAnd2000Events() = runBlocking {
        addMinutes(3000, endMs = now - 3_600_000)
        addEvents(2500)
        val (up, store) = setup()
        val o = up.run()
        assertIs<Uploader.Outcome.Done>(o)
        val minuteBatches = server.requests.filter { it.path.endsWith("/minutes") }.map { it.body!!.jsonArray.size }
        val eventBatches = server.requests.filter { it.path.endsWith("/events") }.map { it.body!!.jsonArray.size }
        assertTrue(minuteBatches.all { it <= 1440 }, "$minuteBatches")
        assertTrue(eventBatches.all { it <= 2000 }, "$eventBatches")
        // 3000 minutes = 50 h; only the last 7 days count, all of them are inside.
        assertEquals(3000, minuteBatches.sum())
        assertEquals(2500, eventBatches.sum())
        assertEquals(3000, server.minutes[store.state.value.deviceId]!!.size)
    }

    @Test
    fun repeatedUploadIsCountedAsDuplicatesAndStoresNothingTwice() = runBlocking {
        addMinutes(30); addEvents(5)
        val (up, store) = setup()
        up.run()
        up.rewindAfterReevaluation() // as after «Alte Messungen neu bewerten»
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 30, events = 5, refused = 0, more = false), o)
        assertEquals(30, server.minutes[store.state.value.deviceId]!!.size)
        assertEquals(2, server.count("POST", "/minutes"))
        assertEquals(1, server.count("POST", "/v1/devices"), "registered once")
        assertEquals(1, server.count("PUT", "/site"), "unchanged site is not sent again")
    }

    @Test
    fun resumesFromTheLastAcknowledgedStartAfterAPartialFailure() = runBlocking {
        addMinutes(3000)
        val (up, store) = setup()
        // First batch succeeds, second fails with a server error.
        var calls = 0
        val failing = object : UploadSource by source {
            override suspend fun minutesAfter(afterMs: Long, limit: Int): List<MinuteEntity> {
                calls++
                if (calls == 2) server.failNext += Triple("POST", "/minutes", 503)
                return source.minutesAfter(afterMs, limit)
            }
        }
        val up2 = Uploader(UploadClient(server.baseUrl), store, failing, reports, "0.4.0-dev", 9, clock = { now })
        val first = up2.run()
        assertIs<Uploader.Outcome.Retry>(first)
        val marker = store.state.value.minutesSentUpToMs
        assertEquals(minuteRows[1439].startEpochMs, marker, "marker = last start of the acknowledged batch")
        assertEquals(Uploader.RETRY_TEXT, store.state.value.lastError)

        val second = up.run()
        assertIs<Uploader.Outcome.Done>(second)
        val id = store.state.value.deviceId!!
        assertEquals(3000, server.minutes[id]!!.size)
        // Every start was sent successfully exactly once (the failed request stored nothing).
        val okStarts = server.requests.filter { it.path.endsWith("/minutes") }.drop(0)
            .flatMap { r -> r.body!!.jsonArray.map { it.jsonObject["start"]!!.jsonPrimitive.content } }
        assertEquals(3000 + 1440, okStarts.size, "the failed batch was resent once, nothing else")
        assertNull(store.state.value.lastError)
    }

    @Test
    fun rejectedRecordsAreMarkedAsRefusedAndNeverSentAgain() = runBlocking {
        addMinutes(10); addEvents(3)
        server.refuseStarts += minuteRows[3].startIso
        server.refuseStarts += eventRows[1].startIso
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 9, events = 2, refused = 2, more = false), o)
        val s = store.state.value
        assertEquals(2, s.refusedTotal)
        assertEquals(
            setOf(RefusedRecord.MINUTE to minuteRows[3].startEpochMs, RefusedRecord.EVENT to eventRows[1].startEpochMs),
            s.refused.map { it.kind to it.startEpochMs }.toSet(),
        )
        assertTrue(s.refused.first().reason.contains("out of range"))
        assertEquals(minuteRows.last().startEpochMs, s.minutesSentUpToMs, "the batch counts as done")

        up.rewindAfterReevaluation()
        up.run()
        val lastMinutes = server.requests.last { it.path.endsWith("/minutes") }.body!!.jsonArray
        assertEquals(9, lastMinutes.size)
        assertFalse(lastMinutes.any { it.jsonObject["start"]!!.jsonPrimitive.content == minuteRows[3].startIso })
    }

    @Test
    fun aRefusedTokenStopsUploadingUntilNewIdentity() = runBlocking {
        addMinutes(5)
        val (up, store) = setup()
        up.run()
        val oldId = store.state.value.deviceId!!
        server.tokens[oldId] = "something-else" // the server no longer accepts our token
        minuteRows += minute(now - 1_800_000)
        assertEquals(Uploader.Outcome.AuthFailed, up.run())
        assertTrue(store.state.value.authFailed)
        assertTrue(store.state.value.lastError!!.contains("«Neu starten»"))
        assertTrue(store.state.value.lastError!!.contains("60 Tage"), "explains the likely reason: expiry on the server")
        val before = server.requests.size
        assertEquals(Uploader.Outcome.AuthFailed, up.run())
        assertEquals(before, server.requests.size, "no requests while the token is refused")

        // «Neue Kennung»: the DELETE is refused (401) too, so the old id is simply forgotten.
        val oldSecret = NoiseSecret.getOrCreate(store)
        store.update { it.copy(sigmaM = 200) }
        assertEquals(Uploader.DeleteOutcome.UnknownToServer, up.newIdentity())
        val s = store.state.value
        assertNull(s.deviceId); assertNull(s.token); assertFalse(s.authFailed)
        assertNull(s.noiseSecret, "a new location-noise secret with the new id")
        assertEquals(200, s.sigmaM, "the setting stays")
        assertFalse(oldSecret.contentEquals(NoiseSecret.getOrCreate(store)))
        assertTrue(s.enabled, "«Neue Kennung» keeps sharing on")
        up.run()
        val newId = store.state.value.deviceId!!
        assertNotEquals(oldId, newId)
        assertEquals(0, server.minutes[newId]?.size ?: 0, "nothing from before the new id is sent under it")
    }

    @Test
    fun deleteRemovesEverythingOnTheServerSynchronouslyAndSwitchesOff() = runBlocking {
        addMinutes(20); addEvents(4)
        val (up, store) = setup()
        up.run()
        val id = store.state.value.deviceId!!
        NoiseSecret.getOrCreate(store) // as at a shared «Start»
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
        assertEquals(1, server.count("DELETE", "/v1/devices/$id"))
        assertNull(server.tokens[id]); assertNull(server.sites[id]); assertNull(server.minutes[id]); assertNull(server.events[id])
        val s = store.state.value
        assertFalse(s.enabled); assertNull(s.deviceId); assertNull(s.token)
        assertNull(s.noiseSecret, "deleting the server data also drops the location-noise secret")
        assertEquals(0, s.minutesSentUpToMs)
        assertEquals(now, s.sendFloorMs, "switching on again sends nothing measured before the delete")
        // Off: nothing is sent.
        val n = server.requests.size
        assertEquals(Uploader.Outcome.Disabled, up.run())
        assertEquals(n, server.requests.size)
    }

    @Test
    fun aFailedDeleteChangesNothingOnThePhone() = runBlocking {
        addMinutes(3)
        val (up, store) = setup()
        up.run()
        val before = store.state.value
        server.failNext += Triple("DELETE", before.deviceId!!, 500)
        assertIs<Uploader.DeleteOutcome.Failed>(up.deleteServerData())
        assertEquals(before, store.state.value)
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
    }

    @Test
    fun deleteWithoutRegistrationSendsNothing() = runBlocking {
        val (up, _) = setup(enabled = false)
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun offByDefaultUndecidedAndMobileDataAllowed() = runBlocking {
        addMinutes(5)
        assertFalse(UploadSnapshot().enabled)
        assertEquals(ShareDecision.UNDECIDED, UploadSnapshot().decision)
        // 0.6.3: mobile data is allowed by default (well under 0.1 MB a day, see below).
        assertFalse(UploadSnapshot().wifiOnly)
        // 0.6.3: the consent is the question «Auf die Lärmkarte?» in the UI; enable() itself has no
        // gate any more (until 0.6.2 it needed the explanation screen's «Weiter»).
        val bare = MemoryUploadStore()
        assertTrue(Uploader(UploadClient(server.baseUrl), bare, source, reports, "x", 1, clock = { now }).enable())
        assertEquals(ShareDecision.YES, bare.state.value.decision)
        val (up, _) = setup(enabled = false)
        assertEquals(Uploader.Outcome.Disabled, up.run())
        assertTrue(server.requests.isEmpty(), "nothing at all is sent while sharing is off")
    }

    @Test
    fun onlyTheLastSevenDaysAndNothingFromWhileSharingWasOff() = runBlocking {
        val day = 86_400_000L
        minuteRows += minute(now - 8 * day)          // too old for the server
        minuteRows += minute(now - 6 * day)          // first opt-in includes the last 7 days
        val (up, store) = setup()
        up.run()
        val id = store.state.value.deviceId!!
        assertEquals(1, server.minutes[id]!!.size)

        up.disable()
        minuteRows += minute(now + 60_000)            // measured while sharing was off
        now += 3_600_000
        assertTrue(up.enable())
        minuteRows += minute(now + 60_000)            // measured after switching on again
        now += 600_000
        up.run()
        assertEquals(2, server.minutes[id]!!.size)
        assertEquals(Iso.format(now - 600_000 + 60_000, zone), server.minutes[id]!!.keys.max())
    }

    @Test
    fun aChangedSiteIsSentAgain() = runBlocking {
        val (up, store) = setup()
        up.run()
        store.update { it.copy(site = it.site.copy(placement = "balcony")) }
        up.run()
        assertEquals(2, server.count("PUT", "/site"))
        assertEquals("balcony", server.sites[store.state.value.deviceId]!!["placement"]!!.jsonPrimitive.content)
        // A new shared measurement elsewhere: the site follows its point.
        measurementRows += shared(2, now, null, pointC)
        up.run()
        assertEquals(3, server.count("PUT", "/site"))
        assertEquals(c, server.sites[store.state.value.deviceId]!!.point())
    }

    @Test
    fun rateLimitAndBadRequestsAreReportedForRetryOrNot() = runBlocking {
        addMinutes(3)
        val (up, store) = setup()
        server.failNext += Triple("POST", "/v1/devices", 429)
        assertIs<Uploader.Outcome.Retry>(up.run())
        assertFalse(store.state.value.registered)
        up.run()
        minuteRows += minute(now - 60_000)
        server.failNext += Triple("POST", "/minutes", 422)
        assertIs<Uploader.Outcome.Failed>(up.run())
        assertTrue(store.state.value.lastError!!.contains("422"))
    }

    @Test
    fun networkErrorIsRetried() = runBlocking {
        val store = MemoryUploadStore(UploadSnapshot(explanationSeen = true, site = site))
        server.close()
        val up = Uploader(UploadClient(server.baseUrl, connectTimeoutMs = 2000), store, source, reports, "x", 1, clock = { now })
        up.enable()
        assertIs<Uploader.Outcome.Retry>(up.run())
        // 0.6.3: no connection at all has its own text («Kein Internet – wird nachgeholt» on the status line).
        assertEquals(Uploader.NO_NETWORK_TEXT, store.state.value.lastError)
    }

    @Test
    fun aDayIsWellUnderATenthOfAMegabyteOnTheWire() = runBlocking {
        // 24 h of minutes and 450 events, backing the settings screen's «weniger als 0,1 MB pro Tag».
        addMinutes(1440, endMs = now - 60_000); addEvents(450, endMs = now - 60_000)
        val (up, _) = setup()
        up.run()
        val bytes = server.requests.sumOf { it.rawSize }
        assertTrue(bytes < 100_000, "$bytes bytes on the wire (gzip bodies)")
    }

    @Test
    fun theClientTalksOnlyHttpsExceptToLoopback() {
        UploadClient("https://api.lessnoise.ch")
        assertEquals("api.lessnoise.ch", UploadClient("https://api.lessnoise.ch/").host)
        UploadClient("http://127.0.0.1:8080")
        assertFailsWith<IllegalArgumentException> { UploadClient("http://api.lessnoise.ch") }
        assertFailsWith<IllegalArgumentException> { UploadClient("https://api.lessnoise.ch/v1") }
    }

    @Test
    fun hourlyJobRunsFiveMinutesAfterTheFullHour() {
        val t = ZonedDateTime.of(2026, 10, 8, 23, 41, 30, 0, zone)
        assertEquals((18 * 60 + 30 + 5 * 60) * 1000L, UploadModule.delayToNextHourMs(t))
    }

    @Test
    fun payloadNumbersAreRoundedAndNeverNaN() {
        assertEquals("42.3", Payloads.level(42.34).toString())
        assertEquals("42.4", Payloads.level(42.35000001).toString())
        assertEquals(JsonNull, Payloads.level(Double.NaN))
        assertEquals(JsonNull, Payloads.level(Double.POSITIVE_INFINITY))
        assertEquals(JsonNull, Payloads.level(null))
        val site = Payloads.site(pointA, SiteSettings(note = "x".repeat(300)), SiteExtras("", "VOICE_RECOGNITION", false, 112.35))
        assertEquals(200, site["note"]!!.jsonPrimitive.content.length)
        assertEquals(JsonNull, site["device_model"])
        assertEquals(JsonNull, site["floor"])
        assertTrue((site as JsonObject).keys.none { it.contains("lat") || it.contains("lon") || it.contains("coord") })
    }

    @Test
    fun coordinateEntryBecomesAnLv95PointOnThePhone() {
        fun ok(s: String) = (CellInput.parse(s) as CellInput.Parsed.Ok).cell
        assertEquals("h26833_12479", ok("47.3769, 8.5417"))
        assertEquals("h26833_12479", ok("47,3769 8,5417"))
        assertEquals("h26833_12479", ok("8.5417, 47.3769"))
        assertEquals("h26833_12479", ok("2'683'304, 1'247'925"))
        assertEquals("h26833_12479", ok("2683304.0 1247925.6"))
        assertEquals("h26833_12479", ok("683'304 / 247'925"))
        assertEquals("h26824_12473", ok("h26824_12473"))
        assertEquals("h26809_12512", ok("47.40717847, 8.51060962")) // ETH2 (DESIGN.md §3)
        assertIs<CellInput.Parsed.Error>(CellInput.parse(""))
        assertIs<CellInput.Parsed.Error>(CellInput.parse("48.8566, 2.3522"))   // Paris
        assertIs<CellInput.Parsed.Error>(CellInput.parse("47.37"))
        assertIs<CellInput.Parsed.Error>(CellInput.parse("h99999_99999"))
    }

    @Test
    fun noiseSecretIsMadeOnceAndKept() {
        val store = MemoryUploadStore()
        assertNull(store.state.value.noiseSecret)
        assertEquals(LocationNoise.DEFAULT_SIGMA_M, store.state.value.sigmaM)
        val first = NoiseSecret.getOrCreate(store)
        assertEquals(LocationNoise.SECRET_BYTES, first.size)
        assertTrue(first.contentEquals(NoiseSecret.getOrCreate(store)), "the same secret every time")
        assertTrue(first.contentEquals(java.util.Base64.getDecoder().decode(store.state.value.noiseSecret)))
        // An unreadable stored value is replaced.
        store.update { it.copy(noiseSecret = "not base64 !") }
        assertEquals(LocationNoise.SECRET_BYTES, NoiseSecret.getOrCreate(store).size)
    }

    // ---- 0.6.3: the point arrives after «Starten»; the 10-minute cadence --------------------------

    /** The running row as MeasurementRepository.attachPoint leaves it once the fix arrived. */
    private fun attach(id: Long, p: LocationNoise.Point) {
        val i = measurementRows.indexOfFirst { it.id == id }
        val m = measurementRows[i]
        check(m.endMs == null && m.pointE == null)
        measurementRows[i] = m.copy(cell = p.cell, pointE = p.e, pointN = p.n, sigmaM = p.sigmaM)
    }

    @Test
    fun aPointAttachedLaterSharesTheWholeRunningMeasurementAlsoAfterAnEarlierRunPassedItsRecords() = runBlocking {
        // An earlier shared measurement A, already uploaded.
        val aStart = now - 5 * 3_600_000L
        measurementRows.clear()
        measurementRows += shared(1, aStart, aStart + 20 * 60_000L, pointA)
        for (i in 0 until 20) minuteRows += minute(aStart + i * 60_000L)
        val (up, store) = setup()
        assertEquals(Uploader.Outcome.Done(minutes = 20, events = 0, refused = 0, more = false), up.run())
        val markerAfterA = store.state.value.minutesSentUpToMs

        // «Starten» with sharing on (0.6.3): B runs at once, without a point, while the fix is running.
        val bStart = now - 3_600_000L
        measurementRows += MeasurementEntity(id = 2, startMs = bStart, endMs = null)
        // Its first minute may start a few seconds before the row's start (MeasurementSplitter tolerance).
        minuteRows += minute(bStart - 3_000L)
        for (i in 1 until 25) minuteRows += minute(bStart + i * 60_000L)
        eventRows += event(bStart + 30_000L); eventRows += event(bStart + 300_000L)

        // An upload run before the fix (e.g. the hourly job): nothing of B, and the markers do NOT
        // move past B's records (before 0.6.3 they did, and B's first minutes were lost for good).
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
        assertEquals(markerAfterA, store.state.value.minutesSentUpToMs)
        assertTrue(store.state.value.eventsSentUpToMs < bStart - 5_000L)
        val id = store.state.value.deviceId!!
        assertEquals(20, server.minutes[id]!!.size)

        // The fix arrives: the shifted point is attached to the running measurement.
        attach(2, pointC)
        for (i in 25 until 30) minuteRows += minute(bStart + i * 60_000L)
        assertEquals(Uploader.Outcome.Done(minutes = 30, events = 2, refused = 0, more = false), up.run())
        val sentB = server.minutes[id]!!.values.filter { it.point() == c }
        assertEquals(30, sentB.size, "every minute of B, also the ones recorded before the fix")
        assertTrue(Iso.format(bStart - 3_000L, zone) in server.minutes[id]!!.keys, "the first minute (started 3 s early) too")
        assertEquals(setOf(c), server.events[id]!!.values.map { it.point() }.toSet())
        assertEquals(c, server.sites[id]!!.point(), "the site follows the new point")
        assertEquals(minuteRows.maxOf { it.startEpochMs }, store.state.value.minutesSentUpToMs)
    }

    @Test
    fun aMeasurementThatEndsWithoutAPointIsSkippedForGoodAndReleasesTheMarker() = runBlocking {
        measurementRows.clear()
        val bStart = now - 3_600_000L
        measurementRows += MeasurementEntity(id = 1, startMs = bStart, endMs = null)
        for (i in 0 until 10) minuteRows += minute(bStart + i * 60_000L)
        val (up, store) = setup()
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
        assertTrue(store.state.value.minutesSentUpToMs < bStart)
        // «Stoppen» without a point (fix failed, nothing pasted): no retroactive sharing.
        measurementRows[0] = measurementRows[0].copy(endMs = bStart + 10 * 60_000L)
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
        assertEquals(minuteRows.last().startEpochMs, store.state.value.minutesSentUpToMs, "the marker moves on")
        // Even a point that would arrive now is refused by the database rule (end_ms IS NULL) — see
        // MeasurementsDbTest.attachPointOnlyChangesTheRunningMeasurementWithoutPoint.
    }

    @Test
    fun switchingOnDuringAMeasurementSharesThatWholeMeasurement() = runBlocking {
        val (up, store) = setup()
        up.run()
        up.disable()
        assertEquals(ShareDecision.NO, store.state.value.decision)
        // A measurement started while sharing was off, 40 minutes ago.
        val bStart = now - 40 * 60_000L
        measurementRows += MeasurementEntity(id = 2, startMs = bStart, endMs = null)
        for (i in 0 until 40) minuteRows += minute(bStart + i * 60_000L)
        // Switched on now (status line or «Teilen»), the fix follows and is attached.
        assertTrue(up.enable(runningSinceMs = bStart))
        assertEquals(ShareDecision.YES, store.state.value.decision)
        attach(2, pointC)
        val o = up.run()
        assertIs<Uploader.Outcome.Done>(o)
        assertEquals(40, o.minutes, "the whole running measurement, not only from now on")
        // Without the running measurement the floor is «now» as before: nothing measured while off.
        up.disable()
        val cStart = now + 60_000L
        measurementRows[1] = measurementRows[1].copy(endMs = cStart - 1)
        measurementRows += shared(3, cStart, null, pointA)
        minuteRows += minute(cStart + 1_000L)
        now += 3_600_000L
        up.enable()
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
    }

    @Test
    fun cadenceIsTwoMinutesAfterThePointThenEveryTenMinutes() {
        assertEquals(listOf(2, 12, 22, 32, 42, 52).map { it * 60_000L }, UploadCadence.slots(60 * 60_000L))
        assertEquals(emptyList(), UploadCadence.slots(60_000L))
    }

    /**
     * Two hours of measuring with the point attached 30 s after «Starten»: the cadence runs
     * (2 min, then every 10 min), the hourly fallback, two «Laut! Melden» reports and the run after
     * «Stoppen». The server's limit is a token bucket of 60 requests per device and hour
     * (server/stadtlaerm_server/ratelimit.py, DESIGN.md §8); this stays well below it.
     */
    @Test
    fun twoHoursAtTheTenMinuteCadenceStayWellBelowTheRateLimit() = runBlocking {
        measurementRows.clear()
        minuteRows.clear(); eventRows.clear()
        val t0 = ZonedDateTime.of(2026, 10, 8, 18, 0, 20, 0, zone).toInstant().toEpochMilli()
        val duration = 2 * 3_600_000L
        measurementRows += shared(1, t0, null, pointA)
        // Records appear when they are finished: a minute at its end, an event every 3 min.
        val allMinutes = (0 until 120).map { minute(t0 + it * 60_000L, id = it + 1L) }
        val allEvents = (0 until 40).map { event(t0 + it * 180_000L + 20_000L, id = it + 1L) }
        fun publishUpTo(t: Long) {
            minuteRows.clear(); minuteRows += allMinutes.filter { it.startEpochMs + 60_000L <= t }
            eventRows.clear(); eventRows += allEvents.filter { it.startEpochMs + 5_000L <= t }
        }
        val attachedAt = t0 + 30_000L
        val runs = sortedSetOf<Long>()
        UploadCadence.slots(duration - 30_000L).forEach { runs += attachedAt + it }
        // Hourly fallback: five minutes after each full hour.
        var h = t0 + UploadModule.delayToNextHourMs(java.time.Instant.ofEpochMilli(t0).atZone(zone))
        while (h <= t0 + duration) { runs += h; h += 3_600_000L }
        runs += t0 + duration + 15_000L // after «Stoppen»
        now = t0
        val store = MemoryUploadStore(UploadSnapshot(site = site))
        val up = Uploader(UploadClient(server.baseUrl), store, source, reports, "0.6.3", 16, clock = { now })
        up.enable()
        val reportAt = setOf(runs.elementAt(3), runs.elementAt(8))
        val perRun = mutableListOf<Pair<Long, Int>>()
        for (t in runs) {
            now = t
            publishUpTo(minOf(t, t0 + duration))
            if (t in reportAt) {
                ReportQueue.add(
                    reports,
                    PendingReport(epochMs = t - 1_000L, timeIso = PendingReport.isoSeconds(t - 1_000L, zone), lat = 47.3769, lon = 8.5417, accuracyM = 9.0, category = "motorrad", cell = "h26833_12479"),
                )
            }
            val before = server.requests.size
            assertIs<Uploader.Outcome.Done>(up.run())
            perRun += t to (server.requests.size - before)
        }
        val id = store.state.value.deviceId!!
        assertEquals(120, server.minutes[id]!!.size, "every minute arrived")
        assertEquals(40, server.events[id]!!.size)
        // Per run: registration (once), site (once), reports (twice), one minute batch, one event batch.
        assertTrue(perRun.all { it.second <= 4 }, perRun.toString())
        // Busiest hour (sliding window over the run times) and the server's token bucket.
        val busiest = perRun.maxOf { (t, _) -> perRun.filter { it.first in t until t + 3_600_000L }.sumOf { it.second } }
        assertTrue(busiest <= 30, "busiest hour: $busiest requests (limit 60)")
        var tokens = 60.0
        var last = perRun.first().first
        for ((t, n) in perRun) {
            tokens = minOf(60.0, tokens + (t - last) / 60_000.0)
            last = t
            tokens -= n
            assertTrue(tokens >= 30, "token bucket at $tokens after the run at $t")
        }
        assertEquals(perRun.sumOf { it.second }, server.requests.size)
    }
}
