package ch.lessnoise.app.labor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «In Ordner exportieren»: the incremental bookkeeping, with a fake sink instead of SAF. */
class IncrementalExportTest {
    /** Keeps everything in memory as `folder/name` → bytes; counts opens. */
    private class FakeSink(override val name: String = "Downloads") : ExportSink {
        val files = LinkedHashMap<String, ByteArray>()
        val opened = ArrayList<String>()
        var failOn: String? = null
        var throwSecurityOn: String? = null
        override fun openForWrite(folder: String, name: String): OutputStream {
            val key = if (folder.isEmpty()) name else "$folder/$name"
            opened += key
            if (key == throwSecurityOn) throw SecurityException("grant revoked")
            if (key == failOn) throw IOException("disk full")
            return object : ByteArrayOutputStream() {
                override fun close() { files[key] = toByteArray() }
            }
        }
    }

    private fun tempDirs(): LaborDirs = LaborDirs(Files.createTempDirectory("labor-export").toFile()).also { it.ensure() }

    private fun write(f: File, bytes: Int, mtime: Long): File {
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        f.setLastModified(mtime)
        return f
    }

    @Test
    fun ledgerRoundTripAndBadLines() {
        val l = ExportLedger()
        l.record("clips/a.wav", 10, 1_000)
        l.record("continuous/b.m4a", 30_000_000, 2_000)
        val text = l.serialize()
        val back = ExportLedger.parse(text + "garbage line\nx\tnotanumber\t3\n")
        assertEquals(2, back.size)
        assertTrue(back.isCurrent("clips/a.wav", 10, 1_000))
        assertTrue(!back.isCurrent("clips/a.wav", 11, 1_000))
        assertTrue(!back.isCurrent("clips/a.wav", 10, 1_001))
        assertNull(back["x"])
    }

    @Test
    fun sourcesKeepFoldersAndSkipTheOpenHourFile() {
        val d = tempDirs()
        write(d.manifest, 100, 1_000)
        write(File(d.clips, "ev_1_20261023_010203.wav"), 200, 1_000)
        write(File(d.clips, "notes.txt"), 5, 1_000) // unknown extension: not exported (as the ZIP)
        write(File(d.classifier, "20261023_01.jsonl"), 50, 1_000)
        write(File(d.continuous, "20261023_01.m4a"), 1_000, 1_000)
        write(File(d.continuous, "20261023_02.m4a"), 1_000, 1_000)
        val s = IncrementalExport.sources(d, openFile = "continuous/20261023_02.m4a")
        assertEquals(
            listOf("manifest.jsonl", "clips/ev_1_20261023_010203.wav", "classifier/20261023_01.jsonl", "continuous/20261023_01.m4a"),
            s.map { it.relativePath },
        )
        assertEquals("clips", s[1].folder)
        assertEquals("ev_1_20261023_010203.wav", s[1].name)
        assertEquals("", s[0].folder)
    }

    @Test
    fun secondRunCopiesOnlyNewOrChangedFilesAndAlwaysTheManifest() {
        val d = tempDirs()
        write(d.manifest, 100, 1_000)
        write(File(d.clips, "a.wav"), 200, 1_000)
        write(File(d.continuous, "h1.m4a"), 3_000_000, 1_000) // > 1 MB: several chunks
        val ledger = ExportLedger()
        val sink = FakeSink()
        var progress = 0
        val r1 = IncrementalExport.run(IncrementalExport.plan(IncrementalExport.sources(d, null), ledger), ledger, sink, onProgress = { progress++ })
        assertEquals(3, r1.filesCopied)
        assertEquals(3_000_300, r1.bytesCopied)
        assertTrue(r1.failures.isEmpty())
        assertEquals("Downloads", r1.destination)
        assertTrue(progress >= 3 + 3) // at least one call per file plus one per chunk
        assertEquals(3_000_000, sink.files["continuous/h1.m4a"]!!.size)
        assertEquals(sink.files["clips/a.wav"]!!.toList(), File(d.clips, "a.wav").readBytes().toList())

        // Nothing changed: only the manifest again.
        val plan2 = IncrementalExport.plan(IncrementalExport.sources(d, null), ledger)
        assertEquals(listOf("manifest.jsonl"), plan2.map { it.relativePath })

        // A new clip, the manifest grew, the hour file is unchanged.
        write(d.manifest, 150, 2_000)
        write(File(d.clips, "b.wav"), 300, 2_000)
        sink.opened.clear()
        val r3 = IncrementalExport.run(IncrementalExport.plan(IncrementalExport.sources(d, null), ledger), ledger, sink)
        assertEquals(listOf("manifest.jsonl", "clips/b.wav"), sink.opened)
        assertEquals(2, r3.filesCopied)
        assertEquals(450, r3.bytesCopied)

        // Same name and size, newer mtime (e.g. a re-written file): copied again.
        write(File(d.clips, "a.wav"), 200, 3_000)
        val plan4 = IncrementalExport.plan(IncrementalExport.sources(d, null), ledger)
        assertEquals(listOf("manifest.jsonl", "clips/a.wav"), plan4.map { it.relativePath })

        // The ledger survives a serialize/parse round trip with the same decisions.
        val again = ExportLedger.parse(ledger.serialize())
        assertEquals(plan4.map { it.relativePath }, IncrementalExport.plan(IncrementalExport.sources(d, null), again).map { it.relativePath })
    }

    @Test
    fun aFailedFileIsReportedAndRetriedNextTime() {
        val d = tempDirs()
        write(d.manifest, 10, 1_000)
        write(File(d.clips, "a.wav"), 20, 1_000)
        write(File(d.clips, "b.wav"), 30, 1_000)
        val ledger = ExportLedger()
        val sink = FakeSink().apply { failOn = "clips/a.wav" }
        var done = 0
        val r = IncrementalExport.run(IncrementalExport.plan(IncrementalExport.sources(d, null), ledger), ledger, sink, onFileDone = { done++ })
        assertEquals(2, r.filesCopied)
        assertEquals(2, done)
        assertEquals(listOf("clips/a.wav" to "IOException"), r.failures)
        assertNull(ledger["clips/a.wav"])
        sink.failOn = null
        val plan = IncrementalExport.plan(IncrementalExport.sources(d, null), ledger)
        assertEquals(listOf("manifest.jsonl", "clips/a.wav"), plan.map { it.relativePath })
    }

    @Test
    fun lostPermissionStopsTheExportButKeepsWhatWasCopied() {
        val d = tempDirs()
        write(d.manifest, 10, 1_000)
        write(File(d.clips, "a.wav"), 20, 1_000)
        write(File(d.clips, "b.wav"), 30, 1_000)
        val ledger = ExportLedger()
        val sink = FakeSink().apply { throwSecurityOn = "clips/a.wav" }
        assertFailsWith<SecurityException> {
            IncrementalExport.run(IncrementalExport.plan(IncrementalExport.sources(d, null), ledger), ledger, sink)
        }
        assertTrue(ledger.isCurrent("manifest.jsonl", 10, 1_000))
        assertNull(ledger["clips/a.wav"])
        assertNull(ledger["clips/b.wav"])
    }

    @Test
    fun cancelStopsBetweenFiles() {
        val d = tempDirs()
        write(d.manifest, 10, 1_000)
        write(File(d.clips, "a.wav"), 20, 1_000)
        val ledger = ExportLedger()
        // 0.9.4.1: the flag is read after every chunk too, so cancel when the first file is done.
        var cancel = false
        val r = IncrementalExport.run(
            IncrementalExport.plan(IncrementalExport.sources(d, null), ledger), ledger, FakeSink(),
            onFileDone = { cancel = true }, isCancelled = { cancel },
        )
        assertEquals(1, r.filesCopied)
        assertTrue(r.cancelled)
        assertEquals(1, ledger.size)
    }

    @Test
    fun cancelInTheMiddleOfAFileDiscardsItAndDoesNotRecordIt() {
        val d = tempDirs()
        write(d.manifest, 10, 1_000)
        write(File(d.continuous, "20261026_01.m4a"), 3 * IncrementalExport.CHUNK + 5, 1_000)
        val ledger = ExportLedger()
        val discarded = ArrayList<String>()
        val sink = object : ExportSink {
            override val name = "USB"
            override fun openForWrite(folder: String, name: String): OutputStream = ByteArrayOutputStream()
            override fun discard(folder: String, name: String) { discarded += "$folder/$name" }
        }
        var chunks = 0
        val r = IncrementalExport.run(
            IncrementalExport.plan(IncrementalExport.sources(d, null), ledger), ledger, sink,
            onProgress = { if (it.current.startsWith("continuous/") && it.bytesDone > 10) chunks++ },
            isCancelled = { chunks >= 2 },
        )
        assertTrue(r.cancelled)
        assertEquals(1, r.filesCopied) // the manifest
        assertEquals(10L, r.bytesCopied) // the half hour file does not count
        assertEquals(listOf("continuous/20261026_01.m4a"), discarded)
        assertNull(ledger["continuous/20261026_01.m4a"])
        assertTrue(ledger["manifest.jsonl"] != null)
    }
}
