package ch.lessnoise.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import ch.lessnoise.app.AppContainer
import ch.lessnoise.app.report.ReportNow
import ch.lessnoise.app.service.NotificationTap

/**
 * 0.8.4, the Online-Version (flavour public only; see build.gradle.kts sourceSets): no measurement on
 * the road. No «Unterwegs» start, no «Sehr laut» notification channel, no alert watcher, no «Antippen
 * zum Melden» or «Melden» on the measurement notification — the real facade and the alert code are in
 * src/laborKarte only. What stays: the shortcut and the widget «Laut! Melden» (src/karte/…/report/)
 * open the report dialog through [presetFrom]. (The Offline and Labor builds have src/nomap's stand-in,
 * which has no report at all.)
 */
object LoudAlerts {
    const val AVAILABLE = false

    fun start(context: Context, container: AppContainer) = Unit

    /** The shortcut's and the widget's intent ([ReportNow.ACTION]): «Laut! Melden» at the tap's time. Nothing else. */
    fun presetFrom(intent: Intent?, tapMs: Long = System.currentTimeMillis()): ReportPreset? =
        ReportNow.preset(intent?.action, tapMs)

    /** The measurement notification as in the Offline-Version: level text, a tap opens the app. */
    fun reportTap(context: Context, running: Boolean, unterwegs: Boolean, moving: Boolean): NotificationTap? = null

    @Composable
    fun rememberUnterwegsPermission(): () -> Unit = {}
}
