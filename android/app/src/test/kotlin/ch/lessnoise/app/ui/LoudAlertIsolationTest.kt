package ch.lessnoise.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.6.3: «Unterwegs» and the «Sehr laut» notification existed in the editions with the map; 0.8.4–0.9.4.5 only in
 * Labor·Karte (src/laborKarte); 0.9.4.6 («Unterwegs messen») again in both editions with the map: alert/ and the
 * real ui/LoudAlerts.kt are in src/karte (compiled into public and laborKarte). Runs in every flavour: [LoudAlerts]
 * is the real facade in public and laborKarte and the stand-in of src/nomap in offline and labor. The dex of the
 * APKs is checked as well (android/tools/check_release_apk.py; android/README.md release checklist).
 */
class LoudAlertIsolationTest {
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/karte").isDirectory }

    @Test
    fun theEditionsWithTheMapHaveIt() {
        assertEquals(ch.lessnoise.app.BuildConfig.FLAVOR in setOf("public", "laborKarte"), LoudAlerts.AVAILABLE)
        assertEquals(MapTab.AVAILABLE, LoudAlerts.AVAILABLE)
    }

    /** 0.9.4.6: the alert code lives in src/karte (both editions with the map); no Labor-only copy, no Online stand-in. */
    @Test
    fun theAlertCodeIsInSrcKarte() {
        for (f in listOf("alert/LoudAlertNotifier.kt", "alert/LoudAlertRule.kt", "alert/ReportTap.kt", "ui/LoudAlerts.kt")) {
            assertTrue(File(app, "src/karte/kotlin/ch/lessnoise/app/$f").isFile, f)
        }
        assertFalse(File(app, "src/laborKarte/kotlin/ch/lessnoise/app/alert").exists())
        assertFalse(File(app, "src/laborKarte/kotlin/ch/lessnoise/app/ui/LoudAlerts.kt").exists())
        assertFalse(File(app, "src/nounterwegs").exists(), "0.8.4–0.9.4.5 the Online-Version's stand-in; gone")
        // Nothing of it in the published edition's own source sets or the position stand-in; src/laborKarte has only its
        // manifest and resources.
        for (d in listOf("src/public", "src/noposition", "src/offline", "src/labor", "src/laborKarte")) {
            val hits = File(app, d).walkTopDown().filter { f ->
                f.isFile && (f.extension == "kt" || f.extension == "xml") && f.readText().let { t ->
                    t.contains("ch.lessnoise.app.alert") || t.contains("LoudAlertRule") || t.contains("LoudAlertNotifier") ||
                        t.contains("\"loud_alert\"") || t.contains("IMPORTANCE_HIGH") || t.contains("REPORT_LOUD")
                }
            }.map { it.path }.toList()
            assertEquals(emptyList(), hits, d)
        }
        assertEquals(emptyList(), File(app, "src/laborKarte").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path }.toList())
        // Gradle: src/karte in exactly the two editions with the map; no stand-in folder, no edition flag.
        val gradle = File(app, "build.gradle.kts").readText()
        assertEquals(2, Regex("""kotlin\.srcDir\("src/karte/kotlin"\)""").findAll(gradle).count())
        assertFalse(gradle.contains("src/nounterwegs/kotlin\")"))
        assertFalse(Regex("""buildConfigField\("boolean", "LABOR_UNTERWEGS"""").containsMatchIn(gradle))
        // One `named("…") { … }` block of sourceSets, up to its closing brace (the comments between blocks are not in it).
        fun block(name: String): String {
            val start = gradle.indexOf("named(\"$name\") {")
            assertTrue(start >= 0, name)
            return gradle.substring(start, gradle.indexOf("\n        }", start))
        }
        for (block in listOf(block("public"), block("laborKarte"))) {
            assertTrue(block.contains("kotlin.srcDir(\"src/karte/kotlin\")"), block)
            assertTrue(block.contains("kotlin.srcDir(\"src/noposition/kotlin\")"), block)
        }
        // The Offline and Labor builds: the src/nomap stand-in only.
        for (block in listOf(block("offline"), block("labor"))) {
            assertTrue(block.contains("kotlin.srcDir(\"src/nomap/kotlin\")"), block)
            assertFalse(block.contains("src/karte"), block)
        }
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
        // 0.6.8: no «Melden» and no report tap on the measurement notification in the Offline and Labor builds.
        assertTrue(stub.contains("fun reportTap(context: Context, running: Boolean, unterwegs: Boolean, moving: Boolean): NotificationTap? = null"))
        assertTrue(stub.contains("fun presetFrom(intent: Intent?, tapMs: Long = 0L): ReportPreset? = null"))
        // 0.7.6: the alert goes into the measurement's own notification (MeasurementService.ACTION_LOUD_ALERT); only
        // src/karte's notifier sends that action, so the Offline and Labor builds never post an alert.
        for (d in listOf("src/main", "src/nomap", "src/public", "src/offline", "src/labor", "src/laborKarte", "src/noposition")) {
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
        assertTrue(File(app, "src/karte/kotlin/ch/lessnoise/app/alert/LoudAlertNotifier.kt").readText().contains(".setAction(MeasurementService.ACTION_LOUD_ALERT)"))
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
        // 0.8.1: same API as the real one, no compass: no reading, no disc.
        assertTrue(stub.contains("fun rememberReading(): State<CompassReading?>? = null"))
        // 0.8.1 (same build): the place moved under the dot; the stand-in has no disc, so no drag.
        // 0.9.2: with onDraggable (the hint «Karte verschiebbar»), which the stand-in never calls.
        // 0.9.4.2: with the disc's size and the dialog's measuring pass.
        assertTrue(stub.contains("fun Panel(reading: State<CompassReading?>, fix: Pair<Double, Double>?, moved: Pair<Double, Double>?, onDraggable: (Boolean) -> Unit = {}, discSize: Dp = REPORT_DISC_MAX_DP.dp, measureOnly: Boolean = false, directionOn: Boolean = true, onDirectionOn: ((Boolean) -> Unit)? = null, onMove: (Pair<Double, Double>) -> Boolean) = Unit"))
        assertFalse(stub.contains("pointerInput"))
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
