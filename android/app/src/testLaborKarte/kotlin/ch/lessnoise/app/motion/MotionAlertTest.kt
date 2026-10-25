package ch.lessnoise.app.motion

import ch.lessnoise.app.alert.LoudAlertRule
import ch.lessnoise.app.service.LiveEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.6.7: the «Sehr laut» alert while the motion detector says MOVING in a window measurement. Since
 * 0.8.4 the alert exists in Labor·Karte only (src/laborKarte), so this part of MotionDetectorTest
 * moved here (src/testLaborKarte).
 */
class MotionAlertTest {
    private fun ev(db: Double = 84.0) = LiveEvent(
        startEpochMs = 1_000L, endEpochMs = 4_000L, lafMaxDb = db, category = "loud_vehicle", wind = false, calibrated = true,
        thresholdDb = 10.0, minLevelDb = 30.0,
    )

    @Test
    fun aMessenMeasurementAlertsWhileMovingAndNotWhileStill() {
        fun alerts(unterwegs: Boolean, moving: Boolean, last: Long? = null, now: Long = 10_000L, db: Double = 84.0) =
            LoudAlertRule.shouldAlert(ev(db), LoudAlertRule.unterwegsNow(unterwegs, moving), running = true, lastAlertAtMs = last, nowMs = now)
        assertFalse(alerts(unterwegs = false, moving = false), "«Messen», still: a windowsill phone never alerts")
        assertTrue(alerts(unterwegs = false, moving = true), "«Messen», moving: as «Unterwegs»")
        assertTrue(alerts(unterwegs = true, moving = false), "«Unterwegs» as before")
        // Same threshold and 2-minute gap as «Unterwegs».
        assertFalse(alerts(unterwegs = false, moving = true, db = 74.9))
        assertFalse(alerts(unterwegs = false, moving = true, last = 10_000L - 60_000L))
        assertTrue(alerts(unterwegs = false, moving = true, last = 10_000L - 120_000L))
        // The watcher passes the live state's «moving».
        val watcher = File(repo(), "android/app/src/laborKarte/kotlin/ch/lessnoise/app/alert/LoudAlertNotifier.kt").readText()
        assertTrue(watcher.contains("LoudAlertRule.shouldAlert(e, LoudAlertRule.unterwegsNow(l.unterwegs, l.moving), l.running, lastAlertAtMs, now)"))
        // The service starts the watch only for «Messen», and stops it with the measurement.
        val service = File(repo(), "android/app/src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains("if (!unterwegs && MotionWatch.AVAILABLE) motion = MotionWatch.start(this@MeasurementService, container, position = positionAllowed)"))
        assertTrue(service.contains("motion?.stop()"))
        // «moving» is only ever set for a running «Messen» measurement.
        val watch = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/motion/MotionWatch.kt").readText()
        assertTrue(watch.contains("if (it.running && !it.unterwegs) it.copy(moving = combiner.moving) else it"))
        assertTrue(watch.contains("registerListener(listener, sensor, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US, handler)"))
        assertTrue(watch.contains("Sensor.TYPE_ACCELEROMETER"))
        // 0.6.9: the position signal is behind PositionWatch (src/laborKarte; stand-in in the Online-Version);
        // the watch itself reads no location and sees no coordinate.
        for (bad in listOf("LocationManager", "android.location", "latitude", "longitude", "TYPE_SIGNIFICANT_MOTION", "ActivityRecognition", "gms", "UploadModule", "upload.")) {
            assertFalse(watch.contains(bad), bad)
        }
    }

    private fun repo(): File = listOf(File("../.."), File(".."), File(".")).first { File(it, "server/stadtlaerm_server").isDirectory }
}
