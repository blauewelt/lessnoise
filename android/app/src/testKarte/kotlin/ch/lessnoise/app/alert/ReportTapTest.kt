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

/**
 * The measurement notification's report parts (alert/ReportTap.kt). 0.6.8: «Antippen zum Melden» while «unterwegs»;
 * 0.6.9 (Labor·Karte) the «Melden» button in every run; 0.8.4–0.9.4.5 Labor·Karte only (src/testLaborKarte);
 * 0.9.4.6 both editions with the map, with Labor·Karte's rule (src/testKarte, run for public and laborKarte).
 */
class ReportTapTest {
    private val t0 = 1_760_000_000_000L

    @Test
    fun meldenInEveryRunTheTapOpensTheReportOnlyWhileUnterwegs() {
        for ((unterwegs, moving) in listOf(false to false, false to true, true to false, true to true)) {
            val t = ReportTap.forState(running = true, unterwegs = unterwegs, moving = moving)!!
            assertTrue(t.button, "button ($unterwegs, $moving)")
            assertEquals(ReportTap.ACTION, t.action)
            assertEquals(ReportTap.REQUEST, t.requestCode)
            // The content tap opens the report only while «unterwegs» (0.6.8; the owner's earlier decision); a window
            // run lying still: it opens the app on «Messen».
            assertEquals(unterwegs || moving, t.contentTap, "content tap ($unterwegs, $moving)")
        }
        assertEquals("Melden", ReportTap.BUTTON)
        // No run: nothing.
        assertNull(ReportTap.forState(running = false, unterwegs = true, moving = true))
        assertNull(ReportTap.forState(running = false, unterwegs = false, moving = false))
        // The button's intent is the content tap's: the report at the time of the tap, nothing preselected.
        assertEquals(ReportPreset(t0, emptyList()), ReportTap.preset(ReportTap.ACTION, t0))
    }

    @Test
    fun itsIntentIsDistinctFromTheSehrLautTap() {
        assertNotEquals(LoudAlertIntent.ACTION, ReportTap.ACTION)
        // 0.8.4: the notification's «Melden», the shortcut and the widget are one intent (ReportNow, src/karte).
        assertEquals(ch.lessnoise.app.report.ReportNow.ACTION, ReportTap.ACTION)
        assertNotEquals(ch.lessnoise.app.report.ReportNow.WIDGET_REQUEST, ReportTap.REQUEST)
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
        // (an open app gets it in onNewIntent); the level text stays (text = null).
        val facade = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
        assertTrue(facade.contains(".setAction(t.action)"))
        assertTrue(facade.contains("context, t.requestCode, intent,"))
        assertTrue(facade.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertTrue(facade.contains("ReportTap.preset(intent.action, tapMs)?.let { return it }"))
        assertTrue(facade.contains("ReportTap.forState(running, unterwegs, moving) ?: return null"))
        assertTrue(facade.contains("text = null,"))
        assertTrue(facade.contains("intent = pending.takeIf { t.contentTap }"))
        assertTrue(facade.contains("button = pending.takeIf { t.button }"))
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
    fun theServiceAddsTheButtonAndKeepsItOnEveryLevelPost() {
        val service = File(repo(), "android/app/src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains("container.live.map { it.running && (it.unterwegs || it.moving) }.distinctUntilChanged()"))
        assertTrue(service.contains("val modeChanged = cadence.modeChanged(reportTap?.text != null)"))
        assertTrue(service.contains("if (!modeChanged && !tapChanged) return@collect"))
        assertTrue(service.contains("notification(text, reportTap)"))
        assertTrue(service.contains("if (cadence.levelDue(now))"))
        assertTrue(service.contains(".addAction(0, \"Stopp\", stop)\n            .apply { reportTap?.button?.let { addAction(0, reportTap.buttonLabel, it) } }"))
        // Level posts keep the current tap (so the button never disappears), in every state.
        assertTrue(service.contains("notify(NOTIFICATION_ID, notification(text, currentTap))"))
        // The usual tap when the report tap is not the content intent.
        assertTrue(service.contains("val open = reportTap?.intent ?: PendingIntent.getActivity("))
    }

    @Test
    fun aRunStillMovingStillKeepsTheLevelTextAndRePostsOnlyOnTapChanges() {
        val c = NotificationCadence(2000)
        val posts = mutableListOf<String>()
        fun second(t: Long, moving: Boolean) {
            val tap = ReportTap.forState(true, false, moving)!!
            // The facade passes text = null: never report mode, the level text stays.
            val mode = c.modeChanged(false)
            val changed = c.tapChanged(tap.contentTap, tap.button)
            if (mode || changed) posts += "tap:${tap.contentTap}"
            if (c.levelDue(t)) posts += "level"
        }
        var t = t0
        repeat(10) { second(t, false); t += 1000 }
        repeat(10) { second(t, true); t += 1000 }
        repeat(4) { second(t, false); t += 1000 }
        assertEquals(
            listOf("tap:false") + List(5) { "level" } + "tap:true" + List(5) { "level" } + "tap:false" + List(2) { "level" },
            posts,
        )
        // An «Unterwegs messen» run: the content tap from the start, the level text every 2 s.
        val u = NotificationCadence(2000)
        val tap = ReportTap.forState(true, true, false)!!
        assertFalse(u.modeChanged(false))
        assertTrue(u.tapChanged(tap.contentTap, tap.button))
        assertEquals(30, (0 until 60).count { u.levelDue(t0 + it * 1000L) })
    }

    /** 0.9.4.6: the flag that chose between Labor·Karte's and 0.6.8's rule is gone from the build and the code. */
    @Test
    fun noEditionFlagLeft() {
        val gradle = File(repo(), "android/app/build.gradle.kts").readText()
        assertFalse(Regex("""buildConfigField\("boolean", "LABOR_UNTERWEGS"""").containsMatchIn(gradle))
        val src = File(repo(), "android/app/src").walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("/test") }.toList()
        assertTrue(src.size > 30)
        assertEquals(emptyList(), src.filter { it.readText().contains("BuildConfig.LABOR_UNTERWEGS") }.map { it.name })
    }

    private fun repo(): File = listOf(File("../.."), File(".."), File(".")).first { File(it, "server/stadtlaerm_server").isDirectory }
}
