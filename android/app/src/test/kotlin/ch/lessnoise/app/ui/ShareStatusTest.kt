package ch.lessnoise.app.ui

import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.UploadSnapshot
import ch.lessnoise.app.upload.Uploader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.3: the status line on «Messen» and «Teilen», every state (ShareStatus.kt). */
class ShareStatusTest {
    private val start = 1_000_000L
    private val on = UploadSnapshot(enabled = true, shareDecision = ShareDecision.YES, deviceId = "d", token = "t")
    private val hm: (Long) -> String = { "14:05" }

    private fun status(
        s: UploadSnapshot,
        running: Boolean = true,
        hasPoint: Boolean = true,
        locate: LocateState = LocateState.Idle,
        online: Boolean = true,
    ) = shareStatus(s, running, if (running) start else 0, hasPoint, locate, online, hm)

    @Test
    fun offOrNoShowsTheInvitationWhichAsksTheQuestion() {
        for (s in listOf(UploadSnapshot(shareDecision = ShareDecision.NO), UploadSnapshot(shareDecision = ShareDecision.UNDECIDED))) {
            assertEquals(ShareStatus(STATUS_OFF, ShareAction.TURN_ON), status(s, hasPoint = false))
        }
        // Idle: the short form only after a «no»; before the first «Starten» nothing (the question comes with it).
        assertEquals(ShareStatus(STATUS_OFF, ShareAction.TURN_ON), status(UploadSnapshot(shareDecision = ShareDecision.NO), running = false))
        assertNull(status(UploadSnapshot(), running = false))
    }

    @Test
    fun withoutPointLocatingOrProblem() {
        assertEquals(ShareStatus(STATUS_LOCATING, ShareAction.NONE), status(on, hasPoint = false, locate = LocateState.Locating))
        assertEquals(ShareStatus(STATUS_LOCATING, ShareAction.NONE), status(on, hasPoint = false, locate = LocateState.Pending))
        for (o in listOf(LocationPolicy.Outcome.PermissionDenied, LocationPolicy.Outcome.LocationOff, LocationPolicy.Outcome.NoFix, LocationPolicy.Outcome.ApproximateOnly)) {
            assertEquals(
                ShareStatus(STATUS_NO_POINT, ShareAction.LOCATION_OPTIONS, problem = true),
                status(on, hasPoint = false, locate = LocateState.Failed(o)),
            )
        }
        // Nobody is locating (e.g. the process restarted): offer the options too.
        assertEquals(ShareAction.LOCATION_OPTIONS, status(on, hasPoint = false, locate = LocateState.Idle)!!.action)
    }

    @Test
    fun sharedFirstValuesThenSentWithTime() {
        assertEquals(ShareStatus(STATUS_FIRST_VALUES, ShareAction.NONE), status(on))
        // Something was sent before, but nothing of this measurement yet (marker before its start).
        assertEquals(STATUS_FIRST_VALUES, status(on.copy(lastSuccessAtMs = start - 5, minutesSentUpToMs = start - 60_000))!!.text)
        // Acknowledged: the marker moved into this measurement.
        assertEquals(
            ShareStatus("Auf der Karte · zuletzt gesendet 14:05", ShareAction.OPEN_MAP),
            status(on.copy(lastSuccessAtMs = start + 180_000, minutesSentUpToMs = start + 60_000)),
        )
    }

    @Test
    fun failuresSayWhatAndThatItIsRetried() {
        assertEquals(ShareStatus(STATUS_NO_INTERNET, ShareAction.NONE, problem = true), status(on, online = false))
        assertEquals(STATUS_NO_INTERNET, status(on.copy(lastError = Uploader.NO_CONNECTION_TEXT))!!.text)
        assertEquals(STATUS_SERVER_BUSY, status(on.copy(lastError = Uploader.RETRY_TEXT))!!.text)
        assertEquals(STATUS_REJECTED, status(on.copy(lastError = "Vom Server abgelehnt (422)."))!!.text)
        // 401: the existing «Neu starten» path in «Teilen».
        assertEquals(ShareStatus(STATUS_AUTH, ShareAction.OPEN_SHARE, problem = true), status(on.copy(authFailed = true)))
        assertEquals(ShareAction.OPEN_SHARE, status(on.copy(authFailed = true), running = false)!!.action)
        // Offline while idle: nothing waits, no alarm.
        assertFalse(status(on, running = false, online = false)!!.problem)
    }

    @Test
    fun idleShortForm() {
        assertEquals(ShareStatus(STATUS_ON_IDLE, ShareAction.NONE), status(on, running = false))
        assertEquals(ShareStatus("Auf der Karte · zuletzt gesendet 14:05", ShareAction.OPEN_MAP), status(on.copy(lastSuccessAtMs = 5), running = false))
    }

    @Test
    fun textsAreShortSwissGermanWithDu() {
        val all = listOf(
            STATUS_OFF, STATUS_LOCATING, STATUS_NO_POINT, STATUS_FIRST_VALUES, STATUS_ON_IDLE, STATUS_NO_INTERNET,
            STATUS_SERVER_BUSY, STATUS_REJECTED, STATUS_AUTH, statusSent("14:05"),
        )
        for (t in all) {
            assertTrue(t.length <= 60, "too long for one line: $t")
            assertFalse(t.contains('ß') || t.contains('"') || Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
            assertFalse(t.endsWith("."), t)
        }
        assertEquals(LocationPolicy.PROGRESS_TEXT, STATUS_LOCATING)
    }
}
