package ch.lessnoise.app.labor

import ch.lessnoise.dsp.EventFeatures
import ch.lessnoise.dsp.EventShape
import ch.lessnoise.dsp.NoiseEvent
import ch.lessnoise.dsp.classify.CategoryDecision
import ch.lessnoise.dsp.classify.ClassifierResult
import ch.lessnoise.dsp.classify.LabelScore
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoliciesAndManifestTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private fun ms(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()

    // ---- Storage cap ----------------------------------------------------------------------

    @Test
    fun storageCapStopsAndLatches() {
        val b = StorageBudget(maxBytes = 1_000, initialUsedBytes = 400)
        assertFalse(b.full)
        assertTrue(b.tryReserve(600)) // exactly at the cap is allowed
        assertEquals(1_000, b.usedBytes)
        assertFalse(b.tryReserve(1)) // one more byte: full
        assertTrue(b.full)
        assertFalse(b.tryReserve(0)) // latched for the rest of the session
        assertEquals(1_000, b.usedBytes) // a refused reservation is not counted
    }

    @Test
    fun storageCapAccountingAfterTheFact() {
        val b = StorageBudget(maxBytes = 100, initialUsedBytes = 0)
        assertTrue(b.account(60))
        assertTrue(b.account(40))
        assertFalse(b.account(1))
        assertTrue(b.full)
        // Already over the cap from earlier sessions: full right away.
        assertTrue(StorageBudget(100, 100).full)
        // 0.7.5: 5 GB for new installs; the choices are unchanged.
        assertEquals(5_000_000_000L, (StorageBudget.DEFAULT_GB * StorageBudget.GB).toLong())
        assertEquals(5_000_000_000L, LaborSettings().maxBytes)
        assertEquals(listOf(0.5, 1.0, 2.0, 5.0, 10.0), StorageBudget.CHOICES_GB)
        assertTrue(StorageBudget.DEFAULT_GB in StorageBudget.CHOICES_GB)
    }

    @Test
    fun fullAudioBudgetKeepsTheLogsGoingWithinTheMargin() {
        // 0.7.5: the 2026-10-18 case — the cap is reached; audio stops, the logs go on within the margin.
        val b = StorageBudget(maxBytes = 1_000, initialUsedBytes = 990, logMarginBytes = 100)
        assertFalse(b.tryReserve(20)) // a clip no longer fits: audio stops
        assertTrue(b.full)
        assertFalse(b.logsFull)
        val gate = LogGate(b)
        var written = 0
        // Manifest and classifier-log lines of 30 bytes: 990 + 3·30 = 1080 ≤ 1100 are written …
        repeat(3) { assertTrue(gate.write(essential = false) { written++; 30L }) }
        assertFalse(b.logsFull)
        // … the 4th crosses cap + margin (1110 > 1100): written, then the margin is used up.
        assertTrue(gate.write(essential = false) { written++; 30L })
        assertTrue(b.logsFull)
        // From now on only essential lines (session_stop, storage_full, logs_full) are written.
        assertFalse(gate.write(essential = false) { written++; 30L })
        assertFalse(gate.write(essential = false) { written++; 30L })
        assertTrue(gate.write(essential = true) { written++; 30L })
        assertEquals(5, written)
        assertEquals(2, gate.dropped)
        // A session that starts already full: audio full at once, logging still allowed.
        val next = StorageBudget(maxBytes = 1_000, initialUsedBytes = 1_000, logMarginBytes = 100)
        assertTrue(next.full)
        assertFalse(next.logsFull)
        assertTrue(LogGate(next).write(essential = false) { 10L })
        assertTrue(StorageBudget(1_000, 1_100, 100).logsFull)
        // Log lines also count towards the audio cap: they can fill it, then the audio stops.
        val c = StorageBudget(maxBytes = 100, initialUsedBytes = 95, logMarginBytes = 50)
        assertTrue(c.accountLog(10))
        assertTrue(c.full)
        assertFalse(c.tryReserve(1))
        assertEquals(StorageBudget.LOG_MARGIN_BYTES, StorageBudget(1, 0).logMarginBytes)
    }

    @Test
    fun nearCapWarningAndBlock() {
        val gb = StorageBudget.GB
        fun level(used: Long, max: Long, on: Boolean = true) = StoragePolicy.level(used, max, on)
        // 5 GB: warn below max(10 % = 500 MB, 200 MB) = 500 MB free.
        assertEquals(500_000_000L, StoragePolicy.warnBelowBytes(5 * gb))
        assertEquals(StoragePolicy.Level.NONE, level(4_500_000_000L, 5 * gb))
        assertEquals(StoragePolicy.Level.WARN, level(4_500_000_001L, 5 * gb))
        assertEquals(StoragePolicy.Level.WARN, level(5 * gb - 1, 5 * gb))
        assertEquals(StoragePolicy.Level.BLOCK, level(5 * gb, 5 * gb))
        assertEquals(StoragePolicy.Level.BLOCK, level(5 * gb + 150_000_000L, 5 * gb), "beyond the cap (log margin): still blocked")
        // 1 GB: 10 % = 100 MB < 200 MB, so the 200 MB floor applies.
        assertEquals(200_000_000L, StoragePolicy.warnBelowBytes(gb))
        assertEquals(StoragePolicy.Level.NONE, level(800_000_000L, gb))
        assertEquals(StoragePolicy.Level.WARN, level(800_000_001L, gb))
        // Recording off: nothing, even when full (the cap applies to audio).
        assertEquals(StoragePolicy.Level.NONE, level(5 * gb, 5 * gb, on = false))
        assertEquals(null, StoragePolicy.message(5 * gb, 5 * gb, false))
        // The texts.
        assertEquals(
            "Speicher fast voll: 312 MB frei von 5 GB – Aufnahme würde bald stoppen. Exportieren und löschen, oder Limit erhöhen.",
            StoragePolicy.message(5 * gb - 312_000_000L, 5 * gb, true),
        )
        assertEquals(
            "Speicher voll: 0 MB frei von 2 GB – Messen startet nicht ohne Platz für die Aufnahme. " +
                "Exportieren und löschen, Limit erhöhen oder Aufnahme ausschalten.",
            StoragePolicy.message(2 * gb + 5, 2 * gb, true),
        )
        assertEquals("0,5 GB", StoragePolicy.gbLabel(500_000_000L))
        // «Audio aufzeichnen» on, but neither clips nor hour files: no audio, so no warning or block.
        assertTrue(StoragePolicy.recordsAudio(LaborSettings(recordAudio = true)))
        assertFalse(StoragePolicy.recordsAudio(LaborSettings(recordAudio = true, clips = false, continuous = false)))
        assertFalse(StoragePolicy.recordsAudio(LaborSettings(recordAudio = false)))
    }

    // ---- Clip rate --------------------------------------------------------------------------

    @Test
    fun clipRateKeepsFirstThenEveryNth() {
        fun pattern(n: Int) = ClipRateSampler(n).let { s -> List(11) { s.next() } }
        assertEquals(List(11) { true }, pattern(1))
        assertEquals(List(11) { it % 2 == 0 }, pattern(2))
        assertEquals(List(11) { it % 5 == 0 }, pattern(5)) // events 1, 6, 11
        val s = ClipRateSampler(5)
        repeat(11) { s.next() }
        assertEquals(11, s.seen); assertEquals(8, s.skipped)
    }

    // ---- Hour rollover ------------------------------------------------------------------------

    @Test
    fun newFileAtStartAndAtEveryWallClockHour() {
        val r = HourRoller(zurich)
        assertTrue(r.needsNewFile(ms("2026-10-07T23:42:10.500+02:00[Europe/Zurich]"))) // measurement start
        assertEquals("20261007_23", r.baseName(ms("2026-10-07T23:42:10.500+02:00[Europe/Zurich]")))
        assertFalse(r.needsNewFile(ms("2026-10-07T23:59:59.999+02:00[Europe/Zurich]")))
        assertTrue(r.needsNewFile(ms("2026-10-08T00:00:00.000+02:00[Europe/Zurich]")))
        assertEquals("20261008_00", r.baseName(ms("2026-10-08T00:00:00.000+02:00[Europe/Zurich]")))
        assertFalse(r.needsNewFile(ms("2026-10-08T00:00:00.125+02:00[Europe/Zurich]")))
        assertEquals(ms("2026-10-08T01:00:00+02:00[Europe/Zurich]"), r.nextBoundaryMs(ms("2026-10-08T00:12:00+02:00[Europe/Zurich]")))
    }

    @Test
    fun daylightSavingTimeHours() {
        // End of DST, 25.10.2026: 02:00–02:59 happens twice (CEST, then CET).
        val r = HourRoller(zurich)
        val firstTwo = ms("2026-10-25T02:30:00+02:00")
        val secondTwo = ms("2026-10-25T02:30:00+01:00")
        assertTrue(r.needsNewFile(firstTwo))
        assertTrue(r.needsNewFile(ms("2026-10-25T02:00:00+01:00"))) // the repeated hour is a new file
        assertFalse(r.needsNewFile(secondTwo))
        assertEquals(r.baseName(firstTwo), r.baseName(secondTwo)) // same name → made unique on disk
        assertTrue(r.needsNewFile(ms("2026-10-25T03:00:00+01:00")))
        // Start of DST, 29.3.2026: 02:00 does not exist, 01:59:59 is followed by 03:00.
        val s = HourRoller(zurich)
        assertTrue(s.needsNewFile(ms("2026-03-29T01:59:59+01:00")))
        assertTrue(s.needsNewFile(ms("2026-03-29T03:00:00+02:00")))
        assertEquals("20260329_03", s.baseName(ms("2026-03-29T03:00:00+02:00")))
        assertEquals(ms("2026-03-29T03:00:00+02:00"), s.nextBoundaryMs(ms("2026-03-29T01:30:00+01:00")))
    }

    @Test
    fun uniqueNames() {
        val taken = setOf("20261025_02.m4a", "20261025_02_2.m4a")
        assertEquals("20261025_02_3.m4a", HourRoller.unique("20261025_02", "m4a") { it in taken })
        assertEquals("20261025_03.m4a", HourRoller.unique("20261025_03", "m4a") { it in taken })
    }

    // ---- Manifest ------------------------------------------------------------------------------

    private val t0 = ms("2026-10-07T23:42:10.250+02:00[Europe/Zurich]")

    @Test
    fun jsonEscapingAndNumbers() {
        val j = Json().put("s", "a\"b\\c\nd\u0001ü").put("n", Double.NaN).put("x", 1.5).put("y", 2.0).put("z", -0.0004)
            .put("i", 3).put("l", null as Long?).put("b", true).putStrings("e", listOf("AGC: aus", "x")).build()
        assertEquals("""{"s":"a\"b\\c\nd\u0001ü","n":null,"x":1.5,"y":2,"z":-0,"i":3,"l":null,"b":true,"e":["AGC: aus","x"]}""", j)
    }

    @Test
    fun sessionAndClockLines() {
        val info = ManifestLines.SessionInfo(
            deviceModel = "Google Pixel 7", androidRelease = "15", sdkInt = 35, appVersion = "0.3.3-labor", appVersionCode = 7,
            audioSource = "UNPROCESSED", encoding = "PCM_FLOAT", effects = listOf("AGC: nicht vorhanden"),
            inputDevice = "eingebautes Mikrofon (bottom)",
            calibrationId = 4, calibrationOffsetDb = 112.25, calibrated = true,
            eventExcessDb = 6.5, localFloorWindowSeconds = 30.0, eventMinLevelDb = 30.0, windLfShareMin = 0.95, windFlutterMinDb = 3.8,
            classifierEnabled = true, classifierNormalize = true,
            classifierIntervalSeconds = 1.0,
            clips = true, continuous = false, clipEvery = 2, maxBytes = 2_000_000_000,
        )
        assertEquals(
            """{"type":"session_start","session":"20261007_234210","time":"2026-10-07T23:42:10.250+02:00","zone":"Europe/Zurich",""" +
                """"device":"Google Pixel 7","android":"15","sdk":35,"app":"0.3.3-labor","appCode":7,"audioSource":"UNPROCESSED",""" +
                """"encoding":"PCM_FLOAT","effects":["AGC: nicht vorhanden"],"inputDevice":"eingebautes Mikrofon (bottom)","calibrationId":4,"calibrationOffsetDb":112.25,"calibrated":true,""" +
                """"detector":"v2","eventExcessDb":6.5,"localFloorWindowS":30,"eventFloorDb":30,"windLfShareMin":0.95,"windFlutterMinDb":3.8,""" +
                """"classifier":true,"classifierLevelAdjustment":true,"classifierIntervalS":1,""" +
                """"inputSampleRate":48000,"clips":true,"continuous":false,"clipEvery":2,"maxBytes":2000000000}""",
            ManifestLines.sessionStart("20261007_234210", t0, zurich, info),
        )
        assertEquals(
            """{"type":"clock","session":"S","time":"2026-10-07T23:42:10.250+02:00","anchorSample":6000,"anchorEpochMs":$t0,""" +
                """"anchorTime":"2026-10-07T23:42:10.250+02:00","correctionMs":-612}""",
            ManifestLines.clock("S", t0, zurich, 6000, t0, -612),
        )
    }

    @Test
    fun clipLine() {
        val ev = NoiseEvent(
            startEpochMs = t0, startIso = "", durationSeconds = 2.375, lafMaxDb = 71.04, selDb = 74.5, backgroundDb = 38.2,
            thresholdDb = 10.0, dominantCategory = "loud_vehicle", dominantScore = 0.5f,
            topLabels = listOf(LabelScore("Motorcycle", 0.75f), LabelScore("Vehicle", 0.5f), LabelScore("Car", 0.25f), LabelScore("X", 0.1f)),
            classifierFrames = 3, calibrationId = 4, audioSource = "UNPROCESSED", calibrated = true,
        )
        val clip = AssembledClip(
            eventStartSample = 480_000, eventEndSample = 594_000, clipStartSample = 240_000,
            chunks = emptyList(), length = 594_000 + 240_000 - 240_000, truncated = false, endedEarly = false,
        )
        // Classifier windows ending at 6 s and 7 s (inside the clip that starts at 5 s).
        val trace = listOf(result(288_000, t0 + 1000, gain = 31.24), result(336_000, t0 + 2000, gain = 0.0, ignored = true))
        val line = ManifestLines.clip("S", t0, zurich, 17, "clips/ev_17_20261007_234210.wav", ev, clip, 198_000, trace)
        assertEquals(
            """{"type":"clip","session":"S","time":"2026-10-07T23:42:10.250+02:00","eventId":17,"file":"clips/ev_17_20261007_234210.wav",""" +
                """"eventStart":"2026-10-07T23:42:10.250+02:00","eventEnd":"2026-10-07T23:42:12.625+02:00","durationS":2.375,""" +
                """"lafMaxDb":71.04,"selDb":74.5,"backgroundDb":38.2,"category":"loud_vehicle","categoryScore":0.5,""" +
                """"top3":[{"label":"Motorcycle","score":0.75},{"label":"Vehicle","score":0.5},{"label":"Car","score":0.25}],""" +
                """"offsetOfEventStartInClipMs":5000,"clipDurationMs":12375,"sampleRate":16000,"truncated":false,"postRollCut":false,""" +
                """"eventStartSample":480000,"eventEndSample":594000,"clipStartSample":240000,""" +
                """"classifierTrace":[""" +
                """{"time":"2026-10-07T23:42:11.250+02:00","atMsInClip":1000,"top5":[{"label":"Vehicle","score":0.4125},""" +
                """{"label":"Car","score":0.2},{"label":"Silence","score":0.0001},{"label":"Wind","score":0},{"label":"Rain","score":0}],""" +
                """"category":"loud_vehicle","categoryScore":0.4125,"gainDb":31.2,"lafDb":52.3,"lafMaxDb":55.1,"ignored":false,"endSample":288000},""" +
                """{"time":"2026-10-07T23:42:12.250+02:00","atMsInClip":2000,"top5":[{"label":"Vehicle","score":0.4125},""" +
                """{"label":"Car","score":0.2},{"label":"Silence","score":0.0001},{"label":"Wind","score":0},{"label":"Rain","score":0}],""" +
                """"category":null,"categoryScore":null,"gainDb":0,"lafDb":52.3,"lafMaxDb":55.1,"ignored":true,"endSample":336000}]}""",
            line,
        )
        assertFalse('\n' in line)
    }

    @Test
    fun eventLineAndClipLineCarryTheDetectorV2Numbers() {
        val f = EventFeatures(41.84, 12.66, 1.748, Double.NaN, 0.0141, 12.52, 0.0083, 0.391, false, EventShape.HUMP)
        val line = ManifestLines.event("S", t0, zurich, 480_000, 702_000, t0, t0 + 4625, f)
        assertEquals(
            """{"type":"event","session":"S","time":"2026-10-07T23:42:10.250+02:00","eventStart":"2026-10-07T23:42:10.250+02:00",""" +
                """"eventEnd":"2026-10-07T23:42:14.875+02:00","durationS":4.625,"eventStartSample":480000,"eventEndSample":702000,""" +
                """"features":{"localFloorDb":41.84,"excessDb":12.66,"riseS":1.748,"decayS":null,"jaggedness":0.0141,""" +
                """"midBandRiseDb":12.52,"lfShare":0.0083,"lfFlutterDb":0.39,"wind":false,"shape":"hump"}}""",
            line,
        )
        val ev = NoiseEvent(
            startEpochMs = t0, startIso = "", durationSeconds = 0.625, lafMaxDb = 57.9, selDb = 52.2, backgroundDb = 41.9,
            thresholdDb = 6.5, dominantCategory = "wind", dominantScore = 0f, topLabels = emptyList(),
            classifierFrames = 1, calibrationId = null, audioSource = "UNPROCESSED", calibrated = false,
            features = f.copy(wind = true, lfShare = 0.985, shape = EventShape.IMPULSE),
        )
        val clip = AssembledClip(480_000, 510_000, 240_000, emptyList(), 510_000, truncated = false, endedEarly = false)
        val clipLine = ManifestLines.clip("S", t0, zurich, 3, "clips/ev_3.wav", ev, clip, 170_000)
        assertTrue(""""category":"wind","categoryScore":0,"top3":[],"features":{"localFloorDb":41.84,""" in clipLine, clipLine)
        assertTrue(""""lfShare":0.985,"lfFlutterDb":0.39,"wind":true,"shape":"impulse"},"offsetOfEventStartInClipMs":5000""" in clipLine, clipLine)
    }

    /** The event head's output (logging only) goes into the event line (with inputs) and the clip line (without). */
    @Test
    fun eventAndClipLinesCarryTheEventHead() {
        val h = ch.lessnoise.dsp.classify.EventHeadResult(
            model = "weniger-laerm-head-F1-MLP",
            scores = linkedMapOf("carried" to 0.12345678, "road" to 0.66, "aircraft" to 0.0001),
            decision = listOf("road"),
            inputs = linkedMapOf("excessDb" to 12.66, "per50_hz" to Double.NaN),
        )
        val f = EventFeatures(41.84, 12.66, 1.748, Double.NaN, 0.0141, 12.52, 0.0083, 0.391, false, EventShape.HUMP, head = h)
        val line = ManifestLines.event("S", t0, zurich, 480_000, 702_000, t0, t0 + 4625, f)
        assertTrue(
            line.endsWith(""""wind":false,"shape":"hump"},"head":{"model":"weniger-laerm-head-F1-MLP",""" +
                """"scores":{"carried":0.1235,"road":0.66,"aircraft":0.0001},"decision":["road"],""" +
                """"inputs":{"excessDb":12.66,"per50_hz":null}}}"""),
            line,
        )
        val ev = NoiseEvent(
            startEpochMs = t0, startIso = "", durationSeconds = 4.625, lafMaxDb = 57.9, selDb = 52.2, backgroundDb = 41.9,
            thresholdDb = 6.5, dominantCategory = "road_traffic", dominantScore = 0.4f, topLabels = emptyList(),
            classifierFrames = 1, calibrationId = null, audioSource = "UNPROCESSED", calibrated = false, features = f,
        )
        assertEquals(listOf("road"), ev.headDecision)
        val clip = AssembledClip(480_000, 702_000, 240_000, emptyList(), 702_000, truncated = false, endedEarly = false)
        val clipLine = ManifestLines.clip("S", t0, zurich, 3, "clips/ev_3.wav", ev, clip, 170_000)
        assertTrue(
            """"shape":"hump"},"head":{"model":"weniger-laerm-head-F1-MLP","scores":{"carried":0.1235,"road":0.66,"aircraft":0.0001},""" +
                """"decision":["road"]},"offsetOfEventStartInClipMs":5000""" in clipLine,
            clipLine,
        )
        // The category written stays the app's (YAMNet) decision.
        assertTrue(""""category":"road_traffic"""" in clipLine, clipLine)
    }

    @Test
    fun continuousAndStopLines() {
        val open = ManifestLines.continuousOpen("S", t0, zurich, "continuous/20261007_23.m4a", 6000, t0)
        assertTrue(open.startsWith("""{"type":"continuous_open","session":"S","""))
        assertTrue(""""startInputSample":6000,"sampleRate":16000,"channels":1,"codec":"AAC-LC","bitRate":64000""" in open, open)
        val close = ManifestLines.continuousClose("S", t0, zurich, "continuous/20261007_23.m4a", 16_000 * 90, 720_000, "hour")
        assertTrue(""""samples":1440000,"durationS":90,"bytes":720000,"reason":"hour"}""" in close, close)
        val gap = ManifestLines.gap("S", t0, zurich, "continuous/x.m4a", 48_000, 54_000, true)
        assertTrue(""""fromInputSample":48000,"toInputSample":54000,"durationMs":125,"filledWithSilence":true}""" in gap, gap)
        val stop = ManifestLines.sessionStop("S", t0, zurich, ManifestLines.Counts(clipsWritten = 3, continuousBlocksDropped = 2), false)
        assertTrue(stop.endsWith(""""clipsWritten":3,"clipsSkippedRate":0,"clipsDroppedBusy":0,"clipsDroppedQueue":0,"clipsDroppedStorage":0,""" +
            """"clipsWithoutEventId":0,"clipErrors":0,"continuousFiles":0,"continuousBlocksDropped":2,"clipsSkipped":0,"linesDropped":0,"storageFull":false}"""), stop)
        // 0.7.5: events without a clip because the audio budget was used up, and lines beyond the log margin.
        val full = ManifestLines.sessionStop("S", t0, zurich, ManifestLines.Counts(clipsSkipped = 41, linesDropped = 7), true)
        assertTrue(full.endsWith(""""clipsSkipped":41,"linesDropped":7,"storageFull":true}"""), full)
        val sf = ManifestLines.storageFull("S", t0, zurich, 2_000_000_100L, 2_000_000_000L)
        assertTrue(sf.startsWith("""{"type":"storage_full","session":"S","""), sf)
        assertTrue(sf.endsWith(""""usedBytes":2000000100,"maxBytes":2000000000,"audio":false,"logging":true}"""), sf)
        val lf = ManifestLines.logsFull("S", t0, zurich, 5_200_000_001L, 5_000_000_000L, 200_000_000L)
        assertTrue(lf.endsWith(""""usedBytes":5200000001,"maxBytes":5000000000,"marginBytes":200000000}"""), lf)
    }

    @Test
    fun reportLine() {
        // 0.7.6, Labor·Karte: «Senden» in «Laut! Melden» writes one line; no coordinates.
        // 0.8.5: whether a photo was taken instead of the plate (no file name, no image).
        // 0.9.4.3: `media` «video», «photo» or null (until 0.9.4.2 `photo` true/false).
        val r = ManifestLines.report("S", t0, zurich, "motorrad", "video", 47, "true", "high")
        assertTrue(r.startsWith("""{"type":"report","session":"S","""), r)
        assertTrue(r.endsWith(""""cat":"motorrad","media":"video","direction":{"deg":47,"north":"true","accuracy":"high"}}"""), r)
        val photo = ManifestLines.report("S", t0, zurich, "auto", "photo", null, null, null)
        assertTrue(photo.endsWith(""""cat":"auto","media":"photo","direction":null}"""), photo)
        val bare = ManifestLines.report("none", t0, zurich, "auto", null, null, null, null)
        assertTrue(bare.endsWith(""""cat":"auto","media":null,"direction":null}"""), bare)
        for (bad in listOf("lat", "lon", "cell", "plate", ".jpg", ".mp4", "\"photo\":")) assertFalse(r.contains(bad), bad)
    }

    // ---- Classifier trace and log ------------------------------------------------------------

    private fun result(endSample: Long, endMs: Long, gain: Double, ignored: Boolean = false) = ClassifierResult(
        endSample = endSample, windowSamples48k = 46_800, endEpochMs = endMs,
        top = listOf(
            LabelScore("Vehicle", 0.41249f), LabelScore("Car", 0.2f), LabelScore("Silence", 0.00011f),
            LabelScore("Wind", 0.00002f), LabelScore("Rain", 0f), LabelScore("Sixth", 0f),
        ),
        decision = if (ignored) null else CategoryDecision("loud_vehicle", 0.41249f, mapOf("loud_vehicle" to 0.41249f)),
        inputGainDb = gain, lafDb = 52.26, lafMaxDb = 55.08, ignoredInvalid = ignored,
    )

    @Test
    fun classifierLogLine() {
        val line = ManifestLines.classifierLine("20261007_234210", result(1_234_567, t0, gain = 40.0), zurich)
        assertEquals(
            """{"time":"2026-10-07T23:42:10.250+02:00","top5":[{"label":"Vehicle","score":0.4125},{"label":"Car","score":0.2},""" +
                """{"label":"Silence","score":0.0001},{"label":"Wind","score":0},{"label":"Rain","score":0}],"category":"loud_vehicle",""" +
                """"categoryScore":0.4125,"gainDb":40,"lafDb":52.3,"lafMaxDb":55.1,"ignored":false,"endSample":1234567,""" +
                """"session":"20261007_234210"}""",
            line,
        )
        assertFalse('\n' in line)
        // Small text: one line per second is about 0.3 kB, ≈ 1.1 MB per hour.
        assertTrue(line.length in 200..400, "${line.length}")
    }

    @Test
    fun classifierLogRollsOverAtTheWallClockHour() {
        val dir = Files.createTempDirectory("cls").toFile()
        try {
            ClassifierLog(dir, zurich).use { log ->
                val a = ms("2026-10-07T23:59:59.000+02:00[Europe/Zurich]")
                val b = ms("2026-10-08T00:00:00.000+02:00[Europe/Zurich]")
                assertEquals("20261007_23.jsonl", log.fileName(a))
                assertEquals(8L, log.append(a, "{\"a\":1}"))
                log.append(a + 500, "{\"a\":2}")
                log.append(b, "{\"b\":1}")
            }
            // A later measurement in the same hour appends.
            ClassifierLog(dir, zurich).use { it.append(ms("2026-10-08T00:30:00+02:00[Europe/Zurich]"), "{\"b\":2}") }
            assertEquals(listOf("20261007_23.jsonl", "20261008_00.jsonl"), dir.list()!!.sorted())
            assertEquals("{\"a\":1}\n{\"a\":2}\n", File(dir, "20261007_23.jsonl").readText())
            assertEquals("{\"b\":1}\n{\"b\":2}\n", File(dir, "20261008_00.jsonl").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zipContainsManifestClipsAndClassifierLogsButHourFilesOnlyOnRequest() {
        val root = Files.createTempDirectory("labor").toFile()
        try {
            val d = LaborDirs(File(root, "audio"))
            d.ensure()
            d.manifest.writeText("{}\n")
            File(d.clips, "ev_3_20261007_234210.wav").writeBytes(ByteArray(100))
            File(d.classifier, "20261007_23.jsonl").writeText("{}\n")
            File(d.continuous, "20261007_23.m4a").writeBytes(ByteArray(1000))
            val st = d.stats()
            assertEquals(1, st.clipCount); assertEquals(1, st.hourFiles); assertEquals(1, st.classifierFiles)
            assertEquals(1106L, st.usedBytes)
            assertEquals(106L, st.textAndClipBytes)
            fun entries(f: File) = java.util.zip.ZipFile(f).use { z -> z.entries().toList().map { it.name } }
            val small = File(root, "a.zip").also { d.zipTo(it, withContinuous = false) }
            assertEquals(listOf("manifest.jsonl", "clips/ev_3_20261007_234210.wav", "classifier/20261007_23.jsonl"), entries(small))
            val all = File(root, "b.zip").also { d.zipTo(it, withContinuous = true) }
            assertEquals("continuous/20261007_23.m4a", entries(all).last())
            d.deleteAll()
            assertEquals(0L, d.usedBytes())
        } finally {
            root.deleteRecursively()
        }
    }
}
