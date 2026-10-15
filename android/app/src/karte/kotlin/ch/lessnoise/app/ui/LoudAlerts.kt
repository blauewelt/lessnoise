package ch.lessnoise.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.AppContainer
import ch.lessnoise.app.alert.LoudAlertIntent
import ch.lessnoise.app.alert.LoudAlertNotifier
import ch.lessnoise.app.alert.LoudAlertSettings
import ch.lessnoise.app.alert.LoudAlertWatcher

/**
 * «Unterwegs» (0.6.3, editions with the map only; the Offline and Labor builds get the stand-in in
 * src/nomap): a switch under «Starten» on «Messen», off by default and remembered. While it is on
 * and a measurement runs, a very loud vehicle (≥ 75 dB(A), «Töff & Poser» or «Strassenverkehr»)
 * shows the «Sehr laut» notification, at most one every 2 minutes; a tap opens «Laut! Melden» with
 * the event's time. Off by default because a phone on the windowsill must not wake its owner at
 * every loud pass, and the app cannot tell a pocket from a windowsill.
 */
object LoudAlerts {
    const val AVAILABLE = true

    /** Short and in the app's «du». */
    const val SWITCH_LABEL = "Unterwegs"
    const val SWITCH_HINT = "Sehr laut (ab 75 dB): eine Mitteilung, antippen zum Melden."

    @Volatile private var settings: LoudAlertSettings? = null

    private fun settings(context: Context): LoudAlertSettings =
        settings ?: synchronized(this) { settings ?: LoudAlertSettings(context).also { settings = it } }

    /** Called once per process (LessNoiseApp): watches the live state for very loud events. */
    fun start(context: Context, container: AppContainer) {
        LoudAlertWatcher.start(container.appScope, container.live, settings(context), LoudAlertNotifier(context.applicationContext))
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

    /** The switch on «Messen». Switching it on asks for the notification permission (Android 13+). */
    @Composable
    fun Switch() {
        val context = LocalContext.current
        val s = settings(context)
        val on by s.on.collectAsStateWithLifecycle()
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* shown or not, as Android decides */ }
        UnterwegsSwitchContent(on) { v ->
            s.set(v)
            if (v && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** The switch row without state (also rendered on the JVM, ShareScreenshots). */
@Composable
fun UnterwegsSwitchContent(checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(LoudAlerts.SWITCH_LABEL, style = MaterialTheme.typography.bodyLarge)
            Text(LoudAlerts.SWITCH_HINT, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
