package ch.lessnoise.app.ui

import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.app.upload.Uploader

// The one status line about the map on «Messen» and «Teilen» (0.6.3, Karten editions only). Pure,
// so that ShareStatusTest checks every state on the JVM; ShareFlow.kt feeds it and acts on the tap.

/** Where the fix for the running measurement stands (kept for the process in AppContainer.shareLocate). */
sealed interface LocateState {
    /** Nothing to do. */
    data object Idle : LocateState

    /** A fix is wanted, but the app was not in the foreground: it is tried when the app is opened again. */
    data object Pending : LocateState

    /** The one fix is running (only while the app is open). */
    data object Locating : LocateState

    /** No usable fix; [outcome] says why (never [LocationPolicy.Outcome.Shifted]). */
    data class Failed(val outcome: LocationPolicy.Outcome) : LocateState
}

/** What a tap on the status line does. */
enum class ShareAction {
    /** Nothing. */
    NONE,

    /** «Auf die Lärmkarte?», then sharing on and the running measurement located (flow C). */
    TURN_ON,

    /** The location options: «Nochmals», «Standort einschalten», «App-Einstellungen», «Koordinaten einfügen». */
    LOCATION_OPTIONS,

    /** Open the «Karte» tab. */
    OPEN_MAP,

    /** Open the «Teilen» tab (for «Neu starten»). */
    OPEN_SHARE,
}

/** One line: its text, what a tap does, and whether it reports a problem (shown in the error colour). */
data class ShareStatus(val text: String, val action: ShareAction, val problem: Boolean = false)

/**
 * The status line for the current state.
 *
 * - [running]: a measurement runs; [measurementStartMs] its start, [hasPoint] whether it has its
 *   shifted point (= it is shared).
 * - [locate]: the fix for the running measurement.
 * - [online]: the phone has a connection (ConnectivityManager); only used while a shared
 *   measurement runs.
 * - [hm]: formats a time as «HH:mm».
 *
 * Null: nothing to say (no measurement runs and the question «Auf die Lärmkarte?» has not been
 * asked yet — it comes with the first «Starten»).
 */
fun shareStatus(
    s: UploadSnapshot,
    running: Boolean,
    measurementStartMs: Long,
    hasPoint: Boolean,
    locate: LocateState,
    online: Boolean,
    hm: (Long) -> String,
): ShareStatus? {
    // Uploader.enable() records YES and disable() NO, so «on» is the switch.
    val on = s.enabled
    if (!running) {
        return when {
            !on && s.shareDecision == ShareDecision.UNDECIDED -> null
            !on -> ShareStatus(STATUS_OFF, ShareAction.TURN_ON)
            s.authFailed -> ShareStatus(STATUS_AUTH, ShareAction.OPEN_SHARE, problem = true)
            s.lastSuccessAtMs != null -> ShareStatus(statusSent(hm(s.lastSuccessAtMs)), ShareAction.OPEN_MAP)
            else -> ShareStatus(STATUS_ON_IDLE, ShareAction.NONE)
        }
    }
    if (!on) return ShareStatus(STATUS_OFF, ShareAction.TURN_ON)
    if (!hasPoint) {
        return when (locate) {
            is LocateState.Failed -> ShareStatus(STATUS_NO_POINT, ShareAction.LOCATION_OPTIONS, problem = true)
            LocateState.Locating, LocateState.Pending -> ShareStatus(STATUS_LOCATING, ShareAction.NONE)
            // Sharing is on but nobody asked for a fix (e.g. the app was closed while locating and
            // the process restarted): offer it.
            LocateState.Idle -> ShareStatus(STATUS_NO_POINT, ShareAction.LOCATION_OPTIONS, problem = true)
        }
    }
    val error = s.lastError
    return when {
        s.authFailed -> ShareStatus(STATUS_AUTH, ShareAction.OPEN_SHARE, problem = true)
        !online || error == Uploader.NO_CONNECTION_TEXT -> ShareStatus(STATUS_NO_INTERNET, ShareAction.NONE, problem = true)
        error == Uploader.RETRY_TEXT -> ShareStatus(STATUS_SERVER_BUSY, ShareAction.NONE, problem = true)
        error != null -> ShareStatus(STATUS_REJECTED, ShareAction.NONE, problem = true)
        // Acknowledged: the markers moved into this measurement only after its point was attached
        // (Uploader.pointAttached moves them back before its start), so its records were sent.
        s.lastSuccessAtMs != null && s.minutesSentUpToMs >= measurementStartMs ->
            ShareStatus(statusSent(hm(s.lastSuccessAtMs)), ShareAction.OPEN_MAP)
        else -> ShareStatus(STATUS_FIRST_VALUES, ShareAction.NONE)
    }
}

/** The banner above the map (0.6.3 map tab fix): its text and what a tap does (the status line's action). */
data class MapBanner(val text: String, val action: ShareAction)

/** How long after the last acknowledged upload the banner «Gesendet HH:MM …» stays (the live map publishes within about 30 s). */
const val BANNER_SENT_MS = 2 * 60_000L

/**
 * The banner above the map in the tab «Karte» while this phone's values are not visible there yet
 * (0.6.3 map tab fix; pure, MapBannerTest). The same state as [shareStatus], plus [hasAnyPoint]
 * (the phone has a shifted point: the running measurement's or the newest shared one's — the point
 * the map is centred on) and [nowMs]. A tap does what a tap on the status line on «Messen» does
 * (except «open the map», which is nothing to do on the map: no tap there).
 *
 * - sharing off or not decided yet: [BANNER_NOT_SHARING];
 * - no point: [BANNER_NO_POINT];
 * - a point, but nothing acknowledged by the server yet (for a running measurement with its point:
 *   nothing of this measurement, as in [shareStatus]): [BANNER_FIRST_VALUES];
 * - acknowledged less than [BANNER_SENT_MS] ago: [bannerSent];
 * - acknowledged longer ago: null (no banner; the values are on the map).
 */
fun mapBanner(
    s: UploadSnapshot,
    running: Boolean,
    measurementStartMs: Long,
    hasPoint: Boolean,
    hasAnyPoint: Boolean,
    locate: LocateState,
    online: Boolean,
    nowMs: Long,
    hm: (Long) -> String,
): MapBanner? {
    // Null only before the question was asked (not running, undecided): a tap asks it.
    // «Open the map» is a no-op on the map itself: no tap, no «›».
    val action = (shareStatus(s, running, measurementStartMs, hasPoint, locate, online, hm)?.action ?: ShareAction.TURN_ON)
        .takeIf { it != ShareAction.OPEN_MAP } ?: ShareAction.NONE
    if (!s.enabled) return MapBanner(BANNER_NOT_SHARING, action)
    if (!hasPoint && !hasAnyPoint) return MapBanner(BANNER_NO_POINT, action)
    val ackAt = if (running && hasPoint) {
        s.lastSuccessAtMs?.takeIf { s.minutesSentUpToMs >= measurementStartMs }
    } else {
        s.lastSuccessAtMs
    }
    return when {
        ackAt == null -> MapBanner(BANNER_FIRST_VALUES, action)
        nowMs - ackAt < BANNER_SENT_MS -> MapBanner(bannerSent(hm(ackAt)), action)
        else -> null
    }
}
