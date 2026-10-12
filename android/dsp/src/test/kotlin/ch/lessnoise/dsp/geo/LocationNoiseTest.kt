package ch.lessnoise.dsp.geo

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Gaussian location noise of shared measurements (server/DESIGN.md §2 rule 2): LocationNoise. */
class LocationNoiseTest {

    private val secret = ByteArray(32) { it.toByte() } // 00 01 02 … 1f
    private val home = "h26833_12479"

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Pins the algorithm: values computed independently in Python (hmac, hashlib, math), 2026-10-12. */
    @Test
    fun matchesTheReferenceComputation() {
        assertEquals("d2e31d1bd3c90029cb85a6d6762dd4c122c7ed2b6db4d08025fd03a76253637a", hex(LocationNoise.seed(secret, home, 50)))
        val expected = mapOf(
            25 to (16.44594426740428 to -7.463859557680601),
            50 to (8.687387512758354 to -29.89649533037961),
            100 to (0.7513963111730636 to 179.75814757707957),
            200 to (230.3153845361086 to 48.65445883586941),
        )
        for ((sigma, en) in expected) {
            val d = LocationNoise.offset(secret, home, sigma)
            assertEquals(en.first, d.e, 1e-9, "dE σ=$sigma")
            assertEquals(en.second, d.n, 1e-9, "dN σ=$sigma")
        }
        assertEquals(LocationNoise.Point(2683359, 1247920, 50), LocationNoise.shift(secret, 2683350.0, 1247950.0, 50))
    }

    @Test
    fun sameHectareAndSigmaGiveTheSameOffset() {
        val a = LocationNoise.offset(secret, home, 50)
        val b = LocationNoise.offset(secret.copyOf(), home, 50)
        assertEquals(a, b)
        // Two fixes in the same hectare are moved by the same offset: their difference survives,
        // so averaging many of them ends at the shifted point, not at the true one.
        val p1 = LocationNoise.shift(secret, 2683310.0, 1247905.0, 50)
        val p2 = LocationNoise.shift(secret, 2683390.0, 1247990.0, 50)
        assertEquals(80, p2.e - p1.e)
        assertEquals(85, p2.n - p1.n)
        repeat(3) { assertEquals(p1, LocationNoise.shift(secret, 2683310.0, 1247905.0, 50)) }
    }

    @Test
    fun differentPlacesSigmasAndSecretsGiveDifferentOffsets() {
        val neighbours = listOf("h26833_12479", "h26834_12479", "h26833_12480", "h26832_12478", "h24999_11999")
        val offsets = neighbours.map { LocationNoise.offset(secret, it, 50) }
        assertEquals(neighbours.size, offsets.toSet().size, offsets.toString())
        // σ is part of the seed: changing the setting draws a new, independent offset.
        assertNotEquals(LocationNoise.offset(secret, home, 100).e / 2, LocationNoise.offset(secret, home, 50).e)
        // A new secret («Neue Kennung») moves every place differently.
        val other = ByteArray(32) { (it + 1).toByte() }
        assertNotEquals(LocationNoise.offset(secret, home, 50), LocationNoise.offset(other, home, 50))
    }

    @Test
    fun offsetScalesWithSigmaForTheSameSeed() {
        val seed = LocationNoise.seed(secret, home, 50)
        val d25 = LocationNoise.offsetFromSeed(seed, 25)
        for (s in LocationNoise.SIGMAS_M) {
            val d = LocationNoise.offsetFromSeed(seed, s)
            assertEquals(d25.e * s / 25, d.e, 1e-9)
            assertEquals(d25.n * s / 25, d.n, 1e-9)
        }
    }

    @Test
    fun sampleStandardDeviationIsSigma() {
        val rnd = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        val count = 20_000
        for (sigma in LocationNoise.SIGMAS_M) {
            var sumE = 0.0; var sumN = 0.0; var sqE = 0.0; var sqN = 0.0; var prod = 0.0
            var within2 = 0
            repeat(count) {
                val d = LocationNoise.offset(LocationNoise.newSecret(rnd), home, sigma)
                sumE += d.e; sumN += d.n; sqE += d.e * d.e; sqN += d.n * d.n; prod += d.e * d.n
                if (hypot(d.e, d.n) <= 2 * sigma) within2++
            }
            val meanE = sumE / count
            val meanN = sumN / count
            val sdE = sqrt(sqE / count - meanE * meanE)
            val sdN = sqrt(sqN / count - meanN * meanN)
            // Standard error of a standard deviation over 20 000 draws: σ/√40 000 = 0.5 % of σ.
            assertTrue(abs(sdE / sigma - 1) < 0.03, "σ=$sigma: sd(E)=$sdE")
            assertTrue(abs(sdN / sigma - 1) < 0.03, "σ=$sigma: sd(N)=$sdN")
            assertTrue(abs(meanE) < 0.05 * sigma && abs(meanN) < 0.05 * sigma, "σ=$sigma: mean ($meanE, $meanN)")
            assertTrue(abs(prod / count) / (sigma.toDouble() * sigma) < 0.05, "σ=$sigma: E and N correlated")
            // Rayleigh: P(distance ≤ 2σ) = 1 − e^−2 ≈ 0.865 (the figure DESIGN.md §2 quotes).
            assertEquals(0.865, within2.toDouble() / count, 0.015, "σ=$sigma")
        }
    }

    @Test
    fun edgeBytesGiveFiniteDraws() {
        val seeds = listOf(
            ByteArray(32), // all zero: u1 = 0.5 / 2^52, the smallest
            ByteArray(32) { -1 }, // all 0xff: u = (2^52 − 0.5) / 2^52, the largest (< 1 in a double)
            ByteArray(32) { if (it < 8) 0 else -1 },
            ByteArray(32) { if (it < 8) -1 else 0 },
            ByteArray(16) { 0x80.toByte() },
        )
        for (s in seeds) {
            val (a, b) = LocationNoise.standardNormals(s)
            assertTrue(a.isFinite() && b.isFinite(), "${hex(s)} → $a, $b")
            for (sigma in LocationNoise.SIGMAS_M) {
                val d = LocationNoise.offsetFromSeed(s, sigma)
                assertTrue(d.e.isFinite() && d.n.isFinite())
            }
        }
        assertTrue(LocationNoise.unitOpen(ByteArray(8), 0) > 0.0)
        assertTrue(LocationNoise.unitOpen(ByteArray(8) { -1 }, 0) < 1.0)
        // The largest possible radius: √(−2 ln(0.5 / 2^52)) ≈ 8.6, i.e. at most 8.6 σ.
        val (z, _) = LocationNoise.standardNormals(ByteArray(32))
        assertEquals(8.6, z, 0.1)
    }

    @Test
    fun shiftedPointStaysInsideTheServerBox() {
        val rnd = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) }
        var clamped = 0
        repeat(2000) {
            val p = LocationNoise.shift(LocationNoise.newSecret(rnd), LocationNoise.E_MIN.toDouble(), LocationNoise.N_MAX + 0.4, 200)
            assertTrue(LocationNoise.inBounds(p.e, p.n), p.toString())
            assertTrue(Lv95.isInServerRange(p.cell), p.cell)
            if (p.clamped) clamped++
        }
        // At the corner about 3 of 4 draws leave the box in E or N and are pulled back.
        assertTrue(clamped in 1300..1700, "clamped $clamped of 2000")
        // Far inside the box nothing is clamped.
        assertFalse(LocationNoise.shift(secret, 2683350.0, 1247950.0, 200).clamped)
    }

    @Test
    fun displayCellIsTheHectareOfTheShiftedPoint() {
        assertEquals("h26833_12479", LocationNoise.Point(2683300, 1247900, 50).cell)
        assertEquals("h26833_12479", LocationNoise.Point(2683399, 1247999, 50).cell)
        assertEquals("h26834_12480", LocationNoise.Point(2683400, 1248000, 50).cell)
    }

    @Test
    fun refusesBadInput() {
        assertFailsWith<IllegalArgumentException> { LocationNoise.seed(secret, home, 10) }
        assertFailsWith<IllegalArgumentException> { LocationNoise.seed(ByteArray(16), home, 50) }
        assertFailsWith<IllegalArgumentException> { LocationNoise.shift(secret, Double.NaN, 1247950.0, 50) }
        assertFailsWith<IllegalArgumentException> { LocationNoise.standardNormals(ByteArray(8)) }
        assertEquals(listOf(25, 50, 100, 200), LocationNoise.SIGMAS_M)
        assertEquals(50, LocationNoise.DEFAULT_SIGMA_M)
        assertEquals(32, LocationNoise.newSecret().size)
    }
}
