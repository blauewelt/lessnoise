package ch.lessnoise.dsp.golden

import ch.lessnoise.dsp.AWeighting
import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.BackgroundEstimator
import ch.lessnoise.dsp.Biquad
import ch.lessnoise.dsp.Butterworth
import ch.lessnoise.dsp.Csv
import ch.lessnoise.dsp.DecimatingResampler
import ch.lessnoise.dsp.Dynamics
import ch.lessnoise.dsp.EngineConfig
import ch.lessnoise.dsp.EventFeatureTracker
import ch.lessnoise.dsp.EventFeatures
import ch.lessnoise.dsp.EventLevels
import ch.lessnoise.dsp.EventShape
import ch.lessnoise.dsp.ExponentialTimeWeighting
import ch.lessnoise.dsp.FeatureFilters
import ch.lessnoise.dsp.Iso
import ch.lessnoise.dsp.LafTick
import ch.lessnoise.dsp.LocalFloorEstimator
import ch.lessnoise.dsp.MeasurementEngine
import ch.lessnoise.dsp.MeasurementSpan
import ch.lessnoise.dsp.MeasurementSplitter
import ch.lessnoise.dsp.MeasurementSummarizer
import ch.lessnoise.dsp.MinuteLevels
import ch.lessnoise.dsp.MinuteRecord
import ch.lessnoise.dsp.NoiseEvent
import ch.lessnoise.dsp.Percentiles
import ch.lessnoise.dsp.QuickSelect
import ch.lessnoise.dsp.Replay
import ch.lessnoise.dsp.SecondResult
import ch.lessnoise.dsp.SilenceDetector
import ch.lessnoise.dsp.SimClock
import ch.lessnoise.dsp.TestSignals
import ch.lessnoise.dsp.Upsampler3
import ch.lessnoise.dsp.Wav
import ch.lessnoise.dsp.WindRule
import ch.lessnoise.dsp.ZWeighting
import ch.lessnoise.dsp.calibration.CalibrationMath
import ch.lessnoise.dsp.calibration.CalibrationMeasurement
import ch.lessnoise.dsp.calibration.EventFloor
import ch.lessnoise.dsp.calibration.Fft
import ch.lessnoise.dsp.calibration.Recalibration
import ch.lessnoise.dsp.calibration.ToneAnalyzer
import ch.lessnoise.dsp.classify.CategoryMapper
import ch.lessnoise.dsp.classify.ClassifierFrame
import ch.lessnoise.dsp.classify.ClassifierPreprocessor
import ch.lessnoise.dsp.geo.LocationNoise
import ch.lessnoise.dsp.geo.Lv95
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertTrue
import ch.lessnoise.dsp.golden.Golden.d
import ch.lessnoise.dsp.golden.Golden.doubles
import ch.lessnoise.dsp.golden.Golden.str

/**
 * Writes the golden fixtures of the measuring core for the Swift port (see [Golden]). Each test
 * writes one file and checks a few sanity conditions so that a broken generator does not produce
 * a fixture silently.
 */
class DspGoldenTest {
    private val zurich = ZoneId.of("Europe/Zurich")

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, zone: ZoneId = zurich): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    // ---- Filters ------------------------------------------------------------------------------

    private fun biquadJson(b: Biquad) = doubles(listOf(b.b0, b.b1, b.b2, b.a1, b.a2))

    @Suppress("UNCHECKED_CAST")
    private fun aSections(a: AWeighting): Pair<Array<Biquad>, Double> {
        val s = AWeighting::class.java.getDeclaredField("sections").apply { isAccessible = true }.get(a) as Array<Biquad>
        val g = AWeighting::class.java.getDeclaredField("gain").apply { isAccessible = true }.getDouble(a)
        return s to g
    }

    @Test
    fun filters() {
        val freqs = listOf(10.0, 12.5, 16.0, 20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0,
            400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0,
            10000.0, 12500.0, 16000.0, 20000.0)
        val impulse = FloatArray(512).also { it[0] = 1f }
        val noise = GoldenSignal(48_000, 4800).noise(0.0, 0.1, 0.1, 5)
        val noiseSamples = noise.samples()
        val rates = buildJsonArray {
            for (fs in listOf(48_000, 44_100, 32_000, 16_000, 96_000)) {
                val a = AWeighting(fs)
                val (sections, gain) = aSections(a)
                val imp = DoubleArray(impulse.size) { a.process(impulse[it].toDouble()) }
                a.reset()
                val nz = if (fs == 48_000) DoubleArray(noiseSamples.size) { a.process(noiseSamples[it].toDouble()) } else DoubleArray(0)
                add(buildJsonObject {
                    put("fs", fs)
                    put("high_pole_effective_hz", d(a.highPoleEffectiveHz))
                    put("sections", buildJsonArray { sections.forEach { add(biquadJson(it)) } })
                    put("gain", d(gain))
                    put("magnitude_db", doubles(freqs.filter { it < fs / 2.0 }.map { a.magnitudeDb(it) }))
                    put("impulse_response", doubles(imp))
                    if (fs == 48_000) put("noise_output", doubles(nz))
                })
            }
        }
        val fast = ExponentialTimeWeighting(ExponentialTimeWeighting.FAST_TAU)
        val step = DoubleArray(12_000) { fast.process(1.0) }
        val slow = ExponentialTimeWeighting(ExponentialTimeWeighting.SLOW_TAU)
        val slowStep = DoubleArray(4_800) { slow.process(if (it < 2400) 1.0 else 0.0) }
        val ff = FeatureFilters(48_000)
        val aw = AWeighting(48_000)
        val feat = buildJsonArray {
            for (i in noiseSamples.indices) {
                val x = noiseSamples[i].toDouble()
                ff.process(x, aw.process(x))
                if (i % 7 == 0) add(doubles(listOf(ff.band, ff.low, ff.total)))
            }
        }
        val dec = DecimatingResampler()
        val sine = GoldenSignal(48_000, 9600).sine(0.0, 0.2, 1000.0, 0.5).noise(0.0, 0.2, 0.01, 6).samples()
        Golden.write("filters.json", buildJsonObject {
            put("frequencies", doubles(freqs))
            put("analog_db", doubles(freqs.map { AWeighting.analogDb(it) }))
            put("noise_spec", noise.spec())
            put("a_weighting", rates)
            put("z_weighting_magnitude_1k", d(ZWeighting().magnitudeDb(1000.0)))
            put("fast_alpha_step", doubles(step.filterIndexed { i, _ -> i % 50 == 0 }))
            put("slow_step", doubles(slowStep.filterIndexed { i, _ -> i % 40 == 0 }))
            put("butterworth", buildJsonObject {
                put("lp_200_48k", biquadJson(Butterworth.lowpass(200.0, 48_000)))
                put("hp_20_48k", biquadJson(Butterworth.highpass(20.0, 48_000)))
                put("hp_250_48k", biquadJson(Butterworth.highpass(250.0, 48_000)))
                put("lp_4500_48k", biquadJson(Butterworth.lowpass(4500.0, 48_000)))
                put("lp_8000_48k", biquadJson(Butterworth.lowpass(8000.0, 48_000)))
                put("lp_3000_16k", biquadJson(Butterworth.lowpass(3000.0, 16_000)))
            })
            put("feature_filters_every_7th", feat)
            put("decimator_taps", doubles(dec.taps))
            put("decimator_magnitude_db", doubles(listOf(1000.0, 7000.0, 7500.0, 8000.0, 12000.0).map { dec.magnitudeDb(it) }))
            put("decimator_input_spec", GoldenSignal(48_000, 9600).sine(0.0, 0.2, 1000.0, 0.5).noise(0.0, 0.2, 0.01, 6).spec())
            put("decimator_output", Golden.floats(dec.processAll(sine)))
            put("classifier_normalize", buildJsonArray {
                for (scale in listOf(0.0, 1e-4, 0.03, 0.5)) {
                    val w = FloatArray(64) { (sine[it] * scale).toFloat() }
                    val g = ClassifierPreprocessor.normalize(w)
                    add(buildJsonObject { put("scale", scale); put("gain_db", d(g)); put("window", Golden.floats(w)) })
                }
            })
            put("silence_detector", buildJsonArray {
                val cases = listOf(FloatArray(480), FloatArray(1000) { if (it in 100 until 579) 0f else 1e-3f },
                    FloatArray(1000) { if (it in 100 until 580) 0f else 1e-3f }, FloatArray(100) { 1e-8f }, FloatArray(100) { 1e-6f }, FloatArray(0))
                for (c in cases) add(SilenceDetector.isDigitalSilence(c))
            })
        })
        assertTrue(AWeighting(48_000).highPoleEffectiveHz in 13_000.0..15_000.0)
    }

    // ---- Percentiles, floors, quickselect -----------------------------------------------------

    @Test
    fun percentiles() {
        val r = SplitMix64(77)
        val cases = buildJsonArray {
            for (n in listOf(1, 2, 3, 7, 240, 481)) {
                val a = DoubleArray(n) { 30 + 40 * r.nextDouble() }
                // Ties, as LAF values of a steady signal produce.
                if (n > 10) for (i in 0 until n / 5) a[i] = a[n - 1 - i]
                val ps = listOf(0.0, 1.0, 10.0, 25.0, 50.0, 90.0, 99.0, 100.0)
                val s = Percentiles.stats(a)
                add(buildJsonObject {
                    put("values", doubles(a))
                    put("percentile_sorted", doubles(ps.map { Percentiles.percentileSorted(a.sortedArray(), it) }))
                    put("exceedance", doubles(ps.map { Percentiles.exceedanceLevel(a, it) }))
                    put("quickselect", doubles(ps.map { QuickSelect.percentile(a.copyOf(), n, it) }))
                    put("select_k", doubles((0 until n step maxOf(1, n / 9)).map { QuickSelect.select(a.copyOf(), n, it) }))
                    put("stats", doubles(listOf(s.min, s.max, s.l1, s.l10, s.l50, s.l90)))
                })
            }
        }
        val lf = LocalFloorEstimator(30.0, 5.0, 0.125)
        val bg = BackgroundEstimator(10.0, 2.0, 0.125)
        val seq = DoubleArray(400) { 35 + 10 * r.nextDouble() + if (it in 150..170) 20.0 else 0.0 }
        val floors = DoubleArray(seq.size)
        val bgs = DoubleArray(seq.size)
        for (i in seq.indices) { lf.add(seq[i]); bg.add(seq[i]); floors[i] = lf.value; bgs[i] = bg.l90() }
        Golden.write("percentiles.json", buildJsonObject {
            put("cases", cases)
            put("floor_input", doubles(seq))
            put("local_floor_30s_5s", doubles(floors))
            put("background_10s_2s", doubles(bgs))
            put("energy_average", d(Acoustics.energyAverage(doubleArrayOf(40.0, 50.0, 60.0), doubleArrayOf(1.0, 2.0, 0.5))))
            put("default_offset", d(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB))
        })
    }

    // ---- Engine on synthetic signals ----------------------------------------------------------

    private class Case(
        val name: String,
        val signal: GoldenSignal,
        val config: EngineConfig,
        val startMs: Long,
        val zone: String,
        val clockRate: Double = 1.0,
        val silenced: List<LongRange> = emptyList(),
        val classifier: Boolean = false,
        val withTicks: Boolean = false,
        val note: String,
    )

    /** Deterministic fake classifier scores for frame [k] (no model in the dsp module; documented in the fixture). */
    private fun fakeScores(m: CategoryMapper, k: Int): FloatArray {
        val r = SplitMix64(1000L + k)
        val s = FloatArray(m.labels.size) { (r.nextDouble() * 0.3).toFloat() }
        when (k % 3) {
            0 -> s[m.labels.indexOf("Motorcycle")] = 0.6f
            1 -> s[m.labels.indexOf("Car")] = 0.5f
        }
        return s
    }

    private fun configJson(c: EngineConfig) = buildJsonObject {
        put("sample_rate", c.sampleRate); put("calibration_offset_db", d(c.calibrationOffsetDb)); put("calibrated", c.calibrated)
        put("calibration_id", c.calibrationId?.let { JsonPrimitive(it) } ?: JsonNull); put("audio_source", c.audioSource)
        put("event_excess_db", d(c.eventExcessDb)); put("event_min_level_db", d(c.eventMinLevelDb))
        put("event_max_duration_s", d(c.eventMaxDurationSeconds)); put("classifier_enabled", c.classifierEnabled)
        put("zone", c.zone.id)
    }

    private fun runCase(c: Case): JsonObject {
        val minutes = ArrayList<MinuteRecord>()
        val events = ArrayList<Triple<NoiseEvent, Long, Long>>()
        val ticks = ArrayList<LafTick>()
        val seconds = ArrayList<SecondResult>()
        val log = ArrayList<String>()
        val windows = buildJsonArray {
            val mapper = if (c.classifier) TestSignals.mapper() else null
            val clock = SimClock(c.startMs, c.clockRate)
            val engine = MeasurementEngine(c.config, mapper, object : MeasurementEngine.Listener {
                override fun onMinute(minute: MinuteRecord) { minutes += minute }
                override fun onEventEmitted(event: NoiseEvent, startSample: Long, endSample: Long) { events += Triple(event, startSample, endSample) }
                override fun onTick(tick: LafTick) { if (c.withTicks) ticks += tick }
                override fun onSecond(second: SecondResult) { if (c.withTicks) seconds += second }
                override fun onClockCorrection(correctionMs: Long) { log += "correction $correctionMs" }
                override fun onClockAnchored(anchorSample: Long, anchorEpochMs: Long) { log += "anchor $anchorSample $anchorEpochMs" }
                override fun onEventCandidate(startSample: Long) { log += "candidate $startSample" }
                override fun onEventConfirmed(startSample: Long) { log += "confirmed $startSample" }
                override fun onEventDiscarded(startSample: Long) { log += "discarded $startSample" }
                override fun onEventClosed(startSample: Long, endSample: Long, features: EventFeatures) { log += "closed $startSample $endSample" }
            }, clock::now)
            val x = c.signal.samples()
            val block = 6000
            val buf = FloatArray(block)
            var p = 0
            var frame = 0
            while (p < x.size) {
                val n = minOf(block, x.size - p)
                x.copyInto(buf, 0, p, p + n)
                val pp = p.toLong()
                engine.setMicSilenced(c.silenced.any { pp in it })
                clock.advance(n)
                engine.process(buf, n)
                if (mapper != null && engine.classifierDue()) {
                    val w = FloatArray(ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
                    val end = engine.copyClassifierWindow(w)
                    var e = 0.0
                    for (v in w) e += v.toDouble() * v
                    add(buildJsonObject { put("end_sample", end); put("energy", d(e)); put("first", d(w[0].toDouble())); put("last", d(w[w.size - 1].toDouble())) })
                    engine.onClassifierFrame(ClassifierFrame(end, engine.classifierWindowLength48k, fakeScores(mapper, frame++)))
                }
                p += n
            }
            engine.stop()
        }
        return buildJsonObject {
            put("name", c.name)
            put("note", c.note)
            put("signal", c.signal.spec())
            put("config", configJson(c.config))
            put("start_ms", c.startMs)
            put("clock_rate", d(c.clockRate))
            put("block", 6000)
            put("silenced", silencedJson(c.silenced))
            put("classifier", c.classifier)
            put("minutes", buildJsonArray { minutes.forEach { add(Golden.minute(it)) } })
            put("events", buildJsonArray {
                events.forEach { (e, s, en) -> add(buildJsonObject { put("event", Golden.event(e)); put("start_sample", s); put("end_sample", en) }) }
            })
            put("log", buildJsonArray { log.forEach { add(it) } })
            put("classifier_windows", windows)
            if (c.withTicks) {
                put("ticks", buildJsonArray {
                    ticks.forEach { t -> add(buildJsonArray { add(t.endSample); add(t.epochMs); add(d(t.lafDb)); add(d(t.lafMaxDb)); add(d(t.leqDb)); add(t.valid) }) }
                })
                put("seconds", buildJsonArray {
                    seconds.forEach { s ->
                        add(buildJsonArray {
                            add(s.endSample); add(s.epochMs); add(d(s.laeqDb)); add(d(s.lafMaxDb)); add(d(s.lzeqDb)); add(d(s.laeqRunning60sDb))
                            add(d(s.backgroundDb)); add(d(s.validFraction)); add(d(s.localFloorDb))
                        })
                    }
                })
            }
            put("csv_minutes", Csv.minutes(minutes, if (c.classifier) TestSignals.mapper().bucketIds else emptyList()))
            put("csv_events", Csv.events(events.map { it.first }))
        }
    }

    private fun cases(): List<Case> {
        val utc = ZoneId.of("UTC")
        return listOf(
            Case(
                "tone_on_noise", GoldenSignal(48_000, 75 * 48_000).noise(0.0, 75.0, 3e-4, 1).sine(10.0, 20.0, 1000.0, 0.01),
                EngineConfig(zone = zurich, classifierEnabled = false), epoch(2026, 10, 2, 23, 0, 10), "Europe/Zurich", withTicks = true,
                note = "noise −70 dBFS, a 1 kHz tone 10–30 s; every tick and second; minute boundary 50 s in",
            ),
            Case(
                "bursts", GoldenSignal(48_000, 150 * 48_000).noise(0.0, 150.0, 3e-4, 2)
                    .burst(20.0, 2.0, 3e-3, 11).burst(40.0, 0.3, 1e-2, 12).burst(59.0, 3.0, 2e-3, 13)
                    .sine(80.0, 1.0, 60.0, 0.05).burst(100.0, 35.0, 1.5e-3, 14).burst(140.0, 1.5, 2e-4, 15),
                EngineConfig(zone = zurich, classifierEnabled = false, calibrationOffsetDb = 120.0, calibrated = true, calibrationId = 7, audioSource = "UNPROCESSED"),
                epoch(2026, 10, 2, 22, 58, 30), "Europe/Zurich",
                note = "bursts: normal, too short, across a minute boundary, a 60 Hz thump (wind), a 35 s long one, one below the floor; calibrated offset 120",
            ),
            Case(
                "invalid_audio", GoldenSignal(48_000, 70 * 48_000).noise(0.0, 70.0, 1e-3, 3).zeros(15.0, 2.0)
                    .burst(32.0, 2.0, 1e-2, 21).burst(45.0, 2.0, 1e-2, 22),
                EngineConfig(zone = utc, classifierEnabled = false), epoch(2026, 10, 12, 12, 0, 0, utc), "UTC",
                silenced = listOf(30L * 48_000 until 34L * 48_000),
                note = "2 s of exact zeros, 4 s of system-silenced mic (blocks starting 30–34 s) with a burst inside, one valid burst; zone UTC (ISO 'Z')",
            ),
            Case(
                "clipping_dst", GoldenSignal(48_000, 40 * 48_000).noise(0.0, 40.0, 1e-3, 4).square(10.0, 5.0, 1.0, 48),
                EngineConfig(zone = zurich, classifierEnabled = false), epoch(2026, 3, 29, 1, 59, 40), "Europe/Zurich",
                note = "a full-scale 1 kHz square wave (digital clipping) across the spring DST change 02:00 → 03:00",
            ),
            Case(
                "clock_drift", GoldenSignal(48_000, 150 * 48_000).noise(0.0, 150.0, 3e-4, 5).burst(70.0, 2.0, 3e-3, 31),
                EngineConfig(zone = zurich, classifierEnabled = false), epoch(2026, 12, 31, 23, 59, 30), "Europe/Zurich", clockRate = 1.01,
                note = "wall clock 1 % fast: re-anchoring once per minute; across midnight and the new year",
            ),
            Case(
                "classifier", GoldenSignal(48_000, 70 * 48_000).noise(0.0, 70.0, 3e-4, 6).burst(20.0, 2.0, 5e-3, 41).burst(50.0, 0.6, 5e-3, 42),
                EngineConfig(zone = zurich, classifierEnabled = true), epoch(2026, 10, 2, 23, 0, 0), "Europe/Zurich", classifier = true,
                note = "classifier plumbing with the app's categories.json/yamnet_labels.txt and deterministic fake scores (SplitMix64(1000+k)·0.3, frame k%3==0: Motorcycle=0.6, k%3==1: Car=0.5)",
            ),
            Case(
                "max_duration", GoldenSignal(48_000, 330 * 48_000).noise(0.0, 330.0, 3e-4, 7).sine(10.0, 315.0, 500.0, 0.01),
                EngineConfig(zone = zurich, classifierEnabled = false), epoch(2026, 10, 3, 1, 0, 0), "Europe/Zurich",
                note = "a 315 s tone: the event is force-closed at 300 s, minutes are held back until it is decided (emitted out of order)",
            ),
        )
    }

    @Test
    fun engine() {
        val out = buildJsonArray { cases().forEach { add(runCase(it)) } }
        Golden.write("engine.json", buildJsonObject {
            put("about", "MeasurementEngine on synthetic signals (GoldenSignal), fed in 6000-sample blocks with SimClock; minutes and events in emission order")
            put("cases", out)
        })
        assertTrue(out.size == 7)
    }

    // ---- Replay WAVs --------------------------------------------------------------------------

    @Test
    fun replay() {
        val dir = File(System.getProperty("stadtlaerm.replayDir") ?: "src/test/resources/replay")
        val items = buildJsonArray {
            for (name in listOf("hump", "wind_thump", "steady_tone")) {
                val wav = File(dir, "$name.wav")
                val r = Replay.runWav(wav)
                val (x, _) = Wav.read(wav)
                val up = Upsampler3.process(x)
                add(buildJsonObject {
                    put("name", name)
                    put("wav_sha256", Golden.sha256(wav))
                    put("start_ms", Replay.START_MS)
                    put("upsampled_count", up.size)
                    put("upsampled_head", Golden.floats(up.copyOfRange(0, 600)))
                    put("upsampled_mid", Golden.floats(up.copyOfRange(up.size / 2, up.size / 2 + 600)))
                    put("events", buildJsonArray { r.events.forEach { add(Golden.event(it)) } })
                    put("minutes", buildJsonArray { r.minutes.forEach { add(Golden.minute(it)) } })
                    put("closed", buildJsonArray { r.closed.forEach { (s, e, f) -> add(buildJsonObject { put("start_sample", s); put("end_sample", e); put("features", Golden.features(f)) }) } })
                    put("ticks", buildJsonArray { r.ticks.forEach { t -> add(buildJsonArray { add(t.endSample); add(t.epochMs); add(d(t.lafDb)); add(d(t.lafMaxDb)); add(d(t.leqDb)); add(t.valid) }) } })
                    put("csv_events", r.eventsCsv())
                    put("csv_minutes", r.minutesCsv())
                })
            }
        }
        Golden.write("replay.json", buildJsonObject {
            put("about", "android/dsp/src/test/resources/replay/*.wav through Replay.runWav (16 kHz upsampled ×3 by Upsampler3, Replay.config(): zone Europe/Zurich, classifier off)")
            put("items", items)
        })
    }

    // ---- Features on a crafted tick series ----------------------------------------------------

    @Test
    fun featureTracker() {
        val t = EventFeatureTracker(capacityTicks = 200, preTicks = 80)
        val r = SplitMix64(91)
        val laf = DoubleArray(150) { i ->
            val base = 40.0 + r.nextDouble()
            when {
                i in 20..23 -> Double.NaN // invalid ticks before the event
                i in 50..70 -> base + 15 * kotlin.math.sin(PI * (i - 50) / 20)
                i in 90..93 -> base + if (i % 2 == 0) 12 else 3
                else -> base
            }
        }
        for (i in laf.indices) {
            if (laf[i].isNaN()) t.pushInvalid()
            else t.push(laf[i], 1e-7 * (1 + r.nextDouble()), 2e-8 * (1 + r.nextDouble()), 5e-8 * (1 + r.nextDouble()), 1e-7 * (1 + r.nextDouble()))
        }
        val wind = WindRule()
        val spans = listOf(
            listOf(50, 71, 76), listOf(52, 69, 69), listOf(90, 94, 100), listOf(10, 30, 35), listOf(140, 150, 150), listOf(5, 6, 6),
        )
        Golden.write("features.json", buildJsonObject {
            put("laf", doubles(laf))
            put("seed", 91)
            put("about", "push(laf[i], 1e-7·(1+u), 2e-8·(1+u), 5e-8·(1+u), 1e-7·(1+u)) with u from SplitMix64(91) after the 150 base draws; NaN = pushInvalid()")
            put("cases", buildJsonArray {
                for ((s, e, tail) in spans) {
                    val f = t.compute(s.toLong(), e.toLong(), tail.toLong(), 40.5, 56.0, (e - s) * 0.125, wind)
                    add(buildJsonObject { put("start", s); put("end", e); put("tail_end", tail); put("features", Golden.features(f)) })
                }
            })
            put("shapes", buildJsonArray {
                for (c in listOf(listOf(31.0, 1.0, 0.1), listOf(1.5, 0.2, 0.5), listOf(3.0, 0.2, 0.5), listOf(3.0, 1.0, 0.1), listOf(2.0, 0.35, 0.3), listOf(2.0, Double.NaN, Double.NaN))) {
                    add(EventShape.classify(c[0], c[1], c[2]))
                }
            })
            put("wind", buildJsonArray {
                for ((a, b) in listOf(0.93 to 0.0, 0.92 to 4.5, 0.5 to 4.4, Double.NaN to Double.NaN, Double.NaN to 5.0)) add(wind.isWind(a, b))
            })
        })
    }

    // ---- Calibration --------------------------------------------------------------------------

    private fun calibrationRun(name: String, sig: GoldenSignal, seconds: Int, tone: Boolean, invalidateAt: Int? = null) = buildJsonObject {
        val m = CalibrationMeasurement(seconds, analyzeTone = tone)
        val x = sig.samples()
        var p = 0
        while (p < x.size) {
            val n = minOf(4800, x.size - p)
            if (invalidateAt != null && p >= invalidateAt) m.invalidate()
            m.process(x.copyOfRange(p, p + n), n)
            p += n
        }
        val res = m.result()
        put("name", name)
        put("signal", sig.spec())
        put("duration_s", seconds)
        put("analyze_tone", tone)
        put("invalidate_at", invalidateAt?.let { JsonPrimitive(it) } ?: JsonNull)
        put("invalidated", m.invalidated)
        put("complete", m.isComplete)
        put("raw_laeq_db", d(res.rawLaeqDb)); put("raw_lzeq_db", d(res.rawLzeqDb))
        put("second_levels", doubles(res.secondLevelsRawDb)); put("std_dev_db", d(res.stdDevDb))
        put("measured_s", d(res.measuredSeconds)); put("tone_hz", d(res.toneFrequencyHz)); put("tonality", d(res.tonality))
        put("clipped_fraction", d(res.clippedFraction))
        put("reference_warnings_94", buildJsonArray { CalibrationMath.referenceWarnings(res, 94.0).forEach { add(it.name) } })
        put("reference_warnings_45", buildJsonArray { CalibrationMath.referenceWarnings(res, 45.0).forEach { add(it.name) } })
        put("calibrator_warnings_94", buildJsonArray { CalibrationMath.calibratorWarnings(res, 94.0).forEach { add(it.name) } })
        put("reference_offset_94", d(CalibrationMath.referenceOffset(94.0, res.rawLaeqDb)))
    }

    private fun sampleMinute(offset: Double, calId: Long?, source: String = "UNPROCESSED") = MinuteRecord(
        startEpochMs = 1_790_000_000_000L, startIso = "2026-09-21T16:53:20+02:00", durationSeconds = 60.0,
        laeqDb = 42.34, lafMaxDb = 61.07, lafMinDb = 33.12, l1Db = 55.0, l10Db = 47.25, l50Db = 41.05, l90Db = Double.NaN,
        eventCount = 2, dominantCategory = "road_traffic", categoryShares = linkedMapOf("road_traffic" to 0.41, "unclassified" to 0.59),
        classifierFrames = 60, calibrationId = calId, calibrationOffsetDb = offset, audioSource = source, calibrated = calId != null,
        validSeconds = 59.5, localFloorDb = 36.4, windEventCount = 1,
    )

    private fun sampleEvent(calId: Long?, withFeatures: Boolean) = NoiseEvent(
        startEpochMs = 1_790_000_012_375L, startIso = "2026-09-21T16:53:32.375+02:00", durationSeconds = 2.75,
        lafMaxDb = 71.44, selDb = 74.04, backgroundDb = 38.15, thresholdDb = 6.5, dominantCategory = "loud_vehicle",
        dominantScore = 0.31f, topLabels = emptyList(), classifierFrames = 3, calibrationId = calId, audioSource = "UNPROCESSED",
        calibrated = calId != null, minLevelDb = 30.0,
        features = if (withFeatures) EventFeatures(40.0, 31.44, 1.2, 1.5, 0.05, 9.0, 0.1, 1.0, false, "hump") else null,
    )

    @Test
    fun calibration() {
        val fs = 48_000
        val runs = buildJsonArray {
            add(calibrationRun("calibrator_1k", GoldenSignal(fs, 4 * fs).sine(0.0, 4.0, 1000.0, 0.3).noise(0.0, 4.0, 1e-4, 51), 3, tone = true))
            add(calibrationRun("noise_reference", GoldenSignal(fs, 6 * fs).noise(0.0, 6.0, 0.02, 52).burst(2.0, 1.0, 0.2, 53), 5, tone = false))
            add(calibrationRun("clipped_square", GoldenSignal(fs, 3 * fs).square(0.0, 3.0, 1.0, 48), 2, tone = true))
            add(calibrationRun("silent_after_settle", GoldenSignal(fs, 3 * fs).noise(0.0, 1.0, 0.01, 54), 2, tone = false))
            add(calibrationRun("invalidated", GoldenSignal(fs, 3 * fs).noise(0.0, 3.0, 0.01, 55), 2, tone = false, invalidateAt = fs))
        }
        val toneFrames = buildJsonArray {
            for ((f, a) in listOf(997.0 to 0.5, 1000.0 to 0.01, 3150.0 to 0.2)) {
                val sig = GoldenSignal(fs, 8192).sine(0.0, 1.0, f, a).noise(0.0, 1.0, 1e-3, 56)
                val frame = sig.samples().map { it.toDouble() }.toDoubleArray()
                val t = ToneAnalyzer.analyze(frame, fs)
                add(buildJsonObject { put("signal", sig.spec()); put("frequency_hz", d(t.frequencyHz)); put("tonality", d(t.tonality)) })
            }
        }
        val re = DoubleArray(16) { kotlin.math.sin(it * 0.7) + 0.25 * it }
        val im = DoubleArray(16) { kotlin.math.cos(it * 0.3) }
        val re0 = re.copyOf(); val im0 = im.copyOf()
        Fft.transform(re, im)
        val tA = Recalibration.Target(11, 121.9, "UNPROCESSED")
        val tB = Recalibration.Target(12, 118.4, "UNPROCESSED")
        val m0 = sampleMinute(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, null)
        val mA = Recalibration.recalibrate(m0, tA)
        val mAB = Recalibration.recalibrate(mA, tB)
        val mB = Recalibration.recalibrate(m0, tB)
        val m1 = sampleMinute(121.9, 11)
        val m1B = Recalibration.recalibrate(m1, tB)
        val m1BA = Recalibration.recalibrate(m1B, tA)
        val e0 = sampleEvent(null, true)
        val eA = Recalibration.recalibrate(e0, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, tA)
        val eAB = Recalibration.recalibrate(eA, 999.0, tB)
        val e1 = sampleEvent(11, false)
        val e1A = Recalibration.recalibrate(e1, 121.9, tA)
        Golden.write("calibration.json", buildJsonObject {
            put("runs", runs)
            put("tone_frames", toneFrames)
            put("fft_in", buildJsonArray { add(doubles(re0)); add(doubles(im0)) })
            put("fft_out", buildJsonArray { add(doubles(re)); add(doubles(im)) })
            put("std_dev", d(CalibrationMath.stdDev(doubleArrayOf(40.1, 41.3, 39.8, 40.0))))
            put("implausible", buildJsonArray { listOf(92.0, 92.36, 132.0, 132.4, 112.35).forEach { add(CalibrationMath.implausible(it)) } })
            put("recalibration", buildJsonObject {
                put("target_a", buildJsonArray { add(11); add(d(121.9)); add("UNPROCESSED") })
                put("target_b", buildJsonArray { add(12); add(d(118.4)); add("UNPROCESSED") })
                put("minute_default", Golden.minute(m0)); put("minute_default_a", Golden.minute(mA)); put("minute_default_a_b", Golden.minute(mAB))
                put("minute_default_b", Golden.minute(mB))
                put("minute_cal11", Golden.minute(m1)); put("minute_cal11_b", Golden.minute(m1B)); put("minute_cal11_b_a", Golden.minute(m1BA))
                put("event_default", Golden.event(e0)); put("event_default_a", Golden.event(eA)); put("event_default_a_b", Golden.event(eAB))
                put("event_cal11", Golden.event(e1)); put("event_cal11_a", Golden.event(e1A))
                put("in_scope", buildJsonArray {
                    add(Recalibration.inScope("UNPROCESSED", null, tA)); add(Recalibration.inScope("UNPROCESSED", 11, tA))
                    add(Recalibration.inScope("VOICE_RECOGNITION", 3, tA)); add(Recalibration.inScope("UNPROCESSED", 12, tA))
                })
            })
            put("event_floor", buildJsonArray {
                for (c in listOf(listOf(30.0, 112.35, 121.9, 20.0, 70.0), listOf(30.0, 121.9, 112.35, 20.0, 70.0), listOf(65.0, 112.35, 125.0, 20.0, 70.0),
                    listOf(30.0, 112.35, 112.35, 20.0, 70.0), listOf(30.25, 100.0, 100.0, 20.0, 70.0), listOf(21.0, 120.0, 110.0, 20.0, 70.0))) {
                    val a = EventFloor.adjust(c[0], c[1], c[2], c[3], c[4])
                    add(buildJsonObject {
                        put("in", doubles(c)); put("unclamped", d(a.unclampedFloorDb)); put("new", d(a.newFloorDb))
                        put("delta", d(a.deltaDb)); put("clamped", a.clamped); put("changed", a.changed)
                    })
                }
            })
            put("signed", buildJsonArray {
                for (v in listOf(9.55, -3.2, 0.0, -0.04, 0.05, 12.345, -0.0, 100.0)) add(buildJsonArray { add(d(v)); add(EventFloor.signed(v)); add(EventFloor.signed(v, 2)) })
            })
            put("round_to_half", doubles(listOf(30.24, 30.25, 30.74, 30.75, -1.25, 0.0).map { EventFloor.roundToHalf(it) }))
        })
    }

    // ---- Geo ----------------------------------------------------------------------------------

    @Test
    fun geo() {
        fun dms(d: Int, m: Int, s: Double) = d + m / 60.0 + s / 3600.0
        // server/DESIGN.md §3: swisstopo's worked example and four AGNES stations.
        val refs = listOf(
            listOf(2_700_000.0, 1_100_000.0, dms(46, 2, 38.87), dms(8, 43, 49.79)),
            listOf(2_602_030.740, 1_191_775.030, dms(46, 52, 37.540569), dms(7, 27, 54.983511)),
            listOf(2_680_910.112, 1_251_259.201, dms(47, 24, 25.842486), dms(8, 30, 38.194637)),
            listOf(2_704_160.863, 1_114_349.376, dms(46, 10, 21.225556), dms(8, 47, 14.732003)),
            listOf(2_498_930.196, 1_122_714.152, dms(46, 14, 53.692140), dms(6, 7, 41.065513)),
        )
        val r = SplitMix64(3)
        val zurich = (0 until 40).map { listOf(47.32 + 0.12 * r.nextDouble(), 8.45 + 0.16 * r.nextDouble()) } +
            listOf(listOf(47.3769, 8.5417), listOf(47.36667, 8.55), listOf(46.0, 6.0), listOf(45.8, 10.5))
        Golden.write("geo.json", buildJsonObject {
            put("reference", buildJsonArray {
                for (p in refs) {
                    val en = Lv95.wgs84ToLv95(p[2], p[3])
                    val ll = Lv95.lv95ToWgs84(p[0], p[1])
                    add(buildJsonObject {
                        put("e", d(p[0])); put("n", d(p[1])); put("lat", d(p[2])); put("lon", d(p[3]))
                        put("to_lv95", doubles(listOf(en.e, en.n))); put("to_wgs84", doubles(listOf(ll.lat, ll.lon)))
                        put("cell", Lv95.cellId(p[2], p[3]))
                    })
                }
            })
            put("points", buildJsonArray {
                for (p in zurich) {
                    val en = Lv95.wgs84ToLv95(p[0], p[1])
                    val back = Lv95.lv95ToWgs84(en.e, en.n)
                    val cell = Lv95.cellId(p[0], p[1])
                    add(buildJsonObject {
                        put("lat", d(p[0])); put("lon", d(p[1])); put("to_lv95", doubles(listOf(en.e, en.n)))
                        put("back", doubles(listOf(back.lat, back.lon))); put("cell", cell)
                        put("in_server_range", Lv95.isInServerRange(cell))
                        put("corners", buildJsonArray { Lv95.cellCorners(cell).forEach { add(doubles(listOf(it.lat, it.lon))) } })
                        put("center", Lv95.cellCenter(cell).let { doubles(listOf(it.lat, it.lon)) })
                    })
                }
            })
            put("cell_ids", buildJsonArray {
                for (s in listOf("h26824_12473", "h2682_12473", "H26824_12473", "h26824-12473", "h26824_124730", "h00000_00000", "h24799_10700",
                    "h24800_10700", "h28399_12999", "h28400_12999", "h٢6824_12473", " h26824_12473", "h26824_12473\n")) {
                    add(buildJsonObject {
                        put("id", s); put("valid", Lv95.isCellId(s)); put("in_server_range", Lv95.isInServerRange(s))
                        put("sw", Lv95.parseCell(s)?.let { doubles(listOf(it.e, it.n)) } ?: JsonNull)
                    })
                }
            })
            put("lv95_to_cell", buildJsonArray {
                for (p in listOf(listOf(2_682_400.0, 1_247_300.0), listOf(2_682_499.999, 1_247_399.999), listOf(2_682_399.9999999, 1_247_300.0), listOf(-50.0, -150.0))) {
                    add(buildJsonArray { add(d(p[0])); add(d(p[1])); add(Lv95.lv95ToCell(p[0], p[1])) })
                }
            })
        })
    }

    @Test
    fun locationNoise() {
        val secrets = listOf(
            ByteArray(32) { it.toByte() },
            ByteArray(32) { (255 - it * 7).toByte() },
            ByteArray(32) { 0 },
            ByteArray(32).also { val r = SplitMix64(99); for (i in it.indices) it[i] = r.nextLong().toByte() },
        )
        val points = listOf(
            listOf(2_682_437.4, 1_247_391.6), listOf(2_682_499.5, 1_247_300.0), listOf(2_600_000.0, 1_200_000.0),
            listOf(2_480_010.0, 1_070_010.0), listOf(2_839_990.0, 1_299_990.0), listOf(2_683_304.0, 1_247_925.0),
        )
        Golden.write("location_noise.json", buildJsonObject {
            put("sigmas", buildJsonArray { LocationNoise.SIGMAS_M.forEach { add(it) } })
            put("cases", buildJsonArray {
                for (secret in secrets) for (p in points) for (sigma in LocationNoise.SIGMAS_M) {
                    val cell = Lv95.lv95ToCell(p[0], p[1])
                    val seed = LocationNoise.seed(secret, cell, sigma)
                    val (zE, zN) = LocationNoise.standardNormals(seed)
                    val off = LocationNoise.offset(secret, cell, sigma)
                    val s = LocationNoise.shift(secret, p[0], p[1], sigma)
                    add(buildJsonObject {
                        put("secret_hex", secret.joinToString("") { "%02x".format(it) })
                        put("e", d(p[0])); put("n", d(p[1])); put("sigma_m", sigma); put("true_cell", cell)
                        put("seed_hex", seed.joinToString("") { "%02x".format(it) })
                        put("z", doubles(listOf(zE, zN))); put("offset", doubles(listOf(off.e, off.n)))
                        put("shifted", buildJsonArray { add(s.e); add(s.n); add(s.sigmaM); add(s.clamped) }); put("shifted_cell", s.cell)
                    })
                }
            })
        })
    }

    // ---- Measurements, CSV, Iso, categories ---------------------------------------------------

    @Test
    fun measurementsCsvIso() {
        val zoneIds = listOf("Europe/Zurich", "UTC", "America/New_York", "Asia/Kolkata", "Pacific/Chatham")
        val times = listOf(1_790_000_000_000L, 1_774_745_999_999L, 1_774_746_000_000L, 1_792_630_799_000L, 1_792_630_800_500L, 0L, -1L, 1_798_758_000_000L)
        val iso = buildJsonArray {
            for (z in zoneIds) for (t in times) {
                add(buildJsonArray { add(z); add(t); add(Iso.format(t, ZoneId.of(z))); add(Iso.format(t, ZoneId.of(z), millis = true)) })
            }
        }
        // Records for the summaries and the CSV: the engine's "bursts" and "classifier" cases, plus edge records.
        val minutes = ArrayList<MinuteRecord>()
        val events = ArrayList<NoiseEvent>()
        val start = epoch(2026, 10, 2, 22, 0, 0)
        val r = SplitMix64(5)
        for (i in 0 until 90) {
            if (i in 40..55) continue // a gap of 16 minutes: a second measurement
            val laeq = 40 + 15 * r.nextDouble()
            val cov = if (i % 17 == 3) 0.3 else 1.0
            minutes += sampleMinute(112.35, null).copy(
                startEpochMs = start + i * 60_000L, startIso = Iso.format(start + i * 60_000L, zurich), laeqDb = if (i == 7) Double.NaN else laeq,
                l10Db = laeq + 4 * r.nextDouble(), l90Db = laeq - 6 * r.nextDouble(), validSeconds = 60.0 * cov,
                calibrated = i % 11 != 0, categoryShares = if (i % 5 == 0) emptyMap() else linkedMapOf("road_traffic" to 0.25, "loud_vehicle" to 0.05, "unclassified" to 0.7),
                recalibratedFromId = if (i == 60) "default" else null,
            )
            if (i % 4 == 1) {
                events += sampleEvent(null, i % 8 != 1).copy(
                    startEpochMs = start + i * 60_000L + 12_375, lafMaxDb = 50 + 30 * r.nextDouble(), backgroundDb = 38.0,
                    dominantCategory = listOf("loud_vehicle", "road_traffic", null, "unclassified")[(i / 4) % 4],
                    features = EventFeatures(40.0, 20.0, 1.2, 1.5, 0.05, 9.0, 0.1, 1.0, i % 12 == 1, "hump"),
                )
            }
        }
        val spans = MeasurementSplitter.split(minutes.map { it.startEpochMs to it.durationSeconds })
        val summaries = MeasurementSummarizer.summarizeAll(spans, minutes, events, 55.0)
        val starts = spans.map { it.startMs }.toLongArray()
        // CSV edge records: quotes, commas, originals, NaN, negative zero.
        val csvMinutes = listOf(
            minutes[1],
            Recalibration.recalibrate(minutes[2], Recalibration.Target(3, 120.25, "UNPROCESSED")),
            sampleMinute(112.35, null, source = "a \"quoted\", source").copy(dominantCategory = "x,y", laeqDb = -0.04, l1Db = 7.25, durationSeconds = 7.25, validSeconds = 7.25),
        )
        val csvEvents = listOf(
            events[0], events[1],
            Recalibration.recalibrate(sampleEvent(null, true), 112.35, Recalibration.Target(3, 120.25, "UNPROCESSED")).copy(
                topLabels = listOf(ch.lessnoise.dsp.classify.LabelScore("Motorcycle", 0.6125f), ch.lessnoise.dsp.classify.LabelScore("Car, \"fast\"", 0.25f)),
            ),
        )
        val mapper = TestSignals.mapper()
        val cat = buildJsonArray {
            for (k in 0 until 12) {
                val s = fakeScores(mapper, k)
                if (k == 9) s.fill(0.05f)
                if (k == 10) { s.fill(0.0f); s[mapper.labels.indexOf("Speech")] = 0.15f; s[mapper.labels.indexOf("Truck")] = 0.25f }
                if (k == 11) { s.fill(0.0f); s[mapper.labels.indexOf("Motorcycle")] = 0.3f; s[mapper.labels.indexOf("Car")] = 0.3f }
                val dcs = mapper.decide(s)
                add(buildJsonObject {
                    put("k", k)
                    put("override", when (k) { 9 -> "all 0.05"; 10 -> "zeros, Speech 0.15, Truck 0.25"; 11 -> "zeros, Motorcycle 0.3, Car 0.3"; else -> "none" })
                    put("dominant", dcs.dominant); put("score", d(dcs.dominantScore.toDouble()))
                    put("category_scores", buildJsonArray { dcs.categoryScores.forEach { (id, v) -> add(buildJsonArray { add(id); add(d(v.toDouble())) }) } })
                    put("top3", buildJsonArray { mapper.topLabels(s, 3).forEach { add(buildJsonArray { add(it.label); add(d(it.score.toDouble())) }) } })
                })
            }
        }
        Golden.write("records.json", buildJsonObject {
            put("iso", iso)
            put("minutes", buildJsonArray { minutes.forEach { add(Golden.minute(it)) } })
            put("events", buildJsonArray { events.forEach { add(Golden.event(it)) } })
            put("spans", buildJsonArray { spans.forEach { add(buildJsonArray { add(it.startMs); add(it.endMs) }) } })
            put("event_min_level_db", 55.0)
            put("summaries", buildJsonArray {
                summaries.forEach { s ->
                    add(buildJsonObject {
                        put("span", buildJsonArray { add(s.startMs); add(s.endMs) })
                        put("measured_s", d(s.measuredSeconds)); put("laeq_db", d(s.laeqDb)); put("event_count", s.eventCount)
                        put("strong_event_count", s.strongEventCount)
                        put("category_shares", buildJsonArray { s.categoryShares.forEach { (k, v) -> add(buildJsonArray { add(k); add(d(v)) }) } })
                        put("events_by_category", buildJsonArray { s.eventsByCategory.forEach { (k, v) -> add(buildJsonArray { add(k); add(v) }) } })
                        put("loudest_start_ms", s.loudestEvent?.startEpochMs?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("all_calibrated", s.allCalibrated); put("excluded_minutes", s.excludedMinutes)
                        put("events_per_hour", d(s.eventsPerHour)); put("dynamics_db", d(s.dynamicsDb))
                        put("any_recalibrated", s.anyRecalibrated); put("hum_db", d(s.humDb)); put("wind_event_count", s.windEventCount)
                        put("coverage", d(s.coverage))
                    })
                }
            })
            put("owner_index", buildJsonArray {
                for (t in listOf(start - 10_000, start - 5_000, start, start + 60_000 * 45L, start + 60_000 * 56L - 5_000, start + 60_000 * 56L - 5_001, start + 99_999_999)) {
                    add(buildJsonArray { add(t); add(MeasurementSplitter.ownerIndex(starts, t)) })
                }
            })
            put("median_l90", d(Dynamics.medianL90(minutes))); put("median_spread", d(Dynamics.medianSpread(minutes)))
            put("csv_minutes_records", buildJsonArray { csvMinutes.forEach { add(Golden.minute(it)) } })
            put("csv_events_records", buildJsonArray { csvEvents.forEach { add(Golden.event(it)) } })
            put("csv_bucket_ids", buildJsonArray { listOf("road_traffic", "loud_vehicle", "unclassified").forEach { add(it) } })
            put("csv_minutes", Csv.minutes(csvMinutes, listOf("road_traffic", "loud_vehicle", "unclassified")))
            put("csv_events", Csv.events(csvEvents))
            put("csv_fields", buildJsonArray { for (s in listOf(null, "", "plain", "a,b", "say \"hi\"", "line\nbreak", "cr\rx")) add(str(Csv.field(s))) })
            put("category_decisions", cat)
            put("bucket_ids", buildJsonArray { mapper.bucketIds.forEach { add(it) } })
        })
        assertTrue(spans.size == 2)
    }
}
