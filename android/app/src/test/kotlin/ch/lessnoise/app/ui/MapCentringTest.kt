package ch.lessnoise.app.ui

import ch.lessnoise.dsp.geo.LocationNoise
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.3 map tab fix: when the kept WebView is loaded, re-centred, or asked to check (MapCentring). */
class MapCentringTest {
    private val p1 = LocationNoise.Point(2683351, 1247988, 50)
    private val p2 = LocationNoise.Point(2600000, 1200000, 50)
    private val check = MapCentring.Step.Check(MapLinks.CHECK_SCRIPT)

    private fun load(point: LocationNoise.Point?) = MapCentring.Step.Load(MapLinks.url(point))
    private fun recentre(point: LocationNoise.Point) = MapCentring.Step.Recentre(MapLinks.recentreScript(MapLinks.viewFragment(point)!!))

    @Test
    fun theFieldCaseOpenedWithoutAPointThenThePointArrives() {
        val c = MapCentring()
        assertEquals(load(null), c.show(null))
        c.pageStarted()
        assertNull(c.pageFinished())
        // Other tab and back, still no point: nothing happens (no reload per switch).
        assertFalse(c.hidden())
        assertNull(c.show(null))
        // The point exists now (shown again, or it arrived while shown): only the fragment changes.
        assertEquals(recentre(p1), c.show(p1))
        assertEquals("location.replace('${MapLinks.viewFragment(p1)}')", recentre(p1).script)
        // The point changes while shown: replaced again.
        assertEquals(recentre(p2), c.show(p2))
    }

    @Test
    fun alreadyCentredMeansNothingToDoAndAKeptPageIsNeverReloaded() {
        val c = MapCentring()
        assertEquals(load(p1), c.show(p1))
        assertNull(c.pageFinished())
        repeat(100) {
            assertFalse(c.hidden())
            assertNull(c.show(p1), "no reload, however often and however late it is shown")
        }
        // Losing the point (e.g. the newest shared measurement was deleted) leaves the page as it is.
        assertNull(c.show(null))
    }

    @Test
    fun aChangedPointIsReplacedOnceAndOnlyOnce() {
        val c = MapCentring()
        c.show(p1)
        c.pageFinished()
        assertEquals(recentre(p2), c.show(p2))
        assertNull(c.show(p2))
        assertEquals(recentre(p1), c.show(p1))
        // Some WebViews report a fragment navigation as a finished page: nothing more happens.
        assertNull(c.pageFinished())
    }

    @Test
    fun whileThePageLoadsTheRecentringWaitsForPageFinished() {
        val c = MapCentring()
        assertEquals(load(null), c.show(null))
        c.pageStarted()
        assertNull(c.show(p1))
        assertNull(c.show(p2))
        assertEquals(recentre(p2), c.pageFinished())
        assertNull(c.pageFinished())
        // Loaded at p1, p1 again before it finished: nothing waits.
        val d = MapCentring()
        d.show(p1)
        assertNull(d.show(p1))
        assertNull(d.pageFinished())
    }

    @Test
    fun shownAgainALoadedPageIsAskedToCheckForNewData() {
        val c = MapCentring()
        assertNull(c.check(), "nothing loaded")
        c.show(p1)
        c.pageStarted()
        assertNull(c.check(), "a page still loading checks by itself when it has loaded")
        c.pageFinished()
        assertEquals(check, c.check())
        assertEquals("window.KarteCheck && window.KarteCheck()", MapLinks.CHECK_SCRIPT)
        c.failed()
        assertNull(c.check())
        // The page defines the function the app calls.
        val js = File(siteDir(), "docs/map/karte.js").readText()
        assertTrue(js.contains("window.KarteCheck = checkForUpdate;"))
    }

    @Test
    fun hiddenWhileLoadingStopsAndShowingLoadsAgain() {
        val c = MapCentring()
        c.show(p1)
        c.pageStarted()
        assertTrue(c.hidden(), "a page still loading is stopped")
        assertNull(c.pageFinished(), "the stopped load's late callback changes nothing")
        assertEquals(load(p2), c.show(p2))
        c.pageFinished()
        assertFalse(c.hidden())
    }

    @Test
    fun anErrorIsLoadedAgain() {
        val c = MapCentring()
        c.show(p1)
        c.failed()
        assertEquals(load(p1), c.show(p1))
        c.pageFinished()
        assertNull(c.show(p1))
    }

    @Test
    fun theScriptIsOnlyEverAFragmentReplacement() {
        for (p in listOf(p1, p2, LocationNoise.Point(2485000, 1075000, 100), LocationNoise.Point(2834000, 1296000, 20))) {
            val s = MapLinks.recentreScript(MapLinks.viewFragment(p)!!)
            assertTrue(Regex("""^location\.replace\('#lat=\d{2}\.\d{5}&lon=\d{1,2}\.\d{5}&z=16'\)$""").matches(s), s)
        }
        for (bad in listOf("#metric", "#lat=1&lon=2&z=16", "#lat=47.00000&lon=8.00000&z=16');alert(1);('", "javascript:alert(1)", "")) {
            assertFailsWith<IllegalArgumentException>(bad) { MapLinks.recentreScript(bad) }
        }
        assertNull(MapLinks.viewFragment(null))
        assertEquals(MapLinks.url(p1), Links.MAP + MapLinks.viewFragment(p1))
    }

    private fun siteDir(): File = listOf(File(".."), File("../.."), File(".")).first { File(it, "docs/map/karte.js").isFile }
}
