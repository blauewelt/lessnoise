package ch.lessnoise.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The 0.9.5 start/end rules ([EventRules]): rise condition, end margin, 30 s cap and hold; V2 unchanged. */
class EventRulesTest {
    private val tick = 6000

    private class Collector : EventDetector.Listener {
        val closed = ArrayList<DetectedEvent>()
        override fun onClosed(event: DetectedEvent) { closed += event }
    }

    /** Feeds [levels] (dB, one per 125 ms tick) at a fixed local floor [floor]; null = an invalid tick. */
    private fun run(levels: List<Double?>, rules: EventRules, excess: Double = 6.5, floor: Double = 50.0): Collector {
        val c = Collector()
        val d = EventDetector(excessDb = excess, rules = rules, listener = c)
        d.floorDb = floor
        levels.forEachIndexed { i, l ->
            val end = (i + 1L) * tick
            if (l == null) d.interrupt(end - tick) else d.onTick(end, l, l, l)
        }
        d.flush(levels.size.toLong() * tick)
        return c
    }

    private fun rep(v: Double, n: Int) = List(n) { v }
    private fun ramp(from: Double, to: Double, perTick: Double): List<Double> {
        val n = Math.round((to - from) / perTick).toInt()
        return List(n + 1) { from + it * perTick }
    }

    @Test
    fun v2IsTheDefaultEverywhere() {
        assertEquals(EventRules.V2, EventRules())
        assertEquals(EventRules.V2, EngineConfig().eventRules)
        assertEquals(EventRules.V2, EventDetector(listener = Collector()).rules)
        assertEquals(300.0, EventDetector(listener = Collector()).maxDurationSeconds)
        assertEquals("v2", EventRules.V2.id)
        assertEquals("v3", EventRules.V3.id)
        assertEquals("custom", EventRules.V3.copy(riseDb = 6.0).id)
    }

    @Test
    fun v3IsTheReplayedRuleSet() {
        val r = EventRules.V3
        assertEquals(3.5, r.riseDb)
        assertEquals(3.0, r.riseWindowSeconds)
        assertEquals(3.0, r.endMarginDb)
        assertEquals(30.0, r.maxDurationSeconds)
        assertTrue(r.holdAfterMax)
        assertEquals(30.0, EventDetector(rules = r, listener = Collector()).maxDurationSeconds)
    }

    @Test
    fun invalidRulesAreRejected() {
        assertFailsWith<IllegalArgumentException> { EventRules(riseDb = -1.0) }
        assertFailsWith<IllegalArgumentException> { EventRules(riseWindowSeconds = 0.0) }
        assertFailsWith<IllegalArgumentException> { EventRules(endMarginDb = -0.5) }
        assertFailsWith<IllegalArgumentException> { EventRules(maxDurationSeconds = 0.0) }
    }

    @Test
    fun riseRuleRejectsASlowCreepButKeepsAPassBy() {
        // 1 dB/s (3 dB in 3 s) from the floor to 12 dB above it, 2 s there, back down.
        val slow = rep(50.0, 40) + ramp(50.0, 62.0, 0.125) + rep(62.0, 16) + rep(50.0, 40)
        assertEquals(1, run(slow, EventRules.V2).closed.size) // v2: an event at 56.5 dB
        assertEquals(0, run(slow, EventRules.V3).closed.size) // never 3.5 dB above the last 3 s
        // 2 dB/s (6 dB in 3 s): a pass-by. Starts at the same tick as v2.
        val fast = rep(50.0, 40) + ramp(50.0, 62.0, 0.25) + rep(62.0, 16) + rep(50.0, 40)
        val v3 = run(fast, EventRules.V3).closed
        val v2 = run(fast, EventRules.V2).closed
        assertEquals(1, v3.size)
        assertEquals(v2.single().startSample, v3.single().startSample)
    }

    @Test
    fun riseIsMeasuredAgainstTheLowestLevelOfTheWindow() {
        // A swell at 55 dB for 5 s, then a step to 59 (4 dB in one tick): starts on the step, not before.
        val c = run(rep(50.0, 40) + rep(55.0, 40) + rep(59.0, 16) + rep(50.0, 40), EventRules(riseDb = 3.5))
        assertEquals(1, c.closed.size)
        assertEquals(80L * tick, c.closed[0].startSample)
        // A 3 dB step is not enough.
        assertEquals(0, run(rep(50.0, 40) + rep(55.0, 40) + rep(58.0, 16) + rep(50.0, 40), EventRules(riseDb = 3.5)).closed.size)
    }

    @Test
    fun riseNeedsHistoryAfterInvalidAudio() {
        val rules = EventRules(riseDb = 3.5)
        // Right after a gap there is nothing to rise from: no start on the first valid tick …
        val c = run(rep(50.0, 40) + listOf<Double?>(null) + rep(70.0, 16) + rep(50.0, 40), rules)
        assertEquals(0, c.closed.size)
        // … a rise after one second of valid audio is an event again.
        assertEquals(1, run(rep(50.0, 40) + listOf<Double?>(null) + rep(50.0, 8) + rep(70.0, 16) + rep(50.0, 40), rules).closed.size)
    }

    @Test
    fun endMarginEndsWithinThreeDbOfTheFrozenFloor() {
        // After the peak the level stays 3 dB above the floor for 1 s: below v2's end (floor + 3.5), not below 3 dB.
        val levels = rep(50.0, 40) + rep(70.0, 8) + rep(53.0, 8) + rep(50.0, 40)
        assertEquals(1.0, run(levels, EventRules.V2).closed.single().durationSeconds, 1e-9)
        assertEquals(2.0, run(levels, EventRules(endMarginDb = 3.0)).closed.single().durationSeconds, 1e-9)
    }

    @Test
    fun endMarginIsNeverAboveTheV2End() {
        // Excess 5: v2 ends below floor + 2 dB; a 3 dB margin must not end the event earlier than that.
        val levels = rep(50.0, 40) + rep(70.0, 8) + rep(52.5, 8) + rep(50.0, 40)
        assertEquals(2.0, run(levels, EventRules(endMarginDb = 3.0), excess = 5.0).closed.single().durationSeconds, 1e-9)
        assertEquals(2.0, run(levels, EventRules.V2, excess = 5.0).closed.single().durationSeconds, 1e-9)
    }

    @Test
    fun capClosesAfter30sAndTheHoldPreventsASecondEventInTheSameSwell() {
        // 60 s at 20 dB above an unchanged floor, then the floor level, then a 2 s pass-by.
        val levels = rep(50.0, 40) + rep(70.0, 480) + rep(50.0, 40) + rep(70.0, 16) + rep(50.0, 40)
        val v2 = run(levels, EventRules.V2).closed
        assertEquals(listOf(60.0, 2.0), v2.map { it.durationSeconds })
        val held = run(levels, EventRules(maxDurationSeconds = 30.0, holdAfterMax = true)).closed
        assertEquals(listOf(30.0, 2.0), held.map { it.durationSeconds })
        // Without the hold the swell is cut into two 30 s events.
        val cut = run(levels, EventRules(maxDurationSeconds = 30.0)).closed
        assertEquals(listOf(30.0, 30.0, 2.0), cut.map { it.durationSeconds })
        // V3: the same as the hold (the plateau has no rise either).
        assertEquals(listOf(30.0, 2.0), run(levels, EventRules.V3).closed.map { it.durationSeconds })
    }

    @Test
    fun holdEndsWhenTheMovingFloorHasCaughtUp() {
        val c = Collector()
        val d = EventDetector(excessDb = 6.5, rules = EventRules(maxDurationSeconds = 30.0, holdAfterMax = true), listener = c)
        var i = 0L
        fun feed(v: Double, n: Int) = repeat(n) { i++; d.onTick(i * tick, v, v, v) }
        d.floorDb = 50.0
        feed(50.0, 40)
        feed(70.0, 240) // 30 s: closed by the cap, then held
        feed(70.0, 40)
        assertEquals(1, c.closed.size)
        d.floorDb = 68.0 // the local floor (L90 of the last 30 s) has caught up with the swell
        feed(70.0, 1) // 70 < 68 + 6.5 − 3: hold released
        feed(80.0, 16) // a pass-by on top of the swell
        feed(70.0, 8)
        assertEquals(2, c.closed.size)
        assertEquals(2.0, c.closed[1].durationSeconds, 1e-9)
    }

    @Test
    fun engineAppliesTheConfiguredRules() {
        // 10 s background, 45 s of steady noise 20 dB above it (rain-like), 10 s background.
        val bg = 3e-4
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(bg, 10.0, seed = 3),
            TestSignals.whiteNoise(bg * 10, 45.0, seed = 4),
            TestSignals.whiteNoise(bg, 10.0, seed = 5),
        )
        val v2 = Replay.run(signal, Replay.config()).events
        assertEquals(1, v2.size)
        assertTrue(v2[0].durationSeconds > 44.0, "v2: one ${v2[0].durationSeconds} s event")
        val v3 = Replay.run(signal, Replay.config().copy(eventRules = EventRules.V3)).events
        assertEquals(1, v3.size, "V3: the swell is one event")
        assertEquals(30.0, v3[0].durationSeconds, 1e-9)
        assertEquals(v2[0].startEpochMs, v3[0].startEpochMs)
    }

    @Test
    fun tickReplayReproducesTheEngine() {
        // ReplayRules (the sweep tool of REPLAY_2026-10.md) must give the engine's own events from its ticks.
        val bg = 3e-4
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(bg, 10.0, seed = 6),
            TestSignals.add(TestSignals.sine(1000.0, 0.02, 2.0), TestSignals.whiteNoise(bg, 2.0, seed = 7)),
            TestSignals.whiteNoise(bg, 5.0, seed = 8),
            TestSignals.whiteNoise(bg * 10, 40.0, seed = 9),
            TestSignals.whiteNoise(bg, 8.0, seed = 10),
        )
        for (rules in listOf(EventRules.V2, EventRules.V3)) {
            val r = Replay.run(signal, Replay.config().copy(eventRules = rules))
            val ticks = r.ticks.map { ReplayRules.Tick(it.endSample, it.lafDb, it.lafMaxDb, it.leqDb, it.valid) }
            val sim = ReplayRules.run(ticks, 6.5, EngineConfig.DEFAULT_EVENT_MIN_LEVEL_DB, rules, minHistory = 5.0)
            assertEquals(r.closed.map { it.first to it.second }, sim.map { it.startSample to it.endSample }, rules.id)
            assertEquals(2, sim.size, rules.id)
        }
    }
}
