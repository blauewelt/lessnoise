package ch.lessnoise.app.ui

import ch.lessnoise.dsp.geo.LocationNoise
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0.6.3: «Auf die Lärmkarte?», the one question at the first «Starten» (server/DESIGN.md §2 rule 3:
 * what is sent, what never, where, how to delete; rule 2: the point is shifted, by about σ), and
 * the contract documents name the same dialog.
 */
class ShareTextsTest {

    @Test
    fun consentSaysWhatWhereNeverAndHowToDelete() {
        assertEquals("Auf die Lärmkarte?", CONSENT_TITLE)
        assertEquals("Ja, auf die Karte", CONSENT_YES)
        assertEquals("Nur auf dem Telefon", CONSENT_NO)
        assertEquals("Mehr dazu", CONSENT_MORE)
        for (sigma in LocationNoise.SIGMAS_M) {
            val (sent, never, delete) = consentLines(sigma).also { assertEquals(3, it.size) }
            // Rule 2: the shift is stated, with the chosen σ; the true place is never sent.
            assertTrue(sent.startsWith("Gesendet:") && sent.contains("um etwa $sigma m verschobenen Punkt statt deines Standorts"), sent)
            assertTrue(sent.contains("pro Minute") && sent.contains("Lärmspitzen"), sent)
            // 0.6.4: only machine and motor noise leaves the phone (Payloads.isMapEvent); say so.
            assertTrue(sent.contains("Lärmspitzen von Motoren und Maschinen"), sent)
            // Where: our server in Switzerland.
            assertTrue(sent.contains("an unseren Server in der Schweiz"), sent)
            assertTrue(never.startsWith("Nie gesendet:") && never.contains("Audio") && never.contains("genauer Standort"), never)
            // The exact place of a report is the one exception, and the consent of «Laut! Melden» is
            // skipped after this «yes»: say so here.
            assertTrue(never.contains("«Laut! Melden»"), never)
            assertTrue(delete.contains("Ausschalten") && delete.contains("löschen") && delete.contains("«Teilen»") && delete.contains("60 Tagen"), delete)
        }
    }

    @Test
    fun consentAndStatusTextsAreSwissGermanWithDuAndOneSentencePerLine() {
        val all = consentLines(50) + listOf(CONSENT_TITLE, CONSENT_YES, CONSENT_NO, CONSENT_MORE) + SHARE_WHEN_BULLETS +
            SHARE_WHERE_BULLETS + shareLocationBullets(50)
        for (t in all) {
            assertFalse(t.contains('ß'), t)
            assertFalse(Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
            assertFalse(t.contains('"') || t.contains('„') || t.contains('“'), t)
            assertEquals(0, t.trimEnd('.', '?').count { it == '.' }, "one sentence: $t")
            assertFalse(t.contains("Ereignis"), "the app says «Lärmspitzen»: $t")
        }
        assertTrue(SHARE_SENT_BULLETS[0].contains("Motoren und Maschinen") && SHARE_SENT_BULLETS[0].contains("Stimmen und Musik nie"), SHARE_SENT_BULLETS[0])
        // The cadence and the network setting of 0.6.3 (server/DESIGN.md §4.6).
        // The live map (0.6.3): loud moments after about 30 s, minutes every 2 minutes; UploadCadenceTest backs the 3 MB.
        assertTrue(SHARE_WHEN_BULLETS[0].contains("30 Sekunden") && SHARE_WHEN_BULLETS[0].contains("alle 2 Minuten") && SHARE_WHEN_BULLETS[0].contains("Stoppen"))
        assertTrue(SHARE_WHEN_BULLETS[1].contains("mobile Daten") && SHARE_WHEN_BULLETS[1].contains("3 MB"))
        // The location is read after «Starten», while the app is open (never in the background).
        assertTrue(shareLocationBullets(50)[0].contains("Nach dem Starten") && shareLocationBullets(50)[0].contains("solange die App offen ist"))
        // Switching on during a measurement shares that whole measurement, never an earlier one.
        assertTrue(SHARE_WHERE_BULLETS.any { it.contains("während einer Messung") && it.contains("frühere nie") })
    }

    @Test
    fun theContractDocumentsNameTheSameDialog() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.take(5)
            .firstOrNull { File(it, "server/DESIGN.md").isFile } ?: fail("repository root not found")
        val design = File(root, "server/DESIGN.md").readText()
        val privacy = File(root, "PRIVACY.md").readText()
        val website = File(root, "docs/datenschutz.html").readText()
        for ((name, text) in listOf("server/DESIGN.md" to design, "PRIVACY.md" to privacy, "docs/datenschutz.html" to website)) {
            assertTrue(text.contains(CONSENT_TITLE), "$name names «$CONSENT_TITLE»")
            assertTrue(text.contains(CONSENT_YES), "$name names «$CONSENT_YES»")
        }
        // §2 rule 3 is the opt-in rule; it must describe this dialog.
        val rule3 = Regex("""3\. \*\*[^\n]*opt-in[\s\S]*?(?=\n4\. \*\*)""").find(design)?.value ?: fail("rule 3 not found")
        assertTrue(rule3.contains(CONSENT_TITLE) && rule3.contains("«Starten»"), rule3)
    }

    @Test
    fun theLicencePlateIsShownInTheListButNeverSaidToBeSent() {
        // 0.7.6: «Kontrollschild (optional)» in «Laut! Melden»; on the phone only. Since 0.8.1 the
        // confirmation that repeated it («Kontrollschild: ZH 123») is gone (0.8.2: «Senden» in the one dialog).
        val zone = java.time.ZoneId.of("Europe/Zurich")
        val t = java.time.ZonedDateTime.of(2026, 10, 24, 16, 5, 3, 0, zone).toInstant().toEpochMilli()
        // The sent list (Teilen › Meldungen).
        val sent = ch.lessnoise.app.upload.SentReport(t, "motorrad", "h1_2", plate = "ZH 123")
        assertEquals("24.10. 16:05 · Töff · ZH 123", sentReportLine("24.10. 16:05", sent))
        assertEquals("24.10. 16:05 · Töff", sentReportLine("24.10. 16:05", sent.copy(plate = null)))
        // 0.8.5: the report's peak level in whole dB.
        assertEquals("24.10. 16:05 · Töff · 81 dB", sentReportLine("24.10. 16:05", sent.copy(plate = null, lafMaxDb = 81.4, laeqDb = 66.2)))
        assertEquals("24.10. 16:05 · Töff · 82 dB · ZH 123", sentReportLine("24.10. 16:05", sent.copy(lafMaxDb = 81.5)))
        // 0.9.1: «Poser» and «anderes Fahrzeug» are no longer offered, but reports made with them are still listed by name.
        assertEquals("24.10. 16:05 · Poser · 84 dB", sentReportLine("24.10. 16:05", sent.copy(category = "poser", plate = null, lafMaxDb = 84.4)))
        assertEquals("24.10. 16:05 · anderes Fahrzeug", sentReportLine("24.10. 16:05", sent.copy(category = "andere", plate = null)))
        // 0.8.5: the field is gone (a photo instead); plates of 0.7.6–0.8.4 reports are still listed.
        // What the app says is sent with a report does not mention the plate (it is not sent).
        for (line in SHARE_SENT_BULLETS + shareLocationBullets(50) + consentLines(50)) {
            assertFalse(line.contains("Kontrollschild") || line.contains("Foto"), line)
        }
    }

    @Test
    fun teilenInTwoSectionsAndOneSwitch() {
        // 0.8.8: «Teilen» says what goes on the map, split into the window measurements and the reports.
        assertEquals("Teilen", SHARE_TITLE)
        assertEquals("Was von dir auf die Lärmkarte kommt.", SHARE_INTRO)
        assertEquals("Messungen am Fenster", WINDOW_SECTION_TITLE)
        assertEquals("Für lange Messungen: Pegel pro 100-m-Quadrat, Ort leicht verschoben.", WINDOW_SECTION_SUBTITLE)
        assertEquals("Meldungen unterwegs", REPORTS_SECTION_TITLE)
        assertEquals("Mit «Laut! Melden»: genauer Ort, nur wenn du sendest.", REPORTS_SECTION_SUBTITLE)
        assertEquals("12 Minuten", pendingMinutesText(12))
        assertEquals("1 Minute", pendingMinutesText(1))
        assertEquals("0 Minuten", pendingMinutesText(0))
        for (t in listOf(SHARE_INTRO, WINDOW_SECTION_SUBTITLE, REPORTS_SECTION_SUBTITLE, REPORTS_SAME_SWITCH, REPORTS_NONE)) {
            assertFalse(t.contains('ß') || Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
        }
        // One switch: the report consent turns on the same flag as «Auf die Karte senden», and the upload
        // run sends nothing (reports included) while it is off. The line in «Meldungen unterwegs» says so.
        val app = listOf(File("."), File("app")).first { File(it, "src/main").isDirectory }
        val src = File(app, "src/main/kotlin/ch/lessnoise/app")
        val flows = File(src, "ui/LocationFlows.kt").readText()
        assertTrue(flows.contains("if (m.uploader.enable()) m.applySchedule()"))
        assertTrue(REPORT_CONSENT_TEXT.contains("Dafür wird «Teilen» eingeschaltet"))
        val uploader = File(src, "upload/Uploader.kt").readText()
        val run = uploader.substring(uploader.indexOf("private suspend fun runLocked("))
        assertTrue(run.indexOf("if (!s.enabled) return Outcome.Disabled") in 0 until run.indexOf("// Reports first"))
        assertTrue(REPORTS_SAME_SWITCH.contains("«Auf die Karte senden»") && REPORTS_SAME_SWITCH.contains("Messungen am Fenster"))
        // The screen: the switch row is labelled as REPORTS_SAME_SWITCH quotes it; order of the four sections;
        // no «Weitere Angaben» fold on the screen level any more (PlacementCard keeps its own for Stockwerk/Notiz).
        val screen = File(src, "ui/ShareScreen.kt").readText()
        val content = screen.substring(screen.indexOf("fun ShareContent("), screen.indexOf("private fun WindowSettings("))
        val order = listOf(
            "ScreenTitle(SHARE_TITLE)", "HelperText(SHARE_INTRO", "CardHeader(AppIcons.Window, WINDOW_SECTION_TITLE, WINDOW_SECTION_SUBTITLE)",
            "SwitchRow(\"Auf die Karte senden\"", "StatusChip(it, onStatus)", "WindowSettings(enabled = s.enabled)", "\"Nur über WLAN\"",
            "SigmaSetting(", "StatRow(\"Noch zu senden\"", "\"Wo liegt das Telefon? (freiwillig)\"", "ReportsCard(reports)",
            "FoldRow(\"Was gesendet wird\"", "SectionCard(\"Deine Daten auf dem Server\")", "DestructiveButton(\"Vom Server löschen\"",
        ).map { content.indexOf(it) }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), order.toString())
        assertFalse(content.contains("\"Weitere Angaben\""))
        val reports = screen.substring(screen.indexOf("private fun ReportsCard("))
        assertTrue(reports.contains("CardHeader(AppIcons.Campaign, REPORTS_SECTION_TITLE, REPORTS_SECTION_SUBTITLE)"))
        assertTrue(reports.contains("Text(REPORTS_SAME_SWITCH"))
    }
}
