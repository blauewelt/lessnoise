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

/** 0.6.8: «Antippen zum Melden» on the measurement notification while «unterwegs» (since 0.8.4 Labor·Karte only: src/testLaborKarte). */
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
        // 0.8.4: the notification's «Melden», the shortcut and the widget are one intent (ReportNow, src/karte).
        assertEquals(ch.lessnoise.app.report.ReportNow.ACTION, ReportTap.ACTION)
        assertNotEquals(ch.lessnoise.app.report.ReportNow.WIDGET_REQUEST, ReportTap.REQUEST)
        // Request codes: service 0 (open) and 1 (Stopp), «Sehr laut» 2, this one 3.
        val service = File(repo(), "android/app/src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains("PendingIntent.getActivity(\n            this, 0,"))
        assertTrue(service.contains("PendingIntent.getService(\n            this, 1,"))
        val notifier = File(repo(), "android/app/src/laborKarte/kotlin/ch/lessnoise/app/alert/LoudAlertNotifier.kt").readText()
        assertTrue(notifier.contains("private const val REQUEST = 2"))
        assertEquals(3, ReportTap.REQUEST)
        // The «Sehr laut» tap is not a report-now tap and vice versa.
        assertNull(ReportTap.preset(LoudAlertIntent.ACTION, t0))
        assertNull(LoudAlertIntent.preset(ReportTap.ACTION, t0, "loud_vehicle"))
        // The facade builds the PendingIntent from the rule's action and request code, single-top
        // (an open app gets it in onNewIntent).
        val facade = File(repo(), "android/app/src/laborKarte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
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
        val facade = File(repo(), "android/app/src/laborKarte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
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
        assertTrue(service.contains("val modeChanged = cadence.modeChanged(reportTap?.text != null)"))
        assertTrue(service.contains("if (!modeChanged && !tapChanged) return@collect"))
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

/** 0.6.9, Labor·Karte (BuildConfig.LABOR_UNTERWEGS): «Melden» on the measurement notification in every state. */
class ReportButtonTest {
    private val t0 = 1_760_000_000_000L

    @Test
    fun laborKarteHasTheMeldenButtonInEveryStateAndKeepsTheLevelText() {
        for ((unterwegs, moving) in listOf(false to false, false to true, true to false, true to true)) {
            val t = ReportTap.forState(running = true, unterwegs = unterwegs, moving = moving, labor = true)!!
            assertTrue(t.button, "button ($unterwegs, $moving)")
            assertNull(t.text, "level text kept ($unterwegs, $moving)")
            assertEquals(ReportTap.ACTION, t.action)
            assertEquals(ReportTap.REQUEST, t.requestCode)
            // The content tap opens the report only while «unterwegs» (0.6.8); still: it opens the app.
            assertEquals(unterwegs || moving, t.contentTap, "content tap ($unterwegs, $moving)")
        }
        assertEquals("Melden", ReportTap.BUTTON)
        assertNull(ReportTap.forState(running = false, unterwegs = false, moving = false, labor = true))
        // The button's intent is the content tap's: the report at the time of the tap, nothing preselected.
        assertEquals(ReportPreset(t0, emptyList()), ReportTap.preset(ReportTap.ACTION, t0))
    }

    /** Without the Labor flag the rule is 0.6.8's (the Online-Version until 0.8.3; no edition calls it so since 0.8.4). */
    @Test
    fun withoutTheLaborFlagItIsThe068Rule() {
        assertNull(ReportTap.forState(running = true, unterwegs = false, moving = false))
        val t = ReportTap.forState(running = true, unterwegs = false, moving = true)!!
        assertEquals(ReportTap.TEXT, t.text)
        assertTrue(t.contentTap)
        assertFalse(t.button)
    }

    @Test
    fun theFlagIsOnlyOnInLaborKarteAndTheServiceAddsTheButton() {
        val gradle = File(repo(), "android/app/build.gradle.kts").readText()
        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"LABOR_UNTERWEGS\", \"false\")"))
        assertEquals(1, Regex("""LABOR_UNTERWEGS", "true"""").findAll(gradle).count())
        val lk = gradle.substring(gradle.indexOf("create(\"laborKarte\")"), gradle.indexOf("sourceSets {"))
        assertTrue(lk.contains("buildConfigField(\"boolean\", \"LABOR_UNTERWEGS\", \"true\")"))
        assertEquals(ch.lessnoise.app.BuildConfig.FLAVOR == "laborKarte", ch.lessnoise.app.BuildConfig.LABOR_UNTERWEGS)
        val facade = File(repo(), "android/app/src/laborKarte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
        assertTrue(facade.contains("ReportTap.forState(running, unterwegs, moving, labor = BuildConfig.LABOR_UNTERWEGS)"))
        assertTrue(facade.contains("intent = pending.takeIf { t.contentTap }"))
        assertTrue(facade.contains("button = pending.takeIf { t.button }"))
        val service = File(repo(), "android/app/src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains(".addAction(0, \"Stopp\", stop)\n            .apply { reportTap?.button?.let { addAction(0, reportTap.buttonLabel, it) } }"))
        // Level posts keep the current tap (so the button never disappears), in every state.
        assertTrue(service.contains("notify(NOTIFICATION_ID, notification(text, currentTap))"))
        // The usual tap when the report tap is not the content intent.
        assertTrue(service.contains("val open = reportTap?.intent ?: PendingIntent.getActivity("))
    }

    @Test
    fun laborRunStillMovingStillKeepsTheLevelTextAndRePostsOnlyOnTapChanges() {
        val c = NotificationCadence(2000)
        val posts = mutableListOf<String>()
        fun second(t: Long, moving: Boolean) {
            val tap = ReportTap.forState(true, false, moving, labor = true)!!
            val mode = c.modeChanged(tap.text != null)
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
    }

    private fun repo(): File = listOf(File("../.."), File(".."), File(".")).first { File(it, "server/stadtlaerm_server").isDirectory }
}
