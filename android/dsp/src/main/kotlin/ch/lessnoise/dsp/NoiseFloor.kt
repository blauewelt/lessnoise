package ch.lessnoise.dsp

/**
 * The phone's own noise floor («Messgrenze», 0.6.5), estimated from the stored minutes; no setting.
 *
 * A phone microphone has self-noise: levels near it are the phone, not the room. The estimate
 * works on raw levels (stored level − the calibration offset stored with it), so it survives every
 * calibration and the retroactive re-evaluation («Alte Messungen anpassen»: level and offset move
 * together, their difference stays):
 *
 * 1. The minutes used are the app's valid minutes (≥ 50 % valid audio and a LAeq, the rule of the
 *    measurement list and the chart, [MeasurementSummarizer.MIN_MINUTE_COVERAGE]) that have an L90,
 *    of one audio source. rawL90 = L90 − calibration offset of the minute.
 * 2. floorRaw = the [PERCENTILE]nd percentile (nearest rank, [rankIndex]) of rawL90 over ALL stored
 *    such minutes of that source. Defined only from [MIN_MINUTES] minutes on; then the rank is at
 *    least the second-lowest value, so a single glitch minute far below never sets the floor.
 * 3. Shown: floorRaw + the ACTIVE calibration offset ([Limit.floorDb]).
 * 4. A minute is «an der Messgrenze» if rawL90 ≤ floorRaw + [MARGIN_DB] ([Limit.atFloor]); the
 *    number shown for such values is [Limit.limitDb] = shown floor + [MARGIN_DB], as «≤ 34,0 dB(A)».
 *
 * Pure Kotlin; the app computes floorRaw with one SQL query (data/NoiseFloorStore.kt), this file
 * holds the arithmetic and the reference implementation [estimate] the tests compare it with.
 */
object NoiseFloor {
    /** Percentile of rawL90 that is taken as the floor (percent, nearest rank). */
    const val PERCENTILE = 2
    /** No floor before this many valid minutes of the audio source. */
    const val MIN_MINUTES = 60
    /** Values up to this much above the floor are «an der Messgrenze» (dB). */
    const val MARGIN_DB = 3.0

    /** The minutes the estimate and the flag use (see the class comment, point 1). */
    fun isValid(m: MinuteRecord): Boolean =
        m.coverage >= MeasurementSummarizer.MIN_MINUTE_COVERAGE && !m.laeqDb.isNaN() && !m.l90Db.isNaN()

    /** L90 without the minute's own calibration offset. */
    fun rawL90(m: MinuteRecord): Double = m.l90Db - m.calibrationOffsetDb

    /**
     * 0-based index of the [PERCENTILE]th percentile among [n] values sorted ascending, by nearest
     * rank: ceil(PERCENTILE / 100 · n) − 1 (integer arithmetic). From [MIN_MINUTES] on it is ≥ 1.
     */
    fun rankIndex(n: Int): Int {
        require(n > 0)
        return ((PERCENTILE * n + 99) / 100 - 1).coerceIn(0, n - 1)
    }

    /** floorRaw of [audioSource], estimated from [minutes] valid minutes. */
    data class Estimate(val audioSource: String, val floorRawDb: Double, val minutes: Int)

    /** floorRaw from already filtered raw L90 values; null below [MIN_MINUTES]. */
    fun estimateFromRaw(audioSource: String, rawL90: DoubleArray): Estimate? {
        if (rawL90.size < MIN_MINUTES) return null
        val sorted = rawL90.copyOf().also { it.sort() }
        return Estimate(audioSource, sorted[rankIndex(sorted.size)], sorted.size)
    }

    /** Reference implementation: floorRaw of [audioSource] from all [minutes] (any order, any source). */
    fun estimate(audioSource: String, minutes: List<MinuteRecord>): Estimate? =
        estimateFromRaw(
            audioSource,
            minutes.filter { it.audioSource == audioSource && isValid(it) }.map { rawL90(it) }.toDoubleArray(),
        )

    /** An [estimate] with the calibration offset that is active now: what the app shows and flags. */
    data class Limit(val estimate: Estimate, val activeOffsetDb: Double) {
        val audioSource: String get() = estimate.audioSource
        val floorRawDb: Double get() = estimate.floorRawDb
        /** The floor as shown with the active calibration. */
        val floorDb: Double get() = floorRawDb + activeOffsetDb
        /** The number shown for values at the limit («≤ …») and the top of the chart band. */
        val limitDb: Double get() = floorDb + MARGIN_DB

        /** A raw level of [source] at the limit (always false for another audio source or NaN). */
        fun atFloorRaw(rawDb: Double, source: String): Boolean =
            source == audioSource && !rawDb.isNaN() && rawDb <= floorRawDb + MARGIN_DB

        /** The minute is valid ([isValid]) and its rawL90 is at the limit. */
        fun atFloor(m: MinuteRecord): Boolean = isValid(m) && atFloorRaw(rawL90(m), m.audioSource)

        /**
         * The «Grundrauschen» (median L90) of [minutes] is at the limit: at least half of their
         * valid minutes are flagged. False without valid minutes.
         */
        fun mostlyAtFloor(minutes: List<MinuteRecord>): Boolean {
            var valid = 0
            var flagged = 0
            for (m in minutes) {
                if (!isValid(m)) continue
                valid++
                if (atFloorRaw(rawL90(m), m.audioSource)) flagged++
            }
            return valid > 0 && 2 * flagged >= valid
        }
    }
}
