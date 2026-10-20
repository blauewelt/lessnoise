package ch.lessnoise.app.audio

import android.media.AudioDeviceInfo
import ch.lessnoise.app.audio.BuiltInMic.Device
import ch.lessnoise.app.audio.BuiltInMic.Route
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.6: the app measures only with the phone's built-in microphone, never a headset's. */
class BuiltInMicTest {

    private val bottom = Device(id = 10, type = BuiltInMic.TYPE_BUILTIN_MIC, address = "bottom")
    private val back = Device(id = 11, type = BuiltInMic.TYPE_BUILTIN_MIC, address = "back")
    private val top = Device(id = 12, type = BuiltInMic.TYPE_BUILTIN_MIC, address = "top")
    private val sco = Device(id = 20, type = BuiltInMic.TYPE_BLUETOOTH_SCO, address = "AA:BB:CC:DD:EE:FF", productName = "Buds")
    private val le = Device(id = 21, type = BuiltInMic.TYPE_BLE_HEADSET, address = "11:22:33:44:55:66", productName = "Buds LE")
    private val wired = Device(id = 22, type = BuiltInMic.TYPE_WIRED_HEADSET)
    private val usb = Device(id = 23, type = BuiltInMic.TYPE_USB_HEADSET)

    @Test
    fun typeConstantsAreAndroidsOwn() {
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, BuiltInMic.TYPE_BUILTIN_MIC)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, BuiltInMic.TYPE_WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, BuiltInMic.TYPE_BLUETOOTH_SCO)
        assertEquals(AudioDeviceInfo.TYPE_USB_DEVICE, BuiltInMic.TYPE_USB_DEVICE)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, BuiltInMic.TYPE_USB_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_HEARING_AID, BuiltInMic.TYPE_HEARING_AID)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, BuiltInMic.TYPE_BLE_HEADSET)
    }

    @Test
    fun picksTheBuiltInMicrophoneNeverAHeadset() {
        // Headsets listed first (as Android does while one is connected) are skipped.
        assertEquals(bottom, BuiltInMic.pick(listOf(sco, le, wired, usb, bottom)))
        assertEquals(back, BuiltInMic.pick(listOf(le, back)))
    }

    @Test
    fun prefersTheMainBottomMicrophoneOfSeveral() {
        assertEquals(bottom, BuiltInMic.pick(listOf(back, top, bottom)))
        assertEquals(10, BuiltInMic.pick(listOf(top, bottom.copy(address = "Bottom")))?.id)   // case-insensitive
        // No «bottom»: the first built-in one in Android's order.
        assertEquals(back, BuiltInMic.pick(listOf(sco, back, top)))
        assertEquals(Device(1, BuiltInMic.TYPE_BUILTIN_MIC), BuiltInMic.pick(listOf(Device(1, BuiltInMic.TYPE_BUILTIN_MIC), Device(2, BuiltInMic.TYPE_BUILTIN_MIC))))
    }

    @Test
    fun noBuiltInMicrophoneReported() {
        assertNull(BuiltInMic.pick(emptyList()))
        assertNull(BuiltInMic.pick(listOf(sco, le, wired)))
        // Then the app keeps measuring as before 0.6.6, whatever is routed.
        assertEquals(Route.NO_BUILT_IN_MIC, BuiltInMic.check(null, sco))
        assertEquals(Route.NO_BUILT_IN_MIC, BuiltInMic.check(null, null))
        assertTrue(BuiltInMic.mayMeasure(Route.NO_BUILT_IN_MIC))
    }

    @Test
    fun routeCheck() {
        assertEquals(Route.OK, BuiltInMic.check(bottom, bottom))
        // Another built-in microphone of the same phone is still the phone's own.
        assertEquals(Route.OK, BuiltInMic.check(bottom, back))
        for (headset in listOf(sco, le, wired, usb)) {
            assertEquals(Route.NOT_BUILT_IN, BuiltInMic.check(bottom, headset), headset.toString())
        }
        assertEquals(Route.UNKNOWN, BuiltInMic.check(bottom, null))
    }

    @Test
    fun onlyAProvenForeignMicrophoneStopsTheMeasurement() {
        assertTrue(BuiltInMic.mayMeasure(Route.OK))
        assertTrue(BuiltInMic.mayMeasure(Route.UNKNOWN))
        assertTrue(BuiltInMic.mayMeasure(Route.NO_BUILT_IN_MIC))
        assertFalse(BuiltInMic.mayMeasure(Route.NOT_BUILT_IN))
    }

    @Test
    fun messageAndErrorLine() {
        assertEquals(
            // 0.7.1: the owner's wording.
            "Das Telefon misst nur mit dem eingebauten Mikrofon. Bitte Kopfhörer oder externes Mikrofon trennen.",
            BuiltInMic.WRONG_MIC_MESSAGE,
        )
        assertEquals(BuiltInMic.WRONG_MIC_MESSAGE, BuiltInMic.errorText("Start fehlgeschlagen: ", BuiltInMic.WRONG_MIC_MESSAGE))
        assertEquals(BuiltInMic.WRONG_MIC_MESSAGE, WrongMicrophoneException().message)
        // Other errors keep their prefix, as before 0.6.6.
        assertEquals("Mikrofon-Fehler: Mikrofon-Fehler -3", BuiltInMic.errorText("Mikrofon-Fehler: ", "Mikrofon-Fehler -3"))
    }

    @Test
    fun laborDiagnosticLine() {
        assertEquals("eingebautes Mikrofon (bottom)", BuiltInMic.label(bottom, Route.OK))
        assertEquals("eingebautes Mikrofon", BuiltInMic.label(Device(1, BuiltInMic.TYPE_BUILTIN_MIC), Route.OK))
        assertEquals("LE-Audio-Headset (11:22:33:44:55:66)", BuiltInMic.label(le, Route.NOT_BUILT_IN))
        assertEquals("unbekannt", BuiltInMic.label(null, Route.UNKNOWN))
        assertEquals("Bluetooth-Headset (AA:BB:CC:DD:EE:FF) · kein eingebautes Mikrofon gemeldet", BuiltInMic.label(sco, Route.NO_BUILT_IN_MIC))
        assertEquals("Eingang Typ 99", BuiltInMic.typeName(99))
    }

    /** 0.7.1: the fact next to «Audioquelle» (every edition): built-in or not, never address or name. */
    @Test
    fun audioSourceFact() {
        assertEquals("Mikrofon: eingebaut", BuiltInMic.fact(bottom))
        assertEquals("Mikrofon: eingebaut", BuiltInMic.fact(back))
        assertEquals("Mikrofon: Bluetooth-Headset!", BuiltInMic.fact(sco))
        assertEquals("Mikrofon: LE-Audio-Headset!", BuiltInMic.fact(le))
        assertEquals("Mikrofon: Kabel-Headset!", BuiltInMic.fact(wired))
        assertEquals("Mikrofon: USB-Headset!", BuiltInMic.fact(usb))
        assertEquals("Mikrofon: unbekannt", BuiltInMic.fact(null))
        for (d in listOf(sco, le)) {
            val f = BuiltInMic.fact(d)
            assertFalse(d.address in f || d.productName in f, f)
        }
    }

    /**
     * 0.7.1, the whole policy as one table: inputs → the id pinned, routed type → measure or refuse.
     * A Bluetooth (SCO / LE Audio), wired or USB route refuses; built-in, unknown, or a phone without a
     * listed built-in microphone measure.
     */
    @Test
    fun policyTable() {
        val inputs = listOf(sco, le, usb, back, bottom)
        val pinned = BuiltInMic.pick(inputs)
        assertEquals(10, pinned?.id)
        for ((routed, measures) in listOf(
            bottom to true, back to true, top to true, null to true,
            sco to false, le to false, wired to false, usb to false,
            Device(30, BuiltInMic.TYPE_USB_DEVICE) to false, Device(31, BuiltInMic.TYPE_HEARING_AID) to false,
        )) assertEquals(measures, BuiltInMic.mayMeasure(BuiltInMic.check(pinned, routed)), routed.toString())
        // No built-in microphone listed: no preference, and whatever Android routes is measured (as before 0.6.6).
        assertNull(BuiltInMic.pick(listOf(sco, usb)))
        assertTrue(BuiltInMic.mayMeasure(BuiltInMic.check(null, sco)))
    }

    /**
     * 0.7.1 isolation: pinning needs no permission and touches no Bluetooth or call routing. The app
     * never starts Bluetooth SCO, never requests a communication device, never changes the audio mode,
     * and the manifests declare exactly the permissions they declared in 0.7.0.
     */
    @Test
    fun noBluetoothNoCommunicationDeviceNoNewPermission() {
        val app = locateAppDir()
        val forbidden = Regex("""startBluetoothSco|stopBluetoothSco|setBluetoothScoOn|isBluetoothScoOn|setCommunicationDevice|clearCommunicationDevice|MODE_IN_COMMUNICATION|MODE_IN_CALL|setSpeakerphoneOn|\.mode\s*=""")
        val hits = File(app, "src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/test" !in it.path.substringAfter("/src/").let { p -> "/" + p } }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (forbidden.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null } }
            .toList()
        assertEquals(emptyList(), hits)

        fun permissions(flavour: String): List<String> {
            val xml = File(app, "src/$flavour/AndroidManifest.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            return Regex("""<uses-permission\b[^>]*>""").findAll(xml).map { m ->
                val name = Regex("""android:name="android\.permission\.([^"]+)"""").find(m.value)!!.groupValues[1]
                if ("""tools:node="remove"""" in m.value) "-$name" else name
            }.toList().sorted()
        }
        val net = listOf("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "ACCESS_NETWORK_STATE", "INTERNET")
        assertEquals(
            listOf("-ACCESS_BACKGROUND_LOCATION", "-ACCESS_WIFI_STATE", "-RECEIVE_BOOT_COMPLETED", "FOREGROUND_SERVICE",
                "FOREGROUND_SERVICE_MICROPHONE", "POST_NOTIFICATIONS", "RECORD_AUDIO", "WAKE_LOCK"),
            permissions("main"),
        )
        assertEquals(net, permissions("public"))
        assertEquals((net + "FOREGROUND_SERVICE_LOCATION").sorted(), permissions("laborKarte"))
        assertEquals(net.map { "-$it" }.sorted(), permissions("offline"))
        assertEquals(net.map { "-$it" }.sorted(), permissions("labor"))
    }

    /**
     * The capture code really uses the rule: it pins the picked device, checks the routed device
     * after startRecording(), follows routing changes, and both places that show a capture error
     * pass the message through unchanged.
     */
    @Test
    fun captureAndCallersUseTheRule() {
        val app = locateAppDir()
        fun code(path: String) = File(app, "src/main/kotlin/ch/lessnoise/app/$path").readText()
        val capture = code("audio/AudioCapture.kt")
        for (needle in listOf(
            "AudioManager.GET_DEVICES_INPUTS", "BuiltInMic.pick(", "setPreferredDevice(", "startRecording()",
            "routedDevice", "BuiltInMic.check(", "WrongMicrophoneException()", "addOnRoutingChangedListener",
            "removeOnRoutingChangedListener",
        )) assertTrue(needle in capture, needle)
        assertTrue(capture.indexOf("setPreferredDevice(") < capture.indexOf("rec.startRecording()"), "pinned before recording")
        assertTrue(capture.indexOf("rec.startRecording()") < capture.indexOf("BuiltInMic.check(mic, routed)"), "checked after recording starts")
        // 0.7.1: the count of built-in microphones is logged, and the fact reaches «Audioquelle».
        assertTrue("built-in microphones: \$builtInCount" in capture, "count logged")
        assertTrue("microphone = BuiltInMic.fact(routed)" in capture, "fact in CaptureInfo")
        assertTrue("StatRow(\"Audioquelle\"" in code("ui/CalibrateScreen.kt") && "live.microphone" in code("ui/CalibrateScreen.kt"))
        for (path in listOf("service/MeasurementService.kt", "ui/CalibrationViewModel.kt")) {
            assertEquals(2, Regex("""BuiltInMic\.errorText\(""").findAll(code(path)).count(), path)
        }
    }

    private fun locateAppDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/kotlin/ch/lessnoise/app").isDirectory) return dir
            val nested = File(dir, "app")
            if (File(nested, "src/main/kotlin/ch/lessnoise/app").isDirectory) return nested
            dir = dir.parentFile
        }
        error("app module not found")
    }
}
