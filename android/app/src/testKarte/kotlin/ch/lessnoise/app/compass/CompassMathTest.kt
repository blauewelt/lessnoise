package ch.lessnoise.app.compass

import ch.lessnoise.app.compass.CompassMath.Hold
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.6.7: where the phone points, from Android's rotation matrix R (device axes → east/north/up),
 * for the phone held flat or upright and every display rotation. The matrices are built here from
 * the phone's attitude exactly as SensorManager.getRotationMatrix defines them (columns = the
 * device's X, Y, Z axes in world coordinates).
 */
class CompassMathTest {
    private fun rad(d: Double) = Math.toRadians(d)

    /** World vector of a horizontal direction at azimuth [deg] (clockwise from north). */
    private fun horiz(deg: Double) = doubleArrayOf(sin(rad(deg)), cos(rad(deg)), 0.0)

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    /** R from the device's X and Y axes in world coordinates (Z = X × Y). */
    private fun r(x: DoubleArray, y: DoubleArray): FloatArray {
        val z = cross(x, y)
        return floatArrayOf(
            x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
            x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
            x[2].toFloat(), y[2].toFloat(), z[2].toFloat(),
        )
    }

    /** Flat on the hand, screen up, the phone's top (+Y) towards [az]. */
    private fun flat(az: Double) = r(horiz(az + 90), horiz(az))

    /** Upright in front of the face (portrait), screen towards the user, the back (−Z) towards [az]. */
    private fun upright(az: Double) = r(horiz(az + 90), doubleArrayOf(0.0, 0.0, 1.0))

    /** Upright in landscape, phone turned counter-clockwise (display ROTATION_90): the phone's +X is up. */
    private fun uprightLandscape(az: Double) = r(doubleArrayOf(0.0, 0.0, 1.0), horiz(az - 90))

    private fun close(expected: Double, actual: Double?, tol: Double = 0.01) {
        assertTrue(actual != null && abs(((actual - expected + 540) % 360) - 180) < tol, "expected $expected, got $actual")
    }

    @Test
    fun flatPortraitPointsWhereTheTopOfThePhonePoints() {
        for (az in listOf(0.0, 45.0, 87.0, 180.0, 271.0, 359.0)) {
            val m = flat(az)
            assertEquals(Hold.FLAT, CompassMath.hold(m, 0, null))
            close(az, CompassMath.bearing(m, 0, Hold.FLAT))
        }
    }

    @Test
    fun flatWithTheScreenRotatedPointsWhereTheScreensTopPoints() {
        // Phone flat, its top towards 0°. Rotated to landscape (ROTATION_90: phone turned
        // counter-clockwise), the screen's top edge is the phone's right side (+X), which points to 90°.
        val m = flat(0.0)
        close(90.0, CompassMath.bearing(m, 1, Hold.FLAT))
        close(180.0, CompassMath.bearing(m, 2, Hold.FLAT))
        close(270.0, CompassMath.bearing(m, 3, Hold.FLAT))
    }

    @Test
    fun uprightPointsOutOfTheBackInAnyDisplayRotation() {
        for (az in listOf(0.0, 87.0, 200.0)) {
            val m = upright(az)
            assertEquals(Hold.UPRIGHT, CompassMath.hold(m, 0, null))
            close(az, CompassMath.bearing(m, 0, Hold.UPRIGHT))
            val l = uprightLandscape(az)
            assertEquals(Hold.UPRIGHT, CompassMath.hold(l, 1, null))
            close(az, CompassMath.bearing(l, 1, Hold.UPRIGHT))
        }
    }

    @Test
    fun tiltedPhonesAndHysteresis() {
        // Tilt the flat phone (top towards 30°) up by t around its X axis: top rises, back turns to 30°.
        fun tilted(t: Double): FloatArray {
            val x = horiz(120.0)
            val top = horiz(30.0)
            val y = doubleArrayOf(top[0] * cos(rad(t)), top[1] * cos(rad(t)), sin(rad(t)))
            return r(x, y)
        }
        for (t in listOf(0.0, 20.0, 40.0)) assertEquals(Hold.FLAT, CompassMath.hold(tilted(t), 0, null), "tilt $t")
        for (t in listOf(50.0, 70.0, 90.0)) assertEquals(Hold.UPRIGHT, CompassMath.hold(tilted(t), 0, null), "tilt $t")
        // Both read 30°: flat via the top edge, upright via the back.
        close(30.0, CompassMath.bearing(tilted(30.0), 0, Hold.FLAT))
        close(30.0, CompassMath.bearing(tilted(60.0), 0, Hold.UPRIGHT))
        // Around 45° the previous hold is kept (no flicker).
        assertEquals(Hold.FLAT, CompassMath.hold(tilted(48.0), 0, Hold.FLAT))
        assertEquals(Hold.UPRIGHT, CompassMath.hold(tilted(42.0), 0, Hold.UPRIGHT))
        assertEquals(Hold.UPRIGHT, CompassMath.hold(tilted(60.0), 0, Hold.FLAT))
    }

    @Test
    fun straightUpHasNoBearing() {
        assertNull(CompassMath.azimuth(doubleArrayOf(0.0, 0.0, 1.0)))
    }

    @Test
    fun smoothingWrapsAroundNorth() {
        close(0.0, CompassMath.smooth(359.0, 1.0, 0.5))
        close(355.0, CompassMath.smooth(350.0, 0.0, 0.5))
        close(10.0, CompassMath.smooth(null, 370.0, 0.2))
        var s: Double? = null
        repeat(40) { s = CompassMath.smooth(s, if (it % 2 == 0) 358.0 else 2.0, 0.2) }
        close(0.0, s, 1.0)
    }

    @Test
    fun theStepNeedsARotationVectorOrBothAccelerometerAndMagnetometer() {
        assertTrue(Compass.available(rotationVector = true, accelerometer = false, magnetometer = false))
        assertTrue(Compass.available(rotationVector = false, accelerometer = true, magnetometer = true))
        assertFalse(Compass.available(rotationVector = false, accelerometer = true, magnetometer = false))
        assertFalse(Compass.available(rotationVector = false, accelerometer = false, magnetometer = true))
    }

    /** The compass code stores, logs and sends nothing, and needs no permission. */
    @Test
    fun theCompassCodeKeepsNothing() {
        val app = listOf(File("."), File("app")).first { File(it, "src/karte").isDirectory }
        val files = listOf(
            File(app, "src/karte/kotlin/ch/lessnoise/app/compass/Compass.kt"),
            File(app, "src/karte/kotlin/ch/lessnoise/app/compass/CompassMath.kt"),
            File(app, "src/karte/kotlin/ch/lessnoise/app/ui/ReportDirectionUi.kt"),
        )
        val bad = Regex("""\bLog\.|println|SharedPreferences|putString|File\b|HttpURLConnection|Uploader|ReportQueue|android\.location""")
        for (f in files) {
            val code = f.readText().lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            assertNull(bad.find(code)?.value, f.name)
        }
        for (m in listOf("src/main/AndroidManifest.xml", "src/public/AndroidManifest.xml", "src/laborKarte/AndroidManifest.xml")) {
            val t = File(app, m).readText()
            assertFalse(t.contains("HIGH_SAMPLING_RATE_SENSORS") || t.contains("BODY_SENSORS"), m)
        }
    }
}
