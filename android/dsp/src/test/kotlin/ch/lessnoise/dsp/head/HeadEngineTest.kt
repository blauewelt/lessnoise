package ch.lessnoise.dsp.head

import ch.lessnoise.dsp.Butterworth
import ch.lessnoise.dsp.MeasurementEngine
import ch.lessnoise.dsp.NoiseEvent
import ch.lessnoise.dsp.Replay
import ch.lessnoise.dsp.ReplayFixtures
import ch.lessnoise.dsp.SimClock
import ch.lessnoise.dsp.TestSignals
import ch.lessnoise.dsp.Upsampler3
import ch.lessnoise.dsp.classify.EventHead
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The head inside [MeasurementEngine] (the phone's signal path: 48 kHz input, LAF and band
 * energies at 48 kHz, spectra from the decimated 16 kHz classifier ring, the live detector's
 * event span) compared with [ClipHarness] (≡ the Python, see [EventHeadGoldenTest]) on the same
 * audio and the same event span. The differences are the phone-feasible deviations of the signal
 * path; [PATH_TOL] states how large they may be (android/dsp/EVENT_HEAD.md).
 */
class HeadEngineTest {
    companion object {
        val head: EventHead by lazy { EventHead.loadDefault() }
        const val RATE = 16_000

        /** Inputs that are the detector's own values (local floor over 30 s, LAFmax, background): not comparable with a clip harness. */
        val DETECTOR_INPUTS = setOf(
            "excessDb", "log10_riseS", "riseS_missing", "log10_decayS", "decayS_missing", "jaggedness", "midBandRiseDb", "lfShare",
            "lfFlutterDb", "wind_rule", "shape_long", "shape_impulse", "shape_jagged", "shape_hump", "lafMaxDb", "localFloorDb", "backgroundDb",
        )

        /**
         * Stated signal-path tolerance (absolute, in the input's unit) between the engine and the
         * harness on the same audio and span. Default [PATH_TOL_DEFAULT].
         */
        val PATH_TOL: Map<String, Double> = mapOf(
            // Replay artefact: a 16 kHz WAV upsampled to 48 kHz and decimated again passes the 7.5 kHz FIR twice, so the
            // 7–7.5 kHz bins (inside the 50–7500 Hz flatness range) are weaker than in the WAV; on a phone the clip and the
            // spectra are the same decimator output. Observed ≤ 0.012 here.
            "flatness_event" to 0.02, "flatness_peak" to 0.02, "flatness_pre" to 0.02,
            // LAF and A-weighted 25 ms energies at 48 kHz (phone) vs 16 kHz (Python): the A-weighting designs differ above
            // ≈ 4 kHz (16 kHz bilinear design near Nyquist), which moves broadband/impulsive envelopes and the white-noise
            // floor. Observed: peak_excess 0.57 dB, env_d2_rms 1.18 dB (clicks), mod_ratio 1.13 dB (clicks), centroid slope 0.07.
            "peak_excess_db" to 1.0, "env_d2_rms_db" to 1.5, "mod_ratio_db" to 1.5, "centroid_slope_oct_per_s" to 0.15,
            "lf_share_fft" to 0.01, "peak_pos_rel" to 0.01,
            "per50_r" to 0.05, "per125_r" to 0.05, "per50_hz" to 0.1, "mod_peak_hz" to 0.1,
            "log2_centroid_peak_hz" to 0.05, "log2_centroid_event_hz" to 0.05, "log2_centroid_pre_hz" to 0.05, "log2_tonal_f_hz" to 0.05,
            "log10_dur_above6_s" to 0.05,
        )
        const val PATH_TOL_DEFAULT = 0.5 // dB-valued inputs

        private fun noise(seconds: Double, rms: Double, seed: Int) = TestSignals.whiteNoise(rms, seconds, seed = seed, fs = RATE)

        /** 6 s background, a 3 s engine pass (harmonics 120 → 95 Hz + 400–4000 Hz noise), 3 s background. */
        fun passby(): FloatArray {
            val bg = noise(12.0, ReplayFixtures.BG_RMS, 41)
            val m = 3 * RATE
            val tyre = noise(3.0, 1.0, 42).let { x ->
                val hp = Butterworth.highpass(400.0, RATE); val lp = Butterworth.lowpass(4000.0, RATE)
                FloatArray(x.size) { lp.process(hp.process(x[it].toDouble())).toFloat() }
            }
            var ph = 0.0
            for (i in 0 until m) {
                val f0 = 120.0 - 25.0 * i / m
                ph += 2 * PI * f0 / RATE
                var h = 0.0
                for (k in 1..6) h += Math.pow(0.7, k - 1.0) * sin(k * ph)
                val env = sin(PI * i / m).let { it * it }
                bg[6 * RATE + i] += (ReplayFixtures.BG_RMS * 8 * env * (0.8 * h + 2.0 * tyre[i])).toFloat()
            }
            return bg
        }

        /** 6 s background, 3 s of footsteps (clicks at 2 Hz), 3 s background. */
        fun steps(): FloatArray {
            val bg = noise(12.0, ReplayFixtures.BG_RMS, 51)
            val r = java.util.Random(52)
            for (s in 0 until 6) {
                val at = 6 * RATE + 2000 + s * RATE / 2
                for (j in 0 until 400) bg[at + j] += (0.02 * r.nextGaussian() * Math.exp(-j / 40.0)).toFloat()
            }
            return bg
        }

        /** 6 s background, 2.5 s of a 4 Hz amplitude-modulated harmonic complex ("voice"), 3.5 s background. */
        fun voices(): FloatArray {
            val bg = noise(12.0, ReplayFixtures.BG_RMS, 61)
            val m = (2.5 * RATE).toInt()
            var ph = 0.0
            for (i in 0 until m) {
                ph += 2 * PI * 160.0 / RATE
                var h = 0.0
                for (k in 1..10) h += Math.pow(0.85, k - 1.0) * sin(k * ph)
                val am = 0.5 + 0.5 * sin(2 * PI * 4.0 * i / RATE).let { it * it }
                bg[6 * RATE + i] += (ReplayFixtures.BG_RMS * 5 * am * h).toFloat()
            }
            return bg
        }

        val SIGNALS: Map<String, () -> FloatArray> = linkedMapOf(
            "hump" to ReplayFixtures::hump, "wind_thump" to ReplayFixtures::thump,
            "passby" to ::passby, "steps" to ::steps, "voices" to ::voices,
        )
    }

    private class Run(val events: List<NoiseEvent>, val closed: Int, val emittedAtSample: List<Long>)

    private fun runEngine(x16: FloatArray, withHead: Boolean): Run {
        val events = ArrayList<NoiseEvent>()
        val at = ArrayList<Long>()
        var closed = 0
        val clock = SimClock(Replay.START_MS)
        lateinit var engine: MeasurementEngine
        engine = MeasurementEngine(
            Replay.config(), null,
            object : MeasurementEngine.Listener {
                override fun onEvent(event: NoiseEvent) { events += event; at += engine.totalSamples }
                override fun onEventClosed(startSample: Long, endSample: Long, features: ch.lessnoise.dsp.EventFeatures) {
                    closed++
                    if (withHead) assertNotNull(features.head, "onEventClosed carries the head") else assertNull(features.head)
                }
            },
            clock::now, if (withHead) head else null,
        )
        TestSignals.feed(engine, Upsampler3.process(x16), clock = clock)
        engine.stop()
        return Run(events, closed, at)
    }

    @Test
    fun headIsLoggingOnly_detectionCategoryAndFeaturesUnchanged() {
        for ((name, gen) in SIGNALS) {
            val x = gen()
            val plain = runEngine(x, withHead = false)
            val withHead = runEngine(x, withHead = true)
            assertEquals(plain.events.size, withHead.events.size, name)
            assertTrue(plain.events.isNotEmpty(), "$name: no event")
            for ((a, b) in plain.events.zip(withHead.events)) {
                assertEquals(a.copy(features = a.features), b.copy(features = b.features!!.copy(head = null)), name)
                assertEquals(a.dominantCategory, b.dominantCategory)
                assertNull(a.headScores)
                val s = assertNotNull(b.headScores, name)
                assertEquals(head.classes.toSet(), s.keys)
                assertTrue(s.values.all { it in 0.0..1.0 })
                assertEquals(head.decide(head.classes.map { s.getValue(it) }.toDoubleArray()), b.headDecision)
            }
            // The head waits at most 8 ticks (1 s) after the end of the event's tail.
            for ((ta, tb) in plain.emittedAtSample.zip(withHead.emittedAtSample)) assertTrue(tb - ta in 0..8L * 6000, "$name: emitted ${tb - ta} samples later")
        }
    }

    /** Engine vs harness on the same audio and event span: the signal-path deviation per head input. */
    @Test
    fun engineMatchesHarnessWithinPathTolerance() {
        val worst = LinkedHashMap<String, Double>()
        val failures = ArrayList<String>()
        for ((name, gen) in SIGNALS) {
            val x = gen()
            val run = runEngine(x, withHead = true)
            for (ev in run.events) {
                val r = ev.features!!.head!!
                // Event start as a 16 kHz tick of x (the upsampled signal is aligned: 48 kHz sample 3j = x[j]).
                val startTick16 = ((ev.startEpochMs - Replay.START_MS) * RATE / 1000 / ClipHarness.TICK).toInt()
                val from = maxOf(0, startTick16 - HeadFeatureExtractor.PRE_TICKS)
                val crop = DoubleArray(x.size - from * ClipHarness.TICK) { x[from * ClipHarness.TICK + it].toDouble() }
                val h = ClipHarness.run(crop, startTick16 - from, ev.durationSeconds, 0.0, ev.backgroundDb, head)
                for ((k, v) in r.inputs) {
                    if (k in DETECTOR_INPUTS) continue
                    val want = h.inputs[k] ?: Double.NaN
                    val d = if (v.isNaN() && want.isNaN()) 0.0 else abs(v - want)
                    worst[k] = maxOf(worst[k] ?: 0.0, d)
                    val tol = PATH_TOL[k] ?: PATH_TOL_DEFAULT
                    if (!(d <= tol)) failures += "$name $k: engine $v, harness $want"
                }
            }
        }
        println("HeadEngineTest: max |engine − harness| per input (same audio, same span)")
        for ((k, v) in worst) println(String.format(Locale.ROOT, "  %-30s %.4g  (tol %.3g)", k, v, PATH_TOL[k] ?: PATH_TOL_DEFAULT))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun ringCopyAtReturnsAbsoluteSamples() {
        val ring = ch.lessnoise.dsp.FloatRingBuffer(10)
        for (i in 0 until 25) ring.write(i.toFloat())
        val d = FloatArray(4)
        assertTrue(ring.copyAt(17, d, 4))
        assertEquals(listOf(17f, 18f, 19f, 20f), d.toList())
        assertTrue(!ring.copyAt(14, d, 4), "no longer held")
        assertTrue(!ring.copyAt(23, d, 4), "not yet written")
        assertEquals(25L, ring.written)
    }

    @Test
    fun chunkedDecimationEqualsWholeBlocks() {
        val x = TestSignals.whiteNoise(0.1, 0.5, seed = 7, fs = 48_000)
        val a = ch.lessnoise.dsp.DecimatingResampler(); val b = ch.lessnoise.dsp.DecimatingResampler()
        val ra = ch.lessnoise.dsp.FloatRingBuffer(16_000); val rb = ch.lessnoise.dsp.FloatRingBuffer(16_000)
        a.process(x, x.size, ra)
        var p = 0
        for (cut in listOf(1000, 1001, 4999, 6000, 7, x.size)) { val q = minOf(cut + p, x.size); b.process(x, p, q, rb); p = q }
        val da = FloatArray(ra.available); val db = FloatArray(rb.available)
        ra.copyLatest(da, da.size); rb.copyLatest(db, db.size)
        assertTrue(da.contentEquals(db))
    }
}
