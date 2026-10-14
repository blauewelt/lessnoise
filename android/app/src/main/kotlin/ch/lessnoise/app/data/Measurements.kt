package ch.lessnoise.app.data

import ch.lessnoise.dsp.MeasurementSpan
import ch.lessnoise.dsp.MeasurementSplitter
import ch.lessnoise.dsp.geo.LocationNoise

/** A measurement as the «Messungen» screen shows it: its stored row with a resolved end. */
data class Measurement(
    val id: Long,
    val span: MeasurementSpan,
    /** The shifted point that is sent if this measurement is shared, else null. */
    val point: LocationNoise.Point?,
    /** The measurement that is running right now (its end is "now"). */
    val running: Boolean,
) {
    /** The hectare the map shows this measurement in, if it is shared. */
    val cell: String? get() = point?.cell
}

/** Pure rules for turning stored rows into measurements (unit-tested on the JVM). */
object MeasurementList {

    /**
     * [rows] in any order; [minutes] = (start, end) epoch ms of the stored minutes, in any order.
     * The minutes of a row are those starting from its start (with MeasurementSplitter's tolerance)
     * until the next row's start.
     *
     * End of a row: its `end_ms`; for the newest open row while a measurement [running], [nowMs];
     * for any other open row (the app was killed before «Stop») the end of its last minute.
     * Rows without a single minute are left out, except the running one. Oldest first.
     */
    fun resolve(rows: List<MeasurementEntity>, minutes: List<Pair<Long, Long>>, nowMs: Long, running: Boolean): List<Measurement> {
        if (rows.isEmpty()) return emptyList()
        val sorted = rows.sortedWith(compareBy({ it.startMs }, { it.id }))
        val starts = LongArray(sorted.size) { sorted[it].startMs }
        val lastEnd = LongArray(sorted.size) { Long.MIN_VALUE }
        val count = IntArray(sorted.size)
        for ((s, e) in minutes) {
            val i = MeasurementSplitter.ownerIndex(starts, s)
            if (i < 0) continue
            count[i]++
            if (e > lastEnd[i]) lastEnd[i] = e
        }
        val out = ArrayList<Measurement>(sorted.size)
        for ((i, r) in sorted.withIndex()) {
            val isRunning = running && r.endMs == null && i == sorted.lastIndex
            if (count[i] == 0 && !isRunning) continue
            val end = when {
                r.endMs != null -> r.endMs
                isRunning -> nowMs
                else -> lastEnd[i]
            }
            out += Measurement(r.id, MeasurementSpan(r.startMs, maxOf(end, r.startMs + 1)), r.sharedPoint(), isRunning)
        }
        return out
    }
}
