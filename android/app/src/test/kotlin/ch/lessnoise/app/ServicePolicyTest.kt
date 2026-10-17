package ch.lessnoise.app

import ch.lessnoise.app.audio.NoAudioTap
import ch.lessnoise.app.data.AppSettings
import ch.lessnoise.app.service.MeasurementNotification
import ch.lessnoise.app.service.TaskRemovedPolicy
import ch.lessnoise.app.service.TaskRemovedPolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Both editions: behaviour when the app is swiped away, and the notification texts. */
class ServicePolicyTest {

    @Test
    fun measurementKeepsRunningWhenTheAppIsSwipedAway() {
        assertFalse(AppSettings().stopOnTaskRemoved)
        val default = AppSettings().stopOnTaskRemoved
        assertEquals(Action.KEEP_RUNNING, TaskRemovedPolicy.onTaskRemoved(default, running = true, starting = false))
        assertEquals(Action.KEEP_RUNNING, TaskRemovedPolicy.onTaskRemoved(default, running = false, starting = true))
    }

    @Test
    fun settingOnStopsARunningOrStartingMeasurement() {
        assertEquals(Action.STOP, TaskRemovedPolicy.onTaskRemoved(true, running = true, starting = false))
        assertEquals(Action.STOP, TaskRemovedPolicy.onTaskRemoved(true, running = false, starting = true))
        // Nothing to stop (e.g. already stopped from the notification).
        assertEquals(Action.KEEP_RUNNING, TaskRemovedPolicy.onTaskRemoved(true, running = false, starting = false))
    }

    @Test
    fun notificationTexts() {
        assertEquals("Mikrofon aktiv – Messung läuft", MeasurementNotification.TITLE)
        assertEquals("Letzte Minute: 42,3 dB(A)", MeasurementNotification.text(42.26, calibrated = true, silenced = false))
        // «unkalibriert» is no longer shown (0.6.1: «Richtwerte» is said once, in Datenschutz).
        assertEquals("Letzte Minute: 42,3 dB(A)", MeasurementNotification.text(42.26, calibrated = false, silenced = false))
        assertEquals(MeasurementNotification.SILENCED, MeasurementNotification.text(42.0, calibrated = true, silenced = true))
        assertEquals(
            "Letzte Minute: 42,3 dB(A) · Aufnahme läuft",
            MeasurementNotification.text(42.26, calibrated = true, silenced = false, note = "Aufnahme läuft"),
        )
    }

    @Test
    fun noAudioTapAddsNothingToTheNotification() {
        assertNull(NoAudioTap.notificationNote)
    }

    @Test
    fun theNotificationCadenceIsEveryTwoSecondsOutsideReportMode() {
        // 0.6.8 (ServicePolicy.kt NotificationCadence): every edition uses it; only the editions with the map ever switch to report mode.
        val c = ch.lessnoise.app.service.NotificationCadence(2000)
        val t = 1_000_000L
        assertEquals(listOf(true, false, true, false, true), (0..4).map { c.levelDue(t + it * 1000L) })
        assertEquals(false, c.modeChanged(false))
        assertEquals(true, c.modeChanged(true))
        assertEquals(false, c.modeChanged(true), "same state: no re-post")
        assertEquals(false, c.levelDue(t + 10_000))
        assertEquals(true, c.modeChanged(false))
        assertEquals(true, c.levelDue(t + 10_500), "the level text comes back at once")
        assertEquals(false, c.levelDue(t + 11_000))
    }
}
