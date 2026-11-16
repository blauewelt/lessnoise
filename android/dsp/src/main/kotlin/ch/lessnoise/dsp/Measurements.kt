package ch.lessnoise.dsp

import kotlin.math.log10
import kotlin.math.pow

/**
 * One measurement: a run from «Start» to «Stop», at any time of day and possibly over several
 * days (since 0.6.0; before, the app grouped data into nights 22–06). [endMs] is exclusive.
 */
data class MeasurementSpan(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    operator fun contains(t: Long): Boolean = t in startMs until endMs
}

/** Summary of one measurement for the «Messungen» list. */
data class MeasurementSummary(
    val span: MeasurementSpan,
    /** Seconds of valid audio in the minutes used (minutes with < 50 % coverage are excluded). */
    val measuredSeconds: Double,
    /** «Mittel»: energy mean (LAeq) over the valid measurement time; NaN without valid minutes. */
    val laeqDb: Double,
    /** «Lärmspitzen»: events (after the floor) without wind on the microphone. */
    val eventCount: Int,
    /** Bursts with LAFmax ≥ background + [MeasurementSummarizer.STRONG_MARGIN_DB]. */
    val strongEventCount: Int,
    /** Time share per bucket id, weighted by measured duration of minutes with classifier data. */
    val categoryShares: Map<String, Double>,
    /** Bursts per category (wind events are not included). */
    val eventsByCategory: Map<String, Int>,
    /** The loudest burst (wind events are not considered). */
    val loudestEvent: NoiseEvent?,
    /** True only if every valid minute was measured with a calibrated offset. */
    val allCalibrated: Boolean,
    /** Minutes left out because less than half of them had valid audio (mic silenced etc.). */
    val excludedMinutes: Int = 0,
    /** «Lärmspitzen pro Stunde»: bursts per hour of valid measurement; NaN without valid time. */
    val eventsPerHour: Double = Double.NaN,
    /** "Dynamik": median over the valid minutes of L10 − L90 (dB); NaN without valid minutes. */
    val dynamicsDb: Double = Double.NaN,
    /** True if any minute was re-evaluated with a later calibration («nachträglich kalibriert»). */
    val anyRecalibrated: Boolean = false,
    /** «Grundrauschen», the hum under the bursts: median over the valid minutes of their L90 (dB). */
    val humDb: Double = Double.NaN,
    /** Events (after the floor) flagged as wind on the microphone: not in [eventCount]. */
    val windEventCount: Int = 0,
    /**
     * 0.6.5: the phone's noise floor ([NoiseFloor.Limit.limitDb], floor + 3 dB) if [humDb] is at it
     * (at least half of the valid minutes are «an der Messgrenze», [NoiseFloor.Limit.mostlyAtFloor]);
     * the list then shows «≤ [humLimitDb] dB(A)». Null otherwise or without a floor.
     */
    val humLimitDb: Double? = null,
) {
    val startMs: Long get() = span.startMs
    val endMs: Long get() = span.endMs

    /** Share of the measurement's duration covered by valid audio (0…1). */
    val coverage: Double
        get() = if (span.durationMs > 0) (measuredSeconds * 1000.0 / span.durationMs).coerceIn(0.0, 1.0) else 0.0
}

/** Burstiness figures shared by the measurement list and the chart. */
object Dynamics {
    /**
     * The hum («Hintergrund (L90)»): median over [minutes] of their L90 — the steady level the
     * bursts stand out from (e.g. the distant highway). Minutes without L90 are skipped.
     */
    fun medianL90(minutes: List<MinuteRecord>): Double {
        val d = minutes.mapNotNull { m -> m.l90Db.takeUnless { it.isNaN() } }.sorted()
        if (d.isEmpty()) return Double.NaN
        val n = d.size
        return if (n % 2 == 1) d[n / 2] else (d[n / 2 - 1] + d[n / 2]) / 2
    }

    /** Events per hour of valid measurement time. */
    fun eventsPerHour(events: Int, validSeconds: Double): Double =
        if (validSeconds > 0) events / (validSeconds / 3600.0) else Double.NaN

    /**
     * Median over [minutes] of L10 − L90: how far the level swings within a minute. Steady traffic
     * gives a few dB, a quiet street with passing bursts much more. Minutes without both values are
     * skipped; the median of an even count is the mean of the middle two.
     */
    fun medianSpread(minutes: List<MinuteRecord>): Double {
        val d = minutes.mapNotNull { m -> (m.l10Db - m.l90Db).takeUnless { it.isNaN() } }.sorted()
        if (d.isEmpty()) return Double.NaN
        val n = d.size
        return if (n % 2 == 1) d[n / 2] else (d[n / 2 - 1] + d[n / 2]) / 2
    }
}

object MeasurementSummarizer {
    const val STRONG_MARGIN_DB = 15.0
    /** Minutes with less valid audio than this fraction are left out of summaries and charts. */
    const val MIN_MINUTE_COVERAGE = 0.5

    /**
     * Summary of the measurement [span]: the minutes and events that start inside it. Only events
     * with LAFmax ≥ [eventMinLevelDb] are counted (the floor is applied at read time too, so events
     * stored before it existed are treated like new ones). Wind events are counted separately
     * ([MeasurementSummary.windEventCount]) and left out of everything else.
     */
    fun summarize(
        span: MeasurementSpan,
        minutes: List<MinuteRecord>,
        events: List<NoiseEvent>,
        eventMinLevelDb: Double = Double.NEGATIVE_INFINITY,
        noiseFloor: NoiseFloor.Limit? = null,
    ): MeasurementSummary {
        val allMinutes = minutes.filter { it.startEpochMs in span }
        val ms = allMinutes.filter { it.coverage >= MIN_MINUTE_COVERAGE && !it.laeqDb.isNaN() }
        val (wind, evs) = events.filter { it.startEpochMs in span && it.reachesFloor(eventMinLevelDb) }.partition { it.wind }
        var energy = 0.0
        var dur = 0.0
        val shareSum = LinkedHashMap<String, Double>()
        var classifiedDur = 0.0
        for (m in ms) {
            // Levels of a minute describe its valid seconds only, so weight by those.
            energy += 10.0.pow(m.laeqDb / 10.0) * m.validSeconds
            dur += m.validSeconds
            if (m.categoryShares.isNotEmpty()) {
                classifiedDur += m.validSeconds
                for ((k, v) in m.categoryShares) shareSum[k] = (shareSum[k] ?: 0.0) + v * m.validSeconds
            }
        }
        val shares = if (classifiedDur > 0) shareSum.mapValues { it.value / classifiedDur } else emptyMap()
        return MeasurementSummary(
            span = span,
            measuredSeconds = dur,
            laeqDb = if (dur > 0) 10 * log10(energy / dur) else Double.NaN,
            eventCount = evs.size,
            strongEventCount = evs.count { it.lafMaxDb >= it.backgroundDb + STRONG_MARGIN_DB },
            categoryShares = shares,
            eventsByCategory = evs.groupingBy { it.dominantCategory ?: "n/a" }.eachCount(),
            loudestEvent = evs.maxByOrNull { it.lafMaxDb },
            allCalibrated = ms.isNotEmpty() && ms.all { it.calibrated },
            excludedMinutes = allMinutes.size - ms.size,
            eventsPerHour = Dynamics.eventsPerHour(evs.size, dur),
            dynamicsDb = Dynamics.medianSpread(ms),
            anyRecalibrated = allMinutes.any { it.recalibrated },
            humDb = Dynamics.medianL90(ms),
            windEventCount = wind.size,
            humLimitDb = noiseFloor?.takeIf { it.mostlyAtFloor(allMinutes) }?.limitDb,
        )
    }

    /** [summarize] for every span, newest first, each with its own minutes and events only. */
    fun summarizeAll(
        spans: List<MeasurementSpan>,
        minutes: List<MinuteRecord>,
        events: List<NoiseEvent>,
        eventMinLevelDb: Double = Double.NEGATIVE_INFINITY,
        noiseFloor: NoiseFloor.Limit? = null,
    ): List<MeasurementSummary> {
        val sortedMinutes = minutes.sortedBy { it.startEpochMs }
        val sortedEvents = events.sortedBy { it.startEpochMs }
        return spans.sortedByDescending { it.startMs }.map { s ->
            summarize(s, slice(sortedMinutes, s) { it.startEpochMs }, slice(sortedEvents, s) { it.startEpochMs }, eventMinLevelDb, noiseFloor)
        }
    }

    private fun <T> slice(sorted: List<T>, s: MeasurementSpan, start: (T) -> Long): List<T> {
        val from = lowerBound(sorted, s.startMs, start)
        val to = lowerBound(sorted, s.endMs, start)
        return sorted.subList(from, maxOf(from, to))
    }

    private fun <T> lowerBound(sorted: List<T>, t: Long, start: (T) -> Long): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (start(sorted[mid]) < t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Splitting stored minutes into measurements, and finding the measurement a record belongs to.
 * Pure functions, used by the database migration 5 → 6 (minutes recorded before 0.6.0 had no
 * explicit start/stop) and by the upload (the cell of a minute is the cell of its measurement).
 */
object MeasurementSplitter {
    /** A gap longer than this between two minutes starts a new measurement. */
    const val MAX_GAP_MS = 10 * 60_000L

    /** A record belongs to a measurement that starts at most this much after it (clock jitter at the start). */
    const val START_TOLERANCE_MS = 5_000L

    /**
     * Runs of minutes, oldest first: a new run starts where the next minute starts more than
     * [maxGapMs] after the end of the previous one. [minutes] are (start epoch ms, duration in
     * seconds), in any order. A run ends at the end of its last minute.
     */
    fun split(minutes: List<Pair<Long, Double>>, maxGapMs: Long = MAX_GAP_MS): List<MeasurementSpan> {
        if (minutes.isEmpty()) return emptyList()
        val sorted = minutes.sortedBy { it.first }
        val out = ArrayList<MeasurementSpan>()
        var start = sorted[0].first
        var end = endOf(sorted[0])
        for (i in 1 until sorted.size) {
            val m = sorted[i]
            if (m.first - end > maxGapMs) {
                out += MeasurementSpan(start, end)
                start = m.first
            }
            end = maxOf(end, endOf(m))
        }
        out += MeasurementSpan(start, end)
        return out
    }

    private fun endOf(m: Pair<Long, Double>): Long = m.first + maxOf(1L, Math.round(m.second * 1000))

    /**
     * Index into [starts] (measurement starts, ascending) of the measurement a record starting at
     * [t] belongs to: the latest one that started at or before [t] (+ [START_TOLERANCE_MS]); -1 if
     * none did. The caller checks the measurement's end when it matters.
     */
    fun ownerIndex(starts: LongArray, t: Long): Int {
        var lo = 0
        var hi = starts.size
        val key = t + START_TOLERANCE_MS
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= key) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }
}
