package ch.lessnoise.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportSnapshot
import ch.lessnoise.app.upload.SentReport
import ch.lessnoise.chart.LessNoiseTheme
import com.android.resources.Density
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** A stand-in for a video's first frame: a street with a red car (the real frame comes from MediaMetadataRetriever on a phone). */
internal fun fakeVideoFrame(): ImageBitmap {
    val img = ImageBitmap(480, 360)
    val c = androidx.compose.ui.graphics.Canvas(img)
    c.drawRect(0f, 0f, 480f, 360f, Paint().apply { color = Color(0xFF9DB4C8) })
    c.drawRect(0f, 200f, 480f, 360f, Paint().apply { color = Color(0xFF5E6266) })
    c.drawRect(0f, 270f, 480f, 278f, Paint().apply { color = Color(0xFFE8E8E8) })
    c.drawRect(120f, 180f, 360f, 260f, Paint().apply { color = Color(0xFFC0392B) })
    c.drawRect(170f, 140f, 310f, 190f, Paint().apply { color = Color(0xFFA93226) })
    val wheel = Paint().apply { color = Color(0xFF1C1C1C) }
    c.drawCircle(Offset(175f, 262f), 26f, wheel)
    c.drawCircle(Offset(305f, 262f), 26f, wheel)
    return img
}

/**
 * 0.9.4.3 (Labor·Karte): the «Laut! Melden» dialog with «Video» (none taken; one taken, its first frame with the
 * play mark and «0:07»), the «Meldungen» card with a sent report's video, and the video full-screen (a still for
 * the VideoView, which needs a phone), at [widthDp] × [heightDp] (480 dpi, font 1.0). Written to
 * $stadtlaerm.screenshotDir/0.9.4.3-video as video-<state>-<suffix>.png, for review only.
 */
abstract class VideoDeviceScreenshots(widthDp: Int, heightDp: Int, private val suffix: String) {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            screenWidth = widthDp * 3, screenHeight = heightDp * 3, xdpi = 480, ydpi = 480, density = Density.XXHIGH, fontScale = 1.0f,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
        snapshotHandler = PolishPngWriter(File(System.getProperty("stadtlaerm.screenshotDir") ?: "build/screenshots", "0.9.4.3-video")),
        useDeviceResolution = true,
    )

    private val zone: ZoneId = ZoneId.of("Europe/Zurich")
    private fun ms(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()
    // Made inside each snapshot (layoutlib draws bitmaps only once Paparazzi has started).
    private val thumb by lazy { ReportVideoThumb(fakeVideoFrame(), 7_200) }

    // Only the name matters: «….mp4» makes it a video; the frame is the stand-in above.
    private fun draft() = File.createTempFile("draft-", ".mp4").apply { deleteOnExit() }

    private fun dialog(state: String, dark: Boolean, chosen: ReportCategory?, taken: Boolean) = paparazzi.snapshot("video-$state-$suffix") {
        LessNoiseTheme(dark = dark) {
            ReportDialogSample(chosen, photo = if (taken) draft() else null, video = true, videoPreview = if (taken) thumb else null)
        }
    }

    @Test fun noVideo() = dialog("dialog-none", dark = false, chosen = ReportCategory.AUTO, taken = false)
    @Test fun noVideoDark() = dialog("dialog-none-dark", dark = true, chosen = null, taken = false)
    @Test fun withVideo() = dialog("dialog-taken", dark = false, chosen = ReportCategory.AUTO, taken = true)
    @Test fun withVideoDark() = dialog("dialog-taken-dark", dark = true, chosen = ReportCategory.MOTORRAD, taken = true)

    private fun meldungen(state: String, dark: Boolean) = paparazzi.snapshot("video-$state-$suffix") {
        val video = "${ms(15, 16, 5)}.mp4"
        val reports = ReportSnapshot(
            sent = listOf(
                SentReport(ms(14, 22, 10), "auto", "c1", lafMaxDb = 76.0),
                SentReport(ms(15, 7, 55), "lastwagen", "c1", lafMaxDb = 78.2),
                SentReport(ms(15, 16, 5), "motorrad", "c1", lafMaxDb = 84.6, media = video),
            ),
        )
        LessNoiseTheme(dark = dark) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                ScreenTitle("Teilen")
                ReportsCard(reports, mapOf(video to thumb))
            }
        }
    }

    @Test fun meldungen() = meldungen("meldungen", dark = false)
    @Test fun meldungenDark() = meldungen("meldungen-dark", dark = true)

    @Test fun viewer() = paparazzi.snapshot("video-viewer-$suffix") {
        LessNoiseTheme(dark = false) {
            ReportVideoViewerContent(note = null, onDelete = {}, onDismiss = {}) {
                Box(Modifier.fillMaxWidth()) {
                    Image(fakeVideoFrame(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The owner's phone, approximately. */
class Video412x915Screenshots : VideoDeviceScreenshots(412, 915, "412x915")

/** A small phone. */
class Video360x740Screenshots : VideoDeviceScreenshots(360, 740, "360x740")
