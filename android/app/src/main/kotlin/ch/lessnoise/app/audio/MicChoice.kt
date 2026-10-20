package ch.lessnoise.app.audio

import ch.lessnoise.app.audio.BuiltInMic.Device
import java.security.MessageDigest

/**
 * 0.7.2: the manual microphone choice («Einstellungen → Erweitert → Mikrofon»). Pure logic on
 * [BuiltInMic.Device] descriptors, unit-tested on the JVM (MicChoiceTest).
 *
 * The owner's design: by default the app stays on the phone's built-in microphone — never an
 * automatic switch to a headset. The user can choose another microphone; once chosen, the app uses
 * it whenever it is connected (at the start of a measurement and when it is connected during one),
 * and the built-in one otherwise.
 *
 * A microphone is remembered by its [key]: «builtin» for the phone's own, else «<kind>:<hash>» where
 * the hash is the first 16 hex digits of SHA-256 over type, product name and address — never
 * Android's device id (it changes on every reconnect), and never the address itself (a Bluetooth
 * address would identify the headset in the calibration export and the CSV). The key is stored on the
 * phone only (settings, calibrations, minutes, events); what is sent is only the [Kind]
 * ([wireAudioSource]).
 */
object MicChoice {
    const val BUILTIN = "builtin"

    /** What kind of microphone; [id] is what the server receives (in `audio_source`, see [wireAudioSource]). */
    enum class Kind(val id: String) { BUILTIN("builtin"), WIRED("wired"), USB("usb"), BLUETOOTH("bluetooth") }

    /** The kind of an input type, or null for inputs that are no microphone (telephony, tuners, submix …). */
    fun kindOf(type: Int): Kind? = when (type) {
        BuiltInMic.TYPE_BUILTIN_MIC -> Kind.BUILTIN
        BuiltInMic.TYPE_WIRED_HEADSET, BuiltInMic.TYPE_LINE_ANALOG, BuiltInMic.TYPE_LINE_DIGITAL, BuiltInMic.TYPE_AUX_LINE -> Kind.WIRED
        BuiltInMic.TYPE_USB_DEVICE, BuiltInMic.TYPE_USB_ACCESSORY, BuiltInMic.TYPE_USB_HEADSET -> Kind.USB
        BuiltInMic.TYPE_BLUETOOTH_SCO, BuiltInMic.TYPE_BLUETOOTH_A2DP, BuiltInMic.TYPE_HEARING_AID, BuiltInMic.TYPE_BLE_HEADSET -> Kind.BLUETOOTH
        else -> null
    }

    /**
     * Whether the app can record from this input with AudioRecord.setPreferredDevice alone. A classic
     * Bluetooth headset (HFP, TYPE_BLUETOOTH_SCO) only delivers audio over a SCO link, which the app
     * would have to open itself (Bluetooth SCO or a communication device — call routing, which the
     * app never touches); A2DP and hearing aids are not microphones for an app. LE Audio (TYPE_BLE_HEADSET),
     * USB and wired microphones route with setPreferredDevice.
     */
    fun usable(type: Int): Boolean = when (type) {
        BuiltInMic.TYPE_BLUETOOTH_SCO, BuiltInMic.TYPE_BLUETOOTH_A2DP, BuiltInMic.TYPE_HEARING_AID -> false
        else -> kindOf(type) != null
    }

    /** The remembered identity of [d] (see the class comment). */
    fun key(d: Device): String {
        val kind = kindOf(d.type) ?: return "other:" + hash(d)
        return if (kind == Kind.BUILTIN) BUILTIN else "${kind.id}:" + hash(d)
    }

    private fun hash(d: Device): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("${d.type}|${d.productName.trim()}|${d.address.trim()}".toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    /** «builtin», «usb», «bluetooth», «wired» (or «other») of a [key]. */
    fun kindId(key: String): String = key.substringBefore(':')

    fun isBuiltIn(key: String): Boolean = key == BUILTIN

    /** The name shown in the list: type and product name, e.g. «USB-Mikrofon (Rode NT-USB)». */
    fun label(d: Device): String {
        if (d.type == BuiltInMic.TYPE_BUILTIN_MIC) return BUILTIN_LABEL
        val name = d.productName.trim()
        val type = BuiltInMic.typeName(d.type)
        return if (name.isEmpty()) type else "$type ($name)"
    }

    const val BUILTIN_LABEL = "Eingebautes Mikrofon"

    /** One row of «Mikrofon»: a connected input. */
    data class Option(val key: String, val label: String, val usable: Boolean, val standard: Boolean)

    /**
     * The connected microphones as the list shows them: the built-in one first (once, even if the
     * phone lists several, marked «Standard»), then the others in Android's order, one row per
     * identity. Inputs that are no microphone are left out.
     */
    fun options(inputs: List<Device>): List<Option> {
        val out = ArrayList<Option>()
        BuiltInMic.pick(inputs)?.let { out += Option(BUILTIN, BUILTIN_LABEL, usable = true, standard = true) }
        for (d in inputs) {
            val kind = kindOf(d.type) ?: continue
            if (kind == Kind.BUILTIN) continue
            val k = key(d)
            if (out.none { it.key == k }) out += Option(k, label(d), usable(d.type), standard = false)
        }
        return out
    }

    /** One line of the list «Mikrofon»: [note] «Standard», «nicht nutzbar», «nicht verbunden» or null. */
    data class Row(val option: Option, val note: String?, val selected: Boolean, val selectable: Boolean)

    /**
     * The list «Einstellungen → Erweitert → Mikrofon»: the connected microphones ([options]), the
     * chosen one selected (the built-in one without a choice); a chosen microphone that is not
     * connected stays in the list, selected, marked «nicht verbunden». A microphone the app cannot
     * use ([usable]) is shown «nicht nutzbar» and cannot be chosen.
     */
    fun rows(chosen: Chosen?, inputs: List<Device>): List<Row> {
        val selectedKey = chosen?.key ?: BUILTIN
        val opts = options(inputs)
        val out = opts.map { o ->
            Row(
                o, note = when { o.standard -> "Standard"; !o.usable -> "nicht nutzbar"; else -> null },
                selected = o.key == selectedKey, selectable = o.usable,
            )
        }.toMutableList()
        if (opts.none { it.key == BUILTIN }) {
            // A phone that lists no built-in microphone: the default row stays, so the choice can be undone.
            out.add(0, Row(Option(BUILTIN, BUILTIN_LABEL, usable = true, standard = true), "Standard", selectedKey == BUILTIN, true))
        }
        if (chosen != null && !isBuiltIn(chosen.key) && opts.none { it.key == chosen.key }) {
            out += Row(Option(chosen.key, chosen.label, usable = true, standard = false), "nicht verbunden", selected = true, selectable = false)
        }
        return out
    }

    /** The user's choice as stored (null or [BUILTIN] key: the default). */
    data class Chosen(val key: String, val label: String)

    /**
     * The microphone a measurement or a calibration uses now. [key]: the identity its calibration and
     * its records carry; [device]: the input to pin (null: no preference, e.g. a phone that lists no
     * built-in microphone); [status]: the line «Mikrofon: …».
     */
    data class Resolution(val key: String, val label: String, val device: Device?, val status: String) {
        val builtIn: Boolean get() = isBuiltIn(key)
    }

    const val STATUS_BUILTIN = "Mikrofon: eingebaut"
    const val STATUS_NOT_CONNECTED = "Mikrofon: eingebaut (gewähltes nicht verbunden)"
    const val STATUS_NOT_USABLE = "Mikrofon: eingebaut (gewähltes nicht nutzbar)"

    /**
     * [chosen] and the connected [inputs] → the microphone to use: the chosen one if it is connected
     * and usable, else the built-in one (with the reason in the status line).
     */
    fun resolve(chosen: Chosen?, inputs: List<Device>): Resolution {
        val builtIn = Resolution(BUILTIN, BUILTIN_LABEL, BuiltInMic.pick(inputs), STATUS_BUILTIN)
        if (chosen == null || isBuiltIn(chosen.key)) return builtIn
        val match = inputs.firstOrNull { kindOf(it.type) != null && key(it) == chosen.key }
            ?: return builtIn.copy(status = STATUS_NOT_CONNECTED)
        if (!usable(match.type)) return builtIn.copy(status = STATUS_NOT_USABLE)
        val label = label(match)
        return Resolution(chosen.key, label, match, "Mikrofon: $label")
    }

    /**
     * Does the [routed] input (AudioRecord.getRoutedDevice) belong to the resolved microphone [r]?
     * Null: Android does not say (measure, check again later). Any built-in microphone counts as the
     * built-in one (some phones switch between «bottom» and «back»). A phone that lists no built-in
     * microphone has no preference to check (true, as before 0.6.6).
     */
    fun routeMatches(r: Resolution, routed: Device?): Boolean? = when {
        r.device == null -> true
        routed == null -> null
        r.builtIn -> routed.type == BuiltInMic.TYPE_BUILTIN_MIC
        else -> routed.id == r.device.id || key(routed) == r.key
    }

    /** Shown when Android keeps routing another input than the resolved one (after one restart). */
    const val ROUTE_MESSAGE = "Android nimmt ein anderes Mikrofon als das gewählte. Bitte nochmals starten."

    /**
     * The error line for a failed start or a capture error: [ROUTE_MESSAGE] stands alone (it is a
     * complete sentence), anything else keeps the caller's prefix.
     */
    fun errorText(prefix: String, message: String?): String =
        if (message == ROUTE_MESSAGE) ROUTE_MESSAGE else "$prefix$message"

    /**
     * The `audio_source` sent with a minute (server/DESIGN.md §4.3): the audio source alone for the
     * built-in microphone — exactly what every app up to 0.7.1 sent — and «<source>+<kind>» for any
     * other, e.g. «UNPROCESSED+usb». Never the key's hash, the label or the address.
     */
    fun wireAudioSource(source: String, key: String): String =
        if (isBuiltIn(key)) source else "$source+${kindId(key)}"
}
