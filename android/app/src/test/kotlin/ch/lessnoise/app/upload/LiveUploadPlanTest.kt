package ch.lessnoise.app.upload

import ch.lessnoise.app.upload.LiveUploadPlan.Kind.EVENTS
import ch.lessnoise.app.upload.LiveUploadPlan.Kind.FULL
import ch.lessnoise.app.upload.LiveUploadPlan.Result.FAILED
import ch.lessnoise.app.upload.LiveUploadPlan.Result.OK
import ch.lessnoise.app.upload.LiveUploadPlan.Result.RATE_LIMITED
import kotlin.test.Test
import kotlin.test.assertEquals

/** 0.6.3 live map: the rules of [LiveUploadPlan] (the request counts are in UploadCadenceTest). */
class LiveUploadPlanTest {
    private val s = 1_000L
    private val min = 60_000L

    private fun LiveUploadPlan.runNext(result: LiveUploadPlan.Result = OK): LiveUploadPlan.Run =
        next().also { done(it, result, it.atMs) }

    @Test
    fun fullRunsThirtySecondsAfterThePointThenEveryTwoMinutes() {
        val p = LiveUploadPlan(0)
        assertEquals(LiveUploadPlan.Run(30 * s, FULL), p.runNext())
        assertEquals(LiveUploadPlan.Run(30 * s + 2 * min, FULL), p.runNext())
        assertEquals(LiveUploadPlan.Run(30 * s + 4 * min, FULL), p.runNext())
    }

    @Test
    fun aLoudEventGoesOutThirtySecondsAfterItEnded() {
        val p = LiveUploadPlan(0)
        p.runNext() // 0:30 full
        p.onEvent(50 * s)
        assertEquals(LiveUploadPlan.Run(80 * s, EVENTS), p.runNext())
        // The full slot stays where it was.
        assertEquals(LiveUploadPlan.Run(150 * s, FULL), p.runNext())
    }

    @Test
    fun eventsCoalesceAtMostOneRunPerThirtySeconds() {
        val p = LiveUploadPlan(0)
        p.runNext() // 0:30
        p.onEvent(40 * s)
        p.onEvent(45 * s)
        p.onEvent(55 * s)
        assertEquals(LiveUploadPlan.Run(70 * s, EVENTS), p.runNext())
        // An event ending right after that run waits for the 30-second gap.
        p.onEvent(75 * s)
        assertEquals(LiveUploadPlan.Run(105 * s, EVENTS), p.runNext())
        p.onEvent(106 * s)
        // Due at 136 s, within 30 s of the full run at 150 s: merged into it, at 136 s.
        assertEquals(LiveUploadPlan.Run(136 * s, FULL), p.runNext())
        assertEquals(LiveUploadPlan.Run(136 * s + 2 * min, FULL), p.next())
    }

    @Test
    fun windIsNotAnEventHereAndAnEventBeforeThePointWaitsForTheFirstRun() {
        val p = LiveUploadPlan(0)
        p.onEvent(0) // nothing yet (LiveState without an event)
        assertEquals(LiveUploadPlan.Run(30 * s, FULL), p.next())
        p.onEvent(5 * s) // due at 35 s, within 30 s of the first full run: merged
        assertEquals(LiveUploadPlan.Run(30 * s, FULL), p.next())
    }

    @Test
    fun aRateLimitBacksOffAndFoldsEventsIntoSlowerFullRuns() {
        val p = LiveUploadPlan(0)
        val first = p.runNext(RATE_LIMITED) // 0:30, 429
        assertEquals(1, p.backOffLevel)
        assertEquals(LiveUploadPlan.Run(first.atMs + 4 * min, FULL), p.next())
        p.onEvent(first.atMs + 10 * s) // ignored as a separate run while backed off
        assertEquals(FULL, p.next().kind)
        val second = p.runNext(RATE_LIMITED)
        assertEquals(2, p.backOffLevel)
        assertEquals(second.atMs + 8 * min, p.next().atMs)
        repeat(5) { p.runNext(RATE_LIMITED) }
        assertEquals(LiveUploadPlan.MAX_LEVEL, p.backOffLevel)
        val t = p.next().atMs
        p.runNext(OK)
        assertEquals(t + 16 * min, p.next().atMs, "still slow within the hour after the last 429")
        // An hour without 429: one level faster per hour.
        var last = p.next()
        while (last.atMs < t + 61 * min) { p.runNext(OK); last = p.next() }
        assertEquals(LiveUploadPlan.MAX_LEVEL - 1, p.backOffLevel)
    }

    @Test
    fun noNetworkOrAServerErrorWaitsForTheNextFullSlot() {
        val p = LiveUploadPlan(0)
        p.runNext()
        p.onEvent(40 * s)
        val r = p.runNext(FAILED)
        assertEquals(LiveUploadPlan.Run(70 * s, EVENTS), r)
        assertEquals(LiveUploadPlan.Run(70 * s + 2 * min, FULL), p.next())
        assertEquals(0, p.backOffLevel)
    }
}
