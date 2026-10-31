package ch.lessnoise.app.labor

import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.edition.EditionUi
import ch.lessnoise.app.upload.KotlinSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.9.4.6 (owner: «The Labor build should be like the production version … Then there is this one thing you can turn
 * on where the audio gets saved as well»): no red banner (until 0.9.4.5 «WENIGER LÄRM LABOR – kann Audio
 * aufzeichnen»), one switch «Audio speichern» on the same stored preference, and «Audio wird gespeichert» on «Messen»
 * and in the notification only while audio is actually stored. Runs in testLaborDebugUnitTest and
 * testLaborKarteDebugUnitTest (src/testLabor/ is a test source of both flavours).
 */
class LaborBannerTest {
    private val app: File = listOf(File("."), File("app")).first { File(it, "src/labor").isDirectory }
    // Code and string literals, comments blanked (KotlinSource): the KDoc may name what was removed.
    private val edition by lazy { KotlinSource.parse(File(app, "src/labor/kotlin/ch/lessnoise/app/edition/Edition.kt").readText()).code }

    @Test
    fun noBannerAnyMore() {
        assertTrue(BuildConfig.FLAVOR in setOf("labor", "laborKarte"), BuildConfig.FLAVOR)
        val banner = edition.substring(edition.indexOf("fun Banner()"), edition.indexOf("fun RecordingNote()"))
        assertTrue(banner.startsWith("fun Banner() {}"), banner)
        for (gone in listOf("BANNER_TEXT", "WENIGER LÄRM LABOR", "kann Audio aufzeichnen", "Aufnahme läuft")) {
            assertFalse(edition.contains(gone), gone)
        }
    }

    @Test
    fun oneSwitchOnTheSamePreference() {
        assertEquals("Labor", LaborCardTexts.TITLE)
        assertEquals("Audio speichern", LaborCardTexts.SWITCH)
        // The card's only switch; the old sub-settings are not in the UI any more.
        val card = KotlinSource.parse(File(app, "src/labor/kotlin/ch/lessnoise/app/labor/LaborCardUi.kt").readText()).code
        assertEquals(1, Regex("""\bSwitchRow\(""").findAll(card).count())
        for (gone in listOf(
            "Audio während der Messung aufzeichnen", "Ereignis-Clips", "Durchgehend (AAC", "Clip-Rate", "Maximaler Speicher",
            // The statistics rows (0.9.4.5: StatRow «Belegt», «Clips», «Stundendateien», «Klassifikator-Protokolle»,
            // «Mikrofon», «Bewegung»); the ZIP dialog may still count the files it packs.
            "StatRow(", "Wo sind die Dateien?", "exportInfo",
        )) {
            assertFalse(card.contains(gone) || edition.contains(gone), gone)
        }
        // What stays when the switch is on or recordings exist: space, clips, export (scope, folder, ZIP, progress), deletes.
        for (kept in listOf("LaborCardTexts.CLIPS", "ExportScopeControls(", "LaborCardTexts.EXPORT_FOLDER", "LaborCardTexts.EXPORT_ZIP",
            "LaborCardTexts.PICK_FOLDER", "ExportJobPanel(", "LaborCardTexts.DELETE_EXPORTED", "LaborCardTexts.DELETE_ALL")) {
            assertTrue(card.contains(kept), kept)
        }
        assertTrue(card.indexOf("if (!showFiles) return@SectionCard") in 0 until card.indexOf("ExportScopeControls("))
        // The same SharedPreferences key as 0.9.4.5's «Audio während der Messung aufzeichnen»: an updated phone keeps recording.
        val state = KotlinSource.parse(File(app, "src/labor/kotlin/ch/lessnoise/app/labor/LaborState.kt").readText()).code
        assertTrue(state.contains("recordAudio = p.getBoolean(\"record_audio\", false)"))
        assertTrue(state.contains(".putBoolean(\"record_audio\", n.recordAudio)"))
        // New installs: off; and the stored defaults of what is no longer shown (clips, continuous, every clip, 5 GB).
        assertEquals(LaborSettings(recordAudio = false, clips = true, continuous = true, clipEvery = 1, maxGb = 5.0), LaborSettings())
        assertTrue(state.contains("clips = p.getBoolean(\"clips\", true)") && state.contains("continuous = p.getBoolean(\"continuous\", true)"))
        assertTrue(state.contains("clipEvery = p.getInt(\"clip_every\", 1)") && state.contains("p.getFloat(\"max_gb\", StorageBudget.DEFAULT_GB.toFloat())"))
        // Switching on keeps what is stored; only a setting that recorded no audio at all gets clips and hour files back.
        val custom = LaborSettings(clips = false, continuous = true, clipEvery = 5, maxGb = 2.0)
        assertEquals(custom.copy(recordAudio = true), LaborCardTexts.switchedOn(custom))
        assertEquals(LaborSettings(recordAudio = true, clips = true, continuous = true, clipEvery = 2), LaborCardTexts.switchedOn(LaborSettings(clips = false, continuous = false, clipEvery = 2)))
        // The confirmation stays (shortened): switching on asks first; switching off does not.
        assertTrue(edition.contains("onRecordAudio = { v -> if (v) confirmOn = true else Labor.update(context) { it.copy(recordAudio = false) } },"))
        assertTrue(edition.contains("Labor.update(context) { LaborCardTexts.switchedOn(it) }"))
        assertTrue(LaborCardTexts.CONFIRM_TEXT.contains("Stimmen") && LaborCardTexts.CONFIRM_TEXT.length <= 110, LaborCardTexts.CONFIRM_TEXT)
    }

    @Test
    fun theCardShowsMoreOnlyWhenOnOrWithRecordings() {
        assertFalse(LaborCardTexts.showsFiles(recordAudio = false, clips = 0, hourFiles = 0, classifierFiles = 0))
        assertTrue(LaborCardTexts.showsFiles(recordAudio = true, clips = 0, hourFiles = 0, classifierFiles = 0))
        assertTrue(LaborCardTexts.showsFiles(recordAudio = false, clips = 3, hourFiles = 0, classifierFiles = 0))
        assertTrue(LaborCardTexts.showsFiles(recordAudio = false, clips = 0, hourFiles = 1, classifierFiles = 0))
        assertTrue(LaborCardTexts.showsFiles(recordAudio = false, clips = 0, hourFiles = 0, classifierFiles = 2))
        assertEquals("1,2 GB von 5 GB belegt", LaborCardTexts.used(1_234_000_000L, 5_000_000_000L))
        assertEquals("350 MB von 5 GB belegt", LaborCardTexts.used(349_600_000L, 5_000_000_000L))
        assertEquals("0 MB von 0,5 GB belegt", LaborCardTexts.used(0L, 500_000_000L))
    }

    @Test
    fun audioWirdGespeichertOnlyWhileAudioIsStored() {
        assertEquals("Audio wird gespeichert", RecordingNote.TEXT)
        assertEquals(RecordingNote.TEXT, RecordingNote.text(active = true, audioOn = true, recordAudio = true, clips = true, continuous = true))
        assertEquals(RecordingNote.TEXT, RecordingNote.text(active = true, audioOn = true, recordAudio = true, clips = false, continuous = true))
        assertNull(RecordingNote.text(active = false, audioOn = true, recordAudio = true, clips = true, continuous = true), "stopped")
        assertNull(RecordingNote.text(active = true, audioOn = false, recordAudio = true, clips = true, continuous = true), "budget used up")
        assertNull(RecordingNote.text(active = true, audioOn = true, recordAudio = false, clips = true, continuous = true), "switched off")
        assertNull(RecordingNote.text(active = true, audioOn = true, recordAudio = true, clips = false, continuous = false), "no audio kind")
        // The recorder hands it to the notification (MeasurementNotification.text appends « · …»).
        val recorder = KotlinSource.parse(File(app, "src/labor/kotlin/ch/lessnoise/app/labor/LaborRecorder.kt").readText()).code
        assertTrue(recorder.contains("override val notificationNote: String?\n        get() = RecordingNote.text(active, audioOn, Labor.settings.value.recordAudio, settings.clips, settings.continuous)"))
        // «Messen» shows the line only while a run records.
        val note = edition.substring(edition.indexOf("fun RecordingNote()"), edition.indexOf("fun SettingsSection()"))
        assertTrue(note.contains("if (live.running && st.recording && s.recordAudio)"))
        assertTrue(note.contains("ch.lessnoise.app.labor.RecordingNote.TEXT"))
    }

    @Test
    fun theAudioTextsAreHonest() {
        assertEquals("Labor: speichert Audio nur, wenn «Audio speichern» an ist. Es wird nie Audio gesendet.", EditionUi.MIC_LINE)
        assertTrue(EditionUi.ABOUT_AUDIO.contains("«Audio speichern»") && EditionUi.ABOUT_AUDIO.contains("nie Audio gesendet"), EditionUi.ABOUT_AUDIO)
        for (t in listOf(EditionUi.MIC_LINE, EditionUi.ABOUT_AUDIO, LaborCardTexts.CONFIRM_TEXT, LaborCardTexts.SWITCH_SUPPORTING, LaborCardTexts.NEXT_RUN)) {
            assertFalse(t.contains('ß'), t)
        }
    }
}
