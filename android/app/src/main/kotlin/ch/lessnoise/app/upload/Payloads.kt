package ch.lessnoise.app.upload

import ch.lessnoise.app.audio.MicChoice
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.app.service.LiveEvent
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
 * Only map events are sent at all ([isMapEvent], 0.6.4: the five machine and motor categories, not
 * wind, the quality gates); the minutes' `event_count` is the count without wind
 * (`MinuteEntity.eventCount`, detector v2), i.e. the count the app shows.
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
     * The categories of the public map (0.6.4): machine and motor noise only. The same ids, in the
     * same order, as the server's allow-list (server/stadtlaerm_server/config.py MAP_CATEGORIES;
     * MapEventTest reads it from the source). The project's definition, not a setting.
     */
    val MAP_CATEGORIES: List<String> = listOf("loud_vehicle", "road_traffic", "rail_tram", "aircraft", "construction")

    /** Quality gates of a map event (the server's MIN_EVENT_THRESHOLD_DB, MIN_EVENT_LAFMAX_DB, MIN_EVENT_DURATION_S). */
    const val MIN_EXCESS_DB = 6.5
    const val MIN_LAFMAX_DB = 30.0
    const val MIN_DURATION_S = 0.5

    /**
     * Whether an event may leave the phone at all (0.6.4, the ONE rule for the upload's include
     * filter and the live-upload trigger): one of [MAP_CATEGORIES], not wind on the microphone, the
     * excess setting in force ([thresholdDb], sent as `threshold_db`) at least [MIN_EXCESS_DB], LAFmax
     * at least the event's own floor setting ([minLevelDb], none for events before 0.3.0) and at least
     * [MIN_LAFMAX_DB], and at least [MIN_DURATION_S] long. Voices, music, unclassified and wind never
     * leave the phone. Uses only fields the app already stores; nothing new is sent.
     */
    fun isMapEvent(
        category: String?, wind: Boolean, thresholdDb: Double, lafMaxDb: Double, minLevelDb: Double?, durationS: Double,
    ): Boolean {
        val floor = minLevelDb?.takeUnless { it.isNaN() } ?: MIN_LAFMAX_DB
        return category != null && category in MAP_CATEGORIES && !wind &&
            thresholdDb >= MIN_EXCESS_DB &&
            lafMaxDb >= maxOf(floor, MIN_LAFMAX_DB) &&
            durationS >= MIN_DURATION_S
    }

    /** [isMapEvent] of a stored event (the upload's include filter, [Uploader]). */
    fun isMapEvent(e: EventEntity): Boolean =
        isMapEvent(e.dominantCategory, e.wind, e.thresholdDb, e.lafMaxDb, e.minLevelDb, e.durationSeconds)

    /** [isMapEvent] of a just-detected event (the live-upload trigger, [LiveUploadPlan.triggers]). */
    fun isMapEvent(e: LiveEvent): Boolean =
        isMapEvent(e.category, e.wind, e.thresholdDb, e.lafMaxDb, e.minLevelDb, (e.endEpochMs - e.startEpochMs) / 1000.0)

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
        // 0.7.2: «UNPROCESSED» for the built-in microphone as before, «UNPROCESSED+usb» etc. for another.
        put("audio_source", MicChoice.wireAudioSource(m.audioSource, m.mic))
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
     * (5 decimals ≈ 1 m), its accuracy and the category; since 0.6.7 the direction of travel when the
     * reporter gave one (whole degrees, true or magnetic north, the compass's accuracy class); since
     * 0.8.5 the `level` while the dialog was open (peak LAFmax and LAeq of those seconds, one decimal,
     * the seconds, calibrated or not) when the microphone gave one. A report without either is exactly
     * the request of 0.6.6. The plate (0.7.6–0.8.4) and the photo (0.8.5) or video (0.9.4.3, `media`) are never read here.
     */
    fun report(r: PendingReport): JsonObject = buildJsonObject {
        put("time", r.timeIso)
        put("lat", num(r.lat, 5))
        put("lon", num(r.lon, 5))
        put("accuracy_m", num(r.accuracyM, 1))
        put("category", r.category)
        r.direction?.let { d ->
            put("direction", buildJsonObject {
                put("bearing_deg", d.bearingDeg)
                put("north", d.north.id)
                put("accuracy", d.accuracy.id)
            })
        }
        r.level?.let { l ->
            put("level", buildJsonObject {
                put("lafmax_db", num(l.lafMaxDb, 1))
                put("laeq_db", num(l.laeqDb, 1))
                put("seconds", l.seconds)
                put("calibrated", l.calibrated)
            })
        }
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
