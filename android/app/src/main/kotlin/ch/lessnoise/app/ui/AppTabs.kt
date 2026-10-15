package ch.lessnoise.app.ui

/** The screens of the bottom bar. */
enum class Screen { MEASURE, MEASUREMENTS, MAP, SHARE, SETTINGS }

/**
 * The bottom tabs (pure, AppTabsTest): «Messen · Messungen · Einstellungen», plus in the editions
 * with the Lärmkarte «Karte» (0.6.3, the map page in the app, [MapTab]) and «Teilen» (0.6.1).
 * [uploadAvailable] = BuildConfig.UPLOAD_AVAILABLE, [mapAvailable] = MapTab.AVAILABLE (false in the
 * Offline and Labor builds, which do not even contain the map screen).
 */
fun appScreens(uploadAvailable: Boolean, mapAvailable: Boolean): List<Screen> = buildList {
    add(Screen.MEASURE)
    add(Screen.MEASUREMENTS)
    if (uploadAvailable && mapAvailable) add(Screen.MAP)
    if (uploadAvailable) add(Screen.SHARE)
    add(Screen.SETTINGS)
}

/** German tab label. */
fun screenLabel(s: Screen): String = when (s) {
    Screen.MEASURE -> "Messen"
    Screen.MEASUREMENTS -> "Messungen"
    Screen.MAP -> "Karte"
    Screen.SHARE -> "Teilen"
    Screen.SETTINGS -> "Einstellungen"
}

/**
 * Which tab the system back gesture returns to (0.6.3 map tab fix; pure, TabHistoryTest).
 *
 * «Messen» is the root: back on «Messen» leaves the app, as before. Every other tab goes back to
 * the tab visited before it. The rule against loops: each tab is in the history at most once —
 * choosing a tab that is already in it moves it to the top (Messen → Karte → Teilen → Karte, back
 * → Teilen, back → Messen); choosing «Messen» empties the history. So it holds at most one entry
 * per tab, and back reaches «Messen» in at most four steps.
 */
data class TabHistory(val stack: List<Screen> = listOf(Screen.MEASURE)) {
    init {
        require(stack.firstOrNull() == Screen.MEASURE && stack.toSet().size == stack.size) { "bad tab history $stack" }
    }

    /** The tab shown. */
    val current: Screen get() = stack.last()

    /** Whether back stays in the app (false on «Messen»: back leaves the app). */
    val canGoBack: Boolean get() = stack.size > 1

    /** [s] was chosen (bottom bar, or a tap that opens a tab). */
    fun select(s: Screen): TabHistory = when (s) {
        current -> this
        Screen.MEASURE -> TabHistory()
        else -> TabHistory(stack.filter { it != s } + s)
    }

    /** The back gesture: the tab before; on «Messen» unchanged (the app is left). */
    fun back(): TabHistory = if (canGoBack) TabHistory(stack.dropLast(1)) else this

    /** For rememberSaveable: the tab names. */
    fun save(): List<String> = stack.map { it.name }

    companion object {
        /** From [save]; anything unreadable gives the plain history (only «Messen»). */
        fun restore(names: List<String>): TabHistory =
            runCatching { TabHistory(names.map { Screen.valueOf(it) }) }.getOrDefault(TabHistory())
    }
}
