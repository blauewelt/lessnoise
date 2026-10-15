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
