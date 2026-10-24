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
import kotlin.test.assertTrue

/** 0.8.1: «Laut! Melden» in one dialog, one tap on a category sends. The pure parts of the flow. */
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
        // Nothing tapped: the dialog waits for the person, with or without the fix.
        assertEquals(ReportNext.CHOOSE, reportNext(null, null))
        assertEquals(ReportNext.CHOOSE, reportNext(null, fix))
        // Tapped before the fix: wait (buttons disabled, progress), then send when it arrives.
        assertEquals(ReportNext.WAIT, reportNext(AUTO, null))
        assertEquals(ReportNext.SEND, reportNext(AUTO, fix))
        // No fix: «Kein genauer Standort», tapped or not (nothing can be sent without one).
        assertEquals(ReportNext.FAILED, reportNext(AUTO, noFix))
        assertEquals(ReportNext.FAILED, reportNext(null, noFix))
        for (o in listOf(LocationPolicy.Outcome.ApproximateOnly, LocationPolicy.Outcome.PermissionDenied, LocationPolicy.Outcome.LocationOff)) {
            assertEquals(ReportNext.FAILED, reportNext(POSER, LocationPolicy.ReportOutcome.Problem(o)))
        }
    }

    @Test
    fun theDialogsTexts() {
        assertEquals("Laut! Melden", REPORT_TITLE)
        assertEquals("Antippen sendet die Meldung.", REPORT_SEND_NOTE)
        assertEquals("Standort wird gesucht …", REPORT_LOCATING)
        assertEquals("Standort wird gesucht …", reportFixLine(null))
        assertEquals("Standort ± 12 m", reportFixLine(fix.accuracyText))
        assertEquals("Standort ± 107 m", reportFixLine(LocationPolicy.ReportOutcome.Fix(47.0, 8.0, 106.2, "h1_2").accuracyText))
    }

    /** The flow's shape: one dialog, no confirmation, the category tap is what sends. */
    @Test
    fun oneDialogOneTap() {
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        for (gone in listOf("ReportStep.Category", "ReportStep.Locating", "ReportStep.Confirm", "ReportDirectionUi.Step", "Text(\"Senden\")", "reportConfirm")) {
            assertFalse(ui.contains(gone), gone)
        }
        val dialog = ui.substringAfter("ReportStep.Report -> {").substringBefore("is ReportStep.Failed ->")
        assertTrue(dialog.contains("confirmButton = {},"), "no confirm button")
        assertTrue(dialog.contains("dismissOnClickOutside = false"))
        assertTrue(dialog.contains(".verticalScroll("), "scrollable on small screens")
        // The reading is taken at the tap, kept for a late fix.
        assertTrue(dialog.contains("chosenReading = reading?.value"))
        // The fix starts when the dialog opens, not after the category.
        val open = ui.substringAfter("fun open() {").substringBefore("\n    }")
        assertTrue(open.contains("locate()") && open.contains("permission.launch(LocationFetcher.PERMISSIONS)"), open)
        // «Nochmals» goes back to the dialog (the chosen category is not reset there).
        assertTrue(ui.contains("confirmButton = { TextButton(onClick = { open() }) { Text(\"Nochmals\") } },"))
        assertFalse(open.contains("chosen = null"))
    }
}
