package ch.lessnoise.app.alert

import ch.lessnoise.app.service.LiveEvent
import ch.lessnoise.app.service.MeasurementNotification
import ch.lessnoise.app.service.MeasurementService
import ch.lessnoise.app.ui.LoudAlerts
import ch.lessnoise.app.ui.UNTERWEGS_BUTTON
import ch.lessnoise.app.ui.UNTERWEGS_HINT
import ch.lessnoise.app.ui.REPORT_SUGGESTED_MARK
import ch.lessnoise.app.ui.ReportPreset
import ch.lessnoise.app.ui.reportCategoryButtons
import ch.lessnoise.app.ui.reportMoreOpenAtStart
import ch.lessnoise.app.upload.ReportCategory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.6.3: «Unterwegs» and the «Sehr laut» notification. 0.8.4–0.9.4.5 Labor·Karte only (src/laborKarte, tested in
 * src/testLaborKarte); 0.9.4.6 «Unterwegs messen» in both editions with the map (src/karte, tested here in
 * src/testKarte, which runs for public and laborKarte).
 */
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
        assertEquals("Sehr laut: Lautes Fahrzeug, 84 dB", LoudAlertRule.title(ev(84.4)))
        assertEquals("Sehr laut: Strassenverkehr, 78 dB", LoudAlertRule.title(ev(77.6, "road_traffic")))
        assertEquals("Antippen zum Melden", LoudAlertRule.TEXT)
        assertEquals("Sehr laut", LoudAlertRule.CHANNEL_NAME)
        // The category names are the app's (categories.json name_de).
        val names = File(repo(), "android/app/src/main/assets/categories.json").readText()
        assertTrue(names.contains("\"name_de\": \"Lautes Fahrzeug\"") && names.contains("\"name_de\": \"Strassenverkehr\""))
        // 0.6.4: «Unterwegs» is a start mode; 0.8.3 a segment of the mode switch; 0.8.4–0.9.4.5 the Labor·Karte
        // button «Unterwegs aufnehmen»; 0.9.4.6 «Unterwegs messen» in both editions with the map (MeasureTextsTest).
        assertEquals("Unterwegs messen", UNTERWEGS_BUTTON)
        assertEquals("Mikrofon an. Mitteilung, wenn ein Fahrzeug sehr laut ist.", UNTERWEGS_HINT)
        // Only in the editions with the map the start with EXTRA_UNTERWEGS exists (the facade says AVAILABLE).
        assertTrue(LoudAlerts.AVAILABLE)
        assertTrue(ch.lessnoise.app.BuildConfig.FLAVOR in setOf("public", "laborKarte"))
    }

    @Test
    fun theTapBecomesAPrefilledReportAtTheEventsTime() {
        val p = LoudAlertIntent.preset(LoudAlertIntent.ACTION, t0, "loud_vehicle")
        // 0.9.1: Töff and Auto (was Töff and Poser; «Poser» is no longer offered).
        assertEquals(ReportPreset(t0, listOf(ReportCategory.MOTORRAD, ReportCategory.AUTO)), p)
        assertEquals(listOf(ReportCategory.AUTO, ReportCategory.LASTWAGEN), LoudAlertIntent.preset(LoudAlertIntent.ACTION, t0, "road_traffic")!!.suggested)
        // 0.7.7: aircraft would suggest «Flugzeug» (the alert itself still never fires for it, above).
        assertEquals(listOf(ReportCategory.FLUGZEUG), LoudAlertRule.suggested("aircraft"))
        assertEquals("Flugzeug", LoudAlertRule.categoryLabel("aircraft"))
        // 0.9.4.5: the detector's construction and rail_tram suggest the new Baustelle and Bahn (reached only if the
        // alert ever fires for them: CATEGORIES stays loud_vehicle, road_traffic).
        assertEquals(listOf(ReportCategory.BAHN), LoudAlertRule.suggested("rail_tram"))
        assertEquals(listOf(ReportCategory.BAUSTELLE), LoudAlertRule.suggested("construction"))
        assertEquals(setOf("loud_vehicle", "road_traffic"), LoudAlertRule.CATEGORIES)
        assertEquals(emptyList<ReportCategory>(), LoudAlertRule.suggested("voices"))
        // Marked «erkannt» where they stand, all four offered still there: nothing is picked silently.
        // 0.9.4.2: the order is fixed (Töff · Auto · «Andere» › Lastwagen · Flugzeug); a suggested Lastwagen or
        // Flugzeug opens the second row at the start, so its «erkannt» is seen.
        assertEquals("erkannt", REPORT_SUGGESTED_MARK)
        val vehicle = reportCategoryButtons(p!!.suggested, null, reportMoreOpenAtStart(p.suggested, null))
        assertEquals(listOf(true, true, false), vehicle.main.map { it.suggested })
        assertFalse(vehicle.expanded)
        val road = LoudAlertRule.suggested("road_traffic")
        val roadRows = reportCategoryButtons(road, null, reportMoreOpenAtStart(road, null))
        assertTrue(roadRows.expanded)
        assertEquals(listOf(false, true, false), roadRows.main.map { it.suggested })
        assertEquals(listOf(true, false, false, false, false, false), roadRows.more.map { it.suggested })
        assertTrue(reportMoreOpenAtStart(LoudAlertRule.suggested("aircraft"), null))
        for (s in listOf(p.suggested, road, LoudAlertRule.suggested("aircraft"), emptyList())) {
            val r = reportCategoryButtons(s, null, false)
            assertEquals(ReportCategory.OFFERED, (r.main + r.more).mapNotNull { it.category })
        }
        // 0.9.1: every suggestion is one the dialog offers (never «Poser» or «anderes Fahrzeug»).
        for (c in listOf("loud_vehicle", "road_traffic", "aircraft", "rail_tram", "construction", null)) {
            assertTrue(LoudAlertRule.suggested(c).all { it.offered }, "$c")
        }
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

    /**
     * 0.9.4.6 (brief A.5): an «Unterwegs messen» run alerts at a road_traffic event of 76 dB, not at 74 dB; a window
     * run with the phone lying still never alerts, however loud. Through the same call the watcher makes
     * (LoudAlertWatcher: shouldAlert with unterwegsNow(LiveState.unterwegs, LiveState.moving)). Runs in testPublic too.
     */
    @Test
    fun anUnterwegsRunAlertsAt76NotAt74AndAStillWindowRunNever() {
        fun alertsIn(run: ch.lessnoise.app.service.LiveState, e: LiveEvent, last: Long? = null) =
            LoudAlertRule.shouldAlert(e, LoudAlertRule.unterwegsNow(run.unterwegs, run.moving), run.running, last, e.startEpochMs + 3_000)
        val unterwegs = ch.lessnoise.app.service.LiveState(running = true, unterwegs = true)
        assertTrue(alertsIn(unterwegs, ev(76.0, "road_traffic")), "76 dB road_traffic, unterwegs")
        assertFalse(alertsIn(unterwegs, ev(74.0, "road_traffic")), "74 dB road_traffic, unterwegs")
        val window = ch.lessnoise.app.service.LiveState(running = true, unterwegs = false, moving = false)
        for (db in listOf(74.0, 76.0, 84.0, 110.0)) {
            for (c in listOf("road_traffic", "loud_vehicle")) assertFalse(alertsIn(window, ev(db, c)), "window, still: $db $c")
        }
        // The same window run while carried («Unterwegs erkannt») alerts like «Unterwegs messen».
        assertTrue(alertsIn(window.copy(moving = true), ev(76.0, "road_traffic")))
        assertFalse(alertsIn(window.copy(moving = true), ev(74.0, "road_traffic")))
        // Not without a run, not twice within 2 minutes.
        assertFalse(alertsIn(unterwegs.copy(running = false), ev(84.0, "road_traffic")))
        assertFalse(alertsIn(unterwegs, ev(84.0, "road_traffic"), last = t0))
        // This is the code the Online-Version runs too (0.9.4.6): the real facade, not a stand-in.
        assertTrue(LoudAlerts.AVAILABLE)
    }

    private fun repo(): File = listOf(File("../.."), File(".."), File(".")).first { File(it, "server/stadtlaerm_server").isDirectory }
}
