package ch.lessnoise.app.labor

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.9.4.1: the export job of the foreground service — progress model and line, STORED for audio and
 * DEFLATE for text, 1 MB chunks, temp file and its cleanup on cancel/failure, ledger only after a
 * completed ZIP that was shared, the free-space check, one job at a time. The service itself
 * (notification, wake lock) needs a device and is not tested here.
 */
class ExportJobTest {
    private lateinit var root: File

    @BeforeTest fun setUp() { root = Files.createTempDirectory("labor-job").toFile(); LaborExportJobs.resetForTest() }
    @AfterTest fun tearDown() { root.deleteRecursively(); LaborExportJobs.resetForTest() }

    private fun file(rel: String, bytes: ByteArray, mtime: Long = 1_700_000_000_000): ExportSource {
        val f = File(root, "src/$rel").apply { parentFile!!.mkdirs(); writeBytes(bytes); setLastModified(mtime) }
        return ExportSource(rel, f.length(), f.lastModified()) { f.inputStream() }
    }

    private fun leftovers(dir: File) = dir.listFiles()!!.map { it.name }.sorted()

    // ---- progress ----

    @Test
    fun progressLineAndFractionByBytes() {
        val p = ExportProgress(412, 1830, 165_400_000, 820_700_000, "clips/ev_1.wav")
        assertEquals("Export · 412 von 1830 Dateien · 165 von 820 MB", ExportProgressText.line(p))
        assertEquals(165.4f / 820.7f, ExportProgressText.fraction(p), 1e-6f)
        assertEquals("Export · 3 von 50 Dateien · 1,2 von 2,8 GB", ExportProgressText.line(ExportProgress(3, 50, 1_234_000_000, 2_800_000_000, "")))
        assertEquals("Export · 0 von 1 Datei · 0,4 von 4,2 MB", ExportProgressText.line(ExportProgress(0, 1, 400_000, 4_200_000, "")))
        // No bytes (only empty files): by files; nothing at all: 0; never above 1.
        assertEquals(0.5f, ExportProgressText.fraction(ExportProgress(1, 2, 0, 0, "")))
        assertEquals(0f, ExportProgressText.fraction(ExportProgress(0, 0, 0, 0, "")))
        assertEquals(1f, ExportProgressText.fraction(ExportProgress(2, 2, 11, 10, "")))
    }

    @Test
    fun notificationUpdatesAreThrottled() {
        val t = ProgressThrottle(1_000)
        assertTrue(t.due(5_000))
        assertFalse(t.due(5_400))
        assertFalse(t.due(5_999))
        assertTrue(t.due(6_000))
    }

    // ---- ZIP ----

    @Test
    fun audioIsStoredTextIsDeflated() {
        assertEquals(ZipEntry.STORED, ZipMethods.forName("clips/ev_1_20261026_010203.wav"))
        assertEquals(ZipEntry.STORED, ZipMethods.forName("continuous/20261026_01.m4a"))
        assertEquals(ZipEntry.STORED, ZipMethods.forName("x/Y.WAV"))
        assertEquals(ZipEntry.DEFLATED, ZipMethods.forName("classifier/20261026_01.jsonl"))
        assertEquals(ZipEntry.DEFLATED, ZipMethods.forName("manifest.jsonl"))
        assertEquals(ZipEntry.DEFLATED, ZipMethods.forName("clips/README"))

        val wav = Random(1).nextBytes(2 * IncrementalExport.CHUNK + 17)
        val text = "{\"type\":\"clip\",\"session\":\"s\"}\n".repeat(20_000).toByteArray()
        val sources = listOf(file("manifest.jsonl", text), file("clips/ev_1_20261026_010203.wav", wav), file("continuous/20261026_01.m4a", wav.copyOf(1000)))
        val zip = File(root, "out/a.zip")
        assertTrue(ExportScopes.writeZip(zip, sources))
        ZipFile(zip).use { z ->
            val e = z.entries().toList()
            assertEquals(listOf("manifest.jsonl", "clips/ev_1_20261026_010203.wav", "continuous/20261026_01.m4a"), e.map { it.name })
            assertEquals(listOf(ZipEntry.DEFLATED, ZipEntry.STORED, ZipEntry.STORED), e.map { it.method })
            assertTrue(e[0].compressedSize < text.size / 10, "jsonl deflated: ${e[0].compressedSize}")
            assertEquals(wav.size.toLong(), e[1].compressedSize)
            assertEquals(CRC32().apply { update(wav) }.value, e[1].crc)
            assertTrue(wav.contentEquals(z.getInputStream(e[1]).readBytes()))
            assertTrue(text.contentEquals(z.getInputStream(e[0]).readBytes()))
            assertEquals(sources[1].lastModified / 2000, e[1].time / 2000) // DOS time: 2 s steps
        }
        assertEquals(listOf("a.zip"), leftovers(zip.parentFile!!)) // no temp file left
    }

    @Test
    fun readsInChunksOfAtMostOneMegabyteAndReportsProgress() {
        val bytes = Random(2).nextBytes(3 * IncrementalExport.CHUNK + 5)
        var maxRead = 0
        val f = File(root, "big.m4a").apply { writeBytes(bytes) }
        val src = ExportSource("continuous/20261026_02.m4a", f.length(), 0) {
            object : FilterInputStream(f.inputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int { maxRead = maxOf(maxRead, len); return super.read(b, off, len) }
            }
        }
        val seen = ArrayList<ExportProgress>()
        assertTrue(ExportScopes.writeZip(File(root, "b.zip"), listOf(src), onProgress = { seen += it }))
        assertTrue(maxRead <= IncrementalExport.CHUNK, "max read $maxRead")
        assertEquals(bytes.size.toLong(), seen.last().bytesDone)
        assertEquals(ExportProgress(1, 1, bytes.size.toLong(), bytes.size.toLong(), ""), seen.last())
        // Monotonic, and a step after every chunk (4 chunks for 3 MB + 5 bytes).
        assertEquals(seen.map { it.bytesDone }.sorted(), seen.map { it.bytesDone })
        assertTrue(seen.map { it.bytesDone }.distinct().size >= 5)
    }

    @Test
    fun cancelInTheMiddleLeavesNoZipNoTempFileAndNoPendingList() {
        val sources = listOf(file("clips/a.wav", ByteArray(10)), file("continuous/20261026_03.m4a", ByteArray(3 * IncrementalExport.CHUNK)))
        val out = File(root, "share").apply { mkdirs() }
        val zip = File(out, "lessnoise-labor-20261026-alles.zip")
        val pending = File(root, "pending/${zip.name}.tsv")
        var seen = 0
        val ok = ZipJob.run(zip, sources, pending, isCancelled = { seen >= 2 }, onProgress = { if (it.bytesDone > 10) seen++ })
        assertFalse(ok)
        assertEquals(emptyList(), leftovers(out))
        assertFalse(pending.exists())
        val ledger = ExportLedger()
        assertFalse(ZipJob.onShared(ledger, pending))
        assertEquals(0, ledger.size)
    }

    @Test
    fun aFailingSourceLeavesNoZipNoTempFileAndNoPendingList() {
        val out = File(root, "share").apply { mkdirs() }
        val zip = File(out, "x.zip")
        val pending = File(root, "pending/x.zip.tsv")
        val broken = ExportSource("clips/b.wav", 5_000_000, 0) {
            object : InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 100) 0 else throw IOException("card removed")
                override fun read(b: ByteArray, off: Int, len: Int): Int { if (n++ > 2) throw IOException("card removed"); return minOf(len, 1000) }
            }
        }
        assertFailsWith<IOException> { ZipJob.run(zip, listOf(file("manifest.jsonl", "{}\n".toByteArray()), broken), pending) }
        assertEquals(emptyList(), leftovers(out))
        assertFalse(pending.exists())
    }

    @Test
    fun aLeftoverTempFileIsReplacedAndRecognised() {
        val out = File(root, "share").apply { mkdirs() }
        val zip = File(out, "y.zip")
        val tmp = ZipMethods.tempFor(zip).apply { writeText("half of an old ZIP") }
        assertTrue(ZipMethods.isTemp(tmp))
        assertFalse(ZipMethods.isTemp(zip))
        assertTrue(ExportScopes.writeZip(zip, listOf(file("manifest.jsonl", "{}\n".toByteArray()))))
        assertEquals(listOf("y.zip"), leftovers(out))
    }

    @Test
    fun ledgerOnlyAfterACompletedZipThatWasShared() {
        val a = file("clips/a.wav", ByteArray(100), 1_000_000)
        val b = file("classifier/20261026_01.jsonl", "{}\n".toByteArray(), 2_000_000)
        val zip = File(root, "share/z.zip")
        val pending = File(root, "pending/z.zip.tsv")
        val ledger = ExportLedger(mapOf("manifest.jsonl" to ExportLedger.Entry(1, 1)))
        val before = ledger.serialize()
        assertTrue(ZipJob.run(zip, listOf(a, b), pending))
        // Complete, not shared yet: the ledger is untouched, the list waits.
        assertEquals(before, ledger.serialize())
        assertTrue(pending.isFile)
        // Shared: recorded once.
        assertTrue(ZipJob.onShared(ledger, pending))
        assertTrue(ledger.isCurrent(a.relativePath, a.size, a.lastModified))
        assertTrue(ledger.isCurrent(b.relativePath, b.size, b.lastModified))
        assertEquals(3, ledger.size)
        assertFalse(pending.exists())
        assertFalse(ZipJob.onShared(ledger, pending))
        // A new ZIP of the same name starts without a list until it is complete.
        File(pending.path).writeText("clips/stale.wav\t1\t1\n")
        var cancel = false
        assertFalse(ZipJob.run(zip, listOf(a), pending, isCancelled = { cancel.also { cancel = true } }))
        assertFalse(pending.exists())
    }

    // ---- free space ----

    @Test
    fun freeSpaceCheck() {
        val e = ExportEstimate(1830, 2_800_000_000)
        val need = ZipSpace.needed(e)
        assertEquals(2_800_000_000 + 1830 * ZipSpace.PER_ENTRY + ZipSpace.RESERVE, need)
        assertNull(ZipSpace.refusal(need, need))
        assertEquals(
            "Zu wenig Speicher für ZIP: 2821 MB nötig, 1500 MB frei – Ordner-Export nutzen",
            ZipSpace.refusal(need, 1_500_999_999),
        )
        assertEquals("Zu wenig Speicher für ZIP: 21 MB nötig, 0 MB frei – Ordner-Export nutzen", ZipSpace.refusal(ZipSpace.needed(ExportEstimate(1, 10)), -5))
    }

    // ---- one job at a time ----

    @Test
    fun aSecondExportIsRefusedWhileOneRuns() {
        val zip = ExportRequest.Zip(emptyList(), "a.zip", "Alles")
        val folder = ExportRequest.Folder("content://tree", ExportScope.ALL, true)
        assertTrue(LaborExportJobs.begin(zip))
        assertEquals(ExportJobState.Running(ExportKind.ZIP, null), LaborExportJobs.state.value)
        assertFalse(LaborExportJobs.begin(folder))
        assertFalse(LaborExportJobs.begin(zip))
        assertEquals(zip, LaborExportJobs.take())
        assertNull(LaborExportJobs.take())
        LaborExportJobs.progress(ExportKind.ZIP, ExportProgress(1, 2, 3, 4, "x"))
        assertEquals(ExportJobState.Running(ExportKind.ZIP, ExportProgress(1, 2, 3, 4, "x")), LaborExportJobs.state.value)
        LaborExportJobs.requestCancel()
        assertTrue(LaborExportJobs.cancelRequested)
        LaborExportJobs.finish(ExportJobState.Cancelled(ExportKind.ZIP, "abgebrochen"))
        assertFalse(LaborExportJobs.cancelRequested)
        // Progress after the end does not bring it back; a cancel without a job is ignored.
        LaborExportJobs.progress(ExportKind.ZIP, ExportProgress(2, 2, 4, 4, ""))
        assertEquals(ExportJobState.Cancelled(ExportKind.ZIP, "abgebrochen"), LaborExportJobs.state.value)
        LaborExportJobs.requestCancel()
        assertFalse(LaborExportJobs.cancelRequested)
        assertTrue(LaborExportJobs.begin(folder))
    }

    @Test
    fun theShareSheetOpensByItselfOnlyOnceAndOnlyRightAfterTheEnd() {
        val done = ExportJobState.Done(ExportKind.ZIP, "fertig", File(root, "z.zip"), 10_000)
        assertFalse(LaborExportJobs.consumeAutoShare(done, 20_000)) // came back to the app later: button or notification
        assertTrue(LaborExportJobs.consumeAutoShare(done, 11_000))
        assertFalse(LaborExportJobs.consumeAutoShare(done, 11_500))
        assertFalse(LaborExportJobs.consumeAutoShare(ExportJobState.Done(ExportKind.FOLDER, "fertig", null, 30_000), 30_100))
    }

    // ---- benchmark (WORKLOG 0.9.4.1) ----

    /**
     * A synthetic 200 MB Labor folder: 400 clips à 0,4 MB of quiet 16-bit PCM (sine + noise, as a
     * street at night), 2 hour files à 20 MB of random bytes (AAC is already compressed), 40 classifier
     * logs à 0,5 MB of JSON lines; ZIP with every entry DEFLATED (0.9.4) vs the 0.9.4.1 choice.
     * Runs only with LABOR_ZIP_BENCH=1 (about a minute); prints the times.
     */
    @Test
    fun zipBenchmark() {
        if (System.getenv("LABOR_ZIP_BENCH") != "1") return
        val rnd = Random(42)
        val sources = ArrayList<ExportSource>()
        repeat(400) { i ->
            val pcm = ByteArray(400_000)
            for (k in 0 until pcm.size / 2) {
                val v = (300 * kotlin.math.sin(k * 2 * Math.PI * 120 / 16_000) + rnd.nextInt(-200, 200)).toInt()
                pcm[2 * k] = v.toByte(); pcm[2 * k + 1] = (v shr 8).toByte()
            }
            sources += file("clips/ev_${i}_20261026_01%04d.wav".format(i), pcm)
        }
        repeat(2) { i -> sources += file("continuous/20261026_0$i.m4a", rnd.nextBytes(20_000_000)) }
        repeat(40) { i ->
            val sb = StringBuilder()
            var t = 0
            while (sb.length < 500_000) sb.append("{\"type\":\"classifier\",\"session\":\"s1\",\"time\":\"2026-10-26T01:00:${t % 60}.000+01:00\",\"top\":[[\"Vehicle\",0.${rnd.nextInt(100, 999)}],[\"Speech\",0.0${t++ % 9}]]}\n")
            sources += file("classifier/20261026_%02d.jsonl".format(i), sb.toString().toByteArray())
        }
        val total = sources.sumOf { it.size }
        fun time(name: String, method: (String) -> Int): Pair<Long, Long> {
            val zip = File(root, name)
            ExportScopes.writeZip(zip, sources, method = method) // warm-up (JIT, page cache)
            val t0 = System.nanoTime()
            ExportScopes.writeZip(zip, sources, method = method)
            return (System.nanoTime() - t0) / 1_000_000 to zip.length()
        }
        val (deflateMs, deflateBytes) = time("deflate.zip") { ZipEntry.DEFLATED }
        val (mixedMs, mixedBytes) = time("mixed.zip", ZipMethods::forName)
        println("ZIP benchmark: ${sources.size} files, $total bytes; all DEFLATE: $deflateMs ms, $deflateBytes bytes; " +
            "STORED audio + DEFLATE jsonl: $mixedMs ms, $mixedBytes bytes")
    }
}
