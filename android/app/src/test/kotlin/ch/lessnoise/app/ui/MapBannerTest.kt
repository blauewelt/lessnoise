package ch.lessnoise.app.ui

import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.UploadSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.3 map tab fix: the banner above the map while this phone's values are not visible yet (mapBanner). */
class MapBannerTest {
    private val start = 1_000_000_000L
    private val min = 60_000L
    private val on = UploadSnapshot(enabled = true, shareDecision = ShareDecision.YES, deviceId = "d", token = "t")
    private val hm: (Long) -> String = { "14:05" }

    private fun banner(
        s: UploadSnapshot,
        running: Boolean = true,
        hasPoint: Boolean = true,
        hasAnyPoint: Boolean = hasPoint,
        locate: LocateState = LocateState.Idle,
        online: Boolean = true,
        now: Long = start + 30 * min,
        unterwegs: Boolean = false,
        moving: Boolean = false,
    ) = mapBanner(s, running, if (running) start else 0, hasPoint, hasAnyPoint, locate, online, now, unterwegs, moving, hm)

    /** The status line's action for the same state (a tap on the banner does the same). */
    private fun lineAction(s: UploadSnapshot, running: Boolean = true, hasPoint: Boolean = true, locate: LocateState = LocateState.Idle) =
        shareStatus(s, running, if (running) start else 0, hasPoint, locate, true, hm = hm)?.action

    @Test
    fun notSharing() {
        for (s in listOf(UploadSnapshot(shareDecision = ShareDecision.NO), UploadSnapshot(shareDecision = ShareDecision.UNDECIDED))) {
            for (running in listOf(true, false)) {
                for (any in listOf(true, false)) {
                    assertEquals(MapBanner(BANNER_NOT_SHARING, ShareAction.OPEN_SHARE, StatusIcon.OFF_MAP), banner(s, running = running, hasPoint = false, hasAnyPoint = any))
                }
            }
        }
        // Before the question (not running, undecided) the status line is empty, the banner still offers
        // «Teilen» (0.8.3; until 0.8.2 it asked the question itself, TURN_ON).
        assertNull(lineAction(UploadSnapshot(), running = false))
        assertEquals(ShareAction.OPEN_SHARE, banner(UploadSnapshot(), running = false, hasPoint = false)!!.action)
    }

    @Test
    fun noPointYet() {
        assertEquals(MapBanner(BANNER_NO_POINT, ShareAction.NONE, StatusIcon.LOCATING), banner(on, hasPoint = false, locate = LocateState.Locating))
        assertEquals(
            MapBanner(BANNER_NO_POINT, ShareAction.LOCATION_OPTIONS, StatusIcon.NO_LOCATION),
            banner(on, hasPoint = false, locate = LocateState.Failed(LocationPolicy.Outcome.LocationOff)),
        )
        // Not measuring, sharing on, never had a point.
        assertEquals(MapBanner(BANNER_NO_POINT, ShareAction.NONE, StatusIcon.NO_LOCATION), banner(on, running = false, hasPoint = false))
    }

    @Test
    fun pointButNothingAcknowledged() {
        assertEquals(MapBanner(BANNER_FIRST_VALUES, ShareAction.NONE, StatusIcon.SHARING), banner(on))
        // An upload of an EARLIER measurement does not count for the running one (as the status line).
        val earlier = on.copy(lastSuccessAtMs = start - 5 * min, minutesSentUpToMs = start - 6 * min)
        assertEquals(BANNER_FIRST_VALUES, banner(earlier, now = start + 2 * min)!!.text)
        // Not running, the newest shared measurement has a point, nothing ever acknowledged.
        assertEquals(BANNER_FIRST_VALUES, banner(on, running = false, hasPoint = false, hasAnyPoint = true)!!.text)
    }

    @Test
    fun acknowledgedLessThan2MinutesAgoThenHidden() {
        val acked = on.copy(lastSuccessAtMs = start + 20 * min, minutesSentUpToMs = start + 19 * min)
        val shown = banner(acked, now = start + 20 * min)!!
        assertEquals(bannerSent("14:05"), shown.text)
        assertEquals("Gesendet 14:05 · die Karte aktualisiert sich laufend", shown.text)
        assertEquals(StatusIcon.ON_MAP, shown.icon)
        // The status line opens the map; on the map that is nothing to do (no «›»).
        assertEquals(ShareAction.OPEN_MAP, lineAction(acked))
        assertEquals(ShareAction.NONE, shown.action)
        assertEquals(shown, banner(acked, now = start + 21 * min + 59_999))
        assertNull(banner(acked, now = start + 22 * min))
        assertNull(banner(acked, now = start + 3 * 60 * min))
        // After «Stoppen»: the last upload counts.
        assertEquals(bannerSent("14:05"), banner(acked, running = false, hasPoint = false, hasAnyPoint = true, now = start + 21 * min)!!.text)
        assertNull(banner(acked, running = false, hasPoint = false, hasAnyPoint = true, now = start + 23 * min))
        assertEquals(2 * min, BANNER_SENT_MS)
    }

    @Test
    fun theTapIsAlwaysTheStatusLinesAction() {
        val states = listOf(
            on, on.copy(lastError = ch.lessnoise.app.upload.Uploader.NO_CONNECTION_TEXT), on.copy(authFailed = true),
            on.copy(lastSuccessAtMs = start + 29 * min, minutesSentUpToMs = start + 28 * min),
        )
        for (s in states) for (running in listOf(true, false)) for (hasPoint in listOf(true, false)) {
            for (locate in listOf(LocateState.Idle, LocateState.Locating, LocateState.Failed(LocationPolicy.Outcome.NoFix))) {
                val b = banner(s, running = running, hasPoint = hasPoint, hasAnyPoint = true, locate = locate) ?: continue
                val line = lineAction(s, running, hasPoint, locate) ?: ShareAction.TURN_ON
                assertEquals(if (line == ShareAction.OPEN_MAP) ShareAction.NONE else line, b.action, "$s $running $hasPoint $locate")
            }
        }
    }

    @Test
    fun textsAreShortAndUseDu() {
        val texts = listOf(BANNER_NOT_SHARING, BANNER_NO_POINT, BANNER_FIRST_VALUES, bannerSent("14:05"))
        assertEquals(
            listOf(
                "Du teilst nicht · tippen zum Teilen",
                "Noch kein Standort · Werte erscheinen danach",
                "Erste Werte werden gesendet · in etwa 1 Minute auf der Karte",
                "Gesendet 14:05 · die Karte aktualisiert sich laufend",
            ),
            texts,
        )
        for (t in texts) assertTrue(t.length <= 70 && " Sie " !in t, t)
    }

    @Test
    fun anUnterwegsMeasurementShowsThatItIsNotOnTheMap() {
        // 0.6.4: also with sharing on and an older shared point the map is centred on.
        for (st in listOf(on, UploadSnapshot())) {
            assertEquals(MapBanner(BANNER_UNTERWEGS, ShareAction.NONE, StatusIcon.UNTERWEGS), banner(st, hasPoint = false, hasAnyPoint = true, unterwegs = true))
        }
    }

    @Test
    fun aMessenMeasurementWhileMovingShowsItIsNotOnTheMap() {
        // 0.6.7 «Unterwegs erkannt»: the banner says the same sentence as the status line.
        for (st in listOf(on, UploadSnapshot())) {
            assertEquals(MapBanner(STATUS_MOVING, ShareAction.NONE, StatusIcon.UNTERWEGS), banner(st, hasPoint = true, hasAnyPoint = true, moving = true))
        }
        assertEquals(MapBanner(BANNER_UNTERWEGS, ShareAction.NONE, StatusIcon.UNTERWEGS), banner(on, hasPoint = false, hasAnyPoint = true, unterwegs = true, moving = true))
    }
}
