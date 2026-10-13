package ch.lessnoise.app.ui

import ch.lessnoise.dsp.calibration.EventFloor

/** German UI texts around calibration changes (pure Kotlin, unit-tested). */
object CalibrationTexts {
    /**
     * After saving: «Kalibrierung gespeichert (+9.6 dB).» — the change of the correction against the
     * previous one. The event floor follows silently (EventFloor); since 0.6.1 it is not reported.
     */
    fun saved(a: EventFloor.Adjustment): String = "Kalibrierung gespeichert (${EventFloor.signed(a.deltaDb)} dB)."

    /** After «Zurücksetzen». */
    const val RESET = "Zurückgesetzt. Die Werte sind wieder Richtwerte."

    fun minutes(n: Int): String = if (n == 1) "1 Minute" else "$n Minuten"

    const val RECAL_QUESTION = "Alte Messungen anpassen?"

    /** «n Minuten werden mit der neuen Kalibrierung umgerechnet. Die Originalwerte bleiben.» */
    fun recalScope(minutes: Int): String =
        "${minutes(minutes)} ${if (minutes == 1) "wird" else "werden"} mit der neuen Kalibrierung umgerechnet. Die Originalwerte bleiben."

    fun recalDone(minutes: Int): String = "${minutes(minutes)} angepasst."
}
