package ch.lessnoise.app.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import ch.lessnoise.app.R
import ch.lessnoise.app.service.LiveEvent
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The «Sehr laut» notification: its own channel (importance HIGH: heads-up, the default sound and
 * vibration, adjustable in Android's settings), one id (a new one replaces the previous), auto
 * cancel; a tap opens «Laut! Melden» with the event's time ([LoudAlertIntent]). Swiping it away
 * does nothing. Without the POST_NOTIFICATIONS permission (Android 13+) it simply does not show.
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
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_level)
                .setContentTitle(LoudAlertRule.title(e))
                .setContentText(LoudAlertRule.TEXT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setWhen(e.startEpochMs)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val CHANNEL = "loud_alert"
        /** The measurement's notification is 1 (MeasurementService); this one replaces itself. */
        const val NOTIFICATION_ID = 7501
        private const val REQUEST = 2
    }
}

/** Watches the live state: an event that passes [LoudAlertRule] shows the notification. */
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
