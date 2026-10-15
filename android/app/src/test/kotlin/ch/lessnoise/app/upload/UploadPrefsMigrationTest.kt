package ch.lessnoise.app.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Upgrade to 0.6.3: the answer to «Auf die Lärmkarte?» and the one-time reset of «Nur über WLAN». */
class UploadPrefsMigrationTest {

    @Test
    fun sharingThatWasOnBecomesYesEverythingElseIsAskedOnce() {
        // 0.6.2 stored no decision.
        assertEquals(ShareDecision.YES, UploadPrefsMigration.decision(stored = null, enabled = true))
        assertEquals(ShareDecision.UNDECIDED, UploadPrefsMigration.decision(stored = null, enabled = false))
        // Once stored, it is read back as it is.
        for (d in ShareDecision.entries) {
            assertEquals(d, UploadPrefsMigration.decision(stored = d.name, enabled = false))
            assertEquals(d, UploadPrefsMigration.decision(stored = d.name, enabled = true))
        }
        // Garbage counts as not stored.
        assertEquals(ShareDecision.UNDECIDED, UploadPrefsMigration.decision(stored = "MAYBE", enabled = false))
    }

    @Test
    fun wifiOnlyIsResetOnceOnTheUpgradeAndKeptAfterwards() {
        // Fresh install: nothing stored → mobile data allowed.
        assertFalse(UploadPrefsMigration.wifiOnly(storedVersion = null, storedWifiOnly = null))
        // 0.6.2 (no prefs_version) with the old default true → reset once.
        assertFalse(UploadPrefsMigration.wifiOnly(storedVersion = null, storedWifiOnly = true))
        assertFalse(UploadPrefsMigration.wifiOnly(storedVersion = UploadPrefsMigration.LEGACY_VERSION, storedWifiOnly = true))
        // After the upgrade (prefs_version 3 stored) the person's choice stays.
        assertTrue(UploadPrefsMigration.wifiOnly(storedVersion = UploadPrefsMigration.CURRENT_VERSION, storedWifiOnly = true))
        assertFalse(UploadPrefsMigration.wifiOnly(storedVersion = UploadPrefsMigration.CURRENT_VERSION, storedWifiOnly = false))
    }

    @Test
    fun theSwitchAndDeletingSetTheDecision() {
        val store = MemoryUploadStore()
        val up = Uploader(UploadClient("http://127.0.0.1:9"), store, object : UploadSource {
            override suspend fun minutesAfter(afterMs: Long, limit: Int) = emptyList<ch.lessnoise.app.data.MinuteEntity>()
            override suspend fun eventsAfter(afterMs: Long, limit: Int) = emptyList<ch.lessnoise.app.data.EventEntity>()
            override suspend fun measurements() = emptyList<ch.lessnoise.app.data.MeasurementEntity>()
            override suspend fun siteExtras() = SiteExtras("x", "MIC", false, 0.0)
        }, MemoryReportStore(), "x", 1)
        assertEquals(ShareDecision.UNDECIDED, store.state.value.decision)
        up.enable()
        assertEquals(ShareDecision.YES, store.state.value.decision)
        assertTrue(store.state.value.enabled)
        up.disable()
        assertEquals(ShareDecision.NO, store.state.value.decision)
        assertFalse(store.state.value.enabled)
        up.enable()
        // «Vom Server löschen» switches off: NO (not registered, so nothing is sent).
        kotlinx.coroutines.runBlocking { up.deleteServerData() }
        assertEquals(ShareDecision.NO, store.state.value.decision)
        assertFalse(store.state.value.enabled)
    }
}
