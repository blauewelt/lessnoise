package ch.lessnoise.app.ui

import ch.lessnoise.chart.BAND_LEGEND
import ch.lessnoise.chart.HIGHLIGHT_LABEL
import ch.lessnoise.chart.HUM_NOTE
import ch.lessnoise.chart.NONE_YET_TILE
import ch.lessnoise.chart.NO_PEAK
import ch.lessnoise.chart.NO_VALUE_YET
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.9.2 clarity pass (owner: every element says what it is for, in a few words): the labels and
 * subtitles added or renamed, and where they are used. Every edition (src/test).
 */
class ClarityTextsTest {
    private val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
    private fun src(path: String) = File(app, path).readText()

    @Test
    fun theReportDialogNamesWhatEachPartIsFor() {
        assertEquals("Wohin fuhr es?", REPORT_WHERE)
        assertEquals("Karte verschiebbar", REPORT_DRAG_HINT)
        assertEquals("Mikrofon läuft", ReportLevelUi.MIC_ON)
        assertEquals(40, REPORT_REMOVE_TOUCH_DP)
        val ui = src("src/main/kotlin/ch/lessnoise/app/ui/LocationFlows.kt")
        val body = ui.substringAfter("fun ReportDialogBody(").substringBefore("\n}\n")
        // The heading comes right before the disc, in the style of «Was war es?»; 0.9.4.2: drawn by the Panel (editions
        // with the map), with the live label at its right, then the hint, then the disc.
        val panel = src("src/karte/kotlin/ch/lessnoise/app/ui/ReportDirectionUi.kt").substringAfter("private fun PanelLines(").substringBefore("\n    }\n")
        assertTrue(panel.contains("REPORT_WHERE, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,"), panel)
        assertTrue(panel.indexOf("REPORT_WHERE") < panel.indexOf("liveLabel(") && panel.indexOf("liveLabel(") < panel.indexOf("POINT_HINT") && panel.indexOf("POINT_HINT") < panel.indexOf("disc()"))
        assertTrue(body.indexOf("ReportCategoryRows(rows") < body.indexOf("disc(discSize, measureOnly)"))
        assertTrue(body.contains("Text(REPORT_WHAT, style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))"))
        // 0.9.4.2: «Mikrofon läuft» is the microphone icon at the start of the status line (its alternate text, and
        // in the line's spoken sentence), shown whenever the level is.
        val level = src("src/main/kotlin/ch/lessnoise/app/ui/ReportLevel.kt").substringAfter("fun ReportStatusLine(").substringBefore("\n}\n")
        assertTrue(level.contains("appendInlineContent(STATUS_MIC, ReportLevelUi.MIC_ON)") && level.contains("Icon(AppIcons.Mic,"), level)
        assertTrue(level.indexOf("appendInlineContent(STATUS_MIC, ReportLevelUi.MIC_ON)") < level.lastIndexOf("row(placeTrailing, place)"), "the level first, then the place")
        assertEquals("Mikrofon läuft: 68,9 dB(A) jetzt, Spitze 75,8 dB(A)", reportLevelDescription(ReportLevelNow(68.9, 75.8)))
        assertEquals("68,9 dB(A) · Spitze 75,8", reportLevelPart(ReportLevelNow(68.9, 75.8)))
        assertEquals("– dB(A) · Spitze –", reportLevelPart(ReportLevelNow()))
        // No break inside a part, only after « · » (and «·» ends the line).
        assertEquals("Standort\u00A0±\u00A0100\u00A0m\u00A0· Karte verschiebbar", reportStatusNoBreak("Standort ± 100 m · Karte verschiebbar"))
        assertEquals("Standort von Hand gesetzt", reportStatusNoBreak(REPORT_MOVED_LINE))
        assertEquals("Spitze\u00A075,8", reportStatusNoBreak("Spitze 75,8"))
        // The photo's «×»: the 40 dp target holds the 22 dp circle.
        val photo = ui.substringAfter("fun ReportPhotoRow(").substringBefore("\n}\n")
        assertTrue(photo.contains("size(REPORT_REMOVE_TOUCH_DP.dp)") && photo.contains("Modifier.size(22.dp)"))
        assertTrue(photo.contains("onClick = onRemove"))
    }

    @Test
    fun theConsentNamesEverythingAReportCarries() {
        for (part in listOf("Zeit", "genauer Standort", "Fahrtrichtung", "Pegel", "Art des Lärms", "nie Audio")) {
            assertTrue(REPORT_CONSENT_TEXT.contains(part), part)
        }
        assertTrue(SHARE_SENT_BULLETS.any { it.startsWith("Meldungen:") && it.contains("Fahrtrichtung") && it.contains("Pegel") })
    }

    @Test
    fun shortLabelsInsteadOfDashesAndTodos() {
        assertEquals("Grundrauschen (ohne Spitzen)", HUM_LABEL)
        assertEquals("ohne Spitzen", HUM_NOTE)
        assertEquals("leise bis laut", BAND_LEGEND)
        assertEquals("Hervorheben:", HIGHLIGHT_LABEL)
        assertEquals("noch kein Wert", NO_VALUE_YET)
        assertEquals("noch keine", NONE_YET_TILE)
        assertEquals("noch keine", NONE_YET)
        assertEquals("keine", NO_PEAK)
        assertEquals("Noch nicht gesendet", PENDING_LABEL)
        assertEquals("Kein Standort für die Karte", STATUS_NO_POINT)
        assertEquals("Foto löschen", ReportPhotos.DELETE)
        assertEquals("Ohne Mikrofon kann die App nicht messen.", MIC_DENIED)
        val share = src("src/main/kotlin/ch/lessnoise/app/ui/ShareScreen.kt")
        assertFalse(share.contains("\"Noch zu senden\""))
        assertEquals(2, Regex("""StatRow\(PENDING_LABEL""").findAll(share).count())
        val measure = src("src/main/kotlin/ch/lessnoise/app/ui/MeasureScreen.kt")
        assertTrue(measure.contains("StatRow(HUM_LABEL,"))
        assertTrue(measure.contains("else scope.launch { snackbar.showSnackbar(MIC_DENIED) }"))
        assertTrue(src("src/main/kotlin/ch/lessnoise/app/ui/MeasurementsScreen.kt").contains("StatRow(HUM_LABEL,"))
    }

    @Test
    fun everySettingsRowSaysWhatItDoes() {
        assertEquals("Alle Messungen als Datei teilen", EXPORT_SUBTITLE)
        assertEquals("Lärmspitzen, Mikrofon, Kalibrieren", ADVANCED_SUBTITLE)
        assertEquals("Wann ein Geräusch als Lärmspitze zählt.", EVENT_SETTINGS_SUBTITLE)
        assertTrue(CALIBRATE_INTRO.startsWith("Gleicht die Werte an ein Messgerät an."))
        assertTrue(MicrophoneTexts.INTRO.startsWith("Wähle, welches Mikrofon misst."))
        // The labels the website quotes stay.
        assertEquals("Exportieren", EXPORT_LABEL)
        assertEquals("Alle löschen", CLEAR_LABEL)
        val settings = src("src/main/kotlin/ch/lessnoise/app/ui/DataAndSettingsScreens.kt")
        assertTrue(settings.contains("ActionRow(EXPORT_LABEL, AppIcons.Download, onExport, supporting = EXPORT_SUBTITLE)"))
        assertTrue(settings.contains("supporting = if (running) CLEAR_WHILE_RUNNING else CLEAR_SUBTITLE"))
    }
}
