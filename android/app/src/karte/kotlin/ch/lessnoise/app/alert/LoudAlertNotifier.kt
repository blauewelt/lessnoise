package ch.lessnoise.app.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ch.lessnoise.app.service.LiveEvent
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.service.MeasurementService
import ch.lessnoise.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The «Sehr laut» alert. Since 0.7.6 it is ONE notification: the alert replaces the content of the
 * running measurement's notification (id 1, MeasurementService) for 30 s
 * (MeasurementService.LOUD_ALERT_WINDOW_MS) instead of posting a second notification next to
 * «Messung läuft». [show] hands the alert to the service (ACTION_LOUD_ALERT); the service posts its
 * notification once on this channel (importance HIGH: heads-up, the default sound and vibration,
 * adjustable in Android's settings), with the title «Sehr laut: …», «Antippen zum Melden», the tap as
 * content intent and as a «Melden» button next to «Stopp»; after the window the level text comes back
 * on the silent measurement channel. A tap opens «Laut! Melden» with the event's time
 * ([LoudAlertIntent]). Without the POST_NOTIFICATIONS permission (Android 13+) nothing shows.
 * Until 0.7.5 this class posted its own notification (id 7501, auto cancel).
 */
class LoudAlertNotifier(private val context: Context) {
    private val nm: NotificationManager = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, LoudAlertRule.CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Meldung bei sehr lautem Verkehr, wenn unterwegs gemessen wird"
            },
        )
    }

    fun show(e: LiveEvent) {
        // startService can throw (IllegalStateException) if the service is not in the foreground; the
        // alert rule already requires a running measurement, so then there is nothing to alert in.
        runCatching {
            ensureChannel()
            val tap = Intent(context, MainActivity::class.java)
                .setAction(LoudAlertIntent.ACTION)
                .putExtra(LoudAlertIntent.EXTRA_EVENT_MS, e.startEpochMs)
                .putExtra(LoudAlertIntent.EXTRA_CATEGORY, e.category)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pending = PendingIntent.getActivity(
                context, REQUEST, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            context.startService(
                Intent(context, MeasurementService::class.java)
                    .setAction(MeasurementService.ACTION_LOUD_ALERT)
                    .putExtra(MeasurementService.EXTRA_ALERT_TITLE, LoudAlertRule.title(e))
                    .putExtra(MeasurementService.EXTRA_ALERT_CHANNEL, CHANNEL)
                    .putExtra(MeasurementService.EXTRA_ALERT_TAP, pending),
            )
        }
    }

    companion object {
        const val CHANNEL = "loud_alert"
        private const val REQUEST = 2
    }
}

/** Watches the live state: an event that passes [LoudAlertRule] shows the alert (in the measurement's notification since 0.7.6). */
object LoudAlertWatcher {
    fun start(scope: CoroutineScope, live: StateFlow<LiveState>, notifier: LoudAlertNotifier) {
        scope.launch {
            var lastAlertAtMs: Long? = null
            live.map { it.lastEvent }.distinctUntilChanged().collect { e ->
                if (e == null) return@collect
                val now = System.currentTimeMillis()
                val l = live.value
                // «Unterwegs» is the mode of the running measurement (0.6.4), not a setting; since
                // 0.6.7 also a «Messen» measurement while the phone is being carried (LiveState.moving).
                if (LoudAlertRule.shouldAlert(e, LoudAlertRule.unterwegsNow(l.unterwegs, l.moving), l.running, lastAlertAtMs, now)) {
                    lastAlertAtMs = now
                    notifier.show(e)
                }
            }
        }
    }
}
