package ch.lessnoise.app.edition

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import ch.lessnoise.app.ui.ReportVideos
import java.io.File
import java.nio.ByteBuffer

/**
 * 0.9.4.3 (Labor·Karte first): the short video of «Laut! Melden» (ui/ReportVideos.kt) exists only in the
 * Labor editions (reachable only in laborKarte, the one Labor build with reports). The published editions
 * have the stand-in in src/public (no video; the dialog keeps the photo), because the copy below needs
 * Android's MP4 writer, `MediaMuxer`, and `tools/check_release_apk.py` proves that the published APKs contain
 * no reference to it (their «no audio encoding» rule). Nothing here encodes anything: the camera app's
 * compressed samples are copied as they are.
 */
object EditionReportVideo {
    /** Whether «Laut! Melden» offers a video (Labor·Karte) instead of a photo (Online-Version). */
    const val AVAILABLE = true

    private const val TAG = "ReportVideo"

    /**
     * Copies the video and audio track of the camera app's [src] sample by sample into a new MP4 [dst] (no
     * re-encode), so that nothing of the source's container survives: no `udta`/`meta` boxes (where camera
     * apps write the GPS position, the maker and model, the camera app's name), no further tracks (e.g. a
     * camera's motion or location data track: only the first video and the first audio track are copied,
     * [ReportVideos.tracksToCopy]), and no location of our own (`setLocation` is never called). The picture's
     * orientation is carried over as the orientation hint ([ReportVideos.orientationHint]). False (and no
     * [dst]) when the file has no video track or a track the writer does not take; the caller then keeps
     * nothing. Verified on a device only (WORKLOG 0.9.4.3); the decisions are tested in ReportVideoTest.
     */
    fun strip(src: File, dst: File): Boolean {
        dst.delete()
        dst.parentFile?.mkdirs()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var ok = false
        try {
            extractor.setDataSource(src.path)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val plan = ReportVideos.tracksToCopy(formats.map { it.getString(MediaFormat.KEY_MIME) })
            if (plan == null) {
                Log.w(TAG, "no video track")
                return false
            }
            val videoFormat = formats[plan.first()]
            val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) videoFormat.getInteger(MediaFormat.KEY_ROTATION) else retrieverRotation(src)
            val m = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            m.setOrientationHint(ReportVideos.orientationHint(rotation))
            val out = HashMap<Int, Int>()
            var largest = 0
            for (t in plan) {
                val f = formats[t]
                out[t] = m.addTrack(f)
                extractor.selectTrack(t)
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) largest = maxOf(largest, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            val buffer = ByteBuffer.allocate(ReportVideos.sampleBufferBytes(largest))
            val info = MediaCodec.BufferInfo()
            m.start()
            started = true
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                buffer.clear()
                val n = extractor.readSampleData(buffer, 0)
                if (n < 0) break
                val to = out[track]
                if (to != null) {
                    info.set(0, n, extractor.sampleTime, ReportVideos.bufferFlags(extractor.sampleFlags))
                    m.writeSampleData(to, buffer, info)
                }
                extractor.advance()
            }
            ok = true
        } catch (e: Exception) {
            Log.w(TAG, "video not copied: ${e.javaClass.simpleName}")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            try {
                if (started) muxer?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "video not finished: ${e.javaClass.simpleName}")
                ok = false
            }
            try { muxer?.release() } catch (_: Exception) {}
        }
        if (!ok || dst.length() == 0L) {
            dst.delete()
            return false
        }
        return true
    }

    /** The rotation the retriever reads (when the extractor's format has none). */
    private fun retrieverRotation(src: File): Int? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(src.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
