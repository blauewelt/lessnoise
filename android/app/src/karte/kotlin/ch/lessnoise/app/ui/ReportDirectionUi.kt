package ch.lessnoise.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.hardware.GeomagneticField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.lessnoise.app.compass.Compass
import ch.lessnoise.app.upload.CompassReading
import ch.lessnoise.app.upload.ReportDirection

/**
 * The direction step of «Laut! Melden» (0.6.7, editions with the map; the Offline and Labor builds
 * get the stand-in in src/nomap): right after «Was war laut?», while the location fix is being
 * found, the reporter points the phone where the vehicle drove and taps «Richtung übernehmen»; one
 * tap on «Überspringen» sends the report without a direction, exactly as before 0.6.7. No new
 * permission. Not shown at all on a phone without a compass ([supported]).
 */
object ReportDirectionUi {
    const val AVAILABLE = true

    const val TITLE = "Wohin fuhr es?"
    const val INSTRUCTION = "Halte das Telefon in die Fahrtrichtung."
    const val CALIBRATE_HINT = "Kompass ungenau: Telefon in Form einer Acht bewegen."
    const val TAKE = "Richtung übernehmen"
    const val SKIP = "Überspringen"

    /** Whether the phone has the sensors for the step (rotation vector, or accelerometer + magnetometer). */
    fun supported(context: Context): Boolean = Compass(context, { 0 }) { _, _ -> }.available

    /**
     * Magnetic declination (degrees, east positive) at the report's own place and time, from
     * Android's built-in World Magnetic Model (GeomagneticField: computed on the phone, no network).
     * Height 0: the declination hardly changes with altitude.
     */
    fun declination(lat: Double, lon: Double, timeMs: Long): Double? =
        try {
            GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, timeMs).declination.toDouble().takeIf { it.isFinite() }
        } catch (_: Exception) {
            null
        }

    /** The figure-eight hint only while the compass reports low or unreliable accuracy (status 0 or 1). */
    fun showCalibrationHint(sensorStatus: Int): Boolean = sensorStatus in 0..1

    /** «Nordost · 47°» under the arrow (magnetic, while pointing; the report stores true north). */
    fun liveLabel(magneticDeg: Double): String {
        val d = ReportDirection.roundDeg(magneticDeg)
        return ReportDirection(d, ReportDirection.North.MAGNETIC, ReportDirection.Accuracy.HIGH).label.removePrefix("Richtung ") + " · $d°"
    }

    /**
     * The step as a dialog. [onTake] gets the reading at the tap; [onSkip] goes on without one;
     * [onCancel] (back) cancels the whole report, like «Abbrechen» in the other steps. A tap outside
     * does nothing, so waving the phone cannot cancel by accident.
     */
    @Composable
    fun Step(onTake: (CompassReading) -> Unit, onSkip: () -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current
        var reading by remember { mutableStateOf<CompassReading?>(null) }
        val compass = remember {
            Compass(context, displayRotation = { displayRotation(context) }) { deg, status -> reading = CompassReading(deg, status) }
        }
        // While the step is open the screen keeps its rotation: turning the phone flat or upright
        // must not recreate the activity (it would end the report) nor turn the arrow.
        DisposableEffect(Unit) {
            val activity = context.findActivity()
            val before = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            onDispose { if (activity != null && before != null) activity.requestedOrientation = before }
        }
        // Sensors only while the step is on screen and the app is in front.
        LifecycleResumeEffect(compass) {
            compass.start()
            onPauseOrDispose { compass.stop() }
        }
        val r = reading
        AlertDialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(TITLE) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(INSTRUCTION, modifier = Modifier.fillMaxWidth())
                    DirectionArrow(r?.magneticDeg)
                    Text(
                        if (r == null) "…" else liveLabel(r.magneticDeg),
                        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    )
                    if (r != null && showCalibrationHint(r.sensorStatus)) {
                        Text(CALIBRATE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reading?.let(onTake) }, enabled = r != null) { Text(TAKE) } },
            dismissButton = { TextButton(onClick = onSkip) { Text(SKIP) } },
        )
    }

    /**
     * The live arrow: it always points up the screen, i.e. where the phone points; the ring around
     * it turns with the compass, its tick and «N» stay on north.
     */
    @Composable
    private fun DirectionArrow(magneticDeg: Double?) {
        val ink = MaterialTheme.colorScheme.onSurface
        val accent = MaterialTheme.colorScheme.primary
        Canvas(Modifier.size(120.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 6.dp.toPx()
            drawCircle(ink.copy(alpha = 0.35f), radius, c, style = Stroke(2.dp.toPx()))
            if (magneticDeg != null) {
                rotate(-magneticDeg.toFloat(), c) {
                    val n = Path().apply {
                        moveTo(c.x, c.y - radius - 4.dp.toPx())
                        lineTo(c.x - 6.dp.toPx(), c.y - radius + 8.dp.toPx())
                        lineTo(c.x + 6.dp.toPx(), c.y - radius + 8.dp.toPx())
                        close()
                    }
                    drawPath(n, ink)
                }
            }
            val a = Path().apply {
                moveTo(c.x, c.y - radius * 0.8f)
                lineTo(c.x - radius * 0.32f, c.y - radius * 0.2f)
                lineTo(c.x - radius * 0.11f, c.y - radius * 0.2f)
                lineTo(c.x - radius * 0.11f, c.y + radius * 0.6f)
                lineTo(c.x + radius * 0.11f, c.y + radius * 0.6f)
                lineTo(c.x + radius * 0.11f, c.y - radius * 0.2f)
                lineTo(c.x + radius * 0.32f, c.y - radius * 0.2f)
                close()
            }
            drawPath(a, if (magneticDeg == null) ink.copy(alpha = 0.3f) else accent)
        }
    }

    private fun displayRotation(context: Context): Int =
        try {
            ContextCompat.getDisplayOrDefault(context).rotation
        } catch (_: Exception) {
            0
        }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
