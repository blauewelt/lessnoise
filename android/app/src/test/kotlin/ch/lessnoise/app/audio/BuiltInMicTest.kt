package ch.lessnoise.app.audio

import android.media.AudioDeviceInfo
import ch.lessnoise.app.audio.BuiltInMic.Device
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.6: the phone's built-in microphone; 0.7.2: the default of the manual choice (MicChoiceTest). */
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
        assertEquals(AudioDeviceInfo.TYPE_LINE_ANALOG, BuiltInMic.TYPE_LINE_ANALOG)
        assertEquals(AudioDeviceInfo.TYPE_LINE_DIGITAL, BuiltInMic.TYPE_LINE_DIGITAL)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, BuiltInMic.TYPE_BLUETOOTH_A2DP)
        assertEquals(AudioDeviceInfo.TYPE_USB_ACCESSORY, BuiltInMic.TYPE_USB_ACCESSORY)
        assertEquals(AudioDeviceInfo.TYPE_AUX_LINE, BuiltInMic.TYPE_AUX_LINE)
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
    }

    @Test
    fun laborDiagnosticLine() {
        assertEquals("eingebautes Mikrofon (bottom)", BuiltInMic.label(bottom))
        assertEquals("eingebautes Mikrofon", BuiltInMic.label(Device(1, BuiltInMic.TYPE_BUILTIN_MIC)))
        assertEquals("LE-Audio-Headset (11:22:33:44:55:66)", BuiltInMic.label(le))
        assertEquals("unbekannt", BuiltInMic.label(null))
        assertEquals("Eingang Typ 99", BuiltInMic.typeName(99))
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
     * 0.7.2: the capture code really uses the choice: it pins the resolved device before recording,
     * checks the routed device after startRecording(), restarts once on a mismatch, follows routing
     * changes; the measurement and the calibration take the resolution from Microphones, and the
     * 0.7.1 refusal text is gone everywhere.
     */
    @Test
    fun captureAndCallersUseTheChoice() {
        val app = locateAppDir()
        fun code(path: String) = File(app, "src/main/kotlin/ch/lessnoise/app/$path").readText()
        val capture = code("audio/AudioCapture.kt")
        for (needle in listOf(
            "AudioManager.GET_DEVICES_INPUTS", "BuiltInMic.pick(", "MicChoice.key(", "setPreferredDevice(", "startRecording()",
            "routedDevice", "MicChoice.routeMatches(", "MicRouteException()", "addOnRoutingChangedListener",
            "removeOnRoutingChangedListener", "onMisrouted()",
        )) assertTrue(needle in capture, needle)
        assertTrue(capture.indexOf("setPreferredDevice(") < capture.indexOf("rec.startRecording()"), "pinned before recording")
        assertTrue(capture.indexOf("rec.startRecording()") < capture.indexOf("MicChoice.routeMatches(target, routed)"), "checked after recording starts")
        assertEquals(2, Regex("""open\(sourceName, target\)""").findAll(capture).count(), "one restart on a mismatch")
        val service = code("service/MeasurementService.kt")
        for (needle in listOf("c.microphones.resolveNow()", "c.calibrations.active(source, res.key)", "microphones.resolution.collect",
            "insert(minute, micKey)", "insert(event, micKey)")) assertTrue(needle in service, needle)
        val vm = code("ui/CalibrationViewModel.kt")
        assertTrue("c.microphones.resolveNow()" in vm && "mic = mic.key" in vm)
        for (path in listOf("service/MeasurementService.kt", "ui/CalibrationViewModel.kt")) {
            assertTrue(Regex("""MicChoice\.errorText\(""").findAll(code(path)).count() >= 2, path)
        }
        val all = File(app, "src").walkTopDown().filter { it.isFile && it.extension == "kt" && "/test" !in it.path.substringAfter("/src/").let { p -> "/" + p } }
        for (f in all) assertFalse("misst nur mit dem eingebauten Mikrofon" in f.readText(), f.path)
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
