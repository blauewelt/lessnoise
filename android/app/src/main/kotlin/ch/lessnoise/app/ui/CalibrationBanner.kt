package ch.lessnoise.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.lessnoise.app.data.CalibrationEntity
import ch.lessnoise.dsp.calibration.CalibrationMath
import java.time.ZoneId

/**
 * 0.9.6 (owner, 2026-11-01: «There should be a very clear indicator on whether the phone is calibrated or not»):
 * what the banner on «Messen» (and at the top of «Kalibrieren») says. [Calibrated]: the active calibration of the
 * microphone in use — not «Zurücksetzen» (reset_default) and not none at all — with its offsetDb and its date;
 * [NotCalibrated]: the standard value is in use. Null where the status is used means «not read yet»: no banner.
 */
sealed interface CalibrationStatus {
    data class Calibrated(val offsetDb: Double, val createdEpochMs: Long) : CalibrationStatus
    data object NotCalibrated : CalibrationStatus

    companion object {
        /** From the active calibration of a microphone (null: never calibrated). */
        fun of(entity: CalibrationEntity?): CalibrationStatus =
            if (entity != null && entity.isCalibrated) Calibrated(entity.offsetDb, entity.createdEpochMs) else NotCalibrated
    }
}

/**
 * 0.9.6: the banner's texts (pure, CalibrationBannerTest). The deviation is that of «Kalibrieren» (0.9.5.1,
 * [CalibrationDeviation.signed]: signed, one decimal, decimal comma, «−» minus sign), the date that of the active
 * calibration. No-break spaces keep «−2,3 dB», «± 5 dB» and «Kalibrieren ›» together when the line wraps.
 */
object CalibrationBannerTexts {
    private const val NBSP = ' '

    /** «Telefon kalibriert: −2,3 dB · 01.11.2026». */
    fun calibrated(offsetDb: Double, createdEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "Telefon kalibriert: ${CalibrationDeviation.signed(CalibrationMath.deviationFromDefault(offsetDb))}${NBSP}dB · " +
            Fmt.date(createdEpochMs, zone)

    /** The red banner's statement (also alone, at the top of «Kalibrieren», where a pointer would point to itself). */
    const val NOT_CALIBRATED = "Telefon nicht kalibriert – Werte sind Richtwerte (±${NBSP}5${NBSP}dB)."

    /** The red banner's pointer on «Messen»: a tap opens «Kalibrieren». */
    const val POINTER = "Kalibrieren$NBSP›"

    /** What TalkBack says for the tap (the action, not «›»). */
    const val OPEN_LABEL = "Kalibrieren öffnen"

    /** The whole line; [pointer]: the banner opens «Kalibrieren» (on «Messen»), so the red one ends with [POINTER]. */
    fun text(status: CalibrationStatus, pointer: Boolean, zone: ZoneId = ZoneId.systemDefault()): String = when (status) {
        is CalibrationStatus.Calibrated -> calibrated(status.offsetDb, status.createdEpochMs, zone)
        CalibrationStatus.NotCalibrated -> if (pointer) "$NOT_CALIBRATED $POINTER" else NOT_CALIBRATED
    }

    /** Spoken: the line without «›» (the tap's label says where it goes). */
    fun spoken(status: CalibrationStatus, zone: ZoneId = ZoneId.systemDefault()): String = text(status, pointer = false, zone)
}

/** 0.9.6: the banner's colours: container, text, icon. */
data class BannerColors(val container: Color, val content: Color, val icon: Color)

/**
 * 0.9.6: green and red from the theme's own roles (no literal colour): calibrated = tertiaryContainer /
 * onTertiaryContainer with the tertiary icon (the brand's green, LessNoiseTheme), not calibrated = errorContainer /
 * onErrorContainer with the error icon. Contrast of the text ≥ 7:1 in both themes (CalibrationBannerTest). The
 * meaning is also in the icon (tick / warning) and the words, not in the colour alone (red–green colour blindness).
 */
fun bannerColors(status: CalibrationStatus, cs: ColorScheme): BannerColors = when (status) {
    is CalibrationStatus.Calibrated -> BannerColors(cs.tertiaryContainer, cs.onTertiaryContainer, cs.tertiary)
    CalibrationStatus.NotCalibrated -> BannerColors(cs.errorContainer, cs.onErrorContainer, cs.error)
}

/** 0.9.6: the banner's minimum height, a full touch target. */
val BANNER_MIN_HEIGHT = 48.dp

/**
 * 0.9.6: the calibration banner, full width within the screen's gutters, 12 dp corners as the cards: the icon (tick or
 * warning), the line ([CalibrationBannerTexts.text]) and — when [onClick] is set (on «Messen») — a chevron (green) or
 * «Kalibrieren ›» in semibold (red); the whole banner is the tap target. Without [onClick] (at the top of
 * «Kalibrieren») no pointer and no tap.
 */
@Composable
fun CalibrationBanner(status: CalibrationStatus, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val colors = bannerColors(status, MaterialTheme.colorScheme)
    val pointer = onClick != null
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.container,
        contentColor = colors.content,
        modifier = modifier.fillMaxWidth().padding(horizontal = SCREEN_GUTTER),
    ) {
        Row(
            Modifier
                .then(if (onClick != null) Modifier.clickable(onClickLabel = CalibrationBannerTexts.OPEN_LABEL, role = Role.Button, onClick = onClick) else Modifier)
                .semantics(mergeDescendants = true) { contentDescription = CalibrationBannerTexts.spoken(status) }
                .heightIn(min = BANNER_MIN_HEIGHT)
                .padding(start = 12.dp, end = if (pointer && status is CalibrationStatus.Calibrated) 6.dp else 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (status is CalibrationStatus.Calibrated) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null, tint = colors.icon, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            val line = CalibrationBannerTexts.text(status, pointer)
            Text(
                buildAnnotatedString {
                    if (pointer && status == CalibrationStatus.NotCalibrated) {
                        append(line.removeSuffix(CalibrationBannerTexts.POINTER))
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(CalibrationBannerTexts.POINTER) }
                    } else append(line)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (pointer && status is CalibrationStatus.Calibrated) {
                Spacer(Modifier.width(2.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * 0.9.6: the banner's tap — «Einstellungen» shown (a tab switch like any other, [TabHistory]) and opened at «Erweitert»,
 * scrolled to «Kalibrieren» ([CALIBRATE_ITEM]): the way a person gets there by hand, Einstellungen › Erweitert ›
 * Kalibrieren. One request, taken by the «Einstellungen» it opens ([taken]); the next visit to the tab starts at the
 * top again. Back from «Erweitert» goes to «Einstellungen», then to «Messen», as after the way by hand.
 */
class CalibrationLink {
    var requested by mutableStateOf(false)
        private set

    /** The banner was tapped: the tab history with «Einstellungen» shown. */
    fun open(history: TabHistory): TabHistory {
        requested = true
        return history.select(Screen.SETTINGS)
    }

    /** «Einstellungen» has opened «Erweitert» at «Kalibrieren». */
    fun taken() { requested = false }
}
