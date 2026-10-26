package ch.lessnoise.dsp

import kotlin.math.log10
import kotlin.math.pow

/**
 * Trailing background estimate: L90 of the LAF samples over the last [windowSeconds].
 * Returns NaN until at least [minHistorySeconds] of samples are available.
 */
class BackgroundEstimator(
    val windowSeconds: Double = 300.0,
    val minHistorySeconds: Double = 30.0,
    val tickSeconds: Double = 0.125,
) {
    private val ring = DoubleRing((windowSeconds / tickSeconds).toInt())
    private val minSamples = (minHistorySeconds / tickSeconds).toInt()

    fun add(lafDb: Double) = ring.add(lafDb)

    fun l90(): Double {
        if (ring.size < minSamples) return Double.NaN
        return Percentiles.exceedanceLevel(ring.toArray(), 90.0)
    }

    fun clear() = ring.clear()
}

/** An event as delimited by the detector (timing in input samples, levels in dB). */
data class DetectedEvent(
    val startSample: Long,
    val endSample: Long,
    val durationSeconds: Double,
    val lafMaxDb: Double,
    /** Sound exposure level LAE (re 1 s). */
    val selDb: Double,
    /** 5-min background (L90) at the start, for continuity with v0.3 (not used for detection). */
    val backgroundDb: Double,
    /** The excess setting in force (dB above the local floor; before v0.4.0: above the background). */
    val thresholdDb: Double,
    /** Absolute floor the event's LAFmax had to reach (see [EventDetector.minLevelDb]). */
    val minLevelDb: Double = Double.NEGATIVE_INFINITY,
    /** Local floor (L90 of LAF over the trailing local window) at the start, frozen for the event. */
    val localFloorDb: Double = Double.NaN,
    /** Event head probabilities (class → p), set by the engine once known; null without a head. Logging only. */
    val headScores: Map<String, Double>? = null,
    /** Event head decision (enabled classes over their threshold); null without a head. Logging only. */
    val headDecision: List<String>? = null,
)

/**
 * The detector's start and end rules beyond the excess threshold (0.9.5). [V2] is detector v2 as it
 * ran from 0.4.0 to 0.9.4 and stays the default of every edition except Labor; [V3] are the rules
 * chosen by replaying two recorded nights (android/dsp/REPLAY_2026-10.md).
 */
data class EventRules(
    /**
     * Start also needs a rise: LAF ≥ (lowest LAF of the previous [riseWindowSeconds]) + this (dB).
     * A slow swell that creeps over the excess threshold is not an event; a pass-by is. 0 = off.
     */
    val riseDb: Double = 0.0,
    val riseWindowSeconds: Double = 3.0,
    /**
     * End when LAF < frozen floor + this (dB), but never above the v2 end level (so with an excess
     * below margin + hysteresis the v2 end applies). NaN = the v2 end: floor + excess − hysteresis
     * (with 6.5 dB that is floor + 3.5 dB).
     */
    val endMarginDb: Double = Double.NaN,
    /** An event is closed after this long (s) even if the level stays up. */
    val maxDurationSeconds: Double = 300.0,
    /**
     * After an event was closed by [maxDurationSeconds], no new event starts until LAF has fallen below
     * the current local floor + excess − hysteresis once (the floor has caught up with a long swell or
     * the swell is over), so that one long swell is not cut into a series of events.
     */
    val holdAfterMax: Boolean = false,
) {
    init {
        require(riseDb >= 0 && riseWindowSeconds > 0 && maxDurationSeconds > 0)
        require(endMarginDb.isNaN() || endMarginDb >= 0)
    }

    /** "v2", "v3" or "custom" (for the Labor manifest and the replay tools). */
    val id: String get() = when (this) { V2 -> "v2"; V3 -> "v3"; else -> "custom" }

    companion object {
        /** Detector v2 (0.4.0–0.9.4): no rise condition, end at floor + excess − 3 dB, 300 s cap. */
        val V2 = EventRules()
        /**
         * 0.9.5 (Labor editions): rise ≥ 3.5 dB within 3 s, end within 3 dB of the frozen floor or after
         * 30 s, then hold. Replayed on two nights (REPLAY_2026-10.md): all 35 labelled vehicle / loud
         * vehicle events still detected, no event longer than 30 s, 7–8 % fewer events per night.
         */
        val V3 = EventRules(riseDb = 3.5, riseWindowSeconds = 3.0, endMarginDb = 3.0, maxDurationSeconds = 30.0, holdAfterMax = true)
    }
}

/**
 * Single-event detector working on 125 ms LAF ticks (detector v2, engine 0.4.0; [EventRules] 0.9.5).
 *
 * - Starts when LAF ≥ local floor + [excessDb] (the local floor is frozen at the start) and, with
 *   [EventRules.riseDb] > 0, LAF ≥ the lowest LAF of the previous [EventRules.riseWindowSeconds] + riseDb.
 * - Continues while LAF ≥ floor + excess − [hysteresisDb] (v2); with [EventRules.endMarginDb] while
 *   LAF ≥ floor + min(endMargin, excess − hysteresis).
 * - Kept only if it lasted ≥ [minDurationSeconds] **and** its LAFmax reached [minLevelDb] (an
 *   absolute floor, so that keystrokes 10 dB above a 20 dB background are not events);
 *   force-closed after [maxDurationSeconds] (the smaller of the constructor's and the rules' value;
 *   with [EventRules.holdAfterMax] no new start until the level has dropped, see there). A candidate
 *   that never reaches the floor is discarded like a too-short one. Both conditions only ever become
 *   true during an event (the duration and the running maximum grow), so confirming as soon as both
 *   hold keeps exactly the events that satisfy them at the end, while the engine can still count
 *   events at confirmation.
 * - SEL is integrated from the un-time-weighted per-tick LAeq: LAE = 10·log10(Σ 10^(Leq_i/10)·Δt / 1 s).
 */
class EventDetector(
    /** Start threshold above the local floor (dB). Read at event start. */
    var excessDb: Double = 6.5,
    val hysteresisDb: Double = 3.0,
    val minDurationSeconds: Double = 0.5,
    maxDurationSeconds: Double = 300.0,
    /** Absolute floor for LAFmax in dB; −∞ disables it. Read at event start. */
    var minLevelDb: Double = Double.NEGATIVE_INFINITY,
    val tickSamples: Int = Acoustics.SAMPLE_RATE / 8,
    val sampleRate: Int = Acoustics.SAMPLE_RATE,
    val rules: EventRules = EventRules.V2,
    private val listener: Listener,
) {
    /** Events are closed after this long (s): the smaller of the constructor's value and [EventRules.maxDurationSeconds]. */
    val maxDurationSeconds: Double = minOf(maxDurationSeconds, rules.maxDurationSeconds)

    interface Listener {
        /** LAF crossed the start threshold (event not yet confirmed). */
        fun onCandidateStart(startSample: Long) {}
        /** Candidate reached the minimum duration and the level floor: it will be reported as an event. */
        fun onConfirmed(startSample: Long) {}
        /** Candidate ended before reaching the minimum duration or the level floor. */
        fun onDiscarded(startSample: Long) {}
        fun onClosed(event: DetectedEvent)
    }

    private val tickSeconds = tickSamples.toDouble() / sampleRate
    private val minTicks = kotlin.math.ceil(minDurationSeconds / tickSeconds - 1e-9).toInt()
    private val maxTicks = (this.maxDurationSeconds / tickSeconds).toInt()

    /**
     * LAF of the previous ticks for the rise condition (ring, valid ticks only; emptied by invalid audio).
     * Until it is full its values are riseRing[0 until riseCount] (a reset also resets riseNext).
     */
    private val riseTicks = if (rules.riseDb > 0) Math.round(rules.riseWindowSeconds / tickSeconds).toInt().coerceAtLeast(1) else 0
    private val riseRing = DoubleArray(riseTicks)
    private var riseCount = 0
    private var riseNext = 0

    /** Set when an event was closed by the maximum duration ([EventRules.holdAfterMax]). */
    private var holding = false

    /** Current local floor (dB); NaN disables detection. */
    var floorDb: Double = Double.NaN

    /** Current 5-min background (dB), recorded with the event for continuity; NaN → the floor is recorded. */
    var backgroundDb: Double = Double.NaN

    private var active = false
    private var startSample = 0L
    private var floorAtStart = 0.0
    private var bgAtStart = 0.0
    private var excessAtStart = 0.0
    private var minLevelAtStart = Double.NEGATIVE_INFINITY
    private var ticks = 0
    private var lafMax = Double.NEGATIVE_INFINITY
    private var energy = 0.0
    private var confirmed = false

    val isActive: Boolean get() = active
    val activeStartSample: Long? get() = if (active) startSample else null
    /** A candidate that has not (yet) reached the minimum duration. */
    val isUnconfirmedCandidate: Boolean get() = active && !confirmed
    /** The local floor frozen for the running candidate (NaN if none). */
    val activeFloorDb: Double get() = if (active) floorAtStart else Double.NaN

    /**
     * Ends a running event at [endSample] because the following audio is invalid (e.g. the
     * microphone was silenced). Confirmed events are reported, unconfirmed candidates discarded.
     */
    fun interrupt(endSample: Long) {
        if (active) close(endSample)
        // The rise is measured over contiguous valid audio only; a hold ends with the gap.
        riseCount = 0
        riseNext = 0
        holding = false
    }

    /** Lowest LAF of the previous [riseTicks] valid ticks (NaN without history). */
    private fun riseBase(): Double {
        if (riseCount == 0) return Double.NaN
        var m = Double.POSITIVE_INFINITY
        for (i in 0 until riseCount) if (riseRing[i] < m) m = riseRing[i]
        return m
    }

    private fun pushRise(lafDb: Double) {
        if (riseTicks == 0) return
        riseRing[riseNext] = lafDb
        riseNext = (riseNext + 1) % riseTicks
        if (riseCount < riseTicks) riseCount++
    }

    /** True if the rise condition holds for [lafDb] (always without a rise rule). */
    private fun risen(lafDb: Double): Boolean {
        if (riseTicks == 0) return true
        val b = riseBase()
        return !b.isNaN() && lafDb >= b + rules.riseDb
    }

    /** The level below which the running event ends (frozen floor, excess at start). */
    private fun endLevel(): Double {
        val v2 = floorAtStart + excessAtStart - hysteresisDb
        return if (rules.endMarginDb.isNaN()) v2 else minOf(v2, floorAtStart + rules.endMarginDb)
    }

    /**
     * @param tickEndSample sample index at the end of this tick
     * @param lafDb LAF at the end of the tick
     * @param tickLafMaxDb maximum LAF within the tick
     * @param tickLeqDb LAeq over the tick
     */
    fun onTick(tickEndSample: Long, lafDb: Double, tickLafMaxDb: Double, tickLeqDb: Double) {
        step(tickEndSample, lafDb, tickLafMaxDb, tickLeqDb)
        // This tick is history for the next tick's rise condition.
        pushRise(lafDb)
    }

    private fun step(tickEndSample: Long, lafDb: Double, tickLafMaxDb: Double, tickLeqDb: Double) {
        if (!active) {
            val floor = floorDb
            if (floor.isNaN()) return
            if (holding) {
                if (lafDb < floor + excessDb - hysteresisDb) holding = false
                return
            }
            if (lafDb >= floor + excessDb && risen(lafDb)) {
                active = true
                startSample = tickEndSample - tickSamples
                floorAtStart = floor
                bgAtStart = if (backgroundDb.isNaN()) floor else backgroundDb
                excessAtStart = excessDb
                minLevelAtStart = minLevelDb
                ticks = 0
                lafMax = Double.NEGATIVE_INFINITY
                energy = 0.0
                confirmed = false
                listener.onCandidateStart(startSample)
                accumulate(tickLafMaxDb, tickLeqDb)
            }
            return
        }
        if (lafDb < endLevel()) {
            close(tickEndSample - tickSamples)
            return
        }
        accumulate(tickLafMaxDb, tickLeqDb)
        if (ticks >= maxTicks) {
            close(tickEndSample)
            if (rules.holdAfterMax) holding = true
        }
    }

    private fun accumulate(tickLafMaxDb: Double, tickLeqDb: Double) {
        ticks++
        if (tickLafMaxDb > lafMax) lafMax = tickLafMaxDb
        energy += 10.0.pow(tickLeqDb / 10.0) * tickSeconds
        if (!confirmed && ticks >= minTicks && lafMax >= minLevelAtStart) {
            confirmed = true
            listener.onConfirmed(startSample)
        }
    }

    /** Closes a running event (e.g. when measurement stops). */
    fun flush(endSample: Long) {
        if (active) close(endSample)
    }

    private fun close(endSample: Long) {
        active = false
        if (!confirmed) {
            listener.onDiscarded(startSample)
            return
        }
        val duration = ticks * tickSeconds
        listener.onClosed(
            DetectedEvent(
                startSample = startSample,
                endSample = maxOf(endSample, startSample + ticks.toLong() * tickSamples),
                durationSeconds = duration,
                lafMaxDb = lafMax,
                selDb = 10.0 * log10(energy),
                backgroundDb = bgAtStart,
                thresholdDb = excessAtStart,
                minLevelDb = minLevelAtStart,
                localFloorDb = floorAtStart,
            )
        )
    }
}
