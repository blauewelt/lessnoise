package ch.lessnoise.app.audio

import ch.lessnoise.app.audio.BuiltInMic.Device
import ch.lessnoise.app.audio.MicChoice.Chosen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.7.2: the manual microphone choice — identity, list, resolution, route check, wire format. */
class MicChoiceTest {

    private val bottom = Device(id = 10, type = BuiltInMic.TYPE_BUILTIN_MIC, address = "bottom")
    private val back = Device(id = 11, type = BuiltInMic.TYPE_BUILTIN_MIC, address = "back")
    private val usb = Device(id = 30, type = BuiltInMic.TYPE_USB_DEVICE, address = "card=1;device=0", productName = "Rode NT-USB")
    private val usbHeadset = Device(id = 31, type = BuiltInMic.TYPE_USB_HEADSET, address = "card=2;device=0", productName = "Jabra")
    private val le = Device(id = 21, type = BuiltInMic.TYPE_BLE_HEADSET, address = "11:22:33:44:55:66", productName = "Buds LE")
    private val sco = Device(id = 20, type = BuiltInMic.TYPE_BLUETOOTH_SCO, address = "AA:BB:CC:DD:EE:FF", productName = "Buds")
    private val wired = Device(id = 22, type = BuiltInMic.TYPE_WIRED_HEADSET)
    private val telephony = Device(id = 40, type = 18)
    private val submix = Device(id = 41, type = 25)

    @Test
    fun identityIsStableAcrossReconnectsAndHidesTheAddress() {
        // Android's id changes on every reconnect; the key does not.
        assertEquals(MicChoice.key(usb), MicChoice.key(usb.copy(id = 99)))
        assertTrue(MicChoice.key(usb).matches(Regex("usb:[0-9a-f]{16}")), MicChoice.key(usb))
        assertTrue(MicChoice.key(le).matches(Regex("bluetooth:[0-9a-f]{16}")), MicChoice.key(le))
        assertTrue(MicChoice.key(wired).matches(Regex("wired:[0-9a-f]{16}")), MicChoice.key(wired))
        // Type, product name and address each make another microphone.
        assertNotEquals(MicChoice.key(usb), MicChoice.key(usb.copy(address = "card=3;device=0")))
        assertNotEquals(MicChoice.key(usb), MicChoice.key(usb.copy(productName = "Other")))
        assertNotEquals(MicChoice.key(usb), MicChoice.key(usb.copy(type = BuiltInMic.TYPE_USB_HEADSET)))
        // Every built-in microphone is «builtin», whatever its id or address.
        assertEquals("builtin", MicChoice.key(bottom))
        assertEquals("builtin", MicChoice.key(back))
        // Never the address or the name in the key.
        for (d in listOf(usb, le, sco)) {
            val k = MicChoice.key(d)
            assertFalse(d.address in k || d.productName in k, k)
        }
        assertEquals("usb", MicChoice.kindId(MicChoice.key(usb)))
        assertEquals("builtin", MicChoice.kindId("builtin"))
    }

    @Test
    fun kindsAndUsability() {
        assertEquals(MicChoice.Kind.BUILTIN, MicChoice.kindOf(BuiltInMic.TYPE_BUILTIN_MIC))
        assertEquals(MicChoice.Kind.USB, MicChoice.kindOf(BuiltInMic.TYPE_USB_HEADSET))
        assertEquals(MicChoice.Kind.USB, MicChoice.kindOf(BuiltInMic.TYPE_USB_ACCESSORY))
        assertEquals(MicChoice.Kind.WIRED, MicChoice.kindOf(BuiltInMic.TYPE_WIRED_HEADSET))
        assertEquals(MicChoice.Kind.WIRED, MicChoice.kindOf(BuiltInMic.TYPE_LINE_ANALOG))
        assertEquals(MicChoice.Kind.BLUETOOTH, MicChoice.kindOf(BuiltInMic.TYPE_BLE_HEADSET))
        assertEquals(MicChoice.Kind.BLUETOOTH, MicChoice.kindOf(BuiltInMic.TYPE_BLUETOOTH_SCO))
        assertNull(MicChoice.kindOf(18)) // telephony
        assertNull(MicChoice.kindOf(25)) // remote submix
        // A classic Bluetooth headset needs SCO (call routing): not usable. LE Audio, USB, wired are.
        assertFalse(MicChoice.usable(BuiltInMic.TYPE_BLUETOOTH_SCO))
        assertFalse(MicChoice.usable(BuiltInMic.TYPE_BLUETOOTH_A2DP))
        assertFalse(MicChoice.usable(BuiltInMic.TYPE_HEARING_AID))
        assertTrue(MicChoice.usable(BuiltInMic.TYPE_BLE_HEADSET))
        assertTrue(MicChoice.usable(BuiltInMic.TYPE_USB_DEVICE))
        assertTrue(MicChoice.usable(BuiltInMic.TYPE_WIRED_HEADSET))
        assertTrue(MicChoice.usable(BuiltInMic.TYPE_BUILTIN_MIC))
        assertFalse(MicChoice.usable(18))
    }

    @Test
    fun listShowsTheBuiltInFirstOnceThenTheOthers() {
        val rows = MicChoice.rows(null, listOf(sco, usb, back, bottom, telephony, le, submix, usb.copy(id = 77)))
        assertEquals(
            listOf(
                "Eingebautes Mikrofon · Standard · selected",
                "Bluetooth-Headset (Buds) · nicht nutzbar",
                "USB-Mikrofon (Rode NT-USB)",
                "LE-Audio-Headset (Buds LE)",
            ),
            rows.map { r -> listOfNotNull(r.option.label, r.note, if (r.selected) "selected" else null).joinToString(" · ") },
        )
        assertEquals(listOf(true, false, true, true), rows.map { it.selectable })
        assertEquals("builtin", rows.first().option.key)
    }

    @Test
    fun aChosenMicrophoneThatIsNotConnectedStaysInTheList() {
        val chosen = Chosen(MicChoice.key(usb), "USB-Mikrofon (Rode NT-USB)")
        val rows = MicChoice.rows(chosen, listOf(bottom))
        assertEquals(2, rows.size)
        assertFalse(rows[0].selected)
        assertEquals("nicht verbunden", rows[1].note)
        assertTrue(rows[1].selected)
        assertFalse(rows[1].selectable)
        // Connected again: the live row is selected, no duplicate.
        val again = MicChoice.rows(chosen, listOf(bottom, usb.copy(id = 55)))
        assertEquals(listOf(false, true), again.map { it.selected })
        assertTrue(again.none { it.note == "nicht verbunden" })
        // A phone that lists no built-in microphone keeps the default row (to undo the choice).
        assertEquals("builtin", MicChoice.rows(chosen, emptyList()).first().option.key)
    }

    /** Chosen + connected list → device to use + status text, as one table. */
    @Test
    fun resolutionTable() {
        val usbKey = MicChoice.key(usb)
        val leKey = MicChoice.key(le)
        val scoKey = MicChoice.key(sco)
        data class Case(val chosen: Chosen?, val inputs: List<Device>, val key: String, val deviceId: Int?, val status: String)
        val cases = listOf(
            // Default: built-in, never an automatic switch to a connected headset.
            Case(null, listOf(sco, le, usb, wired, bottom), "builtin", 10, "Mikrofon: eingebaut"),
            Case(null, listOf(back, bottom), "builtin", 10, "Mikrofon: eingebaut"),
            Case(Chosen("builtin", "x"), listOf(usb, bottom), "builtin", 10, "Mikrofon: eingebaut"),
            // Chosen and connected: that one (whatever id Android gives it now).
            Case(Chosen(usbKey, "USB"), listOf(bottom, usb.copy(id = 123)), usbKey, 123, "Mikrofon: USB-Mikrofon (Rode NT-USB)"),
            Case(Chosen(leKey, "LE"), listOf(bottom, le), leKey, 21, "Mikrofon: LE-Audio-Headset (Buds LE)"),
            // Chosen, not connected: built-in, and the line says so.
            Case(Chosen(usbKey, "USB"), listOf(bottom, usbHeadset), "builtin", 10, "Mikrofon: eingebaut (gewähltes nicht verbunden)"),
            // Chosen, connected, but needs SCO: built-in.
            Case(Chosen(scoKey, "SCO"), listOf(bottom, sco), "builtin", 10, "Mikrofon: eingebaut (gewähltes nicht nutzbar)"),
            // No built-in microphone listed: no preference (as before 0.6.6).
            Case(null, listOf(usb), "builtin", null, "Mikrofon: eingebaut"),
        )
        for (c in cases) {
            val r = MicChoice.resolve(c.chosen, c.inputs)
            assertEquals(c.key, r.key, c.toString())
            assertEquals(c.deviceId, r.device?.id, c.toString())
            assertEquals(c.status, r.status, c.toString())
        }
        assertEquals(MicChoice.STATUS_BUILTIN, MicChoice.resolve(null, emptyList()).status)
    }

    @Test
    fun routeCheck() {
        val builtIn = MicChoice.resolve(null, listOf(bottom, usb))
        assertEquals(true, MicChoice.routeMatches(builtIn, bottom))
        assertEquals(true, MicChoice.routeMatches(builtIn, back)) // another built-in one of the same phone
        for (other in listOf(usb, le, sco, wired)) assertEquals(false, MicChoice.routeMatches(builtIn, other), other.toString())
        assertNull(MicChoice.routeMatches(builtIn, null))
        val chosenUsb = MicChoice.resolve(Chosen(MicChoice.key(usb), "USB"), listOf(bottom, usb))
        assertEquals(true, MicChoice.routeMatches(chosenUsb, usb))
        assertEquals(true, MicChoice.routeMatches(chosenUsb, usb.copy(address = "")))   // same id
        assertEquals(true, MicChoice.routeMatches(chosenUsb, usb.copy(id = 5)))         // same identity
        assertEquals(false, MicChoice.routeMatches(chosenUsb, bottom))
        // Nothing pinned (no built-in microphone listed): nothing to check.
        assertEquals(true, MicChoice.routeMatches(MicChoice.resolve(null, listOf(usb)), usb))
    }

    @Test
    fun errorLineAndWireFormat() {
        assertEquals(MicChoice.ROUTE_MESSAGE, MicChoice.errorText("Start fehlgeschlagen: ", MicChoice.ROUTE_MESSAGE))
        assertEquals(MicChoice.ROUTE_MESSAGE, MicRouteException().message)
        assertEquals("Mikrofon-Fehler: Mikrofon-Fehler -3", MicChoice.errorText("Mikrofon-Fehler: ", "Mikrofon-Fehler -3"))
        // Built-in: exactly what 0.7.1 sent. Another microphone: «+kind», never the hash.
        assertEquals("UNPROCESSED", MicChoice.wireAudioSource("UNPROCESSED", "builtin"))
        assertEquals("VOICE_RECOGNITION", MicChoice.wireAudioSource("VOICE_RECOGNITION", "builtin"))
        assertEquals("UNPROCESSED+usb", MicChoice.wireAudioSource("UNPROCESSED", MicChoice.key(usb)))
        assertEquals("UNPROCESSED+bluetooth", MicChoice.wireAudioSource("UNPROCESSED", MicChoice.key(le)))
        assertEquals("VOICE_RECOGNITION+wired", MicChoice.wireAudioSource("VOICE_RECOGNITION", MicChoice.key(wired)))
    }

    /** The values the server accepts (server/stadtlaerm_server/schemas.py AUDIO_SOURCES) cover everything the app can send. */
    @Test
    fun serverAcceptsEveryWireValue() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        var schemas: java.io.File? = null
        while (dir != null && schemas == null) {
            schemas = java.io.File(dir, "server/stadtlaerm_server/schemas.py").takeIf { it.isFile }
            dir = dir.parentFile
        }
        val text = schemas!!.readText()
        val sources = Regex("""AUDIO_SOURCES\s*=\s*\(([^)]*)\)""").find(text)!!.groupValues[1]
        val accepted = Regex(""""([^"]+)"""").findAll(sources).map { it.groupValues[1] }.toSet()
        for (src in listOf("UNPROCESSED", "VOICE_RECOGNITION")) {
            for (kind in MicChoice.Kind.entries.map { it.id } + "other") {
                val key = if (kind == "builtin") "builtin" else "$kind:0123456789abcdef"
                val wire = MicChoice.wireAudioSource(src, key)
                assertTrue(wire in accepted, "$wire not accepted by the server")
            }
        }
    }
}
