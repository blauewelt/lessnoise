package ch.lessnoise.app.upload

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * «Laut! Melden» (app 0.6.0, Karten editions): what was loud, as one of five categories. The ids
 * are the server's (`POST /v1/devices/{id}/reports`, server/DESIGN.md §4); the labels are the
 * buttons («Töff», «Auto», …).
 */
enum class ReportCategory(val id: String, val label: String) {
    MOTORRAD("motorrad", "Töff"),
    AUTO("auto", "Auto"),
    LASTWAGEN("lastwagen", "Lastwagen"),
    /** 0.7.7: aircraft; the server accepts the id once its schema change is deployed (server/DESIGN.md §4.7). */
    FLUGZEUG("flugzeug", "Flugzeug"),
    POSER("poser", "Poser"),
    /** 0.6.4: label «anderes Fahrzeug» (was «anderes»); the id stays «andere» (server, reports.json). */
    ANDERE("andere", "anderes Fahrzeug");

    companion object {
        fun fromId(id: String): ReportCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where the reported vehicle drove (app 0.6.7, optional): the compass bearing the phone pointed at
 * when the reporter tapped «Richtung übernehmen», whole degrees clockwise from north (0–359), whether
 * that is true or magnetic north, and the compass's accuracy class. One value per report, never a
 * sensor stream. Sent as `direction` (server/DESIGN.md §4.7).
 */
data class ReportDirection(val bearingDeg: Int, val north: North, val accuracy: Accuracy) {
    init {
        require(bearingDeg in 0..359) { "bearing $bearingDeg" }
    }

    /** True north (corrected with the declination at the report's place) or magnetic north (no place). */
    enum class North(val id: String) { TRUE("true"), MAGNETIC("magnetic") }

    /** Android's sensor accuracy classes (SENSOR_STATUS_ACCURACY_*). */
    enum class Accuracy(val id: String) {
        HIGH("high"), MEDIUM("medium"), LOW("low"), UNRELIABLE("unreliable");

        companion object {
            /** SENSOR_STATUS_ACCURACY_HIGH = 3, MEDIUM = 2, LOW = 1; UNRELIABLE = 0 and NO_CONTACT = -1. */
            fun ofSensorStatus(status: Int): Accuracy = when {
                status >= 3 -> HIGH
                status == 2 -> MEDIUM
                status == 1 -> LOW
                else -> UNRELIABLE
            }

            fun fromId(id: String?): Accuracy? = entries.firstOrNull { it.id == id }
        }
    }

    /** «Richtung Nordost»: eight compass points of 45°, for the confirmation dialog. */
    val label: String get() = "Richtung " + COMPASS_8[Math.floorMod(Math.round(bearingDeg / 45.0).toInt(), 8)]

    companion object {
        private val COMPASS_8 = listOf("Nord", "Nordost", "Ost", "Südost", "Süd", "Südwest", "West", "Nordwest")

        /** Any angle in degrees → 0 ≤ x < 360. */
        fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

        /** Rounded to whole degrees, 0–359 (359.5 and −0.4 both become 0). */
        fun roundDeg(deg: Double): Int = Math.floorMod(Math.round(normalize(deg)).toInt(), 360)

        /**
         * The stored value from the compass: [magneticDeg] (clockwise from magnetic north) plus the
         * [declinationDeg] at the report's place (east positive, from Android's geomagnetic model) gives true
         * north; without a declination the bearing stays magnetic and says so. Null for a non-finite
         * bearing.
         */
        fun of(magneticDeg: Double, declinationDeg: Double?, sensorStatus: Int): ReportDirection? {
            if (magneticDeg.isNaN() || magneticDeg.isInfinite()) return null
            val d = declinationDeg?.takeUnless { it.isNaN() || it.isInfinite() }
            return ReportDirection(
                bearingDeg = roundDeg(magneticDeg + (d ?: 0.0)),
                north = if (d != null) North.TRUE else North.MAGNETIC,
                accuracy = Accuracy.ofSensorStatus(sensorStatus),
            )
        }
    }
}

/**
 * The compass at the moment of «Richtung übernehmen» (0.6.7): the bearing the phone pointed at,
 * clockwise from MAGNETIC north (what the sensors give), and Android's accuracy status of the
 * magnetometer. Kept only in memory until «Senden», where [toDirection] corrects it to true north
 * with the declination at the report's own place.
 */
data class CompassReading(val magneticDeg: Double, val sensorStatus: Int) {
    fun toDirection(declinationDeg: Double?): ReportDirection? = ReportDirection.of(magneticDeg, declinationDeg, sensorStatus)
}

/**
 * App 0.8.5 (Labor·Karte first): the sound level while the «Laut! Melden» dialog was open, from the
 * same level meter as a measurement (dsp MeasurementEngine, the phone's calibration applied; or the
 * running measurement's own values). Numbers only, one decimal; no audio exists. Sent as `level`
 * (server/DESIGN.md §4.7): [lafMaxDb] the highest LAFmax of a 125 ms step while the dialog was open,
 * [laeqDb] the energetic average (LAeq) over that time, [seconds] how long it was open (whole
 * seconds, 1–[MAX_SECONDS]), [calibrated] whether the phone's microphone is calibrated.
 */
data class ReportLevel(val lafMaxDb: Double, val laeqDb: Double, val seconds: Int, val calibrated: Boolean) {
    init {
        require(seconds in 1..MAX_SECONDS) { "seconds $seconds" }
        require(!lafMaxDb.isNaN() && !laeqDb.isNaN()) { "level NaN" }
    }

    companion object {
        /** The server's bound (`LevelIn.seconds` ≤ 600); a dialog open longer reports 600. */
        const val MAX_SECONDS = 600
        /** The server's bounds for both levels (`LevelIn`, 0–140 dB). */
        const val MIN_DB = 0.0
        const val MAX_DB = 140.0

        /** One decimal, within the server's bounds. */
        fun round1(v: Double): Double = (Math.round(v.coerceIn(MIN_DB, MAX_DB) * 10.0) / 10.0)
    }
}

/**
 * A report waiting to be sent. The ONLY place where the app keeps exact coordinates, and only
 * until the server acknowledged the report: then [toSent] drops them (PRIVACY.md).
 */
data class PendingReport(
    /** Time of the tap on «Laut! Melden», epoch ms (sent with second precision). */
    val epochMs: Long,
    /** The same time as ISO-8601 with the phone's offset, second precision («2026-10-12T16:05:03+02:00»). */
    val timeIso: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val category: String,
    /** The 100 m cell of the place, kept after sending (the coordinates are not). */
    val cell: String?,
    /** App 0.6.7: where the vehicle drove, if the reporter gave it; dropped after sending. */
    val direction: ReportDirection? = null,
    /**
     * App 0.7.6–0.8.4: the licence plate typed into «Kontrollschild». Since 0.8.5 nothing creates one
     * (the field is gone, the owner takes a photo instead); it is only read from reports queued by
     * those versions and carried into [SentReport]. Kept ON THE PHONE ONLY; never sent.
     */
    val plate: String? = null,
    /** App 0.8.5: the level while the dialog was open ([ReportLevel]); sent as `level`, kept in [SentReport]. */
    val level: ReportLevel? = null,
    /**
     * App 0.8.5: the file name («1761381603000.jpg», no path) of the photo taken in the dialog, in the
     * app's private `files/reports/` (ui/ReportPhotos.kt). Kept ON THE PHONE ONLY, also after sending;
     * never sent: Payloads.report does not read it, and this package opens no file.
     */
    val photo: String? = null,
) {
    fun toSent(): SentReport = SentReport(epochMs, category, cell, plate, level?.laeqDb, level?.lafMaxDb, photo)

    companion object {
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

        /** ISO-8601 with offset, second precision (the server rounds for publication). */
        fun isoSeconds(epochMs: Long, zone: ZoneId): String =
            Instant.ofEpochMilli(epochMs - Math.floorMod(epochMs, 1000L)).atZone(zone).format(ISO)
    }
}

/**
 * What remains on the phone of a sent report: category, time and cell; the licence plate typed in
 * with 0.7.6–0.8.4 (it never left the phone); since 0.8.5 the report's level as sent ([laeqDb],
 * [lafMaxDb]) and the photo's file name ([photo], never sent). No coordinates.
 */
data class SentReport(
    val epochMs: Long,
    val category: String,
    val cell: String?,
    val plate: String? = null,
    val laeqDb: Double? = null,
    val lafMaxDb: Double? = null,
    val photo: String? = null,
)

data class ReportSnapshot(
    val pending: List<PendingReport> = emptyList(),
    /** Newest last, at most [SENT_KEEP]. */
    val sent: List<SentReport> = emptyList(),
) {
    companion object {
        const val SENT_KEEP = 500
    }
}

/** Storage of the report queue. Android: [ReportState]; tests: [MemoryReportStore]. */
interface ReportStore {
    val state: StateFlow<ReportSnapshot>
    fun update(transform: (ReportSnapshot) -> ReportSnapshot): ReportSnapshot
}

class MemoryReportStore(initial: ReportSnapshot = ReportSnapshot()) : ReportStore {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<ReportSnapshot> = flow

    @Synchronized
    override fun update(transform: (ReportSnapshot) -> ReportSnapshot): ReportSnapshot =
        transform(flow.value).also { flow.value = it }
}

/** Queue operations shared by the UI and [Uploader]. */
object ReportQueue {
    /** Queue a confirmed report. */
    fun add(store: ReportStore, r: PendingReport) {
        store.update { it.copy(pending = it.pending + r) }
    }

    /**
     * The server answered for [batch]: accepted, duplicate and rejected reports all leave the queue
     * (a rejected one would be rejected again); their coordinates are gone from the phone after this.
     * Only the accepted and duplicate ones (not in [rejected], indexes into [batch]) go to the list
     * of sent reports, without coordinates.
     */
    fun acknowledge(store: ReportStore, batch: List<PendingReport>, rejected: Set<Int> = emptySet()) {
        val done = batch.toSet()
        val kept = batch.filterIndexed { i, _ -> i !in rejected }.map { it.toSent() }
        store.update { s ->
            s.copy(
                pending = s.pending.filter { it !in done },
                sent = (s.sent + kept).takeLast(ReportSnapshot.SENT_KEEP),
            )
        }
    }

    /**
     * A server from before app 0.6.7 refuses the unknown field `direction` (server/DESIGN.md §4.7):
     * [reports] stay in the queue without their direction and go out again as before.
     */
    fun dropDirection(store: ReportStore, reports: List<PendingReport>) {
        if (reports.isEmpty()) return
        val strip = reports.toSet()
        store.update { s -> s.copy(pending = s.pending.map { if (it in strip) it.copy(direction = null) else it }) }
    }

    /**
     * App 0.8.5: a server from before schema v8 refuses the unknown field `level` (server/DESIGN.md
     * §4.7): [reports] stay in the queue without their level and go out again, like [dropDirection].
     */
    fun dropLevel(store: ReportStore, reports: List<PendingReport>) {
        if (reports.isEmpty()) return
        val strip = reports.toSet()
        store.update { s -> s.copy(pending = s.pending.map { if (it in strip) it.copy(level = null) else it }) }
    }

    /**
     * App 0.8.5: «Löschen» on a sent report's photo: the sent entry of [epochMs] forgets its [photo].
     * The file itself is deleted by ui/ReportPhotos.kt (this package opens no file).
     */
    fun dropSentPhoto(store: ReportStore, epochMs: Long, photo: String) {
        store.update { s -> s.copy(sent = s.sent.map { if (it.epochMs == epochMs && it.photo == photo) it.copy(photo = null) else it }) }
    }

    /**
     * App 0.8.5: every photo file name a pending or sent report still refers to; ui/ReportPhotos.sweep
     * deletes every other file of `files/reports/` (a report dropped, rejected, rolled out of the last
     * [ReportSnapshot.SENT_KEEP] or its photo deleted).
     */
    fun photos(s: ReportSnapshot): Set<String> = (s.pending.mapNotNull { it.photo } + s.sent.mapNotNull { it.photo }).toSet()

    /** «Daten auf dem Server löschen» also drops reports that were not sent yet. */
    fun dropPending(store: ReportStore) {
        store.update { it.copy(pending = emptyList()) }
    }
}

/**
 * The queue on the phone: a private SharedPreferences file ("reports"), excluded from backup like
 * everything else. Pending reports hold coordinates until they are sent; sent ones do not.
 */
class ReportState(context: Context) : ReportStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("reports", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(read())
    override val state: StateFlow<ReportSnapshot> = flow

    @Synchronized
    override fun update(transform: (ReportSnapshot) -> ReportSnapshot): ReportSnapshot {
        val n = transform(flow.value)
        // commit(): a sent report's coordinates must be gone from disk before the worker reports success.
        prefs.edit().putString(KEY_PENDING, ReportJson.pending(n.pending)).putString(KEY_SENT, ReportJson.sent(n.sent)).commit()
        flow.value = n
        return n
    }

    private fun read() = ReportSnapshot(
        pending = ReportJson.parsePending(prefs.getString(KEY_PENDING, null)),
        sent = ReportJson.parseSent(prefs.getString(KEY_SENT, null)),
    )

    private companion object {
        const val KEY_PENDING = "pending"
        const val KEY_SENT = "sent"
    }
}

/** JSON for [ReportState] (pure, tested). */
object ReportJson {
    fun pending(list: List<PendingReport>): String = buildJsonArray {
        list.forEach { r ->
            add(buildJsonObject {
                put("t", r.epochMs); put("iso", r.timeIso); put("lat", r.lat); put("lon", r.lon)
                put("acc", r.accuracyM); put("cat", r.category); r.cell?.let { put("cell", it) }
                r.direction?.let { d ->
                    put("dir", buildJsonObject { put("deg", d.bearingDeg); put("n", d.north.id); put("a", d.accuracy.id) })
                }
                // 0.7.6–0.8.4 only: an old report's plate is kept as it was (nothing creates one since 0.8.5).
                r.plate?.let { put("plate", it) }
                r.level?.let { l ->
                    put("lvl", buildJsonObject { put("max", l.lafMaxDb); put("eq", l.laeqDb); put("s", l.seconds); put("cal", l.calibrated) })
                }
                r.photo?.let { put("photo", it) }
            })
        }
    }.toString()

    fun sent(list: List<SentReport>): String = buildJsonArray {
        list.forEach { r ->
            add(buildJsonObject {
                put("t", r.epochMs); put("cat", r.category); r.cell?.let { put("cell", it) }; r.plate?.let { put("plate", it) }
                r.laeqDb?.let { put("eq", it) }; r.lafMaxDb?.let { put("max", it) }; r.photo?.let { put("photo", it) }
            })
        }
    }.toString()

    fun parsePending(s: String?): List<PendingReport> = try {
        if (s == null) emptyList() else Json.parseToJsonElement(s).jsonArray.map {
            val o = it.jsonObject
            PendingReport(
                o["t"]!!.jsonPrimitive.long, o["iso"]!!.jsonPrimitive.content, o["lat"]!!.jsonPrimitive.double,
                o["lon"]!!.jsonPrimitive.double, o["acc"]!!.jsonPrimitive.double, o["cat"]!!.jsonPrimitive.content,
                o["cell"]?.jsonPrimitive?.content,
                direction(o["dir"] as? JsonObject),
                plate(o),
                level(o["lvl"] as? JsonObject),
                string(o, "photo"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** A stored direction; anything unreadable is dropped (the report itself is kept). */
    private fun direction(o: JsonObject?): ReportDirection? = try {
        o?.let {
            ReportDirection(
                it["deg"]!!.jsonPrimitive.int,
                ReportDirection.North.entries.first { n -> n.id == it["n"]!!.jsonPrimitive.content },
                ReportDirection.Accuracy.fromId(it["a"]!!.jsonPrimitive.content)!!,
            )
        }
    } catch (_: Exception) {
        null
    }

    /** A stored level (0.8.5); anything unreadable is dropped (the report itself is kept). */
    private fun level(o: JsonObject?): ReportLevel? = try {
        o?.let {
            ReportLevel(
                it["max"]!!.jsonPrimitive.double, it["eq"]!!.jsonPrimitive.double, it["s"]!!.jsonPrimitive.int,
                it["cal"]!!.jsonPrimitive.boolean,
            )
        }
    } catch (_: Exception) {
        null
    }

    /** A stored plate (0.7.6–0.8.4); absent in files of older versions and in reports since 0.8.5. */
    private fun plate(o: JsonObject): String? = string(o, "plate")

    private fun string(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun number(o: JsonObject, key: String): Double? = (o[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

    fun parseSent(s: String?): List<SentReport> = try {
        if (s == null) emptyList() else Json.parseToJsonElement(s).jsonArray.map {
            val o = it.jsonObject
            SentReport(
                o["t"]!!.jsonPrimitive.long, o["cat"]!!.jsonPrimitive.content, o["cell"]?.jsonPrimitive?.content, plate(o),
                number(o, "eq"), number(o, "max"), string(o, "photo"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
