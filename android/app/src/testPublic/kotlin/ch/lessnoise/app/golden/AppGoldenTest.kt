package ch.lessnoise.app.golden

import ch.lessnoise.app.alert.LoudAlertIntent
import ch.lessnoise.app.alert.LoudAlertRule
import ch.lessnoise.app.audio.AudioSourceSelector
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.Mappers
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.app.data.sharedPoint
import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.service.LiveEvent
import ch.lessnoise.app.ui.BANNER_FIRST_VALUES
import ch.lessnoise.app.ui.BANNER_NOT_SHARING
import ch.lessnoise.app.ui.BANNER_NO_POINT
import ch.lessnoise.app.ui.CONSENT_MORE
import ch.lessnoise.app.ui.CONSENT_NO
import ch.lessnoise.app.ui.CONSENT_TITLE
import ch.lessnoise.app.ui.CONSENT_YES
import ch.lessnoise.app.ui.LOCATION_NEVER_SENT
import ch.lessnoise.app.ui.Links
import ch.lessnoise.app.ui.LocateState
import ch.lessnoise.app.ui.MapCentring
import ch.lessnoise.app.ui.MapLinks
import ch.lessnoise.app.ui.SHARE_DELETE_BULLETS
import ch.lessnoise.app.ui.SHARE_INTRO
import ch.lessnoise.app.ui.SHARE_SENT_BULLETS
import ch.lessnoise.app.ui.SHARE_WHEN_BULLETS
import ch.lessnoise.app.ui.SHARE_WHERE_BULLETS
import ch.lessnoise.app.ui.SIGMA_EXPLANATION
import ch.lessnoise.app.ui.SIGMA_LABEL
import ch.lessnoise.app.ui.STATUS_AUTH
import ch.lessnoise.app.ui.STATUS_FIRST_VALUES
import ch.lessnoise.app.ui.STATUS_LOCATING
import ch.lessnoise.app.ui.STATUS_NO_INTERNET
import ch.lessnoise.app.ui.STATUS_NO_POINT
import ch.lessnoise.app.ui.STATUS_OFF
import ch.lessnoise.app.ui.STATUS_ON_IDLE
import ch.lessnoise.app.ui.STATUS_REJECTED
import ch.lessnoise.app.ui.STATUS_SERVER_BUSY
import ch.lessnoise.app.ui.Screen
import ch.lessnoise.app.ui.TabHistory
import ch.lessnoise.app.ui.appScreens
import ch.lessnoise.app.ui.bannerSent
import ch.lessnoise.app.ui.consentLines
import ch.lessnoise.app.ui.mapBanner
import ch.lessnoise.app.ui.pastedText
import ch.lessnoise.app.ui.reportCategoryOrder
import ch.lessnoise.app.ui.screenLabel
import ch.lessnoise.app.ui.shareLocationBullets
import ch.lessnoise.app.ui.shareStatus
import ch.lessnoise.app.ui.sigmaChoice
import ch.lessnoise.app.ui.statusSent
import ch.lessnoise.app.upload.CellInput
import ch.lessnoise.app.upload.FakeServer
import ch.lessnoise.app.upload.LiveUploadPlan
import ch.lessnoise.app.upload.MemoryReportStore
import ch.lessnoise.app.upload.MemoryUploadStore
import ch.lessnoise.app.upload.Payloads
import ch.lessnoise.app.upload.PendingReport
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportQueue
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.SiteExtras
import ch.lessnoise.app.upload.SiteSettings
import ch.lessnoise.app.upload.UploadClient
import ch.lessnoise.app.upload.UploadCadence
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.app.upload.UploadSource
import ch.lessnoise.app.upload.Uploader
import ch.lessnoise.dsp.EventFeatures
import ch.lessnoise.dsp.EventShape
import ch.lessnoise.dsp.Iso
import ch.lessnoise.dsp.MinuteRecord
import ch.lessnoise.dsp.NoiseEvent
import ch.lessnoise.dsp.calibration.EventFloor
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden fixtures of the app's pure logic for the Swift port in ios/LessNoiseCore (the iOS app's
 * core; the dsp module's fixtures come from android/dsp …/golden/DspGoldenTest.kt). Written to
 * app/build/ios-golden/ on every run, or to $LESSNOISE_GOLDEN_DIR (ios/LessNoiseCore/regenerate-fixtures.sh).
 * The record serialisation is the one of the dsp generator (Golden.minute / Golden.event), repeated
 * here because the app's tests cannot see the dsp module's test classes.
 */
class AppGoldenTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val dir = File(System.getProperty("lessnoise.goldenDir") ?: "build/ios-golden").also { it.mkdirs() }
    private val server = FakeServer()

    @AfterTest
    fun stop() = server.close()

    private fun write(name: String, root: JsonElement) = File(dir, name).writeText(goldenFormat(root) + "\n")

    private fun d(v: Double): JsonPrimitive = when {
        v.isNaN() -> JsonPrimitive("NaN")
        v == Double.POSITIVE_INFINITY -> JsonPrimitive("Infinity")
        v == Double.NEGATIVE_INFINITY -> JsonPrimitive("-Infinity")
        else -> JsonPrimitive(v)
    }

    private fun d(v: Double?): JsonElement = if (v == null) JsonNull else d(v)
    private fun s(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
    private fun l(v: Long?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    private fun features(f: EventFeatures?): JsonElement = if (f == null) JsonNull else buildJsonObject {
        put("local_floor_db", d(f.localFloorDb)); put("excess_db", d(f.excessDb)); put("rise_s", d(f.riseS))
        put("decay_s", d(f.decayS)); put("jaggedness", d(f.jaggedness)); put("mid_band_rise_db", d(f.midBandRiseDb))
        put("lf_share", d(f.lfShare)); put("lf_flutter_db", d(f.lfFlutterDb)); put("wind", f.wind); put("shape", f.shape)
    }

    private fun minuteJson(m: MinuteRecord): JsonObject = buildJsonObject {
        put("start_ms", m.startEpochMs); put("start_iso", m.startIso); put("duration_s", d(m.durationSeconds))
        put("laeq_db", d(m.laeqDb)); put("lafmax_db", d(m.lafMaxDb)); put("lafmin_db", d(m.lafMinDb))
        put("l1_db", d(m.l1Db)); put("l10_db", d(m.l10Db)); put("l50_db", d(m.l50Db)); put("l90_db", d(m.l90Db))
        put("event_count", m.eventCount); put("dominant_category", s(m.dominantCategory))
        put("category_shares", buildJsonArray { m.categoryShares.forEach { (k, v) -> add(buildJsonArray { add(k); add(d(v)) }) } })
        put("classifier_frames", m.classifierFrames); put("calibration_id", l(m.calibrationId))
        put("calibration_offset_db", d(m.calibrationOffsetDb)); put("audio_source", m.audioSource); put("calibrated", m.calibrated)
        put("valid_s", d(m.validSeconds)); put("clock_corrections", m.clockCorrections)
        put("original", m.original?.let { o -> buildJsonArray { listOf(o.laeqDb, o.lafMaxDb, o.lafMinDb, o.l1Db, o.l10Db, o.l50Db, o.l90Db).forEach { add(d(it)) } } } ?: JsonNull)
        put("recalibrated_from_id", s(m.recalibratedFromId)); put("recalibration_offset_db", d(m.recalibrationOffsetDb))
        put("local_floor_db", d(m.localFloorDb)); put("wind_event_count", m.windEventCount); put("coverage", d(m.coverage))
    }

    private fun eventJson(e: NoiseEvent): JsonObject = buildJsonObject {
        put("start_ms", e.startEpochMs); put("start_iso", e.startIso); put("duration_s", d(e.durationSeconds))
        put("lafmax_db", d(e.lafMaxDb)); put("sel_db", d(e.selDb)); put("background_db", d(e.backgroundDb))
        put("threshold_db", d(e.thresholdDb)); put("dominant_category", s(e.dominantCategory))
        put("dominant_score", d(e.dominantScore.toDouble()))
        put("top_labels", buildJsonArray { e.topLabels.forEach { add(buildJsonArray { add(it.label); add(d(it.score.toDouble())) }) } })
        put("classifier_frames", e.classifierFrames); put("calibration_id", l(e.calibrationId))
        put("audio_source", e.audioSource); put("calibrated", e.calibrated); put("min_level_db", d(e.minLevelDb))
        put("original", e.original?.let { o -> buildJsonArray { add(d(o.lafMaxDb)); add(d(o.selDb)); add(d(o.backgroundDb)) } } ?: JsonNull)
        put("recalibrated_from_id", s(e.recalibratedFromId)); put("recalibration_offset_db", d(e.recalibrationOffsetDb))
        put("features", features(e.features))
    }

    private fun pointJson(p: LocationNoise.Point?): JsonElement =
        if (p == null) JsonNull else buildJsonArray { add(p.e); add(p.n); add(p.sigmaM); add(p.clamped) }

    private fun minuteEntity(m: MinuteRecord): MinuteEntity = with(Mappers) { m.toEntity() }
    private fun eventEntity(e: NoiseEvent): EventEntity = with(Mappers) { e.toEntity() }

    private fun epoch(y: Int, mo: Int, dd: Int, h: Int, mi: Int, sec: Int = 0, z: ZoneId = zone) =
        ZonedDateTime.of(y, mo, dd, h, mi, sec, 0, z).toInstant().toEpochMilli()

    private fun minute(startMs: Long, i: Int) = MinuteRecord(
        startEpochMs = startMs, startIso = Iso.format(startMs, zone), durationSeconds = if (i % 13 == 5) 7.25 else 60.0,
        laeqDb = if (i % 11 == 3) Double.NaN else 40.0 + (i * 37 % 100) / 7.0, lafMaxDb = 61.05 + i % 3, lafMinDb = 33.15,
        l1Db = 55.0, l10Db = 47.25, l50Db = 41.05, l90Db = if (i % 7 == 2) Double.NaN else 36.45,
        eventCount = i % 4, dominantCategory = if (i % 5 == 0) null else "road_traffic",
        categoryShares = if (i % 5 == 0) emptyMap() else linkedMapOf("road_traffic" to 1.0 / 3, "loud_vehicle" to 0.0005, "unclassified" to 2.0 / 3 - 0.0005),
        classifierFrames = 60, calibrationId = if (i % 2 == 0) 7L else null, calibrationOffsetDb = if (i % 2 == 0) 121.9 else 112.35,
        audioSource = "UNPROCESSED", calibrated = i % 2 == 0, validSeconds = if (i % 9 == 4) 59.25 else 60.0 - (i % 3) * 0.125,
        clockCorrections = i % 2, localFloorDb = 36.4, windEventCount = i % 3,
    )

    private fun event(startMs: Long, i: Int) = NoiseEvent(
        startEpochMs = startMs, startIso = Iso.format(startMs, zone, millis = true), durationSeconds = 0.5 + i * 0.125,
        lafMaxDb = 60.0 + i * 1.05, selDb = 64.04 + i, backgroundDb = 38.15, thresholdDb = 6.5,
        dominantCategory = listOf("loud_vehicle", "road_traffic", null, "unclassified")[i % 4], dominantScore = 0.3125f + i / 64f,
        topLabels = emptyList(), classifierFrames = i % 3, calibrationId = if (i % 2 == 0) 7L else null, audioSource = "UNPROCESSED",
        calibrated = i % 2 == 0, minLevelDb = if (i % 5 == 4) Double.NaN else 30.0,
        features = EventFeatures(40.0, 20.0, 1.2, 1.5, 0.05, 9.0, if (i % 6 == 5) 0.95 else 0.1, 1.0, i % 6 == 5, "hump"),
    )

    // ---- Payloads -----------------------------------------------------------------------------

    @Test
    fun payloads() {
        val p1 = LocationNoise.Point(2682437, 1247391, 50)
        val p2 = LocationNoise.Point(2480000, 1299999, 200, clamped = true)
        val sites = listOf(
            SiteSettings(placement = "open_window", floor = 3, streetFacing = true, note = "2. OG") to SiteExtras("Pixel 8", "UNPROCESSED", true, 121.9),
            SiteSettings(placement = "balcony", floor = null, streetFacing = null, note = "  ") to SiteExtras("  ", "VOICE_RECOGNITION", false, 112.34999),
            SiteSettings(placement = "other", floor = 0, streetFacing = false, note = " Zimmer «hinten», \"Hof\"\n\tTab " + "x".repeat(250)) to
                SiteExtras("iPhone16,1 " + "y".repeat(80), "UNPROCESSED", true, 118.005),
        )
        val start = epoch(2026, 10, 12, 21, 0)
        val minutes = (0 until 14).map { minute(start + it * 60_000L, it) }
        val events = (0 until 12).map { event(start + it * 61_375L, it) }
        val reports = listOf(
            PendingReport(1_791_800_703_000L, PendingReport.isoSeconds(1_791_800_703_456L, zone), 47.3769123, 8.5416849, 12.34, "motorrad", "h26833_12479"),
            PendingReport(1_791_800_703_000L, PendingReport.isoSeconds(1_791_800_703_456L, ZoneId.of("UTC")), 46.000005, 6.999995, 0.05, "andere", null),
            PendingReport(-1L, PendingReport.isoSeconds(-1L, ZoneId.of("America/New_York")), 45.82, 10.49, 49.95, "poser", null),
        )
        val body = JsonArray(minutes.map { Payloads.minute(minuteEntity(it), p1) }).toString().toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body) } }.toByteArray()
        val crc = CRC32().also { it.update(body) }.value
        write("payloads.json", buildJsonObject {
            put("points", buildJsonArray { add(pointJson(p1)); add(pointJson(p2)) })
            put("point_json", buildJsonArray { add(Payloads.pointJson(p1).toString()); add(Payloads.pointJson(p2).toString()) })
            put("sites", buildJsonArray {
                for ((site, extras) in sites) for (p in listOf(p1, p2)) {
                    add(buildJsonObject {
                        put("point", pointJson(p))
                        put("placement", site.placement); put("floor", site.floor?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("street_facing", site.streetFacing?.let { JsonPrimitive(it) } ?: JsonNull); put("note", site.note)
                        put("device_model", extras.deviceModel); put("audio_source", extras.audioSource); put("calibrated", extras.calibrated)
                        put("calibration_offset_db", d(extras.calibrationOffsetDb))
                        put("json", Payloads.site(p, site, extras).toString())
                    })
                }
            })
            put("minutes", buildJsonArray {
                minutes.forEach { m -> add(buildJsonObject { put("record", minuteJson(m)); put("json", Payloads.minute(minuteEntity(m), p1).toString()) }) }
            })
            put("events", buildJsonArray {
                events.forEach { e ->
                    val en = eventEntity(e)
                    add(buildJsonObject { put("record", eventJson(e)); put("shared", Payloads.isShared(en)); put("json", Payloads.event(en, p2).toString()) })
                }
            })
            put("reports", buildJsonArray {
                reports.forEach { r ->
                    add(buildJsonObject {
                        put("epoch_ms", r.epochMs); put("time_iso", r.timeIso); put("lat", d(r.lat)); put("lon", d(r.lon))
                        put("accuracy_m", d(r.accuracyM)); put("category", r.category); put("cell", s(r.cell))
                        put("json", Payloads.report(r).toString())
                    })
                }
            })
            put("iso_seconds", buildJsonArray {
                for (z in listOf("Europe/Zurich", "UTC", "Asia/Kolkata", "America/New_York")) for (t in listOf(1_791_800_703_456L, 1_774_745_999_999L, -1L, 0L)) {
                    add(buildJsonArray { add(z); add(t); add(PendingReport.isoSeconds(t, ZoneId.of(z))) })
                }
            })
            put("levels", buildJsonArray {
                for (v in listOf(42.34, 42.35, 42.25, -0.05, 0.05, 99.95, 1e-4, Double.NaN, Double.POSITIVE_INFINITY, 112.34999, 7.25)) {
                    add(buildJsonArray { add(d(v)); add(Payloads.level(v).toString()); add(Payloads.num(v, 3).toString()); add(Payloads.num(v, 5).toString()) })
                }
            })
            put("gzip", buildJsonObject {
                put("body", String(body, Charsets.UTF_8))
                put("crc32", crc); put("size", body.size)
                put("header_hex", gz.copyOfRange(0, 10).joinToString("") { "%02x".format(it) })
                put("trailer_hex", gz.copyOfRange(gz.size - 8, gz.size).joinToString("") { "%02x".format(it) })
            })
        })
    }

    // ---- Uploader scenario against the fake server ---------------------------------------------

    private class Rows {
        val minutes = ArrayList<MinuteEntity>()
        val events = ArrayList<EventEntity>()
        val measurements = ArrayList<MeasurementEntity>()
    }

    @Test
    fun uploadScenario() = runBlocking {
        var now = epoch(2026, 10, 12, 12, 0)
        val rows = Rows()
        val recMinutes = ArrayList<MinuteRecord>()
        val recEvents = ArrayList<NoiseEvent>()
        val p1 = LocationNoise.Point(2682437, 1247391, 50)
        val p2 = LocationNoise.Point(2683351, 1247988, 100)
        fun meas(id: Long, s: Long, e: Long?, p: LocationNoise.Point?) =
            MeasurementEntity(id = id, startMs = s, endMs = e, cell = p?.cell, pointE = p?.e, pointN = p?.n, sigmaM = p?.sigmaM)
        val h = 3_600_000L
        rows.measurements += meas(1, now - 8 * 24 * h, now - 8 * 24 * h + h, p1) // too old
        rows.measurements += meas(2, now - 5 * h, now - 4 * h, p1)
        rows.measurements += meas(3, now - 4 * h + 600_000, now - 3 * h, null) // not shared
        rows.measurements += meas(4, now - 2 * h, null, p2) // running
        fun addMinute(t: Long) {
            val m = minute(t, recMinutes.size)
            recMinutes += m
            rows.minutes += minuteEntity(m).copy(id = rows.minutes.size + 1L)
        }
        fun addEvent(t: Long) {
            val e = event(t, recEvents.size)
            recEvents += e
            rows.events += eventEntity(e).copy(id = rows.events.size + 1L)
        }
        for (i in 0 until 10) addMinute(now - 8 * 24 * h + i * 60_000L)
        for (i in 0 until 60) addMinute(now - 5 * h + i * 60_000L)
        for (i in 0 until 50) addMinute(now - 4 * h + 600_000 + i * 60_000L)
        for (i in 0 until 100) {
            val t = now - 2 * h + i * 60_000L
            addMinute(t)
            // Rows with the same start (an app restart within a minute): groups of 2, 3 and 8.
            if (i == 12) addMinute(t)
            if (i == 33) { addMinute(t); addMinute(t) }
            if (i == 70) repeat(7) { addMinute(t) }
        }
        addEvent(now - 8 * 24 * h + 5_000)
        for (i in 0 until 6) addEvent(now - 5 * h + i * 433_000L + 375)
        for (i in 0 until 3) addEvent(now - 4 * h + 700_000 + i * 600_000L)
        for (i in 0 until 9) addEvent(now - 2 * h + i * 611_125L)
        val source = object : UploadSource {
            override suspend fun minutesAfter(afterMs: Long, limit: Int) =
                rows.minutes.filter { it.startEpochMs > afterMs }.sortedWith(compareBy({ it.startEpochMs }, { it.id })).take(limit)
            override suspend fun eventsAfter(afterMs: Long, limit: Int) =
                rows.events.filter { it.startEpochMs > afterMs }.sortedWith(compareBy({ it.startEpochMs }, { it.id })).take(limit)
            override suspend fun measurements() = rows.measurements.toList()
            override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = true, calibrationOffsetDb = 121.9)
        }
        val reports = MemoryReportStore()
        val pending = listOf(
            PendingReport(now - 3 * h, PendingReport.isoSeconds(now - 3 * h, zone), 47.3769123, 8.5416849, 12.3, "motorrad", "h26833_12479"),
            PendingReport(now - 2 * h, PendingReport.isoSeconds(now - 2 * h, zone), 47.37, 8.54, 40.0, "auto", "h26832_12477"),
        )
        pending.forEach { ReportQueue.add(reports, it) }
        val store = MemoryUploadStore(UploadSnapshot(site = SiteSettings(placement = "open_window", floor = 3, streetFacing = true, note = "2. OG")))
        val up = Uploader(UploadClient(server.baseUrl), store, source, reports, "0.6.3", 16, clock = { now }, minuteBatch = 7, eventBatch = 4)
        server.refuseStarts += recMinutes[200].startIso
        server.refuseStarts += recEvents[12].startIso
        var seen = 0
        val stepList = ArrayList<JsonObject>()
        val m3Start = rows.measurements[3].startMs
        suspend fun step(name: String, action: suspend () -> Any?) {
                val outcome = action()
                val reqs = synchronized(server.requests) { server.requests.drop(seen).also { seen = server.requests.size } }
                val snap = store.state.value
                val id = snap.deviceId
                stepList += buildJsonObject {
                    put("step", name)
                    put("now_ms", now)
                    put("outcome", outcome.toString())
                    put("requests", buildJsonArray {
                        reqs.forEach { r ->
                            add(buildJsonArray {
                                add(r.method)
                                add(if (id != null) r.path.replace(id, "{device}") else r.path.replace(Regex("[0-9a-f-]{36}"), "{device}"))
                                add(r.body?.toString()?.let { JsonPrimitive(it) } ?: JsonNull)
                            })
                        }
                    })
                    put("snapshot", buildJsonObject {
                        put("enabled", snap.enabled); put("decision", snap.shareDecision.stored); put("registered", snap.registered)
                        put("send_floor_ms", snap.sendFloorMs); put("minutes_sent_ms", snap.minutesSentUpToMs); put("events_sent_ms", snap.eventsSentUpToMs)
                        put("last_site_json", s(snap.lastSiteJson)); put("last_success_ms", l(snap.lastSuccessAtMs)); put("last_error", s(snap.lastError))
                        put("auth_failed", snap.authFailed); put("refused_total", snap.refusedTotal)
                        put("refused", buildJsonArray { snap.refused.forEach { r -> add(buildJsonArray { add(r.kind); add(r.startEpochMs); add(r.reason) }) } })
                    })
                    put("reports_pending", reports.state.value.pending.size)
                    put("reports_sent", buildJsonArray { reports.state.value.sent.forEach { r -> add(buildJsonArray { add(r.epochMs); add(r.category); add(s(r.cell)) }) } })
                    put("effective_floor_ms", up.effectiveFloorMs()); put("minutes_pending_after_ms", up.minutesPendingAfterMs())
                }
        }
            step("run while off") { up.run() }
            step("enable") { up.enable() }
            step("run 1") { up.run() }
            now += 120_000
            step("run 2") { up.run() }
            now += 120_000
            step("run 3") { up.run() }
            now += 120_000
            step("run 4") { up.run() }
            step("point attached to the running measurement") { up.pointAttached(m3Start) }
            now += 30_000
            step("run after point attached") { up.run() }
            now += 30_000
            step("run after point attached 2") { up.run() }
            now += 30_000
            step("run after point attached 3") { up.run() }
            for (i in 0 until 3) { addMinute(now - 60_000L * (3 - i)) }
            addEvent(now - 20_000)
            now += 30_000
            step("events-only run") { up.run(withMinutes = false) }
            server.failNext += Triple("POST", "/minutes", 503)
            now += 60_000
            step("server error") { up.run() }
            server.failNext += Triple("POST", "/minutes", 429)
            now += 60_000
            step("rate limited") { up.run() }
            now += 60_000
            step("full run") { up.run() }
            store.update { it.copy(site = it.site.copy(note = "neu: 3. OG")) }
            step("site changed") { up.run() }
            step("rewind after re-evaluation") { up.rewindAfterReevaluation() }
            now += 60_000
            step("run after rewind") { up.run() }
            now += 60_000
            step("run after rewind 2") { up.run() }
            ReportQueue.add(reports, pending[0].copy(epochMs = now, timeIso = PendingReport.isoSeconds(now, zone)))
            step("disable drops the pending report") { up.disable() }
            step("run while disabled") { up.run() }
            step("enable again") { up.enable() }
            step("delete server data") { up.deleteServerData() }
            step("run after delete (registers anew)") { up.run() }
        val steps = JsonArray(stepList)
        write("upload_scenario.json", buildJsonObject {
            put("about", "Uploader (minuteBatch 7, eventBatch 4, 24 requests per run) against FakeServer; device id in paths replaced by {device}")
            put("app_version", "0.6.3"); put("app_build", 16)
            put("measurements", buildJsonArray { rows.measurements.forEach { m -> add(buildJsonArray { add(m.id); add(m.startMs); add(l(m.endMs)); add(pointJson(m.sharedPoint())) }) } })
            put("minutes", buildJsonArray { recMinutes.forEach { add(minuteJson(it)) } })
            put("events", buildJsonArray { recEvents.forEach { add(eventJson(it)) } })
            put("reports", buildJsonArray {
                pending.forEach { r -> add(buildJsonArray { add(r.epochMs); add(r.timeIso); add(d(r.lat)); add(d(r.lon)); add(d(r.accuracyM)); add(r.category); add(s(r.cell)) }) }
            })
            put("refuse_starts", buildJsonArray { server.refuseStarts.sorted().forEach { add(it) } })
            put("steps", steps)
        })
        assertTrue(server.requests.isNotEmpty())
    }

    // ---- Live plan, cadence -------------------------------------------------------------------

    @Test
    fun livePlan() {
        val scenarios = buildJsonArray {
            val results = listOf(
                listOf("OK"), listOf("OK", "OK", "RATE_LIMITED", "OK", "OK", "RATE_LIMITED", "RATE_LIMITED", "RATE_LIMITED", "RATE_LIMITED", "OK"),
                listOf("FAILED", "OK", "FAILED", "FAILED", "OK"),
            )
            for ((k, res) in results.withIndex()) {
                val pointAt = 1_000_000L + k * 7_919
                val plan = LiveUploadPlan(pointAt)
                val r = ch.lessnoise.app.golden.SplitMix(17L + k)
                val events = ArrayList<Long>()
                var t = pointAt - 5_000
                while (t < pointAt + 4 * 3_600_000) { t += 5_000 + (r.nextDouble() * 400_000).toLong(); events += t }
                if (k == 1) { events += pointAt + 7_200_000; events.sort() }
                var e = 0
                add(buildJsonObject {
                    put("point_at_ms", pointAt)
                    put("results", buildJsonArray { res.forEach { add(it) } })
                    put("events", buildJsonArray { events.forEach { add(it) } })
                    put("runs", buildJsonArray {
                        var i = 0
                        while (true) {
                            var run = plan.next()
                            while (e < events.size && events[e] <= run.atMs) { plan.onEvent(events[e]); e++; run = plan.next() }
                            if (run.atMs > pointAt + 4 * 3_600_000) break
                            val result = LiveUploadPlan.Result.valueOf(res[i % res.size])
                            plan.done(run, result, run.atMs + 2_000)
                            add(buildJsonArray { add(run.atMs); add(run.kind.name); add(result.name); add(plan.backOffLevel) })
                            i++
                        }
                    })
                })
            }
        }
        write("live_plan.json", buildJsonObject {
            put("about", "LiveUploadPlan: events (end times) are reported when the next run is due at or after them; done() 2 s after each run; results cycle")
            put("scenarios", scenarios)
            put("hourly", buildJsonArray {
                for ((z, from) in listOf("Europe/Zurich" to 1_791_800_703_456L, "Asia/Kolkata" to 1_791_800_703_456L, "UTC" to 1_791_799_200_000L)) {
                    add(buildJsonArray {
                        add(z); add(from)
                        add(buildJsonArray { UploadCadence.hourlyRunTimes(from, from + 5 * 3_600_000L, ZoneId.of(z)).forEach { add(it) } })
                    })
                }
            })
        })
    }

    // ---- Status line, banner -------------------------------------------------------------------

    @Test
    fun shareStatusAndBanner() {
        val texts = ArrayList<String>()
        fun ti(t: String?): JsonElement = if (t == null) JsonNull else JsonPrimitive(texts.indexOf(t).let { if (it >= 0) it else { texts += t; texts.size - 1 } })
        val t0 = epoch(2026, 10, 12, 14, 5)
        val hm: (Long) -> String = { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime().toString().take(5) }
        val errors = listOf(null, Uploader.NO_CONNECTION_TEXT, Uploader.RETRY_TEXT, "Vom Server abgelehnt (400).")
        val locates = listOf(LocateState.Idle, LocateState.Pending, LocateState.Locating, LocateState.Failed(LocationPolicy.Outcome.NoFix))
        val states = listOf(false to ShareDecision.UNDECIDED, false to ShareDecision.NO, false to ShareDecision.YES, true to ShareDecision.YES)
        val rows = buildJsonArray {
            var k = 0
            for ((enabled, decision) in states) for (auth in listOf(false, true)) for (succ in listOf(null, t0)) for (err in errors.indices)
                for (sent in listOf(0L, t0)) for (running in listOf(false, true)) for (hasPoint in listOf(false, true))
                    for (loc in locates.indices) for (online in listOf(false, true)) {
                        val s = UploadSnapshot(enabled = enabled, shareDecision = decision, authFailed = auth, lastSuccessAtMs = succ,
                            lastError = errors[err], minutesSentUpToMs = sent)
                        val start = t0 - 600_000
                        val anyPoint = hasPoint || k % 2 == 0
                        val now = if (k % 3 == 0) t0 + 60_000 else t0 + 180_000
                        val st = shareStatus(s, running, start, hasPoint, locates[loc], online, hm)
                        val b = mapBanner(s, running, start, hasPoint, anyPoint, locates[loc], online, now, hm)
                        add(buildJsonArray {
                            add(if (enabled) 1 else 0); add(decision.stored); add(if (auth) 1 else 0); add(if (succ != null) 1 else 0); add(err)
                            add(if (sent != 0L) 1 else 0); add(if (running) 1 else 0); add(if (hasPoint) 1 else 0); add(loc); add(if (online) 1 else 0)
                            add(if (anyPoint) 1 else 0); add(now)
                            add(ti(st?.text)); add(st?.action?.name ?: ""); add(if (st?.problem == true) 1 else 0)
                            add(ti(b?.text)); add(b?.action?.name ?: "")
                        })
                        k++
                    }
        }
        write("share_status.json", buildJsonObject {
            put("about", "shareStatus/mapBanner over all combinations; columns: enabled, decision, authFailed, lastSuccess(t0), lastError index, minutesSent(t0), running, hasPoint, locate index (Idle, Pending, Locating, Failed(NoFix)), online, hasAnyPoint, nowMs, status text index, status action, problem, banner text index, banner action; measurement start t0 − 10 min; hm = HH:mm Europe/Zurich")
            put("t0", t0)
            put("errors", buildJsonArray { errors.forEach { add(s(it)) } })
            put("rows", rows)
            put("texts", buildJsonArray { texts.forEach { add(it) } })
        })
    }

    // ---- Alert, map links, tabs, cell input, location policy -----------------------------------

    @Test
    fun alertLinksTabsLocation() {
        val alerts = buildJsonArray {
            for (cat in listOf("loud_vehicle", "road_traffic", "unclassified", "wind", null)) for (lvl in listOf(74.99, 75.0, 84.5, 85.5))
                for (wind in listOf(false, true)) for (last in listOf(null, 0L, 1_000L)) {
                    val e = LiveEvent(100_000L, 120_000L, lvl, cat, wind, calibrated = false)
                    add(buildJsonArray {
                        add(s(cat)); add(d(lvl)); add(wind); add(l(last))
                        add(LoudAlertRule.shouldAlert(e, unterwegs = true, running = true, lastAlertAtMs = last, nowMs = 121_000L))
                        add(LoudAlertRule.shouldAlert(e, unterwegs = false, running = true, lastAlertAtMs = null, nowMs = 121_000L))
                        add(LoudAlertRule.shouldAlert(e, unterwegs = true, running = false, lastAlertAtMs = null, nowMs = 121_000L))
                        add(LoudAlertRule.title(e)); add(LoudAlertRule.categoryLabel(cat))
                        add(buildJsonArray { LoudAlertRule.suggested(cat).forEach { add(it.id) } })
                        add(buildJsonArray { reportCategoryOrder(LoudAlertRule.suggested(cat)).forEach { add(it.id) } })
                    })
                }
        }
        val presets = buildJsonArray {
            for ((a, ms, c) in listOf(Triple(LoudAlertIntent.ACTION, 5L, "loud_vehicle"), Triple("other", 5L, "loud_vehicle"), Triple(LoudAlertIntent.ACTION, 0L, null),
                Triple(null, 5L, null), Triple(LoudAlertIntent.ACTION, 7L, "road_traffic"))) {
                val p = LoudAlertIntent.preset(a, ms, c)
                add(buildJsonArray { add(s(a)); add(ms); add(s(c)); add(if (p == null) JsonNull else buildJsonArray { add(p.epochMs); p.suggested.forEach { add(it.id) } }) })
            }
        }
        val points = listOf(null, LocationNoise.Point(2682437, 1247391, 50), LocationNoise.Point(2480000, 1070000, 25), LocationNoise.Point(2839999, 1299999, 200))
        val urls = listOf(
            "https://wenigerlaerm.ch/karte.html", "https://wenigerlaerm.ch/karte.html#lat=47.1&lon=8.5", "https://wenigerlaerm.ch/karte.html?x=1",
            "https://wenigerlaerm.ch/karte.htmlx", "https://wenigerlaerm.ch/datenschutz.html", "http://wenigerlaerm.ch/karte.html",
            "https://wmts.geo.admin.ch/1.0.0/x.png", "https://api.lessnoise.ch/v1/cells.json", "https://api.stadtlaerm.ch",
            "https://wenigerlaerm.ch:443/karte.html", "https://user@wenigerlaerm.ch/", "https://evil.example/karte.html",
            "https://wenigerlaerm.ch.evil.example/", "https://WENIGERLAERM.CH/karte.html", "https://wenigerlaerm.ch/karte.html#\n",
            "https://wenigerlaerm.ch?x", "https://wenigerlaerm.ch#y", "",
        )
        val frags = listOf("#lat=47.37691&lon=8.54169&z=16", "#lat=-1.00000&lon=-180.00000&z=1", "#lat=47.3769&lon=8.54169&z=16",
            "#lat=47.37691&lon=8.54169&z=161", "#lat=47.37691&lon=8.54169&z=16');alert(1);('", "#lat=1234.00000&lon=8.54169&z=16", "")
        val centring = buildJsonArray {
            val p1 = points[1]; val p2 = points[3]
            val ops = listOf("show:1", "show:1", "finished", "show:2", "show:2", "check", "hidden", "show:0", "started", "show:1", "hidden",
                "show:1", "finished", "failed", "show:0", "finished", "show:2", "check", "started", "check", "show:1", "finished", "hidden")
            val mc = MapCentring()
            for (op in ops) {
                val res: Any? = when {
                    op.startsWith("show:") -> mc.show(when (op.last()) { '1' -> p1; '2' -> p2; else -> null })
                    op == "finished" -> mc.pageFinished()
                    op == "started" -> mc.pageStarted().let { null }
                    op == "check" -> mc.check()
                    op == "hidden" -> mc.hidden()
                    op == "failed" -> mc.failed().let { null }
                    else -> error(op)
                }
                add(buildJsonArray {
                    add(op)
                    add(when (res) {
                        null -> JsonNull
                        is MapCentring.Step.Load -> JsonPrimitive("load " + res.url)
                        is MapCentring.Step.Recentre -> JsonPrimitive("recentre " + res.script)
                        is MapCentring.Step.Check -> JsonPrimitive("check " + res.script)
                        is Boolean -> JsonPrimitive(res)
                        else -> error("$res")
                    })
                })
            }
        }
        val tabOps = listOf("MAP", "MAP", "SHARE", "back", "SETTINGS", "MEASURE", "back", "back", "back", "back", "back") +
            (0 until 40).map { listOf("MAP", "SHARE", "SETTINGS", "MEASUREMENTS", "MEASURE")[it % 5] } + listOf("back", "back")
        val tabs = buildJsonArray {
            var h = TabHistory()
            for (op in tabOps) {
                h = if (op == "back") h.back() else h.select(Screen.valueOf(op))
                add(buildJsonArray { add(op); add(h.current.name); add(h.canGoBack); add(h.stack.size); add(h.save().joinToString(",")) })
            }
        }
        val restores = listOf(listOf("MEASURE", "MAP"), listOf(), listOf("MEASURE", "NOPE"), List(31) { "MAP" }, List(32) { "MAP" }, listOf("SETTINGS"))
        val parse = listOf(
            "", "  ", "h26833_12479", "H26833_12479", "47.3769, 8.5417", "8.5417, 47.3769", "47,3769 8,5417", "2'683'304, 1'247'925",
            "1'247'925 2'683'304", "683'304, 247'925", "2 683 304, 1 247 925", "47.3769", "1, 2, 3", "48.9, 2.35", "-47.3769, 8.5417",
            "47.3769°N 8.5417°E", "2’683’304 / 1’247’925", "h99999_99999", "h24000_10700", "46.0, 6.0", "45.0, 5.0",
        )
        val secret = ByteArray(32) { (it * 3 + 1).toByte() }
        val noise = LocationPolicy.Noise(secret, 100)
        val decisions = listOf(
            listOf(47.3769, 8.5417, 12.3, 1.0), listOf(47.3769, 8.5417, 75.0, 1.0), listOf(47.3769, 8.5417, 75.01, 1.0), listOf(47.3769, 8.5417, Double.NaN, 1.0),
            listOf(47.3769, 8.5417, -1.0, 1.0), listOf(47.3769, 8.5417, 12.3, 0.0), listOf(48.9, 2.35, 10.0, 1.0), listOf(47.3769, 8.5417, 50.0, 1.0),
            listOf(47.3769, 8.5417, 50.5, 1.0), listOf(47.3769, 8.5417, 0.2, 1.0),
        )
        fun outcome(o: LocationPolicy.Outcome): JsonElement = buildJsonArray {
            when (o) {
                is LocationPolicy.Outcome.Shifted -> { add("shifted"); add(pointJson(o.point)); add(o.accuracyM?.let { JsonPrimitive(it) } ?: JsonNull) }
                LocationPolicy.Outcome.ApproximateOnly -> add("approximateOnly")
                is LocationPolicy.Outcome.TooInaccurate -> { add("tooInaccurate"); add(o.accuracyM?.let { JsonPrimitive(it) } ?: JsonNull) }
                is LocationPolicy.Outcome.OutsideSwitzerland -> { add("outsideSwitzerland"); add(o.message) }
                LocationPolicy.Outcome.LocationOff -> add("locationOff")
                LocationPolicy.Outcome.NoFix -> add("noFix")
                LocationPolicy.Outcome.PermissionDenied -> add("permissionDenied")
            }
            add(LocationPolicy.message(o))
        }
        write("ui_location.json", buildJsonObject {
            put("alerts", alerts)
            put("presets", presets)
            put("map_urls", buildJsonArray { points.forEach { p -> add(buildJsonArray { add(pointJson(p)); add(MapLinks.url(p)); add(s(MapLinks.viewFragment(p))) }) } })
            put("url_checks", buildJsonArray { urls.forEach { u -> add(buildJsonArray { add(u); add(MapLinks.staysInApp(u)); add(MapLinks.allowedHost(u)) }) } })
            put("fragments", buildJsonArray { frags.forEach { f -> add(buildJsonArray { add(f); add(s(runCatching { MapLinks.recentreScript(f) }.getOrNull())) }) } })
            put("centring", centring)
            put("tabs", tabs)
            put("tab_restore", buildJsonArray { restores.forEach { r -> add(buildJsonArray { add(r.joinToString(",")); add(TabHistory.restore(r).save().joinToString(",")) }) } })
            put("screens", buildJsonArray {
                for (u in listOf(false, true)) for (m in listOf(false, true)) add(buildJsonArray { add(u); add(m); add(appScreens(u, m).joinToString(",") { it.name }) })
            })
            put("cell_input", buildJsonArray {
                parse.forEach { t ->
                    add(buildJsonArray {
                        add(t)
                        when (val p = CellInput.parse(t)) {
                            is CellInput.Parsed.Ok -> { add("ok"); add(p.cell); add(p.how); add(d(p.e)); add(d(p.n)) }
                            is CellInput.Parsed.Error -> { add("error"); add(p.message) }
                        }
                    })
                }
            })
            put("noise_secret_hex", secret.joinToString("") { "%02x".format(it) }); put("noise_sigma", 100)
            put("decide", buildJsonArray {
                decisions.forEach { c ->
                    val acc = c[2]
                    add(buildJsonArray {
                        add(d(c[0])); add(d(c[1])); add(d(acc)); add(c[3] == 1.0)
                        add(outcome(LocationPolicy.decide(c[0], c[1], acc, c[3] == 1.0, noise)))
                        add(when (val r = LocationPolicy.decideReport(c[0], c[1], acc, c[3] == 1.0)) {
                            is LocationPolicy.ReportOutcome.Fix -> buildJsonArray { add("fix"); add(d(r.lat)); add(d(r.lon)); add(d(r.accuracyM)); add(r.cell); add(r.accuracyText) }
                            is LocationPolicy.ReportOutcome.Problem -> buildJsonArray { add("problem"); add(outcome(r.outcome)) }
                        })
                    })
                }
                // No accuracy at all.
                add(buildJsonArray { add(d(47.3769)); add(d(8.5417)); add(JsonNull); add(true); add(outcome(LocationPolicy.decide(47.3769, 8.5417, null, true, noise))); add(JsonNull) })
            })
            put("pasted", buildJsonArray {
                for (t in listOf("h26833_12479", "47.3769, 8.5417")) {
                    val p = CellInput.parse(t) as CellInput.Parsed.Ok
                    add(buildJsonArray { add(t); add(outcome(LocationPolicy.fromPasted(p, noise))) })
                }
            })
            put("messages", buildJsonArray {
                listOf(LocationPolicy.Outcome.ApproximateOnly, LocationPolicy.Outcome.TooInaccurate(null), LocationPolicy.Outcome.TooInaccurate(80),
                    LocationPolicy.Outcome.LocationOff, LocationPolicy.Outcome.NoFix, LocationPolicy.Outcome.PermissionDenied,
                    LocationPolicy.Outcome.Shifted(LocationNoise.Point(2682437, 1247391, 50), null),
                    LocationPolicy.Outcome.Shifted(LocationNoise.Point(2682437, 1247391, 200), 7)).forEach { add(outcome(it)) }
            })
            put("last_known", buildJsonArray {
                for ((age, acc) in listOf(0L to 10.0, 120_000L to 50.0, 120_001L to 10.0, -1L to 10.0, 1_000L to 50.1, 1_000L to null, 1_000L to Double.NaN)) {
                    add(buildJsonArray { add(age); add(d(acc)); add(LocationPolicy.acceptLastKnown(age, acc)); add(LocationPolicy.goodEnough(acc)) })
                }
            })
            put("better", buildJsonArray {
                for ((a, b) in listOf(10.0 to 20.0, 20.0 to 10.0, null to 10.0, 10.0 to null, null to null, Double.NaN to 5.0, 5.0 to 5.0)) {
                    add(buildJsonArray { add(d(a)); add(d(b)); add(LocationPolicy.better(a, b)) })
                }
            })
        })
    }

    // ---- Texts ----------------------------------------------------------------------------------

    @Test
    fun texts() {
        write("texts.json", buildJsonObject {
            put("about", "Every German text of the pure app logic the iOS core carries (single source: the Kotlin constants)")
            put("SHARE_INTRO", SHARE_INTRO); put("LOCATION_NEVER_SENT", LOCATION_NEVER_SENT)
            put("SHARE_SENT_BULLETS", buildJsonArray { SHARE_SENT_BULLETS.forEach { add(it) } })
            put("SHARE_WHERE_BULLETS", buildJsonArray { SHARE_WHERE_BULLETS.forEach { add(it) } })
            put("SHARE_DELETE_BULLETS", buildJsonArray { SHARE_DELETE_BULLETS.forEach { add(it) } })
            put("SHARE_WHEN_BULLETS", buildJsonArray { SHARE_WHEN_BULLETS.forEach { add(it) } })
            put("CONSENT_TITLE", CONSENT_TITLE); put("CONSENT_YES", CONSENT_YES); put("CONSENT_NO", CONSENT_NO); put("CONSENT_MORE", CONSENT_MORE)
            put("STATUS_OFF", STATUS_OFF); put("STATUS_LOCATING", STATUS_LOCATING); put("STATUS_NO_POINT", STATUS_NO_POINT)
            put("STATUS_FIRST_VALUES", STATUS_FIRST_VALUES); put("STATUS_ON_IDLE", STATUS_ON_IDLE); put("STATUS_NO_INTERNET", STATUS_NO_INTERNET)
            put("STATUS_SERVER_BUSY", STATUS_SERVER_BUSY); put("STATUS_REJECTED", STATUS_REJECTED); put("STATUS_AUTH", STATUS_AUTH)
            put("BANNER_NOT_SHARING", BANNER_NOT_SHARING); put("BANNER_NO_POINT", BANNER_NO_POINT); put("BANNER_FIRST_VALUES", BANNER_FIRST_VALUES)
            put("SIGMA_LABEL", SIGMA_LABEL); put("SIGMA_EXPLANATION", SIGMA_EXPLANATION)
            put("statusSent", statusSent("14:05")); put("bannerSent", bannerSent("14:05"))
            put("per_sigma", buildJsonArray {
                LocationNoise.SIGMAS_M.forEach { sg ->
                    add(buildJsonObject {
                        put("sigma", sg)
                        put("consentLines", buildJsonArray { consentLines(sg).forEach { add(it) } })
                        put("shareLocationBullets", buildJsonArray { shareLocationBullets(sg).forEach { add(it) } })
                        put("sigmaChoice", sigmaChoice(sg)); put("pastedText", pastedText(sg))
                    })
                }
            })
            put("AUTH_FAILED_TEXT", Uploader.AUTH_FAILED_TEXT); put("RETRY_TEXT", Uploader.RETRY_TEXT); put("NO_CONNECTION_TEXT", Uploader.NO_CONNECTION_TEXT)
            put("PROGRESS_TEXT", LocationPolicy.PROGRESS_TEXT)
            put("LOUD_TEXT", LoudAlertRule.TEXT); put("LOUD_CHANNEL_NAME", LoudAlertRule.CHANNEL_NAME)
            put("screens", buildJsonArray { Screen.entries.forEach { add(buildJsonArray { add(it.name); add(screenLabel(it)) }) } })
            put("report_categories", buildJsonArray { ReportCategory.entries.forEach { add(buildJsonArray { add(it.id); add(it.label) }) } })
            put("shapes", buildJsonArray { listOf(EventShape.HUMP, EventShape.JAGGED, EventShape.IMPULSE, EventShape.LONG, "other", null).forEach { add(EventShape.nameDe(it)) } })
            put("audio_sources", buildJsonArray { listOf("UNPROCESSED", "VOICE_RECOGNITION", "x").forEach { add(AudioSourceSelector.labelDe(it)) } })
            put("links", buildJsonArray { (Links.all + Links.MAP).forEach { add(it) } })
            put("map_hosts", buildJsonArray { MapLinks.ALLOWED_HOSTS.sorted().forEach { add(it) } })
            put("signed_example", EventFloor.signed(-3.25))
        })
    }

    // ---- Upload rules without a network --------------------------------------------------------

    @Test
    fun uploadRules() = runBlocking {
        var now = 1_791_800_703_456L
        val source = object : UploadSource {
            override suspend fun minutesAfter(afterMs: Long, limit: Int) = emptyList<MinuteEntity>()
            override suspend fun eventsAfter(afterMs: Long, limit: Int) = emptyList<EventEntity>()
            override suspend fun measurements() = emptyList<MeasurementEntity>()
            override suspend fun siteExtras() = SiteExtras("x", "UNPROCESSED", false, 112.35)
        }
        val cases = buildJsonArray {
            for (init in listOf(UploadSnapshot(), UploadSnapshot(sendFloorMs = 5L, deviceId = "d", token = "t", minutesSentUpToMs = now - 1000, eventsSentUpToMs = now - 99_000),
                UploadSnapshot(deviceId = "d", token = "t", sendFloorMs = now - 10L * 24 * 3_600_000))) {
                val store = MemoryUploadStore(init)
                val up = Uploader(UploadClient("https://api.lessnoise.ch"), store, source, MemoryReportStore(), "0.6.3", 16, clock = { now })
                fun snap(name: String) = buildJsonArray {
                    val s = store.state.value
                    add(name); add(s.enabled); add(s.shareDecision.stored); add(s.sendFloorMs); add(s.minutesSentUpToMs); add(s.eventsSentUpToMs)
                    add(up.effectiveFloorMs()); add(up.minutesPendingAfterMs())
                }
                add(buildJsonArray {
                    add(buildJsonArray { add(init.sendFloorMs); add(s(init.deviceId)); add(init.minutesSentUpToMs); add(init.eventsSentUpToMs) })
                    add(snap("init"))
                    up.enable(); add(snap("enable"))
                    now += 1_000
                    up.pointAttached(now - 3_600_000); add(snap("pointAttached"))
                    up.disable(); add(snap("disable"))
                    up.enable(); add(snap("enable2"))
                    up.rewindAfterReevaluation(); add(snap("rewind"))
                })
            }
        }
        write("upload_rules.json", buildJsonObject {
            put("start_now", 1_791_800_703_456L)
            put("about", "Uploader marker rules without network: enable, pointAttached(now−1 h) 1 s later, disable, enable, rewind; columns: step, enabled, decision, sendFloor, minutesSent, eventsSent, effectiveFloor, minutesPendingAfter")
            put("cases", cases)
        })
    }
}

/**
 * The fixtures' layout: objects one key per line; arrays of numbers/strings on one line; arrays
 * of rows (arrays) one row per line; arrays of objects one object per element. Compact enough
 * for the repository, readable in a diff.
 */
private fun goldenFormat(e: JsonElement, indent: String = ""): String = when (e) {
    is JsonObject -> if (e.isEmpty()) "{}" else e.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
        "$indent " + JsonPrimitive(k).toString() + ": " + goldenFormat(v, "$indent ")
    }
    is JsonArray -> when {
        e.isEmpty() -> "[]"
        e.all { it is JsonPrimitive || it is JsonNull } -> e.toString()
        else -> e.joinToString(",\n", "[\n", "\n$indent]") { v -> "$indent " + if (v is JsonArray) v.toString() else goldenFormat(v, "$indent ") }
    }
    else -> e.toString()
}

/** SplitMix64 (the same as the dsp generator's; the app's tests cannot see the dsp test classes). */
class SplitMix(seed: Long) {
    private var x = seed
    fun nextLong(): Long {
        x += -0x61c8864680b583ebL
        var z = x
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
}
