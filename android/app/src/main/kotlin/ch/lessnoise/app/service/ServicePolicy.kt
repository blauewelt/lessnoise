package ch.lessnoise.app.service

import java.util.Locale

/**
 * What the measurement service does when the app's task is swiped away (Service.onTaskRemoved).
 * Pure logic, unit-tested on the JVM.
 */
object TaskRemovedPolicy {
    enum class Action {
        /** Default: the measurement continues as a foreground service; the notification shows it. */
        KEEP_RUNNING,
        /** Stop exactly like «Stopp» (only if [onTaskRemoved] is told to; since 0.6.1 the app never is). */
        STOP,
    }

    /**
     * @param stopOnTaskRemoved stop when swiped away; fixed false since 0.6.1 (AppSettings.stopOnTaskRemoved)
     * @param running a measurement is running
     * @param starting a measurement is starting (model loading); stopping cancels the start
     */
    fun onTaskRemoved(stopOnTaskRemoved: Boolean, running: Boolean, starting: Boolean): Action =
        if (stopOnTaskRemoved && (running || starting)) Action.STOP else Action.KEEP_RUNNING
}

/** Texts of the foreground notification shown while measuring. */
object MeasurementNotification {
    const val TITLE = "Mikrofon aktiv – Messung läuft"
    const val STARTING = "Messung startet …"
    const val NO_PERMISSION = "Mikrofon-Berechtigung fehlt"
    const val SILENCED = "Mikrofon stumm (z. B. Anruf) – zählt nicht"

    /**
     * Content text: the running 1-min LAeq as «Letzte Minute»; [note] is appended (Labor build).
     * [calibrated] is no longer shown (0.6.1: «Richtwerte» is said once, under Einstellungen › Datenschutz).
     */
    fun text(laeqDb: Double, @Suppress("UNUSED_PARAMETER") calibrated: Boolean, silenced: Boolean, note: String? = null): String {
        val base = if (silenced) SILENCED else String.format(Locale.GERMANY, "Letzte Minute: %.1f dB(A)", laeqDb)
        return if (note.isNullOrEmpty()) base else "$base · $note"
    }
}
