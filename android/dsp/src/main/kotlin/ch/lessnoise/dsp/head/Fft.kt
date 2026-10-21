package ch.lessnoise.dsp.head

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT of a fixed power-of-two size [n] (forward, no scaling,
 * the same sign convention as numpy: X[k] = Σ x[t]·e^(−2πi·k·t/n)). Twiddles and the bit-reversal
 * permutation are precomputed; [transform] allocates nothing.
 */
class Fft(val n: Int) {
    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        var bits = 0
        while (1 shl bits < n) bits++
        for (i in 0 until n) {
            var x = i
            var y = 0
            for (b in 0 until bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[i] = y
        }
    }

    /** Transforms (re, im) of length [n] in place. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }

    /**
     * |X[k]|² for k = 0…n/2 of the real input x[0 until count] zero-padded to [n] (numpy
     * `abs(rfft(x, n))**2`). [re]/[im] are scratch arrays of length [n]; the result goes to
     * [out] (length ≥ n/2 + 1).
     */
    fun powerSpectrum(x: DoubleArray, count: Int, re: DoubleArray, im: DoubleArray, out: DoubleArray) {
        for (i in 0 until n) { re[i] = if (i < count) x[i] else 0.0; im[i] = 0.0 }
        transform(re, im)
        for (k in 0..n / 2) out[k] = re[k] * re[k] + im[k] * im[k]
    }
}
