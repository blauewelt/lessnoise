package ch.lessnoise.chart

import ch.lessnoise.dsp.MinuteRecord
import ch.lessnoise.dsp.MeasurementSummarizer
import ch.lessnoise.dsp.NoiseEvent
import ch.lessnoise.dsp.WindRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** 0.6.5: the line under the summary tiles when «Grundrauschen» is at the phone's noise floor. */
const val AT_FLOOR_NOTE = "Leiser als das Telefon messen kann."

/** 0.6.5: label of the chart band up to the phone's noise floor + 3 dB. */
const val FLOOR_BAND_LABEL = "Messgrenze"

/** Note for data re-evaluated with a calibration made after the measurement. */
const val RECALIBRATED = "nachträglich kalibriert"

/** Raw data of one window as loaded from the database. */
data class ChartData(
    val window: TimeWindow,
    val minutes: List<MinuteRecord>,
    val events: List<NoiseEvent>,
    /** Time of the query; data after it cannot exist yet (not a gap). */
    val nowMs: Long,
    /** End of the last valid minute before the window (to tell an interruption from a first start). */
    val prevValidEndMs: Long? = null,
    /** Start of the first valid minute after the window. */
    val nextValidStartMs: Long? = null,
    /**
     * Optional audio clip per event, keyed by the event's [NoiseEvent.startEpochMs]. Only the Labor
     * build fills it (an opaque reference the app resolves itself); the public app has no clips
     * and leaves it empty.
     */
    val clipRefs: Map<Long, String> = emptyMap(),
)

/**
 * The clip references of the events in [ChartData.window] that reach [eventFloorDb], in time order
 * (for stepping to the previous/next event with a clip).
 */
fun ChartData.clipRefsInTimeOrder(eventFloorDb: Double): List<String> =
    if (clipRefs.isEmpty()) emptyList()
    else events.filter { it.startEpochMs in window && it.reachesFloor(eventFloorDb) }
        .sortedBy { it.startEpochMs }
        .mapNotNull { clipRefs[it.startEpochMs] }
        .distinct()

/**
 * One plotted sample: a minute, an hour (long measurements) or an hour of the day («Tagesverlauf»).
 * Levels are NaN when not [valid].
 * Plotted at the middle of [startMs, endMs).
 */
data class SeriesPoint(
    val startMs: Long,
    val endMs: Long,
    val laeq: Double,
    val l10: Double,
    val l90: Double,
    val lafMax: Double,
    val valid: Boolean,
    val calibrated: Boolean,
    /** Valid seconds in this sample. */
    val validSeconds: Double,
    /** Re-evaluated with a later calibration («nachträglich kalibriert»; any minute of an hour). */
    val recalibrated: Boolean = false,
) {
    val midMs: Long get() = startMs + (endMs - startMs) / 2
}

/** A span without valid data, ≥ [ChartModel.MIN_GAP_MS] long. */
data class Gap(val span: Span, val atWindowStart: Boolean, val atDataEnd: Boolean)

/** dB range of the y axis. */
data class YRange(val lo: Double, val hi: Double) {
    val span: Double get() = hi - lo
    /** Gridlines every 10 dB. */
    fun gridlines(): List<Double> {
        val out = ArrayList<Double>()
        var v = ceil(lo / 10.0) * 10.0
        while (v <= hi + 1e-9) { out += v; v += 10.0 }
        return out
    }
}

/** Window statistics for the summary row. */
data class WindowSummary(
    val laeqDb: Double,
    /** Bursts: events with LAFmax ≥ the floor in the window, without wind (all of them, also in the hourly views). */
    val eventCount: Int,
    /** Bursts of the highlighted category. */
    val highlightCount: Int,
    /** The loudest burst (wind is not considered). */
    val loudest: NoiseEvent?,
    /** Valid time / elapsed window time (0…1). */
    val coverage: Double,
    /** «unterbrochen 20:54–23:54», «Messung ab 23:54», … */
    val gapTexts: List<String>,
    val anyUncalibrated: Boolean,
    /** Bursts (events after the floor, without wind) per hour of valid measurement. */
    val eventsPerHour: Double = Double.NaN,
    /** Median over valid minutes of L10 − L90. */
    val dynamicsDb: Double = Double.NaN,
    /** Any minute of the window re-evaluated with a later calibration (shown as its own line under [line]). */
    val anyRecalibrated: Boolean = false,
    /** «Grundrauschen», the hum: median over the valid minutes of their L90 (same rule as the measurement list). */
    val humDb: Double = Double.NaN,
    /** Events (after the floor) flagged as wind on the microphone: in none of the counts above. */
    val windCount: Int = 0,
    /**
     * 0.6.5: the phone's noise floor + 3 dB if [humDb] is at it (same rule as the measurement list,
     * [ch.lessnoise.dsp.NoiseFloor.Limit.mostlyAtFloor]): the tile shows «≤ …» and the note
     * [AT_FLOOR_NOTE]. Null otherwise or without a floor.
     */
    val humLimitDb: Double? = null,
) {
    /** The «Grundrauschen» tile: «41,2 dB(A)», or «≤ 34,0 dB(A)» at the phone's noise floor. */
    val humText: String
        get() = humLimitDb?.let { "≤ ${ChartFmt.db(it)} dB(A)" } ?: "${ChartFmt.db(humDb)} dB(A)"

    /** «Lärmspitzen pro Stunde» tile: «12», «4,5», «–» without valid time. */
    val eventsPerHourText: String
        get() = if (eventsPerHour.isNaN()) "–" else ChartFmt.comma(eventsPerHour, if (eventsPerHour < 10) 1 else 0)

    /**
     * «Mittel 45,2 dB(A) · 48 Lärmspitzen, davon 5 Töff & Poser · 76 % gemessen» ([highlightName]
     * null: without the highlighted category). The gaps are drawn in the chart, not listed (0.6.1);
     * [gapTexts] and [dynamicsDb] stay for the tests and the CSV.
     */
    fun line(highlightName: String? = null): String {
        val parts = ArrayList<String>()
        if (!laeqDb.isNaN()) parts += "Mittel ${ChartFmt.comma(laeqDb, 1)} dB(A)"
        parts += "$eventCount ${if (eventCount == 1) "Lärmspitze" else "Lärmspitzen"}" +
            (if (highlightName != null) ", davon $highlightCount $highlightName" else "")
        parts += "${ChartFmt.percent(coverage)} gemessen"
        return parts.joinToString(" · ")
    }
}

/**
 * Everything the chart draws, in time/dB space (no pixels). Built once per data/mode/highlight
 * change, never per frame.
 */
class ChartModel(
    val window: TimeWindow,
    val nowMs: Long,
    val hourly: Boolean,
    val points: List<SeriesPoint>,
    /** Runs of contiguous valid points: the line and band are drawn per run, never across gaps. */
    val segments: List<List<SeriesPoint>>,
    val gaps: List<Gap>,
    val gridTicks: List<Long>,
    val axisLabels: List<AxisLabel>,
    val yRange: YRange,
    /** Events drawn, non-highlighted first (they are drawn below), each list oldest first. Never wind. */
    val otherEvents: List<NoiseEvent>,
    val highlightedEvents: List<NoiseEvent>,
    /** True if an hourly view dropped events beyond [HOURLY_EVENT_CAP]. */
    val eventsCapped: Boolean,
    val highlight: String,
    val summary: WindowSummary,
    val zone: ZoneId,
    /** Clip reference per event start (see [ChartData.clipRefs]); empty in the public app. */
    val clipRefs: Map<Long, String> = emptyMap(),
    /**
     * Wind events (≥ the floor), drawn as small hollow grey dots below the others when shown
     * (setting «Wind-Ereignisse zeigen»; never in the hourly views). Not counted anywhere.
     */
    val windEvents: List<NoiseEvent> = emptyList(),
    /** Tooltips name weekday and date (the measurement spans more than one calendar date). */
    val withDates: Boolean = false,
    /**
     * 0.6.5: top of the light band «Messgrenze» (the phone's noise floor + 3 dB, drawn from the
     * bottom of the plot up to here), or null: no floor, or the band lies below the y range.
     * Clamped to the top of the y range.
     */
    val floorBandTopDb: Double? = null,
) {
    /** The event's clip reference, or null (always null in the public app). */
    fun clipRefOf(e: NoiseEvent): String? = if (clipRefs.isEmpty()) null else clipRefs[e.startEpochMs]

    val isEmpty: Boolean get() = segments.isEmpty() && otherEvents.isEmpty() && highlightedEvents.isEmpty() && windEvents.isEmpty()
    val anyUncalibrated: Boolean get() = summary.anyUncalibrated

    /** Nearest valid point to [t], or null if none within [maxDistanceMs]. */
    fun nearestValidPoint(t: Long, maxDistanceMs: Long = if (hourly) 2 * Windows.HOUR_MS else 3 * Windows.MINUTE_MS): SeriesPoint? {
        var best: SeriesPoint? = null
        var bestD = Long.MAX_VALUE
        for (p in points) {
            if (!p.valid) continue
            val d = if (t in p.startMs until p.endMs) 0L else minOf(kotlin.math.abs(p.startMs - t), kotlin.math.abs(p.endMs - t))
            if (d < bestD) { bestD = d; best = p }
        }
        return best?.takeIf { bestD <= maxDistanceMs }
    }

    companion object {
        /** Minutes with less valid audio are treated as missing (same rule as the measurement summaries). */
        const val MIN_COVERAGE = MeasurementSummarizer.MIN_MINUTE_COVERAGE
        /** Measurements longer than this are drawn with hourly values (like the former week view). */
        const val HOURLY_FROM_MS = 36 * Windows.HOUR_MS
        /** «Tagesverlauf»: an hour of the day needs this many valid minutes over the whole measurement. */
        const val MIN_VALID_MINUTES_PER_PROFILE_HOUR = 10
        /** Gaps at least this long get a "keine Messung" rectangle and are listed in the summary. */
        const val MIN_GAP_MS = 10 * Windows.MINUTE_MS
        /** Hourly values: an hour needs this many valid minutes. */
        const val MIN_VALID_MINUTES_PER_HOUR = 30
        /** Hourly views: only events of the highlighted category at least this loud (and ≥ the floor) are drawn … */
        const val HOURLY_EVENT_MIN_DB = 60.0
        /** … and at most this many (the loudest). */
        const val HOURLY_EVENT_CAP = 300
        /** Two minutes are contiguous if the second starts at most this long after the first ends. */
        private const val CONTIGUOUS_SLACK_MS = 30_000L
        /** An interruption is told apart from a first start if the neighbouring data is this close. */
        private const val NEIGHBOUR_MS = 12 * Windows.HOUR_MS

        fun isValid(m: MinuteRecord): Boolean = m.coverage >= MIN_COVERAGE && !m.laeqDb.isNaN()

        /**
         * [noiseFloor] (0.6.5): the phone's noise floor, for the «Messgrenze» band and the «≤» of
         * the «Grundrauschen» tile; null: neither.
         */
        fun build(
            data: ChartData, zone: ZoneId, highlight: String, eventFloorDb: Double, showWind: Boolean = true,
            noiseFloor: ch.lessnoise.dsp.NoiseFloor.Limit? = null,
        ): ChartModel {
            val m = buildWithoutFloor(data, zone, highlight, eventFloorDb, showWind)
            if (noiseFloor == null) return m
            val minutes = data.minutes.filter { it.startEpochMs in data.window }
            val atFloor = noiseFloor.mostlyAtFloor(minutes)
            return ChartModel(
                window = m.window, nowMs = m.nowMs, hourly = m.hourly, points = m.points, segments = m.segments, gaps = m.gaps,
                gridTicks = m.gridTicks, axisLabels = m.axisLabels, yRange = m.yRange, otherEvents = m.otherEvents,
                highlightedEvents = m.highlightedEvents, eventsCapped = m.eventsCapped, highlight = m.highlight,
                summary = if (atFloor) m.summary.copy(humLimitDb = noiseFloor.limitDb) else m.summary,
                zone = m.zone, clipRefs = m.clipRefs, windEvents = m.windEvents, withDates = m.withDates,
                floorBandTopDb = floorBandTop(noiseFloor.limitDb, m.yRange),
            )
        }

        /** The band's top in dB, or null if it lies at or below the bottom of [y] (then nothing is drawn). */
        fun floorBandTop(limitDb: Double, y: YRange): Double? =
            if (limitDb.isNaN() || limitDb <= y.lo) null else minOf(limitDb, y.hi)

        private fun buildWithoutFloor(data: ChartData, zone: ZoneId, highlight: String, eventFloorDb: Double, showWind: Boolean): ChartModel {
            val w = data.window
            val windows = Windows(zone)
            val minutes = data.minutes.filter { it.startEpochMs in w }.sortedBy { it.startEpochMs }
            val dataEnd = minOf(w.endMs, maxOf(data.nowMs, w.startMs))
            // Events: the floor everywhere (also for events stored before it existed); wind apart.
            val (wind, floored) = data.events.filter { it.startEpochMs in w && it.reachesFloor(eventFloorDb) }.partition { it.wind }

            // The summary row is the same in both views: it describes the measurement.
            val spanPoints = if (w.durationMs > HOURLY_FROM_MS) hourlyPoints(w, minutes, data.nowMs) else minutes.map { minutePoint(it) }
            val spanGaps = gaps(spanPoints.filter { it.valid }.map { Span(it.startMs, it.endMs) }, w.startMs, dataEnd)
            val summary = summary(data, minutes, floored, highlight, spanGaps, windows, dataEnd).copy(windCount = wind.size)

            if (w.mode == RangeMode.PROFILE) return profile(data, minutes, floored, zone, highlight, eventFloorDb, summary)

            val hourly = w.durationMs > HOURLY_FROM_MS
            val segments = segments(spanPoints, if (hourly) 0L else CONTIGUOUS_SLACK_MS)
            val windShown = if (showWind && !hourly) wind.sortedBy { it.startEpochMs } else emptyList()
            val (shown, capped) = drawnEvents(floored, hourly, highlight, eventFloorDb)
            val (hl, other) = shown.partition { it.dominantCategory == highlight }
            return ChartModel(
                window = w, nowMs = data.nowMs, hourly = hourly, points = spanPoints, segments = segments, gaps = spanGaps,
                gridTicks = windows.gridTicks(w), axisLabels = windows.axisLabels(w),
                yRange = yRange(spanPoints, shown + windShown), otherEvents = other, highlightedEvents = hl, eventsCapped = capped,
                highlight = highlight, summary = summary, zone = zone, clipRefs = data.clipRefs, windEvents = windShown,
                withDates = windows.crossesDates(w),
            )
        }

        /**
         * Hourly views draw only the highlighted category (a wall of grey dots says nothing), at
         * least [HOURLY_EVENT_MIN_DB] loud and at most [HOURLY_EVENT_CAP] (the loudest). Oldest first.
         */
        private fun drawnEvents(floored: List<NoiseEvent>, hourly: Boolean, highlight: String, eventFloorDb: Double): Pair<List<NoiseEvent>, Boolean> {
            if (!hourly) return floored.sortedBy { it.startEpochMs } to false
            var shown = floored.filter { it.dominantCategory == highlight && it.lafMaxDb >= maxOf(eventFloorDb, HOURLY_EVENT_MIN_DB) }
            var capped = false
            if (shown.size > HOURLY_EVENT_CAP) {
                capped = true
                shown = shown.sortedByDescending { it.lafMaxDb }.take(HOURLY_EVENT_CAP)
            }
            return shown.sortedBy { it.startEpochMs } to capped
        }

        /** Milliseconds since 00:00 local time of [t]: its place on the «Tagesverlauf» axis. */
        fun msOfDay(t: Long, zone: ZoneId): Long =
            Instant.ofEpochMilli(t).atZone(zone).toLocalTime().toNanoOfDay() / 1_000_000

        /**
         * «Tagesverlauf»: every day of the measurement laid over one 0–24 h axis ([Windows.PROFILE_WINDOW],
         * drawn in [Windows.PROFILE_ZONE] so that the axis reads local clock time). One point per
         * hour of the day: LAeq is the energy mean of the valid minutes that started in that hour
         * (any day), the band the median of their L90 and of their L10 (the typical hour, not the
         * extremes); an hour with fewer than [MIN_VALID_MINUTES_PER_PROFILE_HOUR] valid minutes is
         * a gap. Events sit at their clock time; drawn as in the hourly views (highlighted category,
         * loud ones, capped); wind events are not drawn.
         */
        private fun profile(
            data: ChartData, minutes: List<MinuteRecord>, floored: List<NoiseEvent>,
            zone: ZoneId, highlight: String, eventFloorDb: Double, summary: WindowSummary,
        ): ChartModel {
            val pw = Windows.PROFILE_WINDOW
            val points = profilePoints(minutes, zone)
            val gaps = gaps(points.filter { it.valid }.map { Span(it.startMs, it.endMs) }, pw.startMs, pw.endMs)
            fun moved(e: NoiseEvent) = e.copy(startEpochMs = msOfDay(e.startEpochMs, zone))
            val (shown, capped) = drawnEvents(floored, hourly = true, highlight = highlight, eventFloorDb = eventFloorDb)
            val movedShown = shown.map(::moved).sortedBy { it.startEpochMs }
            val (hl, other) = movedShown.partition { it.dominantCategory == highlight }
            val clipRefs = if (data.clipRefs.isEmpty()) emptyMap() else
                shown.mapNotNull { e -> data.clipRefs[e.startEpochMs]?.let { msOfDay(e.startEpochMs, zone) to it } }.toMap()
            val pz = Windows.PROFILE_ZONE
            val pws = Windows(pz)
            return ChartModel(
                window = pw, nowMs = pw.endMs, hourly = true, points = points, segments = segments(points, 0L), gaps = gaps,
                gridTicks = pws.gridTicks(pw), axisLabels = pws.axisLabels(pw),
                yRange = yRange(points, movedShown), otherEvents = other, highlightedEvents = hl, eventsCapped = capped,
                highlight = highlight, summary = summary, zone = pz, clipRefs = clipRefs, windEvents = emptyList(),
                withDates = false,
            )
        }

        /** The 24 points of the «Tagesverlauf» (see [profile]). */
        fun profilePoints(minutes: List<MinuteRecord>, zone: ZoneId): List<SeriesPoint> {
            val buckets = Array(24) { ArrayList<MinuteRecord>() }
            for (m in minutes) buckets[Instant.ofEpochMilli(m.startEpochMs).atZone(zone).hour] += m
            return (0 until 24).map { h ->
                val s = h * Windows.HOUR_MS
                val e = s + Windows.HOUR_MS
                val valid = buckets[h].filter { isValid(it) }
                if (valid.size < MIN_VALID_MINUTES_PER_PROFILE_HOUR) {
                    SeriesPoint(s, e, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, buckets[h].all { it.calibrated }, 0.0, buckets[h].any { it.recalibrated })
                } else {
                    var energy = 0.0
                    var secs = 0.0
                    for (m in valid) { energy += 10.0.pow(m.laeqDb / 10) * m.validSeconds; secs += m.validSeconds }
                    SeriesPoint(
                        s, e,
                        laeq = 10 * log10(energy / secs),
                        l10 = median(valid.map { maxOf(it.l10Db, it.l90Db) }),
                        l90 = median(valid.map { minOf(it.l10Db, it.l90Db) }),
                        lafMax = valid.maxOf { it.lafMaxDb },
                        valid = true, calibrated = valid.all { it.calibrated }, validSeconds = secs,
                        recalibrated = valid.any { it.recalibrated },
                    )
                }
            }
        }

        private fun median(v: List<Double>): Double {
            val d = v.filter { !it.isNaN() }.sorted()
            if (d.isEmpty()) return Double.NaN
            val n = d.size
            return if (n % 2 == 1) d[n / 2] else (d[n / 2 - 1] + d[n / 2]) / 2
        }

        fun minutePoint(m: MinuteRecord): SeriesPoint {
            val valid = isValid(m)
            val end = m.startEpochMs + Math.round(m.durationSeconds * 1000)
            return SeriesPoint(
                m.startEpochMs, end,
                if (valid) m.laeqDb else Double.NaN,
                if (valid) maxOf(m.l10Db, m.l90Db) else Double.NaN,
                if (valid) minOf(m.l10Db, m.l90Db) else Double.NaN,
                if (valid) m.lafMaxDb else Double.NaN,
                valid, m.calibrated, if (valid) m.validSeconds else 0.0, m.recalibrated,
            )
        }

        /**
         * Long measurements: one point per hour of the window up to now. LAeq is the energy mean over the
         * valid minutes weighted by their valid seconds; L10 is the maximum of the minute L10s and
         * L90 the minimum of the minute L90s (the band shows the hour's full range); an hour with
         * fewer than [MIN_VALID_MINUTES_PER_HOUR] valid minutes is a gap.
         */
        fun hourlyPoints(w: TimeWindow, minutes: List<MinuteRecord>, nowMs: Long): List<SeriesPoint> {
            val n = ((w.endMs - w.startMs + Windows.HOUR_MS - 1) / Windows.HOUR_MS).toInt()
            val buckets = Array(n) { ArrayList<MinuteRecord>() }
            for (m in minutes) {
                val i = ((m.startEpochMs - w.startMs) / Windows.HOUR_MS).toInt()
                if (i in 0 until n) buckets[i] += m
            }
            val out = ArrayList<SeriesPoint>(n)
            for (i in 0 until n) {
                val s = w.startMs + i * Windows.HOUR_MS
                if (s > nowMs && buckets[i].isEmpty()) break
                val e = minOf(s + Windows.HOUR_MS, w.endMs)
                val valid = buckets[i].filter { isValid(it) }
                if (valid.size < MIN_VALID_MINUTES_PER_HOUR) {
                    out += SeriesPoint(
                        s, e, Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, buckets[i].all { it.calibrated }, 0.0,
                        buckets[i].any { it.recalibrated },
                    )
                    continue
                }
                var energy = 0.0
                var secs = 0.0
                for (m in valid) { energy += 10.0.pow(m.laeqDb / 10) * m.validSeconds; secs += m.validSeconds }
                out += SeriesPoint(
                    s, e,
                    laeq = 10 * log10(energy / secs),
                    l10 = valid.maxOf { maxOf(it.l10Db, it.l90Db) },
                    l90 = valid.minOf { minOf(it.l10Db, it.l90Db) },
                    lafMax = valid.maxOf { it.lafMaxDb },
                    valid = true, calibrated = valid.all { it.calibrated }, validSeconds = secs,
                    recalibrated = valid.any { it.recalibrated },
                )
            }
            return out
        }

        /** Splits valid points into contiguous runs; any missing or invalid sample breaks the run. */
        fun segments(points: List<SeriesPoint>, slackMs: Long): List<List<SeriesPoint>> {
            val out = ArrayList<List<SeriesPoint>>()
            var cur = ArrayList<SeriesPoint>()
            for (p in points) {
                if (!p.valid) {
                    if (cur.isNotEmpty()) { out += cur; cur = ArrayList() }
                    continue
                }
                val last = cur.lastOrNull()
                if (last != null && p.startMs - last.endMs > slackMs) { out += cur; cur = ArrayList() }
                cur += p
            }
            if (cur.isNotEmpty()) out += cur
            return out
        }

        /** Spans of at least [MIN_GAP_MS] in [from, to) not covered by any of [valid] (sorted). */
        fun gaps(valid: List<Span>, from: Long, to: Long): List<Gap> {
            val out = ArrayList<Gap>()
            var cursor = from
            for (v in valid.sortedBy { it.startMs }) {
                if (v.startMs - cursor >= MIN_GAP_MS) out += Gap(Span(cursor, minOf(v.startMs, to)), cursor == from, false)
                cursor = maxOf(cursor, v.endMs)
            }
            if (to - cursor >= MIN_GAP_MS) out += Gap(Span(cursor, to), cursor == from, true)
            return out.filter { it.span.durationMs >= MIN_GAP_MS }
        }

        /**
         * y range: from floor₅(min L90 − 5) to ceil₅(max(LAFmax of drawn events, LAeq, L10) + 5),
         * at least 30 dB wide, within 0…120 dB.
         */
        fun yRange(points: List<SeriesPoint>, events: List<NoiseEvent>): YRange {
            val valid = points.filter { it.valid }
            if (valid.isEmpty() && events.isEmpty()) return YRange(20.0, 80.0)
            val minL90 = valid.minOfOrNull { it.l90 } ?: events.minOf { it.backgroundDb.takeUnless { b -> b.isNaN() } ?: it.lafMaxDb }
            val maxTop = maxOf(
                valid.maxOfOrNull { maxOf(it.laeq, it.l10) } ?: Double.NEGATIVE_INFINITY,
                events.maxOfOrNull { it.lafMaxDb } ?: Double.NEGATIVE_INFINITY,
            )
            var lo = floor((minL90 - 5) / 5) * 5
            var hi = ceil((maxTop + 5) / 5) * 5
            if (hi - lo < 30) hi = lo + 30
            lo = lo.coerceIn(0.0, 120.0)
            hi = hi.coerceIn(0.0, 120.0)
            if (hi - lo < 30) { if (hi >= 120.0) lo = 90.0 else hi = lo + 30 }
            return YRange(lo, hi)
        }

        private fun summary(
            data: ChartData, minutes: List<MinuteRecord>, floored: List<NoiseEvent>, highlight: String,
            gaps: List<Gap>, windows: Windows, dataEnd: Long,
        ): WindowSummary {
            val w = data.window
            val valid = minutes.filter { isValid(it) }
            var energy = 0.0
            var secs = 0.0
            for (m in valid) { energy += 10.0.pow(m.laeqDb / 10) * m.validSeconds; secs += m.validSeconds }
            val elapsed = (dataEnd - w.startMs) / 1000.0
            return WindowSummary(
                laeqDb = if (secs > 0) 10 * log10(energy / secs) else Double.NaN,
                eventCount = floored.size,
                highlightCount = floored.count { it.dominantCategory == highlight },
                loudest = floored.maxByOrNull { it.lafMaxDb },
                coverage = if (elapsed > 0) (secs / elapsed).coerceIn(0.0, 1.0) else 0.0,
                gapTexts = if (valid.isEmpty()) emptyList() else gapTexts(data, gaps, valid, windows),
                anyUncalibrated = minutes.any { !it.calibrated },
                eventsPerHour = ch.lessnoise.dsp.Dynamics.eventsPerHour(floored.size, secs),
                dynamicsDb = ch.lessnoise.dsp.Dynamics.medianSpread(valid),
                anyRecalibrated = minutes.any { it.recalibrated },
                humDb = ch.lessnoise.dsp.Dynamics.medianL90(valid),
            )
        }

        /**
         * Gap descriptions. A gap at the start of the window is an interruption if there was data
         * shortly before the window (then its real start is shown, e.g. «unterbrochen 20:54–23:54»),
         * otherwise «Messung ab …». The same at the end.
         */
        private fun gapTexts(data: ChartData, gaps: List<Gap>, valid: List<MinuteRecord>, windows: Windows): List<String> {
            val w = data.window
            val fmt = TimeFmt(windows.zone, withWeekday = windows.crossesDates(w))
            val out = ArrayList<String>()
            for (g in gaps) {
                var s = g.span.startMs
                var e = g.span.endMs
                if (g.atWindowStart) {
                    val prev = data.prevValidEndMs
                    if (prev == null || w.startMs - prev > NEIGHBOUR_MS) { out += "Messung ab ${fmt.hm(valid.first().startEpochMs)}"; continue }
                    s = prev
                }
                if (g.atDataEnd) {
                    val next = data.nextValidStartMs
                    val lastEnd = valid.last().let { it.startEpochMs + Math.round(it.durationSeconds * 1000) }
                    if (next == null || next - w.endMs > NEIGHBOUR_MS || g.span.endMs < w.endMs) {
                        out += "Messung bis ${fmt.hm(lastEnd)}"; continue
                    }
                    e = next
                }
                out += "unterbrochen ${fmt.hm(s)}–${fmt.hm(e)}"
            }
            return out
        }
    }
}

/**
 * Time formatting for chart texts (local zone): «23:40», with [withWeekday] «Sa 23:40», with
 * [withDate] «Sa 10.10. 23:40».
 */
class TimeFmt(val zone: ZoneId, private val withWeekday: Boolean, private val withDate: Boolean = false) {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val hms = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private fun prefix(d: java.time.LocalDate): String = when {
        withDate -> "${Windows.weekday(d)} ${d.dayOfMonth}.${d.monthValue}. "
        withWeekday -> Windows.weekday(d) + " "
        else -> ""
    }
    fun hm(t: Long): String {
        val z = Instant.ofEpochMilli(t).atZone(zone)
        return prefix(z.toLocalDate()) + z.format(hm)
    }
    fun hms(t: Long): String {
        val z = Instant.ofEpochMilli(t).atZone(zone)
        return prefix(z.toLocalDate()) + z.format(hms)
    }
}

/** Number formatting as in the rest of the app: decimal comma («45,1»). */
object ChartFmt {
    private val locale = Locale.GERMANY
    fun db(v: Double, decimals: Int = 1): String =
        if (v.isNaN() || v.isInfinite()) "–" else String.format(locale, "%.${decimals}f", v)
    /** «60», «62,5»: whole values without decimals (the event floor moves in 0.5 dB steps). */
    fun dbCompact(v: Double): String = db(v, if (v % 1.0 == 0.0) 0 else 1)
    fun percent(v: Double): String = String.format(locale, "%.0f %%", v * 100)
    /** German decimal comma («5,5»), for the dynamics figures. */
    fun comma(v: Double, decimals: Int): String =
        if (v.isNaN() || v.isInfinite()) "–" else String.format(Locale.GERMANY, "%.${decimals}f", v)
    fun duration(seconds: Double): String = when {
        seconds < 60 -> String.format(locale, "%.1f s", seconds)
        else -> String.format(locale, "%d min %02d s", (seconds / 60).toInt(), (seconds % 60).toInt())
    }
}

/** Event categories as the chart names them (chip order). */
object ChartCategories {
    const val DEFAULT_HIGHLIGHT = "loud_vehicle"
    val all: List<Pair<String, String>> = listOf(
        "road_traffic" to "Strassenverkehr",
        "loud_vehicle" to "Töff & Poser",
        "rail_tram" to "Tram & Bahn",
        "aircraft" to "Flugzeug",
        "voices" to "Stimmen",
        "music" to "Musik",
        "construction" to "Baustelle",
        "unclassified" to "Sonstiges",
    )
    fun name(id: String?): String = when (id) {
        // Old rows from when the classifier could be switched off (up to 0.6.0).
        null -> "–"
        WindRule.CATEGORY -> WindRule.NAME_DE // the detector's wind flag, not a chip
        else -> all.firstOrNull { it.first == id }?.second ?: id
    }
}
