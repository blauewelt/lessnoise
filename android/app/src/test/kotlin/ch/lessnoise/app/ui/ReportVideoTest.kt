package ch.lessnoise.app.ui

import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.upload.KotlinSource
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

/**
 * 0.9.4.3 (Labor·Karte first): the short video of «Laut! Melden» instead of the photo — the size/length policy,
 * the decisions of the metadata-free copy (which tracks, the orientation, the flags), the files (kept, swept),
 * which edition has it, and the source facts that keep it the camera app's video on the phone only. The copy
 * itself (MediaExtractor → MediaMuxer) needs Android: verified on a phone only (WORKLOG 0.9.4.3).
 */
class ReportVideoTest {
    private val tmp = createTempDirectory("report-videos").toFile()

    @AfterTest
    fun clean() {
        tmp.deleteRecursively()
    }

    private fun report(ms: Long) = PendingReport(
        epochMs = ms, timeIso = PendingReport.isoSeconds(ms, ZoneId.of("Europe/Zurich")),
        lat = 47.37691, lon = 8.54169, accuracyM = 12.0, category = "auto", cell = "h26833_12479",
    )

    @Test
    fun theCameraIsAskedForTenSecondsInHighQuality() {
        assertEquals(10, ReportVideos.DURATION_LIMIT_S)
        assertEquals(1, ReportVideos.VIDEO_QUALITY)
        assertEquals("Video zu lang – bitte höchstens 10 s", ReportVideos.TOO_LONG)
    }

    @Test
    fun aVideoIsKeptUpTo30SecondsAnd60MegabytesAndNeverCut() {
        val mb = 1024L * 1024
        assertEquals(30_000L, ReportVideos.MAX_DURATION_MS)
        assertEquals(60 * mb, ReportVideos.MAX_BYTES)
        // A camera app that honours the limit: about 20 MB for 10 s of 1080p.
        assertEquals(ReportVideos.Verdict.OK, ReportVideos.verdict(20 * mb, 10_000))
        // One that ignores it: kept up to 30 s …
        assertEquals(ReportVideos.Verdict.OK, ReportVideos.verdict(40 * mb, 30_000))
        assertEquals(ReportVideos.Verdict.TOO_LONG, ReportVideos.verdict(40 * mb, 30_001))
        // … and 60 MB, whatever the length says, also when the length cannot be read.
        assertEquals(ReportVideos.Verdict.OK, ReportVideos.verdict(60 * mb, 9_000))
        assertEquals(ReportVideos.Verdict.TOO_LONG, ReportVideos.verdict(60 * mb + 1, 9_000))
        assertEquals(ReportVideos.Verdict.TOO_LONG, ReportVideos.verdict(61 * mb, null))
        assertEquals(ReportVideos.Verdict.OK, ReportVideos.verdict(5 * mb, null))
        // Nothing written by the camera app.
        assertEquals(ReportVideos.Verdict.EMPTY, ReportVideos.verdict(0, null))
        assertEquals(ReportVideos.Verdict.EMPTY, ReportVideos.verdict(0, 7_000))
    }

    @Test
    fun theLengthReadsLikeAClock() {
        assertEquals("0:07", ReportVideos.durationText(7_000))
        assertEquals("0:07", ReportVideos.durationText(7_499))
        assertEquals("0:08", ReportVideos.durationText(7_500))
        assertEquals("0:10", ReportVideos.durationText(10_033))
        assertEquals("0:00", ReportVideos.durationText(0))
        assertEquals("0:00", ReportVideos.durationText(-5))
        assertEquals("1:05", ReportVideos.durationText(65_000))
    }

    @Test
    fun theCopyKeepsTheFirstVideoAndTheFirstAudioTrackOnly() {
        assertEquals(listOf(0, 1), ReportVideos.tracksToCopy(listOf("video/avc", "audio/mp4a-latm")))
        assertEquals(listOf(1, 0), ReportVideos.tracksToCopy(listOf("audio/mp4a-latm", "video/hevc")))
        // A camera's metadata / motion track, a second audio track and subtitles are left out.
        assertEquals(
            listOf(1, 2),
            ReportVideos.tracksToCopy(listOf("application/microvideo-meta-stream", "video/hevc", "audio/mp4a-latm", "audio/raw", "text/3gpp-tt", null)),
        )
        // Without sound: the picture alone.
        assertEquals(listOf(0), ReportVideos.tracksToCopy(listOf("video/avc", "application/meta")))
        // Without a picture: nothing is kept.
        assertNull(ReportVideos.tracksToCopy(listOf("audio/mp4a-latm")))
        assertNull(ReportVideos.tracksToCopy(emptyList()))
        assertNull(ReportVideos.tracksToCopy(listOf(null, "application/meta")))
    }

    @Test
    fun theOrientationIsCarriedOverAsOneOfFourAngles() {
        assertEquals(0, ReportVideos.orientationHint(null))
        assertEquals(0, ReportVideos.orientationHint(0))
        assertEquals(90, ReportVideos.orientationHint(90))
        assertEquals(180, ReportVideos.orientationHint(180))
        assertEquals(270, ReportVideos.orientationHint(270))
        assertEquals(270, ReportVideos.orientationHint(-90))
        assertEquals(90, ReportVideos.orientationHint(450))
        assertEquals(0, ReportVideos.orientationHint(360))
        // The writer accepts only these four; anything else stays upright.
        assertEquals(0, ReportVideos.orientationHint(45))
    }

    @Test
    fun aSyncSampleBecomesAKeyFrameAndNothingElseIsCarried() {
        val sync = android.media.MediaExtractor.SAMPLE_FLAG_SYNC
        val key = android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
        assertEquals(key, ReportVideos.bufferFlags(sync))
        assertEquals(0, ReportVideos.bufferFlags(0))
        assertEquals(0, ReportVideos.bufferFlags(android.media.MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))
        assertEquals(key, ReportVideos.bufferFlags(sync or android.media.MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))
        assertEquals(2 * 1024 * 1024, ReportVideos.sampleBufferBytes(0))
        assertEquals(5_000_000, ReportVideos.sampleBufferBytes(5_000_000))
    }

    @Test
    fun aVideoIsKeptAndSweptLikeAPhoto() {
        for (ok in listOf("1761381603000.mp4", "1761381603000-1.mp4", "1761381603000.jpg")) assertTrue(ReportPhotos.isName(ok), ok)
        for (bad in listOf("1.mov", "1.MP4", "x.mp4", "../1.mp4", "1.mp4/..", "1.mp4.jpg.exe", "draft-1.mp4")) assertFalse(ReportPhotos.isName(bad), bad)
        assertEquals("video", ReportPhotos.kind("1.mp4"))
        assertEquals("photo", ReportPhotos.kind("1.jpg"))
        assertNull(ReportPhotos.kind(null))
        assertNull(ReportPhotos.kind("../1.mp4"))
        assertTrue(ReportPhotos.isVideo("1.mp4") && !ReportPhotos.isVideo("1.jpg"))
        // The thumbnail decides by the extension: the dialog's draft «draft-<ms>.mp4» is a video too.
        assertTrue(ReportVideos.isVideoFile(File("draft-1761381603000.mp4")) && ReportVideos.isVideoFile(File("1.mp4")))
        assertFalse(ReportVideos.isVideoFile(File("draft-1.jpg")))
        // «Senden»: the draft moves to files/reports/<epochMs>.mp4.
        val dir = File(tmp, "files/reports")
        val draft = File(tmp, "cache/report-draft/draft-1.mp4").apply { parentFile.mkdirs(); writeText("mp4") }
        assertEquals("1761381603000.mp4", ReportPhotos.keep(dir, draft, 1_761_381_603_000L))
        assertFalse(draft.exists(), "moved, not copied")
        val second = File(tmp, "cache/report-draft/draft-2.mp4").apply { writeText("other") }
        assertEquals("1761381603000-1.mp4", ReportPhotos.keep(dir, second, 1_761_381_603_000L))
        // Only the two kinds the app writes are kept.
        val odd = File(tmp, "cache/report-draft/draft-3.mov").apply { writeText("mov") }
        assertNull(ReportPhotos.keep(dir, odd, 7L))
        assertTrue(odd.exists())
        // The sweep: old photos and new videos alike — whatever no report names goes.
        for (n in listOf("1.jpg", "2.mp4", "3.mp4", "4.jpg")) File(dir, n).writeText(n)
        val store = MemoryReportStore(
            ReportSnapshot(
                pending = listOf(report(2).copy(media = "2.mp4")),
                sent = listOf(report(1).copy(media = "1.jpg").toSent(), report(3).copy(media = "3.mp4").toSent()),
            ),
        )
        ReportPhotos.sweep(dir, ReportQueue.media(store.state.value))
        assertEquals(setOf("1.jpg", "2.mp4", "3.mp4"), dir.list()!!.toSet())
        // «Video löschen» in «Meldungen»: the video alone; the report stays.
        ReportQueue.dropSentMedia(store, 3, "3.mp4")
        ReportPhotos.sweep(dir, ReportQueue.media(store.state.value))
        assertEquals(setOf("1.jpg", "2.mp4"), dir.list()!!.toSet())
        assertEquals(2, store.state.value.sent.size)
        // The pending report dropped («Vom Server löschen», sharing off): its video goes with it.
        ReportQueue.dropPending(store)
        ReportPhotos.sweep(dir, ReportQueue.media(store.state.value))
        assertEquals(setOf("1.jpg"), dir.list()!!.toSet())
    }

    /** The video exists in the Labor editions only (laborKarte is the one with reports); the published ones keep the photo. */
    @Test
    fun onlyTheLaborEditionsHaveTheVideo() {
        val expected = BuildConfig.FLAVOR == "labor" || BuildConfig.FLAVOR == "laborKarte"
        assertEquals(expected, ReportVideos.enabled, BuildConfig.FLAVOR)
        assertEquals(if (expected) "Video" else "Foto", reportMediaButton(ReportVideos.enabled, has = false))
        assertEquals("Neues Video", reportMediaButton(video = true, has = true))
        assertEquals("Kurzes Video vom Fahrzeug (optional)", reportMediaSpoken(video = true))
        assertEquals("Video entfernen", reportMediaRemove(video = true))
        assertEquals("Video löschen", ReportVideos.DELETE)
    }

    /**
     * The camera app records (no CAMERA permission, nothing recorded by this app), with the limit; the camera's
     * file is deleted in every case; the copy is the Labor editions' and writes no location and encodes nothing;
     * the published editions' code holds no MP4 writer (tools/check_release_apk.py checks the APKs for it).
     */
    @Test
    fun theCameraAppRecordsAndTheCopyCarriesNoMetadata() {
        val app = appDir()
        val videos = File(app, "src/main/kotlin/ch/lessnoise/app/ui/ReportVideos.kt").readText()
        assertTrue(videos.contains("private class CaptureShortVideo : ActivityResultContracts.CaptureVideo()"))
        assertTrue(videos.contains(".putExtra(MediaStore.EXTRA_DURATION_LIMIT, ReportVideos.DURATION_LIMIT_S)"))
        assertTrue(videos.contains(".putExtra(MediaStore.EXTRA_VIDEO_QUALITY, ReportVideos.VIDEO_QUALITY)"))
        assertTrue(videos.contains("rememberLauncherForActivityResult(CaptureShortVideo())"))
        assertTrue(videos.contains("    } finally {\n        raw.delete()\n    }"), "the camera's file is deleted in finally")
        assertTrue(videos.contains("if (EditionReportVideo.strip(raw, draft)) ReportMediaResult.Draft(draft)"), "only the stripped copy becomes the draft")
        assertTrue(videos.contains("File(context.cacheDir, ReportPhotos.CAMERA_DIR)") && videos.contains("File(context.cacheDir, ReportPhotos.DRAFT_DIR)"))
        for (bad in listOf("MediaStore.Video", "MediaStore.Images", "Environment.", "getExternal", "openFileOutput", "ACTION_SEND", "MediaRecorder", "AudioRecord")) {
            assertFalse(videos.contains(bad), bad)
        }
        assertFalse(Regex("""\bIntent\(""").containsMatchIn(videos), "builds no intent of its own (only the contract's)")
        // The copy: Labor editions only; first video + first audio track, orientation carried, never a location, no codec.
        val labor = KotlinSource.parse(File(app, "src/labor/kotlin/ch/lessnoise/app/edition/ReportVideoEdition.kt").readText()).code
        assertTrue(labor.contains("const val AVAILABLE = true"))
        assertTrue(labor.contains("ReportVideos.tracksToCopy(formats.map { it.getString(MediaFormat.KEY_MIME) })"))
        assertTrue(labor.contains("MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)"))
        assertTrue(labor.contains("m.setOrientationHint(ReportVideos.orientationHint(rotation))"))
        assertTrue(labor.contains("m.writeSampleData(to, buffer, info)"))
        for (bad in listOf("setLocation", "createDecoder", "createEncoder", "createByCodecName", ".configure(", "MediaRecorder")) assertFalse(labor.contains(bad), bad)
        val public = KotlinSource.parse(File(app, "src/public/kotlin/ch/lessnoise/app/edition/ReportVideoEdition.kt").readText()).code
        assertTrue(public.contains("const val AVAILABLE = false") && public.contains("fun strip(src: File, dst: File): Boolean = false"))
        // No MP4 writer in what the published editions compile (src/main, src/public, src/karte, src/nomap …).
        val published = listOf("main", "public", "karte", "nomap", "noposition", "nounterwegs").flatMap { d ->
            File(app, "src/$d").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue(published.size > 30)
        val writers = published.filter { Regex("""\bMediaMuxer\b|\bMediaCodec\b""").containsMatchIn(KotlinSource.parse(it.readText()).code) }
        assertEquals(emptyList(), writers.map { it.relativeTo(app).path })
        // The draft video plays with the dialog's own microphone closed; the camera app has the microphone while
        // it records, because this activity is then stopped (ReportLevelEffect runs only while STARTED).
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        assertTrue(ui.contains("ReportLevelEffect(meter, micAllowed && !viewingDraft)"))
        assertTrue(ui.contains("ReportVideoViewer(playing, muted = measuring, onDelete = { dropDraft() }, onDismiss = { viewingDraft = false })"))
        val level = File(app, "src/main/kotlin/ch/lessnoise/app/ui/ReportLevel.kt").readText()
        assertTrue(level.contains("val onScreen = lifecycle.isAtLeast(Lifecycle.State.STARTED)"))
        assertTrue(level.contains("DisposableEffect(measuring, micAllowed, onScreen) {\n        if (measuring || !micAllowed || !onScreen) return@DisposableEffect onDispose {}"))
        // «Meldungen»: a video plays, a photo shows; «Video löschen» deletes the video alone.
        val share = File(app, "src/main/kotlin/ch/lessnoise/app/ui/ShareScreen.kt").readText()
        assertTrue(share.contains("if (ReportPhotos.isVideo(file.name)) ReportVideoViewer(file, muted = measuring, onDelete = delete, onDismiss = { viewing = null })"))
        assertTrue(share.contains("ReportPhotos.deleteSentMedia(context, context.container.upload.reports, v.epochMs, name)"))
    }

    private fun appDir(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { it.parentFile }.take(4)
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
            .firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/main").isDirectory } ?: fail("app module not found from $start")
    }
}
