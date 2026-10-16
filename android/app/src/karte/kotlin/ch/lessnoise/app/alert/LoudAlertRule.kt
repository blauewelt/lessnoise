package ch.lessnoise.app.alert

import ch.lessnoise.app.service.LiveEvent
import ch.lessnoise.app.ui.ReportPreset
import ch.lessnoise.app.upload.ReportCategory
import kotlin.math.roundToInt

// «Unterwegs» and the «Sehr laut» notification (0.6.3). Compiled only into the editions with the
// map (src/karte, flavours public and laborKarte); pure, so that LoudAlertTest (src/testKarte)
// checks the rules on the JVM in both.

/** When a detected event alerts, and what the notification says. */
object LoudAlertRule {
    /**
     * «Sehr laut»: LAFmax from this level, the map's loud threshold (server/stadtlaerm_server/
     * aggregate.py DEFAULT_LOUD_EVENT_DB = 75.0, published as cells.json `loud_event_db`). The app
     * has no copy of that constant elsewhere; LoudAlertTest reads the server's value from the source.
     */
    const val THRESHOLD_DB = 75.0

    /** «Töff & Poser» and «Strassenverkehr» (categories.json ids). */
    val CATEGORIES: Set<String> = setOf("loud_vehicle", "road_traffic")

    /** At most one notification per … */
    const val MIN_GAP_MS = 2 * 60_000L

    /** The notification's text. */
    const val TEXT = "Antippen zum Melden"

    /** The channel's name in Android's settings. */
    const val CHANNEL_NAME = "Sehr laut"

    /**
     * Whether [e] alerts now ([nowMs]): the running measurement started as «Unterwegs» (0.6.4; in
     * 0.6.3 a switch), a measurement running, not wind, one of
     * [CATEGORIES], LAFmax ≥ [THRESHOLD_DB] (the level as shown: calibrated if the phone is, else
     * with the default offset), and the last alert [MIN_GAP_MS] or longer ago.
     */
    fun shouldAlert(e: LiveEvent, unterwegs: Boolean, running: Boolean, lastAlertAtMs: Long?, nowMs: Long): Boolean =
        unterwegs && running && !e.wind && e.category in CATEGORIES && e.lafMaxDb >= THRESHOLD_DB &&
            (lastAlertAtMs == null || nowMs - lastAlertAtMs >= MIN_GAP_MS)

    /** «Töff & Poser» / «Strassenverkehr» (the names of categories.json). */
    fun categoryLabel(category: String?): String = when (category) {
        "loud_vehicle" -> "Töff & Poser"
        "road_traffic" -> "Strassenverkehr"
        else -> "Lärm"
    }

    /** «Sehr laut: Töff & Poser, 84 dB». */
    fun title(e: LiveEvent): String = "Sehr laut: ${categoryLabel(e.category)}, ${e.lafMaxDb.roundToInt()} dB"

    /** The report categories that fit the detected sound (highlighted in «Was war laut?»). */
    fun suggested(category: String?): List<ReportCategory> = when (category) {
        "loud_vehicle" -> listOf(ReportCategory.MOTORRAD, ReportCategory.POSER)
        "road_traffic" -> listOf(ReportCategory.AUTO, ReportCategory.LASTWAGEN)
        else -> emptyList()
    }
}

/** What the notification's tap carries to MainActivity, and how it becomes a prefilled «Laut! Melden». */
object LoudAlertIntent {
    const val ACTION = "ch.lessnoise.app.action.REPORT_LOUD"
    const val EXTRA_EVENT_MS = "ch.lessnoise.app.extra.EVENT_MS"
    const val EXTRA_CATEGORY = "ch.lessnoise.app.extra.CATEGORY"

    /** The report to start: the event's time (its start) and the suggested categories; null if this is not the notification's tap. */
    fun preset(action: String?, eventMs: Long, category: String?): ReportPreset? =
        if (action != ACTION || eventMs <= 0) null else ReportPreset(eventMs, LoudAlertRule.suggested(category))
}
