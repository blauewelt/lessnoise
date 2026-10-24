package ch.lessnoise.app.ui

import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportCategory.ANDERE
import ch.lessnoise.app.upload.ReportCategory.AUTO
import ch.lessnoise.app.upload.ReportCategory.FLUGZEUG
import ch.lessnoise.app.upload.ReportCategory.LASTWAGEN
import ch.lessnoise.app.upload.ReportCategory.MOTORRAD
import ch.lessnoise.app.upload.ReportCategory.POSER
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8.1: «Laut! Melden» in one dialog; 0.8.2: a tap selects the category, «Senden» sends. The pure parts of the flow. */
class ReportFlowTest {
    private val fix = LocationPolicy.ReportOutcome.Fix(47.37691, 8.54169, 12.0, "h26833_12479")
    private val noFix = LocationPolicy.ReportOutcome.Problem(LocationPolicy.Outcome.NoFix)

    @Test
    fun theGridIsTwoColumnsInTheOrderOfTheButtons() {
        assertEquals(
            listOf(listOf(MOTORRAD, AUTO), listOf(LASTWAGEN, FLUGZEUG), listOf(POSER, ANDERE)),
            reportCategoryRows(reportCategoryOrder(emptyList())),
        )
        // From the «Sehr laut» notification: the suggested ones first (top row), the rest as always.
        assertEquals(
            listOf(listOf(MOTORRAD, POSER), listOf(AUTO, LASTWAGEN), listOf(FLUGZEUG, ANDERE)),
            reportCategoryRows(reportCategoryOrder(listOf(MOTORRAD, POSER))),
        )
        assertEquals(
            listOf(listOf(FLUGZEUG, MOTORRAD), listOf(AUTO, LASTWAGEN), listOf(POSER, ANDERE)),
            reportCategoryRows(reportCategoryOrder(listOf(FLUGZEUG))),
        )
        // Every category exactly once, whatever is suggested; an odd count leaves one alone in the last row.
        for (s in listOf(emptyList(), listOf(AUTO, LASTWAGEN), ReportCategory.entries.toList())) {
            assertEquals(ReportCategory.entries.toSet(), reportCategoryRows(reportCategoryOrder(s)).flatten().toSet())
            assertEquals(6, reportCategoryRows(reportCategoryOrder(s)).flatten().size)
        }
        assertEquals(listOf(listOf(MOTORRAD, AUTO), listOf(POSER)), reportCategoryRows(listOf(MOTORRAD, AUTO, POSER)))
    }

    @Test
    fun sendNowOrWaitForTheFix() {
        // No category: not sendable, with or without the fix, «Senden» tapped or not.
        for (sent in listOf(false, true)) {
            assertEquals(ReportNext.CHOOSE, reportNext(null, null, sent))
            assertEquals(ReportNext.CHOOSE, reportNext(null, fix, sent))
        }
        assertFalse(reportSendEnabled(null, null, false))
        assertFalse(reportSendEnabled(null, fix, false))
        // 0.8.2: a category selected, «Senden» not tapped: nothing goes out, «Senden» is enabled.
        assertEquals(ReportNext.READY, reportNext(AUTO, fix, sendTapped = false))
        assertEquals(ReportNext.READY, reportNext(AUTO, null, sendTapped = false))
        assertTrue(reportSendEnabled(AUTO, fix, false))
        assertTrue(reportSendEnabled(AUTO, null, false), "enabled while the fix is searched: it then waits")
        // Category + fix + «Senden»: send.
        assertEquals(ReportNext.SEND, reportNext(AUTO, fix, sendTapped = true))
        // Category + «Senden» before the fix: wait (all disabled, progress), then send when it arrives.
        assertEquals(ReportNext.WAIT, reportNext(AUTO, null, sendTapped = true))
        assertFalse(reportSendEnabled(AUTO, null, true), "not twice")
        // No fix: «Kein genauer Standort», sent or not (nothing can be sent without one).
        for (sent in listOf(false, true)) {
            assertEquals(ReportNext.FAILED, reportNext(AUTO, noFix, sent))
            assertEquals(ReportNext.FAILED, reportNext(null, noFix, sent))
            assertFalse(reportSendEnabled(AUTO, noFix, sent))
        }
        for (o in listOf(LocationPolicy.Outcome.ApproximateOnly, LocationPolicy.Outcome.PermissionDenied, LocationPolicy.Outcome.LocationOff)) {
            assertEquals(ReportNext.FAILED, reportNext(POSER, LocationPolicy.ReportOutcome.Problem(o), true))
        }
    }

    /** 0.8.2: from the «Sehr laut» notification one suggestion is preselected («Senden» is one tap); two: none. */
    @Test
    fun aSingleSuggestionIsPreselected() {
        assertEquals(FLUGZEUG, reportPreselected(listOf(FLUGZEUG)))
        assertEquals(AUTO, reportPreselected(listOf(AUTO)))
        assertNull(reportPreselected(listOf(MOTORRAD, POSER)))
        assertNull(reportPreselected(listOf(AUTO, LASTWAGEN)))
        assertNull(reportPreselected(emptyList()))
        // Preselected + fix: «Senden» is enabled at once and sends.
        val pre = reportPreselected(listOf(FLUGZEUG))
        assertTrue(reportSendEnabled(pre, fix, false))
        assertEquals(ReportNext.SEND, reportNext(pre, fix, true))
        // Wired in begin(), after cancel() (which clears the selection).
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        val begin = ui.substringAfter("fun begin(").substringBefore("\n    }")
        assertTrue(begin.indexOf("cancel()") in 0 until begin.indexOf("chosen = reportPreselected(suggest)"), begin)
    }

    @Test
    fun theDialogsTexts() {
        assertEquals("Laut! Melden", REPORT_TITLE)
        assertEquals("Senden", REPORT_SEND)
        assertEquals("Standort wird gesucht …", REPORT_LOCATING)
        assertEquals("Standort wird gesucht …", reportFixLine(null))
        assertEquals("Standort ± 12 m", reportFixLine(fix.accuracyText))
        assertEquals("Standort ± 107 m", reportFixLine(LocationPolicy.ReportOutcome.Fix(47.0, 8.0, 106.2, "h1_2").accuracyText))
    }

    /** 0.8.1 (same build): the map moved under the dot; the tap sends that place with 15 m, else the fix. */
    @Test
    fun movedSendsTheMovedPointWith15mNotMovedSendsTheFix() {
        // Not moved: the fix as it is, its accuracy and its cell.
        assertEquals(ReportPlace(47.37691, 8.54169, 12.0, "h26833_12479"), reportPlace(fix, null))
        // Moved ≈ 300 m north (0.0027° of latitude): that point, 15 m, and the cell recomputed for it
        // with the same function as LocationPolicy.decideReport.
        val moved = reportMovedPlace(fix, 47.37961, 8.54169)
        assertNotNull(moved)
        assertEquals(47.37961, moved.lat); assertEquals(8.54169, moved.lon)
        assertEquals(15.0, MANUAL_ACCURACY_M); assertEquals(MANUAL_ACCURACY_M, moved.accuracyM)
        val decided = LocationPolicy.decideReport(47.37961, 8.54169, 99.0, precise = true)
        assertIs<LocationPolicy.ReportOutcome.Fix>(decided)
        assertEquals(decided.cell, moved.cell)
        assertNotEquals(fix.cell, moved.cell, "300 m away is another 100 m cell")
        assertEquals(moved, reportPlace(fix, moved))
        // The decision itself is unchanged: a tap with a fix sends (the place is chosen by reportPlace).
        assertEquals(ReportNext.SEND, reportNext(AUTO, fix, sendTapped = true))
    }

    @Test
    fun theMovedPlaceStaysWithin2kmAndInSwitzerland() {
        val f = LocationPolicy.decideReport(47.37691, 8.54169, 12.0, precise = true) as LocationPolicy.ReportOutcome.Fix
        // 1 km and 1.98 km north are fine; 2.05 km and 10 km north are not (the map stops there).
        val degPerKm = 1.0 / 111.2
        assertNotNull(reportMovedPlace(f, f.lat + 1.0 * degPerKm, f.lon))
        assertNotNull(reportMovedPlace(f, f.lat + 1.98 * degPerKm, f.lon))
        assertNull(reportMovedPlace(f, f.lat + 2.05 * degPerKm, f.lon))
        assertNull(reportMovedPlace(f, f.lat + 10.0 * degPerKm, f.lon))
        assertEquals(2_000.0, REPORT_MAX_MOVE_M)
        // A point the server would refuse is refused here (CellInput.fromLatLon: outside the Swiss box),
        // whatever the distance; a nearby one in Basel is fine.
        val basel = LocationPolicy.decideReport(47.5596, 7.5886, 10.0, precise = true) as LocationPolicy.ReportOutcome.Fix
        assertNotNull(reportMovedPlace(basel, 47.5600, 7.5890))
        assertNull(reportMovedPlace(basel, 48.6, 7.59))
    }

    @Test
    fun theMovedLines() {
        assertEquals("Standort von Hand gesetzt", REPORT_MOVED_LINE)
        assertEquals("Zurück zum Standort", REPORT_MOVE_BACK)
        assertEquals("Standort von Hand gesetzt", reportFixLine(12, moved = true))
        assertEquals("Standort ± 12 m", reportFixLine(12, moved = false))
        assertEquals("Standort wird gesucht …", reportFixLine(null, moved = true))
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        val dialog = ui.substringAfter("ReportStep.Report -> {").substringBefore("is ReportStep.Failed ->")
        assertTrue(dialog.contains("TextButton(onClick = { moved = null }) { Text(REPORT_MOVE_BACK) }"))
        assertTrue(dialog.contains("reportFixLine(fix?.accuracyText, moved != null)"))
        // A new dialog (or «Nochmals», or Abbrechen) starts from the fix again.
        assertTrue(ui.substringAfter("fun open() {").substringBefore("\n    }").contains("moved = null"))
        assertTrue(ui.substringAfter("fun cancel() {").substringBefore("\n    }").contains("moved = null"))
    }

    /** The flow's shape: one dialog, no second confirmation; 0.8.2: a tap selects, «Senden» sends. */
    @Test
    fun oneDialogWithSenden() {
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        for (gone in listOf("ReportStep.Category", "ReportStep.Locating", "ReportStep.Confirm", "ReportDirectionUi.Step", "reportConfirm", "REPORT_SEND_NOTE", "Antippen sendet")) {
            assertFalse(ui.contains(gone), gone)
        }
        val dialog = ui.substringAfter("ReportStep.Report -> {").substringBefore("is ReportStep.Failed ->")
        assertTrue(
            dialog.contains("TextButton(onClick = { sendTap() }, enabled = reportSendEnabled(chosen, fixOutcome, sending)) { Text(REPORT_SEND) }"),
            "«Senden» is the confirm button",
        )
        assertTrue(dialog.contains("dismissButton = { TextButton(onClick = { cancel() }) { Text(\"Abbrechen\") } },"))
        assertTrue(dialog.contains("dismissOnClickOutside = false"))
        assertTrue(dialog.contains(".verticalScroll("), "scrollable on small screens")
        // A category tap only selects; the selected one is drawn filled.
        assertTrue(dialog.contains("selected = cat == chosen") && dialog.contains("{ select(cat) }"))
        val select = dialog.substringAfter("fun select(").substringBefore("fun sendTap(")
        assertFalse(select.contains("advance()") || select.contains("send("), select)
        val buttons = ui.substringAfter("private fun CategoryButton(").substringBefore("\n}")
        assertTrue(buttons.contains("if (selected) {") && buttons.contains("Button(onClick = onClick") && buttons.contains("OutlinedButton(onClick = onClick"))
        assertTrue(buttons.contains("REPORT_SUGGESTED_MARK"), "the «erkannt» mark stays")
        // The reading is taken at the «Senden» tap, kept for a late fix.
        val sendTap = dialog.substringAfter("fun sendTap() {").substringBefore("\n            }")
        assertTrue(sendTap.contains("sending = true") && sendTap.contains("chosenReading = reading?.value") && sendTap.contains("advance()"), sendTap)
        // The fix starts when the dialog opens, not after the category.
        val open = ui.substringAfter("fun open() {").substringBefore("\n    }")
        assertTrue(open.contains("locate()") && open.contains("permission.launch(LocationFetcher.PERMISSIONS)"), open)
        // «Nochmals» goes back to the dialog (the chosen category is not reset there).
        assertTrue(ui.contains("confirmButton = { TextButton(onClick = { open() }) { Text(\"Nochmals\") } },"))
        assertFalse(open.contains("chosen = null"))
    }
}
