package ch.lessnoise.app.ui

import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.app.upload.Uploader

// The one status line about the map on «Messen» and «Teilen» (0.6.3, Karten editions only; since 0.8.3
// drawn as a chip with an icon, StatusChip in MeasureScreen.kt). Pure, so that ShareStatusTest checks
// every state (text, tap, icon) on the JVM; ShareFlow.kt feeds it and acts on the tap.

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

/**
 * The leading icon of the status chip (0.8.3; the chip replaced the beige/pink card). Which Material
 * icon each one draws is in MeasureScreen.kt (`statusIcon`); [LOCATING] also shows a small spinner.
 */
enum class StatusIcon {
    /** «cloud_done»: the values are on the map. */
    ON_MAP,

    /** «cloud_upload»: sharing is on, values are (about to be) sent. */
    SHARING,

    /** «cloud_off»: not on the map, or not sent (no internet, server, interrupted). */
    OFF_MAP,

    /** «my_location» with a spinner: the one fix is being taken. */
    LOCATING,

    /** «location_off»: no location for the running measurement. */
    NO_LOCATION,

    /** «directions_walk»: «Unterwegs» (started so, or recognised): nothing goes on the map. */
    UNTERWEGS,
}

/**
 * One status chip: its text, what a tap does, its icon, and whether it reports a problem (icon and
 * outline in the error colour).
 */
data class ShareStatus(val text: String, val action: ShareAction, val icon: StatusIcon, val problem: Boolean = false)

/**
 * The status line for the current state.
 *
 * - [running]: a measurement runs; [measurementStartMs] its start, [hasPoint] whether it has its
 *   shifted point (= it is shared).
 * - [locate]: the fix for the running measurement.
 * - [online]: the phone has a connection (ConnectivityManager); only used while a shared
 *   measurement runs.
 * - [unterwegs]: the running measurement was started as «Unterwegs» (0.6.4): it is never
 *   shared, whatever the switch says ([STATUS_UNTERWEGS]).
 * - [moving] (0.6.7): the motion detector says the phone is being carried during a measurement
 *   started with «Messen» ([STATUS_MOVING] while it lasts; nothing of that time is shared).
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
    unterwegs: Boolean = false,
    moving: Boolean = false,
    hm: (Long) -> String,
): ShareStatus? {
    // Uploader.enable() records YES and disable() NO, so «on» is the switch.
    val on = s.enabled
    if (!running) {
        return when {
            !on && s.shareDecision == ShareDecision.UNDECIDED -> null
            !on -> ShareStatus(STATUS_OFF, ShareAction.OPEN_SHARE, StatusIcon.OFF_MAP)
            s.authFailed -> ShareStatus(STATUS_AUTH, ShareAction.OPEN_SHARE, StatusIcon.OFF_MAP, problem = true)
            s.lastSuccessAtMs != null -> ShareStatus(statusSent(hm(s.lastSuccessAtMs)), ShareAction.OPEN_MAP, StatusIcon.ON_MAP)
            else -> ShareStatus(STATUS_ON_IDLE, ShareAction.NONE, StatusIcon.SHARING)
        }
    }
    if (unterwegs) return ShareStatus(STATUS_UNTERWEGS, ShareAction.NONE, StatusIcon.UNTERWEGS)
    if (moving) return ShareStatus(STATUS_MOVING, ShareAction.NONE, StatusIcon.UNTERWEGS)
    // 0.8.3: a tap opens «Teilen» (its switch asks «Auf die Lärmkarte?» if it was never answered and
    // locates the running measurement, ShareController.switchOn); until 0.8.2 it asked here (TURN_ON).
    if (!on) return ShareStatus(STATUS_OFF, ShareAction.OPEN_SHARE, StatusIcon.OFF_MAP)
    if (!hasPoint) {
        return when (locate) {
            is LocateState.Failed -> ShareStatus(STATUS_NO_POINT, ShareAction.LOCATION_OPTIONS, StatusIcon.NO_LOCATION, problem = true)
            LocateState.Locating, LocateState.Pending -> ShareStatus(STATUS_LOCATING, ShareAction.NONE, StatusIcon.LOCATING)
            // Sharing is on but nobody asked for a fix (e.g. the app was closed while locating and
            // the process restarted): offer it.
            LocateState.Idle -> ShareStatus(STATUS_NO_POINT, ShareAction.LOCATION_OPTIONS, StatusIcon.NO_LOCATION, problem = true)
        }
    }
    val error = s.lastError
    return when {
        s.authFailed -> ShareStatus(STATUS_AUTH, ShareAction.OPEN_SHARE, StatusIcon.OFF_MAP, problem = true)
        !online || error == Uploader.NO_CONNECTION_TEXT -> ShareStatus(STATUS_NO_INTERNET, ShareAction.NONE, StatusIcon.OFF_MAP, problem = true)
        error == Uploader.RETRY_TEXT -> ShareStatus(STATUS_SERVER_BUSY, ShareAction.NONE, StatusIcon.OFF_MAP, problem = true)
        error != null -> ShareStatus(STATUS_REJECTED, ShareAction.NONE, StatusIcon.OFF_MAP, problem = true)
        // Acknowledged: the markers moved into this measurement only after its point was attached
        // (Uploader.pointAttached moves them back before its start), so its records were sent.
        s.lastSuccessAtMs != null && s.minutesSentUpToMs >= measurementStartMs ->
            ShareStatus(statusSent(hm(s.lastSuccessAtMs)), ShareAction.OPEN_MAP, StatusIcon.ON_MAP)
        else -> ShareStatus(STATUS_FIRST_VALUES, ShareAction.NONE, StatusIcon.SHARING)
    }
}

/**
 * The banner above the map (0.6.3 map tab fix): its text, what a tap does (the status line's action)
 * and, since 0.8.3 (drawn as the same status chip as on «Messen»), its icon.
 */
data class MapBanner(val text: String, val action: ShareAction, val icon: StatusIcon)

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
    unterwegs: Boolean = false,
    moving: Boolean = false,
    hm: (Long) -> String,
): MapBanner? {
    // «Unterwegs» (0.6.4): this measurement does not come on the map, whatever else holds.
    if (running && unterwegs) return MapBanner(BANNER_UNTERWEGS, ShareAction.NONE, StatusIcon.UNTERWEGS)
    // «Unterwegs erkannt» (0.6.7): this time span does not come on the map.
    if (running && moving) return MapBanner(STATUS_MOVING, ShareAction.NONE, StatusIcon.UNTERWEGS)
    // Null only before the question was asked (not running, undecided): a tap opens «Teilen» (0.8.3,
    // as the status line; until 0.8.2 it asked the question here).
    // «Open the map» is a no-op on the map itself: no tap, no «›».
    val action = (shareStatus(s, running, measurementStartMs, hasPoint, locate, online, hm = hm)?.action ?: ShareAction.OPEN_SHARE)
        .takeIf { it != ShareAction.OPEN_MAP } ?: ShareAction.NONE
    if (!s.enabled) return MapBanner(BANNER_NOT_SHARING, action, StatusIcon.OFF_MAP)
    if (!hasPoint && !hasAnyPoint) {
        val searching = locate == LocateState.Locating || locate == LocateState.Pending
        return MapBanner(BANNER_NO_POINT, action, if (searching) StatusIcon.LOCATING else StatusIcon.NO_LOCATION)
    }
    val ackAt = if (running && hasPoint) {
        s.lastSuccessAtMs?.takeIf { s.minutesSentUpToMs >= measurementStartMs }
    } else {
        s.lastSuccessAtMs
    }
    return when {
        ackAt == null -> MapBanner(BANNER_FIRST_VALUES, action, StatusIcon.SHARING)
        nowMs - ackAt < BANNER_SENT_MS -> MapBanner(bannerSent(hm(ackAt)), action, StatusIcon.ON_MAP)
        else -> null
    }
}
