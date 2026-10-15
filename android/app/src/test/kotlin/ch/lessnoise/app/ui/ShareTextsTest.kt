package ch.lessnoise.app.ui

import ch.lessnoise.dsp.geo.LocationNoise
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The question «Auf die Lärmkarte?» at the first «Starten» (0.6.3) is the opt-in of
 * server/DESIGN.md §2 rule 3: it names what is sent (with the shift of rule 2), what never, where,
 * how to switch off and delete; the full explanation is one tap away. The contract documents say so.
 */
class ShareTextsTest {

    @Test
    fun theQuestionNamesWhatWhereAndHowToDelete() {
        assertEquals("Auf die Lärmkarte?", CONSENT_TITLE)
        assertEquals("Ja, auf die Karte", CONSENT_YES)
        assertEquals("Nur auf dem Telefon", CONSENT_NO)
        assertEquals("Mehr dazu", CONSENT_MORE)
        for (sigma in LocationNoise.SIGMAS_M) {
            val (sent, never, delete) = consentLines(sigma).also { assertEquals(3, it.size) }
            // What is sent, with the shift (rule 2), and where.
            for (part in listOf("Gesendet:", "Lärmwerte pro Minute", "Lärmspitzen", "um etwa $sigma m verschobenen Punkt", "statt deines Standorts", "Server in der Schweiz")) {
                assertTrue(sent.contains(part), "$part in $sent")
            }
            // What never; the exact place only with a report (rule 10).
            for (part in listOf("Nie gesendet:", "Audio", "Name", "genauer Standort", "«Laut! Melden»")) assertTrue(never.contains(part), part)
            // Switching off, deleting, and the server's 60-day rule (rule 9).
            for (part in listOf("Ausschalten", "löschen", "«Teilen»", "60 Tagen")) assertTrue(delete.contains(part), part)
            // The details of rule 2 stay in the full text, one tap away («Mehr dazu»).
            assertTrue(shareLocationBullets(sigma).any { it.contains("Am selben Ort immer gleich verschoben") })
            assertTrue(shareLocationBullets(sigma).any { it.contains("${2 * sigma} m") })
            assertTrue(shareLocationBullets(sigma)[0].contains("solange die App offen ist"))
        }
        // The full text says what happens when sharing is switched on during a measurement.
        assertTrue(SHARE_WHERE_BULLETS.any { it.contains("während einer Messung ein") && it.contains("ganze Messung") })
        // App style: «du», Swiss spelling, guillemets, short.
        for (t in consentLines(50) + CONSENT_TITLE + CONSENT_YES + CONSENT_NO + SHARE_DETAILS_TOGGLE + PLACEMENT_TOGGLE + NO_POINT_YET) {
            assertFalse(t.contains('ß') || t.contains('"') || t.contains('„'), t)
            assertFalse(Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
            assertTrue(t.length <= 160, "$t (${t.length})")
        }
    }

    private fun repoFile(path: String): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { it.parentFile }.take(5).map { File(it, path) }.firstOrNull { it.isFile }
            ?: error("$path not found from $start")
    }

    @Test
    fun theContractDocumentsDescribeTheQuestion() {
        val design = repoFile("server/DESIGN.md").readText()
        val rule3 = design.substringAfter("3. **Off by default, one explicit opt-in.**").substringBefore("4. **Delete means delete.**")
        for (part in listOf("«Auf die Lärmkarte?»", "«Starten»", "«Ja, auf die Karte»", "«Nur auf dem Telefon»", "«Mehr dazu»", "«Teilen»")) {
            assertTrue(rule3.contains(part), "DESIGN.md §2 rule 3 should mention $part")
        }
        val cadence = design.substringAfter("### 4.6 Upload cadence in the app").substringBefore("### 4.7")
        for (part in listOf("10 minutes", "2 minutes", "«Stoppen»", "hour", "mobile data")) assertTrue(cadence.contains(part), "§4.6: $part")
        val privacy = repoFile("PRIVACY.md").readText()
        for (part in listOf("Auf die Lärmkarte?", "right after «Starten»", "never read in the background", "every 10 minutes", "mobile data")) {
            assertTrue(privacy.contains(part), "PRIVACY.md: $part")
        }
    }
}
