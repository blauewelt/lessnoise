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
}
