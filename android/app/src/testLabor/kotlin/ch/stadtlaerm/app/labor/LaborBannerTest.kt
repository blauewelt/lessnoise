package ch.stadtlaerm.app.labor

import ch.stadtlaerm.app.BuildConfig
import ch.stadtlaerm.app.edition.EditionUi
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
            "labor" -> assertEquals("LABOR-VERSION – kann Audio aufzeichnen", EditionUi.BANNER_TEXT)
            "laborKarte" -> assertEquals("LABOR-VERSION mit Karte – kann Audio aufzeichnen", EditionUi.BANNER_TEXT)
            else -> error("unexpected flavour ${BuildConfig.FLAVOR}")
        }
    }
}
