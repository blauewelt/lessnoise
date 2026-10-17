package ch.lessnoise.app.motion

import ch.lessnoise.app.alert.LoudAlertRule
import ch.lessnoise.app.service.LiveEvent
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.6.7 «Unterwegs erkannt»: the motion detector on synthetic accelerometer signals (20 Hz, as
 * MotionWatch asks for), and the alert while MOVING in a «Messen» measurement. src/testKarte: runs for
 * public and laborKarte.
 */
class MotionDetectorTest {
    private val g = 9.80665
    private val hz = 20
    private val dt = 1000L / hz

    /** A signal: acceleration (x, y, z) in m/s² at time t (s). */
    private fun interface Signal {
        fun at(t: Double, rnd: Random): Triple<Double, Double, Double>
    }

    /** Lying flat, sensor noise σ = 0.02 m/s² per axis (a typical MEMS accelerometer at rest). */
    private val still = Signal { _, r -> Triple(noise(r, 0.02), noise(r, 0.02), g + noise(r, 0.02)) }

    /**
     * Walking with the phone in a trouser pocket, upright: 1.8 steps/s, vertical bounce ±3 m/s² with
     * its second harmonic, forward sway ±1.5 m/s² at the stride frequency, plus noise. Tilted a bit.
     */
    private val walking = Signal { t, r ->
        val v = 3.0 * sin(2 * PI * 1.8 * t) + 1.0 * sin(2 * PI * 3.6 * t + 0.7)
        val f = 1.5 * sin(2 * PI * 0.9 * t)
        Triple(f + noise(r, 0.2), g * 0.2 + noise(r, 0.2), g * 0.98 + v + noise(r, 0.2))
    }

    /** Walking with the phone held in the hand, reading it: weaker, ±1 m/s². */
    private val walkingInHand = Signal { t, r ->
        val v = 1.0 * sin(2 * PI * 1.7 * t) + 0.3 * sin(2 * PI * 3.4 * t)
        Triple(0.4 * sin(2 * PI * 0.85 * t) + noise(r, 0.1), g * 0.5 + noise(r, 0.1), g * 0.87 + v + noise(r, 0.1))
    }

    /** Cycling, phone in a bag on the back: broadband road vibration σ ≈ 0.8 m/s² plus 1.2 Hz pedalling. */
    private val cycling = Signal { t, r ->
        Triple(noise(r, 0.8), 0.5 * sin(2 * PI * 1.2 * t) + noise(r, 0.8), g + noise(r, 0.8))
    }

    private fun noise(r: Random, sigma: Double): Double {
        // Box–Muller.
        val u = r.nextDouble().coerceAtLeast(1e-12)
        return sigma * kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * r.nextDouble())
    }

    private class Run(val det: MotionDetector = MotionDetector()) {
        var tMs = 1_000_000L
        val transitions = mutableListOf<MotionDetector.Transition>()
        /** State at the end of every second. */
        val states = mutableListOf<MotionDetector.State>()
    }

    private fun Run.feed(seconds: Int, s: Signal, rnd: Random = Random(1)) {
        val end = tMs + seconds * 1000L
        while (tMs < end) {
            val (x, y, z) = s.at(tMs / 1000.0, rnd)
            transitions += det.onSample(tMs, x.toFloat(), y.toFloat(), z.toFloat())
            tMs += dt
            if (tMs % 1000L == 0L) states += det.state
        }
    }

    /** A bump: the phone is knocked or picked up and put back — [ms] of large acceleration. */
    private fun Run.bump(ms: Long, peak: Double = 15.0, rnd: Random = Random(2)) {
        val end = tMs + ms
        while (tMs < end) {
            transitions += det.onSample(tMs, (peak * (rnd.nextDouble() - 0.5)).toFloat(), (peak * (rnd.nextDouble() - 0.5)).toFloat(), (g + peak * (rnd.nextDouble() - 0.5)).toFloat())
            tMs += dt
            if (tMs % 1000L == 0L) states += det.state
        }
    }

    private val S = MotionDetector.State.STILL
    private val M = MotionDetector.State.MOVING

    @Test
    fun stillWithSensorNoiseStaysStill() {
        val r = Run()
        r.feed(8 * 3600 / 8, still) // an hour (sped-up: 900 s is enough to see it never moves)
        r.feed(3600, still, Random(9))
        assertTrue(r.transitions.isEmpty())
        assertEquals(S, r.det.state)
    }

    @Test
    fun stillWithOneBumpStaysStill() {
        val r = Run()
        r.feed(120, still)
        r.bump(3_000) // a window shut hard, the phone knocked: 3 s
        r.feed(300, still)
        assertTrue(r.transitions.isEmpty(), "${r.transitions}")
    }

    @Test
    fun pickedUpLookedAtAndPutBackStaysStill() {
        val r = Run()
        r.feed(60, still)
        r.bump(2_000)
        r.feed(12, walkingInHand) // looked at it for 12 s, moving it around
        r.bump(1_000)
        r.feed(300, still)
        assertTrue(r.transitions.isEmpty(), "${r.transitions}")
    }

    @Test
    fun stillWithAFewBumpsSpreadOverMinutesStaysStill() {
        val r = Run()
        repeat(10) { i ->
            r.feed(50 + i * 7, still, Random(i))
            r.bump(2_000, rnd = Random(100 + i))
        }
        r.feed(200, still)
        assertTrue(r.transitions.isEmpty(), "${r.transitions}")
    }

    @Test
    fun walkingBecomesMovingAfterAboutTwentySeconds() {
        for (s in listOf(walking, walkingInHand)) {
            val r = Run()
            r.feed(60, still)
            val walkStart = r.tMs
            r.feed(60, s)
            assertEquals(1, r.transitions.size, "${r.transitions}")
            val t = r.transitions[0]
            assertEquals(M, t.to)
            val after = (t.atMs - walkStart) / 1000.0
            assertTrue(after in 19.0..26.0, "MOVING after $after s")
            // The span starts where the walk started (within a second).
            assertTrue(kotlin.math.abs(t.spanStartMs - walkStart) <= 1000, "span start ${t.spanStartMs - walkStart} ms off")
            assertTrue(t.activeInEnterWindow >= MotionDetector.ENTER_ACTIVE_S)
            assertTrue(t.features().keys.containsAll(listOf("activeEnter", "meanStdEnter", "lastStd", "lastSamples")))
        }
    }

    @Test
    fun cyclingVibrationBecomesMoving() {
        val r = Run()
        r.feed(30, still)
        r.feed(60, cycling)
        assertEquals(listOf(M), r.transitions.map { it.to })
    }

    @Test
    fun walkingThenAOneMinuteStopThenWalkingStaysMoving() {
        val r = Run()
        r.feed(60, walking)
        assertEquals(M, r.det.state)
        val n = r.transitions.size
        r.feed(60, still) // a red light, phone in the pocket
        r.feed(60, walking)
        assertEquals(M, r.det.state)
        assertEquals(n, r.transitions.size, "no change: ${r.transitions}")
        // Even a 2.5-minute wait at a tram stop.
        r.feed(150, still)
        r.feed(30, walking)
        assertEquals(n, r.transitions.size, "no change: ${r.transitions}")
    }

    @Test
    fun walkingThenFiveMinutesStillReturnsToStillAfterAboutThreeMinutes() {
        val r = Run()
        r.feed(120, walking)
        val stop = r.tMs
        r.feed(300, still)
        assertEquals(listOf(M, S), r.transitions.map { it.to })
        val back = (r.transitions[1].atMs - stop) / 1000.0
        assertTrue(back in 170.0..185.0, "STILL after $back s")
        assertEquals(r.transitions[1].atMs, r.transitions[1].spanStartMs)
        assertEquals(S, r.det.state)
    }

    @Test
    fun aFewBumpsWhileLyingAfterAWalkDoNotKeepItMoving() {
        val r = Run()
        r.feed(90, walking)
        r.feed(60, still); r.bump(2_000); r.feed(60, still); r.bump(2_000); r.feed(200, still)
        assertEquals(listOf(M, S), r.transitions.map { it.to })
    }

    @Test
    fun aSilentSensorCannotKeepItMoving() {
        val r = Run()
        r.feed(60, walking)
        assertEquals(M, r.det.state)
        // No samples for 4 minutes (sensor stopped delivering): time moves on, it returns to STILL.
        val out = r.det.advanceTo(r.tMs + 240_000)
        assertEquals(listOf(S), out.map { it.to })
        // Seconds with too few samples count as not active.
        val d = MotionDetector()
        var t = 0L
        repeat(60) { s -> repeat(3) { i -> d.onSample(s * 1000L + i * 300, 15f, 0f, 0f) }; t = s * 1000L }
        d.advanceTo(t + 2000)
        assertEquals(S, d.state)
    }

    @Test
    fun outOfOrderSamplesAreDropped() {
        val d = MotionDetector()
        d.onSample(10_000, 0f, 0f, 9.8f)
        assertTrue(d.onSample(5_000, 50f, 50f, 50f).isEmpty())
        assertEquals(S, d.state)
    }

    @Test
    fun thresholdsAreThoseDocumented() {
        assertEquals(0.25, MotionDetector.ACTIVE_STD_MS2)
        // 0.6.9: the Online-Version keeps 0.6.7's level; Labor·Karte enters at 0.07 and stays from 0.05.
        assertEquals(MotionDetector.Thresholds(0.25, 0.25), MotionDetector.Thresholds.ONLINE)
        assertEquals(MotionDetector.Thresholds(0.07, 0.05), MotionDetector.Thresholds.LABOR)
        assertEquals(MotionDetector.Thresholds.ONLINE, MotionDetector().thresholds)
        assertEquals(25, MotionDetector.ENTER_WINDOW_S)
        assertEquals(20, MotionDetector.ENTER_ACTIVE_S)
        assertEquals(180, MotionDetector.EXIT_WINDOW_S)
        assertEquals(10, MotionDetector.EXIT_ACTIVE_MAX_S)
        assertEquals(50_000, MotionWatch.SAMPLING_PERIOD_US)
        assertEquals(5_000_000, MotionWatch.MAX_REPORT_LATENCY_US)
        // The upload's hold covers the enter window, the decision second and the batching.
        val worst = MotionDetector.ENTER_WINDOW_S * 1000L + 1000L + MotionWatch.MAX_REPORT_LATENCY_US / 1000
        assertTrue(ch.lessnoise.app.upload.Uploader.MOTION_HOLD_MS >= worst, "hold ${ch.lessnoise.app.upload.Uploader.MOTION_HOLD_MS} < $worst")
    }

    @Test
    fun clockFallsBackToArrivalTimeForAForeignTimestampBase() {
        val now = 5_000_000_000_000L
        assertEquals((now - 3_000_000_000L) / 1_000_000L, MotionClock.sampleMs(now - 3_000_000_000L, now))
        assertEquals(now / 1_000_000L, MotionClock.sampleMs(123L, now))
        assertEquals(1_700_000_000_000L - 2_000L, MotionClock.wallMs(98_000L, 100_000L, 1_700_000_000_000L))
    }

    // ---- 0.6.9, Labor·Karte thresholds: trams and trains ------------------------------------------

    private fun labor() = Run(MotionDetector(MotionDetector.Thresholds.LABOR))

    /** A still phone with a noisier sensor (σ per axis), e.g. an old MEMS part. */
    private fun stillWith(sigma: Double) = Signal { _, r -> Triple(noise(r, sigma), noise(r, sigma), g + noise(r, sigma)) }

    /**
     * Riding a tram/train/bus, phone in a pocket or on the lap: broadband vibration σ ≈ 0.1 m/s² per
     * axis (smoother than walking: no steps), a slow lateral sway (0.4 Hz) and a weak 2.5 Hz bogie
     * component along gravity.
     */
    private val tram = Signal { t, r ->
        Triple(0.3 * sin(2 * PI * 0.4 * t) + noise(r, 0.1), noise(r, 0.1), g + 0.05 * sin(2 * PI * 2.5 * t) + noise(r, 0.1))
    }

    /** The tram standing at a stop: motors and air conditioning only, σ ≈ 0.03. */
    private val tramAtStop = stillWith(0.03)

    /** Tram ride: [legs] legs of 90 s driving with 25-s stops in between. */
    private fun Run.tramRide(legs: Int, rnd: Int = 30) {
        repeat(legs) { i ->
            feed(90, tram, Random(rnd + i))
            if (i < legs - 1) feed(25, tramAtStop, Random(rnd + 100 + i))
        }
    }

    @Test
    fun aTramRideIsMovingWithTheLaborThresholdsAndWasNotWithTheOnlineOnes() {
        // The owner's field result on 0.6.8: a tram ride never switched (0.25 is above a tram's ~0.1).
        val online = Run()
        online.feed(60, still)
        online.tramRide(4)
        assertTrue(online.transitions.isEmpty(), "Online thresholds: ${online.transitions}")
        // Labor·Karte: MOVING within about 25 s of the ride, and it stays MOVING through the stops.
        val r = labor()
        r.feed(60, still)
        val rideStart = r.tMs
        r.tramRide(4)
        assertEquals(listOf(M), r.transitions.map { it.to }, "${r.transitions}")
        val after = (r.transitions[0].atMs - rideStart) / 1000.0
        assertTrue(after in 19.0..30.0, "MOVING after $after s")
        assertEquals(M, r.det.state)
        assertTrue(r.transitions[0].features()["stayStd"] == 0.05)
    }

    @Test
    fun walkingToTheStopWaitingAndRidingKeepsItMoving() {
        val r = labor()
        r.feed(60, still)
        r.feed(120, walking) // to the stop
        assertEquals(1, r.transitions.size)
        // 2.5 minutes at the stop, phone in the pocket (the exit window stays 180 s, as in 0.6.7).
        r.feed(150, stillWith(0.03), Random(41))
        r.tramRide(5, rnd = 50)
        r.feed(90, walking, Random(60))
        assertEquals(listOf(M), r.transitions.map { it.to }, "${r.transitions}")
    }

    @Test
    fun laborThresholdsKeepAClearMarginAboveAStillPhone() {
        // An hour of a still phone with σ 0.02 and with a noisy σ 0.03 sensor: never MOVING.
        for (sigma in listOf(0.01, 0.02, 0.03)) {
            val r = labor()
            r.feed(3600, stillWith(sigma), Random(70))
            assertTrue(r.transitions.isEmpty(), "σ $sigma: ${r.transitions}")
        }
        // The bump cases of 0.6.7 with the lower levels.
        val b = labor()
        b.feed(120, still)
        b.bump(3_000)
        b.feed(60, still)
        b.bump(2_000)
        b.feed(12, walkingInHand)
        b.bump(1_000)
        b.feed(300, still)
        assertTrue(b.transitions.isEmpty(), "${b.transitions}")
        // After a ride, a phone put down (even a noisy σ 0.04 one) returns to STILL after about 3 minutes.
        for (sigma in listOf(0.02, 0.04)) {
            val r = labor()
            r.tramRide(2)
            val down = r.tMs
            r.feed(400, stillWith(sigma), Random(80))
            assertEquals(listOf(M, S), r.transitions.map { it.to }, "σ $sigma: ${r.transitions}")
            val back = (r.transitions[1].atMs - down) / 1000.0
            assertTrue(back in 170.0..200.0, "σ $sigma: STILL after $back s")
        }
        // Margins as documented: enter ≥ 2× the noisiest still phone's level (0.03), stay above it.
        assertTrue(MotionDetector.LABOR_ENTER_STD_MS2 >= 2 * 0.03)
        assertTrue(MotionDetector.LABOR_STAY_STD_MS2 > 0.03 && MotionDetector.LABOR_STAY_STD_MS2 < MotionDetector.LABOR_ENTER_STD_MS2)
    }

    @Test
    fun theSecondsAreReportedForTheLaborReadout() {
        val seen = mutableListOf<Pair<Long, Double>>()
        val r = Run(MotionDetector(MotionDetector.Thresholds.LABOR) { start, std -> seen += start to std })
        r.feed(10, tram)
        assertEquals(9, seen.size) // the 10th second is closed by the next sample
        assertTrue(seen.all { it.second in 0.07..0.2 }, "$seen")
        assertEquals(1_000_000L, seen[0].first)
    }

    // ---- The alert while MOVING (LoudAlertRule, LoudAlertWatcher) -------------------------------

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
        val watcher = File(repo(), "android/app/src/karte/kotlin/ch/lessnoise/app/alert/LoudAlertNotifier.kt").readText()
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
