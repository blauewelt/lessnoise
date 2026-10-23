package ch.lessnoise.app.labor

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// LABOR BUILD ONLY (app/src/labor/).

/**
 * The storage cap: audio recording stops (measuring goes on) once the audio folder would exceed
 * [maxBytes]. Latching: once [full], it stays full for the rest of the session. Thread-safe.
 *
 * 0.7.5: the cap is an **audio** cap. The manifest and the classifier logs (≈ 1 MB per hour) go on
 * past it, up to [LOG_MARGIN_BYTES] beyond [maxBytes] ([logsFull]); before 0.7.5 a full cap also
 * silenced the manifest (no `event` lines for five days, 2026-10-18 → 23). A bounded margin rather
 * than an exemption, so that the folder can never grow without limit.
 */
class StorageBudget(val maxBytes: Long, initialUsedBytes: Long, val logMarginBytes: Long = LOG_MARGIN_BYTES) {
    var usedBytes: Long = initialUsedBytes
        @Synchronized get
        private set
    /** Audio (clips, continuous) no longer fits. */
    @Volatile var full: Boolean = initialUsedBytes >= maxBytes
        private set
    /** Even the logs no longer fit (cap + [logMarginBytes]). */
    @Volatile var logsFull: Boolean = initialUsedBytes >= maxBytes + logMarginBytes
        private set

    /** Reserves [bytes] of audio before writing them; false (and full from now on) if they do not fit. */
    @Synchronized
    fun tryReserve(bytes: Long): Boolean {
        if (full) return false
        if (usedBytes + bytes > maxBytes) { full = true; return false }
        usedBytes += bytes
        return true
    }

    /** Accounts for audio [bytes] already written (encoder output); false if that crossed the cap. */
    @Synchronized
    fun account(bytes: Long): Boolean {
        usedBytes += bytes
        if (usedBytes > maxBytes) full = true
        if (usedBytes > maxBytes + logMarginBytes) logsFull = true
        return !full
    }

    /**
     * Accounts for log [bytes] (manifest, classifier log) already written. They count towards the
     * audio cap too, but are allowed [logMarginBytes] beyond it; false once that is used up.
     */
    @Synchronized
    fun accountLog(bytes: Long): Boolean {
        usedBytes += bytes
        if (usedBytes > maxBytes) full = true
        if (usedBytes > maxBytes + logMarginBytes) logsFull = true
        return !logsFull
    }

    companion object {
        const val GB = 1_000_000_000L
        val CHOICES_GB = listOf(0.5, 1.0, 2.0, 5.0, 10.0)
        /** 0.7.5: 5 GB for new installs (was 2 GB; an existing setting keeps its value). */
        const val DEFAULT_GB = 5.0
        /** How far the manifest and classifier logs may go beyond the cap (≈ 200 h of logging). */
        const val LOG_MARGIN_BYTES = 200_000_000L
    }
}

/**
 * 0.7.5: what goes to the manifest and the classifier log once the audio budget is used up. Every
 * line counts towards the budget ([StorageBudget.accountLog]); once the log margin is used up only
 * [essential] lines (session start/stop, storage_full, logs_full, the close of an hour file) are
 * written, the others are counted in [dropped]. One per session, used on the recorder's writer thread.
 */
class LogGate(private val budget: StorageBudget) {
    var dropped = 0L
        private set

    /** Writes one line through [write] (which returns the bytes written) unless the margin is used up. */
    fun write(essential: Boolean, write: () -> Long): Boolean {
        if (!essential && budget.logsFull) { dropped++; return false }
        budget.accountLog(write())
        return true
    }
}

/**
 * 0.7.5: the warning before the cap and the block at it, from what the folder holds. Pure, so that
 * PoliciesAndManifestTest drives it; the Labor settings section, the «Messen» screen and the start
 * gate use it.
 */
object StoragePolicy {
    enum class Level { NONE, WARN, BLOCK }

    /** At least this much must be free, else [Level.WARN]. */
    const val WARN_MIN_FREE_BYTES = 200_000_000L
    /** … or this share of the cap, whichever is larger. */
    const val WARN_FREE_SHARE = 0.10

    fun warnBelowBytes(maxBytes: Long): Long = maxOf(WARN_MIN_FREE_BYTES, (maxBytes * WARN_FREE_SHARE).toLong())

    fun freeBytes(usedBytes: Long, maxBytes: Long): Long = maxOf(0L, maxBytes - usedBytes)

    /**
     * Nothing while audio recording is off (the cap applies to audio; [recordAudio] is
     * [recordsAudio] of the settings). [Level.BLOCK] once no audio
     * fits any more («Messen» must not start without the audio the owner asked for), [Level.WARN]
     * below [warnBelowBytes].
     */
    fun level(usedBytes: Long, maxBytes: Long, recordAudio: Boolean): Level = when {
        !recordAudio || maxBytes <= 0L -> Level.NONE
        usedBytes >= maxBytes -> Level.BLOCK
        freeBytes(usedBytes, maxBytes) < warnBelowBytes(maxBytes) -> Level.WARN
        else -> Level.NONE
    }

    /** The red line for [level], or null for [Level.NONE]. */
    fun message(usedBytes: Long, maxBytes: Long, recordAudio: Boolean): String? = when (level(usedBytes, maxBytes, recordAudio)) {
        Level.NONE -> null
        Level.WARN ->
            "Speicher fast voll: ${mbLabel(freeBytes(usedBytes, maxBytes))} frei von ${gbLabel(maxBytes)} – Aufnahme würde bald stoppen. " +
                "Exportieren und löschen, oder Limit erhöhen."
        Level.BLOCK ->
            "Speicher voll: ${mbLabel(freeBytes(usedBytes, maxBytes))} frei von ${gbLabel(maxBytes)} – Messen startet nicht ohne Platz für die Aufnahme. " +
                "Exportieren und löschen, Limit erhöhen oder Aufnahme ausschalten."
    }

    fun mbLabel(bytes: Long): String = "${(bytes + 500_000L) / 1_000_000L} MB"

    /** «5 GB», «0,5 GB» (as the «Maximaler Speicher» buttons). */
    fun gbLabel(bytes: Long): String {
        val gb = bytes / StorageBudget.GB.toDouble()
        return if (gb == Math.floor(gb)) "${gb.toLong()} GB" else "${String.format(java.util.Locale.GERMANY, "%.1f", gb)} GB"
    }

    /** «Audio aufzeichnen» on with something to record: the cap matters (neither clips nor hour files: no audio). */
    fun recordsAudio(s: LaborSettings): Boolean = s.recordAudio && (s.clips || s.continuous)
}

/** «Clip-Rate»: keep a clip for every [every]-th confirmed event (the 1st, then every n-th). */
class ClipRateSampler(val every: Int) {
    init { require(every >= 1) }
    var seen = 0L
        private set
    var skipped = 0L
        private set

    fun next(): Boolean {
        val keep = seen % every == 0L
        seen++
        if (!keep) skipped++
        return keep
    }

    companion object {
        val CHOICES = listOf(1, 2, 5)
    }
}

/**
 * Hourly file rotation for the continuous recording: a new file at every local wall-clock hour
 * (and at measurement start). Hours are keyed with their UTC offset, so the repeated hour at the
 * end of daylight saving time gets a file of its own.
 */
class HourRoller(private val zone: ZoneId) {
    private var currentKey: OffsetDateTime? = null

    fun hourKey(epochMs: Long): OffsetDateTime =
        Instant.ofEpochMilli(epochMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).toOffsetDateTime()

    /** True if a block delivered at [epochMs] belongs into a new file (first call: always). */
    fun needsNewFile(epochMs: Long): Boolean {
        val k = hourKey(epochMs)
        if (k == currentKey) return false
        currentKey = k
        return true
    }

    /** Start of the next hour after [epochMs], epoch ms. */
    fun nextBoundaryMs(epochMs: Long): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()

    fun baseName(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(zone).format(HOUR_NAME)

    companion object {
        val HOUR_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HH")
        val SECOND_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** `base.ext`, or `base_2.ext`, `base_3.ext` … if taken. */
        fun unique(base: String, ext: String, exists: (String) -> Boolean): String {
            var name = "$base.$ext"
            var i = 2
            while (exists(name)) { name = "${base}_$i.$ext"; i++ }
            return name
        }
    }
}
