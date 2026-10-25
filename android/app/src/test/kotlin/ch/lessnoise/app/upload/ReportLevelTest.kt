package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.app.ui.ReportLevelMeter
import ch.lessnoise.app.ui.ReportLevelUi
import ch.lessnoise.app.ui.reportLevelEngine
import ch.lessnoise.app.ui.reportPermissions
import ch.lessnoise.dsp.Acoustics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0.8.5: the level while «Laut! Melden» is open — the meter, the dialog's engine (the measurement's
 * dsp engine, no audio tap), the payload, the queue, an old server, and that no `ui/` class can reach
 * the Labor recorder.
 */
class ReportLevelTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val server = FakeServer()
    private val now = ZonedDateTime.of(2026, 10, 25, 9, 40, 3, 0, zone).toInstant().toEpochMilli()

    @AfterTest
    fun stop() = server.close()

    private val loud = ReportLevel(lafMaxDb = 81.4, laeqDb = 66.2, seconds = 9, calibrated = true)

    private fun report(i: Int = 0, level: ReportLevel? = null, direction: ReportDirection? = null) = PendingReport(
        epochMs = now + i * 1000L, timeIso = PendingReport.isoSeconds(now + i * 1000L, zone),
        lat = 47.376912345, lon = 8.541694321, accuracyM = 12.34, category = "motorrad", cell = "h26833_12479",
        direction = direction, level = level,
    )

    private val emptySource = object : UploadSource {
        override suspend fun minutesAfter(afterMs: Long, limit: Int) = emptyList<MinuteEntity>()
        override suspend fun eventsAfter(afterMs: Long, limit: Int) = emptyList<EventEntity>()
        override suspend fun measurements() = emptyList<MeasurementEntity>()
        override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = true, calibrationOffsetDb = 121.9)
    }

    private fun setup(reports: ReportStore): Pair<Uploader, MemoryUploadStore> {
        val store = MemoryUploadStore(UploadSnapshot(explanationSeen = true))
        val up = Uploader(UploadClient(server.baseUrl), store, emptySource, reports, "0.8.5-dev", 37, clock = { now })
        assertTrue(up.enable())
        return up to store
    }

    // ---- the meter ------------------------------------------------------------------------------

    @Test
    fun peakAndEnergeticAverageOfTheValidStepsOnly() {
        var t = 1_000L
        val m = ReportLevelMeter { t }
        m.reset()
        assertNull(m.level(), "nothing measured yet")
        m.step(50.0, 52.0, 50.0, valid = true)
        m.step(70.0, 81.44, 70.0, valid = true)
        m.step(99.0, 120.0, 99.0, valid = false) // silenced / recovering: not counted
        m.step(Double.NaN, 60.0, 60.0, valid = true) // not finite: not counted
        t = 10_900L
        val l = assertNotNull(m.level())
        assertEquals(81.4, l.lafMaxDb, "peak LAFmax, one decimal")
        // LAeq of 50 and 70 dB in equal steps: 10·log10((10^5 + 10^7) / 2) = 67.03 dB.
        assertEquals(67.0, l.laeqDb)
        assertEquals(9, l.seconds, "whole seconds the dialog was open")
        assertFalse(l.calibrated)
        m.setCalibrated(true)
        assertTrue(m.level()!!.calibrated)
        // Reset when the dialog opens again: everything from zero, the peak too.
        m.reset()
        assertNull(m.level())
        assertNull(m.now.value.peakDb)
    }

    @Test
    fun theLineShowsTheNewestStepAndThePeak() {
        val m = ReportLevelMeter { 0L }
        m.step(58.24, 61.0, 58.0, valid = true)
        m.step(57.0, 81.44, 57.0, valid = true)
        assertEquals(57.0, m.now.value.lafDb)
        assertEquals(81.44, m.now.value.peakDb)
        m.step(0.0, 0.0, 0.0, valid = false)
        assertNull(m.now.value.lafDb, "an invalid step shows «–»")
        assertEquals(81.44, m.now.value.peakDb)
        assertEquals("58,2 dB(A)", ReportLevelUi.nowText(58.24))
        assertEquals("– dB(A)", ReportLevelUi.nowText(null))
        assertEquals("Spitze 81,4 dB(A)", ReportLevelUi.peakText(81.44))
        assertEquals("jetzt", ReportLevelUi.NOW)
    }

    @Test
    fun secondsAreAtLeastOneAndAtMostTheServersBound() {
        var t = 0L
        val m = ReportLevelMeter { t }
        m.reset()
        m.step(60.0, 60.0, 60.0, valid = true)
        t = 400L
        assertEquals(1, m.level()!!.seconds)
        t = 3_600_000L
        assertEquals(ReportLevel.MAX_SECONDS, m.level()!!.seconds)
        assertEquals(600, ReportLevel.MAX_SECONDS)
        // Levels stay within the server's 0–140 dB.
        assertEquals(140.0, ReportLevel.round1(151.0))
        assertEquals(0.0, ReportLevel.round1(-3.0))
    }

    /** The dialog's engine is the measurement's: a 1 kHz tone of known level reads the same through it. */
    @Test
    fun theDialogsEngineAppliesTheCalibrationOffsetLikeAMeasurement() {
        val m = ReportLevelMeter { 0L }
        val offset = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        val eng = reportLevelEngine(offset, calibrated = true, calibrationId = 7, source = "UNPROCESSED", meter = m)
        val fs = Acoustics.SAMPLE_RATE
        val amp = 0.1
        val block = FloatArray(fs / 8)
        var n = 0L
        repeat(8 * 3) {
            for (i in block.indices) { block[i] = (amp * sin(2 * PI * 1000.0 * n / fs)).toFloat(); n++ }
            eng.process(block)
        }
        val l = assertNotNull(m.level())
        // A-weighting is 0 dB at 1 kHz: the level is the RMS in dBFS plus the offset.
        val expected = 20 * log10(amp / sqrt(2.0)) + offset
        assertTrue(abs(l.laeqDb - expected) < 0.3, "LAeq ${l.laeqDb} vs $expected")
        assertTrue(abs(l.lafMaxDb - expected) < 0.5, "LAFmax ${l.lafMaxDb} vs $expected")
    }

    @Test
    fun theDialogAsksForTheMicrophoneTogetherWithTheLocation() {
        assertEquals(emptyList(), reportPermissions(needLocation = false, micAllowed = true).toList())
        assertEquals(listOf("android.permission.RECORD_AUDIO"), reportPermissions(needLocation = false, micAllowed = false).toList())
        assertEquals(
            listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.RECORD_AUDIO"),
            reportPermissions(needLocation = true, micAllowed = false).toList(),
        )
    }

    // ---- stored and sent ------------------------------------------------------------------------

    @Test
    fun theLevelIsStoredAndKeptAfterSending() {
        val r = listOf(report(0, loud), report(1))
        val json = ReportJson.pending(r)
        assertEquals(r, ReportJson.parsePending(json))
        assertTrue(json.contains(""""lvl":{"max":81.4,"eq":66.2,"s":9,"cal":true}"""), json)
        // Queues of 0.8.4 have none; a garbled level is dropped, the report kept.
        val old = ReportJson.parsePending("""[{"t":1,"iso":"x","lat":47.0,"lon":8.0,"acc":5.0,"cat":"auto","lvl":{"max":"x"}}]""")
        assertEquals(listOf<ReportLevel?>(null), old.map { it.level })
        val sent = r.map { it.toSent() }
        assertEquals(listOf(81.4, null), sent.map { it.lafMaxDb })
        assertEquals(listOf(66.2, null), sent.map { it.laeqDb })
        assertEquals(sent, ReportJson.parseSent(ReportJson.sent(sent)))
    }

    @Test
    fun theLevelIsSentAsTheAgreedObject() {
        val o = Payloads.report(report(0, loud))
        assertEquals(setOf("time", "lat", "lon", "accuracy_m", "category", "level"), o.keys)
        assertEquals("""{"lafmax_db":81.4,"laeq_db":66.2,"seconds":9,"calibrated":true}""", o["level"].toString())
        assertFalse("level" in Payloads.report(report(0)), "none measured: the 0.6.6 request")
    }

    @Test
    fun aNewServerGetsTheLevel() = runBlocking {
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0, loud))
        val (up, store) = setup(reports)
        assertIs<Uploader.Outcome.Done>(up.run())
        val stored = server.reports[store.state.value.deviceId]!!.values.single()
        assertEquals(81.4, (stored["level"] as JsonObject)["lafmax_db"].toString().toDouble())
        assertEquals(listOf(81.4), reports.state.value.sent.map { it.lafMaxDb })
    }

    @Test
    fun anOldServerGetsTheReportAgainWithoutTheLevelInsteadOfLosingIt() = runBlocking {
        server.rejectLevel = true
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0, loud))
        ReportQueue.add(reports, report(1))
        val (up, store) = setup(reports)
        val o = up.run()
        assertIs<Uploader.Outcome.Done>(o)
        assertEquals(2, o.reports)
        val stored = server.reports[store.state.value.deviceId]!!.values.toList()
        assertEquals(2, stored.size)
        assertTrue(stored.none { "level" in it })
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(0, store.state.value.refusedTotal, "not counted as refused")
        assertEquals(2, server.count("POST", "/reports"), "one more request, in the same run")
        // The list keeps no level the server never got.
        assertEquals(listOf<Double?>(null, null), reports.state.value.sent.map { it.lafMaxDb })
    }

    @Test
    fun aServerOlderThanBothGetsTheReportWithoutEither() = runBlocking {
        server.rejectLevel = true
        server.rejectDirection = true
        val east = ReportDirection(87, ReportDirection.North.TRUE, ReportDirection.Accuracy.HIGH)
        val reports = MemoryReportStore()
        ReportQueue.add(reports, report(0, loud, east))
        ReportQueue.add(reports, report(1, null, east))
        ReportQueue.add(reports, report(2, loud, null))
        val (up, store) = setup(reports)
        assertIs<Uploader.Outcome.Done>(up.run())
        val stored = server.reports[store.state.value.deviceId]!!.values.toList()
        assertEquals(3, stored.size)
        assertTrue(stored.none { "level" in it || "direction" in it })
        assertTrue(reports.state.value.pending.isEmpty())
        assertEquals(0, store.state.value.refusedTotal)
    }

    @Test
    fun onlyRefusalsOfTheOptionalFieldsTheReportCarriesAreRetried() {
        val r = report(0, loud)
        assertTrue(Uploader.onlyOptional(r, listOf("level")))
        assertTrue(Uploader.onlyOptional(r, listOf("level.seconds", "level")))
        assertFalse(Uploader.onlyOptional(r, emptyList()))
        assertFalse(Uploader.onlyOptional(r, listOf("level", "lat")))
        assertFalse(Uploader.onlyOptional(r, listOf("levels")))
        assertFalse(Uploader.onlyOptional(r, listOf("direction")), "it has no direction to drop")
        assertFalse(Uploader.onlyOptional(report(0), listOf("level")), "it has no level to drop")
    }

    // ---- no audio, no recorder -------------------------------------------------------------------

    /**
     * The dialog's engine has no audio tap: ReportLevel.kt builds the engine with neither category
     * mapper nor event head and hands the capture's blocks to the engine only; and no file in any
     * `ui/` package (main and every flavour folder) refers to the Labor recorder, the tap or its provider.
     */
    @Test
    fun theDialogsEngineHasNoTapAndNoUiClassReachesTheLaborRecorder() {
        val app = appDir()
        val level = KotlinSource.parse(File(app, "src/main/kotlin/ch/lessnoise/app/ui/ReportLevel.kt").readText()).code
        assertEquals(1, Regex("""\bMeasurementEngine\(""").findAll(level).count(), "one engine")
        assertTrue(level.contains("mapper = null,") && level.contains("eventHead = null,") && level.contains("classifierEnabled = false"))
        assertTrue(level.contains("onBlock = { samples, count -> engine.process(samples, count) }"), "blocks go to the engine only")
        assertEquals(1, Regex("""\bAudioCapture\(""").findAll(level).count())
        for (bad in listOf("AudioTap", "Tap", "onSessionStart", "Labor", "File", "write", "MediaRecorder", "MediaCodec", "Upload")) {
            assertFalse(Regex("""\b$bad\w*""").containsMatchIn(level), "ReportLevel.kt mentions «$bad»")
        }
        val uiDirs = File(app, "src").listFiles().orEmpty().filter { !it.name.startsWith("test") }
            .map { File(it, "kotlin/ch/lessnoise/app/ui") }.filter { it.isDirectory }
        assertTrue(uiDirs.size >= 4, uiDirs.toString())
        val forbidden = Regex("""\b(LaborRecorder|AudioTapProvider|AudioTap|NoAudioTap|AudioTapSession)\b|\bch\.lessnoise\.app\.labor\b""")
        val hits = uiDirs.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { f -> forbidden.containsMatchIn(KotlinSource.parse(f.readText()).code) }
            .map { it.relativeTo(app).path }
        assertEquals(emptyList(), hits, "ui/ classes that reach the Labor recorder")
        // The service is the only place that creates a tap.
        val service = File(app, "src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains("AudioTapProvider.create(this)"))
        // The dialog stops its engine when it closes (DisposableEffect), opens no second microphone while measuring.
        assertTrue(level.contains("DisposableEffect(measuring, micAllowed)"))
        assertTrue(level.contains("if (measuring || !micAllowed) return@DisposableEffect onDispose {}"))
        assertTrue(level.contains("c.appScope.launch(Dispatchers.IO) { mic.close() }"))
    }

    private fun appDir(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { it.parentFile }.take(4)
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
            .firstOrNull { File(it, "src/main/kotlin/ch/lessnoise/app/ui").isDirectory } ?: fail("app module not found from $start")
    }
}
