package ch.lessnoise.app.ui

import android.Manifest
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
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.alert.LoudAlertIntent
import ch.lessnoise.app.alert.LoudAlertNotifier
import ch.lessnoise.app.alert.LoudAlertWatcher
import ch.lessnoise.app.alert.ReportTap
import ch.lessnoise.app.service.NotificationTap
import android.app.PendingIntent

/**
 * «Unterwegs» (0.6.3; since 0.6.4 a start mode; since 0.8.4 Labor·Karte ONLY — this file is in
 * src/laborKarte; the Online-Version has the stand-in in src/nounterwegs, the Offline and Labor builds
 * the one in src/nomap): the Labor button «Unterwegs aufnehmen» under «Messung starten» starts a
 * measurement that is never shared (no point, no minutes, no events: LiveState.unterwegs,
 * MeasurementEntity.unterwegs). While it runs, a very loud vehicle (≥ 75 dB(A), «Lautes Fahrzeug» or
 * «Strassenverkehr») shows the «Sehr laut» alert, at most one every 2 minutes; a tap opens
 * «Laut! Melden» with the event's time. Since 0.7.6 the alert is not a second notification: it
 * replaces the content of the measurement's own notification for 30 s (one heads-up with sound and
 * vibration on the channel «Sehr laut», then the level text again; alert/LoudAlertNotifier.kt,
 * MeasurementService.ACTION_LOUD_ALERT). A measurement started with «Messung starten» never alerts
 * while the phone lies still: a phone on the windowsill must not wake its owner at every loud pass
 * (while it is carried, «Unterwegs erkannt», it does, as since 0.6.7). (0.6.3 had a remembered switch
 * instead; its preference file «loud_alert» is no longer read.)
 */
object LoudAlerts {
    const val AVAILABLE = true

    /** Called once per process (LessNoiseApp): watches the live state for very loud events. */
    fun start(context: Context, container: AppContainer) {
        LoudAlertWatcher.start(container.appScope, container.live, LoudAlertNotifier(context.applicationContext))
    }

    /**
     * 0.6.8: the measurement notification's text and tap while the run is «unterwegs» (started with
     * «Unterwegs», or [moving] in a «Messen» run): «Antippen zum Melden», opening «Laut! Melden»
     * ([ReportTap]: its own action and request code). Null otherwise (the notification as before).
     * 0.6.9, Labor·Karte (BuildConfig.LABOR_UNTERWEGS): during every run a «Melden» button with the same
     * intent, the level text kept; the tap opens the report only while «unterwegs».
     */
    fun reportTap(context: Context, running: Boolean, unterwegs: Boolean, moving: Boolean): NotificationTap? {
        val t = ReportTap.forState(running, unterwegs, moving, labor = BuildConfig.LABOR_UNTERWEGS) ?: return null
        val intent = Intent(context, MainActivity::class.java)
            .setAction(t.action)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, t.requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationTap(
            text = t.text,
            intent = pending.takeIf { t.contentTap },
            button = pending.takeIf { t.button },
            buttonLabel = ReportTap.BUTTON,
        )
    }

    /**
     * The prefilled report when [intent] is a notification's tap, else null: the «Sehr laut» tap (the
     * event's time, suggested categories) or, 0.6.8, the measurement notification's tap while
     * «unterwegs» ([tapMs], the moment the app received it; nothing preselected).
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
     * What «Unterwegs» does before it starts: ask for the notification permission (Android 13+) if
     * it is missing. The measurement starts whatever the answer (without it, no notification shows).
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
