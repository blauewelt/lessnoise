package ch.lessnoise.app.labor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry

// LABOR BUILD ONLY (app/src/labor/). 0.9.4.1: the export jobs («In Ordner exportieren», «Als ZIP
// teilen») run in a foreground service (LaborExportService.kt, type dataSync) so that they survive
// leaving the app and the screen turning off. This file is the pure part — the job state the UI and
// the notification show, the progress line, the ZIP compression choice, the free-space check and the
// «ledger only after a completed ZIP that was shared» bookkeeping — unit-tested in
// app/src/testLabor/ (ExportJobTest).

enum class ExportKind { FOLDER, ZIP }

/** What the UI hands to the service (in-process: the ZIP's file list is decided in its dialog). */
sealed interface ExportRequest {
    val kind: ExportKind

    /** [treeUri]: the SAF tree (string form of the Uri, so this stays pure Kotlin). */
    data class Folder(val treeUri: String, val scope: ExportScope, val withoutHourFiles: Boolean) : ExportRequest {
        override val kind get() = ExportKind.FOLDER
    }

    /** [files] in ZIP order (for «Letzte 24 h» with the filtered manifest), [zipName] e.g. `lessnoise-labor-20261026-24h.zip`. */
    data class Zip(val files: List<ExportSource>, val zipName: String, val scopeLabel: String) : ExportRequest {
        override val kind get() = ExportKind.ZIP
    }
}

/** What the Labor section and the notification show; one job at a time ([LaborExportJobs]). */
sealed interface ExportJobState {
    data object Idle : ExportJobState

    /** [progress] is null while the job prepares (lists the files, filters the manifest). */
    data class Running(val kind: ExportKind, val progress: ExportProgress?) : ExportJobState

    /** [zip]: the finished ZIP, shared from the notification or the in-app button. */
    data class Done(val kind: ExportKind, val message: String, val zip: File?, val finishedAtMs: Long) : ExportJobState

    /** [suggestFolder]: «Zu wenig Speicher für ZIP …» — the UI offers the folder export. */
    data class Failed(val kind: ExportKind, val message: String, val suggestFolder: Boolean = false) : ExportJobState

    data class Cancelled(val kind: ExportKind, val message: String) : ExportJobState
}

/**
 * The one export job of the process. [begin] refuses a second job while one runs; the service reads
 * [cancelRequested] after every chunk. Pure (no Android), so the gate is unit-tested.
 */
object LaborExportJobs {
    const val BUSY = "Es läuft schon ein Export – warten oder «Abbrechen»."

    private val _state = MutableStateFlow<ExportJobState>(ExportJobState.Idle)
    val state: StateFlow<ExportJobState> = _state

    @Volatile var cancelRequested: Boolean = false
        private set

    private var request: ExportRequest? = null
    private var autoShared: Long? = null

    val busy: Boolean get() = _state.value is ExportJobState.Running

    /** Reserves the job for [request] (handed to the service in-process); false if one runs. */
    @Synchronized
    fun begin(request: ExportRequest): Boolean {
        if (busy) return false
        cancelRequested = false
        this.request = request
        _state.value = ExportJobState.Running(request.kind, null)
        return true
    }

    /** The service takes the request once; null if there is none (e.g. a restarted process). */
    @Synchronized
    fun take(): ExportRequest? = request.also { request = null }

    fun progress(kind: ExportKind, p: ExportProgress) {
        if (_state.value is ExportJobState.Running) _state.value = ExportJobState.Running(kind, p)
    }

    fun requestCancel() { if (busy) cancelRequested = true }

    @Synchronized
    fun finish(end: ExportJobState) {
        require(end !is ExportJobState.Running)
        request = null
        cancelRequested = false
        _state.value = end
    }

    /**
     * The in-app share sheet opens by itself once, when the ZIP is finished while the Labor section
     * is on screen (within [windowMs] of [ExportJobState.Done.finishedAtMs]); later only by the button
     * or the notification. True exactly once per finished ZIP.
     */
    @Synchronized
    fun consumeAutoShare(done: ExportJobState.Done, nowMs: Long, windowMs: Long = 3_000): Boolean {
        if (done.zip == null || autoShared == done.finishedAtMs) return false
        if (nowMs - done.finishedAtMs !in 0..windowMs) return false
        autoShared = done.finishedAtMs
        return true
    }

    /** Tests only. */
    @Synchronized
    fun resetForTest() { request = null; cancelRequested = false; autoShared = null; _state.value = ExportJobState.Idle }
}

/** «Export · 412 von 1830 Dateien · 165 von 820 MB» and the bar's fraction (by bytes). */
object ExportProgressText {
    const val PREPARING = "Export wird vorbereitet …"

    fun fraction(p: ExportProgress): Float = when {
        p.bytesTotal > 0 -> (p.bytesDone.toDouble() / p.bytesTotal).coerceIn(0.0, 1.0).toFloat()
        p.filesTotal > 0 -> (p.filesDone.toFloat() / p.filesTotal).coerceIn(0f, 1f)
        else -> 0f
    }

    fun line(p: ExportProgress): String =
        "Export · ${p.filesDone} von ${p.filesTotal} ${if (p.filesTotal == 1) "Datei" else "Dateien"} · ${bytes(p.bytesDone, p.bytesTotal)}"

    /** Both numbers in the unit of [total]: «165 von 820 MB», «1,2 von 2,8 GB», «0,4 von 4,2 MB». */
    fun bytes(done: Long, total: Long): String = when {
        total >= 1_000_000_000L -> String.format(Locale.GERMANY, "%.1f von %.1f GB", done / 1e9, total / 1e9)
        total >= 10_000_000L -> String.format(Locale.GERMANY, "%d von %d MB", done / 1_000_000, total / 1_000_000)
        else -> String.format(Locale.GERMANY, "%.1f von %.1f MB", done / 1e6, total / 1e6)
    }
}

/** Throttles notification updates (Android drops updates that come faster than a few per second). */
class ProgressThrottle(private val intervalMs: Long) {
    private var last = Long.MIN_VALUE

    fun due(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < intervalMs) return false
        last = nowMs
        return true
    }
}

/** How each ZIP entry is written. */
object ZipMethods {
    /**
     * WAV (PCM) and AAC (.m4a, already compressed) are STORED: deflate gains little on them and costs
     * most of the time of a big ZIP (ExportJobTest.zipBenchmark, WORKLOG 0.9.4.1); the text logs
     * (.jsonl) are DEFLATED (they shrink to a fraction).
     */
    val STORED_EXTENSIONS = setOf("wav", "m4a")

    fun forName(path: String): Int =
        if (path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT) in STORED_EXTENSIONS) ZipEntry.STORED
        else ZipEntry.DEFLATED

    /** The temp file a ZIP is written to before the rename: `.<name>.part` beside it. */
    fun tempFor(zip: File): File = File(zip.absoluteFile.parentFile, ".${zip.name}.part")

    /** Leftovers of a ZIP the process was killed in the middle of. */
    fun isTemp(f: File): Boolean = f.name.startsWith(".") && f.name.endsWith(".part")
}

/**
 * «Zu wenig Speicher für ZIP»: the ZIP is a full copy in the cache partition, so it needs about the
 * selected bytes free there, plus the ZIP headers and a reserve (Android misbehaves on a full disk).
 * The folder export needs no copy.
 */
object ZipSpace {
    const val RESERVE = 20_000_000L
    /** Local header + central directory entry with a ~40-character name, ZIP64 extra fields included. */
    const val PER_ENTRY = 200L

    fun needed(e: ExportEstimate): Long = e.bytes + e.files * PER_ENTRY + RESERVE

    /** null if [free] suffices, else the line for the UI. MB rounded up for the need, down for the free space. */
    fun refusal(needed: Long, free: Long): String? =
        if (free >= needed) null
        else "Zu wenig Speicher für ZIP: ${(needed + 999_999) / 1_000_000} MB nötig, ${maxOf(0L, free) / 1_000_000} MB frei – Ordner-Export nutzen"
}

/**
 * The ZIP's files go into the export ledger only when the ZIP is complete AND the share sheet was
 * opened (0.9.4 recorded them when the sheet opened; a cancelled or failed ZIP never). Between the two
 * — possibly across a process restart, the share then comes from the «Export fertig» notification —
 * the list waits in a small pending file (the ledger format) in the app's cache.
 */
object ZipJob {
    /**
     * Builds [zip] from [files] ([ExportScopes.writeZip]); only when it is complete writes [pending].
     * Never touches the ledger. Cancelled: false, no [zip], no temp file, no [pending]. Failed: throws, same.
     */
    fun run(
        zip: File,
        files: List<ExportSource>,
        pending: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (ExportProgress) -> Unit = {},
        method: (String) -> Int = ZipMethods::forName,
    ): Boolean {
        pending.delete()
        if (!ExportScopes.writeZip(zip, files, isCancelled, onProgress, method)) return false
        pending.absoluteFile.parentFile?.mkdirs()
        val tmp = File(pending.path + ".tmp")
        tmp.writeText(ExportLedger(files.associate { it.relativePath to ExportLedger.Entry(it.size, it.lastModified) }).serialize())
        if (!tmp.renameTo(pending)) { pending.delete(); tmp.renameTo(pending) }
        return true
    }

    /**
     * After the share sheet for the ZIP opened: records [pending]'s files in [ledger] and deletes
     * [pending]. False (ledger unchanged) if there is no pending list (already recorded, or the ZIP
     * never completed).
     */
    fun onShared(ledger: ExportLedger, pending: File): Boolean {
        if (!pending.isFile) return false
        val p = try { ExportLedger.parse(pending.readText()) } catch (_: Exception) { return false }
        for ((path, e) in p.entries()) ledger.record(path, e.size, e.lastModified)
        pending.delete()
        return true
    }
}
