package ch.lessnoise.chart

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val win = Windows(zone)
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun labelsNameStartAndEndOfTheMeasurement() {
        assertEquals("Di 6.10. 14:05–16:30", win.label(TimeWindow.span(ms(2026, 10, 6, 14, 5), ms(2026, 10, 6, 16, 30))))
        assertEquals("Di 6.10. 22:14 – Mi 7.10. 06:30", win.label(TimeWindow.span(ms(2026, 10, 6, 22, 14), ms(2026, 10, 7, 6, 30))))
        assertEquals("Mi 30.9. 08:00 – Do 1.10. 08:00", win.label(TimeWindow.span(ms(2026, 9, 30, 8), ms(2026, 10, 1, 8))))
        // The profile view shows the same measurement: same label.
        assertEquals(
            win.label(TimeWindow.span(ms(2026, 10, 6, 14, 5), ms(2026, 10, 6, 16, 30))),
            win.label(TimeWindow.profile(ms(2026, 10, 6, 14, 5), ms(2026, 10, 6, 16, 30))),
        )
        assertFalse(win.crossesDates(TimeWindow.span(ms(2026, 10, 6, 14), ms(2026, 10, 7, 0))))
        assertTrue(win.crossesDates(TimeWindow.span(ms(2026, 10, 6, 22), ms(2026, 10, 7, 0, 1))))
    }

    @Test
    fun axisStepGrowsWithTheLengthOfTheMeasurement() {
        // 1 h 40 min → every 15 min, «HH:mm».
        val short = TimeWindow.span(ms(2026, 10, 6, 14, 5), ms(2026, 10, 6, 15, 45))
        assertEquals(15L, win.stepMinutes(short))
        assertEquals(listOf("14:15", "14:30", "14:45", "15:00", "15:15", "15:30", "15:45"), win.axisLabels(short).map { it.text })
        // 22:00–06:00 (8 h) → hourly, the hour only.
        val night = TimeWindow.span(ms(2026, 10, 6, 22), ms(2026, 10, 7, 6))
        assertEquals(listOf("22", "23", "0", "1", "2", "3", "4", "5", "6"), win.axisLabels(night).map { it.text })
        // 20 h → every 3 h on wall-clock multiples of 3.
        val day = TimeWindow.span(ms(2026, 10, 6, 10, 30), ms(2026, 10, 7, 6, 30))
        assertEquals(listOf("12", "15", "18", "21", "0", "3", "6"), win.axisLabels(day).map { it.text })
        // 3 days → 12 h: weekday at midnight, 12 at noon.
        val three = TimeWindow.span(ms(2026, 10, 5, 9), ms(2026, 10, 8, 9))
        assertEquals(listOf("12", "Di", "12", "Mi", "12", "Do"), win.axisLabels(three).map { it.text })
        // 7 days → daily, the weekday.
        val week = TimeWindow.span(ms(2026, 10, 5, 9), ms(2026, 10, 12, 9))
        assertEquals(listOf("Di", "Mi", "Do", "Fr", "Sa", "So", "Mo"), win.axisLabels(week).map { it.text })
        // Ticks stay inside the window and in order.
        for (w in listOf(short, night, day, three, week)) {
            val t = win.gridTicks(w)
            assertTrue(t.all { it in w.startMs..w.endMs }, w.toString())
            assertEquals(t.sorted(), t)
        }
    }

    @Test
    fun axisFollowsDst() {
        // Last Sunday of October 2026 = 25 Oct: 22:00 → 06:00 is 9 hours, the hour 2 appears twice.
        val autumn = TimeWindow.span(ms(2026, 10, 24, 22), ms(2026, 10, 25, 6))
        assertEquals(9 * 3_600_000L, autumn.durationMs)
        assertEquals(listOf("22", "23", "0", "1", "2", "2", "3", "4", "5", "6"), win.axisLabels(autumn).map { it.text })
        // Last Sunday of March 2027 = 28 Mar: 7 hours, no 2.
        val spring = TimeWindow.span(ms(2027, 3, 27, 22), ms(2027, 3, 28, 6))
        assertEquals(listOf("22", "23", "0", "1", "3", "4", "5", "6"), win.axisLabels(spring).map { it.text })
    }

    @Test
    fun profileAxisIsZeroToTwentyFour() {
        val w = Windows.PROFILE_WINDOW
        assertEquals(RangeMode.PROFILE, w.mode)
        assertEquals(24 * 3_600_000L, w.durationMs)
        val p = Windows(Windows.PROFILE_ZONE)
        assertEquals(listOf("0", "3", "6", "9", "12", "15", "18", "21", "24"), p.axisLabels(w).map { it.text })
        assertEquals(w.endMs, p.gridTicks(w).last())
    }

    @Test
    fun weekdayAbbreviations() {
        assertEquals("Fr", Windows.weekday(LocalDate.of(2026, 10, 9)))
        assertEquals("Sa", Windows.weekday(Instant.ofEpochMilli(ms(2026, 10, 10, 1)).atZone(zone).toLocalDate()))
    }
}
