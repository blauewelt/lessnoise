package ch.lessnoise.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import ch.lessnoise.app.icons.AppIcons
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

    /**
     * 0.9.2: the small label in the level card's top-right corner: the numbers come from the microphone,
     * now (its own engine, or the running measurement's). Without it the card did not say where they come from.
     * 0.9.4.2: a microphone icon at the start of the status line instead ([ReportStatusLine]); this text is
     * its spoken form (TalkBack) and the icon's alternate text.
     */
    const val MIC_ON = "Mikrofon läuft"

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
 * app for «Foto» or, 0.9.4.3 in Labor·Karte, «Video» is on screen — the camera app then needs the microphone
 * itself: our activity is stopped, so this microphone is closed meanwhile and opened again on return; the
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

/**
 * 0.9.4.2: one part of the status line: «68,9 dB(A) · Spitze 75,8» (the peak without its unit, which the
 * value before it already names; «– dB(A) · Spitze –» before the first value). Until 0.9.4.1 the level was
 * a card of its own with two large figures and «Mikrofon läuft».
 */
fun reportLevelPart(now: ReportLevelNow): String = "${ReportLevelUi.nowText(now.lafDb)} · ${ReportLevelUi.PEAK} ${Fmt.db(now.peakDb)}"

/**
 * 0.9.4.2: the status line never breaks inside a value with its unit («68,9 dB(A)», «Spitze 75,8», «Standort ±
 * 100 m»: the spaces of a part with a digit become no-break spaces) and never before a «·» (it ends a line). Parts
 * without a digit («Karte verschiebbar», «Standort von Hand gesetzt») break as usual: kept whole they would be cut
 * inside a word on a narrow phone with a large font.
 */
fun reportStatusNoBreak(s: String): String =
    s.split(" · ").joinToString("\u00A0· ") { part -> if (part.any { it.isDigit() }) part.replace(' ', '\u00A0') else part }

/** 0.9.4.2: what TalkBack reads for the level (the icon has no text): one sentence, as the level card did. */
fun reportLevelDescription(now: ReportLevelNow): String =
    "${ReportLevelUi.MIC_ON}: ${ReportLevelUi.nowText(now.lafDb)} ${ReportLevelUi.NOW}, ${ReportLevelUi.peakText(now.peakDb)}"

private const val STATUS_MIC = "mic"

/**
 * 0.9.4.2: the level and the place as one compact status, two lines at most on a phone at font scale 1:
 * «🎤 68,9 dB(A) · Spitze 75,8» with [trailing] («Foto») at its end, and under it «⌖ Standort ± 100 m · Karte
 * verschiebbar» over the full width (beside «Foto» the place would wrap to a third line on a 360 dp phone).
 * Replaces the level card (two stat blocks and «Mikrofon läuft», 0.8.6–0.9.4.1) and its location row. The
 * microphone icon at the start keeps the meaning of «Mikrofon läuft» (the numbers come from the microphone,
 * now); [meter] null (no microphone and no measurement): only the place, «Foto» beside it. [placeIcon] is the
 * location row's icon of 0.8.6 (searching / fix / set by hand); [placeTrailing] («Zurück zum Standort») ends the
 * place line. The live value is semibold with tabular figures.
 */
@Composable
fun ReportStatusLine(
    meter: ReportLevelMeter?,
    fixLine: String,
    placeIcon: ImageVector,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    placeTrailing: (@Composable () -> Unit)? = null,
) {
    val now = meter?.now?.collectAsState()?.value
    val cs = MaterialTheme.colorScheme
    val icon = Placeholder(16.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)
    val inline = mapOf(
        STATUS_MIC to InlineTextContent(icon) { Icon(AppIcons.Mic, contentDescription = null, tint = cs.primary, modifier = Modifier.fillMaxSize()) },
    )
    // The place's icon beside the text, not inline: a wrapped place line keeps its words whole and hangs under the
    // text, not under the icon (an icon glued to «Standort» forced a cut inside the word at font 1.3).
    val place: @Composable (Modifier) -> Unit = { m ->
        Row(m.semantics(mergeDescendants = true) { contentDescription = fixLine }, verticalAlignment = Alignment.Top) {
            Icon(placeIcon, contentDescription = null, tint = cs.primary, modifier = Modifier.padding(top = 2.dp).size(with(LocalDensity.current) { 16.sp.toDp() }))
            Spacer(Modifier.width(4.dp))
            Text(reportStatusNoBreak(fixLine), style = MaterialTheme.typography.bodyMedium)
        }
    }
    val row: @Composable ((@Composable () -> Unit)?, @Composable (Modifier) -> Unit) -> Unit = { end, first ->
        if (end == null) first(Modifier.fillMaxWidth()) else BesideOrBelow(first = { first(Modifier) }, end = end)
    }
    Column(modifier) {
        if (now == null) {
            // No level: «Foto» beside the place; with «Zurück zum Standort» there too, «Foto» gets a line of its own.
            if (placeTrailing == null) {
                row(trailing, place)
            } else {
                row(placeTrailing, place)
                if (trailing != null) row(trailing) { m -> Spacer(m) }
            }
        } else {
            row(trailing) { m ->
                Text(
                    buildAnnotatedString {
                        appendInlineContent(STATUS_MIC, ReportLevelUi.MIC_ON)
                        append('\u00A0')
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")) {
                            append(reportStatusNoBreak(ReportLevelUi.nowText(now.lafDb)))
                        }
                        append("\u00A0· ")
                        append(reportStatusNoBreak("${ReportLevelUi.PEAK} ${Fmt.db(now.peakDb)}"))
                    },
                    inlineContent = inline, style = MaterialTheme.typography.bodyMedium,
                    modifier = m.semantics { contentDescription = reportLevelDescription(now) },
                )
            }
            row(placeTrailing, place)
        }
    }
}

/**
 * 0.9.4.2: [first] (a line of the status) with [end] («Foto», «Zurück zum Standort») at its right, centred, as long
 * as the longest word of [first] still fits beside it; else [end] goes under [first] (a narrow phone with a large
 * font: a word is never cut to make room for a button).
 */
@Composable
private fun BesideOrBelow(first: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout(contents = listOf(first, end)) { (firstM, endM), c ->
        val gap = 8.dp.roundToPx()
        val max = if (c.hasBoundedWidth) c.maxWidth else Constraints.Infinity
        val e = endM.first().measure(Constraints(maxWidth = max))
        val word = firstM.first().minIntrinsicWidth(Constraints.Infinity)
        if (max == Constraints.Infinity || word + gap + e.width <= max) {
            val f = firstM.first().measure(Constraints(maxWidth = if (max == Constraints.Infinity) max else max - gap - e.width))
            val w = if (max == Constraints.Infinity) f.width + gap + e.width else max
            val h = maxOf(f.height, e.height)
            layout(w, h) {
                f.place(0, (h - f.height) / 2)
                e.place(w - e.width, (h - e.height) / 2)
            }
        } else {
            val f = firstM.first().measure(Constraints(maxWidth = max))
            layout(max, f.height + e.height) {
                f.place(0, 0)
                e.place(0, f.height)
            }
        }
    }
}
