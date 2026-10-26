package ch.lessnoise.app

import ch.lessnoise.app.edition.EditionDetector
import ch.lessnoise.dsp.EventRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.9.5: the detector rules V3 are for the Labor editions only; the published editions (public,
 * offline) keep detector v2 exactly as up to 0.9.4. Runs in every flavour's unit tests.
 */
class DetectorRulesEditionTest {
    @Test
    fun publishedEditionsKeepDetectorV2() {
        if (BuildConfig.FLAVOR == "public" || BuildConfig.FLAVOR == "offline") {
            // The public hook ignores its context.
            assertEquals(EventRules.V2, EditionDetector.rules(object : android.content.ContextWrapper(null) {}))
        }
        val app = appDir()
        val public = File(app, "src/public/kotlin/ch/lessnoise/app/edition/Edition.kt").readText()
        assertTrue(public.contains("fun rules(@Suppress(\"UNUSED_PARAMETER\") context: Context): ch.lessnoise.dsp.EventRules = ch.lessnoise.dsp.EventRules.V2"))
        // The service takes the rules from the edition hook, and the engine default is V2.
        val service = File(app, "src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains("eventRules = EditionDetector.rules(this)"))
        assertEquals(EventRules.V2, ch.lessnoise.dsp.EngineConfig().eventRules)
    }

    private fun appDir(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { it.parentFile }.take(4)
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
            .firstOrNull { File(it, "src/main/kotlin/ch/lessnoise/app/service").isDirectory } ?: error("app module not found from $start")
    }
}
