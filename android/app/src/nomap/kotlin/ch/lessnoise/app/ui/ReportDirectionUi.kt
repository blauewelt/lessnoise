package ch.lessnoise.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import ch.lessnoise.app.upload.CompassReading

/**
 * The Offline and Labor builds have no «Laut! Melden» (no network, no location) and so no direction
 * step: no compass, no sensors. This stand-in is all they contain of it (the real one is
 * src/karte/.../ui/ReportDirectionUi.kt with src/karte/.../compass/, compiled only into public and
 * laborKarte).
 */
object ReportDirectionUi {
    const val AVAILABLE = false

    fun supported(context: Context): Boolean = false

    fun declination(lat: Double, lon: Double, timeMs: Long): Double? = null

    /** Same signature as the real step (0.7.8: [fix] for the streets of Labor·Karte); ignores everything. */
    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Step(fix: Pair<Double, Double>?, onTake: (CompassReading) -> Unit, onSkip: () -> Unit, onCancel: () -> Unit) = Unit
}
