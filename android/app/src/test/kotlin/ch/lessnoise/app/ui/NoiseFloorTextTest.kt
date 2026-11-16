package ch.lessnoise.app.ui

import ch.lessnoise.app.service.LiveState
import ch.lessnoise.dsp.NoiseFloor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The texts of the phone's noise floor (0.6.5): «≤ …» in «Grundrauschen», live, and the calibration line. */
class NoiseFloorTextTest {
    private val limit = NoiseFloor.Limit(NoiseFloor.Estimate("UNPROCESSED", -82.0, 1234), 113.0) // floor 31, limit 34

    @Test
    fun humTextShowsLessOrEqualOnlyWithALimit() {
        assertEquals("41,2 dB(A)", humText(41.2, null))
        assertEquals("≤ 34,0 dB(A)", humText(30.0, limit.limitDb))
        assertEquals("– dB(A)", humText(null, null))
    }

    @Test
    fun liveHumIsComparedRawWithTheRunningOffset() {
        val running = LiveState(running = true, audioSource = "UNPROCESSED", calibrationOffsetDb = 113.0)
        // raw = 33.5 − 113 = −79.5 ≤ −82 + 3 → at the limit.
        assertEquals(34.0, liveHumLimit(running.copy(backgroundDb = 33.5), limit)!!, 1e-9)
        // raw −78.9 > −79 → not.
        assertNull(liveHumLimit(running.copy(backgroundDb = 34.1), limit))
        // Another audio source, no level yet, or no floor: never.
        assertNull(liveHumLimit(running.copy(backgroundDb = 20.0, audioSource = "VOICE_RECOGNITION"), limit))
        assertNull(liveHumLimit(running, limit))
        assertNull(liveHumLimit(running.copy(backgroundDb = 20.0), null))
    }

    @Test
    fun calibrationLineNamesTheLimitAndTheMinutes() {
        assertEquals("Messgrenze dieses Telefons: ca. 34 dB(A) (aus 1234 Minuten geschätzt)", floorLine(limit))
    }
}
