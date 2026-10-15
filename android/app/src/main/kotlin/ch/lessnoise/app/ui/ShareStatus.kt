package ch.lessnoise.app.ui

import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.Uploader
import ch.lessnoise.dsp.MeasurementSplitter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The one status line of sharing (app 0.6.3), on «Messen» and at the top of «Teilen» (Karten
// editions only). Pure, so that the JVM tests cover every state (ShareStatusTest).

/** Where the shifted point of the running measurement stands, from the app's point of view. */
enum class LocateState {
    /** No fix running (none needed, or waiting for the app to come to the foreground). */
    IDLE,

    /** One fix is running («Standort wird bestimmt …»). */
    BUSY,

    /** The last fix failed (denied, location off, too inaccurate, none found). */
    FAILED,
}

/** What a tap on the status line does. */
enum class ShareTap {
    /** Nothing. */
    NONE,

    /** «Auf die Lärmkarte?» → switch sharing on (and locate, if a measurement runs). */
    ENABLE,

    /** The options when there is no location: Nochmals, Standort einschalten, App-Einstellungen, Koordinaten einfügen. */
    LOCATION_OPTIONS,

    /** Open the tab «Teilen» (there: «Neu starten» after the server deleted the data). */
    OPEN_SHARE,
}

/** One status line: its text, what a tap does, and whether «Karte ansehen» follows. */
data class ShareStatus(
    val text: String,
    val tap: ShareTap,
    val mapLink: Boolean = false,
    /** Shown as a problem (error colour). */
    val problem: Boolean = false,
)

/** Everything the status line depends on. */
data class ShareStatusInput(
    val decision: ShareDecision,
    val enabled: Boolean,
    val authFailed: Boolean = false,
    /** UploadSnapshot.lastError: the last upload run failed (null: fine). */
    val lastError: String? = null,
    val lastSuccessAtMs: Long? = null,
    /** Start of the newest record the server acknowledged (max of the minute and event markers). */
    val ackUpToMs: Long = 0,
    val running: Boolean = false,
    /** Start of the running measurement (LiveState.measurementStartMs). */
    val measurementStartMs: Long = 0,
    /** The running measurement has its shifted point. */
    val hasPoint: Boolean = false,
    val locate: LocateState = LocateState.IDLE,
    /** The phone has a network connection. */
    val online: Boolean = true,
)

object ShareStatusTexts {
    const val OFF = "Nicht auf der Karte – tippen zum Teilen"
    const val LOCATING = "Standort wird bestimmt …"
    const val NO_LOCATION = "Kein Standort – tippen"
    const val PENDING = "Wird geteilt – erste Werte in wenigen Minuten"
    const val ON_IDLE = "Teilen ist ein – die nächste Messung kommt auf die Karte"
    const val NO_INTERNET = "Kein Internet – wird nachgeholt"
    const val AUTH_FAILED = "Unterbrochen: Daten nach 60 Tagen gelöscht – tippen"
    const val MAP_LINK = "Karte ansehen"

    /** «Auf der Karte · zuletzt gesendet 16:05». */
    fun onMap(hm: String) = "Auf der Karte · zuletzt gesendet $hm"

    /** «Nicht gesendet: Server nicht erreichbar – wird nachgeholt». */
    fun notSent(reason: String) = "Nicht gesendet: $reason – wird nachgeholt"

    /** The short reason for the Uploader's lastError texts. */
    fun reason(lastError: String): String = when {
        lastError == Uploader.RETRY_TEXT -> "Server nicht erreichbar"
        lastError.startsWith("Vom Server abgelehnt") -> "vom Server abgelehnt"
        else -> "Fehler"
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** The status line for [i]; times in [zone]. */
fun shareStatus(i: ShareStatusInput, zone: ZoneId = ZoneId.systemDefault()): ShareStatus {
    val t = ShareStatusTexts
    if (!i.enabled || i.decision != ShareDecision.YES) return ShareStatus(t.OFF, ShareTap.ENABLE)
    // 401: the server deleted this phone's data (60 days without values); «Neu starten» is in «Teilen».
    if (i.authFailed) return ShareStatus(t.AUTH_FAILED, ShareTap.OPEN_SHARE, problem = true)
    val sent = i.lastSuccessAtMs?.let { Instant.ofEpochMilli(it).atZone(zone).format(HM) }
    val failed = i.lastError?.let {
        ShareStatus(if (it == Uploader.NO_NETWORK_TEXT) t.NO_INTERNET else t.notSent(t.reason(it)), ShareTap.NONE, problem = true)
    }
    if (i.running) {
        if (!i.hasPoint) {
            return if (i.locate == LocateState.BUSY) ShareStatus(t.LOCATING, ShareTap.NONE)
            else ShareStatus(t.NO_LOCATION, ShareTap.LOCATION_OPTIONS, problem = true)
        }
        if (!i.online) return ShareStatus(t.NO_INTERNET, ShareTap.NONE, problem = true)
        if (failed != null) return failed
        // Something of THIS measurement was acknowledged: the markers moved into it.
        val acked = sent != null && i.ackUpToMs >= i.measurementStartMs - MeasurementSplitter.START_TOLERANCE_MS
        return if (acked) ShareStatus(t.onMap(sent!!), ShareTap.NONE, mapLink = true) else ShareStatus(t.PENDING, ShareTap.NONE)
    }
    if (failed != null) return failed
    return if (sent != null) ShareStatus(t.onMap(sent), ShareTap.NONE, mapLink = true) else ShareStatus(t.ON_IDLE, ShareTap.NONE)
}
