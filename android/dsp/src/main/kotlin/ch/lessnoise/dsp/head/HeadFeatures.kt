package ch.lessnoise.dsp.head

import ch.lessnoise.dsp.EventFeatureTracker
import ch.lessnoise.dsp.EventFeatures
import ch.lessnoise.dsp.EventShape
import ch.lessnoise.dsp.QuickSelect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The event features of the multi-label event head that the detector does not already compute
 * (port of `step3/features.py` of the Labor analysis: level-envelope extras, envelope
 * periodicity, spectral shape; column names and definitions as in `step3/features/columns.md`).
 *
 * Everything is computed once per event from per-tick values that are recorded while the audio
 * runs ([EventFeatureTracker]: LAF and band energies; [HeadTickStore]: 25 ms A-weighted energies
 * and the per-tick spectrum summary; [TonalAccumulator]: the event-mean 40–800 Hz spectrum), so
 * the per-tick cost is O(1) and no audio is held beyond the 1 s classifier ring.
 *
 * Phone-feasible definitions (where they differ from the Python, see android/dsp/EVENT_HEAD.md):
 * - "pre-roll" = the [PRE_TICKS] valid ticks before the event start held by the tracker (a Labor
 *   clip has exactly these 5 s before the event);
 * - "the clip end" = the ticks available when the features are computed ([available]); the
 *   engine waits until the ticks the Python would read after the event exist (≤ 1 s after the
 *   end), except when the measurement stops first;
 * - the tail end for `dur_above6_s` is the Python rule evaluated on the available ticks.
 */
object HeadFeatureExtractor {
    /** Ticks before the event used as pre-roll (5 s, the Labor clips' pre-roll). */
    const val PRE_TICKS = 40
    /** Ticks of the shortest periodicity window (2 s); shorter events are extended symmetrically. */
    const val MIN_WINDOW_TICKS = 16
    /** `features.py` tail_end: excess that ends a tail when a new event starts (the default setting). */
    const val TAIL_EXCESS_DB = 6.5
    const val TAIL_TICKS = 40
    const val TICK_S = 0.125

    /** The periodicity window [s, e) of an event [start, end) (Python `envelope_features`, no clip end). */
    fun window(start: Long, end: Long, oldest: Long): LongArray {
        var s = start
        var e = end
        if (e - s < MIN_WINDOW_TICKS) {
            val need = MIN_WINDOW_TICKS - (e - s)
            s = max(oldest, s - need / 2)
            e = s + MIN_WINDOW_TICKS
        }
        return longArrayOf(s, e)
    }

    /** Ticks that must exist (tracker.count) before the features read nothing missing: onset (end + 4) and the window. */
    fun ticksNeeded(start: Long, end: Long, oldest: Long): Long = max(end + 4, window(start, end, oldest)[1])

    /**
     * Raw features (names as in columns.md) of the event of ticks [start, end) whose peak tick,
     * loudest-1-s start and width come from the tracker's [EventFeatureTracker.compute] ([peak],
     * [bestStart], [topTicks]); [available] = number of ticks that may be read (exclusive).
     */
    fun compute(
        tracker: EventFeatureTracker, store: HeadTickStore, tonal: TonalAccumulator?,
        start: Long, end: Long, peak: Long, bestStart: Long, topTicks: Int, available: Long = tracker.count,
    ): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        val oldest = tracker.oldest
        val count = min(available, tracker.count)
        fun laf(i: Long) = if (i < oldest || i >= count) Double.NaN else tracker.lafAt(i)
        fun valid(i: Long) = !laf(i).isNaN()
        val preFrom = max(start - PRE_TICKS, oldest)

        // ---- level envelope extras -------------------------------------------------------
        val pre = ArrayList<Double>()
        for (i in preFrom until start) { val v = laf(i); if (!v.isNaN()) pre += v }
        var floor = percentile10(pre)
        if (floor.isNaN()) { // whole pre-roll invalid: valid ticks after the end (Python: rest of the clip)
            val post = ArrayList<Double>()
            for (i in end until count) { val v = laf(i); if (!v.isNaN()) post += v }
            floor = percentile10(post)
        }
        out["laf_floor_pre_db"] = floor
        var curvePeak = Double.NaN
        for (i in start until min(end, count)) { val v = laf(i); if (!v.isNaN() && (curvePeak.isNaN() || v > curvePeak)) curvePeak = v }
        out["peak_excess_db"] = curvePeak - floor
        out["peak_time_in_event_s"] = (peak - start) * TICK_S
        val te = tailEnd(::laf, count, start, end, floor)
        var above = 0
        for (i in start until min(count, te)) if (laf(i) >= floor + 6.0) above++
        out["dur_above6_s"] = above * TICK_S
        out["tail_end_ticks"] = (te - end).toDouble()
        out["env_d2_rms_db"] = if (end - start >= 3) {
            var s = 0.0
            for (i in start + 1 until end - 1) { val d2 = laf(i + 1) - 2 * laf(i) + laf(i - 1); s += d2 * d2 }
            sqrt(s / (end - start - 2))
        } else Double.NaN
        // Onset: fastest 0.5 s rise of the mid-band level, from 1 s before the start to the end.
        var onset = Double.NaN
        for (i in max(oldest, start - 8) until end) {
            if (i + 4 >= count) break
            val r = bandDb(tracker, i + 4, count) - bandDb(tracker, i, count)
            if (!r.isNaN() && (onset.isNaN() || r > onset)) onset = r
        }
        out["onset_mid_rise_0p5s_db"] = onset

        // ---- envelope periodicity ----------------------------------------------------------
        envelope(tracker, store, start, end, oldest, count, out)

        // ---- spectral -----------------------------------------------------------------------
        val preIdx = ArrayList<Long>()
        for (i in preFrom until start) if (valid(i) && store.hasSpectrum(i)) preIdx += i
        val evIdx = ArrayList<Long>()
        for (i in start until min(end, count)) if (valid(i) && store.hasSpectrum(i)) evIdx += i
        for (b in TickSpectrum.TOB.indices) {
            val e = evIdx.map { store.tobAt(it, b) }.average()
            val q = preIdx.map { store.tobAt(it, b) }.average()
            out["tob_${TickSpectrum.TOB_NAMES[b]}_rel_db"] =
                if (evIdx.isNotEmpty() && preIdx.isNotEmpty()) 10 * log10(max(e, 1e-30) / max(q, 1e-30)) else Double.NaN
        }
        fun cen(i: Long) = if (valid(i)) store.centroidAt(i) else Double.NaN
        out["centroid_peak_hz"] = cen(peak)
        val sel = (start until min(end, count)).toList()
        out["centroid_slope_oct_per_s"] = olsSlope(sel) { i -> log2(max(cen(i), 1e-9)).let { if (cen(i).isNaN()) Double.NaN else it } }
        out["centroid_slope_hz_per_s"] = olsSlope(sel) { cen(it) }
        out["centroid_event_mean_hz"] = if (evIdx.isNotEmpty()) nanMean(sel.map { cen(it) }) else Double.NaN
        out["centroid_pre_hz"] = if (preIdx.isNotEmpty()) nanMean(preIdx.map { cen(it) }) else Double.NaN
        // 1–4 kHz and 250–1000 Hz in the loudest 1 s vs the pre-roll (the top ticks include invalid ones, as in the Python).
        val top = (bestStart until bestStart + topTicks).filter { it in oldest until count && store.hasSpectrum(it) }
        fun rise(f: (Long) -> Double): Double =
            if (preIdx.isNotEmpty() && top.isNotEmpty()) 10 * log10(max(top.map(f).average(), 1e-30) / max(preIdx.map(f).average(), 1e-30))
            else Double.NaN
        out["rise_1k_4k_db"] = rise { store.hfAt(it) }
        out["rise_250_1k_db"] = rise { store.midAt(it) }
        out["hf_minus_mid_rise_db"] = out.getValue("rise_1k_4k_db") - out.getValue("rise_250_1k_db")
        out["hf_mid_ratio_slope_db_per_s"] = olsSlope(sel) { i ->
            if (!valid(i)) Double.NaN else 10 * log10(max(store.hfAt(i), 1e-30) / max(store.midAt(i), 1e-30))
        }
        fun flat(i: Long) = if (valid(i)) store.flatnessAt(i) else Double.NaN
        out["flatness_event"] = if (evIdx.isNotEmpty()) nanMean(sel.map { flat(it) }) else Double.NaN
        out["flatness_peak"] = flat(peak)
        out["flatness_pre"] = if (preIdx.isNotEmpty()) nanMean(preIdx.map { flat(it) }) else Double.NaN
        if (tonal != null && tonal.ticks > 0 && evIdx.isNotEmpty()) {
            val t = TickSpectrum()
            out["tonal_prom_db"] = t.prominence(tonal.sum, 0, 1.0 / tonal.ticks)
            out["tonal_f_hz"] = t.lastProminenceHz
        } else {
            out["tonal_prom_db"] = Double.NaN
            out["tonal_f_hz"] = Double.NaN
        }
        out["tonal_prom_tick_median_db"] = if (evIdx.isNotEmpty()) median(evIdx.map { store.prominenceAt(it) }) else Double.NaN
        val lfSum = evIdx.sumOf { store.lfAt(it) }
        val totSum = evIdx.sumOf { store.totAt(it) }
        out["lf_share_fft"] = if (evIdx.isNotEmpty() && totSum > 0) lfSum / totSum else Double.NaN
        return out
    }

    private fun bandDb(tracker: EventFeatureTracker, i: Long, count: Long): Double {
        if (i >= count) return Double.NaN
        val e = tracker.bandAt(i)
        return if (e.isNaN()) Double.NaN else 10 * log10(max(e, 1e-30))
    }

    /** `features.py` tail_end on the ticks below [count]: first tick ≤ 10 % level, a new start, invalid audio, or 5 s. */
    fun tailEnd(laf: (Long) -> Double, count: Long, start: Long, end: Long, floor: Double): Long {
        var peak = Double.NaN
        for (i in start until min(end, count)) { val v = laf(i); if (!v.isNaN() && (peak.isNaN() || v > peak)) peak = v }
        val lo = floor + 0.1 * (peak - floor)
        var cur = end
        while (cur < min(count, end + TAIL_TICKS)) {
            val v = laf(cur)
            if (v.isNaN()) return cur
            if (cur > end && v >= floor + TAIL_EXCESS_DB) return cur
            if (v <= lo || cur + 1 - end >= TAIL_TICKS) return cur + 1
            cur++
        }
        return if (end + TAIL_TICKS <= count) end + TAIL_TICKS else count
    }

    private fun envelope(
        tracker: EventFeatureTracker, store: HeadTickStore, start: Long, end: Long, oldest: Long, count: Long,
        out: MutableMap<String, Double>,
    ) {
        val w = window(start, end, oldest)
        var s = w[0]
        var e = min(w[1], count)
        if (e - s < MIN_WINDOW_TICKS && end - start < MIN_WINDOW_TICKS) s = max(oldest, e - MIN_WINDOW_TICKS) // clip-end rule
        out["period_window_s"] = (e - s) * TICK_S
        val n = (e - s).toInt()
        val env125 = DoubleArray(n) { tracker.lafAt(s + it) }
        if (n >= MIN_WINDOW_TICKS && env125.none { it.isNaN() }) {
            val r = acfPeak(detrendMa(env125, 10), 2, 8, 0.125)
            out["per125_lag_s"] = r[0]; out["per125_r"] = r[1]
        } else {
            out["per125_lag_s"] = Double.NaN; out["per125_r"] = Double.NaN
        }
        // 50 ms envelope: pairs of 25 ms sub-blocks from the window start.
        val nf = n * HeadTickStore.SUB / 2
        var anyInvalid = false
        for (i in s until e) if (tracker.lafAt(i).isNaN()) anyInvalid = true
        if (nf >= 40 && !anyInvalid) {
            val env50 = DoubleArray(nf) { f ->
                val a = 2 * f
                val b = 2 * f + 1
                val ma = store.subAt(s + a / HeadTickStore.SUB, a % HeadTickStore.SUB)
                val mb = store.subAt(s + b / HeadTickStore.SUB, b % HeadTickStore.SUB)
                10 * log10(max((ma + mb) / 2, 1e-20))
            }
            val r = acfPeak(detrendMa(env50, 25), 5, 20, 0.05)
            out["per50_lag_s"] = r[0]; out["per50_r"] = r[1]
            out["per50_hz"] = if (r[0].isNaN()) Double.NaN else 1.0 / r[0]
            modulation(env50, out)
        } else {
            for (k in listOf("per50_lag_s", "per50_r", "per50_hz", "mod_1_4_db", "mod_02_1_db", "mod_ratio_db", "mod_peak_hz")) out[k] = Double.NaN
        }
    }

    /** Modulation spectrum of the 50 ms envelope (linear detrend, symmetric Hann, FFT ≥ 1024 points at 20 Hz). */
    private fun modulation(env: DoubleArray, out: MutableMap<String, Double>) {
        val nf = env.size
        // Least-squares line (np.polyfit degree 1).
        var mt = 0.0; var my = 0.0
        for (i in 0 until nf) { mt += i; my += env[i] }
        mt /= nf; my /= nf
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until nf) { sxy += (i - mt) * (env[i] - my); sxx += (i - mt) * (i - mt) }
        val slope = sxy / sxx
        val icpt = my - slope * mt
        var nfft = 1
        while (nfft < nf) nfft = nfft shl 1
        nfft = max(1024, nfft)
        val v = DoubleArray(nf) { i ->
            val hann = if (nf > 1) 0.5 - 0.5 * cos(2 * PI * i / (nf - 1)) else 1.0
            (env[i] - (slope * i + icpt)) * hann
        }
        val fft = Fft(nfft)
        val s = DoubleArray(nfft / 2 + 1)
        fft.powerSpectrum(v, nf, DoubleArray(nfft), DoubleArray(nfft), s)
        val df = 1.0 / (nfft * 0.05)
        var e14 = 0.0; var e021 = 0.0
        var best = Double.NEGATIVE_INFINITY; var bestF = Double.NaN
        for (k in s.indices) {
            val f = k * df
            if (f >= 1 && f <= 4) {
                e14 += s[k]
                if (s[k] > best) { best = s[k]; bestF = f }
            }
            if (f >= 0.2 && f < 1) e021 += s[k]
        }
        out["mod_1_4_db"] = 10 * log10(max(e14, 1e-30))
        out["mod_02_1_db"] = 10 * log10(max(e021, 1e-30))
        out["mod_ratio_db"] = out.getValue("mod_1_4_db") - out.getValue("mod_02_1_db")
        out["mod_peak_hz"] = bestF
    }

    /** Residual after a centred moving average of L samples, h = L / 2 each side (shrinking at the edges). */
    fun detrendMa(v: DoubleArray, l: Int): DoubleArray {
        val n = v.size
        val c = DoubleArray(n + 1)
        for (i in 0 until n) c[i + 1] = c[i] + v[i]
        val h = l / 2
        return DoubleArray(n) { i ->
            val a = max(0, i - h)
            val b = min(n, i + h + 1)
            v[i] - (c[b] - c[a]) / (b - a)
        }
    }

    /** Normalised autocorrelation: highest local maximum in [lagMin, lagMax], parabolic refinement → (lag s, r). */
    fun acfPeak(v0: DoubleArray, lagMin: Int, lagMax: Int, frameS: Double): DoubleArray {
        if (v0.any { it.isNaN() } || v0.size < 2 * lagMax) return doubleArrayOf(Double.NaN, Double.NaN)
        val n = v0.size
        val mean = v0.average()
        val v = DoubleArray(n) { v0[it] - mean }
        var den = 0.0
        for (x in v) den += x * x
        if (den <= 0) return doubleArrayOf(Double.NaN, 0.0)
        val lo = max(1, lagMin - 1)
        val hi = lagMax + 1
        val r = DoubleArray(hi + 2) { Double.NaN }
        for (k in lo..hi) {
            if (k >= n) continue
            var s = 0.0
            for (i in 0 until n - k) s += v[i] * v[i + k]
            r[k] = s / den
        }
        var bestK = -1
        var bestR = Double.NEGATIVE_INFINITY
        for (k in lagMin..lagMax) {
            if (k - 1 < lo || r[k - 1].isNaN() || r[k].isNaN() || r[k + 1].isNaN()) continue
            if (r[k] >= r[k - 1] && r[k] >= r[k + 1] && r[k] > bestR) { bestK = k; bestR = r[k] }
        }
        if (bestK < 0) return doubleArrayOf(Double.NaN, 0.0)
        val y0 = r[bestK - 1]; val y1 = r[bestK]; val y2 = r[bestK + 1]
        val dnm = y0 - 2 * y1 + y2
        val off = if (dnm != 0.0) 0.5 * (y0 - y2) / dnm else 0.0
        return doubleArrayOf((bestK + off.coerceIn(-0.5, 0.5)) * frameS, bestR)
    }

    /** Least-squares slope of y(i) against t = i·0.125 s over the non-NaN points; NaN for < 3 points. */
    private fun olsSlope(idx: List<Long>, y: (Long) -> Double): Double {
        val t = ArrayList<Double>(); val v = ArrayList<Double>()
        for (i in idx) { val yy = y(i); if (!yy.isNaN()) { t += i * TICK_S; v += yy } }
        if (t.size < 3) return Double.NaN
        val mt = t.average(); val mv = v.average()
        var d = 0.0; var nmr = 0.0
        for (k in t.indices) { val tt = t[k] - mt; d += tt * tt; nmr += tt * (v[k] - mv) }
        return if (d > 0) nmr / d else Double.NaN
    }

    private fun nanMean(v: List<Double>): Double {
        val f = v.filter { !it.isNaN() }
        return if (f.isEmpty()) Double.NaN else f.average()
    }

    private fun median(v: List<Double>): Double {
        val a = v.toDoubleArray()
        if (a.isEmpty()) return Double.NaN
        if (a.any { it.isNaN() }) return Double.NaN
        a.sort()
        val m = a.size / 2
        return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) / 2
    }

    private fun percentile10(v: List<Double>): Double {
        if (v.isEmpty()) return Double.NaN
        val a = v.toDoubleArray()
        return QuickSelect.percentile(a, a.size, 10.0)
    }
}

/**
 * The head's model inputs (`f1_row` in `step3/train/dataset.py`, the "F1" feature set without the
 * situation features): the detector-v2 features, the event levels and the [HeadFeatureExtractor]
 * features, with the same transforms (log10 with offsets, missing flags, one-hot shape).
 */
object HeadInputs {
    /** Python `lg`: log_base(x + off) if finite and positive, else NaN. */
    private fun lg(x: Double, off: Double, base: Double = 10.0): Double =
        if (!x.isNaN() && x + off > 0) ln(x + off) / ln(base) else Double.NaN

    private fun flag(b: Boolean) = if (b) 1.0 else 0.0

    fun f1(raw: Map<String, Double>, f: EventFeatures, lafMaxDb: Double, backgroundDb: Double, durationS: Double): Map<String, Double> {
        val m = LinkedHashMap<String, Double>()
        fun r(k: String) = raw[k] ?: Double.NaN
        m["excessDb"] = f.excessDb
        m["log10_riseS"] = lg(f.riseS, 0.05); m["riseS_missing"] = flag(f.riseS.isNaN())
        m["log10_decayS"] = lg(f.decayS, 0.05); m["decayS_missing"] = flag(f.decayS.isNaN())
        m["jaggedness"] = f.jaggedness
        m["midBandRiseDb"] = f.midBandRiseDb
        m["lfShare"] = f.lfShare
        m["lfFlutterDb"] = f.lfFlutterDb
        m["wind_rule"] = flag(f.wind)
        for (s in listOf(EventShape.LONG, EventShape.IMPULSE, EventShape.JAGGED, EventShape.HUMP)) m["shape_$s"] = flag(f.shape == s)
        m["lafMaxDb"] = lafMaxDb
        m["localFloorDb"] = f.localFloorDb
        m["backgroundDb"] = backgroundDb
        m["log10_durationS"] = lg(durationS, 0.125)
        m["onset_mid_rise_0p5s_db"] = r("onset_mid_rise_0p5s_db")
        m["peak_excess_db"] = r("peak_excess_db")
        m["log10_dur_above6_s"] = lg(r("dur_above6_s"), 0.125)
        m["env_d2_rms_db"] = r("env_d2_rms_db")
        m["peak_pos_rel"] = if (durationS > 0) { val p = r("peak_time_in_event_s") / durationS; if (p.isNaN()) 1.0 else min(1.0, p) } else Double.NaN
        m["per50_r"] = r("per50_r"); m["per125_r"] = r("per125_r")
        val hz = r("per50_hz"); m["per50_hz"] = hz; m["per50_hz_missing"] = flag(hz.isNaN())
        m["mod_ratio_db"] = r("mod_ratio_db"); m["mod_peak_hz"] = r("mod_peak_hz")
        m["log2_centroid_peak_hz"] = lg(r("centroid_peak_hz"), 0.0, 2.0)
        m["log2_centroid_event_hz"] = lg(r("centroid_event_mean_hz"), 0.0, 2.0)
        m["log2_centroid_pre_hz"] = lg(r("centroid_pre_hz"), 0.0, 2.0)
        m["centroid_slope_oct_per_s"] = r("centroid_slope_oct_per_s")
        m["rise_1k_4k_db"] = r("rise_1k_4k_db"); m["rise_250_1k_db"] = r("rise_250_1k_db")
        m["hf_minus_mid_rise_db"] = r("hf_minus_mid_rise_db")
        m["hf_mid_ratio_slope_db_per_s"] = r("hf_mid_ratio_slope_db_per_s")
        m["flatness_event"] = r("flatness_event"); m["flatness_peak"] = r("flatness_peak"); m["flatness_pre"] = r("flatness_pre")
        m["tonal_prom_db"] = r("tonal_prom_db"); m["log2_tonal_f_hz"] = lg(r("tonal_f_hz"), 0.0, 2.0)
        m["tonal_prom_tick_median_db"] = r("tonal_prom_tick_median_db")
        m["lf_share_fft"] = r("lf_share_fft")
        for (b in TickSpectrum.TOB_NAMES) m["tob_${b}_rel_db"] = r("tob_${b}_rel_db")
        return m
    }
}
