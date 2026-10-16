package ch.lessnoise.app.alert

import ch.lessnoise.app.service.LiveEvent
import ch.lessnoise.app.ui.LoudAlerts
import ch.lessnoise.app.ui.ReportPreset
import ch.lessnoise.app.ui.reportCategoryOrder
import ch.lessnoise.app.upload.ReportCategory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.3: «Unterwegs» and the «Sehr laut» notification (editions with the map; src/testKarte runs for public and laborKarte). */
class LoudAlertTest {
    private val min = 60_000L
    private val t0 = 1_760_000_000_000L

    private fun ev(db: Double = 84.0, category: String? = "loud_vehicle", wind: Boolean = false, start: Long = t0) =
        LiveEvent(startEpochMs = start, endEpochMs = start + 3_000, lafMaxDb = db, category = category, wind = wind, calibrated = true)

    private fun alerts(e: LiveEvent, unterwegs: Boolean = true, running: Boolean = true, moved: Boolean = true, last: Long? = null, now: Long = t0 + 3_000) =
        LoudAlertRule.shouldAlert(e, unterwegs, running, moved, last, now)

    @Test
    fun thresholdIsTheMapsLoudLevel() {
        assertTrue(alerts(ev(75.0)))
        assertTrue(alerts(ev(84.0)))
        assertFalse(alerts(ev(74.9)))
        // The same 75 dB(A) as the server's «Sehr laut» (cells.json loud_event_db).
        val server = File(repo(), "server/stadtlaerm_server/aggregate.py").readText()
        val v = Regex("""DEFAULT_LOUD_EVENT_DB\s*=\s*([0-9.]+)""").find(server)!!.groupValues[1].toDouble()
        assertEquals(v, LoudAlertRule.THRESHOLD_DB)
    }

    @Test
    fun onlyTraffickCategoriesAndNeverWind() {
        assertTrue(alerts(ev(category = "loud_vehicle")))
        assertTrue(alerts(ev(category = "road_traffic")))
        for (c in listOf("rail_tram", "aircraft", "voices", "music", "unclassified", "wind", null)) assertFalse(alerts(ev(category = c)), "$c")
        assertFalse(alerts(ev(wind = true)), "wind on the microphone, even if classified as a vehicle")
    }

    @Test
    fun onlyWithUnterwegsOnAndWhileMeasuring() {
        assertFalse(alerts(ev(), unterwegs = false))
        assertFalse(alerts(ev(), running = false))
        assertTrue(alerts(ev(), unterwegs = true, running = true))
    }

    @Test
    fun onlyWhenThePhoneMovedRecently() {
        // The motion gate: a phone on the windowsill (not moved for 5 minutes) stays silent.
        assertTrue(alerts(ev(), moved = true))
        assertFalse(alerts(ev(), moved = false))
        assertFalse(alerts(ev(84.0, "road_traffic"), moved = false))
        // Moving does not lift any other condition.
        assertFalse(alerts(ev(), moved = true, unterwegs = false))
        assertFalse(alerts(ev(), moved = true, running = false))
        assertFalse(alerts(ev(74.9), moved = true))
        assertFalse(alerts(ev(wind = true), moved = true))
        assertFalse(alerts(ev(category = "rail_tram"), moved = true))
        assertFalse(alerts(ev(), moved = true, last = t0, now = t0 + 60_000))
        // The watcher passes the monitor's answer; the monitor opens the gate without an accelerometer.
        val notifier = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/alert/LoudAlertNotifier.kt").readText()
        assertTrue(notifier.contains("live.value.running, motion.movedRecently(), lastAlertAtMs, now)"))
        assertTrue(notifier.contains("combine(settings.on, live.map { it.running }) { on, running -> on && running }"))
        val monitor = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/alert/MotionMonitor.kt").readText()
        assertTrue(monitor.contains("fun movedRecently(): Boolean = !hasSensor || detector.isMoving("))
        assertTrue(monitor.contains("SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US"))
    }

    @Test
    fun atMostOneEveryTwoMinutes() {
        val last = t0
        assertFalse(alerts(ev(), last = last, now = last + 30_000))
        assertFalse(alerts(ev(), last = last, now = last + 2 * min - 1))
        assertTrue(alerts(ev(), last = last, now = last + 2 * min))
        assertEquals(2 * min, LoudAlertRule.MIN_GAP_MS)
    }

    @Test
    fun notificationContent() {
        assertEquals("Sehr laut: Töff & Poser, 84 dB", LoudAlertRule.title(ev(84.4)))
        assertEquals("Sehr laut: Strassenverkehr, 78 dB", LoudAlertRule.title(ev(77.6, "road_traffic")))
        assertEquals("Antippen zum Melden", LoudAlertRule.TEXT)
        assertEquals("Sehr laut", LoudAlertRule.CHANNEL_NAME)
        // The category names are the app's (categories.json name_de).
        val names = File(repo(), "android/app/src/main/assets/categories.json").readText()
        assertTrue(names.contains("\"name_de\": \"Töff & Poser\"") && names.contains("\"name_de\": \"Strassenverkehr\""))
        assertTrue(LoudAlerts.SWITCH_LABEL == "Unterwegs" && LoudAlerts.SWITCH_HINT.length <= 120)
        assertTrue(LoudAlerts.SWITCH_HINT.endsWith("Nur wenn sich das Telefon bewegt, nicht am Fenster."))
    }

    @Test
    fun theTapBecomesAPrefilledReportAtTheEventsTime() {
        val p = LoudAlertIntent.preset(LoudAlertIntent.ACTION, t0, "loud_vehicle")
        assertEquals(ReportPreset(t0, listOf(ReportCategory.MOTORRAD, ReportCategory.POSER)), p)
        assertEquals(listOf(ReportCategory.AUTO, ReportCategory.LASTWAGEN), LoudAlertIntent.preset(LoudAlertIntent.ACTION, t0, "road_traffic")!!.suggested)
        // Suggested first and highlighted, all five still there: nothing is picked silently.
        assertEquals(
            listOf(ReportCategory.MOTORRAD, ReportCategory.POSER, ReportCategory.AUTO, ReportCategory.LASTWAGEN, ReportCategory.ANDERE),
            reportCategoryOrder(p!!.suggested),
        )
        assertEquals(ReportCategory.entries.toList(), reportCategoryOrder(emptyList()))
        // Any other intent (the launcher, the measurement's notification): no report.
        assertNull(LoudAlertIntent.preset(null, t0, "loud_vehicle"))
        assertNull(LoudAlertIntent.preset("android.intent.action.MAIN", t0, "loud_vehicle"))
        assertNull(LoudAlertIntent.preset(LoudAlertIntent.ACTION, 0, "loud_vehicle"))
        // The notifier puts the event's START into the intent (the report's time), not the tap.
        val notifier = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/alert/LoudAlertNotifier.kt").readText()
        assertTrue(notifier.contains(".putExtra(LoudAlertIntent.EXTRA_EVENT_MS, e.startEpochMs)"))
        assertTrue(notifier.contains("NotificationManager.IMPORTANCE_HIGH") && notifier.contains(".setAutoCancel(true)"))
        assertFalse(notifier.contains("setDeleteIntent"), "swiping it away does nothing")
    }

    private fun repo(): File = listOf(File("../.."), File(".."), File(".")).first { File(it, "server/stadtlaerm_server").isDirectory }
}
