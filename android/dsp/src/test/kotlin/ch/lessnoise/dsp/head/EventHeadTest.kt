package ch.lessnoise.dsp.head

import ch.lessnoise.dsp.classify.EventHead
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JSON head runner on its own: format, imputation, enabled flags, thresholds, swapping. */
class EventHeadTest {
    private val json = EventHead::class.java.getResourceAsStream(EventHead.DEFAULT_RESOURCE)!!.readBytes().toString(Charsets.UTF_8)
    private val head = EventHead.parse(json)

    @Test
    fun bundledHeadHas68InputsNineClassesAnd64HiddenUnits() {
        assertEquals(68, head.inputNames.size)
        assertEquals(listOf("carried", "poser", "road", "aircraft", "rail", "voices", "rain", "wind", "impulse"), head.classes)
        assertEquals(64, head.hidden)
        assertEquals(listOf("carried", "road", "aircraft"), head.classes.filterIndexed { i, _ -> head.enabled[i] })
        assertEquals("weniger-laerm-head-F1-MLP", head.name)
    }

    @Test
    fun everyInputIsProducedByHeadInputs() {
        val f = ch.lessnoise.dsp.EventFeatures(40.0, 10.0, 0.5, 1.0, 0.1, 5.0, 0.5, 1.0, false, "hump")
        val produced = HeadInputs.f1(emptyMap(), f, 60.0, 40.0, 2.0).keys
        assertEquals(emptyList(), head.missingInputs(produced))
    }

    @Test
    fun nanInputsAreTheTrainingMedian() {
        val o = Json.parseToJsonElement(json).jsonObject
        val median = (o.getValue("median") as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content.toDouble() }.toDoubleArray()
        val a = head.predict(DoubleArray(68) { Double.NaN })
        val b = head.predict(median)
        assertTrue(a.contentEquals(b))
        assertTrue(a.all { it in 0.0..1.0 })
    }

    @Test
    fun decisionUsesOnlyEnabledClassesAndTheirThresholds() {
        val p = DoubleArray(9) { 1.0 }
        assertEquals(listOf("carried", "road", "aircraft"), head.decide(p))
        val q = head.thresholds.copyOf().also { it[0] -= 1e-6 } // carried just below its threshold
        assertEquals(listOf("road", "aircraft"), head.decide(q))
    }

    /** A retrained head is a JSON swap: other thresholds/flags (and a subset of inputs) need no code change. */
    @Test
    fun swappedJsonChangesThresholdsAndFlags() {
        val o = Json.parseToJsonElement(json).jsonObject.toMutableMap()
        o["enabled"] = buildJsonObject { for (c in head.classes) put(c, JsonPrimitive(c == "rail")) }
        o["thresholds"] = buildJsonObject { for (c in head.classes) put(c, JsonPrimitive(0.5)) }
        o["name"] = JsonPrimitive("retrained")
        val h2 = EventHead.parse(kotlinx.serialization.json.JsonObject(o).toString())
        assertEquals("retrained", h2.name)
        assertEquals(listOf("rail"), h2.decide(DoubleArray(9) { 0.6 }))
        assertEquals(emptyList(), h2.decide(DoubleArray(9) { 0.4 }))
        val r = h2.evaluate(mapOf("excessDb" to 10.0))
        assertEquals(68, r.inputs.size)
        assertTrue(r.inputs.getValue("lafMaxDb").isNaN())
    }
}
