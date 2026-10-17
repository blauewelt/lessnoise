package ch.lessnoise.app.motion

import kotlin.math.roundToInt

// 0.6.9: the run's «unterwegs» state from two signals, and the numbers of the Labor·Karte readout and
// minute log. Pure (no Android types), src/karte; tested in src/testKarte (MotionDetectorTest,
// UnterwegsTest). In the Online-Version only the motion signal ever reports, so [UnterwegsCombiner]
// gives exactly the 0.6.7 spans; [MinuteSummary] and [Readout] are used by Labor·Karte only.

/**
 * «Unterwegs» = motion OR position (0.6.9). Each signal reports its own changes; the combined state
 * opens one moving span when the first signal says MOVING, and closes it when the last one says
 * STILL. A signal that says MOVING with an earlier span start than the open span's (the position
 * decides minutes after the phone left) moves the start earlier: [Change.Begin] again with that start
 * (Repositories.beginMovingSpan closes the open span at its own start and opens the earlier one, so
 * the stored spans cover the union).
 */
class UnterwegsCombiner {
    sealed interface Change {
        /** Open (or move the start of) the span at [startMs] (wall ms). */
        data class Begin(val startMs: Long) : Change
        /** Close the span at [atMs] (wall ms). */
        data class End(val atMs: Long) : Change
    }

    var motion = false
        private set
    var position = false
        private set
    private var openStartMs: Long? = null

    val moving: Boolean get() = motion || position

    /** The motion detector changed at [atMs]; [spanStartMs] for MOVING (the first active second). */
    fun onMotion(moving: Boolean, atMs: Long, spanStartMs: Long): Change? {
        motion = moving
        return update(moving, atMs, spanStartMs)
    }

    /** The position signal changed at [atMs]; [spanStartMs] for MOVING (the last fix still near the reference). */
    fun onPosition(moving: Boolean, atMs: Long, spanStartMs: Long): Change? {
        position = moving
        return update(moving, atMs, spanStartMs)
    }

    private fun update(signalMoving: Boolean, atMs: Long, spanStartMs: Long): Change? {
        val open = openStartMs
        if (signalMoving) {
            if (open == null || spanStartMs < open) {
                openStartMs = spanStartMs
                return Change.Begin(spanStartMs)
            }
            return null
        }
        if (moving || open == null) return null
        openStartMs = null
        return Change.End(atMs)
    }
}

/**
 * Labor·Karte (0.6.9): the motion value per wall-clock minute — median and maximum of the per-second
 * deviation of |a| ([MotionDetector] seconds; seconds without enough samples are counted in
 * [Minute.seconds] but not in the values). [add] returns the summary of the previous minute when a
 * second of a new minute arrives; [flush] the current one (at «Stopp»).
 */
class MinuteSummary {
    data class Minute(val startMs: Long, val medianMs2: Double?, val maxMs2: Double?, val seconds: Int)

    private var minute = Long.MIN_VALUE
    private val values = ArrayList<Double>(60)
    private var seconds = 0

    fun add(secondStartWallMs: Long, stdMs2: Double): Minute? {
        val m = Math.floorDiv(secondStartWallMs, 60_000L)
        val out = if (minute != Long.MIN_VALUE && m != minute) flush() else null
        minute = m
        seconds++
        if (!stdMs2.isNaN()) values += stdMs2
        return out
    }

    fun flush(): Minute? {
        if (minute == Long.MIN_VALUE || seconds == 0) return null
        val out = Minute(minute * 60_000L, median(values), values.maxOrNull(), seconds)
        values.clear()
        seconds = 0
        return out
    }

    companion object {
        fun median(v: List<Double>): Double? {
            if (v.isEmpty()) return null
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}

/** Labor·Karte (0.6.9): the readout's motion value — the median of the last [n] seconds with enough samples. */
class RecentMotion(private val n: Int = 10) {
    private val last = ArrayDeque<Double>(n)

    fun add(stdMs2: Double) {
        if (stdMs2.isNaN()) return
        if (last.size == n) last.removeFirst()
        last.addLast(stdMs2)
    }

    val medianMs2: Double? get() = MinuteSummary.median(last.toList())
}

/** Distances shown and logged (0.6.9): rounded to 50 m, never a coordinate. */
object Distance {
    const val STEP_M = 50

    fun rounded(m: Double?): Int? = m?.takeUnless { it.isNaN() }?.let { (it / STEP_M).roundToInt() * STEP_M }
}

/** Labor·Karte (0.6.9): the readout line on «Messen», e.g. «Bewegung 0,12 m/s² · 400 m · unterwegs». */
object Readout {
    fun line(motionMs2: Double?, distanceM: Double?, moving: Boolean): String {
        val m = motionMs2?.takeUnless { it.isNaN() }?.let { String.format(java.util.Locale.GERMANY, "%.2f m/s²", it) } ?: "– m/s²"
        val d = Distance.rounded(distanceM)?.let { "$it m" } ?: "–"
        return "Bewegung $m · $d · ${if (moving) "unterwegs" else "ruhig"}"
    }
}
