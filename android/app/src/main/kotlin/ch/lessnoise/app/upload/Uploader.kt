package ch.lessnoise.app.upload

import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.app.data.sharedPoint
import ch.lessnoise.dsp.MeasurementSplitter
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Read access to the local records the upload sends (Android: Room; tests: in memory). */
interface UploadSource {
    /** Minutes with `startEpochMs > afterMs`, oldest first, at most [limit]. */
    suspend fun minutesAfter(afterMs: Long, limit: Int): List<MinuteEntity>

    /** Events with `startEpochMs > afterMs`, oldest first, at most [limit]. */
    suspend fun eventsAfter(afterMs: Long, limit: Int): List<EventEntity>

    /** All measurements (start, end, shifted point), to find the point of a record. */
    suspend fun measurements(): List<MeasurementEntity>

    /** Phone model, audio source and calibration for the site call. */
    suspend fun siteExtras(): SiteExtras
}

/**
 * The place of a record: the shifted point of the measurement it belongs to (the one that started
 * last before it, MeasurementSplitter.ownerIndex), null if that measurement is not shared or the
 * record lies after its end. Records without a point are never sent. A measurement row with a
 * cell but no point (apps 0.6.0 and 0.6.1, before the location noise) counts as not shared: the
 * app never sends a true hectare again.
 */
class PlaceIndex(measurements: List<MeasurementEntity>) {
    private val sorted = measurements.sortedWith(compareBy({ it.startMs }, { it.id }))
    private val starts = LongArray(sorted.size) { sorted[it].startMs }
    private val points = Array(sorted.size) { sorted[it].sharedPoint() }

    fun pointAt(t: Long): LocationNoise.Point? {
        val i = MeasurementSplitter.ownerIndex(starts, t)
        if (i < 0) return null
        val m = sorted[i]
        if (m.endMs != null && t >= m.endMs) return null
        return points[i]
    }

    /** The point of the newest shared measurement (sent with the placement details). */
    val latest: LocationNoise.Point? = points.lastOrNull { it != null }
}

/**
 * The upload of server/DESIGN.md §4.6, independent of Android so that it runs in JVM tests:
 * register once, send the site when it changed, the waiting «Laut! Melden» reports (≤ 50 per
 * request; their coordinates are deleted from the phone once the server answered), then every
 * minute and event newer than the last acknowledged `start`, in batches of ≤ 1440 / ≤ 2000. Since
 * 0.6.2 each record carries the shifted point of its measurement; records of a measurement without
 * a point (not shared) are skipped and never sent. The local database stays the source of
 * truth; the markers only move forward after the server acknowledged a batch, so an interrupted
 * upload resumes where it stopped and a repeated batch is harmless (the server is idempotent on
 * `(device_id, start)`).
 *
 * Records the server lists in `rejected` are kept in [UploadSnapshot.refused] and never sent again.
 */
class Uploader(
    private val client: UploadClient,
    private val store: UploadStore,
    private val source: UploadSource,
    /** «Laut! Melden» reports waiting to be sent. */
    private val reports: ReportStore,
    private val appVersion: String,
    private val appBuild: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Requests per run; the server allows 60 per device and hour (DESIGN.md §8). */
    private val maxRequestsPerRun: Int = 24,
    private val minuteBatch: Int = UploadClient.MAX_MINUTES,
    private val eventBatch: Int = UploadClient.MAX_EVENTS,
) {
    /** Serialises uploads, deletes and «Neue Kennung» within the process. */
    private val lock = Mutex()

    /** Set by a delete waiting for the lock: a running upload stops before its next request. */
    private val abort = AtomicBoolean(false)

    private fun stopRequested(): Boolean = abort.get() || !store.state.value.enabled

    sealed interface Outcome {
        /** Sharing is off (or was switched off during the run): nothing sent. */
        data object Disabled : Outcome

        /** The server does not accept the token; stops until «Neue Kennung». */
        data object AuthFailed : Outcome

        /** Everything newer than the markers was sent (or the request budget of this run is used up: [more]). */
        data class Done(val minutes: Int, val events: Int, val refused: Int, val more: Boolean, val reports: Int = 0) : Outcome

        /** Temporary problem (network, server, rate limit): try again later. [rateLimited]: the server answered 429. */
        data class Retry(val message: String, val rateLimited: Boolean = false) : Outcome

        /** The server refused a whole request (4xx): retrying the same request will not help. */
        data class Failed(val message: String) : Outcome
    }

    sealed interface DeleteOutcome {
        /** The server confirmed (204) that every row of this device is gone, or there never was a device. */
        data object Deleted : DeleteOutcome

        /** The server did not know this device/token (401): nothing it holds can be reached with it. */
        data object UnknownToServer : DeleteOutcome

        /** No answer / an error: nothing was changed on the phone. */
        data class Failed(val message: String) : DeleteOutcome
    }

    /**
     * Switch «Messwerte teilen» on: «Ja, auf die Karte» at the first «Starten», the switch in
     * «Teilen», or the short consent of «Laut! Melden» (0.6.3: no other gate). Records the answer
     * [ShareDecision.YES]. Always true (kept as Boolean for the callers).
     */
    fun enable(): Boolean {
        val now = clock()
        store.update {
            // The very first time, shared measurements of the last 7 days are included (normally
            // none exist yet: a measurement is shared only if it has a point); after that, nothing
            // measured while sharing was off is ever sent — except the measurement that is running
            // when sharing is switched on, which gets its point afterwards ([pointAttached]).
            val floor = if (it.sendFloorMs == 0L && !it.registered) now - MAX_AGE_MS else now
            it.copy(
                enabled = true, shareDecision = ShareDecision.YES,
                sendFloorMs = maxOf(it.sendFloorMs, floor), lastError = null,
            )
        }
        return true
    }

    /**
     * Switch it off («Nur auf dem Telefon», or the switch in «Teilen»): records [ShareDecision.NO];
     * no further request is started (a running one finishes its current call). Reports not sent yet
     * are dropped with their coordinates: off means nothing more is sent.
     */
    fun disable() {
        store.update { it.copy(enabled = false, shareDecision = ShareDecision.NO) }
        ReportQueue.dropPending(reports)
    }

    /**
     * The running measurement that started at [measurementStartMs] has just got its shifted point
     * (0.6.3: the fix runs while the measurement already measures, or sharing was switched on during
     * it). Its minutes and events recorded before the point may already lie behind the markers: an
     * earlier run skipped them (no point then) and moved past them. Move both markers back to just
     * before the measurement's start, and the opt-in floor too, so that the next run sends the whole
     * measurement. Records of earlier measurements in between are re-sent at most once; the server is
     * idempotent on `(device_id, start)`. Taken under the upload lock, so a run in progress cannot
     * move the markers past them again after this.
     */
    suspend fun pointAttached(measurementStartMs: Long) = lock.withLock {
        val back = measurementStartMs - MeasurementSplitter.START_TOLERANCE_MS - 1
        store.update {
            it.copy(
                minutesSentUpToMs = minOf(it.minutesSentUpToMs, back),
                eventsSentUpToMs = minOf(it.eventsSentUpToMs, back),
                sendFloorMs = minOf(it.sendFloorMs, back),
            )
        }
        Unit
    }

    /**
     * After «Alte Messungen neu bewerten» the stored levels changed: send the last 7 days again
     * (the server replaces records with the same start, DESIGN.md §4.3).
     */
    fun rewindAfterReevaluation() {
        store.update { it.copy(minutesSentUpToMs = 0, eventsSentUpToMs = 0) }
    }

    /** Oldest start that is still sent: the opt-in floor, and nothing the server would refuse as too old. */
    fun effectiveFloorMs(s: UploadSnapshot = store.state.value): Long =
        maxOf(s.sendFloorMs, clock() - MAX_AGE_MS + AGE_MARGIN_MS)

    /** `afterMs` for counting the minutes still to send (settings screen). */
    fun minutesPendingAfterMs(s: UploadSnapshot = store.state.value): Long =
        maxOf(s.minutesSentUpToMs, effectiveFloorMs(s) - 1)

    /**
     * One upload run. [withMinutes] false (0.6.3 live map): an events-only run for a loud event
     * that just ended — the reports and the site if needed, then the new events, usually ONE
     * request; the minutes wait for the next full run ([LiveUploadPlan]).
     */
    suspend fun run(withMinutes: Boolean = true): Outcome = lock.withLock { runLocked(withMinutes) }

    private suspend fun runLocked(withMinutes: Boolean): Outcome {
        var s = store.state.value
        if (!s.enabled) return Outcome.Disabled
        if (s.authFailed) return Outcome.AuthFailed
        var requests = 0
        var sentReports = 0
        var sentMinutes = 0
        var sentEvents = 0
        var refused = 0
        try {
            if (!s.registered) {
                val reg = client.register(appVersion, appBuild)
                requests++
                s = store.update { it.copy(deviceId = reg.deviceId, token = reg.token, lastSiteJson = null, authFailed = false) }
            }
            val id = s.deviceId!!
            val token = s.token!!

            val places = PlaceIndex(source.measurements())
            // The placement details go with the newest shared measurement's point; none yet: no site call.
            val latestPoint = places.latest
            if (latestPoint != null) {
                val siteJson = Payloads.site(latestPoint, s.site, source.siteExtras())
                val siteText = siteJson.toString()
                if (siteText != s.lastSiteJson) {
                    if (stopRequested()) return Outcome.Disabled
                    client.putSite(id, token, siteJson)
                    requests++
                    s = store.update { it.copy(lastSiteJson = siteText) }
                }
            }

            // Reports first: few, small, and their coordinates should not stay on the phone longer than needed.
            while (true) {
                if (stopRequested()) return Outcome.Disabled
                val batch = reports.state.value.pending.take(UploadClient.MAX_REPORTS)
                if (batch.isEmpty() || requests >= maxRequestsPerRun) break
                val res = try {
                    client.postReports(id, token, JsonArray(batch.map(Payloads::report)))
                } catch (e: UploadClient.Failure.Rejected) {
                    requests++
                    // 404/405: a server without the reports call yet. Keep the reports for a later
                    // run and still send the minutes and events.
                    if (e.status == 404 || e.status == 405) break
                    // Any other 4xx: this batch itself is refused and would be refused again; drop it
                    // (its coordinates with it) instead of blocking every later upload.
                    store.update { it.copy(refusedTotal = it.refusedTotal + batch.size) }
                    ReportQueue.acknowledge(reports, batch, rejected = batch.indices.toSet())
                    continue
                }
                requests++
                sentReports += res.accepted + res.duplicates
                if (res.rejected.isNotEmpty()) store.update { it.copy(refusedTotal = it.refusedTotal + res.rejected.size) }
                ReportQueue.acknowledge(reports, batch, rejected = res.rejected.map { it.index }.toSet())
            }

            var more = false
            if (withMinutes) {
                val minutes = sendAll(
                    kind = RefusedRecord.MINUTE, batchSize = minuteBatch, budget = { maxRequestsPerRun - requests },
                    marker = { it.minutesSentUpToMs },
                    fetch = { after, n -> source.minutesAfter(after, n) }, start = { it.startEpochMs },
                    // Only minutes of shared measurements; the marker still moves past the others.
                    include = { places.pointAt(it.startEpochMs) != null },
                    post = { batch -> client.postMinutes(id, token, JsonArray(batch.map { Payloads.minute(it, places.pointAt(it.startEpochMs)!!) })) },
                    advance = { snap, t -> snap.copy(minutesSentUpToMs = maxOf(snap.minutesSentUpToMs, t)) },
                ) ?: return Outcome.Disabled
                requests += minutes.requests; sentMinutes = minutes.sent; refused += minutes.refused; more = minutes.more
            }
            if (!more) {
                val events = sendAll(
                    kind = RefusedRecord.EVENT, batchSize = eventBatch, budget = { maxRequestsPerRun - requests },
                    marker = { it.eventsSentUpToMs },
                    fetch = { after, n -> source.eventsAfter(after, n) }, start = { it.startEpochMs },
                    // Wind-flagged events (detector v2) are not shared, as they are left out of the app's
                    // own counts, nor events of measurements without a point; the marker still moves
                    // past them (server/DESIGN.md §4.4).
                    include = { Payloads.isShared(it) && places.pointAt(it.startEpochMs) != null },
                    post = { batch -> client.postEvents(id, token, JsonArray(batch.map { Payloads.event(it, places.pointAt(it.startEpochMs)!!) })) },
                    advance = { snap, t -> snap.copy(eventsSentUpToMs = maxOf(snap.eventsSentUpToMs, t)) },
                ) ?: return Outcome.Disabled
                requests += events.requests; sentEvents = events.sent; refused += events.refused; more = events.more
            }
            // «Zuletzt gesendet» only moves when something was actually sent.
            store.update { if (requests > 0) it.copy(lastSuccessAtMs = clock(), lastError = null) else it.copy(lastError = null) }
            return Outcome.Done(sentMinutes, sentEvents, refused, more, sentReports)
        } catch (e: UploadClient.Failure.Unauthorized) {
            store.update { it.copy(authFailed = true, lastError = AUTH_FAILED_TEXT) }
            return Outcome.AuthFailed
        } catch (e: UploadClient.Failure.RateLimited) {
            store.update { it.copy(lastError = RETRY_TEXT) }
            return Outcome.Retry(e.message ?: "429", rateLimited = true)
        } catch (e: UploadClient.Failure.Rejected) {
            store.update { it.copy(lastError = "Vom Server abgelehnt (${e.status}).") }
            return Outcome.Failed(e.message ?: e.status.toString())
        } catch (e: UploadClient.Failure.Server) {
            store.update { it.copy(lastError = RETRY_TEXT) }
            return Outcome.Retry(e.message ?: e.status.toString())
        } catch (e: IOException) {
            store.update { it.copy(lastError = NO_CONNECTION_TEXT) }
            return Outcome.Retry(e.javaClass.simpleName)
        }
    }

    /**
     * Requests one [run] sends at most (the server allows 60 per device and hour, DESIGN.md §8);
     * the live cadence of 0.6.3 (UploadCadence) stays far below that, see UploadCadenceTest.
     */
    val requestBudgetPerRun: Int get() = maxRequestsPerRun

    private class Sent(val requests: Int, val sent: Int, val refused: Int, val more: Boolean)

    /**
     * Sends every record newer than the marker, batch by batch, moving the marker after each
     * acknowledged batch. Null if sharing was switched off in between.
     *
     * A full batch might end in the middle of several records with the same start (two minute rows
     * can share a start after a restart within one minute). The marker means "start > marker", so
     * such a tail is cut off and goes with the next batch. (If a whole batch had one start it is
     * sent as is; with 1440 rows per batch that cannot happen in practice.)
     */
    private suspend fun <T> sendAll(
        kind: String,
        batchSize: Int,
        budget: () -> Int,
        marker: (UploadSnapshot) -> Long,
        fetch: suspend (Long, Int) -> List<T>,
        start: (T) -> Long,
        include: (T) -> Boolean = { true },
        post: (List<T>) -> UploadClient.Result,
        advance: (UploadSnapshot, Long) -> UploadSnapshot,
    ): Sent? {
        var requests = 0
        var sent = 0
        var refusedCount = 0
        while (true) {
            if (stopRequested()) return null
            val s = store.state.value
            if (requests >= budget()) return Sent(requests, sent, refusedCount, more = true)
            // One row more than a batch, to see whether the batch would split records with one start.
            val raw = fetch(maxOf(marker(s), effectiveFloorMs(s) - 1), batchSize + 1)
            if (raw.isEmpty()) return Sent(requests, sent, refusedCount, more = false)
            val full = raw.size > batchSize
            val rows = if (!full) raw else {
                val head = raw.take(batchSize)
                val nextStart = start(raw[batchSize])
                if (start(head.last()) != nextStart) head else head.dropLastWhile { start(it) == nextStart }.ifEmpty { head }
            }
            val skip = s.refused.filter { it.kind == kind }.map { it.startEpochMs }.toHashSet()
            val batch = rows.filter { start(it) !in skip && include(it) }
            if (batch.isNotEmpty()) {
                val res = post(batch)
                requests++
                sent += res.accepted + res.duplicates
                refusedCount += res.rejected.size
                recordRefused(kind, batch.map(start), res.rejected)
            }
            val last = start(rows.last())
            store.update { advance(it, last) }
            if (!full) return Sent(requests, sent, refusedCount, more = false)
        }
    }

    private fun recordRefused(kind: String, starts: List<Long>, rejected: List<UploadClient.Rejected>) {
        if (rejected.isEmpty()) return
        val add = rejected.mapNotNull { r -> starts.getOrNull(r.index)?.let { RefusedRecord(kind, it, r.reasons) } }
        store.update {
            it.copy(
                refused = (it.refused + add).takeLast(UploadSnapshot.REFUSED_KEEP),
                refusedTotal = it.refusedTotal + add.size,
            )
        }
    }

    /**
     * «Daten auf dem Server löschen»: DELETE and wait for the server's 204, then forget the id and
     * token, switch sharing off and drop reports not sent yet. On any error nothing changes on the phone.
     */
    suspend fun deleteServerData(): DeleteOutcome {
        abort.set(true)
        return lock.withLock {
            abort.set(false)
            deleteAndForget(disable = true).also { if (it !is DeleteOutcome.Failed) ReportQueue.dropPending(reports) }
        }
    }

    /** «Neue Kennung»: delete the server data like above, then start over with a fresh id (on the next upload). */
    suspend fun newIdentity(): DeleteOutcome {
        abort.set(true)
        return lock.withLock {
            abort.set(false)
            deleteAndForget(disable = false)
        }
    }

    private fun deleteAndForget(disable: Boolean): DeleteOutcome {
        val r = deleteLocked()
        if (r !is DeleteOutcome.Failed) {
            store.update { forget(it).let { f -> if (disable) f.copy(enabled = false, shareDecision = ShareDecision.NO) else f } }
        }
        return r
    }

    private fun deleteLocked(): DeleteOutcome {
        val s = store.state.value
        if (!s.registered) return DeleteOutcome.Deleted
        return try {
            client.deleteDevice(s.deviceId!!, s.token!!)
            DeleteOutcome.Deleted
        } catch (e: UploadClient.Failure.Unauthorized) {
            DeleteOutcome.UnknownToServer
        } catch (e: IOException) {
            DeleteOutcome.Failed(
                when (e) {
                    is UploadClient.Failure -> e.message ?: "Fehler"
                    else -> "Keine Verbindung zum Server."
                }
            )
        }
    }

    /**
     * Forget the device: nothing sent before now is sent again under a new id. The location-noise
     * secret goes too, so the next shared measurement gets a new offset everywhere (a new one is
     * made at the next «Start», [NoiseSecret]).
     */
    private fun forget(s: UploadSnapshot) = s.copy(
        deviceId = null, token = null, lastSiteJson = null, authFailed = false,
        minutesSentUpToMs = 0, eventsSentUpToMs = 0, sendFloorMs = clock(),
        lastSuccessAtMs = null, lastError = null, refused = emptyList(), noiseSecret = null,
    )

    companion object {
        /**
         * Shown while the server refuses the token (401). The usual reason: the server deletes a
         * device's data after 60 days without uploads (server/DESIGN.md §2.9). No silent
         * re-registration: the user chooses «Neu starten» (a new random id, see [newIdentity]).
         */
        const val AUTH_FAILED_TEXT =
            "Nach 60 Tagen ohne Werte hat der Server deine Daten gelöscht. Tippe auf «Neu starten», um wieder zu teilen."

        /** One message for the failures of the server that are retried by themselves (429, 5xx): nothing to do. */
        const val RETRY_TEXT = "Senden klappt gerade nicht. Neuer Versuch folgt."

        /** No connection to the server (0.6.3: told apart from [RETRY_TEXT] for the status line «Kein Internet»). */
        const val NO_CONNECTION_TEXT = "Keine Verbindung zum Server. Neuer Versuch folgt."

        /** The server refuses records older than 7 days (DESIGN.md §8, `MAX_RECORD_AGE_DAYS`). */
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        /** Records this close to the limit are left out: they could be refused by the time they arrive. */
        const val AGE_MARGIN_MS = 3600L * 1000
    }
}
