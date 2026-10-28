package ch.lessnoise.app.report

import ch.lessnoise.app.ui.LoudAlerts
import ch.lessnoise.app.ui.ReportPreset
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.8.4: «Laut! Melden» from the home screen — the static app shortcut and the widget (src/karte,
 * editions with the map; src/testKarte runs for public and laborKarte). The parts that need a phone
 * (the launcher shows the shortcut, the widget picker lists the widget, a tap opens the dialog) are
 * not checked here; this pins the intent, the resources and the manifest entries.
 */
class ShortcutAndWidgetTest {
    private val t0 = 1_760_000_000_000L
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/karte").isDirectory }

    private fun read(path: String) = File(app, path).readText()

    @Test
    fun theIntentOpensTheReportAtTheTapsTimeWithNothingPreselected() {
        assertEquals("ch.lessnoise.app.action.REPORT_NOW", ReportNow.ACTION)
        assertEquals(ReportPreset(t0, emptyList()), ReportNow.preset(ReportNow.ACTION, t0))
        assertNull(ReportNow.preset(null, t0))
        assertNull(ReportNow.preset("android.intent.action.MAIN", t0))
        assertNull(ReportNow.preset(ReportNow.ACTION, 0))
        // Through the facade MainActivity calls, in both editions with the map (the stand-in of
        // src/nounterwegs in public, the real one of src/laborKarte in laborKarte). The facade takes
        // the action only; no Android intent is built on the JVM here.
        val nounterwegs = read("src/nounterwegs/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt")
        assertTrue(nounterwegs.contains("ReportNow.preset(intent?.action, tapMs)"))
        val labor = read("src/laborKarte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt")
        assertTrue(labor.contains("ReportTap.preset(intent.action, tapMs)?.let { return it }"))
        assertTrue(read("src/laborKarte/kotlin/ch/lessnoise/app/alert/ReportTap.kt").contains("fun preset(action: String?, tapMs: Long): ReportPreset? = ReportNow.preset(action, tapMs)"))
        // No intent (a plain launch): no report.
        assertNull(LoudAlerts.presetFrom(null, t0))
    }

    /**
     * 0.9.4.4 (owner: «the main UI flow is to report someone»): «Melden» on the measurement notification in both
     * editions with the map (Labor·Karte since 0.6.9), the same intent as the shortcut and the widget; the
     * notification's own tap opens the app on «Messen».
     */
    @Test
    fun theMeasurementNotificationHasMeldenDuringEveryRun() {
        assertTrue(ReportNow.notificationButton(running = true))
        assertFalse(ReportNow.notificationButton(running = false))
        assertEquals("Melden", ReportNow.NOTIFICATION_BUTTON)
        assertEquals(3, ReportNow.NOTIFICATION_REQUEST)
        // Request codes: service 0 (open) and 1 (Stopp), «Sehr laut» 2 (Labor·Karte), «Melden» 3, the widget 4.
        assertTrue(ReportNow.NOTIFICATION_REQUEST !in setOf(0, 1, 2, ReportNow.WIDGET_REQUEST))
        val now = read("src/karte/kotlin/ch/lessnoise/app/report/ReportNow.kt")
        // The same intent as the widget (no time, no category), its own request code; no text, no content tap.
        assertTrue(now.contains("context, NOTIFICATION_REQUEST, intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,"))
        assertTrue(now.contains("NotificationTap(text = null, intent = null, button = notificationPendingIntent(context), buttonLabel = NOTIFICATION_BUTTON)"))
        for (bad in listOf("ch.lessnoise.app.alert", "LoudAlertRule", "LoudAlertNotifier", "LoudAlertIntent", "loud_alert", "IMPORTANCE_HIGH")) assertFalse(now.contains(bad), bad)
        // The Online-Version's stand-in returns it; Labor·Karte's rule uses the same code and label.
        assertTrue(read("src/nounterwegs/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").contains("ReportNow.notificationTap(context, running)"))
        val tap = read("src/laborKarte/kotlin/ch/lessnoise/app/alert/ReportTap.kt")
        assertTrue(tap.contains("const val REQUEST = ReportNow.NOTIFICATION_REQUEST"))
        assertTrue(tap.contains("const val BUTTON = ReportNow.NOTIFICATION_BUTTON"))
        // The service adds the button from the tap (every level post keeps it, ReportButtonTest) and its usual tap
        // asks MainActivity for «Messen».
        val service = read("src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt")
        assertTrue(service.contains(".apply { reportTap?.button?.let { addAction(0, reportTap.buttonLabel, it) } }"))
        assertTrue(service.contains("Intent(this, MainActivity::class.java).setAction(MeasurementNotification.ACTION_SHOW_MEASURE)"))
        assertEquals("ch.lessnoise.app.action.SHOW_MEASURE", ch.lessnoise.app.service.MeasurementNotification.ACTION_SHOW_MEASURE)
        assertTrue(ch.lessnoise.app.service.MeasurementNotification.showsMeasure("ch.lessnoise.app.action.SHOW_MEASURE"))
        assertFalse(ch.lessnoise.app.service.MeasurementNotification.showsMeasure(ReportNow.ACTION))
        assertFalse(ch.lessnoise.app.service.MeasurementNotification.showsMeasure(null))
        // «Messen» is not a report: no preset from it (the action is taken before LoudAlerts.presetFrom).
        assertNull(ReportNow.preset("ch.lessnoise.app.action.SHOW_MEASURE", t0))
        val main = read("src/main/kotlin/ch/lessnoise/app/ui/MainActivity.kt")
        assertTrue(main.contains("if (MeasurementNotification.showsMeasure(intent?.action)) {\n            showMeasure++"))
        assertTrue(main.contains("LaunchedEffect(showMeasure) { if (showMeasure > 0) open(Screen.MEASURE) }"))
    }

    @Test
    fun mainActivityTakesItOnAColdStartAndWhenOpen() {
        val main = read("src/main/kotlin/ch/lessnoise/app/ui/MainActivity.kt")
        // Cold start (the shortcut's launch clears the task; the widget may start the process): onCreate.
        assertTrue(main.contains("if (savedInstanceState == null) takeAlert(intent)"))
        // App already open (single-top): onNewIntent.
        assertTrue(main.contains("override fun onNewIntent(intent: Intent) {\n        super.onNewIntent(intent)\n        takeAlert(intent)"))
        assertTrue(main.contains("LoudAlerts.presetFrom(intent, System.currentTimeMillis())"))
        // Taken once: a later recreation does not open the dialog again.
        assertTrue(main.contains("setIntent(Intent(this, MainActivity::class.java))"))
        assertTrue(read("src/main/AndroidManifest.xml").contains("android:launchMode=\"singleTop\""))
    }

    @Test
    fun theWidgetIsOneButtonThatOnlyOpensTheDialog() {
        val widget = read("src/karte/kotlin/ch/lessnoise/app/report/ReportWidget.kt")
        assertTrue(widget.contains("class ReportWidget : AppWidgetProvider()"))
        assertTrue(widget.contains("views.setOnClickPendingIntent(R.id.report_widget_button, ReportNow.pendingIntent(context))"))
        // Plain RemoteViews, no Glance, no data shown, no work.
        for (bad in listOf("androidx.glance", "container", "WorkManager", "UploadClient", "setTextViewText", "AlarmManager")) assertFalse(widget.contains(bad), bad)
        val now = read("src/karte/kotlin/ch/lessnoise/app/report/ReportNow.kt")
        val intent = now.substring(now.indexOf("fun intent("), now.indexOf("fun pendingIntent("))
        assertFalse(intent.contains("putExtra"), "no time or category in the intent")
        assertTrue(intent.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK"))
        assertTrue(now.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertEquals(4, ReportNow.WIDGET_REQUEST)

        val info = read("src/karte/res/xml/report_widget_info.xml")
        assertTrue(info.contains("android:widgetCategory=\"home_screen\""))
        assertTrue(info.contains("android:minWidth=\"110dp\"") && info.contains("android:minHeight=\"40dp\""), "2×1 cells")
        assertTrue(info.contains("android:targetCellWidth=\"2\"") && info.contains("android:targetCellHeight=\"1\""))
        assertTrue(info.contains("android:updatePeriodMillis=\"0\""))
        val layout = read("src/karte/res/layout/report_widget.xml")
        assertTrue(layout.contains("android:id=\"@+id/report_widget_button\""))
        assertTrue(layout.contains("@string/report_now_label"))
        // Only views RemoteViews can inflate.
        val tags = Regex("""<([A-Za-z.]+)[\s>]""").findAll(layout).map { it.groupValues[1] }.filter { it != "?xml" }.toSet()
        assertTrue(setOf("FrameLayout", "LinearLayout", "ImageView", "TextView").containsAll(tags), "$tags")
        assertTrue(read("src/karte/res/values/strings_report.xml").contains("<string name=\"report_now_label\">Laut! Melden</string>"))
    }

    @Test
    fun theIconsAreTheMegaphoneOfAppIcons() {
        val icons = read("src/main/kotlin/ch/lessnoise/app/icons/AppIcons.kt")
        val path = Regex(""""Campaign",\s*"([^"]+)"""").find(icons)!!.groupValues[1]
        assertTrue(read("src/karte/res/drawable/ic_report_megaphone.xml").contains("android:pathData=\"$path\""))
        assertTrue(read("src/karte/res/drawable/ic_shortcut_report_foreground.xml").contains("android:pathData=\"$path\""))
        val adaptive = read("src/karte/res/drawable/ic_shortcut_report.xml")
        assertTrue(adaptive.contains("<adaptive-icon") && adaptive.contains("@drawable/ic_shortcut_report_foreground"))
    }

    @Test
    fun registeredInTheEditionsWithTheMapOnly() {
        val gradle = read("build.gradle.kts")
        for ((flavour, appId) in listOf("public" to "ch.lessnoise.app", "laborKarte" to "ch.lessnoise.labor")) {
            val manifest = read("src/$flavour/AndroidManifest.xml")
            assertTrue(manifest.contains("<meta-data android:name=\"android.app.shortcuts\" android:resource=\"@xml/shortcuts\" />"), flavour)
            assertTrue(manifest.contains("android:name=\".report.ReportWidget\""), flavour)
            assertTrue(manifest.contains("android:exported=\"false\""), flavour)
            assertTrue(manifest.contains("<action android:name=\"android.appwidget.action.APPWIDGET_UPDATE\" />"), flavour)
            assertTrue(manifest.contains("android:resource=\"@xml/report_widget_info\""), flavour)
            val shortcuts = read("src/$flavour/res/xml/shortcuts.xml")
            assertTrue(shortcuts.contains("android:action=\"${ReportNow.ACTION}\""), flavour)
            assertTrue(shortcuts.contains("android:targetClass=\"ch.lessnoise.app.ui.MainActivity\""), flavour)
            // targetPackage must be the flavour's literal application id (public: the default).
            assertTrue(shortcuts.contains("android:targetPackage=\"$appId\""), flavour)
            assertTrue(shortcuts.contains("android:icon=\"@drawable/ic_shortcut_report\""), flavour)
            assertTrue(shortcuts.contains("@string/report_now_label"), flavour)
            // Gradle compiles src/karte/res into this flavour.
            val block = gradle.substring(gradle.indexOf("named(\"$flavour\")"))
            assertTrue(block.substring(0, block.indexOf("}")).contains("res.srcDir(\"src/karte/res\")"), flavour)
        }
        assertTrue(gradle.contains("applicationId = \"ch.lessnoise.app\""))
        val lk = gradle.substring(gradle.indexOf("create(\"laborKarte\")"))
        assertTrue(lk.substring(0, lk.indexOf("}")).contains("applicationId = \"ch.lessnoise.labor\""))
        // No new permission for either.
        for (m in listOf("src/public/AndroidManifest.xml", "src/laborKarte/AndroidManifest.xml")) {
            assertFalse(read(m).contains("BIND_APPWIDGET") || read(m).contains("INSTALL_SHORTCUT"), m)
        }
        // Not in the editions without the report.
        for (m in listOf("src/main/AndroidManifest.xml", "src/offline/AndroidManifest.xml", "src/labor/AndroidManifest.xml")) {
            val t = read(m)
            assertFalse(t.contains("ReportWidget") || t.contains("android.app.shortcuts"), m)
        }
        for (d in listOf("src/main/res", "src/offline", "src/labor/res")) {
            assertFalse(File(app, d).walkTopDown().any { it.name == "shortcuts.xml" || it.name == "report_widget_info.xml" }, d)
        }
    }
}
