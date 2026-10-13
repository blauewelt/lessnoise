package ch.lessnoise.app

import ch.lessnoise.app.edition.EditionInfo
import ch.lessnoise.app.edition.EditionUi
import ch.lessnoise.app.update.UpdateCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The edition texts must tell the truth about each edition. Runs in every flavour
 * (testOfflineDebugUnitTest, testPublicDebugUnitTest, testLaborDebugUnitTest,
 * testLaborKarteDebugUnitTest) and branches on BuildConfig.FLAVOR.
 */
class EditionTextTest {
    @Test
    fun aboutAudioMatchesTheEdition() {
        val text = EditionUi.ABOUT_AUDIO
        if (BuildConfig.FLAVOR == "labor") {
            // Labor can store clips and hour files locally; it has no internet permission.
            assertTrue(text.contains("Weniger Lärm Labor"), text)
            assertTrue(text.contains("keine Internet-Berechtigung"), text)
            assertFalse(text.contains("nie Audio"), text)
            assertFalse(EditionUi.MIC_LINE.contains("nie Audio"), EditionUi.MIC_LINE)
        } else if (BuildConfig.FLAVOR == "laborKarte") {
            // Labor with «Messwerte teilen»: must not claim «no internet», must say audio is never sent.
            assertEquals(
                "Weniger Lärm Labor: Audio kann – nur wenn du es einschaltest – als Clips und Stundendateien auf diesem Telefon " +
                    "gespeichert werden, dazu ein Protokoll der Erkennung. " +
                    "Nichts davon wird gesendet: «Teilen» sendet nur Kennwerte, nie Audio, Clips oder Protokolle.",
                text,
            )
            assertFalse(text.contains("keine Internet-Berechtigung"), text)
            assertFalse(EditionUi.MIC_LINE.contains("nie Audio"), EditionUi.MIC_LINE)
        } else {
            // Public editions: the one plain audio promise, in Datenschutz and at the microphone line on «Messen».
            assertEquals("Es wird nie Audio gespeichert oder gesendet.", text)
            assertEquals("Die App braucht das Mikrofon. Es wird nie Audio gespeichert oder gesendet.", EditionUi.MIC_LINE)
        }
    }

    @Test
    fun buildConfigMatchesTheFlavour() {
        when (BuildConfig.FLAVOR) {
            "offline" -> {
                // The default download: no «Messwerte teilen» (and no INTERNET, checked with aapt2).
                assertEquals(EditionInfo.OFFLINE, BuildConfig.EDITION)
                assertFalse(BuildConfig.UPLOAD_AVAILABLE)
                assertEquals("ch.lessnoise.app", BuildConfig.APPLICATION_ID)
            }
            "public" -> {
                assertEquals(EditionInfo.KARTE, BuildConfig.EDITION)
                assertTrue(BuildConfig.UPLOAD_AVAILABLE)
                assertEquals("ch.lessnoise.app", BuildConfig.APPLICATION_ID)
            }
            "labor" -> {
                assertEquals(EditionInfo.LABOR, BuildConfig.EDITION)
                assertFalse(BuildConfig.UPLOAD_AVAILABLE)
                assertEquals("ch.lessnoise.labor", BuildConfig.APPLICATION_ID)
            }
            "laborKarte" -> {
                // Internal: Labor + «Messwerte teilen». Same app id as Labor (installs over it).
                assertEquals(EditionInfo.LABOR_KARTE, BuildConfig.EDITION)
                assertTrue(BuildConfig.UPLOAD_AVAILABLE)
                assertEquals("ch.lessnoise.labor", BuildConfig.APPLICATION_ID)
                assertEquals("0.6.1-labor-karte", BuildConfig.VERSION_NAME)
            }
            else -> error("unknown flavour ${BuildConfig.FLAVOR}")
        }
        // Offline and Karte editions are one app in two editions: same version name, no suffix
        // (update.html compares versionCode; the edition travels separately as &e=).
        if (BuildConfig.FLAVOR == "labor") assertTrue(BuildConfig.VERSION_NAME.endsWith("-labor"))
        else if (BuildConfig.FLAVOR == "laborKarte") assertTrue(BuildConfig.VERSION_NAME.endsWith("-labor-karte"))
        else assertFalse(BuildConfig.VERSION_NAME.contains('-'), BuildConfig.VERSION_NAME)
    }

    @Test
    fun editionTexts() {
        assertEquals("Weniger Lärm", EditionInfo.label(EditionInfo.OFFLINE))
        assertEquals("Weniger Lärm · Karte", EditionInfo.label(EditionInfo.KARTE))
        assertEquals("Weniger Lärm Labor", EditionInfo.label(EditionInfo.LABOR))
        assertEquals("Weniger Lärm Labor · Karte", EditionInfo.label(EditionInfo.LABOR_KARTE))
        assertTrue(EditionInfo.summary(EditionInfo.LABOR_KARTE).contains("Audio wird nie gesendet"))
        assertNotNull(EditionInfo.switchHint(EditionInfo.LABOR_KARTE))
        assertEquals("Weniger Lärm: ohne Internet und ohne Standort.", EditionInfo.summary(EditionInfo.OFFLINE))
        assertEquals("Weniger Lärm · Karte: gesendet wird nur, wenn du «Teilen» einschaltest.", EditionInfo.summary(EditionInfo.KARTE))
        // Switching: install the other APK from wenigerlaerm.ch over this one, measurements are kept.
        for (e in listOf(EditionInfo.OFFLINE, EditionInfo.KARTE)) {
            val hint = assertNotNull(EditionInfo.switchHint(e))
            assertTrue(hint.contains("wenigerlaerm.ch"), hint)
            assertTrue(hint.contains("Messungen bleiben"), hint)
        }
        assertTrue(EditionInfo.switchHint(EditionInfo.OFFLINE)!!.contains("«Weniger Lärm · Karte»"))
        assertTrue(EditionInfo.switchHint(EditionInfo.KARTE)!!.contains("ohne Internet"))
        // Before switching away from the map edition: delete on the server (impossible afterwards).
        assertTrue(EditionInfo.switchHint(EditionInfo.KARTE)!!.contains("vorher unter «Teilen» löschen"))
        // The app's old name appears in no edition text.
        for (e in listOf(EditionInfo.OFFLINE, EditionInfo.KARTE, EditionInfo.LABOR, EditionInfo.LABOR_KARTE)) {
            for (t in listOf(EditionInfo.label(e), EditionInfo.summary(e), EditionInfo.switchHint(e) ?: "")) {
                assertFalse(t.contains("Stadtlärm") || t.contains("stadtlaerm"), t)
            }
        }
        // The server deletes a device's data after 60 days without uploads (server/DESIGN.md §2.9).
        assertTrue(EditionInfo.switchHint(EditionInfo.KARTE)!!.contains("60 Tage"))
        assertNull(EditionInfo.switchHint(EditionInfo.LABOR))
        // Swiss spelling, «du» like the rest of the app.
        val all = listOf(EditionInfo.OFFLINE, EditionInfo.KARTE, EditionInfo.LABOR, EditionInfo.LABOR_KARTE)
            .flatMap { listOf(EditionInfo.label(it), EditionInfo.summary(it), EditionInfo.switchHint(it) ?: "") }
        all.forEach { assertFalse(it.contains('ß'), it); assertFalse(Regex("\\b(Sie|Ihre?)\\b").containsMatchIn(it), it) }
        // The installed edition has its texts (not the raw id).
        assertTrue(EditionInfo.summary(BuildConfig.EDITION).startsWith(EditionInfo.label(BuildConfig.EDITION)))
    }

    @Test
    fun updateUrlCarriesTheEdition() {
        val url = UpdateCheck.updateUrl(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, EditionInfo.updateEdition(BuildConfig.EDITION))
        // Only values docs/update.html accepts (^(offline|karte)$); the Labor builds send none.
        when (BuildConfig.FLAVOR) {
            "offline", "public" -> assertTrue(url.endsWith("&e=${BuildConfig.EDITION}"), url)
            else -> assertFalse(url.contains("&e="), url)
        }
        assertEquals("offline", EditionInfo.updateEdition(EditionInfo.OFFLINE))
        assertEquals("karte", EditionInfo.updateEdition(EditionInfo.KARTE))
        assertNull(EditionInfo.updateEdition(EditionInfo.LABOR))
        assertNull(EditionInfo.updateEdition(EditionInfo.LABOR_KARTE))
    }
}
