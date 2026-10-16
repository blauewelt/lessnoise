package ch.lessnoise.app.alert

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * The accelerometer for the motion gate of the «Sehr laut» notification (0.6.3). Registered only
 * while «Unterwegs» is on AND a measurement runs ([setActive], driven by [LoudAlertWatcher]);
 * otherwise the app does not touch the sensor. The samples go into [MotionDetector] in memory and
 * nowhere else: not stored, not logged, not exported, not sent. No permission is needed.
 */
class MotionMonitor(context: Context) {
    private val sm: SensorManager? = context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val detector = MotionDetector()
    private var registered = false

    /** No accelerometer at all: the gate is open (the feature must not silently break on such a phone). */
    val hasSensor: Boolean get() = sensor != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (v.size < 3) return
            detector.add(timestampNs(event.timestamp), v[0], v[1], v[2])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Registers the accelerometer while [active], unregisters it (and forgets all) otherwise. */
    @Synchronized
    fun setActive(active: Boolean) {
        val m = sm ?: return
        val s = sensor ?: return
        if (active && !registered) {
            detector.reset()
            registered = runCatching { m.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US) }.getOrDefault(false)
        } else if (!active && registered) {
            runCatching { m.unregisterListener(listener) }
            registered = false
            detector.reset()
        }
    }

    /** The phone moved within the last 5 minutes; always true on a phone without an accelerometer. */
    fun movedRecently(): Boolean = !hasSensor || detector.isMoving(SystemClock.elapsedRealtime())

    companion object {
        /** Batching: the sensor may hold samples this long before waking the app (battery). */
        const val MAX_REPORT_LATENCY_US = 5_000_000

        /**
         * Sensor timestamps are SystemClock.elapsedRealtimeNanos on current devices; one that is
         * more than 10 minutes off that clock (another time base) is replaced by the arrival time.
         */
        internal fun timestampNs(eventNs: Long, nowNs: Long = SystemClock.elapsedRealtimeNanos()): Long =
            if (abs(nowNs - eventNs) <= 10 * 60_000_000_000L) eventNs else nowNs
    }
}
