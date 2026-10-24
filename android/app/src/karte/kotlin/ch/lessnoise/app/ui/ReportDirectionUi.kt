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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.lessnoise.app.compass.Compass
import ch.lessnoise.app.upload.CompassReading
import ch.lessnoise.app.upload.ReportDirection
import kotlin.math.cos
import kotlin.math.sin

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
     * The live compass (0.7.6): a schematic compass rose that turns with the phone, so that it is
     * obvious that the reading is live. The arrow stays fixed and points up the screen, i.e. where
     * the phone points (accent colour). Around it the rose is turned by −[magneticDeg]: the ring, 16
     * ticks (the four cardinal ones longer) and the letters N, O, S, W (German: Ost) on north, east,
     * south and west — N larger and in the accent colour. The letters move with the rose but stay
     * upright so they can be read. Behind it a faint «schematic map»: three thin grey lines across
     * the disc like a street grid, drawn in the turning frame so they visibly turn too. They are
     * purely decorative: no map data, no place, nothing read or stored. Before the first reading
     * ([magneticDeg] null) only the ring and the ticks, faint, and a grey arrow.
     */
    @Composable
    private fun DirectionArrow(magneticDeg: Double?) {
        val ink = MaterialTheme.colorScheme.onSurface
        val accent = MaterialTheme.colorScheme.primary
        val measurer = rememberTextMeasurer()
        val letterStyle = MaterialTheme.typography.labelLarge.copy(color = ink, fontWeight = FontWeight.Medium)
        val northStyle = MaterialTheme.typography.titleMedium.copy(color = accent, fontWeight = FontWeight.Bold)
        Canvas(Modifier.size(160.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2 - 4.dp.toPx()
            val live = magneticDeg != null
            val turn = -(magneticDeg ?: 0.0).toFloat()
            val faint = if (live) 1f else 0.4f
            rotate(turn, c) {
                // The schematic street grid, clipped to the disc (decorative only).
                if (live) {
                    val disc = Path().apply { addOval(Rect(c, radius)) }
                    clipPath(disc) {
                        val street = ink.copy(alpha = 0.12f)
                        val w = 3.dp.toPx()
                        drawLine(street, Offset(c.x - radius, c.y - radius * 0.28f), Offset(c.x + radius, c.y - radius * 0.40f), w)
                        drawLine(street, Offset(c.x - radius, c.y + radius * 0.46f), Offset(c.x + radius, c.y + radius * 0.30f), w)
                        drawLine(street, Offset(c.x + radius * 0.22f, c.y - radius), Offset(c.x + radius * 0.36f, c.y + radius), w)
                    }
                }
                drawCircle(ink.copy(alpha = 0.35f * faint), radius, c, style = Stroke(2.dp.toPx()))
                // 16 ticks every 22.5°, inward from the ring; the cardinal ones (every 4th) longer.
                for (i in 0 until 16) {
                    val cardinal = i % 4 == 0
                    val a = Math.toRadians(i * 22.5)
                    val len = if (cardinal) 12.dp.toPx() else 6.dp.toPx()
                    val dx = sin(a).toFloat()
                    val dy = -cos(a).toFloat()
                    drawLine(
                        ink.copy(alpha = (if (cardinal) 0.7f else 0.4f) * faint),
                        Offset(c.x + dx * radius, c.y + dy * radius),
                        Offset(c.x + dx * (radius - len), c.y + dy * (radius - len)),
                        (if (cardinal) 2.dp else 1.dp).toPx(),
                    )
                }
            }
            // The letters: their places turn with the rose, the letters themselves stay upright.
            if (live) {
                val letterRadius = radius - 24.dp.toPx()
                for ((i, letter) in CARDINALS.withIndex()) {
                    val layout = measurer.measure(letter, if (i == 0) northStyle else letterStyle)
                    val a = Math.toRadians(i * 90.0 + turn)
                    val p = Offset(c.x + sin(a).toFloat() * letterRadius, c.y - cos(a).toFloat() * letterRadius)
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }
            }
            // The fixed arrow: where the phone points.
            val r = radius * 0.62f
            val arrow = Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x - r * 0.36f, c.y - r * 0.25f)
                lineTo(c.x - r * 0.12f, c.y - r * 0.25f)
                lineTo(c.x - r * 0.12f, c.y + r * 0.7f)
                lineTo(c.x + r * 0.12f, c.y + r * 0.7f)
                lineTo(c.x + r * 0.12f, c.y - r * 0.25f)
                lineTo(c.x + r * 0.36f, c.y - r * 0.25f)
                close()
            }
            drawPath(arrow, if (live) accent else ink.copy(alpha = 0.3f))
        }
    }

    /** The rose's letters, clockwise from north: Nord, Ost, Süd, West. */
    private val CARDINALS = listOf("N", "O", "S", "W")

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
