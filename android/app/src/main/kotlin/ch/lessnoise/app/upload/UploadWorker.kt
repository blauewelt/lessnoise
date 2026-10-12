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
import ch.lessnoise.app.data.Recalibrator
import ch.lessnoise.app.service.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Background upload for «Messwerte teilen» (server/DESIGN.md §4.6): a periodic job every hour
 * (a few minutes after the full hour, so the hour's last minute is closed) and a one-shot job
 * shortly after a measurement stops. Network constraint: Wi-Fi/unmetered with «nur über WLAN»
 * (default), otherwise any connection. Failures back off exponentially (WorkManager, from 10 min).
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
     * offline edition «Weniger Lärm», and the Labor build installed over «Weniger Lärm Labor · Karte»
     * (same app id ch.lessnoise.labor). Shown here for the offline edition; the Labor case is the same.
     * Someone who switches from the Karten edition by installing the offline one over it keeps
     * the sharing state and WorkManager's scheduled upload jobs. If sharing was on, it is switched
     * off here and the jobs are cancelled: the jobs would do nothing in this edition (UploadWorker
     * returns at once), and a later switch back to the Karten edition must not send what was
     * measured in between — [Uploader.enable] starts at "now" again once the person switches
     * sharing back on. Device id and token are kept, so the Karten edition can still delete the
     * data on the server.
     */
    fun stopForOfflineEdition() {
        if (BuildConfig.UPLOAD_AVAILABLE || !store.state.value.enabled) return
        uploader.disable()
        runCatching {
            work.cancelUniqueWork(UploadWorker.WORK_PERIODIC)
            work.cancelUniqueWork(UploadWorker.WORK_NOW)
            work.cancelUniqueWork(UploadWorker.WORK_MORE)
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
     * after «Alte Messungen neu bewerten», send the last 7 days again with the new levels.
     */
    fun start(scope: CoroutineScope, live: StateFlow<LiveState>, recalibrator: Recalibrator) {
        if (!BuildConfig.UPLOAD_AVAILABLE) return
        applySchedule(replace = false)
        scope.launch {
            var wasRunning = live.value.running
            live.collect { st ->
                if (wasRunning && !st.running) uploadSoon()
                wasRunning = st.running
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
