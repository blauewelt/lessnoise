package ch.lessnoise.app.edition

import android.content.Context
import androidx.compose.runtime.Composable
import ch.lessnoise.app.audio.AudioTap
import ch.lessnoise.app.audio.NoAudioTap
import ch.lessnoise.app.data.EventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// The PUBLIC editions: compiled into the «Online-Version» (flavour `public`) AND the «Offline-Version»
// (flavour `offline`, which adds this directory as a source dir in app/build.gradle.kts). This
// source set (app/src/public/kotlin) and app/src/main/ contain no code that writes audio; the Labor
// edition's recorder exists only in app/src/labor/. See PRIVACY.md.

/** Local diagnostics of the Labor builds (0.6.7: the motion detector's changes). Public: none, nothing is written. */
object EditionDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun motionTransition(context: Context, atMs: Long, to: String, spanStartMs: Long, features: Map<String, Double>) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun motionMinute(
        context: Context, minuteStartMs: Long, medianMs2: Double?, maxMs2: Double?, seconds: Int,
        distanceM: Int?, state: String, motion: Boolean, position: Boolean,
    ) = Unit
}

/**
 * The event head (multi-label classifier on event features, logging only). Public: none — the
 * engine runs without it, nothing of it is computed, stored or sent.
 */
object EditionEventHead {
    fun create(): ch.lessnoise.dsp.classify.EventHeadClassifier? = null
}

/** Public: there is no audio recorder, the measurement service always gets [NoAudioTap]. */
object AudioTapProvider {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): AudioTap = NoAudioTap
}

/** Event clips for the chart. Public: there are none (no clip references, no player). */
object EditionClips {
    val version: Flow<Long> = flowOf(0L)

    fun clipRefs(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") events: List<EventEntity>): Map<Long, String> =
        emptyMap()

    val player: ((clipRef: String, playlist: List<String>) -> Unit)? = null
}

/** Edition-specific UI. Public: nothing. */
object EditionUi {
    /**
     * First line of Einstellungen › «Datenschutz»: what this edition does with audio. (The details —
     * at most about 1 s is buffered in working memory for the detection, nothing is stored, logged
     * or sent — are in PRIVACY.md and on wenigerlaerm.ch/datenschutz.html.)
     */
    const val ABOUT_AUDIO = "Es wird nie Audio gespeichert oder gesendet."

    /** The microphone line on «Messen» before the first start: the moment of the microphone consent. */
    const val MIC_LINE = "Die App braucht das Mikrofon. Es wird nie Audio gespeichert oder gesendet."

    /** Hosted once at the app root (Labor: the clip player). */
    @Composable
    fun Overlay() {}

    /** Shown above every screen (Labor: the red warning banner). */
    @Composable
    fun Banner() {}

    /** Extra section at the end of Einstellungen (Labor: recording settings). */
    @Composable
    fun SettingsSection() {}

    /** Why «Messen» must not start (Labor: audio budget used up), or null. Public: never refused. */
    fun startRefusal(@Suppress("UNUSED_PARAMETER") context: Context): String? = null

    /** A red storage line on «Messen» (Labor: «Speicher fast voll …»). Public: nothing. */
    @Composable
    fun StorageNotice() {}

    /** Marker after an event in «Letzte Lärmspitzen» (Labor: a red dot if a clip exists, tap plays it). */
    @Composable
    fun EventMarker(@Suppress("UNUSED_PARAMETER") eventId: Long) {}
}
