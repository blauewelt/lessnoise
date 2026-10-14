package ch.lessnoise.dsp.geo

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gaussian location noise for shared measurements (server/DESIGN.md §2 rule 2, §4.3).
 *
 * At «Start» of a shared measurement the app knows the true LV95 point (E, N). It sends instead
 * the point moved by a random 2-D offset: independent normal draws N(0, σ²) in E and in N, σ one
 * of [SIGMAS_M] (default [DEFAULT_SIGMA_M]). The shifted point is rounded to whole metres and kept
 * inside the box the server accepts ([E_MIN]…[E_MAX], [N_MIN]…[N_MAX]).
 *
 * **The offset is deterministic per place, not fresh per measurement.** A fresh offset for every
 * measurement would let anyone average many measurements made at the same home and recover the
 * home (the error of the mean shrinks as σ/√n). So the two normal draws come from
 * `HMAC-SHA256(secret, "<true hectare id>|<σ>")`, where `secret` is a random per-device key that
 * never leaves the phone ([SECRET_BYTES] bytes, [newSecret]): the same hectare and σ always give
 * the same offset, another hectare an independent one. Averaging then converges to the shifted
 * point, never to the true one. A new secret («Neu starten», «Vom Server löschen») gives every place a new offset.
 *
 * The two draws: the first 8 bytes and the next 8 bytes of the HMAC output, each read as an
 * unsigned 64-bit number, keep their top 52 bits, and map to the open interval (0, 1) as
 * (k + 0.5) / 2^52 (exact in a double: the largest value is 1 − 2^−53, the smallest 2^−53);
 * Box–Muller turns the pair (u1, u2) into two independent standard normals
 * z_E = √(−2 ln u1) · cos(2π u2), z_N = √(−2 ln u1) · sin(2π u2). Neither u is ever 0 or 1, so no
 * draw is infinite or NaN.
 *
 * What it means for a reader who knows σ: the true point is within σ of the shifted point in
 * about 39 % of the cases, within 2σ in about 86 %, within 3σ in about 99 % (Rayleigh
 * distribution of the distance). Pure Kotlin/JVM (javax.crypto), no Android; unit-tested in
 * dsp/src/test/.../geo/LocationNoiseTest.kt.
 */
object LocationNoise {

    /** The choices of the setting «Standort verschieben», in metres. */
    val SIGMAS_M: List<Int> = listOf(25, 50, 100, 200)

    /** The default of the setting. */
    const val DEFAULT_SIGMA_M = 50

    /** Length of the per-device noise secret. */
    const val SECRET_BYTES = 32

    /** The LV95 box the server accepts for points (inclusive, whole metres); the cells' box of §3/§4.3. */
    const val E_MIN = 2_480_000
    const val E_MAX = 2_839_999
    const val N_MIN = 1_070_000
    const val N_MAX = 1_299_999

    /**
     * The point that is sent: LV95 [e]/[n] in whole metres, the [sigmaM] it was moved with, and
     * whether it had to be pulled back into the server's box ([clamped]; only near the borders of
     * the box, far outside populated Switzerland).
     */
    data class Point(val e: Int, val n: Int, val sigmaM: Int, val clamped: Boolean = false) {
        /** The hectare that contains the shifted point: where the map shows the measurement. */
        val cell: String get() = Lv95.lv95ToCell(e.toDouble(), n.toDouble())
    }

    fun isSigma(sigmaM: Int): Boolean = sigmaM in SIGMAS_M

    /** True if (e, n) lies in the server's box. */
    fun inBounds(e: Int, n: Int): Boolean = e in E_MIN..E_MAX && n in N_MIN..N_MAX

    /** A fresh per-device secret ([SECRET_BYTES] random bytes). */
    fun newSecret(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SECRET_BYTES).also { random.nextBytes(it) }

    /** HMAC-SHA256(secret, "<trueCell>|<sigmaM>"): 32 bytes, the seed of one place's offset. */
    fun seed(secret: ByteArray, trueCell: String, sigmaM: Int): ByteArray {
        require(secret.size == SECRET_BYTES) { "noise secret must be $SECRET_BYTES bytes" }
        require(isSigma(sigmaM)) { "sigma must be one of $SIGMAS_M" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal("$trueCell|$sigmaM".toByteArray(Charsets.UTF_8))
    }

    /** Bytes [offset] … [offset]+7 as an unsigned 64-bit number → (0, 1), never 0 or 1. */
    internal fun unitOpen(bytes: ByteArray, offset: Int): Double {
        var k = 0L
        for (i in 0 until 8) k = (k shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return ((k ushr 12).toDouble() + 0.5) / TWO_POW_52
    }

    /** Two independent standard normal draws (z_E, z_N) from the first 16 bytes of [seed] (Box–Muller). */
    fun standardNormals(seed: ByteArray): Pair<Double, Double> {
        require(seed.size >= 16) { "seed needs at least 16 bytes" }
        val u1 = unitOpen(seed, 0)
        val u2 = unitOpen(seed, 8)
        val r = sqrt(-2.0 * ln(u1))
        return Pair(r * cos(2.0 * PI * u2), r * sin(2.0 * PI * u2))
    }

    /** The offset (dE, dN) in metres for a seed: σ times the two standard normals. */
    fun offsetFromSeed(seed: ByteArray, sigmaM: Int): Lv95.En {
        val (zE, zN) = standardNormals(seed)
        return Lv95.En(sigmaM * zE, sigmaM * zN)
    }

    /** The offset (dE, dN) in metres of the place whose true hectare is [trueCell]. */
    fun offset(secret: ByteArray, trueCell: String, sigmaM: Int): Lv95.En =
        offsetFromSeed(seed(secret, trueCell, sigmaM), sigmaM)

    /**
     * The point to send for the true LV95 point ([e], [n]): moved by the offset of its hectare,
     * rounded to whole metres, kept inside the server's box. The true point is not part of the result.
     */
    fun shift(secret: ByteArray, e: Double, n: Double, sigmaM: Int): Point {
        require(e.isFinite() && n.isFinite()) { "point must be finite" }
        val d = offset(secret, Lv95.lv95ToCell(e, n), sigmaM)
        val se = (e + d.e).roundToInt()
        val sn = (n + d.n).roundToInt()
        val ce = se.coerceIn(E_MIN, E_MAX)
        val cn = sn.coerceIn(N_MIN, N_MAX)
        return Point(ce, cn, sigmaM, clamped = ce != se || cn != sn)
    }

    private const val TWO_POW_52 = 4503599627370496.0
}
