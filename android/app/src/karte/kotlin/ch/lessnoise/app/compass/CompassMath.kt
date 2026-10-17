package ch.lessnoise.app.compass

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The arithmetic of the direction step of «Laut! Melden» (0.6.7), pure so that it is tested on the
 * JVM (src/testKarte). Android's rotation matrix R (SensorManager.getRotationMatrix /
 * getRotationMatrixFromVector, row-major 3×3) turns a vector in the phone's own axes (X right, Y up
 * the screen in the natural orientation, Z out of the screen) into world axes (X east, Y north,
 * Z up).
 *
 * Where does the phone «point»?
 * - held flat (screen to the sky): the top edge of the screen as the user sees it. That depends on
 *   the display rotation: the screen's «up» is the phone's +Y in portrait, +X at ROTATION_90 (phone
 *   turned counter-clockwise), −Y at ROTATION_180, −X at ROTATION_270 (Display.getRotation: the
 *   graphics turn the opposite way of the phone).
 * - held upright (screen to the face, like taking a photo): the back of the phone, −Z, whatever the
 *   display rotation.
 * The one of the two that lies more horizontally wins, with some hysteresis so that the arrow does
 * not flip at 45° tilt.
 */
object CompassMath {
    enum class Hold { FLAT, UPRIGHT }

    /** The screen's «up» in the phone's axes for Surface.ROTATION_0..270 (0..3). */
    fun screenUp(displayRotation: Int): DoubleArray = when (displayRotation and 3) {
        0 -> doubleArrayOf(0.0, 1.0, 0.0)
        1 -> doubleArrayOf(1.0, 0.0, 0.0)
        2 -> doubleArrayOf(0.0, -1.0, 0.0)
        else -> doubleArrayOf(-1.0, 0.0, 0.0)
    }

    /** Out of the back of the phone (the camera's direction). */
    val BACK = doubleArrayOf(0.0, 0.0, -1.0)

    /** The other hold must be this much more horizontal before the arrow switches. */
    const val HYSTERESIS = 1.25

    /** R · v (R row-major 3×3). */
    fun toWorld(r: FloatArray, v: DoubleArray): DoubleArray {
        require(r.size >= 9)
        return DoubleArray(3) { i -> r[3 * i] * v[0] + r[3 * i + 1] * v[1] + r[3 * i + 2] * v[2] }
    }

    /** Length of the east/north part of a world vector. */
    fun horizontal(w: DoubleArray): Double = hypot(w[0], w[1])

    /** Degrees clockwise from north of a world vector's horizontal part; null if it points straight up or down. */
    fun azimuth(w: DoubleArray): Double? {
        if (horizontal(w) < 1e-6) return null
        return normalize(Math.toDegrees(atan2(w[0], w[1])))
    }

    /** Flat or upright, keeping [previous] unless the other is clearly more horizontal. */
    fun hold(r: FloatArray, displayRotation: Int, previous: Hold?): Hold {
        val up = horizontal(toWorld(r, screenUp(displayRotation)))
        val back = horizontal(toWorld(r, BACK))
        return when (previous) {
            Hold.FLAT -> if (back > up * HYSTERESIS) Hold.UPRIGHT else Hold.FLAT
            Hold.UPRIGHT -> if (up > back * HYSTERESIS) Hold.FLAT else Hold.UPRIGHT
            null -> if (back > up) Hold.UPRIGHT else Hold.FLAT
        }
    }

    /** The bearing the phone points at, clockwise from the north of R (magnetic for Android's sensors). */
    fun bearing(r: FloatArray, displayRotation: Int, hold: Hold): Double? =
        azimuth(toWorld(r, if (hold == Hold.UPRIGHT) BACK else screenUp(displayRotation)))

    /**
     * Low-pass of an angle in degrees: averages unit vectors, so 359° and 1° give 0°, not 180°.
     * [alpha] is the weight of the new value (0..1]; [previous] null starts at [next].
     */
    fun smooth(previous: Double?, next: Double, alpha: Double): Double {
        if (previous == null) return normalize(next)
        val a = Math.toRadians(previous)
        val b = Math.toRadians(next)
        val x = (1 - alpha) * sin(a) + alpha * sin(b)
        val y = (1 - alpha) * cos(a) + alpha * cos(b)
        if (hypot(x, y) < 1e-9) return normalize(next)
        return normalize(Math.toDegrees(atan2(x, y)))
    }

    fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}
