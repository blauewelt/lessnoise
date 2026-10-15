package ch.lessnoise.app.ui

import ch.lessnoise.dsp.geo.LocationNoise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.3 map tab fix: when the kept WebView is loaded, and when it is only re-centred (MapCentring). */
class MapCentringTest {
    private val p1 = LocationNoise.Point(2683351, 1247988, 50)
    private val p2 = LocationNoise.Point(2600000, 1200000, 50)
    private val min = 60_000L

    private fun load(point: LocationNoise.Point?) = MapCentring.Step.Load(MapLinks.url(point))
    private fun recentre(point: LocationNoise.Point) = MapCentring.Step.Recentre(MapLinks.recentreScript(MapLinks.viewFragment(point)!!))

    @Test
    fun theFieldCaseOpenedWithoutAPointThenThePointArrives() {
        val c = MapCentring()
        assertEquals(load(null), c.show(null, 0))
        c.pageStarted()
        assertNull(c.pageFinished())
        // Other tab and back, still no point: nothing happens (no reload per switch).
        assertFalse(c.hidden())
        assertNull(c.show(null, 1 * min))
        // The point exists now (shown again, or it arrived while shown): only the fragment changes.
        assertEquals(recentre(p1), c.show(p1, 2 * min))
        assertEquals("location.replace('${MapLinks.viewFragment(p1)}')", recentre(p1).script)
        // The point changes while shown: replaced again.
        assertEquals(recentre(p2), c.show(p2, 3 * min))
    }

    @Test
    fun alreadyCentredMeansNothingToDo() {
        val c = MapCentring()
        assertEquals(load(p1), c.show(p1, 0))
        assertNull(c.pageFinished())
        assertNull(c.show(p1, 1 * min))
        assertFalse(c.hidden())
        assertNull(c.show(p1, 5 * min))
        // Losing the point (e.g. the newest shared measurement was deleted) leaves the page as it is.
        assertNull(c.show(null, 6 * min))
    }

    @Test
    fun aChangedPointIsReplacedOnceAndOnlyOnce() {
        val c = MapCentring()
        c.show(p1, 0)
        c.pageFinished()
        assertEquals(recentre(p2), c.show(p2, 1 * min))
        assertNull(c.show(p2, 2 * min))
        assertEquals(recentre(p1), c.show(p1, 3 * min))
        // Some WebViews report a fragment navigation as a finished page: nothing more happens.
        assertNull(c.pageFinished())
    }

    @Test
    fun whileThePageLoadsTheRecentringWaitsForPageFinished() {
        val c = MapCentring()
        assertEquals(load(null), c.show(null, 0))
        c.pageStarted()
        assertNull(c.show(p1, 1_000))
        assertNull(c.show(p2, 2_000))
        assertEquals(recentre(p2), c.pageFinished())
        assertNull(c.pageFinished())
        // Loaded at p1, p1 again before it finished: nothing waits.
        val d = MapCentring()
        d.show(p1, 0)
        assertNull(d.show(p1, 1_000))
        assertNull(d.pageFinished())
    }

    @Test
    fun hiddenWhileLoadingStopsAndShowingLoadsAgain() {
        val c = MapCentring()
        c.show(p1, 0)
        c.pageStarted()
        assertTrue(c.hidden(), "a page still loading is stopped")
        assertNull(c.pageFinished(), "the stopped load's late callback changes nothing")
        assertEquals(load(p2), c.show(p2, 1_000))
        c.pageFinished()
        assertFalse(c.hidden())
    }

    @Test
    fun anErrorOrAnOldPageIsLoadedAgain() {
        val c = MapCentring()
        c.show(p1, 0)
        c.failed()
        assertEquals(load(p1), c.show(p1, 1_000))
        c.pageFinished()
        assertNull(c.show(p1, 1_000 + MapCentring.STALE_MS - 1))
        assertEquals(load(p1), c.show(p1, 1_000 + MapCentring.STALE_MS), "the map files are published every 10 minutes")
        assertEquals(10 * min, MapCentring.STALE_MS)
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
}
