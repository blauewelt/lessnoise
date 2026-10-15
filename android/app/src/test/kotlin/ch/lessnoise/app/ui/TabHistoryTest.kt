package ch.lessnoise.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.6.3 map tab fix: the system back gesture returns to the tab visited before (TabHistory). */
class TabHistoryTest {
    private fun visit(vararg s: Screen): TabHistory = s.fold(TabHistory()) { h, x -> h.select(x) }

    /** The tabs back visits from [h] until it would leave the app, starting with the current one. */
    private fun backTrail(h: TabHistory): List<Screen> {
        val out = mutableListOf(h.current)
        var x = h
        while (x.canGoBack) {
            x = x.back()
            out += x.current
        }
        return out
    }

    @Test
    fun backGoesToThePreviousTabAndOnMessenLeavesTheApp() {
        var h = visit(Screen.MAP, Screen.SHARE)
        assertEquals(Screen.SHARE, h.current)
        h = h.back()
        assertEquals(Screen.MAP, h.current)
        h = h.back()
        assertEquals(Screen.MEASURE, h.current)
        // On «Messen» with an empty history the back gesture is not handled: the app is left.
        assertFalse(h.canGoBack)
        assertEquals(h, h.back())
        assertFalse(TabHistory().canGoBack)
    }

    @Test
    fun revisitingATabMovesItToTheTopSoThereAreNoLoops() {
        // Messen → Karte → Teilen → Karte: back → Teilen → Messen (Karte is not visited twice).
        assertEquals(listOf(Screen.MAP, Screen.SHARE, Screen.MEASURE), backTrail(visit(Screen.MAP, Screen.SHARE, Screen.MAP)))
        // Ping-pong between two tabs does not grow the history.
        val pingPong = visit(Screen.MAP, Screen.SHARE, Screen.MAP, Screen.SHARE, Screen.MAP, Screen.SHARE)
        assertEquals(listOf(Screen.MEASURE, Screen.MAP, Screen.SHARE), pingPong.stack)
        // Choosing the tab shown changes nothing.
        assertEquals(visit(Screen.MAP), visit(Screen.MAP, Screen.MAP))
    }

    @Test
    fun choosingMessenEmptiesTheHistory() {
        val h = visit(Screen.MAP, Screen.SHARE, Screen.MEASURE)
        assertEquals(TabHistory(), h)
        assertFalse(h.canGoBack)
        // … and starts again from there.
        assertEquals(listOf(Screen.SETTINGS, Screen.MEASURE), backTrail(h.select(Screen.SETTINGS)))
    }

    @Test
    fun everySequenceStaysBoundedAndBackAlwaysReachesMessen() {
        val all = Screen.entries
        val rnd = java.util.Random(7)
        repeat(2000) {
            var h = TabHistory()
            repeat(rnd.nextInt(30)) {
                h = if (rnd.nextInt(4) == 0) h.back() else h.select(all[rnd.nextInt(all.size)])
                assertEquals(Screen.MEASURE, h.stack.first())
                assertEquals(h.stack.toSet().size, h.stack.size, "a tab twice: ${h.stack}")
                assertTrue(h.stack.size <= all.size)
            }
            val trail = backTrail(h)
            assertEquals(Screen.MEASURE, trail.last())
            assertTrue(trail.size <= all.size)
        }
    }

    @Test
    fun savedAndRestored() {
        val h = visit(Screen.MEASUREMENTS, Screen.MAP, Screen.SHARE)
        assertEquals(h, TabHistory.restore(h.save()))
        // Anything unreadable gives the plain history.
        assertEquals(TabHistory(), TabHistory.restore(listOf("MAP")))
        assertEquals(TabHistory(), TabHistory.restore(listOf("MEASURE", "NOPE")))
        assertEquals(TabHistory(), TabHistory.restore(emptyList()))
    }

    @Test
    fun theMapTabNeverHandsBackToThePageHistory() {
        // Source check (src/karte/ exists in every checkout, also when this runs for another flavour).
        val map = File(appDir(), "src/karte/kotlin/ch/lessnoise/app/ui/MapScreen.kt").readText()
        val code = map.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        assertFalse(code.contains("goBack"), "MapScreen.kt uses WebView history")
        assertFalse(code.contains("BackHandler"), "MapScreen.kt takes the back gesture")
        // The app's one back handler is the tab history's.
        val root = File(appDir(), "src/main/kotlin/ch/lessnoise/app/ui/MainActivity.kt").readText()
        assertTrue(root.contains("BackHandler(enabled = history.canGoBack) { history = history.back() }"))
    }

    private fun appDir(): File = listOf(File("."), File("app")).first { File(it, "src/karte").isDirectory }
}
