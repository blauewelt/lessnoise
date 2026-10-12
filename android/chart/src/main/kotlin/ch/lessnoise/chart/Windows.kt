package ch.lessnoise.chart

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The two views of one measurement (since 0.6.0; they replace the three fixed ranges of 0.5):
 * - [SPAN]: the measurement's own time span, start to stop.
 * - [PROFILE]: «Tagesverlauf», the level by hour of day across the whole measurement (all days of
 *   it laid over each other on one 0–24 h axis).
 */
enum class RangeMode { SPAN, PROFILE }

/**
 * The data window [startMs, endMs) of a chart: always the measurement's time span; [mode] says how
 * it is drawn. A running measurement's window ends at "now".
 */
data class TimeWindow(val mode: RangeMode, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    operator fun contains(t: Long): Boolean = t in startMs until endMs

    companion object {
        fun span(startMs: Long, endMs: Long) = TimeWindow(RangeMode.SPAN, startMs, endMs)
        fun profile(startMs: Long, endMs: Long) = TimeWindow(RangeMode.PROFILE, startMs, endMs)
    }
}

/** A labelled x position. */
data class AxisLabel(val ms: Long, val text: String)

/** A time span [startMs, endMs). */
data class Span(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** Axis and label arithmetic in the local [zone] (DST-aware). */
class Windows(val zone: ZoneId) {

    /**
     * «Sa 10.10. 14:05–16:30», across dates «Sa 10.10. 22:14 – So 11.10. 06:30». The same for both
     * views (the profile shows the same measurement).
     */
    fun label(w: TimeWindow): String {
        val a = Instant.ofEpochMilli(w.startMs).atZone(zone)
        val b = Instant.ofEpochMilli(w.endMs).atZone(zone)
        val first = "${weekday(a.toLocalDate())} ${a.dayOfMonth}.${a.monthValue}. ${hm(a)}"
        return if (a.toLocalDate() == b.toLocalDate()) "$first–${hm(b)}"
        else "$first – ${weekday(b.toLocalDate())} ${b.dayOfMonth}.${b.monthValue}. ${hm(b)}"
    }

    /** True if the window spans more than one local calendar date (then tooltips name the date). */
    fun crossesDates(w: TimeWindow): Boolean = localDate(w.startMs) != localDate(maxOf(w.startMs, w.endMs - 1))

    /** Grid step for a span: the smallest that gives at most [MAX_LABELS] labels. */
    fun stepMinutes(w: TimeWindow): Long {
        val minutes = w.durationMs / MINUTE_MS
        return STEPS_MIN.firstOrNull { minutes / it <= MAX_LABELS } ?: STEPS_MIN.last()
    }

    /** Vertical grid positions of the x axis (span: local wall-clock multiples of the step; profile: every 3 h). */
    fun gridTicks(w: TimeWindow): List<Long> =
        if (w.mode == RangeMode.PROFILE) (0..8).map { it * 3 * HOUR_MS } else spanTicks(w)

    /**
     * x-axis labels. Span: «14:15» for steps under an hour, the hour («22», «23», «0») up to 3 h,
     * the weekday at 00:00 and «12» at noon for 12 h, the weekday for a day or more. Profile:
     * 0, 3, … 24.
     */
    fun axisLabels(w: TimeWindow): List<AxisLabel> {
        if (w.mode == RangeMode.PROFILE) return gridTicks(w).mapIndexed { i, t -> AxisLabel(t, (i * 3).toString()) }
        val step = stepMinutes(w)
        return spanTicks(w).map { t ->
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val text = when {
                step < 60 -> hm(z)
                step <= 180 -> z.hour.toString()
                step == 720L && z.hour != 0 -> z.hour.toString()
                else -> weekday(z.toLocalDate())
            }
            AxisLabel(t, text)
        }
    }

    private fun spanTicks(w: TimeWindow): List<Long> {
        val step = stepMinutes(w)
        val out = ArrayList<Long>()
        // Walk local wall-clock time from the start of the first day; keep the multiples of the step.
        var z: ZonedDateTime = Instant.ofEpochMilli(w.startMs).atZone(zone).truncatedTo(ChronoUnit.DAYS)
        var guard = 0
        while (guard++ < 20_000) {
            val t = z.toInstant().toEpochMilli()
            if (t > w.endMs) break
            val minuteOfDay = z.hour * 60L + z.minute
            val aligned = if (step >= 1440) minuteOfDay == 0L && dayAligned(z.toLocalDate(), step) else minuteOfDay % step == 0L
            // A DST fall-back hour repeats a wall-clock time; both instants are real ticks.
            if (t >= w.startMs && aligned && (out.isEmpty() || out.last() != t)) out += t
            z = if (step >= 1440) z.plusDays(1) else z.plusMinutes(minOf(step, 60L))
        }
        return out
    }

    /** Multi-day steps: every 2nd / 7th day counted from the epoch day, so ticks do not jump while a measurement grows. */
    private fun dayAligned(d: LocalDate, stepMinutes: Long): Boolean = d.toEpochDay() % (stepMinutes / 1440) == 0L

    fun localDate(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    private fun hm(z: ZonedDateTime): String = "%02d:%02d".format(z.hour, z.minute)

    companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        /** At most this many x labels on a span. */
        const val MAX_LABELS = 9L
        private val STEPS_MIN = longArrayOf(15, 30, 60, 180, 720, 1440, 2880, 10080)
        private val WEEKDAYS = arrayOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        fun weekday(d: LocalDate): String = WEEKDAYS[d.dayOfWeek.value - 1]

        /** The x domain of the «Tagesverlauf»: one day, 0–24 h, drawn in UTC so that 14:00 is at 14 h. */
        val PROFILE_ZONE: ZoneId = ZoneOffset.UTC
        val PROFILE_WINDOW = TimeWindow(RangeMode.PROFILE, 0L, DAY_MS)
    }
}
