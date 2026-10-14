package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

/** What the site call needs besides the contributor's own answers (server/DESIGN.md §4.2). */
data class SiteExtras(
    /** `android.os.Build.MODEL`. */
    val deviceModel: String,
    /** UNPROCESSED | VOICE_RECOGNITION */
    val audioSource: String,
    val calibrated: Boolean,
    val calibrationOffsetDb: Double,
)

/**
 * The exact request bodies of server/DESIGN.md §4.2–4.4 and the reports call, built from the app's
 * stored records. Since 0.6.2 every minute and event carries the `point` of its measurement: the
 * LV95 point determined at «Start», already moved on the phone by the location noise
 * (dsp geo/LocationNoise.kt, DESIGN.md §2 rule 2), with its σ. The true place is never sent.
 * Records of a measurement without a point are not sent at all.
 *
 * Left out on purpose: the `orig_*` / `recalibrated_*` columns (§4.3), the events' top-3 raw
 * AudioSet labels (`topLabelsJson`, §2.1: fingerprinting surface for no gain), classifier frame
 * counts, the database row ids, the detector-v2 features (local floor, excess, rise/decay, LF share,
 * flutter, shape, wind flag) and the minutes' `wind_event_count`. Levels are rounded to 0.1 dB as
 * the design says.
 *
 * Wind-flagged events are not sent at all ([isShared]); the minutes' `event_count` is already the
 * count without wind (`MinuteEntity.eventCount`, detector v2), i.e. the count the app shows.
 */
object Payloads {

    /** Category id the server stores for an event that has none (classifier was off). */
    const val NO_CATEGORY = "unclassified"

    /** `{"e": …, "n": …, "sigma_m": …}`: a shifted point (DESIGN.md §4.3). */
    fun pointJson(p: LocationNoise.Point): JsonObject = buildJsonObject {
        put("e", p.e)
        put("n", p.n)
        put("sigma_m", p.sigmaM)
    }

    /** The placement details, with the shifted point of the newest shared measurement ([point]). */
    fun site(point: LocationNoise.Point, site: SiteSettings, extras: SiteExtras): JsonObject = buildJsonObject {
        put("point", pointJson(point))
        put("placement", site.placement)
        put("floor", site.floor?.let { JsonPrimitive(it) } ?: JsonNull)
        put("street_facing", site.streetFacing?.let { JsonPrimitive(it) } ?: JsonNull)
        put("device_model", extras.deviceModel.trim().take(64).ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("audio_source", extras.audioSource)
        put("calibrated", extras.calibrated)
        put("calibration_offset_db", num(extras.calibrationOffsetDb, 2))
        put("note", site.note.trim().take(SiteSettings.NOTE_MAX).ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    /**
     * Whether an event is uploaded at all: events flagged as wind on the microphone are excluded,
     * the same way they are left out of the app's own counts (server/DESIGN.md §4.4).
     */
    fun isShared(e: EventEntity): Boolean = !e.wind

    fun minute(m: MinuteEntity, point: LocationNoise.Point): JsonObject = buildJsonObject {
        put("start", m.startIso)
        put("point", pointJson(point))
        put("duration_s", num(m.durationSeconds, 3))
        put("valid_s", num(m.validSeconds, 3))
        put("coverage", num(m.coverage, 3))
        put("laeq_db", level(m.laeqDb))
        put("lafmax_db", level(m.lafMaxDb))
        put("lafmin_db", level(m.lafMinDb))
        put("l1_db", level(m.l1Db))
        put("l10_db", level(m.l10Db))
        put("l50_db", level(m.l50Db))
        put("l90_db", level(m.l90Db))
        // Without wind events (detector v2); windEventCount is not sent.
        put("event_count", m.eventCount)
        put("dominant_category", m.dominantCategory?.let { JsonPrimitive(it) } ?: JsonNull)
        put("category_shares", shares(m.categorySharesJson))
        put("calibration_id", m.calibrationId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("calibration_offset_db", num(m.calibrationOffsetDb, 2))
        put("calibrated", m.calibrated)
        put("audio_source", m.audioSource)
        put("clock_corrections", m.clockCorrections)
    }

    fun event(e: EventEntity, point: LocationNoise.Point): JsonObject = buildJsonObject {
        put("start", e.startIso)
        put("point", pointJson(point))
        put("duration_s", num(e.durationSeconds, 3))
        put("lafmax_db", level(e.lafMaxDb))
        put("sel_db", level(e.selDb))
        put("background_db", level(e.backgroundDb))
        put("threshold_db", num(e.thresholdDb, 1))
        put("min_level_db", level(e.minLevelDb))
        put("category", e.dominantCategory ?: NO_CATEGORY)
        put("category_score", num(e.dominantScore.toDouble(), 3))
        put("calibration_id", e.calibrationId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        put("calibrated", e.calibrated)
    }

    /**
     * One «Laut! Melden» report: time of the tap (ISO-8601 with offset, seconds), the exact place
     * (5 decimals ≈ 1 m), its accuracy and the category.
     */
    fun report(r: PendingReport): JsonObject = buildJsonObject {
        put("time", r.timeIso)
        put("lat", num(r.lat, 5))
        put("lon", num(r.lon, 5))
        put("accuracy_m", num(r.accuracyM, 1))
        put("category", r.category)
    }

    /** One decimal; NaN/∞/null become JSON null (NaN is not valid JSON and would spoil the batch). */
    fun level(v: Double?): JsonPrimitive = num(v, 1)

    fun num(v: Double?, decimals: Int): JsonPrimitive {
        if (v == null || v.isNaN() || v.isInfinite()) return JsonNull
        var f = 1.0
        repeat(decimals) { f *= 10 }
        return JsonPrimitive((v * f).roundToLong() / f)
    }

    private fun shares(json: String): JsonObject = try {
        buildJsonObject {
            Json.parseToJsonElement(json).jsonObject.forEach { (k, v) ->
                v.jsonPrimitive.doubleOrNull?.let { d -> if (!d.isNaN() && !d.isInfinite()) put(k, num(d, 3)) }
            }
        }
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}
