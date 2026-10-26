package ch.lessnoise.app.ui

import ch.lessnoise.app.upload.MemoryReportStore
import ch.lessnoise.app.upload.PendingReport
import ch.lessnoise.app.upload.ReportQueue
import ch.lessnoise.app.upload.ReportSnapshot
import java.io.File
import java.time.ZoneId
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** 0.8.5: the photo of «Laut! Melden» instead of the typed plate — sizes, files, deletion, no camera permission. */
class ReportPhotoTest {
    private val tmp = createTempDirectory("report-photos").toFile()

    @AfterTest
    fun clean() {
        tmp.deleteRecursively()
    }

    private fun report(ms: Long) = PendingReport(
        epochMs = ms, timeIso = PendingReport.isoSeconds(ms, ZoneId.of("Europe/Zurich")),
        lat = 47.37691, lon = 8.54169, accuracyM = 12.0, category = "auto", cell = "h26833_12479",
    )

    @Test
    fun aPhotoIsReencodedToAtMost1600PixelsOnTheLongSide() {
        assertEquals(1600, ReportPhotos.MAX_SIDE)
        assertEquals(85, ReportPhotos.JPEG_QUALITY)
        // 12 MP camera, 4000 × 3000: decoded at half (2000 × 1500), then scaled to 1600 × 1200.
        assertEquals(2, ReportPhotos.sampleSize(4000, 3000))
        assertEquals(1600 to 1200, ReportPhotos.scaledSize(2000, 1500))
        // 50 MP, 8160 × 6120: a quarter (2040 × 1530), then 1600 × 1200.
        assertEquals(4, ReportPhotos.sampleSize(8160, 6120))
        assertEquals(1600 to 1200, ReportPhotos.scaledSize(2040, 1530))
        // Portrait the same way; small photos as they are.
        assertEquals(2, ReportPhotos.sampleSize(3000, 4000))
        assertEquals(1200 to 1600, ReportPhotos.scaledSize(1500, 2000))
        assertEquals(1, ReportPhotos.sampleSize(1600, 1200))
        assertEquals(1280 to 960, ReportPhotos.scaledSize(1280, 960))
        assertEquals(1, ReportPhotos.sampleSize(3199, 100))
    }

    @Test
    fun theExifOrientationTurnsThePhotoUpright() {
        // android.media.ExifInterface: NORMAL 1, ROTATE_180 3, FLIP_VERTICAL 4, TRANSPOSE 5, ROTATE_90 6, TRANSVERSE 7, ROTATE_270 8.
        assertEquals(listOf(0, 0, 0, 180, 180, 90, 90, 270, 270), (0..8).map(ReportPhotos::rotationDegrees))
    }

    @Test
    fun onlyNamesTheAppWritesAreFiles() {
        for (ok in listOf("1761381603000.jpg", "1761381603000-1.jpg", "1.jpg")) assertTrue(ReportPhotos.isName(ok), ok)
        for (bad in listOf(null, "", "../x.jpg", "a/1.jpg", "1.png", "x.jpg", "1.jpg/..", "1761381603000.jpg.exe", "/1.jpg")) {
            assertFalse(ReportPhotos.isName(bad), bad.toString())
        }
    }

    @Test
    fun senden_movesTheDraftIntoTheReportsFolder() {
        val dir = File(tmp, "files/reports")
        val draft = File(tmp, "cache/report-draft/draft-1.jpg").apply { parentFile.mkdirs(); writeText("jpeg") }
        assertEquals("1761381603000.jpg", ReportPhotos.keep(dir, draft, 1_761_381_603_000L))
        assertFalse(draft.exists(), "moved, not copied")
        assertEquals("jpeg", File(dir, "1761381603000.jpg").readText())
        // The same time again (two reports in one millisecond cannot happen, but a name is never reused).
        val second = File(tmp, "cache/report-draft/draft-2.jpg").apply { writeText("other") }
        assertEquals("1761381603000-1.jpg", ReportPhotos.keep(dir, second, 1_761_381_603_000L))
        assertEquals("jpeg", File(dir, "1761381603000.jpg").readText(), "the first one untouched")
        // No draft: no photo.
        assertNull(ReportPhotos.keep(dir, File(tmp, "missing.jpg"), 5L))
    }

    @Test
    fun aPhotoIsDeletedWithItsReport() {
        val dir = File(tmp, "files/reports").apply { mkdirs() }
        for (n in listOf("1.jpg", "2.jpg", "3.jpg", "stray.tmp")) File(dir, n).writeText(n)
        val store = MemoryReportStore(
            ReportSnapshot(
                pending = listOf(report(1).copy(photo = "1.jpg")),
                sent = listOf(report(2).copy(photo = "2.jpg").toSent(), report(3).copy(photo = "3.jpg").toSent()),
            ),
        )
        // Everything a report refers to stays; nothing else does.
        assertEquals(1, ReportPhotos.sweep(dir, ReportQueue.photos(store.state.value)))
        assertEquals(setOf("1.jpg", "2.jpg", "3.jpg"), dir.list()!!.toSet())
        // «Löschen» on a sent photo: forgotten by its entry, then swept.
        ReportQueue.dropSentPhoto(store, 3, "3.jpg")
        ReportPhotos.sweep(dir, ReportQueue.photos(store.state.value))
        assertEquals(setOf("1.jpg", "2.jpg"), dir.list()!!.toSet())
        // «Vom Server löschen» (or sharing off) drops the pending report, and its photo goes with it.
        ReportQueue.dropPending(store)
        ReportPhotos.sweep(dir, ReportQueue.photos(store.state.value))
        assertEquals(setOf("2.jpg"), dir.list()!!.toSet())
        // A sent entry that rolls out of the last 500 takes its photo with it.
        store.update { it.copy(sent = emptyList()) }
        ReportPhotos.sweep(dir, ReportQueue.photos(store.state.value))
        assertEquals(emptySet(), dir.list()!!.toSet())
        assertEquals(500, ReportSnapshot.SENT_KEEP)
    }

    /** The photo is the camera app's: no CAMERA permission anywhere; our own output file; re-encoded; never in the upload. */
    @Test
    fun noCameraPermissionOwnFileAndTheCamerasOriginalIsDeleted() {
        val app = appDir()
        val manifests = File(app, "src").listFiles().orEmpty().map { File(it, "AndroidManifest.xml") }.filter { it.isFile }
        assertEquals(5, manifests.size, manifests.toString())
        for (m in manifests) {
            val xml = m.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            assertFalse(xml.contains("android.permission.CAMERA"), "$m declares CAMERA")
            assertFalse(xml.contains("android.hardware.camera"), "$m declares a camera feature")
        }
        // No text recognition and none of what it would bring (WORKLOG 0.8.2).
        val gradle = File(app, "build.gradle.kts").readText()
        for (bad in listOf("com.google.mlkit", "play-services", "firebase")) assertFalse(gradle.contains(bad), bad)
        for (gone in listOf("PlateCamera.kt", "PlateOcr.kt")) assertFalse(File(app, "src/main/kotlin/ch/lessnoise/app/ui/$gone").exists(), gone)
        // The camera writes into cache/camera/ through the FileProvider path «camera», in both file_paths.xml.
        for (f in listOf("src/main/res/xml/file_paths.xml", "src/labor/res/xml/file_paths.xml")) {
            val xml = File(app, f).readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            assertTrue(xml.contains("""<cache-path name="camera" path="camera/" />"""), f)
            assertFalse(xml.contains("reports/") || xml.contains("files-path name=\"report"), "$f: the kept photos are never shared")
        }
        val photos = File(app, "src/main/kotlin/ch/lessnoise/app/ui/ReportPhotos.kt").readText()
        assertTrue(photos.contains("ActivityResultContracts.TakePicture()"))
        assertTrue(photos.contains("} finally {\n                    file.delete()"), "the camera's original is deleted in finally")
        assertTrue(photos.contains("bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)"), "re-encoded: no EXIF (no GPS) survives")
        assertTrue(photos.contains("File(context.filesDir, DIR)") && photos.contains("const val DIR = \"reports\""))
        for (bad in listOf("MediaStore.Images", "Environment.", "getExternal", "openFileOutput", "ACTION_SEND", "Intent(")) {
            assertFalse(photos.contains(bad), bad)
        }
        // The dialog: «Foto» instead of «Kontrollschild».
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        assertTrue(ui.contains("ReportPhotoRow(draft, enabled = !waiting, onTake = { camera() }, onRemove = { dropDraft() })"))
        assertTrue(ui.contains("val queued = ReportPhotos.queue(context, c.upload.reports, report, draft)"))
        assertFalse(ui.contains("OutlinedTextField") || ui.contains("ReportQueue.add("), "no plate field; queued only together with its photo")
        // 0.9.4.2: «Foto» beside the status line; «Foto vom Fahrzeug (optional)» stays its spoken label.
        assertEquals("Foto", reportPhotoButton(false))
        assertEquals("Foto vom Fahrzeug (optional)", ReportPhotos.BUTTON)
        assertTrue(ui.substringAfter("fun ReportPhotoRow(").substringBefore("\n}\n").contains("contentDescription = ReportPhotos.BUTTON"))
        assertTrue(ui.contains("photoBesideStatus = draft == null,"))
        assertEquals("Neues Foto", reportPhotoButton(true))
        assertEquals(56, REPORT_THUMB_DP)
        // The app sweeps on every change of the queue and clears the camera's leftovers at start.
        val start = File(app, "src/main/kotlin/ch/lessnoise/app/LessNoiseApp.kt").readText()
        assertTrue(start.contains("ReportPhotos.clearLeftovers(this@LessNoiseApp)"))
        assertTrue(start.contains("reports.state.collect { ch.lessnoise.app.ui.ReportPhotos.sweep(this@LessNoiseApp, reports) }"))
    }

    private fun appDir(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { it.parentFile }.take(4)
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
            .firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/main").isDirectory } ?: fail("app module not found from $start")
    }
}
