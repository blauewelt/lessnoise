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
import ch.lessnoise.app.position.PositionWatch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking

/**
 * «Unterwegs erkannt» (0.6.7, editions with the map only; the Offline and Labor builds get the
 * stand-in src/nomap/.../motion/MotionWatch.kt): while a window measurement («Langzeitmessung starten»)
 * runs, the measuring foreground service reads the accelerometer and feeds [MotionDetector]. While it
 * says MOVING, LiveState.moving is true: the status chip says «Unterwegs erkannt · nichts auf die Karte»,
 * the «Sehr laut» notification works as in «Unterwegs messen» (the alert rule, src/karte/…/alert/), and
 * the time span is stored as a moving span (MovingSpanEntity, only start and end), whose minutes and
 * events are never sent (upload/PlaceIndex). No permission is needed; no location is read; no sensor
 * value is stored or sent (the Labor·Karte build writes the transitions with their feature values, and
 * one `motion_minute` line per minute, to its local manifest.jsonl, EditionDiagnostics).
 *
 * 0.9.4.6: the same in both editions with the map — [MotionDetector.Thresholds.ONLINE] (0.6.9–0.9.4.5
 * Labor·Karte used [MotionDetector.Thresholds.LABOR], for trams and trains), no readout line on «Messen»,
 * and no position signal: [PositionWatch] is the stand-in of src/noposition in both (AVAILABLE false),
 * so the plumbing below for it (0.6.9, [UnterwegsCombiner]) never runs. Only the invisible minute log
 * differs ([EditionDiagnostics.ENABLED]: Labor·Karte).
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

    /** 0.6.9: the watch of the running measurement, for [extraHoldMs] (null when none runs). */
    @Volatile private var current: (Long) -> Long = { 0L }

    /**
     * 0.6.9: the foreground-service type the measuring service adds for the position signal, or null.
     * 0.9.4.6: always null (no edition has the signal; PositionWatch.AVAILABLE is false).
     */
    fun positionForegroundType(context: Context): Int? =
        if (PositionWatch.AVAILABLE) PositionWatch.foregroundType(context) else null

    /**
     * 0.6.9: how long the upload must hold records at [nowMs] beyond the motion hold, so that a span the
     * position signal decides minutes later still covers them. 0.9.4.6: always 0 (no position signal).
     */
    fun extraHoldMs(nowMs: Long): Long = current(nowMs)

    /**
     * Starts watching for the running «Messen» measurement. Null if the phone has no accelerometer or
     * the listener could not be registered (then the measurement behaves as before 0.6.7).
     * [position] (0.6.9): the service runs with the foreground type «location», so the position signal
     * may run too (0.9.4.6: never — no edition adds that type).
     */
    fun start(context: Context, container: AppContainer, position: Boolean = false): Handle? {
        val app = context.applicationContext
        val sm = app.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
        val thread = HandlerThread("lessnoise-motion").apply { start() }
        val handler = Handler(thread.looper)
        // 0.9.4.6: the Labor manifest's minute line (Labor·Karte); the Online-Version computes no per-second values.
        val log = EditionDiagnostics.ENABLED
        // 0.9.4.6: never true (PositionWatch is the src/noposition stand-in in both editions with the map).
        val positionSignal = position && PositionWatch.AVAILABLE
        // Everything below runs on [thread]: the detector, the combiner, the minute summary, the span writes.
        val combiner = UnterwegsCombiner()
        val minutes = MinuteSummary()
        var positionWatch: PositionWatch.Handle? = null
        // 0.7.5: wall ms of the latest second the detector saw movement (≥ its enter level, or while it says
        // MOVING). The position may only switch to MOVING within 3 minutes of it (0.6.9–0.9.4.5 PositionRule).
        var lastMovementWall: Long? = null
        val enterStd = MotionDetector.Thresholds.ONLINE.enterStdMs2

        fun writeSpan(change: UnterwegsCombiner.Change?) {
            change ?: return
            container.live.update { if (it.running && !it.unterwegs) it.copy(moving = combiner.moving) else it }
            // On this thread, in order: a span's end can never be written before its start.
            runCatching {
                runBlocking {
                    when (change) {
                        is UnterwegsCombiner.Change.Begin -> container.measurements.beginMovingSpan(change.startMs)
                        is UnterwegsCombiner.Change.End -> container.measurements.endMovingSpans(change.atMs)
                    }
                }
            }.onFailure { Log.w(TAG, "span write failed: ${it.javaClass.simpleName}") }
        }

        fun logMinute(m: MinuteSummary.Minute?) {
            m ?: return
            EditionDiagnostics.motionMinute(
                app, m.startMs, m.medianMs2, m.maxMs2, m.seconds, Distance.rounded(positionWatch?.distanceM),
                if (combiner.moving) "MOVING" else "STILL", combiner.motion, combiner.position,
            )
        }

        val onSecond: ((Long, Double) -> Unit)? = if (!log && !positionSignal) null else { startMs, std ->
            val wall = MotionClock.wallMs(startMs, SystemClock.elapsedRealtime(), System.currentTimeMillis())
            if (combiner.motion || (!std.isNaN() && std >= enterStd)) lastMovementWall = wall
            if (log) logMinute(minutes.add(wall, std))
        }
        val detector = MotionDetector(MotionDetector.Thresholds.ONLINE, onSecond)

        fun apply(transitions: List<MotionDetector.Transition>) {
            for (t in transitions) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val nowWall = System.currentTimeMillis()
                val atWall = MotionClock.wallMs(t.atMs, nowElapsed, nowWall)
                val moving = t.to == MotionDetector.State.MOVING
                // 0.6.9: the run is «unterwegs» if motion or position says so (0.9.4.6: motion only, every edition).
                writeSpan(combiner.onMotion(moving, atWall, MotionClock.wallMs(t.spanStartMs, nowElapsed, nowWall)))
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
        // 0.6.9: the position signal, on the same thread (silently none without permission). 0.9.4.6: never.
        if (positionSignal) {
            handler.post {
                positionWatch = PositionWatch.start(app, handler, lastMovementMs = { lastMovementWall }) { t ->
                    writeSpan(combiner.onPosition(t.moving, t.atMs, t.spanStartMs))
                    EditionDiagnostics.motionTransition(
                        app, t.atMs, if (t.moving) "MOVING" else "STILL", t.spanStartMs,
                        linkedMapOf("position" to 1.0, "distanceM" to (Distance.rounded(t.distanceM) ?: 0).toDouble(), "thresholdM" to t.thresholdM),
                    )
                }
                val w = positionWatch
                if (w != null) current = { now -> w.holdMs(now) }
            }
        }
        return object : Handle {
            override fun stop() {
                runCatching { sm.unregisterListener(listener) }
                handler.removeCallbacks(tick)
                handler.post {
                    val now = System.currentTimeMillis()
                    // 0.6.9: a ride the position had begun to see but not confirmed is not shared either.
                    val pendingSince = positionWatch?.stop()
                    current = { 0L }
                    if (pendingSince != null && !combiner.moving) {
                        writeSpan(combiner.onPosition(true, now, pendingSince))
                    }
                    if (log) logMinute(minutes.flush())
                    // The measurement ends: so does a moving span (its end is the stop).
                    if (combiner.moving) {
                        runCatching { runBlocking { container.measurements.endMovingSpans(now) } }
                        EditionDiagnostics.motionTransition(app, now, "STOP", now, emptyMap())
                    }
                    container.live.update { it.copy(moving = false) }
                    thread.quitSafely()
                }
            }
        }
    }
}
