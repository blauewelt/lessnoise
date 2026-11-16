package ch.lessnoise.dsp.head

import ch.lessnoise.dsp.classify.EventHead
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden test of the event-head port against the Labor analysis's Python
 * (`/home/claude/labor/step3/features.py` + `train/dataset.py` f1_row + the JSON head), on 32
 * synthetic 16 kHz clips (`src/test/resources/eventhead/`, written with their expected values by
 * `android/tools/event_head_golden.py`).
 *
 * Path: [ClipHarness] (the Python tick pipeline at 16 kHz) → production feature classes → head.
 * Tolerances (absolute, per feature; [TOL]) are the stated port accuracy: the definitions are the
 * same, differences are floating-point order (pairwise vs sequential sums, radix-2 FFT vs
 * pocketfft, float32 weights) — see the printed table for the observed maxima.
 */
class EventHeadGoldenTest {
    companion object {
        val DIR = File(System.getProperty("stadtlaerm.eventHeadDir") ?: "src/test/resources/eventhead")

        /** Stated tolerance (absolute) per feature; default [TOL_DEFAULT]. */
        val TOL: Map<String, Double> = mapOf(
            // Parabolic lag refinement and frequency picks are exact unless a peak is a near tie.
            "per125_lag_s" to 1e-6, "per50_lag_s" to 1e-6, "per50_hz" to 1e-5,
        )
        const val TOL_DEFAULT = 1e-6
        const val TOL_PROB = 1e-4

        /** Python column → value in the Kotlin result (the detector-v2 features come from the tracker). */
        val KT = listOf("localFloorDb", "excessDb", "riseS", "decayS", "jaggedness", "midBandRiseDb", "lfShare", "lfFlutterDb")

        val golden: JsonObject by lazy { Json.parseToJsonElement(File(DIR, "golden.json").readText()).jsonObject }
        val head: EventHead by lazy { EventHead.loadDefault() }

        fun d(e: kotlinx.serialization.json.JsonElement?): Double =
            if (e == null || e is JsonNull) Double.NaN else e.jsonPrimitive.double

        fun same(a: Double, b: Double, tol: Double) = (a.isNaN() && b.isNaN()) || abs(a - b) <= tol * maxOf(1.0, abs(b))
    }

    private class Clip(val o: JsonObject) {
        val name = o.getValue("name").jsonPrimitive.content
        val features = o.getValue("features").jsonObject
        val result by lazy {
            ClipHarness.run(
                ClipHarness.readWav(File(DIR, o.getValue("file").jsonPrimitive.content)),
                o.getValue("startTick").jsonPrimitive.int, d(o["durationS"]), d(golden["offsetDb"]), d(o["backgroundDb"]), head,
            )
        }
    }

    private val clips by lazy { golden.getValue("clips").jsonArray.map { Clip(it.jsonObject) } }

    @Test
    fun fixtureHasAtLeast30Clips() {
        assertTrue(clips.size >= 30, "${clips.size} clips")
        assertEquals(head.inputNames, golden.getValue("inputNames").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(head.classes, golden.getValue("classes").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun eventSpanAndIndicesMatchPython() {
        for (c in clips) {
            val r = c.result
            assertEquals(c.o.getValue("ticks").jsonPrimitive.int, r.ticks, c.name)
            assertEquals(c.o.getValue("peakTick").jsonPrimitive.int.toLong(), r.peak, "${c.name} peak")
            assertEquals(c.o.getValue("bestStart").jsonPrimitive.int.toLong(), r.bestStart, "${c.name} bestStart")
            assertEquals(c.o.getValue("topTicks").jsonPrimitive.int, r.topTicks, "${c.name} topTicks")
            assertEquals(c.o.getValue("tailEndTick").jsonPrimitive.int.toLong(), r.tailEnd, "${c.name} tail end")
        }
    }

    /** Every ported feature (detector-v2 ones and the head's own) against the Python column, with the stated tolerance. */
    @Test
    fun featuresMatchPython() {
        val worst = LinkedHashMap<String, Double>()
        val failures = ArrayList<String>()
        fun check(clip: String, name: String, got: Double, want: Double) {
            val tol = TOL[name] ?: TOL_DEFAULT
            val diff = if (got.isNaN() && want.isNaN()) 0.0 else if (got.isNaN() || want.isNaN()) Double.POSITIVE_INFINITY else abs(got - want) / maxOf(1.0, abs(want))
            worst[name] = maxOf(worst[name] ?: 0.0, diff)
            if (!same(got, want, tol)) failures += "$clip $name: kotlin $got, python $want"
        }
        for (c in clips) {
            val r = c.result
            val f = r.features
            val kt = mapOf(
                "localFloorDb" to f.localFloorDb, "excessDb" to f.excessDb, "riseS" to f.riseS, "decayS" to f.decayS,
                "jaggedness" to f.jaggedness, "midBandRiseDb" to f.midBandRiseDb, "lfShare" to f.lfShare, "lfFlutterDb" to f.lfFlutterDb,
            )
            for ((k, v) in kt) check(c.name, "kt_$k", v, d(c.features["kt_$k"]))
            assertEquals(c.features.getValue("kt_shape").jsonPrimitive.content, f.shape, "${c.name} shape")
            assertEquals(d(c.features["kt_wind"]) == 1.0, f.wind, "${c.name} wind")
            for ((k, v) in r.raw) {
                if (!c.features.containsKey(k)) continue
                check(c.name, k, v, d(c.features[k]))
            }
        }
        println("EventHeadGoldenTest: max |kotlin − python| / max(1, |python|) per feature over ${clips.size} clips")
        for ((k, v) in worst) println(String.format(java.util.Locale.ROOT, "  %-32s %.3g  (tol %.0e)", k, v, TOL[k] ?: TOL_DEFAULT))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        // Every head input that is a raw feature was compared.
        val compared = worst.keys
        for (k in listOf("onset_mid_rise_0p5s_db", "per50_r", "per125_r", "mod_ratio_db", "centroid_peak_hz", "tonal_prom_db", "tob_25_rel_db", "tob_6300_rel_db", "lf_share_fft"))
            assertTrue(k in compared, "$k not compared")
    }

    @Test
    fun inputsMatchPython() {
        val failures = ArrayList<String>()
        var worst = 0.0
        for (c in clips) {
            val want = c.o.getValue("inputs").jsonArray.map { d(it) }
            for ((i, name) in head.inputNames.withIndex()) {
                val got = c.result.inputs[name] ?: Double.NaN
                val tol = TOL[name] ?: TOL_DEFAULT
                if (!(got.isNaN() && want[i].isNaN())) worst = maxOf(worst, abs(got - want[i]) / maxOf(1.0, abs(want[i])))
                if (!same(got, want[i], tol)) failures += "${c.name} $name: kotlin $got, python ${want[i]}"
            }
        }
        println("EventHeadGoldenTest: inputs max rel diff $worst")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The head alone: Python's input vectors through the Kotlin MLP give Python's probabilities. */
    @Test
    fun headOnPythonInputsMatchesPython() {
        var worst = 0.0
        for (c in clips) {
            val x = c.o.getValue("inputs").jsonArray.map { d(it) }.toDoubleArray()
            val want = c.o.getValue("probabilities").jsonArray.map { d(it) }
            val got = head.predict(x)
            for (k in want.indices) worst = maxOf(worst, abs(got[k] - want[k]))
            assertEquals(c.o.getValue("decision").jsonArray.map { it.jsonPrimitive.content }, head.decide(got), c.name)
        }
        println("EventHeadGoldenTest: head on Python inputs, max |Δp| = $worst")
        assertTrue(worst <= 1e-6, "max |Δp| $worst")
    }

    /** End to end: WAV → Kotlin features → Kotlin head, within 1e-4 of Python's probabilities. */
    @Test
    fun headOnKotlinFeaturesMatchesPythonWithin1e4() {
        var worst = 0.0
        for (c in clips) {
            val want = c.o.getValue("probabilities").jsonArray.map { d(it) }
            val res = c.result.head!!
            for ((k, cls) in head.classes.withIndex()) worst = maxOf(worst, abs(res.scores.getValue(cls) - want[k]))
            assertEquals(c.o.getValue("decision").jsonArray.map { it.jsonPrimitive.content }, res.decision, c.name)
        }
        println("EventHeadGoldenTest: end to end, max |Δp| = $worst")
        assertTrue(worst <= TOL_PROB, "max |Δp| $worst")
    }
}
