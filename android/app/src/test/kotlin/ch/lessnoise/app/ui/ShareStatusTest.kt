package ch.lessnoise.app.ui

import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.ShareDecision
import ch.lessnoise.app.upload.Uploader
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sharing status line on «Messen» and «Teilen» (0.6.3), every state. */
class ShareStatusTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val start = ZonedDateTime.of(2026, 10, 15, 16, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val sentAt = ZonedDateTime.of(2026, 10, 15, 16, 12, 30, 0, zone).toInstant().toEpochMilli()

    private val on = ShareStatusInput(decision = ShareDecision.YES, enabled = true)
    private val running = on.copy(running = true, measurementStartMs = start)

    private fun st(i: ShareStatusInput) = shareStatus(i, zone)

    @Test
    fun offOrUndecidedOffersSharing() {
        for (d in ShareDecision.entries) {
            for (r in listOf(false, true)) {
                val s = st(ShareStatusInput(decision = d, enabled = false, running = r, measurementStartMs = start))
                assertEquals(ShareStatus("Nicht auf der Karte – tippen zum Teilen", ShareTap.ENABLE), s)
            }
        }
    }

    @Test
    fun runningWithoutPoint() {
        assertEquals(ShareStatus("Standort wird bestimmt …", ShareTap.NONE), st(running.copy(locate = LocateState.BUSY)))
        assertEquals(LocationPolicy.PROGRESS_TEXT, ShareStatusTexts.LOCATING)
        for (l in listOf(LocateState.FAILED, LocateState.IDLE)) {
            assertEquals(ShareStatus("Kein Standort – tippen", ShareTap.LOCATION_OPTIONS, problem = true), st(running.copy(locate = l)))
        }
    }

    @Test
    fun runningWithPointBeforeAndAfterTheFirstAcknowledgement() {
        val withPoint = running.copy(hasPoint = true)
        assertEquals(ShareStatus("Wird geteilt – erste Werte in wenigen Minuten", ShareTap.NONE), st(withPoint))
        // Something was sent before, but nothing of this measurement yet (the markers lie before its start).
        assertEquals(ShareTap.NONE, st(withPoint.copy(lastSuccessAtMs = sentAt, ackUpToMs = start - 3_600_000L)).tap)
        assertEquals("Wird geteilt – erste Werte in wenigen Minuten", st(withPoint.copy(lastSuccessAtMs = sentAt, ackUpToMs = start - 3_600_000L)).text)
        // The first minute of this measurement (it may start a few seconds before the row) was acknowledged.
        val acked = st(withPoint.copy(lastSuccessAtMs = sentAt, ackUpToMs = start - 2_000L))
        assertEquals(ShareStatus("Auf der Karte · zuletzt gesendet 16:12", ShareTap.NONE, mapLink = true), acked)
    }

    @Test
    fun failedUploadsSayWhyAndThatTheyAreRetried() {
        val withPoint = running.copy(hasPoint = true, lastSuccessAtMs = sentAt, ackUpToMs = start + 60_000L)
        assertEquals(ShareStatus("Kein Internet – wird nachgeholt", ShareTap.NONE, problem = true), st(withPoint.copy(online = false)))
        assertEquals("Kein Internet – wird nachgeholt", st(withPoint.copy(lastError = Uploader.NO_NETWORK_TEXT)).text)
        assertEquals("Nicht gesendet: Server nicht erreichbar – wird nachgeholt", st(withPoint.copy(lastError = Uploader.RETRY_TEXT)).text)
        assertEquals("Nicht gesendet: vom Server abgelehnt – wird nachgeholt", st(withPoint.copy(lastError = "Vom Server abgelehnt (422).")).text)
        assertTrue(st(withPoint.copy(lastError = Uploader.RETRY_TEXT)).problem)
        // 401: the server deleted the data; «Neu starten» is in «Teilen».
        assertEquals(
            ShareStatus("Unterbrochen: Daten nach 60 Tagen gelöscht – tippen", ShareTap.OPEN_SHARE, problem = true),
            st(withPoint.copy(authFailed = true, lastError = Uploader.AUTH_FAILED_TEXT)),
        )
    }

    @Test
    fun idleShortForms() {
        assertEquals(ShareStatus("Teilen ist ein – die nächste Messung kommt auf die Karte", ShareTap.NONE), st(on))
        assertEquals(ShareStatus("Auf der Karte · zuletzt gesendet 16:12", ShareTap.NONE, mapLink = true), st(on.copy(lastSuccessAtMs = sentAt)))
        assertEquals("Nicht gesendet: Server nicht erreichbar – wird nachgeholt", st(on.copy(lastSuccessAtMs = sentAt, lastError = Uploader.RETRY_TEXT)).text)
        assertEquals(ShareTap.OPEN_SHARE, st(on.copy(authFailed = true)).tap)
    }

    @Test
    fun textsAreShortAndInTheAppStyle() {
        val all = listOf(
            ShareStatusTexts.OFF, ShareStatusTexts.LOCATING, ShareStatusTexts.NO_LOCATION, ShareStatusTexts.PENDING,
            ShareStatusTexts.ON_IDLE, ShareStatusTexts.NO_INTERNET, ShareStatusTexts.AUTH_FAILED, ShareStatusTexts.MAP_LINK,
            ShareStatusTexts.onMap("16:12"), ShareStatusTexts.notSent("Server nicht erreichbar"),
        )
        for (t in all) {
            assertTrue(t.length <= 60, "$t (${t.length})")
            assertFalse(t.contains('ß') || Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
        }
        assertEquals("https://wenigerlaerm.ch/karte.html", Links.MAP)
    }
}
