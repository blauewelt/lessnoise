package ch.lessnoise.app.labor

import java.io.File
import java.io.InputStream
import java.io.OutputStream

// LABOR BUILD ONLY (app/src/labor/). «In Ordner exportieren»: incremental copy of the recording
// folder into a folder the owner picked (Storage Access Framework). This file is pure Kotlin so
// that the bookkeeping can be unit-tested; the Android side (DocumentsContract) is in
// LaborExportSaf.kt.

/**
 * Which files were already exported, keyed by relative path (`clips/ev_….wav`) with size and
 * lastModified: a file is copied again only when either changed. Serialised as one line per file,
 * `path<TAB>size<TAB>mtime`, so it needs no JSON library and survives a partial write (a bad
 * line is dropped, that file is simply copied again).
 */
class ExportLedger(initial: Map<String, Entry> = emptyMap()) {
    data class Entry(val size: Long, val lastModified: Long)

    private val entries = LinkedHashMap<String, Entry>(initial)

    val size: Int get() = entries.size

    operator fun get(path: String): Entry? = entries[path]

    fun isCurrent(path: String, size: Long, lastModified: Long): Boolean =
        entries[path]?.let { it.size == size && it.lastModified == lastModified } == true

    fun record(path: String, size: Long, lastModified: Long) { entries[path] = Entry(size, lastModified) }

    fun remove(path: String) { entries.remove(path) }

    /** 0.9.4.1: a copy of the entries (for merging a completed ZIP's pending list, [ZipJob.onShared]). */
    fun entries(): Map<String, Entry> = LinkedHashMap(entries)

    fun serialize(): String = buildString {
        for ((p, e) in entries) append(p).append('\t').append(e.size).append('\t').append(e.lastModified).append('\n')
    }

    companion object {
        fun parse(text: String): ExportLedger {
            val m = LinkedHashMap<String, Entry>()
            for (line in text.lineSequence()) {
                val f = line.split('\t')
                if (f.size != 3 || f[0].isEmpty()) continue
                val size = f[1].toLongOrNull() ?: continue
                val mtime = f[2].toLongOrNull() ?: continue
                m[f[0]] = Entry(size, mtime)
            }
            return ExportLedger(m)
        }
    }
}

/** One file of the recording folder, as the export sees it. */
data class ExportSource(val relativePath: String, val size: Long, val lastModified: Long, val open: () -> InputStream) {
    val folder: String get() = relativePath.substringBeforeLast('/', "")
    val name: String get() = relativePath.substringAfterLast('/')
}

/** Where the files go: a folder tree (the SAF tree on the phone, a directory in tests). */
interface ExportSink {
    /** Display name of the destination for the final message. */
    val name: String
    /**
     * Opens `<folder>/<name>` for writing, creating [folder] (one level: `clips`, `continuous`, …)
     * if needed and replacing an existing file of that name.
     */
    fun openForWrite(folder: String, name: String): OutputStream

    /**
     * 0.9.4.1: removes `<folder>/<name>` after a copy was cancelled in the middle of the file, so no
     * half file stays in the destination. Best effort; the default does nothing.
     */
    fun discard(folder: String, name: String) {}
}

data class ExportProgress(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesTotal: Long, val current: String)

data class ExportResult(
    val filesCopied: Int,
    val bytesCopied: Long,
    /** Files that failed, with the exception's simple name; the export goes on after a failure. */
    val failures: List<Pair<String, String>>,
    val destination: String,
    /** 0.9.4.1: stopped by «Abbrechen»; the files copied before it are in the ledger, the one being copied is not. */
    val cancelled: Boolean = false,
)

/**
 * The incremental export. [plan] decides what to copy, [run] copies it in [CHUNK]-byte chunks
 * through [sink] and updates [ledger] after each file (the caller persists the ledger).
 */
object IncrementalExport {
    const val CHUNK = 1 shl 20
    const val MANIFEST = "manifest.jsonl"

    /**
     * Lists the recording folder of [dirs] as [ExportSource]s: the manifest and the files of
     * clips/, classifier/ and continuous/ (only the known extensions, as the ZIP does).
     * [openFile] (`continuous/<name>`) is the hour file still being written and is left out.
     */
    fun sources(dirs: LaborDirs, openFile: String?): List<ExportSource> {
        fun of(f: File, rel: String) = ExportSource(rel, f.length(), f.lastModified()) { f.inputStream() }
        fun list(dir: File, ext: String, prefix: String) =
            (dir.listFiles()?.filter { it.isFile && it.name.endsWith(ext) } ?: emptyList()).sortedBy { it.name }.map { of(it, "$prefix/${it.name}") }
        val out = ArrayList<ExportSource>()
        if (dirs.manifest.isFile) out += of(dirs.manifest, MANIFEST)
        out += list(dirs.clips, ".wav", "clips")
        out += list(dirs.classifier, ".jsonl", "classifier")
        out += list(dirs.continuous, ".m4a", "continuous").filter { it.relativePath != openFile }
        return out
    }

    /**
     * What [run] would copy: every source whose path/size/lastModified is not in the ledger, plus
     * the manifest always (it grows while the app runs and may be appended between two exports
     * without the size changing in the same second).
     */
    fun plan(sources: List<ExportSource>, ledger: ExportLedger): List<ExportSource> =
        sources.filter { it.relativePath == MANIFEST || !ledger.isCurrent(it.relativePath, it.size, it.lastModified) }

    /**
     * Copies [plan]'s files. Each file is recorded in [ledger] as soon as it is complete, so an
     * interrupted export (exception from the sink, device unplugged) resumes where it stopped;
     * [onFileDone] is called after each file for the caller to persist the ledger.
     * 0.9.4.1: [isCancelled] is checked before each file and after each [CHUNK]; a file cancelled in
     * the middle is not recorded and is removed from the destination ([ExportSink.discard]).
     */
    fun run(
        plan: List<ExportSource>,
        ledger: ExportLedger,
        sink: ExportSink,
        onProgress: (ExportProgress) -> Unit = {},
        onFileDone: () -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ExportResult {
        val total = plan.sumOf { it.size }
        var done = 0L
        var copied = 0
        var cancelled = false
        val failures = ArrayList<Pair<String, String>>()
        val buf = ByteArray(CHUNK)
        for ((i, s) in plan.withIndex()) {
            if (isCancelled()) { cancelled = true; break }
            onProgress(ExportProgress(i, plan.size, done, total, s.relativePath))
            val before = done
            try {
                s.open().use { input ->
                    sink.openForWrite(s.folder, s.name).use { out ->
                        while (true) {
                            if (isCancelled()) { cancelled = true; break }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(ExportProgress(i, plan.size, done, total, s.relativePath))
                        }
                    }
                }
                if (cancelled) {
                    ledger.remove(s.relativePath)
                    done = before
                    try { sink.discard(s.folder, s.name) } catch (e: Exception) { if (e is SecurityException) throw e }
                    break
                }
                ledger.record(s.relativePath, s.size, s.lastModified)
                copied++
                onFileDone()
            } catch (e: Exception) {
                ledger.remove(s.relativePath)
                failures += s.relativePath to (e.javaClass.simpleName)
                if (e is SecurityException) throw e // the folder permission is gone: the caller asks for the folder again
            }
        }
        onProgress(ExportProgress(copied + failures.size, plan.size, done, total, ""))
        return ExportResult(copied, done, failures, sink.name, cancelled)
    }
}
