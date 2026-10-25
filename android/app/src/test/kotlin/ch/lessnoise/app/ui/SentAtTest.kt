package ch.lessnoise.app.ui

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.8.6: the owner saw «Auf der Karte · 00:00» at 08:23 and again at 11:03. The chip showed only the
 * time of the last acknowledged send (Uploader: `lastSuccessAtMs = clock()`, the real send time), so a
 * send just after midnight read like «today 00:00». It now carries the day when the send was not today.
 */
class SentAtTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private fun ms(d: Int, h: Int, m: Int, month: Int = 10, year: Int = 2026) =
        LocalDateTime.of(year, month, d, h, m, 20).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun todayYesterdayEarlier() {
        val now = ms(25, 8, 23)
        assertEquals("08:05", Fmt.sentAt(ms(25, 8, 5), now, zone))
        assertEquals("00:00", Fmt.sentAt(ms(25, 0, 0), now, zone), "a send today just after midnight")
        assertEquals("gestern 18:42", Fmt.sentAt(ms(24, 18, 42), now, zone))
        assertEquals("gestern 00:00", Fmt.sentAt(ms(24, 0, 0), now, zone))
        assertEquals("23.10. 18:42", Fmt.sentAt(ms(23, 18, 42), now, zone))
        assertEquals("02.01. 07:09", Fmt.sentAt(ms(2, 7, 9, month = 1), now, zone))
        assertEquals("31.12.2025 23:59", Fmt.sentAt(ms(31, 23, 59, month = 12, year = 2025), now, zone))
        // Across midnight: 23:50 is «gestern» five minutes later.
        assertEquals("gestern 23:50", Fmt.sentAt(ms(24, 23, 50), ms(25, 0, 0), zone))
        // A clock that went back (send «in the future»): the time only, never a wrong day word.
        assertEquals("08:30", Fmt.sentAt(ms(25, 8, 30), now, zone))
    }

    @Test
    fun theChipAndTheBannerUseIt() {
        assertEquals("Auf der Karte · gestern 18:42", statusSent(Fmt.sentAt(ms(24, 18, 42), ms(25, 8, 23), zone)))
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val measure = File(app, "src/main/kotlin/ch/lessnoise/app/ui/MeasureScreen.kt").readText()
        val map = File(app, "src/karte/kotlin/ch/lessnoise/app/ui/MapScreen.kt").readText()
        for (src in listOf(measure, map)) {
            assertTrue(src.contains("hm = { Fmt.sentAt(it) }"))
            assertTrue(!src.contains("hm = Fmt::hm"))
        }
    }
}
