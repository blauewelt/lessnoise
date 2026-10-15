package ch.lessnoise.dsp.golden

import ch.lessnoise.dsp.EventFeatures
import ch.lessnoise.dsp.MinuteRecord
import ch.lessnoise.dsp.NoiseEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Golden fixtures for the Swift port in ios/LessNoiseCore (the iOS app's measuring core). The JVM
 * implementation is the reference: these tests run the production Kotlin code on fixed inputs and
 * write what it computes as JSON; `swift test` in ios/LessNoiseCore checks the Swift port against it.
 *
 * Every run writes to `build/ios-golden/` (so the generators stay exercised); with
 * `LESSNOISE_GOLDEN_DIR=<dir>` (ios/LessNoiseCore/regenerate-fixtures.sh) they write there instead,
 * i.e. into ios/LessNoiseCore/Tests/Fixtures/. Review the diff before committing.
 *
 * Doubles are written as JSON numbers (the JVM's shortest representation, which Swift's
 * `Double(String)` reads back bit-exactly); NaN and ±∞ as the strings "NaN", "Infinity", "-Infinity".
 */
object Golden {
    val dir: File = File(System.getProperty("lessnoise.goldenDir") ?: "build/ios-golden").also { it.mkdirs() }

    fun write(name: String, root: JsonElement) {
        val f = File(dir, name)
        f.writeText(goldenFormat(root) + "\n")
    }

    fun d(v: Double): JsonPrimitive = when {
        v.isNaN() -> JsonPrimitive("NaN")
        v == Double.POSITIVE_INFINITY -> JsonPrimitive("Infinity")
        v == Double.NEGATIVE_INFINITY -> JsonPrimitive("-Infinity")
        else -> JsonPrimitive(v)
    }

    fun d(v: Double?): JsonElement = if (v == null) JsonNull else d(v)

    fun doubles(a: DoubleArray): JsonArray = buildJsonArray { a.forEach { add(d(it)) } }
    fun doubles(a: List<Double>): JsonArray = buildJsonArray { a.forEach { add(d(it)) } }
    fun floats(a: FloatArray): JsonArray = buildJsonArray { a.forEach { add(d(it.toDouble())) } }
    fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)

    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    fun features(f: EventFeatures?): JsonElement = if (f == null) JsonNull else buildJsonObject {
        put("local_floor_db", d(f.localFloorDb)); put("excess_db", d(f.excessDb)); put("rise_s", d(f.riseS))
        put("decay_s", d(f.decayS)); put("jaggedness", d(f.jaggedness)); put("mid_band_rise_db", d(f.midBandRiseDb))
        put("lf_share", d(f.lfShare)); put("lf_flutter_db", d(f.lfFlutterDb)); put("wind", f.wind); put("shape", f.shape)
    }

    /** A MinuteRecord, every field (the Swift tests rebuild the record from this). */
    fun minute(m: MinuteRecord): JsonObject = buildJsonObject {
        put("start_ms", m.startEpochMs); put("start_iso", m.startIso); put("duration_s", d(m.durationSeconds))
        put("laeq_db", d(m.laeqDb)); put("lafmax_db", d(m.lafMaxDb)); put("lafmin_db", d(m.lafMinDb))
        put("l1_db", d(m.l1Db)); put("l10_db", d(m.l10Db)); put("l50_db", d(m.l50Db)); put("l90_db", d(m.l90Db))
        put("event_count", m.eventCount); put("dominant_category", str(m.dominantCategory))
        put("category_shares", buildJsonArray { m.categoryShares.forEach { (k, v) -> add(buildJsonArray { add(k); add(d(v)) }) } })
        put("classifier_frames", m.classifierFrames)
        put("calibration_id", m.calibrationId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("calibration_offset_db", d(m.calibrationOffsetDb)); put("audio_source", m.audioSource); put("calibrated", m.calibrated)
        put("valid_s", d(m.validSeconds)); put("clock_corrections", m.clockCorrections)
        put("original", m.original?.let { o -> doubles(listOf(o.laeqDb, o.lafMaxDb, o.lafMinDb, o.l1Db, o.l10Db, o.l50Db, o.l90Db)) } ?: JsonNull)
        put("recalibrated_from_id", str(m.recalibratedFromId)); put("recalibration_offset_db", d(m.recalibrationOffsetDb))
        put("local_floor_db", d(m.localFloorDb)); put("wind_event_count", m.windEventCount)
        put("coverage", d(m.coverage))
    }

    /** A NoiseEvent, every field. */
    fun event(e: NoiseEvent): JsonObject = buildJsonObject {
        put("start_ms", e.startEpochMs); put("start_iso", e.startIso); put("duration_s", d(e.durationSeconds))
        put("lafmax_db", d(e.lafMaxDb)); put("sel_db", d(e.selDb)); put("background_db", d(e.backgroundDb))
        put("threshold_db", d(e.thresholdDb)); put("dominant_category", str(e.dominantCategory))
        put("dominant_score", d(e.dominantScore.toDouble()))
        put("top_labels", buildJsonArray { e.topLabels.forEach { add(buildJsonArray { add(it.label); add(d(it.score.toDouble())) }) } })
        put("classifier_frames", e.classifierFrames)
        put("calibration_id", e.calibrationId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("audio_source", e.audioSource); put("calibrated", e.calibrated); put("min_level_db", d(e.minLevelDb))
        put("original", e.original?.let { o -> doubles(listOf(o.lafMaxDb, o.selDb, o.backgroundDb)) } ?: JsonNull)
        put("recalibrated_from_id", str(e.recalibratedFromId)); put("recalibration_offset_db", d(e.recalibrationOffsetDb))
        put("features", features(e.features))
    }
}

/**
 * The fixtures' layout: objects one key per line; arrays of numbers/strings on one line; arrays
 * of rows (arrays) one row per line; arrays of objects one object per element. Compact enough
 * for the repository, readable in a diff.
 */
fun goldenFormat(e: JsonElement, indent: String = ""): String = when (e) {
    is JsonObject -> if (e.isEmpty()) "{}" else e.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
        "$indent " + JsonPrimitive(k).toString() + ": " + goldenFormat(v, "$indent ")
    }
    is JsonArray -> when {
        e.isEmpty() -> "[]"
        e.all { it is JsonPrimitive || it is JsonNull } -> e.toString()
        else -> e.joinToString(",\n", "[\n", "\n$indent]") { v -> "$indent " + if (v is JsonArray) v.toString() else goldenFormat(v, "$indent ") }
    }
    else -> e.toString()
}

/**
 * SplitMix64, the portable PRNG of the golden signals (the same few lines are in the Swift test
 * support): kotlin.random.Random is not reproducible outside the JVM.
 */
class SplitMix64(seed: Long) {
    private var x = seed

    fun nextLong(): Long {
        x += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = x
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1): the top 53 bits. */
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
}
