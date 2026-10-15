package ch.lessnoise.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.6.3 map tab fix: back is an undo stack of the tabs opened (TabHistory). */
class TabHistoryTest {
    /** Starts on [start] (the app opens on «Messen»; here any tab) and opens [s] in turn. */
    private fun from(start: Screen, vararg s: Screen): TabHistory = s.fold(TabHistory(listOf(start))) { h, x -> h.select(x) }
    private fun visit(vararg s: Screen): TabHistory = from(Screen.MEASURE, *s)

    /** The tabs shown by pressing back until the app would be left, starting with the current one. */
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
    fun theOwnersExampleMessenIsNotSpecial() {
        // On «Karte», switch to «Messen», back → «Karte».
        val h = visit(Screen.MAP, Screen.MEASURE)
        assertEquals(Screen.MEASURE, h.current)
        assertTrue(h.canGoBack, "back on «Messen» does not leave the app while there is a tab to go back to")
        assertEquals(Screen.MAP, h.back().current)
        // … back → «Messen» (where the app started), back → leaves the app.
        assertEquals(listOf(Screen.MEASURE, Screen.MAP, Screen.MEASURE), backTrail(h))
    }

    @Test
    fun aBaBThenFourTimesBackLeavesTheApp() {
        val a = Screen.MAP
        val b = Screen.SHARE
        var h = from(a, b, a, b)
        assertEquals(listOf(a, b, a, b), h.stack)
        val shown = mutableListOf<Screen>()
        var exited = false
        repeat(4) {
            if (h.canGoBack) {
                h = h.back()
                shown += h.current
            } else {
                exited = true
            }
        }
        assertEquals(listOf(a, b, a), shown)
        assertTrue(exited, "the fourth back leaves the app")
        assertFalse(h.canGoBack)
        assertEquals(h, h.back())
    }

    @Test
    fun everySwitchPushesReselectingDoesNot() {
        assertEquals(listOf(Screen.MEASURE, Screen.MAP, Screen.SHARE, Screen.MAP), visit(Screen.MAP, Screen.SHARE, Screen.MAP).stack)
        assertEquals(visit(Screen.MAP), visit(Screen.MAP, Screen.MAP, Screen.MAP))
        assertEquals(TabHistory(), visit(Screen.MEASURE))
        assertFalse(TabHistory().canGoBack)
        // Programmatic switches go through the same select (AppRoot `open`): the status line on
        // «Messen» opening «Karte», then the banner opening «Teilen».
        assertEquals(listOf(Screen.SHARE, Screen.MAP, Screen.MEASURE), backTrail(visit(Screen.MAP, Screen.SHARE)))
    }

    @Test
    fun theStackIsCappedDroppingTheOldest() {
        assertEquals(30, TabHistory.MAX_BACK)
        val tabs = listOf(Screen.MAP, Screen.SHARE, Screen.SETTINGS, Screen.MEASUREMENTS, Screen.MEASURE)
        var h = TabHistory()
        val opened = mutableListOf(Screen.MEASURE)
        repeat(100) { i ->
            h = h.select(tabs[i % tabs.size])
            opened += tabs[i % tabs.size]
            assertTrue(h.stack.size <= TabHistory.MAX_BACK + 1)
        }
        assertEquals(TabHistory.MAX_BACK + 1, h.stack.size)
        // Back walks the newest 30 switches in reverse, then leaves the app.
        val trail = backTrail(h)
        assertEquals(opened.takeLast(TabHistory.MAX_BACK + 1).reversed(), trail)
    }

    @Test
    fun savedAndRestored() {
        val h = visit(Screen.MEASUREMENTS, Screen.MAP, Screen.MEASURE, Screen.MAP)
        assertEquals(h, TabHistory.restore(h.save()))
        // Anything unreadable gives the plain history.
        assertEquals(TabHistory(), TabHistory.restore(listOf("MEASURE", "NOPE")))
        assertEquals(TabHistory(), TabHistory.restore(emptyList()))
        assertEquals(TabHistory(), TabHistory.restore(List(40) { "MAP" }))
    }

    @Test
    fun theMapTabNeverHandsBackToThePageHistory() {
        // Source check (src/karte/ exists in every checkout, also when this runs for another flavour).
        val map = File(appDir(), "src/karte/kotlin/ch/lessnoise/app/ui/MapScreen.kt").readText()
        val code = map.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        assertFalse(code.contains("goBack"), "MapScreen.kt uses WebView history")
        assertFalse(code.contains("BackHandler"), "MapScreen.kt takes the back gesture")
        // The app's one tab-level back handler is the tab history's, and every switch goes through select.
        val root = File(appDir(), "src/main/kotlin/ch/lessnoise/app/ui/MainActivity.kt").readText()
        assertTrue(root.contains("BackHandler(enabled = history.canGoBack) { history = history.back() }"))
        assertTrue(root.contains("history = history.select(s)"))
    }

    private fun appDir(): File = listOf(File("."), File("app")).first { File(it, "src/karte").isDirectory }
}
