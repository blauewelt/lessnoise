package ch.lessnoise.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import ch.lessnoise.app.upload.PendingReport
import ch.lessnoise.app.upload.ReportQueue
import ch.lessnoise.app.upload.ReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 0.8.5 (Labor·Karte first; the owner finds a photo faster than typing the plate): the photo of
 * «Laut! Melden». Reached only in the editions with reports (public, laborKarte).
 *
 * - Taken by the phone's own camera app (`ActivityResultContracts.TakePicture`, i.e.
 *   `MediaStore.ACTION_IMAGE_CAPTURE` with our own output file), so this app needs **no camera
 *   permission** and declares none (a declared but refused CAMERA permission would even block that
 *   intent). The camera app writes into `cache/camera/` (FileProvider path «camera», a write grant
 *   for that one file).
 * - At once the app re-encodes it ([downscale]): at most [MAX_SIDE] px on the long side, JPEG
 *   [JPEG_QUALITY], turned upright from its EXIF orientation, into `cache/report-draft/`. The
 *   re-encoded file carries **no EXIF at all** (`Bitmap.compress` writes none), so a GPS position
 *   the camera app may have written is gone; the camera's original is deleted in `finally`.
 * - «Senden» moves the draft into the app's private files, `files/reports/<epochMs>.jpg`
 *   ([queue]); the report keeps the file name (`PendingReport.photo`, then `SentReport.photo`).
 *   «Abbrechen», «×» or a new photo delete the draft. Leftovers (the app killed meanwhile) are
 *   deleted at app start ([clearLeftovers]).
 * - The photo is shown only in the person's own list («Teilen» › «Meldungen») and **never sent**:
 *   the upload package opens no file (UploadSourceIsolationTest) and Payloads.report does not read
 *   the name (ReportsTest). A photo of a licence plate is personal data of the driver; the app keeps
 *   it only on the phone, in storage no other app can read, excluded from backup like everything else.
 * - Deleted with the report: [sweep] deletes every file of `files/reports/` that no pending or sent
 *   report refers to (a report dropped by «Vom Server löschen» or switching sharing off, rejected,
 *   rolled out of the last 500 sent ones), and «Löschen» deletes one photo alone ([deleteSentPhoto]).
 *   Queueing and sweeping hold [lock], so a photo is never swept between its move and its report.
 */
object ReportPhotos {
    /** FileProvider path in res/xml/file_paths.xml (`<cache-path name="camera" path="camera/" />`). */
    const val CAMERA_DIR = "camera"

    /** The re-encoded photo until «Senden» (cache, never shared). */
    const val DRAFT_DIR = "report-draft"

    /** The kept photos, in the app's private files. */
    const val DIR = "reports"

    /** 0.8.2–0.8.4: the «Kontrollschild» photo's cache folder; only emptied now. */
    private const val OLD_PLATE_DIR = "plate"

    const val MAX_SIDE = 1600
    const val JPEG_QUALITY = 85

    const val BUTTON = "Foto"
    const val BUTTON_AGAIN = "Neues Foto"
    const val REMOVE = "Foto entfernen"
    const val DESCRIPTION = "Foto der Meldung"
    const val DELETE = "Löschen"
    const val CLOSE = "Schliessen"
    const val NO_CAMERA = "Keine Kamera-App gefunden"
    const val FAILED = "Foto konnte nicht gespeichert werden"

    private const val TAG = "ReportPhotos"
    private val NAME = Regex("""^\d{1,19}(-\d{1,3})?\.jpg$""")

    /** Held while a photo moves into [DIR] with its report, and while [sweep] decides what to delete. */
    val lock = Any()

    /** Whether [name] is a photo name this app writes («1761381603000.jpg», «…-1.jpg»): no path, no other file. */
    fun isName(name: String?): Boolean = name != null && NAME.matches(name)

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** The kept photo [name], or null for anything that is not one of ours. */
    fun file(context: Context, name: String?): File? = if (isName(name)) File(dir(context), name!!) else null

    /** Deletes the camera's and the dialog's leftovers (cache only; the kept photos are untouched). */
    fun clearLeftovers(context: Context) {
        for (d in listOf(CAMERA_DIR, DRAFT_DIR, OLD_PLATE_DIR)) File(context.cacheDir, d).listFiles()?.forEach { it.delete() }
    }

    internal fun newCameraFile(context: Context): File {
        File(context.cacheDir, CAMERA_DIR).listFiles()?.forEach { it.delete() }
        val dir = File(context.cacheDir, CAMERA_DIR).apply { mkdirs() }
        return File(dir, "camera-${System.currentTimeMillis()}.jpg")
    }

    internal fun newDraftFile(context: Context): File {
        val dir = File(context.cacheDir, DRAFT_DIR).apply { mkdirs() }
        return File(dir, "draft-${System.currentTimeMillis()}.jpg")
    }

    internal fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** The largest power of two that keeps the long side at least [maxSide] (BitmapFactory.inSampleSize). */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        val long = maxOf(width, height)
        var s = 1
        while (long / (s * 2) >= maxSide) s *= 2
        return s
    }

    /** The size with the long side at most [maxSide], proportions kept. */
    fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxSide) return width to height
        val f = maxSide.toDouble() / long
        return maxOf(1, Math.round(width * f).toInt()) to maxOf(1, Math.round(height * f).toInt())
    }

    /** EXIF orientation → degrees to turn clockwise (the mirrored ones turned only; rare from a camera app). */
    fun rotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /** Re-encodes [src] into [dst] ([MAX_SIDE], [JPEG_QUALITY], upright, no EXIF). False when it cannot be read. */
    fun downscale(src: File, dst: File): Boolean {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            var bmp = BitmapFactory.decodeFile(src.path, opts) ?: return false
            val (w, h) = scaledSize(bmp.width, bmp.height)
            val turn = rotationDegrees(
                try { ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { 0 },
            )
            if (w != bmp.width || h != bmp.height || turn != 0) {
                val m = Matrix().apply {
                    postScale(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
                    if (turn != 0) postRotate(turn.toFloat())
                }
                val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (out !== bmp) bmp.recycle()
                bmp = out
            }
            dst.parentFile?.mkdirs()
            val ok = FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bmp.recycle()
            if (!ok) dst.delete()
            return ok
        } catch (e: Exception) {
            Log.w(TAG, "photo not re-encoded: ${e.javaClass.simpleName}")
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "photo too large")
        }
        dst.delete()
        return false
    }

    /**
     * Moves [draft] into [dir] as «<epochMs>.jpg» («<epochMs>-1.jpg» … if taken), returns the name;
     * null when there is no draft or it cannot be moved (then nothing is kept). Pure file work (tested).
     */
    fun keep(dir: File, draft: File, epochMs: Long): String? {
        if (!draft.isFile) return null
        dir.mkdirs()
        var name = "$epochMs.jpg"
        var i = 0
        while (File(dir, name).exists() && i < 999) { i++; name = "$epochMs-$i.jpg" }
        val target = File(dir, name)
        if (target.exists()) return null
        if (!draft.renameTo(target)) {
            try {
                draft.copyTo(target)
                draft.delete()
            } catch (_: Exception) {
                target.delete()
                return null
            }
        }
        return name
    }

    /** Deletes every file in [dir] whose name is not in [keep]; returns how many. Pure file work (tested). */
    fun sweep(dir: File, keep: Set<String>): Int {
        var n = 0
        dir.listFiles()?.forEach { f -> if (f.name !in keep && f.delete()) n++ }
        return n
    }

    /** Queues [report] with the photo [draft] (moved into [DIR] first; none if [draft] is null or unusable). */
    fun queue(context: Context, store: ReportStore, report: PendingReport, draft: File?): PendingReport = synchronized(lock) {
        val name = draft?.let { keep(dir(context), it, report.epochMs) }
        val r = report.copy(photo = name)
        ReportQueue.add(store, r)
        r
    }

    /** The photos no report refers to any more are deleted (called on every change of the queue). */
    fun sweep(context: Context, store: ReportStore): Int = synchronized(lock) {
        sweep(dir(context), ReportQueue.photos(store.state.value))
    }

    /** «Löschen» in the photo viewer: the sent report forgets its photo, the file is deleted. */
    fun deleteSentPhoto(context: Context, store: ReportStore, epochMs: Long, name: String) {
        synchronized(lock) {
            ReportQueue.dropSentPhoto(store, epochMs, name)
            file(context, name)?.delete()
        }
    }

    /** A small bitmap of [file] for a thumbnail of [px] (the short side at least [px]); null if unreadable. */
    fun thumbnail(file: File, px: Int): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) null else {
            var s = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= px) s *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

/** What the camera gave the dialog: a re-encoded draft, a short line, or nothing (cancelled). */
sealed interface ReportPhotoResult {
    data class Draft(val file: File) : ReportPhotoResult
    data class Note(val text: String) : ReportPhotoResult
    data object Cancelled : ReportPhotoResult
}

/**
 * The «Foto» button's action: opens the camera app, re-encodes the photo off the main thread, deletes
 * the camera's original, then hands [onResult] the draft.
 */
@Composable
fun rememberReportPhotoCamera(onResult: (ReportPhotoResult) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Saved: the camera may outlive this screen's state (the path is in the app's cache).
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = path?.let(::File)
        path = null
        if (file == null || !taken || !file.isFile || file.length() == 0L) {
            file?.delete()
            onResult(ReportPhotoResult.Cancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val draft = withContext(Dispatchers.IO) {
                try {
                    val d = ReportPhotos.newDraftFile(context)
                    if (ReportPhotos.downscale(file, d)) d else null
                } finally {
                    file.delete()
                }
            }
            onResult(if (draft != null) ReportPhotoResult.Draft(draft) else ReportPhotoResult.Note(ReportPhotos.FAILED))
        }
    }
    return {
        val file = ReportPhotos.newCameraFile(context)
        path = file.path
        try {
            launcher.launch(ReportPhotos.uriFor(context, file))
        } catch (_: ActivityNotFoundException) {
            // No camera app on this phone.
            path = null
            file.delete()
            onResult(ReportPhotoResult.Note(ReportPhotos.NO_CAMERA))
        }
    }
}

/** A square thumbnail of [file] ([size]), cropped to fill; [onClick] makes it a button. */
@Composable
fun ReportPhotoThumbnail(file: File, size: Dp, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, file.path, px) {
        value = withContext(Dispatchers.IO) { ReportPhotos.thumbnail(file, px) }
    }
    val shape = RoundedCornerShape(8.dp)
    val m = modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)
        .let { if (onClick != null) it.clickable(onClickLabel = ReportPhotos.DESCRIPTION, onClick = onClick) else it }
    Box(m) {
        bitmap?.let { Image(it, contentDescription = ReportPhotos.DESCRIPTION, contentScale = ContentScale.Crop, modifier = Modifier.size(size)) }
    }
}

/** The photo full-screen (0.8.5, «Teilen» › «Meldungen»): «Löschen» deletes this photo alone, «Schliessen» closes. */
@Composable
fun ReportPhotoViewer(file: File, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val px = with(LocalDensity.current) { 2000.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, file.path) {
        value = withContext(Dispatchers.IO) { ReportPhotos.thumbnail(file, minOf(px, ReportPhotos.MAX_SIDE)) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it, contentDescription = ReportPhotos.DESCRIPTION, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(ReportPhotos.DELETE) }
                    TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(ReportPhotos.CLOSE) }
                }
            }
        }
    }
}
