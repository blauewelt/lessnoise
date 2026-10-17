package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.dsp.Iso
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.launch
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

    // ---- 0.6.3: the point arrives after «Starten» ------------------------------------------------

    /** A running measurement whose fix is not there yet: its first [n] minutes and two events. */
    private fun runningWithoutPoint(start: Long, n: Int) {
        measurementRows.clear()
        measurementRows += MeasurementEntity(id = 1, startMs = start, endMs = null)
        for (i in 0 until n) minuteRows += minute(start + i * 60_000L)
        eventRows += event(start + 30_000L); eventRows += event(start + 5 * 60_000L)
    }

    @Test
    fun aPointAttachedLaterSendsTheWholeRunningMeasurementAlsoAfterAnEarlierRunMovedTheMarkerPastIt() = runBlocking {
        val start = now - 20 * 60_000L
        runningWithoutPoint(start, 10)
        val (up, store) = setup()
        // A run before the fix (e.g. the hourly job): nothing to send, and the markers move past
        // the records, which had no point then.
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
        assertEquals(minuteRows.last().startEpochMs, store.state.value.minutesSentUpToMs)
        assertEquals(eventRows.last().startEpochMs, store.state.value.eventsSentUpToMs)
        // The fix arrives: the row gets its point (MeasurementRepository.attachPoint), then the upload hears of it.
        measurementRows[0] = shared(1, start, null, pointA)
        up.pointAttached(start)
        for (i in 10 until 15) minuteRows += minute(start + i * 60_000L)
        eventRows += event(start + 12 * 60_000L)
        assertEquals(Uploader.Outcome.Done(minutes = 15, events = 3, refused = 0, more = false), up.run())
        val id = store.state.value.deviceId!!
        assertEquals(15, server.minutes[id]!!.size, "the 10 minutes before the fix are not lost behind the marker")
        assertTrue(server.minutes[id]!!.values.all { it.point() == a })
        assertEquals(3, server.events[id]!!.size)
        assertTrue(server.events[id]!!.values.all { it.point() == a })
        // Nothing twice afterwards.
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
    }

    @Test
    fun withoutPointAttachedTheMinutesBeforeTheFixWouldBeLost() = runBlocking {
        // The behaviour of 0.6.2 that pointAttached fixes: the same scenario without it.
        val start = now - 20 * 60_000L
        runningWithoutPoint(start, 10)
        val (up, store) = setup()
        up.run()
        measurementRows[0] = shared(1, start, null, pointA)
        for (i in 10 until 15) minuteRows += minute(start + i * 60_000L)
        up.run()
        assertEquals(5, server.minutes[store.state.value.deviceId]!!.size)
    }

    @Test
    fun switchingOnDuringAMeasurementSharesThatWholeMeasurementButNoEarlierOne() = runBlocking {
        // Registered before, then off; an earlier measurement ended unshared.
        measurementRows.clear()
        val old = now - 3 * 3_600_000L
        measurementRows += MeasurementEntity(id = 1, startMs = old, endMs = old + 30 * 60_000L)
        for (i in 0 until 30) minuteRows += minute(old + i * 60_000L)
        val (up, store) = setup()
        up.run()
        val id = store.state.value.deviceId!!
        up.disable()
        // A measurement starts while sharing is off …
        val start = now - 40 * 60_000L
        measurementRows += MeasurementEntity(id = 2, startMs = start, endMs = null)
        for (i in 0 until 40) minuteRows += minute(start + i * 60_000L)
        // … and sharing is switched on during it (the floor becomes «now» for a registered phone),
        // then the fix gives that measurement its point.
        assertTrue(up.enable())
        assertEquals(now, store.state.value.sendFloorMs)
        measurementRows[1] = shared(2, start, null, pointC)
        up.pointAttached(start)
        up.run()
        val sent = server.minutes[id]!!.values
        assertEquals(40, sent.size, "the whole running measurement")
        assertTrue(sent.all { it.point() == c }, "nothing of the measurement that ended unshared")
    }

    @Test
    fun pointAttachedWaitsForARunningUpload() = runBlocking {
        // pointAttached takes the upload lock: a run in progress cannot move the markers past the
        // measurement again after the rewind.
        val start = now - 20 * 60_000L
        runningWithoutPoint(start, 10)
        val (up, store) = setup()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : UploadSource by source {
            override suspend fun eventsAfter(afterMs: Long, limit: Int): List<EventEntity> {
                gate.await()
                return source.eventsAfter(afterMs, limit)
            }
        }
        val up2 = Uploader(UploadClient(server.baseUrl), store, slow, reports, "0.4.0-dev", 9, clock = { now })
        kotlinx.coroutines.coroutineScope {
            val run = launch(kotlinx.coroutines.Dispatchers.Default) { up2.run() }
            while (store.state.value.minutesSentUpToMs == 0L) kotlinx.coroutines.delay(10)
            val attached = launch(kotlinx.coroutines.Dispatchers.Default) {
                measurementRows[0] = shared(1, start, null, pointA)
                up2.pointAttached(start)
            }
            kotlinx.coroutines.delay(100)
            assertTrue(attached.isActive, "waits for the run")
            gate.complete(Unit)
            run.join(); attached.join()
        }
        assertTrue(store.state.value.minutesSentUpToMs < start && store.state.value.eventsSentUpToMs < start)
        up.run()
        assertEquals(10, server.minutes[store.state.value.deviceId]!!.size)
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
        // 0.6.4: an event without a category is no map event and is not sent (Payloads.isMapEvent),
        // so the body is checked on a map event; the «no category → unclassified» mapping below.
        eventRows += event(now - 7_100_000, category = "loud_vehicle")
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
        assertEquals("loud_vehicle", e["category"]!!.jsonPrimitive.content)
        assertEquals("unclassified", Payloads.event(event(now - 7_000_000, category = null), pointA)["category"]!!.jsonPrimitive.content, "no category → unclassified")
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
        assertFalse(Payloads.isMapEvent(eventRows.first()))
        assertTrue(Payloads.isMapEvent(event(now - 1)))
    }

    // ---- 0.6.4: only map events leave the phone (Payloads.isMapEvent) ----------------------------

    @Test
    fun onlyTheFiveMachineAndMotorCategoriesAreSent() = runBlocking {
        val five = listOf("loud_vehicle", "road_traffic", "rail_tram", "aircraft", "construction")
        val never = listOf("voices", "music", "unclassified", "wind", "speech", "dog", null)
        var t = now - 7_200_000
        for (cat in never + five) { eventRows += event(t, category = cat); t += 10_000 }
        // Wind on the microphone stays home even when it was classified as a vehicle.
        eventRows += event(t, category = "loud_vehicle").copy(wind = true); t += 10_000
        addMinutes(3)
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 3, events = 5, refused = 0, more = false), o)
        val id = store.state.value.deviceId!!
        assertEquals(five.toSet(), server.events[id]!!.values.map { it["category"]!!.jsonPrimitive.content }.toSet())
        assertEquals(5, server.events[id]!!.size)
        for (r in server.requests.filter { it.path.endsWith("/events") }) {
            val text = r.body.toString()
            for (bad in listOf("voices", "music", "unclassified", "speech", "dog", "\"wind")) assertFalse(text.contains(bad), "$bad in $text")
        }
        // The marker moves past every one of them: the others are never fetched again.
        assertEquals(eventRows.maxOf { it.startEpochMs }, store.state.value.eventsSentUpToMs)
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), up.run())
        assertEquals(1, server.count("POST", "/events"))
    }

    @Test
    fun weakQuietOrShortEventsStayOnThePhone() = runBlocking {
        val base = event(0)
        // (event, sent?) — each varies one gate around its limit.
        val cases = listOf(
            base.copy(thresholdDb = 6.4) to false, // excess setting below 6.5 dB
            base.copy(thresholdDb = 6.5) to true,
            base.copy(lafMaxDb = 29.9, minLevelDb = null) to false, // quieter than 30 dB(A)
            base.copy(lafMaxDb = 30.0, minLevelDb = null) to true,
            base.copy(lafMaxDb = 29.9, minLevelDb = 20.0) to false, // a lower floor setting does not lower the 30
            base.copy(lafMaxDb = 30.0, minLevelDb = Double.NaN) to true, // events before 0.3.0: no floor
            base.copy(lafMaxDb = 44.9, minLevelDb = 45.0) to false, // below its own floor setting
            base.copy(lafMaxDb = 45.0, minLevelDb = 45.0) to true,
            base.copy(durationSeconds = 0.49) to false, // shorter than 0.5 s
            base.copy(durationSeconds = 0.5) to true,
        )
        var t = now - 7_200_000
        for ((e, _) in cases) { eventRows += e.copy(id = eventRows.size + 1L, startEpochMs = t, startIso = Iso.format(t, zone, millis = true)); t += 10_000 }
        for ((i, c) in cases.withIndex()) assertEquals(c.second, Payloads.isMapEvent(eventRows[i]), "case $i: ${c.first}")
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 5, refused = 0, more = false), o)
        val sent = server.events[store.state.value.deviceId!!]!!.keys
        val expected = eventRows.filterIndexed { i, _ -> cases[i].second }.map { it.startIso }.toSet()
        assertEquals(expected, sent)
    }

    @Test
    fun theRuleIsTheServersAllowListAndGatesAndBothUploadPathsUseIt() {
        val repo = listOf(java.io.File("../.."), java.io.File(".."), java.io.File(".")).first { java.io.File(it, "server/stadtlaerm_server").isDirectory }
        val config = java.io.File(repo, "server/stadtlaerm_server/config.py").readText()
        val ids = Regex("""MAP_CATEGORIES[^=]*=\s*\(([^)]*)\)""").find(config)!!.groupValues[1]
            .split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        assertEquals(ids, Payloads.MAP_CATEGORIES)
        fun num(name: String) = Regex("""$name\s*=\s*([0-9.]+)""").find(config)!!.groupValues[1].toDouble()
        assertEquals(num("MIN_EVENT_THRESHOLD_DB"), Payloads.MIN_EXCESS_DB)
        assertEquals(num("MIN_EVENT_LAFMAX_DB"), Payloads.MIN_LAFMAX_DB)
        assertEquals(num("MIN_EVENT_DURATION_S"), Payloads.MIN_DURATION_S)
        // One rule: the upload's include filter and the live trigger call it; no other filter.
        val app = java.io.File(repo, "android/app/src/main/kotlin/ch/lessnoise/app/upload")
        val uploader = java.io.File(app, "Uploader.kt").readText()
        // 0.6.7: the place now also depends on the record's duration (moving spans, MovingSpanUploadTest).
        assertTrue(uploader.contains("include = { Payloads.isMapEvent(it) && places.pointAt(it.startEpochMs, (it.durationSeconds * 1000).toLong()) != null }"))
        val worker = java.io.File(app, "UploadWorker.kt").readText()
        assertTrue(worker.contains("fun triggers(e: ch.lessnoise.app.service.LiveEvent?): Boolean = e != null && Payloads.isMapEvent(e)"))
        // 0.6.7: and not while the phone is being carried (nothing of that span is sent).
        assertTrue(worker.contains("if (e != null && LiveUploadPlan.triggers(e) && !live.value.moving)"))
        assertFalse(worker.contains("!e.wind"), "the old wind-only trigger is gone")
    }

    @Test
    fun anUnterwegsMeasurementSharesNothingNotEvenWithAPointInItsRow() = runBlocking {
        // 0.6.4: a measurement started as «Unterwegs» never gets a point (MeasurementDao.attachPoint
        // refuses it); even a row that had one would not make a field: sharedPoint() is null.
        val start = now - 7_200_000
        measurementRows.clear()
        measurementRows += shared(1, start - 3_600_000, start - 60_000, pointA)
        measurementRows += shared(2, start, null, pointC).copy(unterwegs = true)
        repeat(30) { minuteRows += minute(start + it * 60_000L) }
        repeat(6) { eventRows += event(start + 5_000 + it * 120_000L, category = if (it % 2 == 0) "loud_vehicle" else "construction") }
        val (up, store) = setup()
        // As if the fix had arrived and the upload heard of it: still nothing.
        up.pointAttached(start)
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 0, events = 0, refused = 0, more = false), o)
        assertEquals(0, server.count("POST", "/minutes"))
        assertEquals(0, server.count("POST", "/events"))
        assertNull(PlaceIndex(measurementRows).pointAt(start + 10_000))
        // The site call carries the point of the newest SHARED measurement (A), never C.
        val site = server.sites[store.state.value.deviceId!!]!!
        assertEquals(a, site.point())
        assertTrue(store.state.value.registered)
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
        assertEquals(ShareDecision.NO, s.shareDecision, "0.6.3: deleting is a «no»; the question is not asked again")
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
    fun offByDefaultAndOneYesSwitchesOn() = runBlocking {
        addMinutes(5)
        assertFalse(UploadSnapshot().enabled)
        assertEquals(ShareDecision.UNDECIDED, UploadSnapshot().shareDecision)
        assertFalse(UploadSnapshot().wifiOnly, "0.6.3: mobile data allowed by default (at most about 3 MB a day)")
        // 0.6.3: no explanation gate («Weiter») any more; the opt-in is «Ja, auf die Karte» at the
        // first «Starten», the switch in «Teilen» or the consent of «Laut! Melden» (ShareFlow.kt,
        // LocationFlows.kt), each of which calls enable(). Since 0.6.0 no hectare is needed either.
        val bareStore = MemoryUploadStore()
        val bare = Uploader(UploadClient(server.baseUrl), bareStore, source, reports, "x", 1, clock = { now })
        assertTrue(bare.enable())
        assertTrue(bareStore.state.value.enabled)
        assertEquals(ShareDecision.YES, bareStore.state.value.shareDecision)
        bare.disable()
        assertFalse(bareStore.state.value.enabled)
        assertEquals(ShareDecision.NO, bareStore.state.value.shareDecision)
        assertTrue(server.requests.isEmpty(), "switching on and off sends nothing by itself")
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
        // 0.6.3: told apart from the server's own failures, for the status line «Kein Internet».
        assertEquals(Uploader.NO_CONNECTION_TEXT, store.state.value.lastError)
    }

    @Test
    fun aDayIsWellUnderATenthOfAMegabyteOnTheWire() = runBlocking {
        // 24 h of minutes and 450 events in ONE run (the hourly fallback after a day offline). The live
        // map's many small requests are estimated in UploadCadenceTest («höchstens etwa 3 MB pro Tag»).
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
}
