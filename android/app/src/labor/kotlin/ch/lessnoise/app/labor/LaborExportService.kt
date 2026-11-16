package ch.lessnoise.app.labor

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.lessnoise.app.R
import ch.lessnoise.app.ui.MainActivity
import ch.lessnoise.app.ui.shareFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.Locale

// LABOR BUILD ONLY (app/src/labor/; declared in src/labor and src/laborKarte AndroidManifest.xml, with
// FOREGROUND_SERVICE_DATA_SYNC, which the published editions do not have — tools/check_release_apk.py).
// 0.9.4.1: «In Ordner exportieren» and «Als ZIP teilen» run here, as a foreground service of type
// dataSync with a notification «Export · 412 von 1830 Dateien · 165 von 820 MB», a progress bar and
// «Abbrechen», so they go on when the app is left or the screen turns off (0.9.4 ran them in the
// settings screen's coroutine scope; a 2,8 GB ZIP was killed with the app). A partial wake lock is
// held only while files are copied. The pure part (state, texts, ZIP rules) is LaborExportJob.kt.

/** Where the ZIP and its pending ledger list live (cache; the share folder is in res/xml/file_paths.xml). */
object LaborZipFiles {
    fun shareDir(context: Context): File = File(context.externalCacheDir ?: context.cacheDir, "labor_share")

    private fun pendingDir(context: Context) = File(context.cacheDir, "labor_zip_pending")

    fun pending(context: Context, zipName: String): File = File(pendingDir(context), "$zipName.tsv")

    /** Deletes earlier ZIPs, half-written temp files and pending lists (before a new ZIP, «Alle Aufnahmen löschen»). */
    fun clear(context: Context) {
        shareDir(context).listFiles()?.forEach { it.delete() }
        pendingDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Free bytes for a new ZIP: the free space of the share folder's partition plus what [clear] frees there. */
    fun freeBytes(context: Context): Long {
        val dir = shareDir(context).apply { mkdirs() }
        return dir.usableSpace + (dir.listFiles()?.sumOf { it.length() } ?: 0L)
    }

    /** «Zu wenig Speicher für ZIP: … – Ordner-Export nutzen», or null. */
    fun spaceRefusal(context: Context, files: List<ExportSource>): String? =
        ZipSpace.refusal(ZipSpace.needed(ExportEstimate.of(files)), freeBytes(context))
}

/** Opens the share sheet for a finished ZIP and, the first time, records its files in the ledger. */
object LaborZipShare {
    const val SUBJECT = "Weniger Lärm Labor – Aufnahmen"

    /** On the main thread (starts an activity); then call [recordShared] off it. */
    fun open(context: Context, zip: File) {
        shareFiles(context, listOf(zip), "application/zip", "$SUBJECT (${zip.name})")
        context.getSystemService(NotificationManager::class.java)?.cancel(LaborExportService.DONE_ID)
    }

    /** Blocking (file I/O). The ledger only ever gets a ZIP's files here: complete AND shared. */
    fun recordShared(context: Context, zip: File) {
        val ledger = LaborExportStore.loadLedger(context)
        if (ZipJob.onShared(ledger, LaborZipFiles.pending(context, zip.name))) {
            LaborExportStore.saveLedger(context, ledger)
            Labor.filesVersion.value = Labor.filesVersion.value + 1
        }
    }
}

/**
 * The tap on «Export fertig» of a ZIP: opens the share sheet and finishes (a notification may not
 * start an activity through a service or receiver since Android 12, hence this invisible activity).
 */
class LaborShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(LaborExportService.EXTRA_ZIP)
        val zip = name?.takeIf { it.endsWith(".zip") && '/' !in it && !it.startsWith(".") }?.let { File(LaborZipFiles.shareDir(this), it) }
        if (zip == null || !zip.isFile) {
            Toast.makeText(this, "ZIP nicht mehr vorhanden.", Toast.LENGTH_LONG).show()
        } else {
            try {
                LaborZipShare.open(this, zip)
                val app = applicationContext
                Thread { try { LaborZipShare.recordShared(app, zip) } catch (e: Exception) { Log.w(TAG, "ledger: ${e.javaClass.simpleName}") } }.start()
            } catch (e: Exception) {
                Toast.makeText(this, "Teilen nicht möglich: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    private companion object { const val TAG = "LaborExport" }
}

class LaborExportService : Service() {
    companion object {
        private const val TAG = "LaborExport"
        const val ACTION_RUN = "ch.lessnoise.app.labor.EXPORT_RUN"
        const val ACTION_CANCEL = "ch.lessnoise.app.labor.EXPORT_CANCEL"
        const val EXTRA_ZIP = "zip"
        private const val CHANNEL = "labor_export"
        const val NOTIFICATION_ID = 7301
        const val DONE_ID = 7302
        /** Safety timeout of the wake lock; Android 15 also limits dataSync to 6 h a day (onTimeout). */
        private const val WAKE_LOCK_MS = 6 * 3_600_000L

        /** Starts [request]; null if started, else the refusal («Es läuft schon ein Export …»). */
        fun start(context: Context, request: ExportRequest): String? {
            if (!LaborExportJobs.begin(request)) return LaborExportJobs.BUSY
            return try {
                ContextCompat.startForegroundService(context, Intent(context, LaborExportService::class.java).setAction(ACTION_RUN))
                null
            } catch (e: Exception) {
                LaborExportJobs.take()
                LaborExportJobs.finish(ExportJobState.Failed(request.kind, "Export nicht startbar: ${e.javaClass.simpleName}"))
                "Export nicht startbar: ${e.javaClass.simpleName}"
            }
        }

        /** «Abbrechen» in the app (the notification sends [ACTION_CANCEL]). */
        fun cancel() = LaborExportJobs.requestCancel()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private val throttle = ProgressThrottle(1_000)
    @Volatile private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            LaborExportJobs.requestCancel()
            if (!working) stopSelf()
            return START_NOT_STICKY
        }
        // Already in the foreground with a job (LaborExportJobs.begin lets only one through).
        if (working) return START_NOT_STICKY
        createChannel()
        // startForeground() must follow every startForegroundService(), also when there is nothing to do.
        val req = LaborExportJobs.take()
        promote(req?.kind ?: ExportKind.FOLDER)
        if (req == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        working = true
        scope.launch { runJob(req) }
        return START_NOT_STICKY
    }

    private fun promote(kind: ExportKind) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, progressNotification(kind, null), type)
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException: the job still runs, as in 0.9.4 without the service.
            Log.w(TAG, "startForeground failed: ${e.javaClass.simpleName}")
        }
    }

    /** Android 15+: dataSync has used up its 6 h of the day. Stop like «Abbrechen». */
    override fun onTimeout(startId: Int, fgsType: Int) {
        LaborExportJobs.requestCancel()
    }

    private suspend fun runJob(req: ExportRequest) {
        val end: ExportJobState = try {
            when (req) {
                is ExportRequest.Zip -> runZip(req)
                is ExportRequest.Folder -> runFolder(req)
            }
        } catch (e: Exception) {
            Log.w(TAG, "export: ${e.javaClass.simpleName}")
            when {
                req is ExportRequest.Folder && e is SecurityException -> {
                    LaborExportStore.setTreeUri(this, null)
                    ExportJobState.Failed(req.kind, "Zugriff auf den Export-Ordner verloren; bitte den Ordner neu wählen.")
                }
                req is ExportRequest.Zip -> ExportJobState.Failed(req.kind, "ZIP konnte nicht erstellt werden (${e.javaClass.simpleName}; Speicher?).")
                else -> ExportJobState.Failed(req.kind, "Export nicht möglich: ${e.javaClass.simpleName}")
            }
        } finally {
            releaseWakeLock()
        }
        LaborExportJobs.finish(end)
        withContext(Dispatchers.Main) {
            ServiceCompat.stopForeground(this@LaborExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            postEnd(end)
            working = false
            stopSelf()
        }
    }

    private fun onProgress(kind: ExportKind, p: ExportProgress) {
        LaborExportJobs.progress(kind, p)
        if (throttle.due(System.currentTimeMillis()) || p.filesDone == p.filesTotal) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progressNotification(kind, p))
            } catch (e: Exception) {
                Log.w(TAG, "notify: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun runZip(req: ExportRequest.Zip): ExportJobState {
        LaborZipFiles.clear(this)
        LaborZipFiles.spaceRefusal(this, req.files)?.let { return ExportJobState.Failed(ExportKind.ZIP, it, suggestFolder = true) }
        val zip = File(LaborZipFiles.shareDir(this), req.zipName)
        acquireWakeLock()
        val ok = ZipJob.run(
            zip, req.files, LaborZipFiles.pending(this, req.zipName),
            isCancelled = { LaborExportJobs.cancelRequested },
            onProgress = { onProgress(ExportKind.ZIP, it) },
        )
        if (!ok) return ExportJobState.Cancelled(ExportKind.ZIP, "ZIP abgebrochen; nichts gilt als exportiert.")
        return ExportJobState.Done(
            ExportKind.ZIP,
            "ZIP fertig: ${zip.name}, ${ExportScopes.files(req.files.size)}, ${ExportScopes.size(zip.length())}.",
            zip, System.currentTimeMillis(),
        )
    }

    /** «In Ordner exportieren» (0.7.4; scope 0.9.4) — the code of 0.9.4's EditionUi.runExport, moved here. */
    private fun runFolder(req: ExportRequest.Folder): ExportJobState {
        Labor.init(this)
        val tree = Uri.parse(req.treeUri)
        val dirs = Labor.dirs(this)
        val sc = req.scope
        val noHours = req.withoutHourFiles
        val ledger = LaborExportStore.loadLedger(this)
        val sources = IncrementalExport.sources(dirs, Labor.openContinuousFile)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val inScope = ExportScopes.select(
            sources, ExportTarget.ZIP, if (sc == ExportScope.LAST_24H) sc else ExportScope.ALL, noHours, ledger, now, zone,
        )
        val selected = ExportScopes.select(sources, ExportTarget.FOLDER, sc, noHours, ledger, now, zone)
        val plan = ExportScopes.withScopedManifest(sc, selected, dirs.manifest, File(cacheDir, "labor_export"), now)
        val sink = SafExportSink(contentResolver, tree, LaborExportStore.treeName(this, tree))
        acquireWakeLock()
        val r = IncrementalExport.run(
            plan, ledger, sink,
            onProgress = { onProgress(ExportKind.FOLDER, it) },
            onFileDone = { LaborExportStore.saveLedger(this, ledger) },
            isCancelled = { LaborExportJobs.cancelRequested },
        )
        LaborExportStore.saveLedger(this, ledger)
        Labor.filesVersion.value = Labor.filesVersion.value + 1 // the ledger changed: refresh the size line
        if (r.cancelled) {
            return ExportJobState.Cancelled(
                ExportKind.FOLDER,
                "Export abgebrochen: ${ExportScopes.files(r.filesCopied)} (${mb(r.bytesCopied)}) schon in ${r.destination}; der Rest beim nächsten Export.",
            )
        }
        val skipped = inScope.size - plan.size
        return ExportJobState.Done(
            ExportKind.FOLDER,
            buildString {
                append("Exportiert (${sc.label}${if (noHours) " ohne Stundendateien" else ""}): ${r.filesCopied} Dateien, ${mb(r.bytesCopied)} nach ${r.destination}")
                if (skipped > 0) append(" ($skipped schon vorhanden)")
                if (r.failures.isNotEmpty()) append("; ${r.failures.size} fehlgeschlagen: ${r.failures.first().first} (${r.failures.first().second})")
                append(".")
            },
            null, System.currentTimeMillis(),
        )
    }

    private fun mb(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.GERMANY, "%.2f GB", bytes / 1e9)
        else -> String.format(Locale.GERMANY, "%.1f MB", bytes / 1e6)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(kind: ExportKind, p: ExportProgress?): Notification {
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, LaborExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_level)
            .setContentTitle(if (kind == ExportKind.ZIP) "ZIP wird erstellt" else "Export in Ordner")
            .setContentText(p?.let(ExportProgressText::line) ?: ExportProgressText.PREPARING)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(0, "Abbrechen", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (p == null || (p.bytesTotal == 0L && p.filesTotal == 0)) b.setProgress(0, 0, true)
        else b.setProgress(1000, (ExportProgressText.fraction(p) * 1000).toInt(), false)
        return b.build()
    }

    private fun postEnd(end: ExportJobState) {
        val (title, text, tap) = when (end) {
            is ExportJobState.Done -> if (end.zip != null) {
                val share = PendingIntent.getActivity(
                    this, 3, Intent(this, LaborShareActivity::class.java).putExtra(EXTRA_ZIP, end.zip.name),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                Triple("Export fertig", "${end.zip.name} (${ExportScopes.size(end.zip.length())}) – antippen zum Teilen", share)
            } else Triple("Export fertig", end.message, openApp())
            is ExportJobState.Failed -> Triple("Export fehlgeschlagen", end.message, openApp())
            is ExportJobState.Cancelled -> Triple("Export abgebrochen", end.message, openApp())
            else -> return
        }
        try {
            getSystemService(NotificationManager::class.java).notify(
                DONE_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_level)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setSilent(true)
                    .setAutoCancel(true)
                    .setContentIntent(tap)
                    .build(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "notify end: ${e.javaClass.simpleName}")
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // LOW: in the shade and the status bar, never a sound (updated once a second).
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Labor-Export", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Fortschritt von «In Ordner exportieren» und «Als ZIP teilen»"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LessNoise::LaborExport").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        // Destroyed while copying (should not happen: the job stops the service): stop the copy, which
        // deletes a half-written ZIP (ExportScopes.writeZip) and leaves the ledger as it is.
        if (working) LaborExportJobs.requestCancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
