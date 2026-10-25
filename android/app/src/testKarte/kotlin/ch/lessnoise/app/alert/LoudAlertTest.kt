package ch.lessnoise.app.alert

import ch.lessnoise.app.service.LiveEvent
import ch.lessnoise.app.service.MeasurementNotification
import ch.lessnoise.app.service.MeasurementService
import ch.lessnoise.app.ui.MODE_ROAD
import ch.lessnoise.app.ui.MODE_WINDOW
import ch.lessnoise.app.ui.UNTERWEGS_HINT
import ch.lessnoise.app.ui.REPORT_SUGGESTED_MARK
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
        LiveEvent(
            startEpochMs = start, endEpochMs = start + 3_000, lafMaxDb = db, category = category, wind = wind, calibrated = true,
            thresholdDb = 10.0, minLevelDb = 30.0,
        )

    private fun alerts(e: LiveEvent, unterwegs: Boolean = true, running: Boolean = true, last: Long? = null, now: Long = t0 + 3_000) =
        LoudAlertRule.shouldAlert(e, unterwegs, running, last, now)

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
    fun onlyInAnUnterwegsMeasurementWhileMeasuring() {
        // 0.6.4: «unterwegs» is LiveState.unterwegs (the start mode), which the watcher passes; a
        // measurement started with «Messen» never alerts while the phone lies still.
        val watcher = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/alert/LoudAlertNotifier.kt").readText()
        // 0.6.7: or a «Messen» measurement while the phone is being carried (MotionDetectorTest).
        assertTrue(watcher.contains("LoudAlertRule.shouldAlert(e, LoudAlertRule.unterwegsNow(l.unterwegs, l.moving), l.running, lastAlertAtMs, now)"))
        assertFalse(watcher.contains("SharedPreferences"), "no remembered switch any more")
        assertFalse(alerts(ev(), unterwegs = false))
        assertFalse(alerts(ev(), running = false))
        assertTrue(alerts(ev(), unterwegs = true, running = true))
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
        // 0.6.4: «Unterwegs» is a start mode; since 0.8.3 a segment of the mode switch («Am Fenster» |
        // «Unterwegs»), with one line under «Messung starten» (MeasureModeTest).
        assertEquals("Unterwegs", MODE_ROAD)
        assertEquals("Am Fenster", MODE_WINDOW)
        assertTrue(UNTERWEGS_HINT.length <= 90 && UNTERWEGS_HINT.contains("sehr lautem Verkehr") && UNTERWEGS_HINT.contains("Nichts kommt auf die Karte"), UNTERWEGS_HINT)
    }

    @Test
    fun theTapBecomesAPrefilledReportAtTheEventsTime() {
        val p = LoudAlertIntent.preset(LoudAlertIntent.ACTION, t0, "loud_vehicle")
        assertEquals(ReportPreset(t0, listOf(ReportCategory.MOTORRAD, ReportCategory.POSER)), p)
        assertEquals(listOf(ReportCategory.AUTO, ReportCategory.LASTWAGEN), LoudAlertIntent.preset(LoudAlertIntent.ACTION, t0, "road_traffic")!!.suggested)
        // 0.7.7: aircraft would suggest «Flugzeug» (the alert itself still never fires for it, above).
        assertEquals(listOf(ReportCategory.FLUGZEUG), LoudAlertRule.suggested("aircraft"))
        assertEquals("Flugzeug", LoudAlertRule.categoryLabel("aircraft"))
        assertEquals(emptyList<ReportCategory>(), LoudAlertRule.suggested("rail_tram"))
        // Suggested first and marked «erkannt», all six still there: nothing is picked silently.
        assertEquals("erkannt", REPORT_SUGGESTED_MARK)
        assertEquals(
            listOf(ReportCategory.MOTORRAD, ReportCategory.POSER, ReportCategory.AUTO, ReportCategory.LASTWAGEN, ReportCategory.FLUGZEUG, ReportCategory.ANDERE),
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
        assertTrue(notifier.contains("NotificationManager.IMPORTANCE_HIGH"))
        assertFalse(notifier.contains("setDeleteIntent"), "swiping it away does nothing")
    }

    @Test
    fun oneNotificationTheAlertReplacesTheMeasurementsOwnFor30s() {
        // 0.7.6: no second notification (id 7501 until 0.7.5); the notifier hands the alert to the
        // running service, which posts notification 1 once on the HIGH channel, then the level text.
        val notifier = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/alert/LoudAlertNotifier.kt").readText()
        val code = notifier.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse(code.contains(".notify("), "the notifier posts nothing itself")
        assertFalse(code.contains("7501"))
        assertFalse(code.contains("NotificationCompat"))
        assertTrue(code.contains("context.startService("))
        assertTrue(code.contains(".setAction(MeasurementService.ACTION_LOUD_ALERT)"))
        assertTrue(code.contains(".putExtra(MeasurementService.EXTRA_ALERT_TITLE, LoudAlertRule.title(e))"))
        assertTrue(code.contains(".putExtra(MeasurementService.EXTRA_ALERT_CHANNEL, CHANNEL)"))
        assertTrue(code.contains(".putExtra(MeasurementService.EXTRA_ALERT_TAP, pending)"))
        assertTrue(code.contains("runCatching {"), "startService may throw when the service is not in the foreground")
        assertTrue(code.contains("ensureChannel()"), "the HIGH channel still exists: the alert post uses it")
        // The service: one alerting post on id 1 (not startForeground), the window of 30 s.
        val service = File(repo(), "android/app/src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains("ACTION_LOUD_ALERT -> { showLoudAlert(intent, startId); return START_NOT_STICKY }"))
        assertTrue(service.contains("nm.notify(NOTIFICATION_ID, alertNotification(channel, title, tapIntent, now))"))
        assertTrue(service.contains("if (!cadence.alertUntil(now, now + LOUD_ALERT_WINDOW_MS)) return"))
        assertTrue(service.contains("if (!live.running || stopping) {"), "ignored without a running measurement")
        assertEquals(30_000L, MeasurementService.LOUD_ALERT_WINDOW_MS)
        val alert = service.substring(service.indexOf("private fun alertNotification("), service.indexOf("private fun notification("))
        assertTrue(alert.contains("NotificationCompat.Builder(this, channel)"))
        assertTrue(alert.contains(".setOnlyAlertOnce(false)"))
        assertFalse(alert.contains("setSilent"), "this one post must make the heads-up")
        assertTrue(alert.contains(".setOngoing(true)"))
        assertTrue(alert.contains(".setContentIntent(tapIntent)"))
        assertTrue(alert.contains(".addAction(0, \"Stopp\", stop)"))
        assertTrue(alert.contains(".addAction(0, MeasurementNotification.ALERT_BUTTON, tapIntent)"))
        assertTrue(alert.contains("FOREGROUND_SERVICE_IMMEDIATE"))
        assertFalse(alert.contains("startForeground"))
        // The tap watcher does not post over an open alert; the level posts restore the notification after it.
        assertTrue(service.contains("if (cadence.alertOpen(System.currentTimeMillis())) return@collect"))
        assertTrue(service.contains("cadence.endAlert()"))
        // The texts in src/main agree with the rule's and the report button's.
        assertEquals(LoudAlertRule.TEXT, MeasurementNotification.ALERT_TEXT)
        assertEquals(ReportTap.BUTTON, MeasurementNotification.ALERT_BUTTON)
    }

    private fun repo(): File = listOf(File("../.."), File(".."), File(".")).first { File(it, "server/stadtlaerm_server").isDirectory }
}
