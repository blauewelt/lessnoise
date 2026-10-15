package ch.lessnoise.app.upload

import ch.lessnoise.app.upload.UploadPrefsMigration.Stored
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 0.6.3: the stored upload settings of 0.6.2 and older become the share decision; mobile data once. */
class UploadPrefsMigrationTest {

    @Test
    fun sharingOnIn062MeansYesEverybodyElseIsAskedOnce() {
        // 0.6.2 had no version key (read as 1) and no decision.
        assertEquals(ShareDecision.YES, UploadPrefsMigration.migrate(Stored(1, enabled = true, decision = null, wifiOnly = true)).decision)
        assertEquals(ShareDecision.UNDECIDED, UploadPrefsMigration.migrate(Stored(1, enabled = false, decision = null, wifiOnly = true)).decision)
        // A fresh install: nothing stored.
        assertEquals(
            UploadPrefsMigration.Result(ShareDecision.UNDECIDED, wifiOnly = false, version = 2),
            UploadPrefsMigration.migrate(Stored(1, enabled = false, decision = null, wifiOnly = false)),
        )
    }

    @Test
    fun wifiOnlyIsResetOnceOnTheUpdateTo063AndThenKept() {
        // The update from 0.6.2 (default «Nur über WLAN» on): reset to mobile data allowed.
        val first = UploadPrefsMigration.migrate(Stored(1, enabled = true, decision = null, wifiOnly = true))
        assertEquals(false, first.wifiOnly)
        assertEquals(UploadPrefsMigration.CURRENT, first.version)
        // Afterwards the person's own choice stays, both ways.
        assertEquals(true, UploadPrefsMigration.migrate(Stored(2, enabled = true, decision = "yes", wifiOnly = true)).wifiOnly)
        assertEquals(false, UploadPrefsMigration.migrate(Stored(2, enabled = true, decision = "yes", wifiOnly = false)).wifiOnly)
    }

    @Test
    fun aStoredDecisionIsKept() {
        for (d in ShareDecision.entries) {
            // Also when it disagrees with «enabled» (e.g. NO after «Vom Server löschen», UNDECIDED never stored with on).
            assertEquals(d, UploadPrefsMigration.migrate(Stored(2, enabled = d == ShareDecision.YES, decision = d.stored, wifiOnly = false)).decision)
            assertEquals(d, ShareDecision.fromStored(d.stored))
        }
        assertNull(ShareDecision.fromStored("maybe"))
        // An unknown stored value falls back to the 0.6.2 rule.
        assertEquals(ShareDecision.YES, UploadPrefsMigration.migrate(Stored(2, enabled = true, decision = "maybe", wifiOnly = false)).decision)
        assertEquals(listOf("undecided", "yes", "no"), ShareDecision.entries.map { it.stored })
    }

    @Test
    fun snapshotDefaultsAre063() {
        val s = UploadSnapshot()
        assertEquals(ShareDecision.UNDECIDED, s.shareDecision)
        assertEquals(false, s.wifiOnly)
        assertEquals(false, s.enabled)
    }
}
