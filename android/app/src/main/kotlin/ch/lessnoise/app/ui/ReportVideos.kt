package ch.lessnoise.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.lessnoise.app.edition.EditionReportVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 0.9.4.3 (Labor·Karte first; owner: evidence a police report could use — a photo shows which car, a short
 * video with sound also that it was loud): the short video of «Laut! Melden», instead of the photo. Only where
 * [enabled] (`EditionReportVideo.AVAILABLE`: the Labor editions; the published ones keep the photo, WORKLOG).
 *
 * - Taken by the phone's own camera app at the person's tap (`ActivityResultContracts.CaptureVideo`, i.e.
 *   `MediaStore.ACTION_VIDEO_CAPTURE` with our own output file, asking for at most [DURATION_LIMIT_S] s in
 *   quality [VIDEO_QUALITY]), so this app needs **no camera permission** and records nothing itself: the
 *   camera app records picture and sound. It writes into `cache/camera/` (FileProvider path «camera», a write
 *   grant for that one file), as the photo does.
 * - Many camera apps honour the duration limit; one that does not may return a longer video. It is accepted up
 *   to [MAX_DURATION_MS] and [MAX_BYTES], otherwise refused with [TOO_LONG] and deleted ([verdict]); never cut.
 * - At once the camera's file is copied into a clean MP4 (`EditionReportVideo.strip`: the video and the audio
 *   track sample by sample, no re-encode, no metadata of the source, no location written) in
 *   `cache/report-draft/`; the camera's original is deleted in `finally`, also when the copy fails (then
 *   nothing is kept: [FAILED]).
 * - From there on it is a report file like the photo (ReportPhotos: «Senden» moves it to
 *   `files/reports/<epochMs>.mp4`, «Abbrechen» deletes it, the sweep deletes what no report names, it is
 *   **never sent**).
 * - Shown as its first frame with a play mark and its length («0:07», [ReportMediaThumbnail]); a tap plays it
 *   ([ReportVideoViewer], the platform's VideoView, no player library) with [DELETE].
 */
object ReportVideos {
    /** Video in «Laut! Melden» (Labor·Karte); false: the photo (Online-Version). A constant per edition. */
    val enabled: Boolean get() = EditionReportVideo.AVAILABLE

    /** `MediaStore.EXTRA_DURATION_LIMIT`, seconds. */
    const val DURATION_LIMIT_S = 10

    /** `MediaStore.EXTRA_VIDEO_QUALITY`: 1 = high (0 = MMS quality, too poor to read a plate). */
    const val VIDEO_QUALITY = 1

    /** A camera app that ignores [DURATION_LIMIT_S]: accepted up to this length … */
    const val MAX_DURATION_MS = 30_000L

    /** … and this size (60 MiB; 10 s of 1080p is about 20 MB, of 4K about 50 MB). */
    const val MAX_BYTES = 60L * 1024 * 1024

    /** The smallest sample buffer of the copy (a 4K key frame fits). */
    const val MIN_SAMPLE_BUFFER = 2 * 1024 * 1024

    /** The spoken label of the button (its visible label is [BUTTON_SHORT]). */
    const val BUTTON = "Kurzes Video vom Fahrzeug (optional)"
    const val BUTTON_SHORT = "Video"
    const val BUTTON_AGAIN = "Neues Video"
    const val REMOVE = "Video entfernen"
    const val DESCRIPTION = "Video der Meldung"
    const val PLAY = "Video abspielen"
    const val PAUSE = "Video anhalten"
    const val DELETE = "Video löschen"
    const val CLOSE = "Schliessen"
    const val SAVING = "Video wird gespeichert …"
    const val TOO_LONG = "Video zu lang – bitte höchstens 10 s"
    const val FAILED = "Video konnte nicht gespeichert werden"
    const val NO_CAMERA = "Keine Kamera-App gefunden"
    const val MUTED = "Ton aus, solange eine Messung läuft"

    private const val TAG = "ReportVideos"

    /** The extractor's sync-sample flag and the writer's key-frame flag (both 1; ReportVideoTest compares them with the platform constants). */
    private const val SAMPLE_FLAG_SYNC = 1
    private const val BUFFER_FLAG_KEY_FRAME = 1

    enum class Verdict { OK, EMPTY, TOO_LONG }

    /**
     * Whether the camera's file is kept: nothing ([bytes] 0) is [Verdict.EMPTY]; more than [MAX_BYTES], or longer
     * than [MAX_DURATION_MS] when the length could be read ([durationMs]), is [Verdict.TOO_LONG]. Pure (tested).
     */
    fun verdict(bytes: Long, durationMs: Long?): Verdict = when {
        bytes <= 0L -> Verdict.EMPTY
        bytes > MAX_BYTES -> Verdict.TOO_LONG
        durationMs != null && durationMs > MAX_DURATION_MS -> Verdict.TOO_LONG
        else -> Verdict.OK
    }

    /** «0:07» (rounded to the second; «1:05» past a minute). Pure (tested). */
    fun durationText(ms: Long): String {
        val s = ((ms.coerceAtLeast(0L) + 500L) / 1000L)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /**
     * Which tracks of the camera's file the copy keeps, by their MIME types in file order: the first video
     * track, then the first audio track if there is one; nothing else (a camera's metadata, motion or location
     * tracks, a second audio track, subtitles). Null without a video track (then nothing is kept). Pure (tested).
     */
    fun tracksToCopy(mimes: List<String?>): List<Int>? {
        val video = mimes.indexOfFirst { it?.startsWith("video/") == true }
        if (video < 0) return null
        val audio = mimes.indexOfFirst { it?.startsWith("audio/") == true }
        return if (audio < 0) listOf(video) else listOf(video, audio)
    }

    /**
     * The orientation hint of the copy from the source's rotation (degrees clockwise; MediaFormat.KEY_ROTATION or
     * the retriever's METADATA_KEY_VIDEO_ROTATION): 0, 90, 180 or 270 (negative angles turned positive); anything
     * else, or none, 0 (the writer accepts only these four). Pure (tested).
     */
    fun orientationHint(rotation: Int?): Int {
        val r = Math.floorMod(rotation ?: 0, 360)
        return if (r % 90 == 0) r else 0
    }

    /** The extractor's sample flags → the writer's buffer flags: a sync sample is a key frame; nothing else is carried. */
    fun bufferFlags(sampleFlags: Int): Int = if (sampleFlags and SAMPLE_FLAG_SYNC != 0) BUFFER_FLAG_KEY_FRAME else 0

    /** The copy's sample buffer: the largest MediaFormat.KEY_MAX_INPUT_SIZE of the copied tracks, at least [MIN_SAMPLE_BUFFER]. */
    fun sampleBufferBytes(largestMaxInput: Int): Int = maxOf(largestMaxInput, MIN_SAMPLE_BUFFER)

    internal fun newCameraFile(context: Context): File {
        File(context.cacheDir, ReportPhotos.CAMERA_DIR).listFiles()?.forEach { it.delete() }
        val dir = File(context.cacheDir, ReportPhotos.CAMERA_DIR).apply { mkdirs() }
        return File(dir, "camera-${System.currentTimeMillis()}.mp4")
    }

    internal fun newDraftFile(context: Context): File {
        val dir = File(context.cacheDir, ReportPhotos.DRAFT_DIR).apply { mkdirs() }
        return File(dir, "draft-${System.currentTimeMillis()}.mp4")
    }

    /** Whether [file] is a video (draft or kept), by its extension «.mp4». */
    fun isVideoFile(file: File): Boolean = file.extension == "mp4"

    /** The length of [file] in ms, or null when it cannot be read. */
    fun durationMs(file: File): Long? = withRetriever(file) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }

    /**
     * The camera's [raw] file → the dialog's result: [verdict], then the clean copy into `cache/report-draft/`.
     * [raw] is deleted in every case. Runs off the main thread.
     */
    fun prepare(context: Context, raw: File): ReportMediaResult = try {
        when (verdict(raw.length(), durationMs(raw))) {
            Verdict.EMPTY -> ReportMediaResult.Note(FAILED)
            Verdict.TOO_LONG -> ReportMediaResult.Note(TOO_LONG)
            Verdict.OK -> {
                val draft = newDraftFile(context)
                if (EditionReportVideo.strip(raw, draft)) ReportMediaResult.Draft(draft)
                else {
                    draft.delete()
                    ReportMediaResult.Note(FAILED)
                }
            }
        }
    } finally {
        raw.delete()
    }

    /** The first frame of [file] (short side at least [px]) and its length; null if it cannot be read. */
    fun thumbnail(file: File, px: Int): ReportVideoThumb? = withRetriever(file) { r ->
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val frame = r.getFrameAtTime(0L)?.let { scaled(it, px) }
        ReportVideoThumb(frame?.asImageBitmap(), duration)
    }

    private fun scaled(b: Bitmap, px: Int): Bitmap {
        val short = minOf(b.width, b.height)
        if (px <= 0 || short <= px) return b
        val f = px.toDouble() / short
        val out = Bitmap.createScaledBitmap(b, maxOf(1, (b.width * f).toInt()), maxOf(1, (b.height * f).toInt()), true)
        if (out !== b) b.recycle()
        return out
    }

    private fun <T> withRetriever(file: File, block: (MediaMetadataRetriever) -> T?): T? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            block(r)
        } catch (e: Exception) {
            Log.w(TAG, "video not read: ${e.javaClass.simpleName}")
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}

/** 0.9.4.3: a video's thumbnail: its first frame (null if none could be decoded) and its length in ms. */
data class ReportVideoThumb(val frame: ImageBitmap?, val durationMs: Long?)

/** `CaptureVideo` with the duration limit and the quality ([ReportVideos.DURATION_LIMIT_S], [ReportVideos.VIDEO_QUALITY]). */
private class CaptureShortVideo : ActivityResultContracts.CaptureVideo() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, ReportVideos.DURATION_LIMIT_S)
            .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, ReportVideos.VIDEO_QUALITY)
}

/**
 * The «Video» button's action: opens the camera app, then (off the main thread, «Video wird gespeichert …»
 * meanwhile) checks and copies the video ([ReportVideos.prepare]), deletes the camera's file and hands
 * [onResult] the draft or a short line.
 */
@Composable
fun rememberReportVideoCamera(onResult: (ReportMediaResult) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saved: the camera may outlive this screen's state (the path is in the app's cache).
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(CaptureShortVideo()) { taken ->
        val file = path?.let(::File)
        path = null
        if (file == null || !taken) {
            file?.delete()
            onResult(ReportMediaResult.Cancelled)
            return@rememberLauncherForActivityResult
        }
        onResult(ReportMediaResult.Note(ReportVideos.SAVING))
        scope.launch {
            val r = withContext(Dispatchers.IO) { ReportVideos.prepare(context, file) }
            onResult(r)
        }
    }
    return {
        val file = ReportVideos.newCameraFile(context)
        path = file.path
        try {
            launcher.launch(ReportPhotos.uriFor(context, file))
        } catch (_: ActivityNotFoundException) {
            // No camera app on this phone.
            path = null
            file.delete()
            onResult(ReportMediaResult.Note(ReportVideos.NO_CAMERA))
        }
    }
}

/**
 * 0.9.4.3: the thumbnail of a report's photo or video, by the file's extension: a photo as before
 * ([ReportPhotoThumbnail]); a video as its first frame with a play mark and its length ([ReportVideoThumbnailContent]).
 * [preview] stands in for the decoded frame (the JVM renders; MediaMetadataRetriever needs a phone).
 */
@Composable
fun ReportMediaThumbnail(
    file: File, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, preview: ReportVideoThumb? = null,
) {
    // By the extension: the dialog's draft is «draft-<ms>.mp4», a kept one «<epochMs>.mp4» (ReportPhotos.isName).
    if (!ReportVideos.isVideoFile(file)) {
        ReportPhotoThumbnail(file, size, modifier, onClick)
        return
    }
    val px = with(LocalDensity.current) { size.roundToPx() }
    val thumb by produceState<ReportVideoThumb?>(preview, file.path, px) {
        if (preview == null) value = withContext(Dispatchers.IO) { ReportVideos.thumbnail(file, px) }
    }
    ReportVideoThumbnailContent(thumb, size, modifier, onClick)
}

/** 0.9.4.3: a video's square thumbnail ([size]): its first frame cropped to fill, a play mark, the length at the bottom right. */
@Composable
fun ReportVideoThumbnailContent(thumb: ReportVideoThumb?, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val length = thumb?.durationMs?.let(ReportVideos::durationText)
    val spoken = if (length != null) "${ReportVideos.DESCRIPTION}, $length" else ReportVideos.DESCRIPTION
    val m = modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color(0xFF30343A))
        .let { if (onClick != null) it.clickable(onClickLabel = ReportVideos.PLAY, role = Role.Button, onClick = onClick) else it }
        .semantics { contentDescription = spoken }
    Box(m) {
        thumb?.frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size)) }
        // The play mark a little above the centre when the length is shown under it (at 40 dp they would overlap).
        Box(
            Modifier.align(Alignment.Center).offset(y = if (length != null) -size * 0.12f else 0.dp)
                .size(size * 0.46f).background(Color.Black.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.34f))
        }
        if (length != null) {
            Text(
                length, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 4.dp))
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

/**
 * 0.9.4.3: the video full-screen (the dialog's draft, «Teilen» › «Meldungen»): it plays at once, a tap pauses or
 * plays again; [DELETE] deletes the video alone (not the report), «Schliessen» closes. [muted] while a measurement
 * runs (the phone's speaker would be measured, ReportVideoPlayer).
 */
@Composable
fun ReportVideoViewer(file: File, muted: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReportVideoViewerContent(note = if (muted) ReportVideos.MUTED else null, onDelete = onDelete, onDismiss = onDismiss) {
            ReportVideoPlayer(file, muted)
        }
    }
}

/** The full-screen video without the Dialog window and with [player] as a slot (so the JVM renders draw it with a still). */
@Composable
fun ReportVideoViewerContent(note: String?, onDelete: () -> Unit, onDismiss: () -> Unit, player: @Composable BoxScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { player() }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(ReportVideos.DELETE) }
                TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(ReportVideos.CLOSE) }
            }
        }
    }
}

/**
 * The platform's VideoView (no player library): plays [file] once at once, a tap pauses / plays (again from the
 * start after the end), the play mark while not playing. [muted]: volume 0 (a measurement runs). Stopped when it
 * leaves the composition.
 */
@Composable
fun BoxScope.ReportVideoPlayer(file: File, muted: Boolean) {
    var playing by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(file.path) {
        onDispose { view?.stopPlayback() }
    }
    val toggle = {
        view?.let { v ->
            if (v.isPlaying) { v.pause(); playing = false } else { v.start(); playing = true }
        }
        Unit
    }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    if (muted) mp.setVolume(0f, 0f)
                    start()
                }
                setOnCompletionListener { playing = false }
                setOnErrorListener { _, _, _ -> playing = false; true }
                setVideoPath(file.path)
                view = this
            }
        },
        modifier = Modifier.align(Alignment.Center),
    )
    Box(
        Modifier.matchParentSize().clickable(onClickLabel = if (playing) ReportVideos.PAUSE else ReportVideos.PLAY, role = Role.Button, onClick = toggle),
        contentAlignment = Alignment.Center,
    ) {
        if (!playing) {
            Box(Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }
    }
}
