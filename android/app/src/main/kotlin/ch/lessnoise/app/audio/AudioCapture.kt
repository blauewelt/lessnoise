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
    /** 0.7.2: the microphone the capture was opened with ([MicChoice.resolve]). */
    val mic: MicChoice.Resolution = MicChoice.resolve(null, emptyList()),
)

/** 0.7.2: Android keeps routing another input than the resolved microphone, even after one restart. */
class MicRouteException : IllegalStateException(MicChoice.ROUTE_MESSAGE)

/**
 * Microphone capture on a dedicated thread: 48 kHz, mono, PCM float (16-bit fallback).
 *
 * 0.7.2: records from the microphone [MicChoice.resolve] returned (the built-in one by default, a
 * chosen one while it is connected): it is pinned with setPreferredDevice before recording starts,
 * and the routed device is checked after it ([MicChoice.routeMatches]). If Android routes another
 * input, the AudioRecord is built and started once more; if it still does, the start fails with
 * [MicChoice.ROUTE_MESSAGE]. While recording, a routing change to another input stops the delivery of
 * blocks and calls [onMisrouted] (the measurement service then reopens the capture). Never starts
 * Bluetooth SCO and never sets a communication device.
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
    /** 0.6.6: the input delivering the audio changed (Labor diagnostics). */
    private val onInputDevice: (String) -> Unit = {},
    /**
     * 0.7.2: while recording, Android routed another input than the resolved microphone; no further
     * block is delivered. Default: the capture error [MicChoice.ROUTE_MESSAGE].
     */
    private val onMisrouted: () -> Unit = { onError(MicChoice.ROUTE_MESSAGE) },
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
    /** The microphone this capture records from. */
    private var mic: MicChoice.Resolution = MicChoice.resolve(null, emptyList())
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    /** The route was not known yet at the start: check once more before the first block is used. */
    @Volatile private var recheckRoute = false
    /** Another input took over: no further block reaches [onBlock] until [stop]. */
    @Volatile private var wrongRoute = false

    /** Opens [sourceName] on the microphone [target] (see the class comment). */
    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun start(sourceName: String, target: MicChoice.Resolution): CaptureInfo {
        check(!running) { "already running" }
        mic = target
        // Never names or addresses in the log: only counts and kinds.
        Log.i(TAG, "microphone ${MicChoice.kindId(target.key)}; ${inputDevices().size} inputs")
        var opened = open(sourceName, target)
        if (opened.matches == false) {
            // Android routes another input than the one pinned: build and start the AudioRecord once more.
            Log.i(TAG, "routed input differs from the ${MicChoice.kindId(target.key)} microphone: restarting once")
            release(opened.rec)
            opened = open(sourceName, target)
            if (opened.matches == false) {
                release(opened.rec)
                throw MicRouteException()
            }
        }
        val rec = opened.rec
        record = rec
        recheckRoute = opened.matches == null
        val info = CaptureInfo(
            source = sourceName,
            sampleRate = rec.sampleRate,
            encoding = if (opened.encoding == AudioFormat.ENCODING_PCM_FLOAT) "PCM_FLOAT" else "PCM_16BIT",
            effects = opened.effects,
            inputDevice = BuiltInMic.label(opened.routed),
            mic = target,
        )
        running = true
        registerSilencingCallback(rec)
        registerRoutingListener(rec)
        val isFloat = opened.encoding == AudioFormat.ENCODING_PCM_FLOAT
        thread = Thread({ loop(rec, isFloat) }, "lessnoise-capture").apply { start() }
        return info
    }

    private class Opened(
        val rec: AudioRecord, val encoding: Int, val effects: List<String>,
        val routed: BuiltInMic.Device?, val matches: Boolean?,
    )

    /** Builds, pins and starts one AudioRecord; releases it and rethrows on any failure. */
    @SuppressLint("MissingPermission")
    private fun open(sourceName: String, target: MicChoice.Resolution): Opened {
        val source = AudioSourceSelector.androidSource(sourceName)
        var encoding = AudioFormat.ENCODING_PCM_FLOAT
        var rec = build(source, encoding)
        if (rec == null) {
            encoding = AudioFormat.ENCODING_PCM_16BIT
            rec = build(source, encoding) ?: throw IllegalStateException("Mikrofon nicht verfügbar")
        }
        val fx = disableEffects(rec.audioSessionId)
        try {
            if (rec.sampleRate != Acoustics.SAMPLE_RATE) {
                throw IllegalStateException("Mikrofon nicht unterstützt (${rec.sampleRate} Hz)")
            }
            // Pin the resolved microphone before recording starts. Android's ids change on every
            // reconnect, so the device is looked up afresh by its identity.
            val preferred = preferredDevice(target)
            if (preferred != null && !rec.setPreferredDevice(preferred)) Log.w(TAG, "setPreferredDevice refused")
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Mikrofon ist belegt (andere App?)")
            }
            val routed = routedDevice(rec)
            return Opened(rec, encoding, fx, routed, MicChoice.routeMatches(target, routed))
        } catch (e: Exception) {
            release(rec)
            throw e
        }
    }

    /** Releases a record [open] produced (and the effects attached to it). */
    private fun release(rec: AudioRecord) {
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        try { rec.stop() } catch (_: Exception) {}
        rec.release()
        if (record === rec) record = null
    }

    /** The Android device of [target]: the built-in one [BuiltInMic.pick] chooses, or the input with its key. */
    private fun preferredDevice(target: MicChoice.Resolution): AudioDeviceInfo? {
        if (target.device == null) return null
        val inputs = inputDevices()
        return if (target.builtIn) {
            val pick = BuiltInMic.pick(inputs.map { it.first }) ?: return null
            inputs.firstOrNull { it.first.id == pick.id }?.second
        } else {
            inputs.firstOrNull { MicChoice.key(it.first) == target.key }?.second
        }
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

    /** AudioManager's input devices as descriptors, with the Android object for pinning. */
    private fun inputDevices(): List<Pair<BuiltInMic.Device, AudioDeviceInfo>> = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { Microphones.describe(it) to it }
    } catch (e: Exception) {
        Log.w(TAG, "input devices unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    /**
     * The input that actually delivers the audio: getRoutedDevice(), and on API 29+ the active
     * recording configuration's device if Android does not report a route (yet). Null if unknown.
     */
    private fun routedDevice(rec: AudioRecord): BuiltInMic.Device? {
        val routed = try { rec.routedDevice } catch (_: Exception) { null }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { rec.activeRecordingConfiguration?.audioDevice } catch (_: Exception) { null }
            } else null
        return routed?.let { Microphones.describe(it) }
    }

    /** Re-checks the route; false (and [onMisrouted], once) if Android now routes another input. */
    private fun verifyRoute(rec: AudioRecord): Boolean {
        val routed = routedDevice(rec)
        if (MicChoice.routeMatches(mic, routed) == false) {
            if (!wrongRoute && running) {
                wrongRoute = true
                onMisrouted()
            }
            return false
        }
        if (routed != null) onInputDevice(BuiltInMic.label(routed))
        return true
    }

    /** A device connected while recording may take over the input: stop delivering rather than measure through it. */
    private fun registerRoutingListener(rec: AudioRecord) {
        if (mic.device == null) return // no device pinned (no built-in microphone reported): as before 0.6.6
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
