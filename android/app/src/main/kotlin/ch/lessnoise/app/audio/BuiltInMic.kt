package ch.lessnoise.app.audio

/**
 * 0.6.6: the app measures only with the phone's built-in microphone, never with a connected
 * headset's (Bluetooth, LE Audio, wired, USB). A headset microphone is tuned to the wearer's voice,
 * and the stored calibration belongs to the built-in microphone, so a headset would silently carry
 * the wrong offset.
 *
 * Pure logic on plain [Device] descriptors (no Android classes), so it is unit-tested on the JVM.
 * [AudioCapture] turns AudioManager.getDevices(GET_DEVICES_INPUTS) into descriptors, pins the
 * device [pick] returns with AudioRecord.setPreferredDevice, and after startRecording() checks
 * AudioRecord.getRoutedDevice() with [check] (and again on every routing change while it records).
 */
object BuiltInMic {
    // AudioDeviceInfo.TYPE_* values (BuiltInMicTest checks them against the SDK's constants).
    const val TYPE_BUILTIN_MIC = 15
    const val TYPE_WIRED_HEADSET = 3
    const val TYPE_BLUETOOTH_SCO = 7
    const val TYPE_USB_DEVICE = 11
    const val TYPE_USB_HEADSET = 22
    const val TYPE_HEARING_AID = 23
    const val TYPE_BLE_HEADSET = 26

    /** The address AOSP gives the phone's main (bottom) microphone when a phone reports several. */
    const val MAIN_MIC_ADDRESS = "bottom"

    /** Shown (error line on «Messen», «Kalibrieren») when Android routes another microphone. */
    const val WRONG_MIC_MESSAGE = "Gemessen wird nur mit dem Mikrofon des Telefons. Bitte das Headset trennen und neu starten."

    /** One input device as AudioManager reports it. */
    data class Device(val id: Int, val type: Int, val address: String = "", val productName: String = "")

    enum class Route {
        /** The built-in microphone delivers the audio. */
        OK,
        /** Another input (headset, USB …) delivers the audio: do not measure. */
        NOT_BUILT_IN,
        /** Android does not say which input is used (getRoutedDevice() null): measure, as before 0.6.6. */
        UNKNOWN,
        /** The phone reports no built-in microphone at all: measure as before 0.6.6 (never brick the app). */
        NO_BUILT_IN_MIC,
    }

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

    /** Is the [routed] input acceptable, given the built-in microphone [pick] found ([builtIn])? */
    fun check(builtIn: Device?, routed: Device?): Route = when {
        builtIn == null -> Route.NO_BUILT_IN_MIC
        routed == null -> Route.UNKNOWN
        routed.type == TYPE_BUILTIN_MIC -> Route.OK
        else -> Route.NOT_BUILT_IN
    }

    /**
     * The error line for a failed start or a capture error: [WRONG_MIC_MESSAGE] stands alone (it is
     * already a complete sentence), anything else keeps the caller's prefix as before 0.6.6.
     */
    fun errorText(prefix: String, message: String?): String =
        if (message == WRONG_MIC_MESSAGE) WRONG_MIC_MESSAGE else "$prefix$message"

    /** Only a proven foreign microphone stops the measurement. */
    fun mayMeasure(route: Route): Boolean = route != Route.NOT_BUILT_IN

    /** Labor diagnostics: which input delivered the audio, e.g. «eingebautes Mikrofon (bottom)». */
    fun label(routed: Device?, route: Route): String {
        val device = routed?.let { d ->
            val type = typeName(d.type)
            if (d.address.isNotBlank()) "$type (${d.address})" else type
        } ?: "unbekannt"
        return if (route == Route.NO_BUILT_IN_MIC) "$device · kein eingebautes Mikrofon gemeldet" else device
    }

    fun typeName(type: Int): String = when (type) {
        TYPE_BUILTIN_MIC -> "eingebautes Mikrofon"
        TYPE_BLUETOOTH_SCO -> "Bluetooth-Headset"
        TYPE_BLE_HEADSET -> "LE-Audio-Headset"
        TYPE_WIRED_HEADSET -> "Kabel-Headset"
        TYPE_USB_HEADSET -> "USB-Headset"
        TYPE_USB_DEVICE -> "USB-Gerät"
        TYPE_HEARING_AID -> "Hörgerät"
        else -> "Eingang Typ $type"
    }
}
