package ch.lessnoise.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import ch.lessnoise.app.AppContainer

/**
 * The Offline and Labor builds: no «Unterwegs» switch, no «Sehr laut» notification channel, no
 * alert code. This stand-in is all they contain of it (the real one is src/karte/.../ui/LoudAlerts.kt
 * with src/karte/.../alert/, compiled only into public and laborKarte).
 */
object LoudAlerts {
    const val AVAILABLE = false

    fun start(context: Context, container: AppContainer) = Unit

    fun presetFrom(intent: Intent?): ReportPreset? = null

    @Composable
    fun Switch() = Unit
}
