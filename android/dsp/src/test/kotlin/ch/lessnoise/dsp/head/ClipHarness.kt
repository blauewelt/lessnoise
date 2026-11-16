package ch.lessnoise.dsp.head

import ch.lessnoise.dsp.AWeighting
import ch.lessnoise.dsp.Butterworth
import ch.lessnoise.dsp.EventFeatureTracker
import ch.lessnoise.dsp.EventFeatures
import ch.lessnoise.dsp.QuickSelect
import ch.lessnoise.dsp.WindRule
import ch.lessnoise.dsp.classify.EventHeadClassifier
import ch.lessnoise.dsp.classify.EventHeadResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.log10

/**
 * The Labor analysis's per-clip pipeline (`step3/features.py`: `tick_series`, `tick_spectra`,
 * `compute_clip`) on a 16 kHz clip, statement by statement — but feeding the production classes
 * ([EventFeatureTracker], [HeadTickStore], [TickSpectrum], [TonalAccumulator],
 * [HeadFeatureExtractor], [HeadInputs], the head). It checks the *port* of the feature
 * definitions; the phone's own signal path (48 kHz input, decimated spectra, the live detector)
 * is checked against this harness in [HeadEngineTest].
 *
 * Python conventions kept here (test-only): samples = int16 / 32767; Fast weighting seeded with
 * the first tick's mean square; no 8 kHz low-pass (it sits at Nyquist at 16 kHz); invalid ticks =
 * zero run ≥ 10 ms or mean square < −130 dBFS, plus a 0.5 s guard; spectrum frames reflect-padded
 * at the clip edges; event = ticks [start, start + round(durationS·8)) cut at the clip end; floor
 * = 10th percentile of the valid LAF ticks before the start (else after the end).
 */
object ClipHarness {
    const val FS = 16_000
    const val TICK = FS / 8
    private const val ZERO_RUN = 160
    private const val FLOOR_MS = 1e-13
    private const val GUARD = FS / 2

    class Result(
        val features: EventFeatures,
        val raw: Map<String, Double>,
        val inputs: Map<String, Double>,
        val head: EventHeadResult?,
        val peak: Long, val bestStart: Long, val topTicks: Int, val tailEnd: Long, val floor: Double, val lafMax: Double,
        val ticks: Int,
    )

    /** int16 / 32767 like `features.read_wav`. */
    fun readWav(file: File): DoubleArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(b.array(), pos, 4)
            val size = b.getInt(pos + 4)
            if (id == "data") {
                val n = size / 2
                return DoubleArray(n) { b.getShort(pos + 8 + 2 * it) / 32767.0 }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in ${file.name}")
    }

    fun run(
        x: DoubleArray, startTick: Int, durationS: Double, offsetDb: Double, backgroundDb: Double, head: EventHeadClassifier?,
    ): Result {
        val nt = x.size / TICK
        val n = nt * TICK
        val aw = AWeighting(FS)
        val hp250 = Butterworth.highpass(250.0, FS); val lp4500 = Butterworth.lowpass(4500.0, FS)
        val hp20 = Butterworth.highpass(20.0, FS); val lp200 = Butterworth.lowpass(200.0, FS)
        val alpha = 1.0 - exp(-1.0 / (0.125 * FS))
        val a = DoubleArray(n) { aw.process(x[it]) }
        var y = if (n > 0) (0 until TICK).sumOf { a[it] * a[it] } / TICK else 0.0
        val laf = DoubleArray(nt); val lafMaxTick = DoubleArray(nt)
        val eA = DoubleArray(nt); val eBand = DoubleArray(nt); val eLow = DoubleArray(nt); val eTot = DoubleArray(nt)
        val sub = Array(nt) { DoubleArray(HeadTickStore.SUB) }
        val bad = BooleanArray(nt)
        var run = 0
        for (k in 0 until nt) {
            var mx = 0.0; var sA = 0.0; var sB = 0.0; var sL = 0.0; var sT = 0.0; var sX = 0.0; var maxRun = 0
            for (j in 0 until TICK) {
                val i = k * TICK + j
                val a2 = a[i] * a[i]
                y = y + alpha * (a2 - y)
                if (y > mx) mx = y
                sA += a2
                sub[k][j / (TICK / HeadTickStore.SUB)] += a2
                val band = lp4500.process(hp250.process(a[i]))
                val h = hp20.process(x[i])
                val low = lp200.process(h)
                sB += band * band; sL += low * low; sT += h * h; sX += x[i] * x[i]
                run = if (x[i] == 0.0) run + 1 else 0
                if (run > maxRun) maxRun = run
            }
            laf[k] = 10 * log10(maxOf(y, 1e-20)) + offsetDb
            lafMaxTick[k] = 10 * log10(maxOf(mx, 1e-20)) + offsetDb
            eA[k] = sA / TICK; eBand[k] = sB / TICK; eLow[k] = sL / TICK; eTot[k] = sT / TICK
            for (s in 0 until HeadTickStore.SUB) sub[k][s] /= (TICK / HeadTickStore.SUB)
            bad[k] = maxRun >= ZERO_RUN || sX / TICK < FLOOR_MS
        }
        val invalid = BooleanArray(nt)
        var guardUntil = -1L
        for (k in 0 until nt) {
            val endS = (k + 1L) * TICK
            if (bad[k]) { guardUntil = endS + GUARD; invalid[k] = true } else if (endS <= guardUntil) invalid[k] = true
        }

        val tracker = EventFeatureTracker(capacityTicks = nt + 8, tickSeconds = 0.125, preTicks = 80)
        val store = HeadTickStore(nt + 8)
        val start = startTick.toLong()
        val end = minOf(start + Math.round(durationS * 8), nt.toLong())
        val tonal = TonalAccumulator(start).also { it.endTick = end }
        val spec = TickSpectrum()
        // Reflect-padded signal (numpy pad mode "reflect") for the spectrum frames.
        val pad = TickSpectrum.NFFT / 2
        fun xr(i: Int): Double {
            var j = i
            while (j < 0 || j >= n) j = if (j < 0) -j else 2 * (n - 1) - j
            return x[j]
        }
        val frame = DoubleArray(TickSpectrum.NFFT)
        for (k in 0 until nt) {
            if (invalid[k]) { tracker.pushInvalid(); store.putSubInvalid(k.toLong()) } else {
                tracker.push(laf[k], eA[k], eBand[k], eLow[k], eTot[k]); store.putSub(k.toLong(), sub[k])
            }
            val c = k * TICK + TICK / 2
            for (j in 0 until TickSpectrum.NFFT) frame[j] = xr(c - pad + j)
            spec.analyze(frame)
            store.putSpectrum(k.toLong(), spec)
            tonal.add(k.toLong(), !invalid[k], spec)
        }

        fun lafAt(i: Long) = tracker.lafAt(i)
        val pre = (0 until startTick).filter { !invalid[it] }.map { laf[it] }.toDoubleArray()
        var floor = if (pre.isEmpty()) Double.NaN else QuickSelect.percentile(pre.copyOf(), pre.size, 10.0)
        if (floor.isNaN()) {
            val post = (end.toInt() until nt).filter { !invalid[it] }.map { laf[it] }.toDoubleArray()
            floor = if (post.isEmpty()) Double.NaN else QuickSelect.percentile(post.copyOf(), post.size, 10.0)
        }
        var lafMax = Double.NaN
        for (k in start until end) { val v = if (invalid[k.toInt()]) Double.NaN else lafMaxTick[k.toInt()]; if (!v.isNaN() && (lafMax.isNaN() || v > lafMax)) lafMax = v }
        val te = HeadFeatureExtractor.tailEnd(::lafAt, nt.toLong(), start, end, floor)
        val f = tracker.compute(start, end, maxOf(te, end), floor, lafMax, durationS, WindRule())
        val raw = HeadFeatureExtractor.compute(
            tracker, store, tonal, start, end, tracker.lastPeakTick, tracker.lastBestStart, tracker.lastTopTicks, nt.toLong(),
        )
        val inputs = HeadInputs.f1(raw, f, lafMax, backgroundDb, durationS)
        return Result(
            f, raw, inputs, head?.evaluate(inputs), tracker.lastPeakTick, tracker.lastBestStart, tracker.lastTopTicks, te, floor, lafMax, nt,
        )
    }
}
