package ch.lessnoise.app.alert

import ch.lessnoise.app.service.LiveEvent
import ch.lessnoise.app.ui.ReportPreset
import ch.lessnoise.app.upload.ReportCategory
import kotlin.math.roundToInt

// «Unterwegs» and the «Sehr laut» notification (0.6.3). Since 0.8.4 compiled only into Labor·Karte
// (src/laborKarte; until 0.8.3 src/karte, also the Online-Version); pure, so that LoudAlertTest
// (src/testLaborKarte) checks the rules on the JVM.

/** When a detected event alerts, and what the notification says. */
object LoudAlertRule {
    /**
     * «Sehr laut»: LAFmax from this level, the map's loud threshold (server/stadtlaerm_server/
     * aggregate.py DEFAULT_LOUD_EVENT_DB = 75.0, published as cells.json `loud_event_db`). The app
     * has no copy of that constant elsewhere; LoudAlertTest reads the server's value from the source.
     */
    const val THRESHOLD_DB = 75.0

    /** «Lautes Fahrzeug» and «Strassenverkehr» (categories.json ids). */
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

    /**
     * Whether the running measurement counts as «Unterwegs» for the alert: started with «Unterwegs»
     * (0.6.4), or (0.6.7, «Unterwegs erkannt») started with «Messen» while the motion detector says
     * the phone is being carried ([moving], LiveState.moving). Everything else of the rule is the same.
     */
    fun unterwegsNow(unterwegs: Boolean, moving: Boolean): Boolean = unterwegs || moving

    /** «Lautes Fahrzeug» / «Strassenverkehr» (the names of categories.json; renamed in 0.9.3). */
    fun categoryLabel(category: String?): String = when (category) {
        "loud_vehicle" -> "Lautes Fahrzeug"
        "road_traffic" -> "Strassenverkehr"
        "aircraft" -> "Flugzeug"
        else -> "Lärm"
    }

    /** «Sehr laut: Lautes Fahrzeug, 84 dB». */
    fun title(e: LiveEvent): String = "Sehr laut: ${categoryLabel(e.category)}, ${e.lafMaxDb.roundToInt()} dB"

    /**
     * The report categories that fit the detected sound (first in the grid, marked «erkannt»). 0.9.1:
     * only offered ones (ReportCategory.OFFERED); a loud vehicle suggests Töff and Auto (was Töff and Poser).
     */
    fun suggested(category: String?): List<ReportCategory> = when (category) {
        "loud_vehicle" -> listOf(ReportCategory.MOTORRAD, ReportCategory.AUTO)
        "road_traffic" -> listOf(ReportCategory.AUTO, ReportCategory.LASTWAGEN)
        // 0.7.7: only reached if the alert ever fires for aircraft; CATEGORIES does not include it.
        "aircraft" -> listOf(ReportCategory.FLUGZEUG)
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
