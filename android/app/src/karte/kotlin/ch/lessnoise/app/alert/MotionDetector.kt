package ch.lessnoise.app.alert

import kotlin.math.sqrt

/**
 * Whether the phone is being carried (0.6.3, the motion gate of the «Sehr laut» notification):
 * pure, no Android imports, so MotionDetectorTest checks it on the JVM.
 *
 * Consumes accelerometer samples (timestamp in ns, x/y/z in m/s², gravity included). The phone
 * counts as moving at a sample when the standard deviation of the acceleration magnitude
 * |a| = √(x²+y²+z²) over the trailing [WINDOW_NS] exceeds [MOVING_STD_MS2]. The magnitude does not
 * depend on how the phone is turned, so a phone lying still gives only sensor noise (< 0.05 m/s²),
 * walking and cycling several m/s². Only [lastMotionAtMs] (in the samples' time base) is kept
 * beyond the window; nothing is stored or sent.
 */
class MotionDetector {
    private val tNs = LongArray(MAX_SAMPLES)
    private val mag = DoubleArray(MAX_SAMPLES)
    private var head = 0 // index of the oldest sample in the ring
    private var size = 0

    /** When the phone last moved (the samples' time base, ms), or null. */
    @Volatile
    var lastMotionAtMs: Long? = null
        private set

    /** Adds one sample; returns whether the phone counts as moving at it. */
    @Synchronized
    fun add(timestampNs: Long, x: Float, y: Float, z: Float): Boolean {
        val m = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)
        // A sample older than the newest one (clock jump, batch out of order): start over.
        if (size > 0 && timestampNs < tNs[(head + size - 1) % MAX_SAMPLES]) clearWindow()
        if (size == MAX_SAMPLES) { head = (head + 1) % MAX_SAMPLES; size-- }
        val i = (head + size) % MAX_SAMPLES
        tNs[i] = timestampNs; mag[i] = m; size++
        while (size > 0 && timestampNs - tNs[head] > WINDOW_NS) { head = (head + 1) % MAX_SAMPLES; size-- }
        // Judge only a (nearly) full window: a few samples right after the start say nothing.
        if (size < MIN_SAMPLES || timestampNs - tNs[head] < MIN_SPAN_NS) return false
        var sum = 0.0
        for (k in 0 until size) sum += mag[(head + k) % MAX_SAMPLES]
        val mean = sum / size
        var sq = 0.0
        for (k in 0 until size) { val d = mag[(head + k) % MAX_SAMPLES] - mean; sq += d * d }
        val std = sqrt(sq / size)
        val moving = std > MOVING_STD_MS2
        if (moving) lastMotionAtMs = timestampNs / 1_000_000L
        return moving
    }

    /** Moved within the last [MOTION_WINDOW_MS] before [nowMs] (same time base as the samples). */
    fun isMoving(nowMs: Long): Boolean {
        val last = lastMotionAtMs ?: return false
        return nowMs - last in 0..MOTION_WINDOW_MS
    }

    /** Forgets everything (the sensor is switched off). */
    @Synchronized
    fun reset() {
        clearWindow()
        lastMotionAtMs = null
    }

    private fun clearWindow() { head = 0; size = 0 }

    companion object {
        /**
         * Standard deviation of |a| above which the phone moves. A STARTING POINT, not measured on a
         * device: still < 0.05, walking/cycling several m/s²; a phone in a car on a smooth road is
         * the hard case (vibration ~0.2–1). One constant, to tune after a field test.
         */
        const val MOVING_STD_MS2 = 0.5

        /** The window the standard deviation is taken over. */
        const val WINDOW_NS = 2_000_000_000L

        /** «Moved recently»: the last motion at most this long ago. */
        const val MOTION_WINDOW_MS = 5 * 60_000L

        /** A window is judged once it spans this much (75 % of [WINDOW_NS]) … */
        private const val MIN_SPAN_NS = 1_500_000_000L

        /** … and holds at least this many samples (SENSOR_DELAY_NORMAL gives ~5 per second). */
        private const val MIN_SAMPLES = 5

        /** Bound on memory if a device delivers far faster than asked (≥ 2 s at 500 Hz). */
        private const val MAX_SAMPLES = 1024
    }
}
