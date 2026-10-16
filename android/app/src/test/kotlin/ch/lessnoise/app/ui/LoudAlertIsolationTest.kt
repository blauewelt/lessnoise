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
                t.contains("\"loud_alert\"") || t.contains("IMPORTANCE_HIGH")
        }.map { it.name }
        assertEquals(emptyList(), offenders)
        // The stand-in does nothing: no channel, no notification, no switch.
        val stub = File(app, "src/nomap/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
        assertTrue(stub.contains("const val AVAILABLE = false"))
        assertTrue(stub.contains("fun Switch() = Unit"))
        for (bad in listOf("Notification", "notify", "onCheckedChange", "SharedPreferences", "alert.")) assertFalse(stub.contains(bad), bad)
        // No new permission: POST_NOTIFICATIONS was already declared for the measurement's notification.
        assertTrue(File(app, "src/main/AndroidManifest.xml").readText().contains("android.permission.POST_NOTIFICATIONS"))
        for (m in listOf("src/public/AndroidManifest.xml", "src/laborKarte/AndroidManifest.xml", "src/offline/AndroidManifest.xml", "src/labor/AndroidManifest.xml")) {
            val t = File(app, m).takeIf { it.isFile }?.readText() ?: continue
            assertFalse(t.contains("loud_alert") || t.contains("REPORT_LOUD"), m)
        }
    }

    /**
     * The motion gate (0.6.3): the accelerometer is read only in src/karte/.../alert/
     * (MotionMonitor), its values only go into MotionDetector in memory, and no manifest gains a
     * permission or feature for it (the accelerometer needs none).
     */
    @Test
    fun theAccelerometerStaysInTheAlertCodeAndIsNeverKept() {
        val alertDir = File(app, "src/karte/kotlin/ch/lessnoise/app/alert")
        val sensorWords = listOf("android.hardware.Sensor", "SensorManager", "SensorEvent", "TYPE_ACCELEROMETER", "MotionMonitor", "MotionDetector")
        val prod = File(app, "src").listFiles()!!.filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
        val offenders = prod.flatMap { set -> set.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
            .filter { f -> f.parentFile.canonicalFile != alertDir.canonicalFile && sensorWords.any { f.readText().contains(it) } }
            // The facade creates the monitor and hands it to the watcher; it reads no sample itself.
            .filterNot { it.canonicalPath == File(app, "src/karte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").canonicalPath }
            .map { it.path }
        assertEquals(emptyList(), offenders)
        val facade = File(app, "src/karte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").readText()
        for (bad in listOf("android.hardware", "SensorManager", "TYPE_ACCELEROMETER")) assertFalse(facade.contains(bad), bad)
        // The detector is pure and the monitor keeps nothing: no storage, no log, no file, no network.
        val detector = File(alertDir, "MotionDetector.kt").readText()
        assertFalse(detector.contains("import android"))
        for (f in listOf("MotionDetector.kt", "MotionMonitor.kt")) {
            val t = File(alertDir, f).readText()
            for (bad in listOf("SharedPreferences", "java.io", "File(", "Log.", "println", "Dao", "upload", "Upload", "http", "Intent", "putExtra", "notify("))
                assertFalse(t.contains(bad), "$f: $bad")
        }
        // The notifier gets the event only, no motion values.
        val notifier = File(alertDir, "LoudAlertNotifier.kt").readText()
        assertFalse(notifier.contains("lastMotionAtMs") || notifier.contains("detector"))
        // No new permission or feature in any manifest for the sensor.
        for (m in File(app, "src").walkTopDown().filter { it.isFile && it.name == "AndroidManifest.xml" }) {
            val t = m.readText()
            for (bad in listOf("ACTIVITY_RECOGNITION", "HIGH_SAMPLING_RATE_SENSORS", "BODY_SENSORS", "android.hardware.sensor"))
                assertFalse(t.contains(bad), "${m.path}: $bad")
        }
    }
}
