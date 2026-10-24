package ch.lessnoise.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import ch.lessnoise.app.upload.CompassReading

/**
 * The Offline and Labor builds have no «Laut! Melden» (no network, no location) and so no compass:
 * no sensors. This stand-in is all they contain of it (the real one is
 * src/karte/.../ui/ReportDirectionUi.kt with src/karte/.../compass/, compiled only into public and
 * laborKarte). Same API as the real one (0.8.1), so that LocationFlows.kt compiles everywhere.
 */
object ReportDirectionUi {
    const val AVAILABLE = false

    fun declination(lat: Double, lon: Double, timeMs: Long): Double? = null

    /** No compass: always null (the report carries no direction, and no disc is drawn). */
    @Composable
    fun rememberReading(): State<CompassReading?>? = null

    /** Same signature as the real disc (0.8.1 with the place moved under the dot); draws nothing, so nothing can be moved. */
    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun Panel(reading: State<CompassReading?>, fix: Pair<Double, Double>?, moved: Pair<Double, Double>?, onMove: (Pair<Double, Double>) -> Boolean) = Unit
}
