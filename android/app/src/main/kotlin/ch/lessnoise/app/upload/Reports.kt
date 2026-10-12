package ch.lessnoise.app.upload

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
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
    POSER("poser", "Poser"),
    ANDERE("andere", "anderes");

    companion object {
        fun fromId(id: String): ReportCategory? = entries.firstOrNull { it.id == id }
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
) {
    fun toSent(): SentReport = SentReport(epochMs, category, cell)

    companion object {
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

        /** ISO-8601 with offset, second precision (the server rounds for publication). */
        fun isoSeconds(epochMs: Long, zone: ZoneId): String =
            Instant.ofEpochMilli(epochMs - Math.floorMod(epochMs, 1000L)).atZone(zone).format(ISO)
    }
}

/** What remains on the phone of a sent report: category, time and cell. No coordinates. */
data class SentReport(val epochMs: Long, val category: String, val cell: String?)

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
            })
        }
    }.toString()

    fun sent(list: List<SentReport>): String = buildJsonArray {
        list.forEach { r -> add(buildJsonObject { put("t", r.epochMs); put("cat", r.category); r.cell?.let { put("cell", it) } }) }
    }.toString()

    fun parsePending(s: String?): List<PendingReport> = try {
        if (s == null) emptyList() else Json.parseToJsonElement(s).jsonArray.map {
            val o = it.jsonObject
            PendingReport(
                o["t"]!!.jsonPrimitive.long, o["iso"]!!.jsonPrimitive.content, o["lat"]!!.jsonPrimitive.double,
                o["lon"]!!.jsonPrimitive.double, o["acc"]!!.jsonPrimitive.double, o["cat"]!!.jsonPrimitive.content,
                o["cell"]?.jsonPrimitive?.content,
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun parseSent(s: String?): List<SentReport> = try {
        if (s == null) emptyList() else Json.parseToJsonElement(s).jsonArray.map {
            val o = it.jsonObject
            SentReport(o["t"]!!.jsonPrimitive.long, o["cat"]!!.jsonPrimitive.content, o["cell"]?.jsonPrimitive?.content)
        }
    } catch (_: Exception) {
        emptyList()
    }
}
