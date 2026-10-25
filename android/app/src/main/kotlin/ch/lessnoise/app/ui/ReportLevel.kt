package ch.lessnoise.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import ch.lessnoise.app.AppContainer
import ch.lessnoise.app.audio.AudioCapture
import ch.lessnoise.app.audio.AudioSourceSelector
import ch.lessnoise.app.container
import ch.lessnoise.app.upload.ReportLevel
import ch.lessnoise.dsp.EngineConfig
import ch.lessnoise.dsp.LafTick
import ch.lessnoise.dsp.MeasurementEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.log10
import kotlin.math.pow

/**
 * 0.8.5 (Labor·Karte first): the sound level while «Laut! Melden» is open.
 *
 * - **No measurement running:** the microphone runs through the SAME level meter as a measurement
 *   ([MeasurementEngine], A-weighting and Fast time weighting, 125 ms steps), with the phone's
 *   calibration offset for the microphone a measurement would use now — exactly what the measuring
 *   screen shows, so the number means the same. A short-lived engine in this process, not the
 *   measuring service: no classifier, no event head, and **no audio tap** (no Labor recorder can
 *   see these blocks; ReportLevelTest checks the source). The capture block lives only in
 *   [AudioCapture]'s reused array and is overwritten by the next; nothing is written or sent. The
 *   engine starts when the dialog shows and stops when it closes ([ReportLevelMic], DisposableEffect).
 * - **A measurement running** (window, dialog from the widget or the shortcut): no second microphone;
 *   the running measurement's own per-step values are taken from `container.live`.
 *
 * What the report keeps is [ReportLevelMeter.level]: the peak LAFmax and the LAeq of the open time.
 */
object ReportLevelUi {
    /** Small label after the live value. */
    const val NOW = "jetzt"

    /** Prefix of the peak since the dialog opened. */
    const val PEAK = "Spitze"

    fun hasMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** «58,2 dB(A)»; «– dB(A)» before the first value. */
    fun nowText(db: Double?): String = "${Fmt.db(db)} dB(A)"

    /** «Spitze 81,4 dB(A)». */
    fun peakText(db: Double?): String = "$PEAK ${Fmt.db(db)} dB(A)"
}

/** What the dialog's line shows: the newest LAF and the peak LAFmax since the dialog opened (null: none yet). */
data class ReportLevelNow(val lafDb: Double? = null, val peakDb: Double? = null)

/**
 * Collects the 125 ms steps while the dialog is open (pure, thread-safe: the capture thread feeds it,
 * the UI reads [now]; ReportLevelTest). Only valid steps count (the engine marks a silenced or
 * recovering microphone invalid), exactly as in a measurement.
 */
class ReportLevelMeter(private val clock: () -> Long = System::currentTimeMillis) {
    private val flow = MutableStateFlow(ReportLevelNow())
    val now: StateFlow<ReportLevelNow> = flow

    private var openedMs = clock()
    private var sumEnergy = 0.0
    private var steps = 0
    private var peak = Double.NEGATIVE_INFINITY
    private var calibrated = false

    /** The dialog opened: everything starts from zero (the peak too). */
    @Synchronized
    fun reset() {
        openedMs = clock(); sumEnergy = 0.0; steps = 0; peak = Double.NEGATIVE_INFINITY; calibrated = false
        flow.value = ReportLevelNow()
    }

    /** Whether the values come from a calibrated microphone (the engine's or the running measurement's). */
    @Synchronized
    fun setCalibrated(value: Boolean) {
        calibrated = value
    }

    /** One 125 ms step: LAF at its end, LAFmax within it, LAeq of it (dB, calibrated as configured). */
    @Synchronized
    fun step(lafDb: Double, lafMaxDb: Double, leqDb: Double, valid: Boolean) {
        if (!valid || !lafDb.isFinite() || !lafMaxDb.isFinite() || !leqDb.isFinite()) {
            flow.value = flow.value.copy(lafDb = null)
            return
        }
        sumEnergy += 10.0.pow(leqDb / 10.0)
        steps++
        if (lafMaxDb > peak) peak = lafMaxDb
        flow.value = ReportLevelNow(lafDb, peak)
    }

    fun step(t: LafTick) = step(t.lafDb, t.lafMaxDb, t.leqDb, t.valid)

    /**
     * The report's level at [atMs] («Senden»): peak LAFmax and LAeq over the valid steps since
     * [reset], one decimal, the open time in whole seconds (at least 1, at most
     * [ReportLevel.MAX_SECONDS]); null without a single valid step (no microphone, or closed within
     * the engine's first half second).
     */
    @Synchronized
    fun level(atMs: Long = clock()): ReportLevel? {
        if (steps == 0) return null
        val laeq = 10.0 * log10(sumEnergy / steps)
        val seconds = ((atMs - openedMs) / 1000L).toInt().coerceIn(1, ReportLevel.MAX_SECONDS)
        return ReportLevel(ReportLevel.round1(peak), ReportLevel.round1(laeq), seconds, calibrated)
    }
}

/**
 * The in-dialog microphone: [AudioCapture] → [MeasurementEngine] → [meter], nothing else. Opened
 * off the main thread ([open], it reads the calibration from the database), closed by [close]
 * (also while [open] still runs: then it never starts). One instance per dialog.
 */
class ReportLevelMic(private val context: Context, private val meter: ReportLevelMeter) {
    private var capture: AudioCapture? = null
    private var closed = false

    suspend fun open(c: AppContainer) {
        if (!ReportLevelUi.hasMic(context)) return
        // The calibration screen holds the microphone: no level then (the dialog works as before).
        if (c.calibrationActive.value) return
        // The same source, microphone and calibration a measurement would start with now (MeasurementService).
        val source = AudioSourceSelector.select(context)
        val res = c.microphones.resolveNow()
        val cal = c.calibrations.active(source, res.key)
        val engine = reportLevelEngine(cal.offsetDb, cal.calibrated, cal.id, source, meter)
        meter.setCalibrated(cal.calibrated)
        val cap = AudioCapture(
            context, onBlock = { samples, count -> engine.process(samples, count) },
            onError = { msg -> Log.w(TAG, "capture: $msg") },
            onSilenced = { silenced -> engine.setMicSilenced(silenced) },
        )
        synchronized(this) {
            if (closed) return
            try {
                cap.start(source, res)
                capture = cap
            } catch (e: Exception) {
                Log.w(TAG, "microphone not opened: ${e.javaClass.simpleName}")
                try { cap.stop() } catch (_: Exception) {}
            }
        }
    }

    fun close() {
        val cap = synchronized(this) {
            closed = true
            capture.also { capture = null }
        }
        cap?.stop()
    }

    private companion object {
        const val TAG = "ReportLevel"
    }
}

/**
 * The dialog's engine: the measurement's [MeasurementEngine] with the calibration of [offsetDb],
 * without classifier, category mapper or event head, and nothing but [meter] listening. There is
 * no audio tap here (ReportLevelTest).
 */
fun reportLevelEngine(offsetDb: Double, calibrated: Boolean, calibrationId: Long?, source: String, meter: ReportLevelMeter): MeasurementEngine =
    MeasurementEngine(
        EngineConfig(
            calibrationOffsetDb = offsetDb, calibrated = calibrated, calibrationId = calibrationId,
            audioSource = source, classifierEnabled = false, zone = ZoneId.systemDefault(),
        ),
        mapper = null,
        listener = object : MeasurementEngine.Listener {
            override fun onTick(tick: LafTick) = meter.step(tick)
        },
        eventHead = null,
    )

/**
 * Runs the level for the open dialog into [meter]: the dialog's own engine while no measurement runs
 * and [micAllowed]; the running measurement's values while one runs. Stops with the composition
 * (the dialog closing) — never later — and while the app is not in the foreground (e.g. the camera
 * app for «Foto» is on screen: the microphone is closed meanwhile and opened again on return; the
 * level then covers the seconds the dialog was actually on screen, the open time still counts all).
 */
@Composable
fun ReportLevelEffect(meter: ReportLevelMeter, micAllowed: Boolean) {
    val context = LocalContext.current
    val c = context.container
    val live by c.live.collectAsState()
    val measuring = live.running
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val onScreen = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(measuring, micAllowed, onScreen) {
        if (measuring || !micAllowed || !onScreen) return@DisposableEffect onDispose {}
        val mic = ReportLevelMic(context.applicationContext, meter)
        val job = c.appScope.launch(Dispatchers.IO) { mic.open(c) }
        onDispose {
            job.cancel()
            // Off the main thread (the capture thread is joined); waits only for an open() in progress.
            c.appScope.launch(Dispatchers.IO) { mic.close() }
        }
    }
    if (measuring) {
        // A measurement runs: its own steps (the same engine and calibration), no second microphone.
        LaunchedEffect(Unit) {
            // Every 125 ms step once: `live` also changes for other reasons (each second, the
            // microphone state); a step is new when its time is. A step the collector misses (the
            // flow keeps only the newest value) is left out of the average and the peak.
            var lastTickMs = Long.MIN_VALUE
            c.live.collect { l ->
                if (!l.running || l.tickEpochMs == lastTickMs) return@collect
                lastTickMs = l.tickEpochMs
                meter.setCalibrated(l.calibrated)
                val laf = l.lafDb
                if (laf == null) meter.step(Double.NaN, Double.NaN, Double.NaN, valid = false)
                else meter.step(laf, l.lafMaxTickDb ?: laf, l.leqTickDb ?: laf, valid = true)
            }
        }
    }
}

/** The dialog's line under the disc: «58,2 dB(A) jetzt · Spitze 81,4 dB(A)». */
@Composable
fun ReportLevelLine(meter: ReportLevelMeter) {
    val now by meter.now.collectAsState()
    val nowText = ReportLevelUi.nowText(now.lafDb)
    val peakText = ReportLevelUi.peakText(now.peakDb)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$nowText ${ReportLevelUi.NOW}, $peakText" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(nowText, style = MaterialTheme.typography.titleMedium)
        Text(ReportLevelUi.NOW, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(peakText, style = MaterialTheme.typography.bodyMedium)
    }
}

