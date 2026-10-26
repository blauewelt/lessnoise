package ch.lessnoise.app.labor

import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.9.4: the export scope («Alles» / «Seit letztem Export» / «Letzte 24 h»), the manifest window, «Exportierte Aufnahmen löschen». */
class ExportScopeTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private fun ms(d: Int, h: Int, m: Int = 0, s: Int = 0) = LocalDateTime.of(2026, 10, d, h, m, s).atZone(zone).toInstant().toEpochMilli()
    private val now = ms(26, 8, 0)

    private fun src(path: String, size: Long, mtime: Long) = ExportSource(path, size, mtime) { ByteArray(size.toInt()).inputStream() }

    private val manifest = src("manifest.jsonl", 1_000, now)
    // Last night (in the window) and the night before (not).
    private val clipNew = src("clips/ev_120_20261025_234012.wav", 400_000, ms(25, 23, 41))
    private val clipNew2 = src("clips/ev_121_20261026_031500_2.wav", 300_000, ms(26, 3, 16))
    private val clipOld = src("clips/ev_7_20261024_230000.wav", 500_000, ms(24, 23, 1))
    private val clipOldTouched = src("clips/ev_8_20261024_220000.wav", 500_000, ms(26, 7, 0)) // name wins over mtime
    private val classNew = src("classifier/20261026_02.jsonl", 1_200_000, ms(26, 3, 0))
    private val classEdge = src("classifier/20261025_07.jsonl", 1_200_000, ms(25, 8, 0)) // 07:00–07:59 yesterday: overlaps 08:00? no
    private val classOverlap = src("classifier/20261025_08.jsonl", 1_200_000, ms(25, 9, 0)) // 08:00–08:59: overlaps the window start
    private val hourNew = src("continuous/20261025_23_2.m4a", 29_000_000, ms(26, 0, 0))
    private val hourOld = src("continuous/20261023_23.m4a", 29_000_000, ms(24, 0, 0))
    private val all = listOf(manifest, clipNew, clipNew2, clipOld, clipOldTouched, classNew, classEdge, classOverlap, hourNew, hourOld)

    private fun paths(l: List<ExportSource>) = l.map { it.relativePath }

    @Test
    fun timeSpanFromNamesElseLastModified() {
        assertEquals(ms(25, 23, 40, 12)..ms(25, 23, 40, 12), ExportScopes.timeSpan(clipNew.relativePath, 0, zone))
        assertEquals(ms(26, 3, 15), ExportScopes.timeSpan(clipNew2.relativePath, 0, zone).first) // `_2` suffix
        assertEquals(ms(26, 1, 2, 3), ExportScopes.timeSpan("clips/ev_na_20261026_010203.wav", 0, zone).first)
        assertEquals(ms(26, 2)..(ms(26, 3) - 1), ExportScopes.timeSpan(classNew.relativePath, 0, zone))
        assertEquals(ms(25, 23)..(ms(26, 0) - 1), ExportScopes.timeSpan(hourNew.relativePath, 0, zone))
        // Unknown or impossible names: lastModified.
        assertEquals(42L..42L, ExportScopes.timeSpan("clips/notes.wav", 42, zone))
        assertEquals(42L..42L, ExportScopes.timeSpan("clips/ev_1_20261399_250000.wav", 42, zone))
        assertEquals(42L..42L, ExportScopes.timeSpan("continuous/20261026_24.m4a", 42, zone))
    }

    @Test
    fun last24hTakesTheWindowByNameAndKeepsTheManifestFirst() {
        val l = ExportScopes.select(all, ExportTarget.ZIP, ExportScope.LAST_24H, withoutHourFiles = false, ExportLedger(), now, zone)
        assertEquals(
            listOf("manifest.jsonl", clipNew.relativePath, clipNew2.relativePath, classNew.relativePath, classOverlap.relativePath, hourNew.relativePath),
            paths(l),
        )
        val noHours = ExportScopes.select(all, ExportTarget.ZIP, ExportScope.LAST_24H, withoutHourFiles = true, ExportLedger(), now, zone)
        assertEquals(paths(l) - hourNew.relativePath, paths(noHours))
        // A file whose name does not parse: its lastModified decides.
        val odd = listOf(src("clips/x.wav", 1, now - 1_000), src("clips/y.wav", 1, now - ExportScopes.WINDOW_MS - 1))
        assertEquals(listOf("clips/x.wav"), paths(ExportScopes.select(odd, ExportTarget.ZIP, ExportScope.LAST_24H, false, ExportLedger(), now, zone)))
    }

    @Test
    fun ledgerDecidesSinceLastExportAndTheFolderButNotTheZipOfAllOr24h() {
        val ledger = ExportLedger()
        for (s in listOf(manifest, clipOld, clipNew, hourOld)) ledger.record(s.relativePath, s.size, s.lastModified)
        fun sel(t: ExportTarget, sc: ExportScope, noHours: Boolean = false) = paths(ExportScopes.select(all, t, sc, noHours, ledger, now, zone))

        assertEquals(paths(all), sel(ExportTarget.ZIP, ExportScope.ALL))
        val fresh = paths(all) - listOf(clipOld.relativePath, clipNew.relativePath, hourOld.relativePath)
        assertEquals(fresh, sel(ExportTarget.FOLDER, ExportScope.ALL)) // as IncrementalExport.plan before 0.9.4
        assertEquals(paths(IncrementalExport.plan(all, ledger)), sel(ExportTarget.FOLDER, ExportScope.ALL))
        assertEquals(fresh, sel(ExportTarget.ZIP, ExportScope.SINCE_LAST))
        assertEquals(fresh, sel(ExportTarget.FOLDER, ExportScope.SINCE_LAST))
        assertEquals(fresh - hourNew.relativePath, sel(ExportTarget.ZIP, ExportScope.SINCE_LAST, noHours = true))
        // «Letzte 24 h»: the ZIP takes the window even if exported; the folder only what is new in it.
        assertTrue(clipNew.relativePath in sel(ExportTarget.ZIP, ExportScope.LAST_24H))
        assertEquals(
            listOf("manifest.jsonl", clipNew2.relativePath, classNew.relativePath, classOverlap.relativePath, hourNew.relativePath),
            sel(ExportTarget.FOLDER, ExportScope.LAST_24H),
        )
        // A file changed since (size): no longer «exportiert».
        val grown = all.map { if (it == clipOld) src(it.relativePath, it.size + 1, it.lastModified) else it }
        assertTrue(clipOld.relativePath in paths(ExportScopes.select(grown, ExportTarget.ZIP, ExportScope.SINCE_LAST, false, ledger, now, zone)))
    }

    @Test
    fun sinceLastExportWithAnEmptyLedgerTakesEverythingAndSaysSo() {
        val empty = ExportLedger()
        assertTrue(ExportScopes.neverExported(empty))
        assertEquals(ExportScope.ALL, ExportScopes.effective(ExportScope.SINCE_LAST, empty))
        assertEquals(ExportScope.LAST_24H, ExportScopes.effective(ExportScope.LAST_24H, empty))
        assertEquals(paths(all), paths(ExportScopes.select(all, ExportTarget.ZIP, ExportScope.SINCE_LAST, false, empty, now, zone)))
        val line = ExportScopes.estimateLine(ExportScope.SINCE_LAST, false, ExportEstimate.of(all), neverExported = true)
        assertEquals("Seit letztem Export mit Stundendateien: Noch nie exportiert – nimmt alles, 10\u00A0Dateien, 63\u00A0MB", line)
        assertFalse(ExportScopes.neverExported(ExportLedger().apply { record("manifest.jsonl", 1, 1) }))
    }

    @Test
    fun estimateLineAndSizes() {
        assertEquals(
            "Letzte 24 h ohne Stundendateien: 412\u00A0Dateien, 165\u00A0MB",
            ExportScopes.estimateLine(ExportScope.LAST_24H, true, ExportEstimate(412, 165_000_000), neverExported = true),
        )
        assertEquals("Alles mit Stundendateien: 1\u00A0Datei, 0,1\u00A0MB", ExportScopes.estimateLine(ExportScope.ALL, false, ExportEstimate(1, 100_000), false))
        assertEquals("3,37\u00A0GB", ExportScopes.size(3_370_000_000))
        assertEquals("4,2\u00A0MB", ExportScopes.size(4_200_000))
        val l = ExportScopes.select(all, ExportTarget.ZIP, ExportScope.LAST_24H, true, ExportLedger(), now, zone)
        assertEquals(ExportEstimate(5, 1_000 + 400_000 + 300_000 + 1_200_000 + 1_200_000), ExportEstimate.of(l))
    }

    @Test
    fun hourFilesTooBigForAZipAreLeftOut() {
        val small = listOf(manifest, hourNew)
        assertEquals(small to emptyList(), ExportScopes.fitZip(small))
        val big = listOf(manifest, clipNew, hourNew, hourOld)
        val (files, out) = ExportScopes.fitZip(big, limit = 50_000_000)
        assertEquals(listOf(manifest, clipNew), files)
        assertEquals(listOf(hourNew, hourOld), out)
    }

    @Test
    fun zipNameCarriesTheScope() {
        assertEquals("lessnoise-labor-20261026-24h.zip", ExportScopes.zipName(ExportScope.LAST_24H, now, zone))
        assertEquals("lessnoise-labor-20261026-seit-export.zip", ExportScopes.zipName(ExportScope.SINCE_LAST, now, zone))
        assertEquals("lessnoise-labor-20261026-alles.zip", ExportScopes.zipName(ExportScope.ALL, now, zone))
        assertEquals(ExportScope.LAST_24H, ExportScope.of(null))
        assertEquals(ExportScope.LAST_24H, ExportScope.of("weird"))
        for (sc in ExportScope.values()) assertEquals(sc, ExportScope.of(sc.key))
    }

    @Test
    fun deleteExportedTakesOnlyCurrentLedgerFilesNeverTheManifest() {
        val root = Files.createTempDirectory("labor-del").toFile()
        try {
            val d = LaborDirs(root).also { it.ensure() }
            fun write(rel: String, n: Int, mtime: Long) = File(root, rel).also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(n)); it.setLastModified(mtime) }
            write("manifest.jsonl", 50, 1_000)
            write("clips/a.wav", 100, 1_000)
            write("clips/b.wav", 200, 1_000)
            write("classifier/20261025_23.jsonl", 30, 1_000)
            write("continuous/20261025_23.m4a", 400, 1_000)
            val ledger = ExportLedger()
            for (s in IncrementalExport.sources(d, null)) if (s.relativePath != "clips/b.wav") ledger.record(s.relativePath, s.size, s.lastModified)
            ledger.record("clips/b.wav", 199, 1_000) // exported, then changed: stays
            val sel = ExportScopes.exportedForDeletion(IncrementalExport.sources(d, null), ledger)
            assertEquals(listOf("clips/a.wav", "classifier/20261025_23.jsonl", "continuous/20261025_23.m4a"), paths(sel))

            // The classifier log grew between the dialog and «Löschen»: kept.
            File(root, "classifier/20261025_23.jsonl").appendBytes(ByteArray(5))
            val done = ExportScopes.deleteExported(root, sel, ledger)
            assertEquals(ExportEstimate(2, 500), done)
            assertFalse(File(root, "clips/a.wav").exists())
            assertFalse(File(root, "continuous/20261025_23.m4a").exists())
            assertTrue(File(root, "classifier/20261025_23.jsonl").exists())
            assertTrue(File(root, "clips/b.wav").exists())
            assertTrue(d.manifest.exists())
            assertEquals(null, ledger["clips/a.wav"])
            assertTrue(ledger.isCurrent("manifest.jsonl", 50, 1_000)) // «schon einmal exportiert» stays
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipWritesTheGivenSourcesWithTheFilteredManifest() {
        val root = Files.createTempDirectory("labor-zip").toFile()
        try {
            val filtered = File(root, "m.jsonl").apply { writeText("{\"a\":1}\n") }
            val m = ExportScopes.replaced(src("manifest.jsonl", 9_999, 1_234_000), filtered)
            assertEquals(8, m.size)
            assertEquals(1_234_000, m.lastModified)
            val zip = File(root, "x.zip")
            ExportScopes.writeZip(zip, listOf(m, src("clips/a.wav", 10, 2_000)))
            java.util.zip.ZipFile(zip).use { z ->
                assertEquals(listOf("manifest.jsonl", "clips/a.wav"), z.entries().toList().map { it.name })
                assertEquals("{\"a\":1}\n", z.getInputStream(z.getEntry("manifest.jsonl")).readBytes().toString(Charsets.UTF_8))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    // ---- manifest window ----

    private fun iso(t: Long) = ch.lessnoise.dsp.Iso.format(t, zone, millis = true)
    private fun line(type: String, session: String, t: Long, rest: String = "") =
        "{\"type\":\"$type\",\"session\":\"$session\",\"time\":\"${iso(t)}\"$rest}"

    @Test
    fun manifestWindowKeepsTheWindowAndWhatItsSessionsDependOn() {
        val from = now - ExportScopes.WINDOW_MS
        // Session B: two days ago, over before the window.
        val bStart = line("session_start", "B", ms(24, 1), ",\"calibrationOffsetDb\":0.5")
        val bEvent = line("event", "B", ms(24, 2))
        // Session A: started three days ago, still running.
        val aStart = line("session_start", "A", ms(23, 22), ",\"calibrationOffsetDb\":1.25")
        val aClock1 = line("clock", "A", ms(23, 22), ",\"anchorSample\":0")
        val aOpen1 = line("continuous_open", "A", ms(25, 6), ",\"file\":\"continuous/20261025_06.m4a\"")
        val aClose1 = line("continuous_close", "A", ms(25, 7), ",\"file\":\"continuous/20261025_06.m4a\"")
        val aClock2 = line("clock", "A", ms(25, 7, 30), ",\"anchorSample\":9")
        val aOpen2 = line("continuous_open", "A", ms(25, 7), ",\"file\":\"continuous/20261025_07.m4a\"")
        val aFull = line("storage_full", "A", ms(25, 7, 40), ",\"usedBytes\":1")
        val aOldEvent = line("event", "A", ms(25, 7, 50))
        val bad = "{\"type\":\"clip\",\"session\":\"A\",\"time\":\"2026-10-25T07:5" // cut off by a full phone
        val aEvent = line("event", "A", ms(25, 23), ",\"durationS\":4")
        // Other key order (not the writer's): parsed in full.
        val aClip = "{\"session\":\"A\",\"type\":\"clip\",\"time\":\"${iso(ms(25, 23, 1))}\",\"file\":\"clips/ev_1_20261025_230000.wav\"}"
        val motion = line("motion", "none", ms(26, 1))
        val untimed = "{\"type\":\"note\",\"session\":\"A\"}"
        val badTime = "{\"type\":\"event\",\"session\":\"A\",\"time\":\"gestern\"}"
        val cStart = line("session_start", "C", ms(26, 2))
        val report = line("report", "C", ms(26, 2, 5), ",\"cat\":\"motorrad\"")
        val future = line("event", "C", now + 60_000)
        val lines = listOf(
            bStart, bEvent, aStart, aClock1, aOpen1, aClose1, aClock2, aOpen2, aFull, aOldEvent, bad, "",
            aEvent, aClip, motion, untimed, badTime, cStart, report, future,
        )
        val kept = ManifestWindow.filter(lines, from, now)
        assertEquals(listOf(aStart, aClock2, aOpen2, aFull, aEvent, aClip, motion, untimed, cStart, report), kept)

        // The file versions agree with the list version.
        val root = Files.createTempDirectory("labor-mf").toFile()
        try {
            val src = File(root, "manifest.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }
            val dst = File(root, "out/manifest.jsonl")
            val n = ManifestWindow.write(src, dst, from, now)
            assertEquals(kept.joinToString("\n") + "\n", dst.readText())
            assertEquals(n, ManifestWindow.bytes(src, from, now))
            assertEquals(lines.joinToString("\n") + "\n", src.readText()) // the phone's manifest is untouched
            assertEquals(0L, ManifestWindow.bytes(File(root, "missing.jsonl"), from, now))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestWindowWithTheWritersOwnLines() {
        // Lines as ManifestLines writes them: the fast path must read them.
        val from = now - ExportScopes.WINDOW_MS
        val old = ManifestLines.clock("S", ms(25, 1), zone, 0, ms(25, 1), null)
        val clock = ManifestLines.clock("S", ms(25, 2), zone, 48_000, ms(25, 2), 3)
        val open = ManifestLines.continuousOpen("S", ms(25, 7, 30), zone, "continuous/20261025_07.m4a", 0, ms(25, 7, 30))
        val full = ManifestLines.storageFull("S", ms(25, 7, 45), zone, 5, 4)
        val inWin = ManifestLines.report("S", ms(26, 7), zone, "auto", false, null, null, null)
        assertEquals(listOf(clock, open, full, inWin), ManifestWindow.filter(listOf(old, clock, open, full, inWin), from, now))
        // No line of the session in the window: nothing of it.
        assertEquals(emptyList<String>(), ManifestWindow.filter(listOf(old, clock, open, full), from, now))
    }
}
