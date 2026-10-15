package ch.lessnoise.dsp.golden

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A synthetic test signal described by a few numbers, so that Kotlin and Swift build exactly the
 * same samples: only additions and multiplications of doubles from the spec (no transcendental
 * function is evaluated per sample; a sine is a rotation by the stored cos/sin of its step).
 *
 * The parts are applied in order to a double buffer of [totalSamples] zeros:
 * - `noise`: adds `(2u − 1)·rms·√3` (u from SplitMix64(seed), uniform [0, 1)) to [start, start+n);
 * - `sine`: adds `amp·y_k`, (x, y) starting at (1, 0) and rotated by (c, s) every sample;
 * - `burst`: like noise, times a raised-cosine envelope with a rotation (c, s) of half a period
 *   over the burst: env_k = (1 − x_k)/2;
 * - `square`: sets ±amp with period `period` samples (first half +);
 * - `zeros`: sets 0.
 * Finally every sample is rounded to Float.
 */
class GoldenSignal(val fs: Int, val totalSamples: Int) {
    private val parts = ArrayList<JsonObject>()
    private val buf = DoubleArray(totalSamples)

    private fun range(startS: Double, seconds: Double): IntRange {
        val a = (startS * fs).toInt()
        val n = (seconds * fs).toInt()
        return a until minOf(a + n, totalSamples)
    }

    fun noise(startS: Double, seconds: Double, rms: Double, seed: Long) = apply {
        parts += buildJsonObject { put("type", "noise"); put("start_s", startS); put("seconds", seconds); put("rms", rms); put("seed", seed) }
        val r = SplitMix64(seed)
        val a = rms * sqrt(3.0)
        for (i in range(startS, seconds)) buf[i] += (r.nextDouble() * 2 - 1) * a
    }

    fun sine(startS: Double, seconds: Double, freq: Double, amp: Double) = apply {
        val w = 2 * PI * freq / fs
        val c = cos(w)
        val s = sin(w)
        parts += buildJsonObject {
            put("type", "sine"); put("start_s", startS); put("seconds", seconds); put("amp", amp); put("c", c); put("s", s)
        }
        var x = 1.0
        var y = 0.0
        for (i in range(startS, seconds)) {
            buf[i] += amp * y
            val nx = x * c - y * s
            y = x * s + y * c
            x = nx
        }
    }

    fun burst(startS: Double, seconds: Double, rms: Double, seed: Long) = apply {
        val r = range(startS, seconds)
        val n = r.last - r.first + 1
        val w = 2 * PI / n
        val c = cos(w)
        val s = sin(w)
        parts += buildJsonObject {
            put("type", "burst"); put("start_s", startS); put("seconds", seconds); put("rms", rms); put("seed", seed)
            put("c", c); put("s", s)
        }
        val g = SplitMix64(seed)
        val a = rms * sqrt(3.0)
        var x = 1.0
        var y = 0.0
        for (i in r) {
            val env = (1 - x) / 2
            buf[i] += (g.nextDouble() * 2 - 1) * a * env
            val nx = x * c - y * s
            y = x * s + y * c
            x = nx
        }
    }

    fun square(startS: Double, seconds: Double, amp: Double, period: Int) = apply {
        parts += buildJsonObject { put("type", "square"); put("start_s", startS); put("seconds", seconds); put("amp", amp); put("period", period) }
        var k = 0
        for (i in range(startS, seconds)) {
            buf[i] = if (k % period < period / 2) amp else -amp
            k++
        }
    }

    fun zeros(startS: Double, seconds: Double) = apply {
        parts += buildJsonObject { put("type", "zeros"); put("start_s", startS); put("seconds", seconds) }
        for (i in range(startS, seconds)) buf[i] = 0.0
    }

    fun samples(): FloatArray = FloatArray(totalSamples) { buf[it].toFloat() }

    fun spec(): JsonObject = buildJsonObject {
        put("fs", fs); put("total_samples", totalSamples); put("parts", JsonArray(parts))
    }
}

/** [from, to) sample ranges (block starts) during which the engine is told the mic is silenced. */
fun silencedJson(ranges: List<LongRange>): JsonArray = buildJsonArray {
    ranges.forEach { r -> add(buildJsonArray { add(r.first); add(r.last + 1) }) }
}
