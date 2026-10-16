package ch.lessnoise.app.alert

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.3: the motion gate of the «Sehr laut» notification, on synthetic accelerometer samples. */
class MotionDetectorTest {
    private val g = 9.81
    private val t0Ns = 3_600_000_000_000L // an hour after boot (elapsedRealtimeNanos)

    /** Feeds [seconds] of samples at [hz] from [startNs]; z = g + [accel](t) (+ noise on all axes). Returns the first time (s) it counted as moving, or null. */
    private fun feed(
        d: MotionDetector, hz: Int, seconds: Double, startNs: Long = t0Ns, noise: Double = 0.0, seed: Int = 1,
        accel: (Double) -> Double = { 0.0 },
    ): Double? {
        val rnd = Random(seed)
        val n = (seconds * hz).toInt()
        var first: Double? = null
        for (i in 0 until n) {
            val t = i.toDouble() / hz
            fun nz() = if (noise == 0.0) 0.0 else rnd.nextGaussian() * noise
            val moving = d.add(startNs + (t * 1e9).toLong(), nz().toFloat(), nz().toFloat(), (g + accel(t) + nz()).toFloat())
            if (moving && first == null) first = t
        }
        return first
    }

    private fun Random.nextGaussian(): Double {
        // Box–Muller
        val u = nextDouble().coerceAtLeast(1e-12)
        val v = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * v)
    }

    private val walking: (Double) -> Double = { t -> 1.5 * sin(2 * PI * 2.0 * t) }

    @Test
    fun aStillPhoneNeverMoves() {
        for (hz in listOf(5, 10, 50)) {
            val d = MotionDetector()
            assertNull(feed(d, hz, 600.0, noise = 0.02), "$hz Hz")
            assertNull(d.lastMotionAtMs)
            assertFalse(d.isMoving(t0Ns / 1_000_000 + 600_000))
        }
    }

    @Test
    fun walkingCountsAsMovingWithinTwoSeconds() {
        for (hz in listOf(5, 10, 50)) {
            val d = MotionDetector()
            val first = feed(d, hz, 10.0, noise = 0.02, accel = walking)
            assertNotNull(first, "$hz Hz")
            assertTrue(first <= 2.0, "$hz Hz: first at $first s")
            assertTrue(d.isMoving(t0Ns / 1_000_000 + 10_000))
        }
    }

    @Test
    fun movingThenStillEndsAfterFiveMinutes() {
        val d = MotionDetector()
        feed(d, 10, 30.0, noise = 0.02, accel = walking)
        assertTrue(d.lastMotionAtMs!! >= t0Ns / 1_000_000 + 29_000, "moving until the walk ended")
        // Put down on the windowsill: the still samples keep coming; the walk leaves the 2-s window
        // within 2 s, after that the last motion stays where it was.
        feed(d, 10, 400.0, startNs = t0Ns + 30_000_000_000L, noise = 0.02)
        val lastWalkMs = d.lastMotionAtMs!!
        assertTrue(lastWalkMs <= t0Ns / 1_000_000 + 32_000, "last motion $lastWalkMs")
        assertTrue(d.isMoving(lastWalkMs + MotionDetector.MOTION_WINDOW_MS))
        assertFalse(d.isMoving(lastWalkMs + MotionDetector.MOTION_WINDOW_MS + 1))
        assertEquals(5 * 60_000L, MotionDetector.MOTION_WINDOW_MS)
    }

    @Test
    fun sampleRateDoesNotMatter() {
        // The same signals give the same verdict at 10 Hz and 50 Hz (and at SENSOR_DELAY_NORMAL's ~5 Hz).
        for (hz in listOf(5, 10, 50)) {
            assertNull(feed(MotionDetector(), hz, 60.0, noise = 0.02), "still at $hz Hz")
            assertNotNull(feed(MotionDetector(), hz, 60.0, noise = 0.02, accel = walking), "walking at $hz Hz")
            // A gentle 0.3 m/s² sway (below the threshold) is not motion at any rate …
            assertNull(feed(MotionDetector(), hz, 60.0, accel = { t -> 0.3 * sin(2 * PI * 1.0 * t) }), "sway at $hz Hz")
            // … a 1 m/s² one is.
            assertNotNull(feed(MotionDetector(), hz, 60.0, accel = { t -> 1.0 * sin(2 * PI * 1.0 * t) }), "1 m/s² at $hz Hz")
        }
    }

    @Test
    fun orientationDoesNotMatterOnlyTheMagnitude() {
        // Turning a still phone over (gravity from z to x) is one step, not continuous motion.
        val d = MotionDetector()
        for (i in 0 until 100) d.add(t0Ns + i * 100_000_000L, 0f, 0f, g.toFloat())
        for (i in 100 until 200) d.add(t0Ns + i * 100_000_000L, g.toFloat(), 0f, 0f)
        assertNull(d.lastMotionAtMs)
    }

    @Test
    fun resetForgetsAndOutOfOrderSamplesRestartTheWindow() {
        val d = MotionDetector()
        feed(d, 10, 5.0, accel = walking)
        assertNotNull(d.lastMotionAtMs)
        d.reset()
        assertNull(d.lastMotionAtMs)
        assertFalse(d.isMoving(t0Ns / 1_000_000 + 5_000))
        // A sample earlier than the last (another time base): the window starts over, no crash.
        feed(d, 10, 1.0, startNs = t0Ns + 10_000_000_000L)
        assertFalse(d.add(t0Ns, 0f, 0f, g.toFloat()))
        // Fewer than 1.5 s of samples are not judged.
        val e = MotionDetector()
        assertNull(feed(e, 10, 1.4, accel = walking))
    }

    @Test
    fun thresholdIsOneConstant() {
        assertEquals(0.5, MotionDetector.MOVING_STD_MS2)
        assertEquals(2_000_000_000L, MotionDetector.WINDOW_NS)
    }

    @Test
    fun theMonitorFallsBackToArrivalTimeForAForeignTimeBase() {
        val now = 7_200_000_000_000L
        assertEquals(now - 3_000_000_000L, MotionMonitor.timestampNs(now - 3_000_000_000L, now)) // batched, 3 s old
        assertEquals(now, MotionMonitor.timestampNs(1_760_000_000_000_000_000L, now)) // wall clock in ns
        assertEquals(5_000_000, MotionMonitor.MAX_REPORT_LATENCY_US)
    }
}
