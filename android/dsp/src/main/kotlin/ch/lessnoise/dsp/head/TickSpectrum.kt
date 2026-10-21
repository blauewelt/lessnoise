package ch.lessnoise.dsp.head

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Per-tick spectral summary for the event head (port of `spectral_features` / `tick_spectra` in
 * the Labor analysis `step3/features.py`).
 *
 * One 4096-point periodic-Hann frame of the **16 kHz** signal per 125 ms tick, centred on the
 * tick's centre (256 ms; 3.9 Hz bins), one-sided power spectrum P in mean-square units (sums to
 * the frame's Hann-weighted mean square), unweighted. From P, per tick: the 1/3-octave band
 * powers 25 Hz…6.3 kHz, the spectral centroid 50–7500 Hz, the 1–4 kHz and 250–1000 Hz powers,
 * the spectral flatness 50–7500 Hz, the tonal prominence 80–600 Hz, the 20–200 Hz and
 * 20–7500 Hz powers, and (for the event-mean spectrum) the raw 40–800 Hz bins.
 *
 * Cost per tick: one 4096-point complex FFT (≈ 25 k butterflies), ≈ 1 900 logarithms (flatness)
 * and a 15-point median over 194 bins (tonality); no allocation.
 */
class TickSpectrum {
    companion object {
        const val FS = 16_000
        const val NFFT = 4096
        /** 16 kHz samples per 125 ms tick. */
        const val TICK = FS / 8
        /** 1/3-octave centre frequencies of the head's `tob_*_rel_db` inputs (25 Hz … 6.3 kHz). */
        val TOB = doubleArrayOf(
            25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0,
            1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0,
        )
        /** Column-name fragment of each band: 31.5 → "31p5" (as in features.csv). */
        val TOB_NAMES: List<String> = TOB.map { fc ->
            val s = if (fc == Math.floor(fc)) fc.toLong().toString() else fc.toString()
            s.replace(".", "p")
        }
        val BINS = NFFT / 2 + 1
        /** numpy `rfftfreq(4096, 1/16000)`, same floating-point operations. */
        val FREQS = DoubleArray(BINS).also { f ->
            val v = 1.0 / (NFFT * (1.0 / FS))
            for (k in f.indices) f[k] = k * v
        }

        /** [first, last) bin range with lo ≤ f < hi (numpy `band_mask`). */
        fun range(lo: Double, hi: Double): IntArray {
            var a = 0
            while (a < BINS && !(FREQS[a] >= lo)) a++
            var b = a
            while (b < BINS && FREQS[b] < hi) b++
            return intArrayOf(a, b)
        }

        val TOB_RANGES: List<IntArray> = TOB.map { fc -> range(fc * 2.0.pow(-1.0 / 6), fc * 2.0.pow(1.0 / 6)) }
        val SPEC = range(50.0, 7500.0)
        val HF = range(1000.0, 4000.0)
        val MID = range(250.0, 1000.0)
        val LF = range(20.0, 200.0)
        val TOT = range(20.0, 7500.0)
        /** Bins for the tonality analysis (40–800 Hz) … */
        val TONAL = range(40.0, 800.0)
        val TONAL_BINS = TONAL[1] - TONAL[0]
        /** … of which the peak is searched in 80–600 Hz (inclusive, as in the Python). */
        val TONAL_INNER: IntArray = run {
            var a = TONAL[0]
            while (a < TONAL[1] && !(FREQS[a] >= 80.0)) a++
            var b = a
            while (b < TONAL[1] && FREQS[b] <= 600.0) b++
            intArrayOf(a - TONAL[0], b - TONAL[0])
        }
        const val MEDIAN_SIZE = 15

        /** numpy `hanning(4097)[:-1]` (periodic Hann). */
        private val HANN = DoubleArray(NFFT) { 0.5 - 0.5 * cos(2 * PI * it / NFFT) }
        private val WSCALE = 1.0 / (NFFT * HANN.sumOf { it * it })
    }

    private val fft = Fft(NFFT)
    private val re = DoubleArray(NFFT)
    private val im = DoubleArray(NFFT)
    /** The last frame's power spectrum (one-sided, mean-square units). */
    val power = DoubleArray(BINS)
    private val dB = DoubleArray(TONAL_BINS)
    private val med = DoubleArray(TONAL_BINS)
    private val win = DoubleArray(MEDIAN_SIZE)

    // Results of the last [analyze].
    val tob = DoubleArray(TOB.size)
    var centroidHz = 0.0; private set
    var hfPower = 0.0; private set
    var midPower = 0.0; private set
    var flatness = 0.0; private set
    var tonalProminenceDb = 0.0; private set
    var lfPower = 0.0; private set
    var totalPower = 0.0; private set

    /** Analyses one frame of [NFFT] samples (16 kHz, unweighted, full scale ±1) at [offset] in [frame]. */
    fun analyze(frame: DoubleArray, offset: Int = 0) {
        for (i in 0 until NFFT) { re[i] = frame[offset + i] * HANN[i]; im[i] = 0.0 }
        fft.transform(re, im)
        for (k in 0 until BINS) {
            var p = (re[k] * re[k] + im[k] * im[k]) * WSCALE
            if (k != 0 && k != BINS - 1) p *= 2
            power[k] = p
        }
        for (b in TOB.indices) tob[b] = sum(TOB_RANGES[b])
        var s = 0.0
        var sf = 0.0
        var logSum = 0.0
        for (k in SPEC[0] until SPEC[1]) {
            val p = power[k]
            s += p; sf += p * FREQS[k]
            logSum += ln(maxOf(p, 1e-30))
        }
        val nSpec = SPEC[1] - SPEC[0]
        centroidHz = sf / maxOf(s, 1e-30)
        flatness = exp(logSum / nSpec) / maxOf(s / nSpec, 1e-30)
        hfPower = sum(HF)
        midPower = sum(MID)
        lfPower = sum(LF)
        totalPower = sum(TOT)
        tonalProminenceDb = prominence(power, TONAL[0], 1.0)
    }

    private fun sum(r: IntArray): Double {
        var s = 0.0
        for (k in r[0] until r[1]) s += power[k]
        return s
    }

    /** Frequency of the maximum found by the last [prominence] call. */
    var lastProminenceHz = Double.NaN; private set

    /**
     * Tonal prominence of a spectrum: `spec[from until from + TONAL_BINS]` (the 40–800 Hz bins,
     * multiplied by [scale]) in dB minus its 15-bin running median (edges: nearest), maximum over
     * 80–600 Hz (first maximum). Sets [lastProminenceHz].
     */
    fun prominence(spec: DoubleArray, from: Int, scale: Double): Double {
        for (i in 0 until TONAL_BINS) dB[i] = 10 * log10(maxOf(spec[from + i] * scale, 1e-30))
        val h = MEDIAN_SIZE / 2
        for (i in 0 until TONAL_BINS) {
            for (j in 0 until MEDIAN_SIZE) win[j] = dB[(i - h + j).coerceIn(0, TONAL_BINS - 1)]
            win.sort()
            med[i] = win[h]
        }
        var best = Double.NEGATIVE_INFINITY
        var bestI = TONAL_INNER[0]
        for (i in TONAL_INNER[0] until TONAL_INNER[1]) {
            val r = dB[i] - med[i]
            if (r > best) { best = r; bestI = i }
        }
        lastProminenceHz = FREQS[TONAL[0] + bestI]
        return best
    }
}

/**
 * Per-tick values for the head, kept in a ring numbered like [ch.lessnoise.dsp.EventFeatureTracker]
 * (tick index = push order): the A-weighted mean square of the tick's five 25 ms sub-blocks (for
 * the 50 ms envelope; NaN for invalid ticks) and the [TickSpectrum] summary (NaN until the
 * tick's spectrum is known — it needs 64 ms of the next tick — or if it cannot be computed).
 */
class HeadTickStore(val capacityTicks: Int) {
    companion object {
        const val SUB = 5
    }

    private val sub = DoubleArray(capacityTicks * SUB)
    private val tob = DoubleArray(capacityTicks * TickSpectrum.TOB.size)
    private val cen = DoubleArray(capacityTicks)
    private val hf = DoubleArray(capacityTicks)
    private val mid = DoubleArray(capacityTicks)
    private val flat = DoubleArray(capacityTicks)
    private val prom = DoubleArray(capacityTicks)
    private val lf = DoubleArray(capacityTicks)
    private val tot = DoubleArray(capacityTicks)
    private val hasSpec = BooleanArray(capacityTicks)

    private fun slot(i: Long) = (i % capacityTicks).toInt()

    /** Records tick [i]'s sub-block mean squares (5 values; NaN for an invalid tick); clears its spectrum. */
    fun putSub(i: Long, ms: DoubleArray) {
        val s = slot(i)
        for (k in 0 until SUB) sub[s * SUB + k] = ms[k]
        hasSpec[s] = false
    }

    fun putSubInvalid(i: Long) {
        val s = slot(i)
        for (k in 0 until SUB) sub[s * SUB + k] = Double.NaN
        hasSpec[s] = false
    }

    fun putSpectrum(i: Long, t: TickSpectrum) {
        val s = slot(i)
        val nb = TickSpectrum.TOB.size
        for (b in 0 until nb) tob[s * nb + b] = t.tob[b]
        cen[s] = t.centroidHz; hf[s] = t.hfPower; mid[s] = t.midPower; flat[s] = t.flatness
        prom[s] = t.tonalProminenceDb; lf[s] = t.lfPower; tot[s] = t.totalPower
        hasSpec[s] = true
    }

    fun hasSpectrum(i: Long) = hasSpec[slot(i)]
    fun subAt(i: Long, k: Int) = sub[slot(i) * SUB + k]
    fun tobAt(i: Long, b: Int) = if (hasSpectrum(i)) tob[slot(i) * TickSpectrum.TOB.size + b] else Double.NaN
    fun centroidAt(i: Long) = if (hasSpectrum(i)) cen[slot(i)] else Double.NaN
    fun hfAt(i: Long) = if (hasSpectrum(i)) hf[slot(i)] else Double.NaN
    fun midAt(i: Long) = if (hasSpectrum(i)) mid[slot(i)] else Double.NaN
    fun flatnessAt(i: Long) = if (hasSpectrum(i)) flat[slot(i)] else Double.NaN
    fun prominenceAt(i: Long) = if (hasSpectrum(i)) prom[slot(i)] else Double.NaN
    fun lfAt(i: Long) = if (hasSpectrum(i)) lf[slot(i)] else Double.NaN
    fun totAt(i: Long) = if (hasSpectrum(i)) tot[slot(i)] else Double.NaN
}

/**
 * Sum of the 40–800 Hz power bins over the valid ticks of one event (for the event-mean spectrum
 * of `tonal_prom_db`): fed tick by tick as the spectra become known, so nothing is buffered.
 */
class TonalAccumulator(val startTick: Long) {
    /** Tick index after the event's last tick; −1 while the event is still running. */
    var endTick = -1L
    val sum = DoubleArray(TickSpectrum.TONAL_BINS)
    var ticks = 0
        private set

    /** Adds tick [i]'s spectrum if it belongs to the event and the tick is valid. */
    fun add(i: Long, valid: Boolean, t: TickSpectrum) {
        if (!valid || i < startTick || (endTick >= 0 && i >= endTick)) return
        val from = TickSpectrum.TONAL[0]
        for (k in 0 until TickSpectrum.TONAL_BINS) sum[k] += t.power[from + k]
        ticks++
    }
}
