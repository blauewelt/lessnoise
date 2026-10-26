package ch.lessnoise.app.labor

import ch.lessnoise.dsp.EventRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 0.9.5 «Lärmspitzen-Regeln 0.9.5»: V3 by default in the Labor editions, V2 (up to 0.9.4) when switched off. */
class EventRulesSettingTest {
    @Test
    fun v3ByDefaultV2ForAbNights() {
        assertTrue(LaborSettings().eventRulesV3)
        assertEquals(EventRules.V3, Labor.eventRules(LaborSettings()))
        assertEquals(EventRules.V2, Labor.eventRules(LaborSettings(eventRulesV3 = false)))
        assertEquals("event_rules_v3", Labor.KEY_EVENT_RULES_V3)
    }
}
