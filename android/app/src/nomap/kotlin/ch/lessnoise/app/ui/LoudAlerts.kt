package ch.lessnoise.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import ch.lessnoise.app.AppContainer
import ch.lessnoise.app.service.NotificationTap

/**
 * The Offline and Labor builds: no «Unterwegs» button, no «Sehr laut» notification channel, no
 * alert code. This stand-in is all they contain of it (the real one is src/karte/.../ui/LoudAlerts.kt
 * with src/karte/.../alert/, compiled only into public and laborKarte).
 */
object LoudAlerts {
    const val AVAILABLE = false

    fun start(context: Context, container: AppContainer) = Unit

    fun presetFrom(intent: Intent?, tapMs: Long = 0L): ReportPreset? = null

    /** 0.6.8: no «Antippen zum Melden» on the measurement notification; it always opens the app. */
    fun reportTap(context: Context, running: Boolean, unterwegs: Boolean, moving: Boolean): NotificationTap? = null

    @Composable
    fun rememberUnterwegsPermission(): () -> Unit = {}
}
