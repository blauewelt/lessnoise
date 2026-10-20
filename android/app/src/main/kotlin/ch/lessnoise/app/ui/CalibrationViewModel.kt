package ch.lessnoise.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.lessnoise.app.audio.AudioCapture
import ch.lessnoise.app.audio.MicChoice
import ch.lessnoise.app.audio.AudioSourceSelector
import ch.lessnoise.app.container
import ch.lessnoise.app.data.CalibrationEntity
import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.calibration.CalibrationMath
import ch.lessnoise.dsp.calibration.CalibrationResult
import ch.lessnoise.dsp.calibration.CalibrationWarning
import ch.lessnoise.dsp.calibration.EventFloor
import ch.lessnoise.dsp.calibration.Recalibration
import ch.lessnoise.app.data.AppSettings
import ch.lessnoise.app.data.Recalibrator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CalUiState(
    val running: CalMode? = null,
    val progress: Float = 0f,
    val remainingSeconds: Int = 0,
    /** Last 1 s level with the currently active offset (live feedback). */
    val currentDb: Double? = null,
    val referenceResult: CalibrationResult? = null,
    /** Fixed at 30 s since 0.6.1 (was 30 | 60 s). */
    val referenceWindowSeconds: Int = 30,
    val calibratorResult: CalibrationResult? = null,
    val calibratorNominalDb: Double = 94.0,
    val noiseFloorDb: Double? = null,
    val message: String? = null,
    /** 0.7.2: the microphone of the last calibration capture («Mikrofon: …»), null before one ran. */
    val microphone: String? = null,
    /** After saving a calibration: offer to re-evaluate earlier measurements with it. */
    val recalOffer: RecalOffer? = null,
)

/** [mic]: the microphone of the new calibration (0.7.2); only its records are re-evaluated. */
data class RecalOffer(val target: Recalibration.Target, val mic: String, val minutes: Int, val events: Int)

class CalibrationViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val source: String = AudioSourceSelector.select(app)
    val deviceModel: String = ch.lessnoise.app.data.CalibrationRepository.deviceModel

    /**
     * 0.7.2: the microphone this screen calibrates — the one a measurement would use now (the built-in
     * one, or the chosen one while it is connected). Calibrations are per microphone.
     */
    val microphone: StateFlow<MicChoice.Resolution> = c.microphones.resolution

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val active: StateFlow<CalibrationEntity?> =
        c.microphones.resolution.map { it.key }.distinctUntilChanged()
            .flatMapLatest { key -> c.calibrations.activeFlow(source, key) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val history: StateFlow<List<CalibrationEntity>> =
        c.calibrations.history().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(CalUiState())
    val state: StateFlow<CalUiState> = _state

    private var capture: AudioCapture? = null
    private var run: CalibrationRun? = null
    /** 0.7.2: the microphone of the running (or last) calibration capture; results are saved for it. */
    private var runMic: MicChoice.Resolution = c.microphones.resolution.value

    private val activeOffset: Double get() = active.value?.offsetDb ?: Acoustics.DEFAULT_CALIBRATION_OFFSET_DB

    fun setCalibratorNominal(db: Double) { _state.value = _state.value.copy(calibratorNominalDb = db) }

    fun start(mode: CalMode) {
        if (_state.value.running != null) return
        if (c.live.value.running || c.live.value.starting) {
            _state.value = _state.value.copy(message = "Zuerst die Messung stoppen.")
            return
        }
        val seconds = when (mode) {
            CalMode.REFERENCE -> _state.value.referenceWindowSeconds
            CalMode.CALIBRATOR -> 10
            CalMode.NOISE_FLOOR -> 20
        }
        val r = CalibrationRun(mode, seconds)
        val m = r.measurement
        run = r
        // A new run replaces the previous result of the same method: it cannot be saved anymore.
        _state.value = when (mode) {
            CalMode.REFERENCE -> _state.value.copy(referenceResult = null)
            CalMode.CALIBRATOR -> _state.value.copy(calibratorResult = null)
            CalMode.NOISE_FLOOR -> _state.value.copy(noiseFloorDb = null)
        }.copy(running = mode, progress = 0f, remainingSeconds = seconds, currentDb = null, message = null)
        var lastPosted = -1
        val cap = AudioCapture(getApplication(), onBlock = { buf, n ->
            // Capture thread: feed the measurement, post progress about every second.
            r.onBlock(buf, n)
            val elapsed = (m.progress * seconds).toInt()
            if (elapsed != lastPosted || m.isComplete) {
                lastPosted = elapsed
                val raw = m.lastSecondRawDb
                _state.update {
                    it.copy(
                        progress = m.progress.toFloat().coerceIn(0f, 1f),
                        remainingSeconds = (seconds - elapsed).coerceAtLeast(0),
                        currentDb = if (raw.isNaN()) null else raw + activeOffset,
                    )
                }
            }
            if (r.shouldStop) viewModelScope.launch { finish(mode) }
        }, onError = { msg -> viewModelScope.launch { abort(MicChoice.errorText("Mikrofon-Fehler: ", msg)) } },
            onSilenced = { silenced ->
                r.onMicSilenced(silenced)
                if (r.shouldStop) viewModelScope.launch { finish(mode) }
            })
        try {
            c.calibrationActive.value = true
            // 0.7.2: the microphone a measurement would use now; a calibration run never switches.
            val mic = c.microphones.resolveNow()
            runMic = mic
            val info = cap.start(source, mic)
            capture = cap
            _state.update { it.copy(microphone = info.mic.status) }
        } catch (e: Exception) {
            c.calibrationActive.value = false
            _state.value = _state.value.copy(running = null, message = MicChoice.errorText("Mikrofon konnte nicht geöffnet werden: ", e.message))
        }
    }

    private suspend fun stopCapture() {
        val cap = capture ?: return
        capture = null
        withContext(Dispatchers.Default) { cap.stop() }
        c.calibrationActive.value = false
    }

    private var finishing = false

    private suspend fun finish(mode: CalMode) {
        // The capture thread may request finish() several times; handle it once.
        if (_state.value.running != mode || finishing) return
        finishing = true
        try { stopCapture() } finally { finishing = false }
        val current = run ?: return
        run = null
        when (val outcome = current.outcome()) {
            is CalibrationRun.Outcome.Aborted ->
                _state.value = _state.value.copy(running = null, progress = 0f, message = outcome.reason)
            is CalibrationRun.Outcome.Done -> {
                val r = outcome.result
                _state.value = when (mode) {
                    CalMode.REFERENCE -> _state.value.copy(running = null, referenceResult = r)
                    CalMode.CALIBRATOR -> _state.value.copy(running = null, calibratorResult = r)
                    CalMode.NOISE_FLOOR -> _state.value.copy(running = null, noiseFloorDb = r.rawLaeqDb + activeOffset)
                }
            }
        }
    }

    /** Lifecycle ON_STOP of the screen: a calibration must not continue in the background. */
    fun onAppStopped() {
        val r = run ?: return
        r.onAppStopped()
        viewModelScope.launch { finish(r.mode) }
    }

    fun cancel() = viewModelScope.launch { abort(null) }

    private suspend fun abort(msg: String?) {
        stopCapture()
        run = null
        _state.value = _state.value.copy(running = null, progress = 0f, message = msg)
    }

    fun referenceWarnings(referenceDb: Double?): List<CalibrationWarning> =
        _state.value.referenceResult?.let { CalibrationMath.referenceWarnings(it, referenceDb) } ?: emptyList()

    fun calibratorWarnings(): List<CalibrationWarning> =
        _state.value.calibratorResult?.let { CalibrationMath.calibratorWarnings(it, _state.value.calibratorNominalDb) } ?: emptyList()

    /** Offset the engine would use now for this source and [mic]: the active calibration's, else the default. */
    private suspend fun currentOffset(mic: String): Double = c.calibrations.active(source, mic).offsetDb

    /**
     * A calibration with [newOffset] has just become active (previously [previousOffset]): the
     * event floor follows it (see [EventFloor]); for a real calibration, offer to re-evaluate
     * earlier measurements.
     */
    private suspend fun activated(id: Long, previousOffset: Double, newOffset: Double, calibrated: Boolean, mic: String) {
        var adjustment: EventFloor.Adjustment? = null
        c.settings.update { s ->
            val a = EventFloor.adjust(s.eventMinLevelDb, previousOffset, newOffset, AppSettings.EVENT_MIN_LEVEL_MIN, AppSettings.EVENT_MIN_LEVEL_MAX)
            adjustment = a
            s.copy(eventMinLevelDb = a.newFloorDb)
        }
        val offer = if (calibrated) {
            val target = Recalibration.Target(id, newOffset, source)
            val (minutes, events) = c.recalibrator.counts(target, mic).first()
            if (minutes + events > 0) RecalOffer(target, mic, minutes, events) else null
        } else null
        val message = if (calibrated) CalibrationTexts.saved(adjustment!!) else CalibrationTexts.RESET
        _state.update { it.copy(message = message, recalOffer = offer) }
    }

    fun saveReference(referenceDb: Double, notes: String) = viewModelScope.launch {
        val r = _state.value.referenceResult ?: return@launch
        val offset = CalibrationMath.referenceOffset(referenceDb, r.rawLaeqDb)
        val mic = runMic
        val previous = currentOffset(mic.key)
        val id = c.calibrations.save(
            source, offset, CalibrationEntity.METHOD_REFERENCE, notes,
            referenceDb = referenceDb, measuredRawDb = r.rawLaeqDb, stdDevDb = r.stdDevDb,
            durationSeconds = r.measuredSeconds, warnings = referenceWarnings(referenceDb).map { it.name },
            mic = mic.key, micLabel = mic.label,
        )
        _state.update { it.copy(referenceResult = null) }
        activated(id, previous, offset, calibrated = true, mic = mic.key)
    }

    fun saveCalibrator(notes: String) = viewModelScope.launch {
        val r = _state.value.calibratorResult ?: return@launch
        val nominal = _state.value.calibratorNominalDb
        val offset = CalibrationMath.calibratorOffset(nominal, r.rawLaeqDb)
        val mic = runMic
        val previous = currentOffset(mic.key)
        val id = c.calibrations.save(
            source, offset, CalibrationEntity.METHOD_CALIBRATOR, notes,
            measuredRawDb = r.rawLaeqDb, stdDevDb = r.stdDevDb, durationSeconds = r.measuredSeconds,
            calibratorNominalDb = nominal, toneFrequencyHz = r.toneFrequencyHz, tonality = r.tonality,
            warnings = calibratorWarnings().map { it.name },
            mic = mic.key, micLabel = mic.label,
        )
        _state.update { it.copy(calibratorResult = null) }
        activated(id, previous, offset, calibrated = true, mic = mic.key)
    }

    /** «Korrektur von Hand» and «Zurücksetzen» apply to the microphone in use now (0.7.2). */
    fun saveManual(offsetDb: Double, notes: String) = viewModelScope.launch {
        val mic = c.microphones.resolveNow()
        val previous = currentOffset(mic.key)
        val id = c.calibrations.save(
            source, offsetDb, CalibrationEntity.METHOD_MANUAL, notes,
            warnings = if (CalibrationMath.implausible(offsetDb)) listOf(CalibrationWarning.IMPLAUSIBLE_OFFSET.name) else emptyList(),
            mic = mic.key, micLabel = mic.label,
        )
        activated(id, previous, offsetDb, calibrated = true, mic = mic.key)
    }

    fun resetToDefault() = viewModelScope.launch {
        val mic = c.microphones.resolveNow()
        val previous = currentOffset(mic.key)
        val id = c.calibrations.save(
            source, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, CalibrationEntity.METHOD_RESET, "",
            mic = mic.key, micLabel = mic.label,
        )
        // Back to uncalibrated: the floor follows, but nothing is re-evaluated (that would mark
        // uncalibrated data as calibrated).
        activated(id, previous, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, calibrated = false, mic = mic.key)
    }

    // ---- Re-evaluating earlier measurements ----------------------------------------------------

    val recalibration: StateFlow<Recalibrator.State> = c.recalibrator.state

    fun confirmRecalibration() {
        val offer = _state.value.recalOffer ?: return
        _state.update { it.copy(recalOffer = null) }
        c.recalibrator.start(offer.target, offer.mic)
    }

    fun dismissRecalibration() { _state.update { it.copy(recalOffer = null) } }

    fun acknowledgeRecalibration() = c.recalibrator.acknowledge()

    suspend fun exportHistory(): File = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        File(dir, "wenigerlaerm-kalibrierungen.json").apply { writeText(c.calibrations.exportJson()) }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    override fun onCleared() {
        capture?.stop()
        capture = null
        c.calibrationActive.value = false
    }
}
