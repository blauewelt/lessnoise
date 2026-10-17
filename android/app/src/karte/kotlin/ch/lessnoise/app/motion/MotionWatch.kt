package ch.lessnoise.app.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import ch.lessnoise.app.AppContainer
import ch.lessnoise.app.edition.EditionDiagnostics
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking

/**
 * «Unterwegs erkannt» (0.6.7, editions with the map only; the Offline and Labor builds get the
 * stand-in src/nomap/.../motion/MotionWatch.kt): while a measurement started with «Messen» runs, the
 * measuring foreground service reads the accelerometer and feeds [MotionDetector]. While it says
 * MOVING, LiveState.moving is true: the «Sehr laut» notification works as in «Unterwegs»
 * (LoudAlertRule.shouldAlert) and the time span is stored as a moving span (MovingSpanEntity, only
 * start and end), whose minutes and events are never sent (upload/PlaceIndex). No permission is
 * needed; no location is read; no sensor value is stored or sent (the Labor·Karte build writes the
 * transitions with their feature values to its local manifest.jsonl, EditionDiagnostics).
 */
object MotionWatch {
    const val AVAILABLE = true

    /**
     * 20 Hz. Walking is 1–2 steps per second with harmonics to about 5 Hz, cycling 1–1.5 Hz pedalling
     * plus broadband vibration; the detector uses only the per-second deviation of the magnitude, for
     * which 20 samples per second are ample (≥ [MotionDetector.MIN_SAMPLES_PER_SECOND] even with
     * jitter). Far below the 200 Hz above which Android 12+ wants HIGH_SAMPLING_RATE_SENSORS, and a
     * rate the sensor hub serves at a few tens of µA.
     */
    const val SAMPLING_PERIOD_US = 50_000

    /**
     * Sensor batching: the hub may hold samples up to 5 s and deliver them in one burst (phones
     * without a FIFO deliver at once): one delivery of about 100 samples every 5 s instead of 20
     * callbacks per second over an 8-hour night. 5 s keeps the decision timely (it is added to the
     * upload hold, Uploader.MOTION_HOLD_MS).
     */
    const val MAX_REPORT_LATENCY_US = 5_000_000

    /** Every so often the detector's time is moved on without samples (a sensor that went silent cannot keep MOVING). */
    private const val TICK_MS = 30_000L

    private const val TAG = "MotionWatch"

    /** A running watch; [stop] ends it (and the open moving span). */
    interface Handle {
        fun stop()
    }

    /**
     * Starts watching for the running «Messen» measurement. Null if the phone has no accelerometer or
     * the listener could not be registered (then the measurement behaves as before 0.6.7).
     */
    fun start(context: Context, container: AppContainer): Handle? {
        val app = context.applicationContext
        val sm = app.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
        val thread = HandlerThread("lessnoise-motion").apply { start() }
        val handler = Handler(thread.looper)
        val detector = MotionDetector()

        fun apply(transitions: List<MotionDetector.Transition>) {
            for (t in transitions) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val nowWall = System.currentTimeMillis()
                val atWall = MotionClock.wallMs(t.atMs, nowElapsed, nowWall)
                val moving = t.to == MotionDetector.State.MOVING
                container.live.update { if (it.running && !it.unterwegs) it.copy(moving = moving) else it }
                // On this thread, in order: a span's end can never be written before its start.
                runCatching {
                    runBlocking {
                        if (moving) container.measurements.beginMovingSpan(MotionClock.wallMs(t.spanStartMs, nowElapsed, nowWall))
                        else container.measurements.endMovingSpans(atWall)
                    }
                }.onFailure { Log.w(TAG, "span write failed: ${it.javaClass.simpleName}") }
                EditionDiagnostics.motionTransition(app, atWall, t.to.name, MotionClock.wallMs(t.spanStartMs, nowElapsed, nowWall), t.features())
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val tMs = MotionClock.sampleMs(e.timestamp, SystemClock.elapsedRealtimeNanos())
                apply(detector.onSample(tMs, e.values[0], e.values[1], e.values[2]))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val tick = object : Runnable {
            override fun run() {
                // Samples may arrive up to the batching latency late: only move past older seconds.
                apply(detector.advanceTo(SystemClock.elapsedRealtime() - 2 * MAX_REPORT_LATENCY_US / 1000))
                handler.postDelayed(this, TICK_MS)
            }
        }
        val ok = runCatching { sm.registerListener(listener, sensor, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US, handler) }.getOrDefault(false)
        if (!ok) {
            thread.quitSafely()
            return null
        }
        handler.postDelayed(tick, TICK_MS)
        return object : Handle {
            override fun stop() {
                runCatching { sm.unregisterListener(listener) }
                handler.removeCallbacks(tick)
                handler.post {
                    // The measurement ends: so does a moving span (its end is the stop).
                    if (detector.state == MotionDetector.State.MOVING) {
                        runCatching { runBlocking { container.measurements.endMovingSpans(System.currentTimeMillis()) } }
                        EditionDiagnostics.motionTransition(app, System.currentTimeMillis(), "STOP", System.currentTimeMillis(), emptyMap())
                    }
                    container.live.update { it.copy(moving = false) }
                    thread.quitSafely()
                }
            }
        }
    }
}
