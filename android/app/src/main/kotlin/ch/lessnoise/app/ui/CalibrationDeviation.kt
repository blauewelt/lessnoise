package ch.lessnoise.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.lessnoise.app.data.CalibrationEntity
import ch.lessnoise.dsp.calibration.CalibrationMath
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 0.9.5.1 (owner, 2026-11-01): «Kalibrieren» shows a calibration as its deviation from the standard
 * ([CalibrationMath.deviationFromDefault]), never as the conversion constant offsetDb (≈ 112 dB on a
 * typical phone): «132,42 dB» read like «the microphone is off by 132 dB». Stored values, exports and
 * uploads keep offsetDb. Pure Kotlin, unit-tested.
 */
object CalibrationDeviation {
    /** «+12,3», «−3,0» (minus sign), «0,0»: one decimal, decimal comma; no sign when it rounds to zero. */
    fun signed(deviationDb: Double): String {
        val tenths = (abs(deviationDb) * 10).roundToLong()
        val text = String.format(Locale.GERMANY, "%d,%d", tenths / 10, tenths % 10)
        return when {
            tenths == 0L -> text
            deviationDb > 0 -> "+$text"
            else -> "−$text"
        }
    }

    /** «+12,3 dB» for a stored offsetDb. */
    fun ofOffset(offsetDb: Double): String = "${signed(CalibrationMath.deviationFromDefault(offsetDb))} dB"

    /** The label of the rows and of the field in «Korrektur von Hand». */
    const val LABEL = "Abweichung vom Standard"

    /** The first line of «Kalibrieren»: «Kalibriert am 01.11.2026 15:45: Abweichung vom Standard +20,1 dB (Messgerät)». */
    fun activeLine(calibrated: Boolean, createdEpochMs: Long, method: String, offsetDb: Double): String =
        if (!calibrated) NOT_CALIBRATED
        else "Kalibriert am ${Fmt.dateTime(createdEpochMs)}: $LABEL ${ofOffset(offsetDb)} (${methodName(method)})"

    /** Until 0.9.5 «Nicht kalibriert (Standardwert 112,35 dB).». */
    const val NOT_CALIBRATED = "Nicht kalibriert (Standardwert)."

    /** A row of «Verlauf»: «… · Messgerät · Abweichung +12,3 dB · …». */
    fun historyPart(offsetDb: Double): String = "Abweichung ${ofOffset(offsetDb)}"

    /**
     * What a person types in «Korrektur von Hand»: «+3», «-3,5», «−3,5» (minus sign), «3.5». Null when it
     * is not a number.
     */
    fun parse(s: String): Double? =
        s.trim().replace('−', '-').replace(',', '.').removeSuffix("dB").trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    /** «Korrektur von Hand»: the typed deviation → the offsetDb to store. */
    fun manualOffset(typed: String): Double? = parse(typed)?.let { CalibrationMath.offsetFromDeviation(it) }

    /** «Korrektur von Hand» prefilled with the active calibration's deviation (empty when uncalibrated). */
    fun manualPrefill(calibrated: Boolean, offsetDb: Double?): String =
        if (!calibrated || offsetDb == null) "" else signed(CalibrationMath.deviationFromDefault(offsetDb))

    // ---- «Wirklich speichern?» above 20 dB ------------------------------------------------------

    /** The same rule as the warning: |deviation| > [CalibrationMath.MAX_DEVIATION_FROM_DEFAULT_DB] (20 dB). */
    fun needsConfirm(offsetDb: Double): Boolean = CalibrationMath.implausible(offsetDb)

    const val CONFIRM_TITLE = "Wirklich speichern?"
    const val CONFIRM_SAVE = "Speichern"
    const val CONFIRM_CANCEL = "Abbrechen"

    /** One line: the deviation, why it is suspicious, the likely mistake of [method]. */
    fun confirmText(offsetDb: Double, method: String): String {
        val hint = when (method) {
            CalibrationEntity.METHOD_REFERENCE -> "Messgerät auf A-Bewertung und Mittelwert über 30 s?"
            CalibrationEntity.METHOD_CALIBRATOR -> "Kalibrator dicht aufgesetzt, Pegel richtig gewählt?"
            else -> "Abweichung eingetragen, nicht den alten Korrekturwert?"
        }
        val limit = CalibrationMath.MAX_DEVIATION_FROM_DEFAULT_DB.toInt()
        return "$LABEL ${ofOffset(offsetDb)} – mehr als $limit dB, vermutlich ein Fehler ($hint)."
    }
}

/**
 * 0.9.5.1: the save of a calibration, held back by «Wirklich speichern?» when it is more than 20 dB from
 * the standard ([CalibrationDeviation.needsConfirm]); within 20 dB it saves at once, as before. A
 * cancelled confirm saves nothing. Compose state, so the screen shows the dialog while [pending] is set.
 */
class SaveConfirmation {
    data class Pending(val offsetDb: Double, val method: String, val save: () -> Unit)

    var pending by mutableStateOf<Pending?>(null)
        private set

    fun request(offsetDb: Double, method: String, save: () -> Unit) {
        if (CalibrationDeviation.needsConfirm(offsetDb)) pending = Pending(offsetDb, method, save) else save()
    }

    fun confirm() {
        val p = pending ?: return
        pending = null
        p.save()
    }

    fun cancel() { pending = null }
}

@Composable
fun SaveConfirmDialog(p: SaveConfirmation.Pending, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(CalibrationDeviation.CONFIRM_TITLE) },
        text = { Text(CalibrationDeviation.confirmText(p.offsetDb, p.method)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(CalibrationDeviation.CONFIRM_SAVE) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(CalibrationDeviation.CONFIRM_CANCEL) } },
    )
}
