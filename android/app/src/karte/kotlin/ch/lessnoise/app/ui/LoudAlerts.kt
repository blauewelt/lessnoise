package ch.lessnoise.app.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import ch.lessnoise.app.AppContainer
import ch.lessnoise.app.alert.LoudAlertIntent
import ch.lessnoise.app.alert.LoudAlertNotifier
import ch.lessnoise.app.alert.LoudAlertWatcher
import ch.lessnoise.app.alert.ReportTap
import ch.lessnoise.app.service.NotificationTap

/**
 * «Unterwegs messen» and the «Sehr laut» notification, in both editions with the map (src/karte: the
 * Online-Version and Labor·Karte compile this file; the Offline and Labor builds have the stand-in in
 * src/nomap). History: 0.6.3 a switch, 0.6.4 a start mode, 0.8.4–0.9.4.5 Labor·Karte only («Unterwegs
 * aufnehmen», this file in src/laborKarte, the Online-Version with a stand-in in src/nounterwegs); 0.9.4.6
 * (owner: «If the mic is open and you recognise a car, make sure you send a notification if it's loud»)
 * the third way to use the app in both editions.
 *
 * «Unterwegs messen» on «Messen» starts a measurement that is never shared (no point, no minutes, no
 * events: LiveState.unterwegs, MeasurementEntity.unterwegs). While it runs, a very loud vehicle (≥ 75 dB(A),
 * «Lautes Fahrzeug» or «Strassenverkehr», not wind) shows the «Sehr laut» alert, at most one every 2 minutes;
 * a tap opens «Laut! Melden» with the event's time. The alert is not a second notification: it replaces the
 * content of the measurement's own notification for 30 s (one heads-up with sound and vibration on the
 * channel «Sehr laut», then the level text again; alert/LoudAlertNotifier.kt, MeasurementService.ACTION_LOUD_ALERT).
 * A window measurement («Langzeitmessung starten») never alerts while the phone lies still: a phone on the
 * windowsill must not wake its owner at every loud pass (while it is carried, «Unterwegs erkannt», it does).
 * The alert is computed and shown on the phone only; nothing of it is sent.
 */
object LoudAlerts {
    const val AVAILABLE = true

    /** Called once per process (LessNoiseApp): watches the live state for very loud events. */
    fun start(context: Context, container: AppContainer) {
        LoudAlertWatcher.start(container.appScope, container.live, LoudAlertNotifier(context.applicationContext))
    }

    /**
     * The measurement notification's report button and tap ([ReportTap.forState]): during every run a «Melden»
     * button next to «Stopp» (the report at the tap's time); the level text stays. A tap on the notification
     * itself opens «Laut! Melden» directly while the run is «unterwegs» (started with «Unterwegs messen», or
     * [moving] in a window run), otherwise the app on «Messen». Null without a run.
     */
    fun reportTap(context: Context, running: Boolean, unterwegs: Boolean, moving: Boolean): NotificationTap? {
        val t = ReportTap.forState(running, unterwegs, moving) ?: return null
        val intent = Intent(context, MainActivity::class.java)
            .setAction(t.action)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, t.requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationTap(
            text = null,
            intent = pending.takeIf { t.contentTap },
            button = pending.takeIf { t.button },
            buttonLabel = ReportTap.BUTTON,
        )
    }

    /**
     * The prefilled report when [intent] is a notification's, the shortcut's or the widget's tap, else null: the
     * «Sehr laut» tap (the event's time, suggested categories) or the report-now intent ([tapMs], the moment the
     * app received it; nothing preselected).
     */
    fun presetFrom(intent: Intent?, tapMs: Long = System.currentTimeMillis()): ReportPreset? {
        intent ?: return null
        // 0.8.4: the same action as the shortcut and the widget (ReportNow, src/karte).
        ReportTap.preset(intent.action, tapMs)?.let { return it }
        return LoudAlertIntent.preset(
            intent.action,
            intent.getLongExtra(LoudAlertIntent.EXTRA_EVENT_MS, 0L),
            intent.getStringExtra(LoudAlertIntent.EXTRA_CATEGORY),
        )
    }

    /**
     * What «Unterwegs messen» does when it starts: ask for the notification permission (Android 13+) if it is
     * missing. The measurement starts whatever the answer (without it, no notification shows).
     */
    @Composable
    fun rememberUnterwegsPermission(): () -> Unit {
        val context = LocalContext.current
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* shown or not, as Android decides */ }
        return {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
