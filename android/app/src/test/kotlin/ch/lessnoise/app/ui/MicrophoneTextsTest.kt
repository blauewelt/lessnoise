package ch.lessnoise.app.ui

import ch.lessnoise.app.audio.MicChoice
import ch.lessnoise.app.service.LiveState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 0.7.2: the microphone lines on «Messen» and in «Kalibrieren». */
class MicrophoneTextsTest {
    @Test
    fun measureShowsTheMicrophoneOnlyWhenItIsNotThePlainDefault() {
        assertNull(microphoneNote(LiveState(running = true, microphone = MicChoice.STATUS_BUILTIN)))
        assertNull(microphoneNote(LiveState(running = false, microphone = MicChoice.STATUS_NOT_CONNECTED)))
        assertEquals(MicChoice.STATUS_NOT_CONNECTED, microphoneNote(LiveState(running = true, microphone = MicChoice.STATUS_NOT_CONNECTED)))
        assertEquals("Mikrofon: USB-Mikrofon (Rode)", microphoneNote(LiveState(running = true, microphone = "Mikrofon: USB-Mikrofon (Rode)")))
    }

    @Test
    fun theFloorIsThatOfTheBuiltInMicrophone() {
        val floor = ch.lessnoise.dsp.NoiseFloor.Limit(ch.lessnoise.dsp.NoiseFloor.Estimate("UNPROCESSED", -90.0, 100), 121.95)
        val live = LiveState(running = true, backgroundDb = 20.0, calibrationOffsetDb = 121.95, audioSource = "UNPROCESSED")
        assertEquals(true, liveHumLimit(live, floor) != null)
        assertNull(liveHumLimit(live.copy(micKey = "usb:0123456789abcdef"), floor))
    }

    @Test
    fun calibrationTexts() {
        assertEquals("eingebaut", CalibrationTexts.microphoneLine(MicChoice.STATUS_BUILTIN))
        assertEquals("eingebaut (gewähltes nicht verbunden)", CalibrationTexts.microphoneLine(MicChoice.STATUS_NOT_CONNECTED))
        assertEquals("Jedes Mikrofon wird für sich kalibriert: hier das unten genannte.", CalibrationTexts.PER_MICROPHONE)
    }
}
