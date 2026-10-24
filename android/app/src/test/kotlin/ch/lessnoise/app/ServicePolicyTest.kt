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
        // 0.6.9 (Labor·Karte): the tap's target and the «Melden» button re-post once when they change.
        assertEquals(true, c.tapChanged(contentTap = false, button = true))
        assertEquals(false, c.tapChanged(contentTap = false, button = true))
        assertEquals(true, c.tapChanged(contentTap = true, button = true))
        assertEquals(false, c.levelDue(t + 11_500), "the level cadence is not reset by a tap change")
    }

    @Test
    fun aLoudAlertWindowHoldsBackTheLevelPostsAndTheFirstPostAfterItIsForced() {
        // 0.7.6 (editions with the map): the «Sehr laut» alert replaces notification 1 for 30 s.
        val c = ch.lessnoise.app.service.NotificationCadence(2000)
        val t = 1_000_000L
        assertEquals(true, c.levelDue(t))
        assertEquals(false, c.alertOpen(t))
        // The alert at t + 1 s, window until t + 31 s: the service posts it once.
        assertEquals(true, c.alertUntil(t + 1_000, t + 31_000))
        assertEquals(true, c.alertOpen(t + 1_000))
        // Window open: not one level post, although the 2-s cadence would have posted 15 times.
        assertEquals(emptyList(), (2..30).filter { c.levelDue(t + it * 1000L) })
        assertEquals(true, c.alertOpen(t + 30_999))
        assertEquals(false, c.alertOpen(t + 31_000))
        // Window ended: the first post comes at once, then every 2 s as before.
        assertEquals(true, c.levelDue(t + 31_000), "the normal notification is restored at once")
        assertEquals(false, c.levelDue(t + 32_000))
        assertEquals(true, c.levelDue(t + 33_000))
        // A window that already ended is no window.
        assertEquals(false, c.alertUntil(t + 40_000, t + 40_000))
        assertEquals(true, c.levelDue(t + 41_000))
        // Forced even where the 2-s cadence would skip it: level post at t + 41 s, a short window
        // t + 41.5 s … t + 42 s; at t + 42 s the cadence alone would say «not yet» (1 s < 2 s).
        assertEquals(true, c.alertUntil(t + 41_500, t + 42_000))
        assertEquals(true, c.levelDue(t + 42_000), "forced after the window")
        assertEquals(false, c.levelDue(t + 43_000))
        assertEquals(true, c.levelDue(t + 44_000))
    }

    @Test
    fun aTapChangeDuringTheWindowIsAppliedByThePostAfterIt() {
        val c = ch.lessnoise.app.service.NotificationCadence(2000)
        val t = 2_000_000L
        assertEquals(true, c.alertUntil(t, t + 30_000))
        // The run becomes «unterwegs» during the window (Online-Version: report mode): the tap watcher
        // records the change, sees the open window and posts nothing.
        assertEquals(true, c.modeChanged(true))
        assertEquals(true, c.alertOpen(t + 10_000))
        assertEquals(false, c.levelDue(t + 10_000))
        // After the window ONE post restores the notification (with the tap's fixed text, report mode),
        // then nothing per second in report mode, as 0.6.8.
        assertEquals(true, c.levelDue(t + 30_000), "restored once, also in report mode")
        assertEquals(emptyList(), (31..60).filter { c.levelDue(t + it * 1000L) })
        // «Stopp» ends a window: nothing is restored afterwards (the notification is gone).
        assertEquals(true, c.alertUntil(t + 61_000, t + 91_000))
        c.endAlert()
        assertEquals(false, c.alertOpen(t + 62_000))
        assertEquals(false, c.levelDue(t + 62_000), "still report mode, nothing to restore")
    }
}
