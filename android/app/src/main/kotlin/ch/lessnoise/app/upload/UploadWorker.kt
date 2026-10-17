package ch.lessnoise.app.upload

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.audio.AudioSourceSelector
import ch.lessnoise.app.container
import ch.lessnoise.app.data.AppDatabase
import ch.lessnoise.app.data.CalibrationRepository
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.data.MeasurementEntity
import ch.lessnoise.app.data.MinuteEntity
import ch.lessnoise.app.data.MovingSpanEntity
import ch.lessnoise.app.data.Recalibrator
import ch.lessnoise.app.service.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Background upload for «Messwerte teilen» (server/DESIGN.md §4.6). Since 0.6.3: while a shared
 * measurement runs, the live uploads of [LiveUploadPlan] run in the app's process (not here); this
 * worker is the one-shot job shortly after a measurement stops, after a report or a settings change,
 * and the periodic job every hour (a few minutes after the full hour) as the fallback. Network constraint: any connection (since 0.6.3 the default), Wi-Fi/unmetered with «nur
 * über WLAN». Failures back off exponentially (WorkManager, from 10 min).
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!BuildConfig.UPLOAD_AVAILABLE) return Result.success()
        val module = applicationContext.container.upload
        return when (val o = module.uploader.run()) {
            is Uploader.Outcome.Done -> {
                // More than one run's request budget waiting (e.g. after a long time offline).
                if (o.more) module.scheduleFollowUp()
                Result.success()
            }
            is Uploader.Outcome.Retry -> {
                Log.i(TAG, "upload postponed") // never log data, ids or tokens
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
            is Uploader.Outcome.Failed -> {
                Log.w(TAG, "upload refused by server")
                Result.failure()
            }
            Uploader.Outcome.AuthFailed, Uploader.Outcome.Disabled -> Result.success()
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val MAX_ATTEMPTS = 8
        const val WORK_PERIODIC = "upload_hourly"
        const val WORK_NOW = "upload_now"
        const val WORK_MORE = "upload_more"
        /** The 10-minute job of the first 0.6.3 builds; only cancelled now (the live uploads run in-process). */
        const val WORK_LIVE = "upload_live"
    }
}

/**
 * Uploads outside a running shared measurement (server/DESIGN.md §4.6): once shortly after
 * «Stoppen», and the hourly fallback job. While a shared measurement runs: [LiveUploadPlan].
 */
object UploadCadence {
    /** One upload this long after «Stoppen» (the service writes the last minute within 3 s). */
    const val AFTER_STOP_MS = 15_000L

    /** The hourly fallback job's runs in [fromMs, toMs) (five minutes after every full hour). */
    fun hourlyRunTimes(fromMs: Long, toMs: Long, zone: ZoneId): List<Long> {
        val first = fromMs + UploadModule.delayToNextHourMs(java.time.Instant.ofEpochMilli(fromMs).atZone(zone))
        return generateSequence(first) { it + 3_600_000L }.takeWhile { it < toMs }.toList()
    }
}

/**
 * The live map (0.6.3, «every 30 seconds»): when the app uploads while a shared measurement runs.
 * Pure and single-threaded by contract (the caller serialises; UploadModule holds a lock), so that
 * LiveUploadPlanTest and UploadCadenceTest can drive the real Uploader with it against the fake
 * server in simulated time.
 *
 * - The first FULL run (reports, site, minutes, events) [FIRST_AFTER_POINT_MS] after the point is
 *   attached, then one every [MINUTES_EVERY_MS].
 * - A loud event that ended goes out [EVENT_DELAY_MS] after its end in an EVENTS run (usually one
 *   request); runs are at least [MIN_GAP_MS] apart, so several events coalesce into one run, and an
 *   events run that would fall within [MIN_GAP_MS] of the next full run is merged into it.
 * - A 429 (the server's per-device limit) backs off: no separate events runs, and full runs every
 *   [MINUTES_EVERY_MS] × 2^level (4, 8, 16 minutes) for [SLOW_FOR_MS]; nothing is lost, the markers
 *   only move for acknowledged records. Another problem (no network, «nur über WLAN» without
 *   Wi-Fi, server error): the events wait for the next full slot.
 */
class LiveUploadPlan(pointAtMs: Long) {
    enum class Kind { FULL, EVENTS }

    data class Run(val atMs: Long, val kind: Kind)

    enum class Result { OK, RATE_LIMITED, FAILED }

    private var nextFullAtMs = pointAtMs + FIRST_AFTER_POINT_MS
    private var eventDueAtMs: Long? = null
    private var lastRunAtMs = Long.MIN_VALUE / 4
    private var level = 0
    private var slowUntilMs = Long.MIN_VALUE / 4

    /** The back-off level after 429 answers (0: normal). */
    val backOffLevel: Int get() = level

    private fun fullInterval(): Long = MINUTES_EVERY_MS shl level

    /** A map event ([triggers]) ended at [endMs]. */
    fun onEvent(endMs: Long) {
        if (endMs <= 0 || level > 0) return // backed off: the next full run sends it
        val due = maxOf(endMs + EVENT_DELAY_MS, lastRunAtMs + MIN_GAP_MS)
        eventDueAtMs = eventDueAtMs?.let { minOf(it, due) } ?: due
    }

    /** The next run. */
    fun next(): Run {
        val ev = eventDueAtMs
        return when {
            ev == null -> Run(nextFullAtMs, Kind.FULL)
            ev + MIN_GAP_MS > nextFullAtMs -> Run(minOf(ev, nextFullAtMs), Kind.FULL)
            else -> Run(ev, Kind.EVENTS)
        }
    }

    /** [run] ended at [nowMs] with [result]. */
    fun done(run: Run, result: Result, nowMs: Long) {
        lastRunAtMs = nowMs
        when (result) {
            Result.RATE_LIMITED -> {
                level = minOf(level + 1, MAX_LEVEL)
                slowUntilMs = nowMs + SLOW_FOR_MS
                eventDueAtMs = null
                nextFullAtMs = nowMs + fullInterval()
            }
            Result.FAILED -> {
                eventDueAtMs = null
                nextFullAtMs = nowMs + fullInterval()
            }
            Result.OK -> {
                if (level > 0 && nowMs >= slowUntilMs) {
                    level--
                    slowUntilMs = nowMs + SLOW_FOR_MS
                }
                eventDueAtMs = null
                nextFullAtMs = if (run.kind == Kind.FULL) nowMs + fullInterval() else maxOf(nextFullAtMs, nowMs + MIN_GAP_MS)
            }
        }
    }

    companion object {
        /**
         * Whether a just-detected event starts an events run: exactly the events the upload sends
         * ([Payloads.isMapEvent], 0.6.4). Voices, music, unclassified, wind or a failed quality
         * gate never cause a request.
         */
        fun triggers(e: ch.lessnoise.app.service.LiveEvent?): Boolean = e != null && Payloads.isMapEvent(e)

        /** First full run this long after the running measurement got its point. */
        const val FIRST_AFTER_POINT_MS = 30_000L

        /** Then a full run (minutes and events) every … */
        const val MINUTES_EVERY_MS = 2 * 60_000L

        /** A loud event goes out this long after it ended. */
        const val EVENT_DELAY_MS = 30_000L

        /** At most one run per … */
        const val MIN_GAP_MS = 30_000L

        /** After a 429: slower for this long, then one level faster again. */
        const val SLOW_FOR_MS = 60 * 60_000L

        const val MAX_LEVEL = 3
    }
}

/** Room-backed [UploadSource]. */
private class RoomUploadSource(
    private val context: Context,
    private val db: AppDatabase,
    private val calibrations: CalibrationRepository,
) : UploadSource {
    override suspend fun minutesAfter(afterMs: Long, limit: Int): List<MinuteEntity> = db.measurements().minutesAfter(afterMs, limit)
    override suspend fun eventsAfter(afterMs: Long, limit: Int): List<EventEntity> = db.measurements().eventsAfter(afterMs, limit)
    override suspend fun measurements(): List<MeasurementEntity> = db.measurements().allMeasurements()
    override suspend fun movingSpans(): List<MovingSpanEntity> = db.measurements().allMovingSpans()

    override suspend fun siteExtras(): SiteExtras {
        val source = AudioSourceSelector.select(context)
        val active = calibrations.active(source)
        return SiteExtras(
            deviceModel = Build.MODEL ?: "",
            audioSource = source,
            calibrated = active.calibrated,
            calibrationOffsetDb = active.offsetDb,
        )
    }
}

/** Everything of the upload, wired once per process (AppContainer.upload). */
class UploadModule(
    context: Context,
    db: AppDatabase,
    calibrations: CalibrationRepository,
    /**
     * 0.6.7: how long records wait after their end before they may be sent ([Uploader.MOTION_HOLD_MS]
     * while a «Messen» measurement runs in an edition with the motion detector, else 0); AppContainer.
     */
    motionHoldMs: () -> Long = { 0L },
) {
    private val appContext = context.applicationContext
    val store: UploadState = UploadState(appContext)
    /** «Laut! Melden» reports waiting to be sent (with coordinates) and the sent ones (without). */
    val reports: ReportState = ReportState(appContext)
    val client: UploadClient = UploadClient(BuildConfig.API_BASE)
    val uploader: Uploader = Uploader(
        client = client,
        store = store,
        source = RoomUploadSource(appContext, db, calibrations),
        reports = reports,
        appVersion = BuildConfig.VERSION_NAME,
        appBuild = BuildConfig.VERSION_CODE,
        holdMs = motionHoldMs,
    )
    private val minutes = db.measurements()

    /** Minutes of shared measurements not yet acknowledged by the server (settings screen). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingMinutes: Flow<Int> = store.state.flatMapLatest { minutes.sharedMinuteCountAfter(uploader.minutesPendingAfterMs(it)) }

    private val work: WorkManager get() = WorkManager.getInstance(appContext)

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (store.state.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /**
     * Brings the scheduled jobs in line with the settings (on, off, «nur über WLAN»). [replace]:
     * true after a settings change (new constraints); false at app start, which keeps an existing
     * hourly job and its timing (the process may have been started for that very job).
     */
    fun applySchedule(replace: Boolean = true) {
        if (!BuildConfig.UPLOAD_AVAILABLE) return
        val s = store.state.value
        if (!s.enabled) {
            work.cancelUniqueWork(UploadWorker.WORK_PERIODIC)
            work.cancelUniqueWork(UploadWorker.WORK_NOW)
            work.cancelUniqueWork(UploadWorker.WORK_MORE)
            work.cancelUniqueWork(UploadWorker.WORK_LIVE)
            return
        }
        val req = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setInitialDelay(delayToNextHourMs(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(
            UploadWorker.WORK_PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    /**
     * The editions without «Messwerte teilen» (UPLOAD_AVAILABLE = false, no INTERNET): the
     * offline edition («Offline-Version»), and the Labor build installed over «Weniger Lärm Labor · Karte»
     * (same app id ch.lessnoise.labor). Shown here for the offline edition; the Labor case is the same.
     * Someone who switches from the «Online-Version» by installing the offline one over it keeps
     * the sharing state and WorkManager's scheduled upload jobs. If sharing was on, it is switched
     * off here and the jobs are cancelled: the jobs would do nothing in this edition (UploadWorker
     * returns at once), and a later switch back to the Online-Version must not send what was
     * measured in between — [Uploader.enable] starts at "now" again once the person switches
     * sharing back on. Device id and token are kept, so the Online-Version can still delete the
     * data on the server.
     */
    fun stopForOfflineEdition() {
        if (BuildConfig.UPLOAD_AVAILABLE || !store.state.value.enabled) return
        uploader.disable()
        runCatching {
            work.cancelUniqueWork(UploadWorker.WORK_PERIODIC)
            work.cancelUniqueWork(UploadWorker.WORK_NOW)
            work.cancelUniqueWork(UploadWorker.WORK_MORE)
            work.cancelUniqueWork(UploadWorker.WORK_LIVE)
        }
    }

    /** One upload soon (after a measurement stopped, after switching on, after a site change, after a report). */
    fun uploadSoon(delaySeconds: Long = 15) {
        if (!BuildConfig.UPLOAD_AVAILABLE || !store.state.value.enabled) return
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(UploadWorker.WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /**
     * The live uploads of the running shared measurement ([LiveUploadPlan]), in this process (it
     * lives as long as the measurement's foreground service, which holds a wake lock), not as a
     * WorkManager job every 30 s. Cancelled when the measurement stops or is no longer shared.
     */
    private suspend fun liveLoop(live: StateFlow<LiveState>) = coroutineScope {
        val plan = LiveUploadPlan(System.currentTimeMillis())
        val lock = Any()
        val wake = Channel<Unit>(Channel.CONFLATED)
        launch {
            live.map { it.lastEvent }.distinctUntilChanged().collect { e ->
                // Only an event that may leave the phone at all starts an events run (0.6.4); not
                // one while the phone is being carried (0.6.7: it is not sent).
                if (e != null && LiveUploadPlan.triggers(e) && !live.value.moving) {
                    // 0.6.7: not before the upload's motion hold has passed (else the run finds nothing to send).
                    val wait = maxOf(0L, uploader.holdNowMs() - LiveUploadPlan.EVENT_DELAY_MS)
                    synchronized(lock) { plan.onEvent(e.endEpochMs + wait) }
                    wake.trySend(Unit)
                }
            }
        }
        while (true) {
            val run = synchronized(lock) { plan.next() }
            val wait = run.atMs - System.currentTimeMillis()
            // A new event may move the next run earlier: wait for it or for the time.
            if (wait > 0 && withTimeoutOrNull(wait) { wake.receive() } != null) continue
            val result = if (!networkAllowed()) {
                LiveUploadPlan.Result.FAILED
            } else {
                when (val o = withContext(Dispatchers.IO) { uploader.run(withMinutes = run.kind == LiveUploadPlan.Kind.FULL) }) {
                    is Uploader.Outcome.Done -> LiveUploadPlan.Result.OK
                    is Uploader.Outcome.Retry -> if (o.rateLimited) LiveUploadPlan.Result.RATE_LIMITED else LiveUploadPlan.Result.FAILED
                    else -> LiveUploadPlan.Result.FAILED
                }
            }
            synchronized(lock) { plan.done(run, result, System.currentTimeMillis()) }
        }
    }

    /** Whether a live upload may go now: a connection, and Wi-Fi (unmetered) with «nur über WLAN». */
    private fun networkAllowed(): Boolean = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!store.state.value.wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }.getOrDefault(true)

    /**
     * The running measurement got its shifted point (0.6.3): its whole measurement is sent from now
     * on, also the minutes before the point ([Uploader.pointAttached]). The first live upload follows
     * from the live state ([start]).
     */
    suspend fun pointAttached(measurementStartMs: Long) {
        if (!BuildConfig.UPLOAD_AVAILABLE) return
        uploader.pointAttached(measurementStartMs)
    }

    /** Another run after the server's hourly request limit has room again. */
    fun scheduleFollowUp() {
        if (!BuildConfig.UPLOAD_AVAILABLE || !store.state.value.enabled) return
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints())
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(UploadWorker.WORK_MORE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /**
     * Hooks that need no change in the measurement code: when the live state goes from running to
     * stopped, upload once (the service writes the last minute within 3 s; the job waits 15 s);
     * while a shared measurement runs (it has its point and sharing is on), the live uploads of
     * [UploadCadence] — this runs in the app's process scope, which lives as long as the
     * measurement's foreground service, also with the screen off or the app closed;
     * after «Alte Messungen neu bewerten», send the last 7 days again with the new levels.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, live: StateFlow<LiveState>, recalibrator: Recalibrator) {
        if (!BuildConfig.UPLOAD_AVAILABLE) return
        applySchedule(replace = false)
        scope.launch {
            var wasRunning = live.value.running
            var wasUnterwegs = live.value.running && live.value.unterwegs
            live.collect { st ->
                // 0.6.4: stopping an «Unterwegs» measurement (never shared) causes no request.
                if (wasRunning && !st.running && !wasUnterwegs) uploadSoon(UploadCadence.AFTER_STOP_MS / 1000)
                wasRunning = st.running
                if (st.running) wasUnterwegs = st.unterwegs
            }
        }
        scope.launch {
            // The start of the running shared measurement, or null; a new value restarts the loop.
            combine(live, store.state) { l, s ->
                if (l.running && !l.unterwegs && l.sharedPoint != null && s.enabled && !s.authFailed) l.measurementStartMs else null
            }.distinctUntilChanged().collectLatest { start ->
                // The WorkManager job of the first 0.6.3 builds (every 10 minutes) is gone.
                runCatching { work.cancelUniqueWork(UploadWorker.WORK_LIVE) }
                if (start == null) return@collectLatest
                liveLoop(live)
            }
        }
        scope.launch {
            var wasDone = recalibrator.state.value is Recalibrator.State.Done
            recalibrator.state.collect { st ->
                val done = st is Recalibrator.State.Done
                if (done && !wasDone && store.state.value.registered) {
                    uploader.rewindAfterReevaluation()
                    uploadSoon()
                }
                wasDone = done
            }
        }
    }

    companion object {
        /** Five minutes after the next full hour (local time). */
        fun delayToNextHourMs(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Long {
            val next = now.withMinute(0).withSecond(0).withNano(0).plusHours(1).plusMinutes(5)
            return Duration.between(now, next).toMillis().coerceAtLeast(0)
        }
    }
}
