package ch.lessnoise.app.labor

import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.edition.EditionUi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The red banner says which Labor build this is. Runs in testLaborDebugUnitTest and
 * testLaborKarteDebugUnitTest (src/testLabor/ is a test source of both flavours).
 */
class LaborBannerTest {
    @Test
    fun bannerNamesTheBuild() {
        when (BuildConfig.FLAVOR) {
            "labor" -> assertEquals("WENIGER LÄRM LABOR – kann Audio aufzeichnen", EditionUi.BANNER_TEXT)
            "laborKarte" -> assertEquals("WENIGER LÄRM LABOR · KARTE – kann Audio aufzeichnen", EditionUi.BANNER_TEXT)
            else -> error("unexpected flavour ${BuildConfig.FLAVOR}")
        }
    }
}
