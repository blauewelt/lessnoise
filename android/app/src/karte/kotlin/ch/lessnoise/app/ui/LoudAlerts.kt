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
import ch.lessnoise.app.alert.LoudAlertIntent
import ch.lessnoise.app.alert.LoudAlertNotifier
import ch.lessnoise.app.alert.LoudAlertWatcher

/**
 * «Unterwegs» (0.6.3; since 0.6.4 a start mode, editions with the map only; the Offline and Labor
 * builds get the stand-in in src/nomap): the button «Unterwegs» next to «Messen» starts a
 * measurement that is never shared (no point, no minutes, no events: LiveState.unterwegs,
 * MeasurementEntity.unterwegs). While it runs, a very loud vehicle (≥ 75 dB(A), «Töff & Poser» or
 * «Strassenverkehr») shows the «Sehr laut» notification, at most one every 2 minutes; a tap opens
 * «Laut! Melden» with the event's time. A measurement started with «Messen» never alerts: a phone
 * on the windowsill must not wake its owner at every loud pass. (0.6.3 had a remembered switch
 * instead; its preference file «loud_alert» is no longer read.)
 */
object LoudAlerts {
    const val AVAILABLE = true

    /** Called once per process (LessNoiseApp): watches the live state for very loud events. */
    fun start(context: Context, container: AppContainer) {
        LoudAlertWatcher.start(container.appScope, container.live, LoudAlertNotifier(context.applicationContext))
    }

    /** The prefilled report when [intent] is the notification's tap, else null. */
    fun presetFrom(intent: Intent?): ReportPreset? {
        intent ?: return null
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
