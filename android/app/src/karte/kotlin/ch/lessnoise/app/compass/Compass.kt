package ch.lessnoise.app.compass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * The phone's compass for the direction step of «Laut! Melden» (0.6.7, editions with the map). Only
 * while that step is on screen: [start] when it opens, [stop] when it closes. No permission is needed
 * for these sensors. Each sensor event becomes one smoothed bearing in memory and is forgotten;
 * nothing here stores, logs or sends anything (the one value the person takes goes into the report).
 *
 * Sensors: Sensor.TYPE_ROTATION_VECTOR (fused by Android from accelerometer, magnetometer and,
 * where present, gyroscope); without it, accelerometer + magnetometer through
 * SensorManager.getRotationMatrix. [available] is false when the phone has neither, and the step is
 * then not shown at all. The accuracy class is the magnetometer's status when the phone has one
 * (that is what the figure-eight fixes), else the rotation vector's.
 *
 * The bearing is relative to MAGNETIC north; the report corrects it with the declination at its own
 * place (ReportDirectionStep.declination).
 */
class Compass(context: Context, private val displayRotation: () -> Int, private val onReading: (bearingDeg: Double, status: Int) -> Unit) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val rotationVector = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val r = FloatArray(9)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveGeomagnetic = false
    private var hold: CompassMath.Hold? = null
    private var smoothed: Double? = null
    private var magStatus = -1
    private var rvStatus = -1
    private var running = false

    val available: Boolean get() = available(rotationVector != null, accelerometer != null, magnetometer != null)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    rvStatus = e.accuracy
                    SensorManager.getRotationMatrixFromVector(r, e.values)
                    publish()
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(e.values, 0, gravity, 0, 3); haveGravity = true
                    if (rotationVector == null) fused()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magStatus = e.accuracy
                    System.arraycopy(e.values, 0, geomagnetic, 0, 3); haveGeomagnetic = true
                    if (rotationVector == null) fused()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            when (sensor.type) {
                Sensor.TYPE_MAGNETIC_FIELD -> magStatus = accuracy
                Sensor.TYPE_ROTATION_VECTOR -> rvStatus = accuracy
            }
        }
    }

    private fun fused() {
        if (haveGravity && haveGeomagnetic && SensorManager.getRotationMatrix(r, null, gravity, geomagnetic)) publish()
    }

    private fun publish() {
        val rot = displayRotation()
        val h = CompassMath.hold(r, rot, hold).also { hold = it }
        val b = CompassMath.bearing(r, rot, h) ?: return
        val s = CompassMath.smooth(smoothed, b, SMOOTHING).also { smoothed = it }
        onReading(s, status())
    }

    private fun status(): Int = if (magnetometer != null && magStatus >= 0) magStatus else rvStatus

    fun start() {
        val m = sm ?: return
        if (running || !available) return
        running = true
        if (rotationVector != null) {
            m.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_UI)
            // Only for its accuracy status (the figure-eight hint); the bearing comes from the rotation vector.
            magnetometer?.let { m.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        } else {
            m.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            m.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(listener)
        hold = null; smoothed = null; haveGravity = false; haveGeomagnetic = false
    }

    companion object {
        /** Weight of a new reading in the low-pass (SENSOR_DELAY_UI ≈ 16 per second): settles in about half a second. */
        const val SMOOTHING = 0.2

        /** The step is shown only with a rotation vector sensor, or with both accelerometer and magnetometer. */
        fun available(rotationVector: Boolean, accelerometer: Boolean, magnetometer: Boolean): Boolean =
            rotationVector || (accelerometer && magnetometer)
    }
}
