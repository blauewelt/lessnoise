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
 * Which tab the system back gesture returns to (0.6.3 map tab fix; pure, TabHistoryTest): an
 * undo stack of the tabs opened. [stack] ends with the tab shown; before it, the tabs left, oldest
 * first.
 *
 * Every switch to another tab — a tap in the bottom bar or a tap that opens a tab (the status
 * line opening «Karte», the banner opening «Teilen») — pushes the tab left; choosing the tab shown
 * pushes nothing. Back pops and shows the tab before, whichever it is («Messen» is not special:
 * Karte → Messen, back → Karte). Only with nothing left to pop does back leave the app. At most
 * [MAX_BACK] tabs are kept to go back to; the oldest is dropped.
 */
data class TabHistory(val stack: List<Screen> = listOf(Screen.MEASURE)) {
    init {
        require(stack.isNotEmpty() && stack.size <= MAX_BACK + 1) { "bad tab history $stack" }
    }

    /** The tab shown. */
    val current: Screen get() = stack.last()

    /** Whether back stays in the app (false: nothing to go back to, back leaves the app). */
    val canGoBack: Boolean get() = stack.size > 1

    /** [s] was chosen (bottom bar, or a tap that opens a tab). */
    fun select(s: Screen): TabHistory = if (s == current) this else TabHistory((stack + s).takeLast(MAX_BACK + 1))

    /** The back gesture: the tab before; unchanged when there is none (the app is left). */
    fun back(): TabHistory = if (canGoBack) TabHistory(stack.dropLast(1)) else this

    /** For rememberSaveable: the tab names. */
    fun save(): List<String> = stack.map { it.name }

    companion object {
        /** How many tabs back can return to. */
        const val MAX_BACK = 30

        /** From [save]; anything unreadable gives the plain history (only «Messen»). */
        fun restore(names: List<String>): TabHistory =
            runCatching { TabHistory(names.map { Screen.valueOf(it) }) }.getOrDefault(TabHistory())
    }
}
