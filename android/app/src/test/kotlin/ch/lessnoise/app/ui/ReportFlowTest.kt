package ch.lessnoise.app.ui

import ch.lessnoise.app.location.LocationPolicy
import ch.lessnoise.app.upload.ReportCategory
import ch.lessnoise.app.upload.ReportCategory.ANDERE
import ch.lessnoise.app.upload.ReportCategory.AUTO
import ch.lessnoise.app.upload.ReportCategory.BAHN
import ch.lessnoise.app.upload.ReportCategory.BAUSTELLE
import ch.lessnoise.app.upload.ReportCategory.GARTEN
import ch.lessnoise.app.upload.ReportCategory.HUPEN
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

    /** 0.9.4.2 (owner): Töff · Auto · «Andere» in one row; «Andere» folds out Lastwagen · Flugzeug (until 0.9.4.1 a 2 × 2 grid). */
    @Test
    fun oneRowWithAndereThatFoldsOutTheRarerOnes() {
        assertEquals(listOf(MOTORRAD, AUTO), REPORT_MAIN_CATEGORIES)
        // 0.9.4.5: six rarer ones, three per row: Lastwagen · Flugzeug · Bahn / Baustelle · Gartengeräte · Hupen.
        assertEquals(listOf(LASTWAGEN, FLUGZEUG, BAHN, BAUSTELLE, GARTEN, HUPEN), REPORT_MORE_CATEGORIES)
        assertEquals(3, REPORT_MORE_PER_ROW)
        assertEquals(ReportCategory.OFFERED, REPORT_MAIN_CATEGORIES + REPORT_MORE_CATEGORIES)
        assertEquals("Andere", REPORT_MORE)
        // Nothing chosen, row shut: Töff · Auto · «Andere» (its own icon), none selected, no «erkannt».
        val none = reportCategoryButtons(emptyList(), null, expanded = false)
        assertEquals(listOf("Töff", "Auto", "Andere"), none.main.map { it.label })
        assertEquals(listOf(MOTORRAD, AUTO, null), none.main.map { it.category })
        assertEquals(listOf(MOTORRAD, AUTO, null), none.main.map { it.icon })
        assertEquals(listOf("Lastwagen", "Flugzeug", "Bahn", "Baustelle", "Gartengeräte", "Hupen"), none.more.map { it.label })
        assertFalse(none.expanded)
        assertTrue((none.main + none.more).none { it.selected || it.suggested })
        // Auto chosen: only Auto selected.
        val auto = reportCategoryButtons(emptyList(), AUTO, expanded = false)
        assertEquals(listOf(false, true, false), auto.main.map { it.selected })
        assertTrue(auto.more.none { it.selected })
        // Lastwagen chosen in the second row: selected there, and «Andere» shows «Lastwagen» with its icon, selected,
        // so the choice stays visible with the row shut; a tap on «Andere» still selects nothing (category null).
        for (open in listOf(true, false)) {
            val lw = reportCategoryButtons(emptyList(), LASTWAGEN, expanded = open)
            assertEquals(ReportCategoryButton(null, "Lastwagen", LASTWAGEN, selected = true, suggested = false), lw.main[2])
            assertEquals(listOf(true, false, false, false, false, false), lw.more.map { it.selected })
            assertEquals(listOf(false, false), lw.main.take(2).map { it.selected })
            assertEquals(open, lw.expanded)
        }
        assertEquals("Flugzeug", reportCategoryButtons(emptyList(), FLUGZEUG, true).main[2].label)
        assertEquals(ReportCategoryButton(null, "Gartengeräte", GARTEN, selected = true, suggested = false), reportCategoryButtons(emptyList(), GARTEN, false).main[2])
        // Every offered category exactly once (as a selectable button), whatever is suggested or chosen.
        for (s in listOf(emptyList(), listOf(AUTO, LASTWAGEN), ReportCategory.entries.toList(), listOf(POSER), listOf(MOTORRAD, POSER))) {
            for (c in listOf(null, MOTORRAD, LASTWAGEN, POSER, HUPEN)) {
                val r = reportCategoryButtons(s, c, expanded = false)
                assertEquals(ReportCategory.OFFERED, (r.main + r.more).mapNotNull { it.category })
                assertEquals(1, r.main.count { it.category == null }, "one «Andere»")
            }
        }
        // A category not offered (old reports) is never shown as chosen.
        assertTrue(reportCategoryButtons(emptyList(), POSER, false).let { r -> (r.main + r.more).none { it.selected } })
    }

    @Test
    fun theSecondRowOpensAndShuts() {
        // At the start: open when the notification suggests a rarer one (its «erkannt» is seen) or one is chosen.
        assertFalse(reportMoreOpenAtStart(emptyList(), null))
        assertFalse(reportMoreOpenAtStart(listOf(MOTORRAD, AUTO), null))
        assertFalse(reportMoreOpenAtStart(listOf(AUTO), AUTO))
        assertTrue(reportMoreOpenAtStart(listOf(AUTO, LASTWAGEN), null))
        assertTrue(reportMoreOpenAtStart(listOf(LASTWAGEN), LASTWAGEN))
        assertTrue(reportMoreOpenAtStart(listOf(FLUGZEUG), FLUGZEUG))
        assertTrue(reportMoreOpenAtStart(emptyList(), FLUGZEUG), "back from «Nochmals» with Flugzeug chosen")
        // «Andere» toggles; Töff and Auto shut the row; Lastwagen and Flugzeug leave it as it is (open).
        assertTrue(reportMoreOpenAfter(null, open = false))
        assertFalse(reportMoreOpenAfter(null, open = true))
        for (open in listOf(true, false)) {
            assertFalse(reportMoreOpenAfter(MOTORRAD, open))
            assertFalse(reportMoreOpenAfter(AUTO, open))
        }
        assertTrue(reportMoreOpenAfter(LASTWAGEN, open = true))
        assertTrue(reportMoreOpenAfter(FLUGZEUG, open = true))
        // «erkannt» where the suggestion stands; on «Andere» too while its row is shut.
        val road = reportCategoryButtons(listOf(AUTO, LASTWAGEN), null, expanded = true)
        assertEquals(listOf(false, true, false), road.main.map { it.suggested })
        assertEquals(listOf(true, false, false, false, false, false), road.more.map { it.suggested })
        assertTrue(reportCategoryButtons(listOf(AUTO, LASTWAGEN), null, expanded = false).main[2].suggested)
        assertFalse(reportCategoryButtons(listOf(MOTORRAD, AUTO), null, expanded = false).main[2].suggested)
        // TalkBack labels.
        assertEquals("Andere", reportCategoryDescription(reportCategoryButtons(emptyList(), null, false).main[2]))
        assertEquals("Andere: Lastwagen", reportCategoryDescription(reportCategoryButtons(emptyList(), LASTWAGEN, true).main[2]))
        assertEquals("Auto, erkannt", reportCategoryDescription(road.main[1]))
        assertEquals("Lastwagen, erkannt", reportCategoryDescription(road.more[0]))
        // The body keeps the state above both passes and applies it with the selection.
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        val body = ui.substringAfter("fun ReportDialogBody(").substringBefore("\n}\n")
        assertTrue(body.contains("var moreOpen by remember { mutableStateOf(reportMoreOpenAtStart(suggested, chosen)) }"))
        assertTrue(body.contains("moreOpen = reportMoreOpenAfter(b.category, moreOpen)\n        b.category?.let(onSelect)"))
        assertTrue(body.indexOf("var moreOpen") < body.indexOf("ReportFitDisc("))
        val rows = ui.substringAfter("private fun ReportCategoryRows(").substringBefore("\n}\n")
        assertTrue(rows.contains("AnimatedVisibility(") && rows.contains("visible = rows.expanded"), rows)
    }

    /** 0.9.4.2: the disc takes the height that is left, 140–220 dp. */
    @Test
    fun theDiscFitsTheHeightLeft() {
        assertEquals(140, REPORT_DISC_MIN_DP)
        assertEquals(220, REPORT_DISC_MAX_DP)
        assertEquals(200, reportDiscDp(600f, 400f))
        assertEquals(220, reportDiscDp(900f, 400f))
        assertEquals(140, reportDiscDp(500f, 400f))
        assertEquals(140, reportDiscDp(300f, 400f))
        assertEquals(220, reportDiscDp(Float.POSITIVE_INFINITY, 400f))
        assertEquals(171, reportDiscDp(571.9f, 400.5f))
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        val fit = ui.substringAfter("private fun ReportFitDisc(").substringBefore("\n}\n")
        // The measuring pass: a zero disc, measureOnly; then the real one with reportDiscDp, scrolling.
        assertTrue(fit.contains("content(0.dp, true)") && fit.contains("reportDiscDp(available, rest.toDp().value)") && fit.contains("verticalScroll(scroll)) { content(disc, false) }"), fit)
        // The dialog hands both to the Panel.
        val dialog = ui.substringAfter("ReportStep.Report -> {").substringBefore("is ReportStep.Failed ->")
        assertTrue(dialog.contains("discSize = size, measureOnly = measureOnly,"))
    }

    /**
     * 0.9.1 (owner): the dialog offers exactly Töff, Auto, Lastwagen, Flugzeug; «Poser» and «anderes Fahrzeug» are kept
     * only for old reports. 0.9.4.5: plus Bahn, Baustelle, Gartengeräte, Hupen (in «Andere»), in the dialog's order.
     */
    @Test
    fun theDialogOffersExactlyTheFour() {
        assertEquals(listOf(MOTORRAD, AUTO, LASTWAGEN, FLUGZEUG, BAHN, BAUSTELLE, GARTEN, HUPEN), ReportCategory.OFFERED)
        assertEquals(
            listOf("Töff", "Auto", "Lastwagen", "Flugzeug", "Bahn", "Baustelle", "Gartengeräte", "Hupen"),
            ReportCategory.OFFERED.map { it.label },
        )
        assertEquals(listOf(POSER, ANDERE), ReportCategory.entries.filter { !it.offered })
        // Still parsed (queued and sent reports of 0.9.0 and older).
        assertEquals(POSER, ReportCategory.fromId("poser"))
        assertEquals(ANDERE, ReportCategory.fromId("andere"))
        // The «Andere» button is not the category ANDERE («anderes Fahrzeug», id andere): nothing is sent as «Andere».
        assertNotEquals(ANDERE.label, REPORT_MORE)
        // The dialog builds its rows from the two lists, never from all entries.
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        val dialog = ui.substringAfter("ReportStep.Report -> {").substringBefore("is ReportStep.Failed ->")
        assertTrue(dialog.contains("suggested = suggested,") && dialog.contains("chosen = chosen,"))
        assertFalse(ui.substringAfter("fun reportCategoryButtons(").substringBefore("\n}\n").contains("entries"))
    }

    @Test
    fun sendNowOrWaitForTheFix() {
        // 0.9.4.5 (owner): only the place is required; the category decides nothing here any more.
        // «Senden» not tapped: nothing goes out, «Senden» is enabled (also without a fix yet: it then waits).
        assertEquals(ReportNext.READY, reportNext(fix, sendTapped = false))
        assertEquals(ReportNext.READY, reportNext(null, sendTapped = false))
        assertTrue(reportSendEnabled(fix, false))
        assertTrue(reportSendEnabled(null, false), "enabled while the fix is searched: it then waits")
        // Fix + «Senden»: send (with or without a category).
        assertEquals(ReportNext.SEND, reportNext(fix, sendTapped = true))
        // «Senden» before the fix: wait (all disabled, progress), then send when it arrives.
        assertEquals(ReportNext.WAIT, reportNext(null, sendTapped = true))
        assertFalse(reportSendEnabled(null, true), "not twice")
        // No fix: «Kein genauer Standort», sent or not (nothing can be sent without one).
        for (sent in listOf(false, true)) {
            assertEquals(ReportNext.FAILED, reportNext(noFix, sent))
            assertFalse(reportSendEnabled(noFix, sent))
        }
        for (o in listOf(LocationPolicy.Outcome.ApproximateOnly, LocationPolicy.Outcome.PermissionDenied, LocationPolicy.Outcome.LocationOff)) {
            assertEquals(ReportNext.FAILED, reportNext(LocationPolicy.ReportOutcome.Problem(o), true))
        }
        assertEquals(listOf("READY", "WAIT", "SEND", "FAILED"), ReportNext.entries.map { it.name }, "no CHOOSE since 0.9.4.5")
        // A tap selects; a tap on the selected one deselects (nothing chosen = no category sent).
        assertEquals(AUTO, reportSelectAfter(null, AUTO))
        assertEquals(MOTORRAD, reportSelectAfter(AUTO, MOTORRAD))
        assertNull(reportSelectAfter(AUTO, AUTO))
        assertNull(reportSelectAfter(ReportCategory.HUPEN, ReportCategory.HUPEN))
        // The dialog: «Senden» is not gated by the category, and the direction of a disc switched off is not taken.
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val ui = File(app, "src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt").readText()
        val dialog = ui.substringAfter("ReportStep.Report -> {").substringBefore("is ReportStep.Failed ->")
        assertTrue(dialog.contains("sendEnabled = reportSendEnabled(fixOutcome, sending)"))
        assertTrue(dialog.contains("chosenReading = if (directionOn) reading?.value else null"))
        assertTrue(dialog.contains("chosen = reportSelectAfter(chosen, category)"))
        assertTrue(ui.contains("category = category?.id, cell = place.cell, direction = direction, level = level, count = count,"))
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
        assertEquals(FLUGZEUG, reportPreselected(listOf(FLUGZEUG)))
        assertTrue(reportSendEnabled(fix, false))
        assertEquals(ReportNext.SEND, reportNext(fix, true))
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
        assertEquals(ReportNext.SEND, reportNext(fix, sendTapped = true))
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
        // 0.8.6: the content is ReportDialogBody; the dialog passes the reset and the line.
        val body = ui.substringAfter("fun ReportDialogBody(").substringBefore("\n}\n")
        assertTrue(dialog.contains("onMoveBack = { moved = null },"))
        // 0.9.4.2: at the end of the status line's place (was in the location row of the card).
        assertTrue(body.contains("if (!waiting && fixState == ReportFixState.MOVED) ({\n                TextButton(onClick = onMoveBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(REPORT_MOVE_BACK) }"))
        assertTrue(body.contains("placeTrailing = moveBack,"))
        // 0.9.2: «· Karte verschiebbar» only while the disc says the drag works, and not while «Senden» waits.
        assertTrue(dialog.contains("fixLine = reportFixLine(fix?.accuracyText, moved != null, draggable = draggable && !waiting),"))
        assertTrue(dialog.contains("onDraggable = { draggable = it },"))
        assertEquals("Standort ± 12 m · Karte verschiebbar", reportFixLine(12, moved = false, draggable = true))
        assertEquals("Standort von Hand gesetzt", reportFixLine(12, moved = true, draggable = true))
        assertEquals("Standort wird gesucht …", reportFixLine(null, draggable = true))
        assertTrue(ui.substringAfter("fun cancel() {").substringBefore("\n    }").contains("draggable = false"))
        assertTrue(dialog.contains("fixState = reportFixState(fix != null, moved != null),"))
        assertEquals(ReportFixState.SEARCHING, reportFixState(false, true))
        assertEquals(ReportFixState.MOVED, reportFixState(true, true))
        assertEquals(ReportFixState.FIX, reportFixState(true, false))
        // The preview tiles of the JVM renders are never passed by the app.
        assertFalse(ui.contains("previewTiles"))
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
        // 0.8.6: «Senden» a filled button with the send icon (ReportSendButton). 0.9.1: no longer the confirm
        // button but the dialog's main button in the content (ReportDialogBody), full width, 56 dp, under the grid;
        // the dialog is a BasicAlertDialog in ReportDialogLayout, «Abbrechen» its one text button, centred under «Senden».
        assertTrue(dialog.contains("sendEnabled = reportSendEnabled(fixOutcome, sending),\n                        onSend = { sendTap() },"), dialog)
        assertFalse(dialog.contains("ReportSendButton("), "only inside ReportDialogBody")
        assertTrue(dialog.contains("BasicAlertDialog(") && dialog.contains("ReportDialogLayout(onCancel = { cancel() }) {"), dialog)
        assertFalse(dialog.contains("confirmButton") || dialog.contains("dismissButton"), "no action row")
        val layout = ui.substringAfter("fun ReportDialogLayout(").substringBefore("\n}")
        assertTrue(layout.contains("Box(Modifier.weight(1f, fill = false)) {") && layout.contains("content()"), layout)
        assertTrue(layout.trimEnd().lines().takeLast(3).first().contains("TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(REPORT_CANCEL) }"), layout)
        assertTrue(layout.indexOf("content()") < layout.indexOf("TextButton(onClick = onCancel"), "«Abbrechen» under the content")
        assertEquals("Abbrechen", REPORT_CANCEL)
        val sendButton = ui.substringAfter("fun ReportSendButton(").substringBefore("\n}")
        assertTrue(sendButton.contains("Button(\n        onClick = onClick, enabled = enabled") && sendButton.contains("Text(REPORT_SEND"), sendButton)
        assertTrue(sendButton.contains("modifier.heightIn(min = REPORT_SEND_HEIGHT)") && sendButton.contains("Icons.AutoMirrored.Filled.Send"), sendButton)
        assertEquals(56f, REPORT_SEND_HEIGHT.value)
        // Waiting for the fix after the tap: a small progress circle and «Standort wird gesucht …» on the button.
        assertTrue(sendButton.contains("if (waiting) {\n            CircularProgressIndicator(") && sendButton.contains("REPORT_LOCATING, style = MaterialTheme.typography.labelLarge, maxLines = 1,"), sendButton)
        assertTrue(dialog.contains("dismissOnClickOutside = false"))
        val body = ui.substringAfter("fun ReportDialogBody(").substringBefore("\n}\n")
        assertTrue(dialog.contains("ReportDialogLayout(onCancel = { cancel() }) {\n                    // 0.9.4.2: the content (ReportDialogBody): Was war es? · Wohin fuhr es? · level and place · Foto\n                    ReportDialogBody("))
        // 0.9.4.2: the groups scroll inside ReportFitDisc (weight(1f, fill = false), so the bottom part keeps its place).
        assertTrue(ui.contains("    scroll: ScrollState = rememberScrollState(),\n) {") && body.contains("ReportFitDisc(scroll, Modifier.weight(1f, fill = false)) {"), "the groups scroll on small screens")
        // 0.9.1: «Senden» is the body's last element, full width, outside the scrolling part (never scrolled away).
        val bodyLines = body.trimEnd().lines()
        assertEquals("        ReportSendButton(onClick = onSend, enabled = sendEnabled, waiting = waiting, modifier = Modifier.fillMaxWidth())", bodyLines[bodyLines.size - 2])
        assertTrue(body.indexOf("ReportSendButton(") > body.indexOf("ReportCategoryRows(rows, enabled = !waiting, onTap = onTap)"), "under the rows")
        // 0.9.4.2 (owner): the vehicle first, then the direction, then level and place.
        val what = body.indexOf("Text(REPORT_WHAT")
        assertTrue(what in 0 until body.indexOf("ReportCategoryRows(rows") && body.indexOf("ReportCategoryRows(rows") < body.indexOf("disc(discSize, measureOnly)"))
        assertTrue(body.indexOf("disc(discSize, measureOnly)") < body.indexOf("ReportStatusLine("))
        // A category tap only selects (and folds the row); the selected one is drawn filled.
        assertTrue(body.contains("b.category?.let(onSelect)") && dialog.contains("onSelect = { cat -> select(cat) },"))
        assertTrue(ui.substringAfter("fun reportCategoryButtons(").substringBefore("\n}\n").contains("selected = c == chosen"))
        assertTrue(dialog.contains("chosen = chosen,"))
        val select = dialog.substringAfter("fun select(").substringBefore("fun sendTap(")
        assertFalse(select.contains("advance()") || select.contains("send("), select)
        val buttons = ui.substringAfter("private fun CategoryButton(").substringBefore("\n}")
        assertTrue(buttons.contains("if (b.selected) {") && buttons.contains("Button(onClick = onClick") && buttons.contains("OutlinedButton(onClick = onClick"))
        assertTrue(buttons.contains("REPORT_SUGGESTED_MARK"), "the «erkannt» mark stays")
        // The reading is taken at the «Senden» tap, kept for a late fix.
        val sendTap = dialog.substringAfter("fun sendTap() {").substringBefore("\n            }")
        assertTrue(sendTap.contains("sending = true") && sendTap.contains("chosenReading = if (directionOn) reading?.value else null") && sendTap.contains("advance()"), sendTap)
        // The fix starts when the dialog opens, not after the category.
        val open = ui.substringAfter("fun open() {").substringBefore("\n    }")
        // 0.8.5: the microphone permission (for the level) goes into the same request (reportPermissions).
        assertTrue(open.contains("locate()") && open.contains("val ask = reportPermissions(needLocation, micAllowed)") && open.contains("permission.launch(ask)"), open)
        assertTrue(open.contains("meter.reset()"), "the level starts from zero when the dialog opens")
        assertTrue(sendTap.contains("chosenLevel = meter.level()"), "the level of the «Senden» tap")
        // «Nochmals» goes back to the dialog (the chosen category is not reset there).
        assertTrue(ui.contains("confirmButton = { TextButton(onClick = { open() }) { Text(\"Nochmals\") } },"))
        assertFalse(open.contains("chosen = null"))
    }
}
