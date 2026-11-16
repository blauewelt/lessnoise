package ch.lessnoise.app.edition

import java.io.File

/**
 * 0.9.4.3: the published editions (Online-Version; the Offline-Version compiles this file too but has no
 * reports) have **no video** in «Laut! Melden»: the dialog keeps the photo (ui/ReportPhotos.kt). The Labor
 * editions' version (src/labor) copies the camera app's video into a clean MP4 with Android's MP4 writer,
 * which `tools/check_release_apk.py` keeps out of the published APKs. Here nothing is ever copied.
 */
object EditionReportVideo {
    /** Whether «Laut! Melden» offers a video instead of a photo: not in the published editions. */
    const val AVAILABLE = false

    /** Never reached (no video button here); keeps nothing if it were. */
    @Suppress("UNUSED_PARAMETER")
    fun strip(src: File, dst: File): Boolean = false
}
