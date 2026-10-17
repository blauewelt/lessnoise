package ch.lessnoise.app.motion

import kotlin.math.sqrt

// «Unterwegs erkannt» (0.6.7): whether the phone is being carried during a measurement started with
// «Messen». Compiled only into the editions with the map (src/karte, flavours public and laborKarte);
// the Offline and Labor builds get the stand-in src/nomap/.../motion/MotionWatch.kt and contain no
// detector and no sensor listener. Pure (no Android types), so that MotionDetectorTest (src/testKarte)
// drives it with synthetic accelerometer signals on the JVM.

/**
 * STILL / MOVING from the accelerometer, with hysteresis.
 *
 * Feature: per whole second, the standard deviation of the acceleration magnitude |a| = √(x²+y²+z²)
 * (m/s²) over the samples of that second. The constant 1 g of gravity drops out of a standard
 * deviation, and the magnitude does not depend on how the phone lies, so this is the gravity-removed
 * variability of the motion. A second is **active** if it has at least [MIN_SAMPLES_PER_SECOND]
 * samples and that deviation is at least [ACTIVE_STD_MS2]; a second without enough samples counts as
 * not active.
 *
 * - STILL → MOVING when at least [ENTER_ACTIVE_S] of the last [ENTER_WINDOW_S] seconds were active
 *   (sustained walking or cycling; a bump, picking the phone up or a window being shut is one to a
 *   few seconds). The span then starts at the first active second of that window
 *   ([Transition.spanStartMs]), i.e. at most [ENTER_WINDOW_S] before the decision.
 * - MOVING → STILL when the last [EXIT_WINDOW_S] seconds hold at most [EXIT_ACTIVE_MAX_S] active
 *   seconds (about 3 minutes without walking; a red light or a stop at a tram stop does not end it).
 *
 * Times are a monotonic clock in ms (the caller uses SystemClock.elapsedRealtime's base). Samples
 * must come in time order; older ones are dropped. [advanceTo] moves time on without samples (sensor
 * silent), so that a dead sensor cannot keep the state MOVING for ever.
 */
class MotionDetector(
    /** The levels (0.6.9): the Online-Version uses [Thresholds.ONLINE] (as 0.6.7), Labor·Karte [Thresholds.LABOR]. */
    val thresholds: Thresholds = Thresholds.ONLINE,
    /**
     * 0.6.9 (Labor·Karte readout and minute log): called with the start (monotonic ms) and the deviation
     * of every closed second (NaN: too few samples). Null: nothing is reported (Online-Version).
     */
    private val onSecond: ((startMs: Long, stdMs2: Double) -> Unit)? = null,
) {
    enum class State { STILL, MOVING }

    /**
     * Level hysteresis (0.6.9): a second counts as active for entering MOVING from [enterStdMs2], and
     * for staying MOVING (the exit rule) from [stayStdMs2] ≤ [enterStdMs2]. Both equal: no level
     * hysteresis (the Online-Version, 0.6.7).
     */
    data class Thresholds(val enterStdMs2: Double, val stayStdMs2: Double) {
        init { require(stayStdMs2 <= enterStdMs2) }

        companion object {
            val ONLINE = Thresholds(ACTIVE_STD_MS2, ACTIVE_STD_MS2)
            val LABOR = Thresholds(LABOR_ENTER_STD_MS2, LABOR_STAY_STD_MS2)
        }
    }

    /**
     * A change of state at [atMs] (end of the second that decided it) to [to]. [spanStartMs]: for
     * MOVING the start of the first active second of the enter window; for STILL = [atMs]. The
     * feature values that caused it (for the Labor·Karte log): active seconds in the enter and exit
     * windows, the mean per-second deviation of the known seconds in the enter window, that of the
     * last second, and the samples of the last second.
     */
    data class Transition(
        val atMs: Long,
        val to: State,
        val spanStartMs: Long,
        val activeInEnterWindow: Int,
        val activeInExitWindow: Int,
        val meanStdEnterMs2: Double,
        val lastStdMs2: Double,
        val lastSamples: Int,
        val thresholds: Thresholds = Thresholds.ONLINE,
    ) {
        /** The numbers of the Labor·Karte log line (EditionDiagnostics.motionTransition). */
        fun features(): Map<String, Double> = linkedMapOf(
            "spanStartAgoS" to (atMs - spanStartMs) / 1000.0,
            "activeEnter" to activeInEnterWindow.toDouble(),
            "enterWindowS" to ENTER_WINDOW_S.toDouble(),
            "activeExit" to activeInExitWindow.toDouble(),
            "exitWindowS" to EXIT_WINDOW_S.toDouble(),
            "meanStdEnter" to meanStdEnterMs2,
            "lastStd" to lastStdMs2,
            "lastSamples" to lastSamples.toDouble(),
            "activeStd" to thresholds.enterStdMs2,
            "stayStd" to thresholds.stayStdMs2,
        )
    }

    var state: State = State.STILL
        private set

    // The second being filled: its index (ms / 1000) and running sums of (|a| − g).
    private var second = UNSET
    private var n = 0
    private var sum = 0.0
    private var sumSq = 0.0

    // The last EXIT_WINDOW_S closed seconds (ring): deviation (NaN: too few samples), start. Whether a
    // second is active depends on the threshold in use (enter or stay, 0.6.9), so it is not stored.
    private val std = DoubleArray(EXIT_WINDOW_S) { Double.NaN }
    private val startMs = LongArray(EXIT_WINDOW_S)
    private var closed = 0L
    private var secondsInState = 0L
    private var lastStd = Double.NaN
    private var lastN = 0

    /** One accelerometer sample at [tMs] (monotonic ms), axes in m/s². Returns the changes it caused (usually none). */
    fun onSample(tMs: Long, x: Float, y: Float, z: Float): List<Transition> {
        val sec = Math.floorDiv(tMs, 1000L)
        if (second == UNSET) second = sec
        if (sec < second) return emptyList() // out of order: dropped
        val out = if (sec > second) moveTo(sec) else emptyList()
        val m = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z) - G
        n++
        sum += m
        sumSq += m * m
        return out
    }

    /** No samples until [tMs]: closes every second before it (seconds without samples are not active). */
    fun advanceTo(tMs: Long): List<Transition> {
        val sec = Math.floorDiv(tMs, 1000L)
        if (second == UNSET || sec <= second) return emptyList()
        return moveTo(sec)
    }

    private fun moveTo(sec: Long): List<Transition> {
        var out: List<Transition> = emptyList()
        fun add(t: Transition?) { if (t != null) out = out + t }
        val s = if (n >= MIN_SAMPLES_PER_SECOND) sqrt(maxOf(0.0, sumSq / n - (sum / n) * (sum / n))) else Double.NaN
        add(close(second, s, n))
        // Seconds without any sample; more than a full exit window of them changes nothing further.
        val gap = minOf(sec - second - 1, EXIT_WINDOW_S.toLong() + 1)
        for (i in gap downTo 1) add(close(sec - i, Double.NaN, 0))
        second = sec
        n = 0; sum = 0.0; sumSq = 0.0
        return out
    }

    private fun close(sec: Long, s: Double, samples: Int): Transition? {
        val i = (closed % EXIT_WINDOW_S).toInt()
        std[i] = s
        startMs[i] = sec * 1000L
        closed++
        secondsInState++
        lastStd = s
        lastN = samples
        onSecond?.invoke(sec * 1000L, s)
        val atMs = (sec + 1) * 1000L
        return when (state) {
            State.STILL -> {
                val enter = countActive(ENTER_WINDOW_S, thresholds.enterStdMs2)
                if (enter < ENTER_ACTIVE_S) null else {
                    state = State.MOVING
                    secondsInState = 0
                    transition(atMs, State.MOVING, firstActiveStart(ENTER_WINDOW_S, thresholds.enterStdMs2), enter)
                }
            }
            State.MOVING -> {
                val exit = countActive(EXIT_WINDOW_S, thresholds.stayStdMs2)
                if (secondsInState < EXIT_WINDOW_S || exit > EXIT_ACTIVE_MAX_S) null else {
                    state = State.STILL
                    secondsInState = 0
                    transition(atMs, State.STILL, atMs, countActive(ENTER_WINDOW_S, thresholds.enterStdMs2))
                }
            }
        }
    }

    private fun transition(atMs: Long, to: State, spanStartMs: Long, enter: Int) = Transition(
        atMs = atMs, to = to, spanStartMs = spanStartMs,
        activeInEnterWindow = enter, activeInExitWindow = countActive(EXIT_WINDOW_S, thresholds.stayStdMs2),
        meanStdEnterMs2 = meanStd(ENTER_WINDOW_S), lastStdMs2 = lastStd, lastSamples = lastN, thresholds = thresholds,
    )

    private fun active(i: Int, threshold: Double): Boolean = !std[i].isNaN() && std[i] >= threshold

    /** Index in the ring of the k-th newest closed second (0 = newest), or -1 if not closed yet. */
    private fun at(k: Int): Int = if (k >= closed || k >= EXIT_WINDOW_S) -1 else ((closed - 1 - k) % EXIT_WINDOW_S).toInt()

    private fun countActive(window: Int, threshold: Double): Int {
        var c = 0
        for (k in 0 until window) { val i = at(k); if (i < 0) break; if (active(i, threshold)) c++ }
        return c
    }

    private fun firstActiveStart(window: Int, threshold: Double): Long {
        var first = Long.MAX_VALUE
        for (k in 0 until window) { val i = at(k); if (i < 0) break; if (active(i, threshold)) first = startMs[i] }
        return first
    }

    private fun meanStd(window: Int): Double {
        var s = 0.0
        var c = 0
        for (k in 0 until window) { val i = at(k); if (i < 0) break; if (!std[i].isNaN()) { s += std[i]; c++ } }
        return if (c == 0) Double.NaN else s / c
    }

    companion object {
        private const val UNSET = Long.MIN_VALUE
        /** Standard gravity (m/s²); subtracted only to keep the sums small (it drops out of the deviation). */
        const val G = 9.80665

        /**
         * A second is active from this deviation of |a| (m/s²). Physics: a phone lying still reads
         * sensor noise of about 0.005–0.03 m/s², and a windowsill shaken by a passing lorry or tram a
         * few hundredths; a phone held in the hand while reading stays below about 0.1 (tremor).
         * Walking moves the body up and down at 1–2 steps per second with peaks of 0.2–0.4 g, i.e. a
         * deviation of |a| of about 1–3 m/s² in a pocket or bag and about 0.5–1.5 in the hand;
         * cycling adds the pedalling (about 1–1.5 Hz) and broadband road vibration (0.5–2 m/s²); a
         * bus or tram sways and vibrates at about 0.1–0.5. 0.25 lies about ten times above the still
         * phone and several times below walking and cycling (buses and trams are recognised partly).
         */
        const val ACTIVE_STD_MS2 = 0.25

        /**
         * Labor·Karte (0.6.9), enter level: a tram, train or bus ride. The owner's tram ride on 0.6.8
         * never reached 0.25 for 20 of 25 s: a vehicle's broadband vibration at the phone is about
         * 0.1 m/s² (smoother than walking). A phone lying still reads 0.01–0.03 (per-axis MEMS noise;
         * the deviation of |a| is about that of the axis along gravity); in the synthetic model even a
         * noisy σ = 0.04 sensor never reaches 0.07 (0 of 288 000 s), i.e. 0.07 is 2.3× the noisiest
         * still phone and ~0.7× the tram, which makes about 97 % of its seconds active.
         */
        const val LABOR_ENTER_STD_MS2 = 0.07

        /**
         * Labor·Karte (0.6.9), stay level (level hysteresis): while MOVING, a second counts as active
         * from 0.05, so standing at a stop or sitting in a slow, smooth vehicle keeps MOVING. Above the
         * 99.9th percentile of a σ = 0.03 still phone (0.044); a σ = 0.04 phone exceeds it in 3 % of
         * seconds, below the exit rule's 10 of 180 (5.6 %), so a phone put down still returns to STILL.
         */
        const val LABOR_STAY_STD_MS2 = 0.05

        /** Fewer samples in a second (sensor batching hiccup, sensor off): the second counts as not active. */
        const val MIN_SAMPLES_PER_SECOND = 5

        /** The enter window (s). With [ENTER_ACTIVE_S]: 20 s of sustained motion within 25 s, as planned (20–30 s). */
        const val ENTER_WINDOW_S = 25

        /** Active seconds needed in the enter window: 4 of 5, so a short pause while walking does not reset it. */
        const val ENTER_ACTIVE_S = 20

        /** The exit window (s): about 3 minutes, longer than a red light or a wait at a stop. */
        const val EXIT_WINDOW_S = 180

        /** At most this many active seconds in the exit window: a few bumps while lying still do not keep MOVING. */
        const val EXIT_ACTIVE_MAX_S = 10
    }
}

/**
 * The detector's clock (pure): the time of a sensor event in SystemClock.elapsedRealtime ms. Android
 * documents SensorEvent.timestamp in nanoseconds; on current phones it has the elapsedRealtimeNanos
 * base. If a phone's stamps are far from that base (older HALs used another clock), the arrival time
 * is used instead (with sensor batching up to [MotionWatch.MAX_REPORT_LATENCY_US] late).
 */
object MotionClock {
    /** Stamps more than this before now (or later than 1 s after) are taken to be of another clock. */
    const val MAX_AGE_NS = 10_000_000_000L

    fun sampleMs(eventNs: Long, nowElapsedNs: Long): Long =
        if (eventNs in (nowElapsedNs - MAX_AGE_NS)..(nowElapsedNs + 1_000_000_000L)) eventNs / 1_000_000L else nowElapsedNs / 1_000_000L

    /** [elapsedMs] (elapsedRealtime base) as wall-clock ms, given the two clocks now. */
    fun wallMs(elapsedMs: Long, nowElapsedMs: Long, nowWallMs: Long): Long = nowWallMs - (nowElapsedMs - elapsedMs)
}
