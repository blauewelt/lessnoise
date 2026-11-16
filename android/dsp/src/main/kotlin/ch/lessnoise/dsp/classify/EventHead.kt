package ch.lessnoise.dsp.classify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.exp

/**
 * A per-event classifier that works on numbers computed from the event (levels, shape, envelope
 * and spectral features), not on audio. It sits next to [SoundClassifier]: the app keeps the
 * YAMNet category decision for display and upload and can log this head's output beside it.
 */
interface EventHeadClassifier {
    /** Model name (from the weights file). */
    val name: String
    /** Input names, index-aligned with [predict]'s argument. */
    val inputNames: List<String>
    /** Output class names, index-aligned with [predict]'s result. */
    val classes: List<String>

    /** One probability per class for one input vector (NaN = missing, imputed by the model). */
    fun predict(inputs: DoubleArray): DoubleArray

    /** Classes whose probability reaches their threshold, among the classes the model enables. */
    fun decide(scores: DoubleArray): List<String>

    /** Inputs taken by name from [features] (missing names are NaN), probabilities and decision. */
    fun evaluate(features: Map<String, Double>): EventHeadResult {
        val x = DoubleArray(inputNames.size) { features[inputNames[it]] ?: Double.NaN }
        val p = predict(x)
        return EventHeadResult(
            model = name,
            scores = classes.indices.associate { classes[it] to p[it] },
            decision = decide(p),
            inputs = inputNames.indices.associate { inputNames[it] to x[it] },
        )
    }
}

/**
 * The head's output for one event (logging only: nothing of it is shown, stored in the
 * measurement database or uploaded).
 *
 * @param scores class → probability (all classes, also disabled ones)
 * @param decision enabled classes at or above their threshold, in class order
 * @param inputs the model inputs as fed (before imputation; NaN = missing)
 */
data class EventHeadResult(
    val model: String,
    val scores: Map<String, Double>,
    val decision: List<String>,
    val inputs: Map<String, Double>,
)

/**
 * The 2-layer MLP head exported by the Labor training (`step3/train`, JSON format
 * "weniger-laerm-head"): `x := median where NaN; z = (x − mean)/scale; h = relu(z·W1 + b1);
 * p = sigmoid(h·W2 + b2)`; per class an `enabled` flag and a decision threshold. Pure Kotlin, no
 * allocation per call except the result.
 *
 * To use a retrained head, replace the JSON resource ([DEFAULT_RESOURCE]); inputs are matched by
 * name, so a head with other inputs works as long as [ch.lessnoise.dsp.head.HeadInputs] produces
 * them (names it does not produce are fed as NaN = the training median; see [missingInputs]).
 */
class EventHead private constructor(
    override val name: String,
    override val inputNames: List<String>,
    override val classes: List<String>,
    private val median: DoubleArray,
    private val mean: DoubleArray,
    private val scale: DoubleArray,
    private val w1: FloatArray, private val b1: FloatArray,
    private val w2: FloatArray, private val b2: FloatArray,
    val hidden: Int,
    val enabled: BooleanArray,
    val thresholds: DoubleArray,
) : EventHeadClassifier {
    private val z = DoubleArray(inputNames.size)
    private val h = DoubleArray(hidden)

    companion object {
        /** The bundled head (classpath resource of the dsp module). */
        const val DEFAULT_RESOURCE = "/ch/lessnoise/dsp/head/head_F1_MLP_v2.json"

        /**
         * The bundled head. Its [name] is the JSON's `name` + "/" + the resource file stem
         * (e.g. `weniger-laerm-head-F1-MLP/head_F1_MLP_v2`): the Labor exports of different
         * trainings carry the same JSON `name`, and the manifest must tell them apart.
         */
        fun loadDefault(): EventHead =
            requireNotNull(EventHead::class.java.getResourceAsStream(DEFAULT_RESOURCE)) { "missing $DEFAULT_RESOURCE" }
                .use { parse(it.readBytes().toString(Charsets.UTF_8), nameSuffix = "/" + DEFAULT_RESOURCE.substringAfterLast('/').removeSuffix(".json")) }

        fun parse(json: String, nameSuffix: String = ""): EventHead {
            val o = Json.parseToJsonElement(json).jsonObject
            fun strings(k: String) = o.getValue(k).jsonArray.map { it.jsonPrimitive.content }
            fun doubles(k: String) = o.getValue(k).jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
            val inputs = strings("inputs")
            val classes = strings("classes")
            fun tensor(k: String, vararg shape: Int): FloatArray {
                val t = o.getValue(k).jsonObject
                val sh = t.getValue("shape").jsonArray.map { it.jsonPrimitive.int }
                require(sh == shape.toList()) { "$k: shape $sh, expected ${shape.toList()}" }
                val bytes = Base64.getDecoder().decode(t.getValue("b64").jsonPrimitive.content)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                require(buf.remaining() == shape.fold(1) { a, b -> a * b }) { "$k: ${buf.remaining()} floats" }
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
            val hidden = o.getValue("b1").jsonObject.getValue("shape").jsonArray[0].jsonPrimitive.int
            val en = o.getValue("enabled").jsonObject
            val th = o.getValue("thresholds").jsonObject
            return EventHead(
                name = (o["name"]?.jsonPrimitive?.content ?: "event-head") + nameSuffix,
                inputNames = inputs,
                classes = classes,
                median = doubles("median"), mean = doubles("mean"), scale = doubles("scale"),
                w1 = tensor("W1", inputs.size, hidden), b1 = tensor("b1", hidden),
                w2 = tensor("W2", hidden, classes.size), b2 = tensor("b2", classes.size),
                hidden = hidden,
                enabled = BooleanArray(classes.size) { (en[classes[it]] as? JsonPrimitive)?.boolean ?: false },
                thresholds = DoubleArray(classes.size) { (th[classes[it]] as? JsonPrimitive)?.double ?: Double.NaN },
            ).also {
                require(it.median.size == inputs.size && it.mean.size == inputs.size && it.scale.size == inputs.size)
            }
        }
    }

    /** Input names of this head that [available] does not contain (fed as NaN, i.e. the training median). */
    fun missingInputs(available: Set<String>): List<String> = inputNames.filter { it !in available }

    @Synchronized
    override fun predict(inputs: DoubleArray): DoubleArray {
        require(inputs.size == inputNames.size)
        for (i in z.indices) {
            val x = if (inputs[i].isNaN()) median[i] else inputs[i]
            z[i] = (x - mean[i]) / scale[i]
        }
        for (j in 0 until hidden) {
            var s = b1[j].toDouble()
            for (i in z.indices) s += z[i] * w1[i * hidden + j]
            h[j] = if (s > 0) s else 0.0
        }
        val c = classes.size
        return DoubleArray(c) { k ->
            var s = b2[k].toDouble()
            for (j in 0 until hidden) s += h[j] * w2[j * c + k]
            1.0 / (1.0 + exp(-s))
        }
    }

    override fun decide(scores: DoubleArray): List<String> =
        classes.indices.filter { enabled[it] && scores[it] >= thresholds[it] }.map { classes[it] }
}
