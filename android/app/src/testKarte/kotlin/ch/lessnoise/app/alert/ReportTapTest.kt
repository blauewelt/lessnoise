package ch.lessnoise.app.alert

import ch.lessnoise.app.service.NotificationCadence
import ch.lessnoise.app.ui.ReportPreset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.8: «Antippen zum Melden» on the measurement notification while «unterwegs» (src/testKarte: public and laborKarte). */
class ReportTapTest {
    private val t0 = 1_760_000_000_000L

    @Test
    fun theNotificationOpensTheReportOnlyWhileUnterwegs() {
        // «Messen», detector STILL: as before 0.6.8 (level text, tap opens the app).
        assertNull(ReportTap.forState(running = true, unterwegs = false, moving = false))
        // «Messen», detector MOVING; and a run started with «Unterwegs» (with or without motion).
        val tap = ReportTap.Tap("Antippen zum Melden", "ch.lessnoise.app.action.REPORT_NOW", 3)
        assertEquals(tap, ReportTap.forState(running = true, unterwegs = false, moving = true))
        assertEquals(tap, ReportTap.forState(running = true, unterwegs = true, moving = false))
        assertEquals(tap, ReportTap.forState(running = true, unterwegs = true, moving = true))
        // No run: nothing.
        assertNull(ReportTap.forState(running = false, unterwegs = true, moving = true))
        assertEquals(LoudAlertRule.TEXT, ReportTap.TEXT)
    }

    @Test
    fun itsIntentIsDistinctFromTheSehrLautTap() {
        assertNotEquals(LoudAlertIntent.ACTION, ReportTap.ACTION)
        // Request codes: service 0 (open) and 1 (Stopp), «Sehr laut» 2, this one 3.
        val service = File(repo(), "android/app/src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains("PendingIntent.getActivity(\n            this, 0,"))
        assertTrue(service.contains("PendingIntent.getService(\n            this, 1,"))
        val notifier = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/alert/LoudAlertNotifier.kt").readText()
        assertTrue(notifier.contains("private const val REQUEST = 2"))
        assertEquals(3, ReportTap.REQUEST)
        // The «Sehr laut» tap is not a report-now tap and vice versa.
        assertNull(ReportTap.preset(LoudAlertIntent.ACTION, t0))
        assertNull(LoudAlertIntent.preset(ReportTap.ACTION, t0, "loud_vehicle"))
        // The facade builds the PendingIntent from the rule's action and request code, single-top
        // (an open app gets it in onNewIntent).
        val facade = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
        assertTrue(facade.contains(".setAction(t.action)"))
        assertTrue(facade.contains("context, t.requestCode, intent,"))
        assertTrue(facade.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertTrue(facade.contains("ReportTap.preset(intent.action, tapMs)?.let { return it }"))
    }

    @Test
    fun theTapBecomesAReportAtTheTapsTimeWithNothingPreselected() {
        assertEquals(ReportPreset(t0, emptyList()), ReportTap.preset(ReportTap.ACTION, t0))
        assertNull(ReportTap.preset(null, t0))
        assertNull(ReportTap.preset("android.intent.action.MAIN", t0))
        assertNull(ReportTap.preset(ReportTap.ACTION, 0))
        // The time is taken when MainActivity receives the intent (onCreate and onNewIntent), not when
        // the notification was built: the intent carries no time at all.
        val main = File(repo(), "android/app/src/main/kotlin/ch/lessnoise/app/ui/MainActivity.kt").readText()
        assertTrue(main.contains("LoudAlerts.presetFrom(intent, System.currentTimeMillis())"))
        assertTrue(main.contains("override fun onNewIntent(intent: Intent) {\n        super.onNewIntent(intent)\n        takeAlert(intent)"))
        val facade = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
        val build = facade.substring(facade.indexOf("fun reportTap("), facade.indexOf("fun presetFrom("))
        assertFalse(build.contains("putExtra"), "no time or category in the report-now intent")
        val manifest = File(repo(), "android/app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:launchMode=\"singleTop\""))
    }

    @Test
    fun aStateChangeRePostsTheNotificationOnceAndNotPerSecond() {
        // The service's collector: distinct «running && (unterwegs || moving)» → reportTap → cadence.
        val service = File(repo(), "android/app/src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains("container.live.map { it.running && (it.unterwegs || it.moving) }.distinctUntilChanged()"))
        assertTrue(service.contains("if (!cadence.modeChanged(reportTap != null)) return@collect"))
        assertTrue(service.contains("notification(text, reportTap)"))
        assertTrue(service.contains("if (cadence.levelDue(now))"))
        // Simulated run: «Messen» 10 s still, 30 s moving, 10 s still; one level result per second.
        val c = NotificationCadence(2000)
        val posts = mutableListOf<String>()
        fun second(t: Long, moving: Boolean) {
            val tap = ReportTap.forState(true, false, moving)
            if (c.modeChanged(tap != null)) posts += if (tap != null) "tap" else "level-now"
            if (c.levelDue(t)) posts += "level"
        }
        var t = t0
        repeat(10) { second(t, false); t += 1000 }
        repeat(30) { second(t, true); t += 1000 }
        repeat(10) { second(t, false); t += 1000 }
        assertEquals(List(5) { "level" } + "tap" + "level-now" + List(5) { "level" }, posts)
        // An «Unterwegs» run: the report tap from the start, never a level text.
        val u = NotificationCadence(2000)
        assertTrue(u.modeChanged(ReportTap.forState(true, true, false) != null))
        assertFalse((0 until 60).any { u.levelDue(t0 + it * 1000L) })
    }

    private fun repo(): File = listOf(File("../.."), File(".."), File(".")).first { File(it, "server/stadtlaerm_server").isDirectory }
}
