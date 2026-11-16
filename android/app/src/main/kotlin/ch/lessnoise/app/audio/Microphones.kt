package ch.lessnoise.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 0.7.2: the connected input devices (AudioManager.getDevices(GET_DEVICES_INPUTS), kept current by
 * an AudioDeviceCallback) and the user's microphone choice (SharedPreferences «microphone»), combined
 * into the [resolution] every capture uses ([MicChoice.resolve]). No permission: listing input
 * devices needs none, and nothing here opens the microphone.
 *
 * Stored: the chosen microphone's key (kind + hash, [MicChoice.key]) and its label for the list
 * while it is not connected. Never sent.
 */
class Microphones(context: Context, scope: CoroutineScope) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = context.getSharedPreferences("microphone", Context.MODE_PRIVATE)

    private val _inputs = MutableStateFlow(query())
    /** The connected input devices, in Android's order. */
    val inputs: StateFlow<List<BuiltInMic.Device>> = _inputs

    private val _chosen = MutableStateFlow(readChosen())
    /** The user's choice; null = the built-in microphone (the default). */
    val chosen: StateFlow<MicChoice.Chosen?> = _chosen

    /** The microphone to use now. */
    val resolution: StateFlow<MicChoice.Resolution> =
        combine(_chosen, _inputs) { c, i -> MicChoice.resolve(c, i) }
            .stateIn(scope, SharingStarted.Eagerly, MicChoice.resolve(_chosen.value, _inputs.value))

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    init {
        try {
            am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "device callback unavailable: ${e.javaClass.simpleName}")
        }
    }

    /** Re-reads the connected inputs (also called right before a capture starts). */
    fun refresh() { _inputs.value = query() }

    /** The resolution with a fresh device list (start of a measurement or a calibration). */
    fun resolveNow(): MicChoice.Resolution {
        refresh()
        return MicChoice.resolve(_chosen.value, _inputs.value)
    }

    /** «Mikrofon»: remember [option] (null or the built-in one: back to the default). */
    fun choose(option: MicChoice.Option?) {
        if (option == null || MicChoice.isBuiltIn(option.key)) {
            prefs.edit().remove(KEY).remove(LABEL).apply()
            _chosen.value = null
        } else {
            prefs.edit().putString(KEY, option.key).putString(LABEL, option.label).apply()
            _chosen.value = MicChoice.Chosen(option.key, option.label)
        }
    }

    private fun readChosen(): MicChoice.Chosen? {
        val key = prefs.getString(KEY, null) ?: return null
        return MicChoice.Chosen(key, prefs.getString(LABEL, null) ?: key)
    }

    private fun query(): List<BuiltInMic.Device> = try {
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { describe(it) }
    } catch (e: Exception) {
        Log.w(TAG, "input devices unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    companion object {
        private const val TAG = "Microphones"
        private const val KEY = "mic_key"
        private const val LABEL = "mic_label"

        fun describe(d: AudioDeviceInfo) = BuiltInMic.Device(
            id = d.id,
            type = d.type,
            address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) d.address.orEmpty() else "",
            productName = d.productName?.toString().orEmpty(),
        )
    }
}
