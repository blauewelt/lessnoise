package ch.lessnoise.app.labor

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// LABOR BUILD ONLY (app/src/labor/). 0.9.4: what «In Ordner exportieren» and «Als ZIP teilen» take —
// «Alles», «Seit letztem Export» or «Letzte 24 h», with or without the hour files — and what
// «Exportierte Aufnahmen löschen» may delete. Pure Kotlin, unit-tested in app/src/testLabor/
// (ExportScopeTest); the buttons are in edition/Edition.kt.

/** The «Export» row. [key] is what the Labor SharedPreferences keep, [zipSuffix] ends the ZIP's name. */
enum class ExportScope(val key: String, val label: String, val zipSuffix: String) {
    ALL("alles", "Alles", "alles"),
    SINCE_LAST("seit_export", "Seit letztem Export", "seit-export"),
    LAST_24H("24h", "Letzte 24 h", "24h");

    companion object {
        val DEFAULT = LAST_24H
        fun of(key: String?): ExportScope = values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Which button: the folder export is always incremental (ledger), a ZIP is a self-contained hand-over. */
enum class ExportTarget { FOLDER, ZIP }

data class ExportEstimate(val files: Int, val bytes: Long) {
    companion object {
        fun of(sources: List<ExportSource>) = ExportEstimate(sources.size, sources.sumOf { it.size })
    }
}

object ExportScopes {
    const val WINDOW_MS = 24 * 3_600_000L
    private const val HOUR_MS = 3_600_000L
    const val NEVER_EXPORTED = "Noch nie exportiert – nimmt alles"

    private val CLIP_NAME = Regex("""^ev_(?:\d+|na)_(\d{8}_\d{6})(?:_\d+)?\.wav$""")
    private val HOUR_NAME = Regex("""^(\d{8}_\d{2})(?:_\d+)?\.(?:jsonl|m4a)$""")
    private val CLIP_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val HOUR_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HH")

    /**
     * The time span (epoch ms, both ends inclusive) a file stands for: a clip
     * `ev_<id>_<yyyyMMdd_HHmmss>.wav` the event start; a classifier log `<yyyyMMdd_HH>.jsonl` or an
     * hour file `<yyyyMMdd_HH>[_n].m4a` its whole local hour; any other name (or a name that does not
     * parse) its [lastModified].
     */
    fun timeSpan(relativePath: String, lastModified: Long, zone: ZoneId): LongRange {
        val name = relativePath.substringAfterLast('/')
        CLIP_NAME.find(name)?.let { m ->
            parse(m.groupValues[1], CLIP_STAMP, zone)?.let { return it..it }
        }
        HOUR_NAME.find(name)?.let { m ->
            parseHour(m.groupValues[1], zone)?.let { return it until it + HOUR_MS }
        }
        return lastModified..lastModified
    }

    private fun parse(stamp: String, f: DateTimeFormatter, zone: ZoneId): Long? =
        try { LocalDateTime.parse(stamp, f).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) { null }

    private fun parseHour(stamp: String, zone: ZoneId): Long? =
        try {
            java.time.LocalDate.parse(stamp.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
                .atTime(stamp.substring(9, 11).toInt(), 0).atZone(zone).toInstant().toEpochMilli()
        } catch (_: Exception) { null }

    /** Whether [s] belongs to the 24 hours before [nowMs] (its span overlaps the window). */
    fun inLast24h(s: ExportSource, nowMs: Long, zone: ZoneId): Boolean {
        val span = timeSpan(s.relativePath, s.lastModified, zone)
        return span.first <= nowMs && span.last >= nowMs - WINDOW_MS
    }

    /** «Seit letztem Export» with an empty ledger takes everything. */
    fun neverExported(ledger: ExportLedger): Boolean = ledger.size == 0

    fun effective(scope: ExportScope, ledger: ExportLedger): ExportScope =
        if (scope == ExportScope.SINCE_LAST && neverExported(ledger)) ExportScope.ALL else scope

    private fun isHourFile(s: ExportSource) = s.relativePath.startsWith("continuous/")

    /**
     * The files [target] takes from [sources] ([IncrementalExport.sources]: manifest first, the open
     * hour file already left out). The manifest is always in it (for «Letzte 24 h» the caller swaps in
     * [ManifestWindow]'s filtered copy). [withoutHourFiles] drops `continuous/`.
     *
     * - the folder: what is new or changed against [ledger] (as before 0.9.4), within the scope;
     * - a ZIP: «Alles» everything, «Seit letztem Export» what is not in the ledger, «Letzte 24 h»
     *   everything of the window (already exported or not: the ZIP is the hand-over).
     */
    fun select(
        sources: List<ExportSource>, target: ExportTarget, scope: ExportScope, withoutHourFiles: Boolean,
        ledger: ExportLedger, nowMs: Long, zone: ZoneId,
    ): List<ExportSource> {
        val eff = effective(scope, ledger)
        val incremental = target == ExportTarget.FOLDER || eff == ExportScope.SINCE_LAST
        return sources.filter { s ->
            if (s.relativePath == IncrementalExport.MANIFEST) return@filter true
            if (withoutHourFiles && isHourFile(s)) return@filter false
            if (eff == ExportScope.LAST_24H && !inLast24h(s, nowMs, zone)) return@filter false
            !incremental || !ledger.isCurrent(s.relativePath, s.size, s.lastModified)
        }
    }

    /**
     * «Als ZIP teilen» with the hour files: they go in only if they total less than [limit]
     * ([LaborDirs.ZIP_CONTINUOUS_LIMIT], as before 0.9.4). Returns (files for the ZIP, hour files left out).
     */
    fun fitZip(selected: List<ExportSource>, limit: Long = LaborDirs.ZIP_CONTINUOUS_LIMIT): Pair<List<ExportSource>, List<ExportSource>> {
        val hours = selected.filter(::isHourFile)
        return if (hours.sumOf { it.size } < limit) selected to emptyList() else selected.filterNot(::isHourFile) to hours
    }

    /**
     * «Exportierte Aufnahmen löschen»: the files of [sources] that the ledger has with the same size
     * and lastModified, i.e. already copied to a folder or packed into a ZIP and not changed since.
     * Never the manifest; what is not in the ledger (or changed) stays.
     */
    fun exportedForDeletion(sources: List<ExportSource>, ledger: ExportLedger): List<ExportSource> =
        sources.filter { it.relativePath != IncrementalExport.MANIFEST && ledger.isCurrent(it.relativePath, it.size, it.lastModified) }

    /**
     * Deletes [selected] ([exportedForDeletion]) under [root], each only if it still has the size and
     * lastModified the ledger has (a file that changed since is kept), and removes the deleted ones
     * from [ledger] (the caller saves it). Returns what was deleted.
     */
    fun deleteExported(root: File, selected: List<ExportSource>, ledger: ExportLedger): ExportEstimate {
        var n = 0
        var bytes = 0L
        for (s in selected) {
            if (s.relativePath == IncrementalExport.MANIFEST) continue
            val f = File(root, s.relativePath)
            if (!f.isFile || !ledger.isCurrent(s.relativePath, f.length(), f.lastModified())) continue
            val size = f.length()
            if (f.delete()) { ledger.remove(s.relativePath); n++; bytes += size }
        }
        return ExportEstimate(n, bytes)
    }

    /** «Letzte 24 h ohne Stundendateien: 412 Dateien, 165 MB». */
    fun estimateLine(scope: ExportScope, withoutHourFiles: Boolean, e: ExportEstimate, neverExported: Boolean): String = buildString {
        append(scope.label)
        append(if (withoutHourFiles) " ohne Stundendateien" else " mit Stundendateien")
        append(": ")
        if (scope == ExportScope.SINCE_LAST && neverExported) append(NEVER_EXPORTED).append(", ")
        append(files(e.files)).append(", ").append(size(e.bytes))
    }

    /** Number and unit joined by a no-break space (U+00A0), so a line never ends between them. */
    private const val NB = '\u00A0'

    fun files(n: Int): String = if (n == 1) "1${NB}Datei" else "$n${NB}Dateien"

    /** 3,37 GB · 165 MB · 4,2 MB · 0,1 MB */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.GERMANY, "%.2f${NB}GB", bytes / 1e9)
        bytes >= 10_000_000L -> String.format(Locale.GERMANY, "%.0f${NB}MB", bytes / 1e6)
        else -> String.format(Locale.GERMANY, "%.1f${NB}MB", bytes / 1e6)
    }

    /** `lessnoise-labor-20261026-24h.zip` */
    fun zipName(scope: ExportScope, nowMs: Long, zone: ZoneId): String =
        "lessnoise-labor-" + java.time.Instant.ofEpochMilli(nowMs).atZone(zone).format(DateTimeFormatter.BASIC_ISO_DATE).take(8) +
            "-" + scope.zipSuffix + ".zip"

    /**
     * Writes [sources] into [zip] (entries keep their folder: clips/…, classifier/…, continuous/…).
     *
     * 0.9.4.1 (the owner's 2,8 GB ZIP that never finished):
     * - entries are STORED (no compression) or DEFLATED as [method] says ([ZipMethods.forName]: the
     *   audio stored, the text logs deflated). A STORED entry needs its CRC and size before its data,
     *   so its file is read twice (one CRC pass, one copy pass), in [IncrementalExport.CHUNK] chunks;
     *   no file is ever held in memory;
     * - written to a temp file `.<name>.part` beside [zip] and renamed to [zip] only when complete;
     *   cancelled ([isCancelled], checked before each file and after each chunk) or failed (exception,
     *   rethrown), the temp file is deleted and [zip] does not exist;
     * - [onProgress] after each chunk written and each file finished (bytes = the sources' bytes).
     *
     * Returns true if [zip] is complete, false if cancelled.
     */
    fun writeZip(
        zip: File,
        sources: List<ExportSource>,
        isCancelled: () -> Boolean = { false },
        onProgress: (ExportProgress) -> Unit = {},
        method: (String) -> Int = ZipMethods::forName,
    ): Boolean {
        val dir = zip.absoluteFile.parentFile
        dir.mkdirs()
        val tmp = ZipMethods.tempFor(zip)
        val total = sources.sumOf { it.size }
        val n = sources.size
        var done = 0L
        val buf = ByteArray(IncrementalExport.CHUNK)
        var complete = false
        tmp.delete()
        if (zip.exists()) zip.delete()
        // Not `use`: closing the ZipOutputStream finishes the current entry (and throws for a STORED one
        // cut short); on cancel or failure only the file is closed and deleted.
        val file = FileOutputStream(tmp)
        try {
            val z = ZipOutputStream(BufferedOutputStream(file, IncrementalExport.CHUNK))
            for ((i, s) in sources.withIndex()) {
                if (isCancelled()) return false
                onProgress(ExportProgress(i, n, done, total, s.relativePath))
                val entry = ZipEntry(s.relativePath).apply { time = s.lastModified }
                val stored = method(s.relativePath) == ZipEntry.STORED
                var storedSize = 0L
                if (stored) {
                    val crc = CRC32()
                    s.open().use { input ->
                        while (true) {
                            if (isCancelled()) return false
                            val r = input.read(buf)
                            if (r < 0) break
                            crc.update(buf, 0, r)
                            storedSize += r
                        }
                    }
                    entry.method = ZipEntry.STORED
                    entry.size = storedSize
                    entry.compressedSize = storedSize
                    entry.crc = crc.value
                } else {
                    entry.method = ZipEntry.DEFLATED
                }
                z.putNextEntry(entry)
                s.open().use { input ->
                    // STORED: exactly the bytes the CRC pass saw (a clip or a closed hour file does not change).
                    var left = if (stored) storedSize else Long.MAX_VALUE
                    while (left > 0) {
                        if (isCancelled()) return false
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) break
                        z.write(buf, 0, r)
                        done += r
                        left -= r
                        onProgress(ExportProgress(i, n, done, total, s.relativePath))
                    }
                }
                z.closeEntry()
                onProgress(ExportProgress(i + 1, n, done, total, s.relativePath))
            }
            if (isCancelled()) return false
            z.close() // writes the central directory, closes the file
            if (!tmp.renameTo(zip)) throw IOException("ZIP nicht umbenannt")
            complete = true
            onProgress(ExportProgress(n, n, done, total, ""))
            return true
        } finally {
            if (!complete) {
                try { file.close() } catch (_: IOException) {}
                tmp.delete()
            }
        }
    }

    /** «Letzte 24 h»: [selected] with the manifest replaced by its filtered copy in [tmpDir] (0.9.4; moved here in 0.9.4.1). */
    fun withScopedManifest(scope: ExportScope, selected: List<ExportSource>, manifest: File, tmpDir: File, nowMs: Long): List<ExportSource> {
        if (scope != ExportScope.LAST_24H) return selected
        return selected.map { s ->
            if (s.relativePath != IncrementalExport.MANIFEST) s
            else {
                val f = File(tmpDir, IncrementalExport.MANIFEST)
                ManifestWindow.write(manifest, f, nowMs - WINDOW_MS, nowMs)
                replaced(s, f)
            }
        }
    }

    /** [source] with its content replaced by [file] (the filtered manifest), same path and lastModified. */
    fun replaced(source: ExportSource, file: File): ExportSource =
        ExportSource(source.relativePath, file.length(), source.lastModified) { file.inputStream() }
}

/**
 * «Letzte 24 h»: the manifest lines of the window, for a filtered `manifest.jsonl` in the export (the
 * one on the phone stays whole). Kept, in file order:
 *
 * - every line whose `time` lies in [fromMs, toMs];
 * - for each session that has such a line, the lines before the window that the window's lines
 *   depend on: its `session_start` (device, calibration offset, detector and recording settings —
 *   the replay tools read `calibrationOffsetDb` from it), its last `clock` line (the sample clock in
 *   force at the window start), the `continuous_open` of an hour file still open at the window start
 *   (its sample mapping), and its last `storage_full` / `logs_full` (why audio or lines are missing);
 * - a valid JSON object without `time` (none is written today; kept rather than lost).
 *
 * Dropped: everything else before the window, lines after [toMs], and lines that are not JSON
 * objects (a line cut off by a full phone). Keeping whole sessions instead would keep a measurement
 * that runs for days in full; this keeps the window plus a few lines per session.
 */
object ManifestWindow {
    // The writer (ManifestLines.base) always starts a line with type, session, time; the fallback parses.
    private val HEAD = Regex("""^\{"type":"([^"\\]*)","session":"([^"\\]*)","time":"([^"\\]*)"""")
    private val FILE = Regex(""""file":"([^"\\]*)"""")

    private class Context {
        val lines = java.util.TreeMap<Long, String>()
        val starts = ArrayList<Long>()
        var clock: Long? = null
        val open = HashMap<String, Long>()
        var storageFull: Long? = null
        var logsFull: Long? = null
        var flushed = false

        fun replace(old: Long?, index: Long, line: String): Long { old?.let { lines.remove(it) }; lines[index] = line; return index }
    }

    private data class Head(val type: String?, val session: String?, val timeMs: Long?, val valid: Boolean)

    private fun head(line: String): Head {
        HEAD.find(line)?.let { m ->
            return Head(m.groupValues[1], m.groupValues[2], isoMs(m.groupValues[3]) ?: Long.MIN_VALUE, true)
        }
        val o = MiniJson.obj(line) ?: return Head(null, null, null, false)
        val t = o["time"] as? String
        return Head(o["type"] as? String, o["session"] as? String, t?.let(::isoMs) ?: if (t != null) Long.MIN_VALUE else null, true)
    }

    private fun isoMs(s: String): Long? = try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { null }

    /** Streams [lines] and hands the kept ones to [out] in file order. */
    fun filter(lines: Sequence<String>, fromMs: Long, toMs: Long, out: (String) -> Unit) {
        val sessions = HashMap<String, Context>()
        var index = 0L
        for (line in lines) {
            val i = index++
            if (line.isBlank()) continue
            val h = head(line)
            if (!h.valid) continue
            val t = h.timeMs
            if (t == null) { out(line); continue } // a JSON object without `time`
            if (t == Long.MIN_VALUE) continue // a `time` that does not parse
            val session = h.session ?: ""
            if (t in fromMs..toMs) {
                val c = sessions[session]
                if (c != null && !c.flushed) { c.lines.values.forEach(out); c.lines.clear(); c.flushed = true }
                else if (c == null) sessions[session] = Context().apply { flushed = true }
                out(line)
                continue
            }
            if (t > toMs) continue
            val c = sessions.getOrPut(session) { Context() }
            if (c.flushed) continue // cannot happen for an ordered file; a late line before the window is dropped
            when (h.type) {
                "session_start" -> { c.lines[i] = line; c.starts += i }
                "clock" -> c.clock = c.replace(c.clock, i, line)
                "storage_full" -> c.storageFull = c.replace(c.storageFull, i, line)
                "logs_full" -> c.logsFull = c.replace(c.logsFull, i, line)
                "continuous_open" -> FILE.find(line)?.groupValues?.get(1)?.let { f -> c.open[f] = c.replace(c.open[f], i, line) }
                "continuous_close" -> FILE.find(line)?.groupValues?.get(1)?.let { f -> c.open.remove(f)?.let { c.lines.remove(it) } }
            }
        }
    }

    fun filter(lines: List<String>, fromMs: Long, toMs: Long): List<String> {
        val out = ArrayList<String>()
        filter(lines.asSequence(), fromMs, toMs) { out += it }
        return out
    }

    /** Writes the filtered copy of [manifest] to [dst]; returns its size in bytes. */
    fun write(manifest: File, dst: File, fromMs: Long, toMs: Long): Long {
        dst.parentFile?.mkdirs()
        dst.bufferedWriter(Charsets.UTF_8).use { w ->
            if (manifest.isFile) manifest.bufferedReader(Charsets.UTF_8).useLines { seq ->
                filter(seq, fromMs, toMs) { w.write(it); w.write("\n") }
            }
        }
        return dst.length()
    }

    /** The size [write] would produce, without writing. */
    fun bytes(manifest: File, fromMs: Long, toMs: Long): Long {
        if (!manifest.isFile) return 0
        var n = 0L
        manifest.bufferedReader(Charsets.UTF_8).useLines { seq ->
            filter(seq, fromMs, toMs) { n += it.toByteArray(Charsets.UTF_8).size + 1 }
        }
        return n
    }
}
