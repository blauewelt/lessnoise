package ch.lessnoise.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import ch.lessnoise.dsp.Acoustics
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Which microphone path is used. Calibration is stored per source. */
object AudioSourceSelector {
    const val UNPROCESSED = "UNPROCESSED"
    const val VOICE_RECOGNITION = "VOICE_RECOGNITION"

    fun unprocessedSupported(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    /** UNPROCESSED if the device declares support for it, else VOICE_RECOGNITION. */
    fun select(context: Context): String =
        if (unprocessedSupported(context)) UNPROCESSED else VOICE_RECOGNITION

    fun androidSource(name: String): Int = when (name) {
        UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    fun labelDe(name: String): String = when (name) {
        UNPROCESSED -> "unbearbeitet"
        VOICE_RECOGNITION -> "Sprachmodus"
        else -> name
    }
}

data class CaptureInfo(
    val source: String,
    val sampleRate: Int,
    val encoding: String,
    /** e.g. "AGC: deaktiviert", "NS: nicht vorhanden". */
    val effects: List<String>,
    /** 0.6.6: the input that delivers the audio, e.g. «eingebautes Mikrofon (bottom)» (Labor diagnostics). */
    val inputDevice: String = "unbekannt",
    /** 0.7.1: «Mikrofon: eingebaut» (BuiltInMic.fact), shown next to «Audioquelle» in every edition. */
    val microphone: String = "Mikrofon: unbekannt",
)

/** 0.6.6: Android routes a microphone other than the phone's own; the app does not measure with it. */
class WrongMicrophoneException : IllegalStateException(BuiltInMic.WRONG_MIC_MESSAGE)

/**
 * Microphone capture on a dedicated thread: 48 kHz, mono, PCM float (16-bit fallback).
 *
 * 0.6.6: always the phone's built-in microphone ([BuiltInMic]): it is pinned with
 * setPreferredDevice, and if Android still routes another input (a headset) at the start or later,
 * the capture fails with [BuiltInMic.WRONG_MIC_MESSAGE] instead of measuring through it. A phone that
 * reports no built-in microphone measures as before.
 *
 * PRIVACY: audio exists only in the reusable [FloatArray] block (125 ms) handed to [onBlock]
 * and in AudioRecord's own internal buffer. Nothing is written to disk, logged or sent anywhere.
 * [onBlock] must consume the samples synchronously; the array is overwritten by the next read.
 */
class AudioCapture(
    private val context: Context,
    private val onBlock: (samples: FloatArray, count: Int) -> Unit,
    private val onError: (String) -> Unit = {},
    /**
     * Called (on an arbitrary thread) when Android silences or un-silences this capture, e.g.
     * while a phone call or a voice assistant owns the microphone (API 29+). While silenced the
     * app receives zeros; callers must not evaluate that audio.
     */
    private val onSilenced: (Boolean) -> Unit = {},
    /** 0.6.6: the input delivering the audio changed to another built-in microphone (Labor diagnostics). */
    private val onInputDevice: (String) -> Unit = {},
    /** 0.7.1: the routed input as BuiltInMic.fact («Mikrofon: eingebaut»), after every re-check. */
    private val onMicrophone: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val BLOCK = Acoustics.SAMPLE_RATE / 8 // 125 ms
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val effects = ArrayList<AudioEffect>()
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var callbackExecutor: ExecutorService? = null
    @Volatile private var lastSilenced = false
    /** The built-in microphone pinned at the start (null: the phone reports none). */
    private var builtIn: BuiltInMic.Device? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    /** The route was not known yet at the start: check once more before the first block is used. */
    @Volatile private var recheckRoute = false
    /** Another microphone took over: no further block reaches [onBlock] until [stop]. */
    @Volatile private var wrongRoute = false

    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun start(sourceName: String): CaptureInfo {
        check(!running) { "already running" }
        val source = AudioSourceSelector.androidSource(sourceName)
        var encoding = AudioFormat.ENCODING_PCM_FLOAT
        var rec = build(source, encoding)
        if (rec == null) {
            encoding = AudioFormat.ENCODING_PCM_16BIT
            rec = build(source, encoding) ?: throw IllegalStateException("Mikrofon nicht verfügbar")
        }
        record = rec
        val fx = disableEffects(rec.audioSessionId)
        val info: CaptureInfo
        try {
            if (rec.sampleRate != Acoustics.SAMPLE_RATE) {
                throw IllegalStateException("Mikrofon nicht unterstützt (${rec.sampleRate} Hz)")
            }
            // 0.6.6: pin the phone's own microphone before recording starts.
            val inputs = inputDevices()
            val builtInCount = inputs.count { it.first.type == BuiltInMic.TYPE_BUILTIN_MIC }
            val mic = BuiltInMic.pick(inputs.map { it.first })
            builtIn = mic
            // Never the address or product name in the log: only counts and types.
            if (mic == null) Log.i(TAG, "no built-in microphone listed (${inputs.size} inputs): no preferred device")
            else Log.i(TAG, "built-in microphones: $builtInCount of ${inputs.size} inputs")
            val micInfo = mic?.let { m -> inputs.firstOrNull { it.first.id == m.id }?.second }
            if (micInfo != null && !rec.setPreferredDevice(micInfo)) Log.w(TAG, "setPreferredDevice refused")
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Mikrofon ist belegt (andere App?)")
            }
            val routed = routedDevice(rec)
            val route = BuiltInMic.check(mic, routed)
            if (!BuiltInMic.mayMeasure(route)) throw WrongMicrophoneException()
            recheckRoute = route == BuiltInMic.Route.UNKNOWN
            info = CaptureInfo(
                source = sourceName,
                sampleRate = rec.sampleRate,
                encoding = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) "PCM_FLOAT" else "PCM_16BIT",
                effects = fx,
                inputDevice = BuiltInMic.label(routed, route),
                microphone = BuiltInMic.fact(routed),
            )
        } catch (e: Exception) {
            // Release everything: the caller only keeps a reference after a successful start.
            effects.forEach { try { it.release() } catch (_: Exception) {} }
            effects.clear()
            try { rec.stop() } catch (_: Exception) {}
            rec.release(); record = null
            throw e
        }
        running = true
        registerSilencingCallback(rec)
        registerRoutingListener(rec)
        val isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
        thread = Thread({ loop(rec, isFloat) }, "lessnoise-capture").apply { start() }
        return info
    }

    /** API 29+: follow AudioRecordingConfiguration.isClientSilenced for this AudioRecord. */
    private fun registerSilencingCallback(rec: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                val cfg = try { rec.activeRecordingConfiguration } catch (_: Exception) { null }
                    ?: configs?.firstOrNull { it.clientAudioSessionId == rec.audioSessionId }
                    ?: return
                val silenced = cfg.isClientSilenced
                if (silenced != lastSilenced) {
                    lastSilenced = silenced
                    onSilenced(silenced)
                }
            }
        }
        try {
            val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "lessnoise-silence") }
            callbackExecutor = exec
            rec.registerAudioRecordingCallback(exec, cb)
            recordingCallback = cb
            // Initial state (the mic may already be taken when we start).
            rec.activeRecordingConfiguration?.let { if (it.isClientSilenced) { lastSilenced = true; onSilenced(true) } }
        } catch (e: Exception) {
            Log.w(TAG, "recording callback unavailable: ${e.javaClass.simpleName}")
        }
    }

    /** AudioManager's input devices as descriptors for [BuiltInMic], with the Android object for pinning. */
    private fun inputDevices(): List<Pair<BuiltInMic.Device, AudioDeviceInfo>> = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { describe(it) to it }
    } catch (e: Exception) {
        Log.w(TAG, "input devices unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun describe(d: AudioDeviceInfo) = BuiltInMic.Device(
        id = d.id,
        type = d.type,
        address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) d.address.orEmpty() else "",
        productName = d.productName?.toString().orEmpty(),
    )

    /**
     * The input that actually delivers the audio: getRoutedDevice(), and on API 29+ the active
     * recording configuration's device if Android does not report a route (yet). Null if unknown.
     */
    private fun routedDevice(rec: AudioRecord): BuiltInMic.Device? {
        val routed = try { rec.routedDevice } catch (_: Exception) { null }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { rec.activeRecordingConfiguration?.audioDevice } catch (_: Exception) { null }
            } else null
        return routed?.let { describe(it) }
    }

    /** Re-checks the route; false (and [onError]) if Android now routes another microphone. */
    private fun verifyRoute(rec: AudioRecord): Boolean {
        val routed = routedDevice(rec)
        val route = BuiltInMic.check(builtIn, routed)
        if (routed != null) onMicrophone(BuiltInMic.fact(routed))
        if (!BuiltInMic.mayMeasure(route)) {
            if (!wrongRoute && running) {
                wrongRoute = true
                onError(BuiltInMic.WRONG_MIC_MESSAGE)
            }
            return false
        }
        if (routed != null) onInputDevice(BuiltInMic.label(routed, route))
        return true
    }

    /** A headset connected while measuring may take over the input: stop rather than measure through it. */
    private fun registerRoutingListener(rec: AudioRecord) {
        if (builtIn == null) return // no built-in microphone reported: measure as before 0.6.6
        val l = AudioRouting.OnRoutingChangedListener { if (running) verifyRoute(rec) }
        try {
            rec.addOnRoutingChangedListener(l, Handler(Looper.getMainLooper()))
            routingListener = l
        } catch (e: Exception) {
            Log.w(TAG, "routing listener unavailable: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun build(source: Int, encoding: Int): AudioRecord? {
        val fmt = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(Acoustics.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val min = AudioRecord.getMinBufferSize(Acoustics.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
        if (min <= 0) return null
        // ~0.5 s of internal buffer gives slack if the processing thread is briefly delayed.
        val size = maxOf(min, Acoustics.SAMPLE_RATE / 2 * bytesPerSample)
        return try {
            val r = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(size)
                .build()
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord build failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Explicitly disables platform AGC / noise suppression / echo cancellation if present. */
    private fun disableEffects(session: Int): List<String> {
        val out = ArrayList<String>()
        fun handle(name: String, available: Boolean, create: () -> AudioEffect?) {
            if (!available) { out += "$name: nicht vorhanden"; return }
            try {
                val e = create()
                if (e == null) { out += "$name: nicht erstellbar"; return }
                e.enabled = false
                effects += e
                out += "$name: deaktiviert" + if (e.enabled) " (fehlgeschlagen!)" else ""
            } catch (ex: Exception) {
                out += "$name: Fehler"
            }
        }
        handle("AGC", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(session) }
        handle("NS", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(session) }
        handle("AEC", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(session) }
        return out
    }

    private fun loop(rec: AudioRecord, isFloat: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(BLOCK)
        val shorts = if (isFloat) null else ShortArray(BLOCK)
        try {
            while (running) {
                var filled = 0
                while (filled < BLOCK && running) {
                    val n = if (isFloat) {
                        rec.read(buf, filled, BLOCK - filled, AudioRecord.READ_BLOCKING)
                    } else {
                        rec.read(shorts!!, filled, BLOCK - filled, AudioRecord.READ_BLOCKING).also { k ->
                            for (i in filled until filled + maxOf(k, 0)) buf[i] = shorts[i] / 32768f
                        }
                    }
                    if (n < 0) throw IllegalStateException("Mikrofon-Fehler $n")
                    filled += n
                }
                // The route was unknown at the start: check it before the first audio is used.
                if (filled == BLOCK && recheckRoute) {
                    recheckRoute = false
                    if (!verifyRoute(rec)) return
                }
                if (filled == BLOCK && !wrongRoute) onBlock(buf, BLOCK)
            }
        } catch (e: Exception) {
            if (running) onError(e.message ?: e.javaClass.simpleName)
        } finally {
            buf.fill(0f)
        }
    }

    fun stop() {
        running = false
        val cb = recordingCallback
        recordingCallback = null
        if (cb != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { record?.unregisterAudioRecordingCallback(cb) } catch (_: Exception) {}
        }
        callbackExecutor?.shutdown()
        callbackExecutor = null
        lastSilenced = false
        routingListener?.let { l -> try { record?.removeOnRoutingChangedListener(l) } catch (_: Exception) {} }
        routingListener = null
        recheckRoute = false
        wrongRoute = false
        try { record?.stop() } catch (_: Exception) {}
        thread?.join(2000)
        thread = null
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        record?.release()
        record = null
    }
}
