package ch.lessnoise.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.6.3: «Unterwegs» and the «Sehr laut» notification exist only in the editions with the map
 * (src/karte, flavours public and laborKarte). Runs in every flavour: in offline and labor
 * [LoudAlerts] is the stand-in of src/nomap. The dex of the debug APKs is checked as well
 * (WORKLOG; android/README.md release checklist).
 */
class LoudAlertIsolationTest {
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/karte").isDirectory }

    @Test
    fun onlyTheEditionsWithTheMapHaveIt() {
        assertEquals(MapTab.AVAILABLE, LoudAlerts.AVAILABLE)
    }

    @Test
    fun theSharedCodeOnlyCallsTheFacade() {
        val main = File(app, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders = main.filter { f ->
            val t = f.readText()
            t.contains("ch.lessnoise.app.alert") || t.contains("LoudAlertRule") || t.contains("LoudAlertNotifier") ||
                t.contains("\"loud_alert\"") || t.contains("IMPORTANCE_HIGH") || t.contains("ReportTap") || t.contains("REPORT_NOW")
        }.map { it.name }
        assertEquals(emptyList(), offenders)
        // The stand-in does nothing: no channel, no notification, no permission prompt (0.6.4: no «Unterwegs» button either).
        val stub = File(app, "src/nomap/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
        assertTrue(stub.contains("const val AVAILABLE = false"))
        assertTrue(stub.contains("fun rememberUnterwegsPermission(): () -> Unit = {}"))
        // (0.6.8: it names the shared type NotificationTap in its signature, nothing that posts one.)
        val stubCode = stub.replace("NotificationTap", "")
        for (bad in listOf("Notification", "notify", "onCheckedChange", "SharedPreferences", "alert.", "PendingIntent", "REPORT_NOW")) assertFalse(stubCode.contains(bad), bad)
        // 0.6.8: no «Antippen zum Melden» on the measurement notification in the Offline and Labor builds.
        assertTrue(stub.contains("fun reportTap(context: Context, running: Boolean, unterwegs: Boolean, moving: Boolean): NotificationTap? = null"))
        assertTrue(stub.contains("fun presetFrom(intent: Intent?, tapMs: Long = 0L): ReportPreset? = null"))
        // 0.7.6: the alert goes into the measurement's own notification (MeasurementService.ACTION_LOUD_ALERT);
        // only src/karte sends that action, so the Offline and Labor builds never post an alert.
        for (d in listOf("src/main", "src/nomap", "src/public", "src/offline", "src/labor", "src/laborKarte")) {
            val senders = File(app, d).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.readText().contains(".setAction(MeasurementService.ACTION_LOUD_ALERT)") }
                .map { it.name }.toList()
            assertEquals(emptyList(), senders, d)
        }
        assertFalse(stub.contains("ACTION_LOUD_ALERT"))
        // No new permission: POST_NOTIFICATIONS was already declared for the measurement's notification.
        assertTrue(File(app, "src/main/AndroidManifest.xml").readText().contains("android.permission.POST_NOTIFICATIONS"))
        for (m in listOf("src/public/AndroidManifest.xml", "src/laborKarte/AndroidManifest.xml", "src/offline/AndroidManifest.xml", "src/labor/AndroidManifest.xml")) {
            val t = File(app, m).takeIf { it.isFile }?.readText() ?: continue
            assertFalse(t.contains("loud_alert") || t.contains("REPORT_LOUD"), m)
        }
    }

    /** 0.6.7: the direction step of «Laut! Melden» (compass) exists only where the map and the reports do. */
    @Test
    fun theDirectionStepOnlyInTheEditionsWithTheMap() {
        assertEquals(MapTab.AVAILABLE, ReportDirectionUi.AVAILABLE)
        val main = File(app, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders = main.filter { f ->
            val t = f.readText()
            t.contains("ch.lessnoise.app.compass") || t.contains("SensorManager") || t.contains("GeomagneticField")
        }.map { it.name }
        assertEquals(emptyList(), offenders)
        val stub = File(app, "src/nomap/kotlin/ch/lessnoise/app/ui/ReportDirectionUi.kt").readText()
        assertTrue(stub.contains("const val AVAILABLE = false"))
        for (bad in listOf("Sensor", "GeomagneticField", "Compass(")) assertFalse(stub.contains(bad), bad)
    }

    @Test
    fun theMotionDetectorExistsOnlyInTheEditionsWithTheMap() {
        // 0.6.7 «Unterwegs erkannt»: the detector and the sensor listener are in src/karte only.
        assertEquals(MapTab.AVAILABLE, ch.lessnoise.app.motion.MotionWatch.AVAILABLE)
        val main = File(app, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val offenders = main.filter { f ->
            val t = f.readText()
            t.contains("SensorManager") || t.contains("MotionDetector") || t.contains("SensorEventListener") || t.contains("TYPE_ACCELEROMETER")
        }.map { it.name }
        assertEquals(emptyList(), offenders)
        val stub = File(app, "src/nomap/kotlin/ch/lessnoise/app/motion/MotionWatch.kt").readText()
        assertTrue(stub.contains("const val AVAILABLE = false"))
        assertTrue(stub.contains("fun start(context: Context, container: AppContainer, position: Boolean = false): Handle? = null"))
        // 0.6.9: no position signal and no foreground type «location» in the editions without the map.
        assertTrue(stub.contains("fun positionForegroundType(context: Context): Int? = null"))
        assertTrue(stub.contains("fun extraHoldMs(nowMs: Long): Long = 0L"))
        for (bad in listOf("Sensor", "registerListener", "MotionDetector", "moving_spans", "beginMovingSpan", "Location", "PositionWatch")) assertFalse(stub.contains(bad), bad)
        for (d in listOf("src/public", "src/offline", "src/labor", "src/laborKarte", "src/nomap")) {
            val hits = File(app, d).walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().contains("SensorManager") }.toList()
            assertEquals(emptyList(), hits, d)
        }
        // No new permission for it: the accelerometer needs none (no ACTIVITY_RECOGNITION, no HIGH_SAMPLING_RATE_SENSORS).
        for (m in listOf("src/main/AndroidManifest.xml", "src/public/AndroidManifest.xml", "src/laborKarte/AndroidManifest.xml", "src/offline/AndroidManifest.xml", "src/labor/AndroidManifest.xml")) {
            val t = File(app, m).takeIf { it.isFile }?.readText() ?: continue
            for (p in listOf("ACTIVITY_RECOGNITION", "HIGH_SAMPLING_RATE_SENSORS", "BODY_SENSORS")) assertFalse(t.contains(p), "$m: $p")
        }
    }
}
