package ch.lessnoise.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * 0.8.2 (Labor·Karte first): the camera button of «Kontrollschild (optional)» in «Laut! Melden».
 * Reached only in the editions with reports (public, laborKarte).
 *
 * - The photo is taken by the phone's own camera app (`MediaStore.ACTION_IMAGE_CAPTURE` through
 *   `ActivityResultContracts.TakePicture`), so this app needs NO camera permission and declares none
 *   (a declared but refused CAMERA permission would even block that intent).
 * - The camera app writes the one photo into this app's cache, `cache/plate/` (FileProvider path
 *   «plate», a write grant for that one file only); [onPhoto] reads it and the file is deleted in
 *   `finally`, whatever happened; leftovers (the app killed while the camera was open) are deleted
 *   before the next photo and at app start ([clearLeftovers]).
 * - Text recognition: NONE in this build ([OCR] = false). The bundled Google ML Kit text recognition
 *   was checked and not added, because it brings Google Play services and Firebase's logging
 *   transport, which sends usage logs to firebaselogging.googleapis.com (WORKLOG.md, 0.8.2). The
 *   photo is therefore deleted unread and the field stays empty ([plateFromPhoto]); [PlateOcr]
 *   is the tested plate picker for when an on-device recognizer is decided.
 * - Nothing of the photo leaves the phone; only text the person keeps in the field is stored (on
 *   the phone, never sent, as typed text).
 */
object PlateCamera {
    /** Whether this build recognises text on the photo (owner's decision pending, WORKLOG 0.8.2). */
    const val OCR = false

    /** FileProvider path name in res/xml/file_paths.xml (`<cache-path name="plate" path="plate/" />`). */
    const val DIR = "plate"

    /** Shown under the field when no plate came out of the photo. */
    const val NONE = "Kein Kontrollschild erkannt"

    /** Shown under the field in a build without text recognition ([OCR] = false). */
    const val NO_OCR = "Foto gelöscht – Erkennung noch nicht eingebaut, bitte eintippen"

    /** The camera button's label for TalkBack. */
    const val BUTTON = "Kontrollschild fotografieren"

    /** Deletes every file in cache/plate/ (a photo left by an interrupted call). */
    fun clearLeftovers(context: Context) {
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }

    internal fun newPhoto(context: Context): File {
        clearLeftovers(context)
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        return File(dir, "plate-${System.currentTimeMillis()}.jpg")
    }

    internal fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** What a photo gives the field: a plate to put in, a short line under the field, or nothing (cancelled). */
sealed interface PlatePhoto {
    data class Found(val plate: String) : PlatePhoto
    data class Note(val text: String) : PlatePhoto
    data object Cancelled : PlatePhoto
}

/**
 * [taken]: the camera app returned a photo; [lines]: the text recognised on it, null when this build
 * has no text recognition. Pure (PlateOcrTest).
 */
fun plateFromPhoto(taken: Boolean, lines: List<String>?): PlatePhoto = when {
    !taken -> PlatePhoto.Cancelled
    lines == null -> PlatePhoto.Note(PlateCamera.NO_OCR)
    else -> PlateOcr.bestPlate(lines)?.let { PlatePhoto.Found(it) } ?: PlatePhoto.Note(PlateCamera.NONE)
}

/**
 * The camera button's action: opens the camera app, then hands [onResult] what the photo gave.
 * The photo file is deleted before [onResult] runs.
 */
@Composable
fun rememberPlateCamera(onResult: (PlatePhoto) -> Unit): () -> Unit {
    val context = LocalContext.current
    // Saved: the camera may outlive this screen's state (the path is in the app's cache, no content).
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = path?.let(::File)
        path = null
        val result = try {
            // No text recognition in this build (PlateCamera.OCR): the photo is not opened at all.
            plateFromPhoto(taken && file?.isFile == true && file.length() > 0, lines = null)
        } finally {
            file?.delete()
            PlateCamera.clearLeftovers(context)
        }
        onResult(result)
    }
    return {
        val file = PlateCamera.newPhoto(context)
        path = file.path
        try {
            launcher.launch(PlateCamera.uriFor(context, file))
        } catch (_: ActivityNotFoundException) {
            // No camera app on this phone.
            path = null
            file.delete()
            onResult(PlatePhoto.Note(PlateCamera.NONE))
        }
    }
}
