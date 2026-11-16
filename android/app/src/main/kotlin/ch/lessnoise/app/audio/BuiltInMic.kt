package ch.lessnoise.app.audio

/**
 * The phone's built-in microphone and the descriptors of Android's input devices (pure, no Android
 * classes, so it is unit-tested on the JVM).
 *
 * 0.6.6–0.7.1 measured only with the built-in microphone and refused any other route. Since 0.7.2
 * the built-in microphone is still the default and nothing switches to a headset by itself, but the
 * user may choose another microphone ([MicChoice], «Einstellungen → Erweitert → Mikrofon»).
 * [AudioCapture] pins the device [MicChoice.resolve] returns with AudioRecord.setPreferredDevice and
 * checks the routed device with [MicChoice.routeMatches].
 */
object BuiltInMic {
    // AudioDeviceInfo.TYPE_* values (BuiltInMicTest checks them against the SDK's constants).
    const val TYPE_BUILTIN_MIC = 15
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_LINE_ANALOG = 5
    const val TYPE_LINE_DIGITAL = 6
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_BLUETOOTH_A2DP = 8
    const val TYPE_USB_DEVICE = 11
    const val TYPE_USB_ACCESSORY = 12
    const val TYPE_AUX_LINE = 19
    const val TYPE_USB_HEADSET = 22
    const val TYPE_HEARING_AID = 23
    const val TYPE_BLE_HEADSET = 26

    /** The address AOSP gives the phone's main (bottom) microphone when a phone reports several. */
    const val MAIN_MIC_ADDRESS = "bottom"

    /** One input device as AudioManager reports it. */
    data class Device(val id: Int, val type: Int, val address: String = "", val productName: String = "")

    /**
     * The built-in microphone to pin, or null if the phone reports none. With several built-in
     * microphones (some phones list «bottom», «back», «top») the main one, «bottom», is preferred,
     * because that is the one Android uses by default and the one the calibration was made with;
     * otherwise the first one in Android's order.
     */
    fun pick(inputs: List<Device>): Device? {
        val builtIn = inputs.filter { it.type == TYPE_BUILTIN_MIC }
        return builtIn.firstOrNull { it.address.equals(MAIN_MIC_ADDRESS, ignoreCase = true) } ?: builtIn.firstOrNull()
    }

    /** Labor diagnostics: which input delivered the audio, e.g. «eingebautes Mikrofon (bottom)». */
    fun label(routed: Device?): String = routed?.let { d ->
        val type = typeName(d.type)
        if (d.address.isNotBlank()) "$type (${d.address})" else type
    } ?: "unbekannt"

    fun typeName(type: Int): String = when (type) {
        TYPE_BUILTIN_MIC -> "eingebautes Mikrofon"
        TYPE_BLUETOOTH_SCO -> "Bluetooth-Headset"
        TYPE_BLUETOOTH_A2DP -> "Bluetooth-Gerät"
        TYPE_BLE_HEADSET -> "LE-Audio-Headset"
        TYPE_WIRED_HEADSET -> "Kabel-Headset"
        TYPE_LINE_ANALOG -> "Line-Eingang"
        TYPE_LINE_DIGITAL -> "Digitaleingang"
        TYPE_AUX_LINE -> "Aux-Eingang"
        TYPE_USB_HEADSET -> "USB-Headset"
        TYPE_USB_DEVICE -> "USB-Mikrofon"
        TYPE_USB_ACCESSORY -> "USB-Zubehör"
        TYPE_HEARING_AID -> "Hörgerät"
        else -> "Eingang Typ $type"
    }
}
