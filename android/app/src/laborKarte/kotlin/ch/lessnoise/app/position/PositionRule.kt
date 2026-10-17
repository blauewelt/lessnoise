package ch.lessnoise.app.position

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// LABOR·KARTE ONLY (src/laborKarte/kotlin, 0.6.9): the position as a second «unterwegs» signal in a
// «Messen» run. Pure, so that PositionRuleTest (src/testLaborKarte) drives it on the JVM. The Online,
// Offline and Labor builds contain none of this (stand-in src/noposition, or no motion code at all).

/**
 * Whether the phone has left the place where the run started, from fixes about once a minute.
 *
 * Memory: the run's **first** accepted fix (the reference: latitude, longitude, accuracy, time) and,
 * of the latest fix, only its distance from the reference, its accuracy and its time. No other fix is
 * kept, nothing is written anywhere; [Transition] and [distanceM] carry no coordinate.
 *
 * - A fix is used if it has an accuracy ≤ [MAX_ACCURACY_M], is at most [MAX_AGE_MS] old when it
 *   arrives, and is not older than the previous one ([accept]).
 * - **Away**: the distance from the reference is > max([MIN_DISTANCE_M], accuracy of the reference +
 *   accuracy of the fix) — both circles can no longer overlap with the phone at the start.
 * - STILL → MOVING after [AWAY_FIXES] away fixes in a row (a still phone's drift of ±80 m with
 *   20–60 m accuracy gives a stray far fix now and then: in a synthetic model of independent fixes
 *   uniform in an 80 m disk, 3 in a row falsely switched in 0.075 % of 8-hour nights, 4 in 0.005 %). The
 *   span starts at the last fix that was still near the reference ([lastNearMs]).
 * - MOVING → STILL only when [NEAR_FIXES] fixes in a row are near the **first** fix again (the
 *   phone came back), otherwise at «Stopp». No new reference: the run's shared point is where it was
 *   started, so a minute measured elsewhere must never be shared with it.
 */
class PositionRule {
    /** One fix as delivered (wall-clock [timeMs]). Not stored, except the first (the reference). */
    data class Fix(val timeMs: Long, val latitude: Double, val longitude: Double, val accuracyM: Double?)

    /** A change of the position signal at [atMs]; [spanStartMs] for MOVING. Distances rounded later; no coordinates. */
    data class Transition(val atMs: Long, val moving: Boolean, val spanStartMs: Long, val distanceM: Double, val thresholdM: Double)

    private var refLat = Double.NaN
    private var refLon = Double.NaN
    private var refAccuracy = Double.NaN

    /** Time of the reference fix (the run's first accepted one), or null before it. */
    var referenceMs: Long? = null
        private set

    /** Distance of the latest accepted fix from the reference (m), or null before the first fix. */
    var distanceM: Double? = null
        private set

    /** Time of the latest accepted fix. */
    var latestMs: Long? = null
        private set

    /** Time of the last fix near the reference (the reference itself at first). */
    var lastNearMs: Long? = null
        private set

    var moving = false
        private set

    private var away = 0
    private var near = 0

    /** Accepted fixes so far (for the log). */
    var fixes = 0
        private set

    /** One fix ([ageMs] old when it arrived). Returns the change it causes, or null. */
    fun onFix(f: Fix, ageMs: Long = 0L): Transition? {
        if (!accept(f.accuracyM, ageMs)) return null
        val prev = latestMs
        if (prev != null && f.timeMs < prev) return null
        val acc = f.accuracyM!!
        fixes++
        latestMs = f.timeMs
        if (referenceMs == null) {
            refLat = f.latitude; refLon = f.longitude; refAccuracy = acc
            referenceMs = f.timeMs
            lastNearMs = f.timeMs
            distanceM = 0.0
            return null
        }
        val d = distance(refLat, refLon, f.latitude, f.longitude)
        val threshold = max(MIN_DISTANCE_M, refAccuracy + acc)
        distanceM = d
        return if (d > threshold) {
            away++; near = 0
            if (!moving && away >= AWAY_FIXES) {
                moving = true
                Transition(f.timeMs, true, lastNearMs ?: f.timeMs, d, threshold)
            } else null
        } else {
            near++; away = 0
            lastNearMs = f.timeMs
            if (moving && near >= NEAR_FIXES) {
                moving = false
                Transition(f.timeMs, false, f.timeMs, d, threshold)
            } else null
        }
    }

    /**
     * At «Stopp»: if the latest fixes were already away but not yet confirmed ([AWAY_FIXES]), the time
     * from which the run's end counts as «unterwegs» (the last near fix), else null. Conservative: a
     * ride that the stop cut short is not shared either.
     */
    fun pendingAwaySinceMs(): Long? = if (!moving && away > 0) lastNearMs else null

    /**
     * How long the upload must hold records at [nowMs] so that a span this rule may still decide
     * covers them: while it says STILL, everything after the last near fix may turn out to be part of
     * a ride (+ [HOLD_MARGIN_MS]); at most [MAX_HOLD_MS]. 0 before the reference and while MOVING
     * (the open span covers new records).
     */
    fun holdMs(nowMs: Long): Long {
        val near = lastNearMs ?: return 0L
        if (moving) return 0L
        return min(MAX_HOLD_MS, max(0L, nowMs - near) + HOLD_MARGIN_MS)
    }

    companion object {
        /** Fixes less accurate than this are ignored (a cell-tower fix of a few km says nothing here). */
        const val MAX_ACCURACY_M = 500.0
        /** Fixes older than this when they arrive are ignored (a cached last position). */
        const val MAX_AGE_MS = 120_000L
        /** The smallest distance that counts as away, whatever the accuracies. */
        const val MIN_DISTANCE_M = 150.0
        const val AWAY_FIXES = 4
        const val NEAR_FIXES = 2
        const val HOLD_MARGIN_MS = 10_000L
        /** A fix that never comes cannot hold the upload for ever. */
        const val MAX_HOLD_MS = 600_000L

        fun accept(accuracyM: Double?, ageMs: Long): Boolean =
            accuracyM != null && !accuracyM.isNaN() && accuracyM > 0.0 && accuracyM <= MAX_ACCURACY_M && ageMs in 0..MAX_AGE_MS

        /** Metres between two points (equirectangular; exact to well below a metre over a few km). */
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_008.8
            val x = (lon2 - lon1) * PI / 180 * cos((lat1 + lat2) / 2 * PI / 180)
            val y = (lat2 - lat1) * PI / 180
            return r * sqrt(x * x + y * y)
        }
    }
}
