package ch.lessnoise.app.ui

import ch.lessnoise.app.BuildConfig
import ch.lessnoise.dsp.geo.LocationNoise
import ch.lessnoise.dsp.geo.Lv95
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** 0.6.3: the tab «Karte» — its address, what stays in the app, what it may load, where it exists. */
class MapLinksTest {

    @Test
    fun theUrlIsThePlainMapPageWithoutAPoint() {
        assertEquals("https://wenigerlaerm.ch/karte.html", Links.MAP)
        // Without a point: the map page at its first control (an anchor, not a view; no place in it).
        assertEquals("https://wenigerlaerm.ch/karte.html#metric", MapLinks.url(null))
        assertTrue(MapLinks.staysInApp(MapLinks.url(null)))
        val html = mapPage()
        assertTrue(html.contains("""id="metric""""), "karte.html has the anchor")
        assertFalse(Links.MAP in Links.all, "not a «Mehr dazu» link")
    }

    @Test
    fun withAPointOnlyTheFragmentIsAddedRoundedToFiveDecimals() {
        val p = LocationNoise.Point(2683351, 1247988, 50)
        val url = MapLinks.url(p)
        val m = Regex("""^https://wenigerlaerm\.ch/karte\.html#lat=(\d+\.\d{5})&lon=(\d+\.\d{5})&z=16$""").matchEntire(url) ?: fail(url)
        val ll = Lv95.lv95ToWgs84(p.e.toDouble(), p.n.toDouble())
        assertTrue(abs(m.groupValues[1].toDouble() - ll.lat) <= 0.000005, url)
        assertTrue(abs(m.groupValues[2].toDouble() - ll.lon) <= 0.000005, url)
        // Back to LV95 the fragment lands within about a metre of the shifted point that was sent:
        // it is that point, not a true location (the app does not keep one).
        val back = Lv95.wgs84ToLv95(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        assertTrue(abs(back.e - p.e) < 2 && abs(back.n - p.n) < 2, "$back vs $p")
        // No query string: nothing of it reaches the server (browsers never send the fragment).
        assertFalse(url.contains('?'))
        // The decimal point is a point in every locale.
        assertEquals(1, url.substringAfter("lat=").substringBefore("&").count { it == '.' })
    }

    @Test
    fun theUrlBuilderTakesOnlyAShiftedPoint() {
        // The builder's only input type is LocationNoise.Point, what LocationNoise.shift returns and
        // what the measurement row stores; there is no overload with latitude/longitude.
        val methods = MapLinks::class.java.declaredMethods.filter { it.name == "url" }
        assertEquals(1, methods.size)
        assertEquals(listOf(LocationNoise.Point::class.java), methods[0].parameterTypes.toList())
    }

    @Test
    fun onlyTheMapPageStaysInTheApp() {
        for (u in listOf(
            "https://wenigerlaerm.ch/karte.html",
            "https://wenigerlaerm.ch/karte.html#lat=47.37690&lon=8.54170&z=16",
            "https://wenigerlaerm.ch/karte.html?x=1",
        )) assertTrue(MapLinks.staysInApp(u), u)
        for (u in listOf(
            "https://wenigerlaerm.ch/datenschutz.html",
            "https://wenigerlaerm.ch/",
            "https://github.com/blauewelt/lessnoise",
            "https://www.swisstopo.admin.ch/",
            "http://wenigerlaerm.ch/karte.html",
            "https://wenigerlaerm.ch/karte.html.evil.example/",
            "https://wenigerlaerm.ch.evil.example/karte.html",
            "https://evil.example/https://wenigerlaerm.ch/karte.html",
            "https://wenigerlaerm.ch:8443/karte.html",
            "https://user@wenigerlaerm.ch/karte.html",
            "https://wenigerlaerm.ch/karte.htmlx",
            "javascript:alert(1)",
            "file:///sdcard/karte.html",
            "intent://wenigerlaerm.ch/karte.html",
        )) assertFalse(MapLinks.staysInApp(u), u)
    }

    @Test
    fun theWebViewMayContactOnlyThePageTheTilesAndTheApi() {
        assertEquals(setOf("wenigerlaerm.ch", "wmts.geo.admin.ch", "api.lessnoise.ch", "api.stadtlaerm.ch"), MapLinks.ALLOWED_HOSTS)
        for (u in listOf(
            "https://wenigerlaerm.ch/map/karte.js?v=1",
            "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-grau/default/current/3857/16/34318/22944.jpeg",
            "https://api.lessnoise.ch/v1/map/cells.json",
            "https://api.stadtlaerm.ch/v1/map/cells.json",
        )) assertTrue(MapLinks.allowedHost(u), u)
        for (u in listOf(
            "http://wmts.geo.admin.ch/x.jpeg",
            "https://fonts.googleapis.com/css",
            "https://wmts.geo.admin.ch.evil.example/x",
            "https://evil.example/?https://api.lessnoise.ch/",
            "https://user@api.lessnoise.ch/v1/map/cells.json",
            "https://api.lessnoise.ch:444/v1/map/cells.json",
            "wss://api.lessnoise.ch/",
        )) assertFalse(MapLinks.allowedHost(u), u)
    }

    private fun mapPage(): String {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.take(5)
            .firstOrNull { File(it, "docs/karte.html").isFile } ?: fail("repository root not found")
        return File(root, "docs/karte.html").readText()
    }

    @Test
    fun theAllowedHostsAreExactlyThoseOfTheMapPagesCsp() {
        val html = mapPage()
        val csp = Regex("""Content-Security-Policy" content="([^"]+)"""").find(html)?.groupValues?.get(1) ?: fail("no CSP")
        val hosts = Regex("""https://([a-z0-9.-]+)""").findAll(csp).map { it.groupValues[1] }.toSet() + "wenigerlaerm.ch"
        assertEquals(hosts, MapLinks.ALLOWED_HOSTS)
    }

    @Test
    fun theTabExistsOnlyInTheEditionsWithTheMap() {
        val screens = appScreens(BuildConfig.UPLOAD_AVAILABLE, MapTab.AVAILABLE)
        assertEquals(BuildConfig.UPLOAD_AVAILABLE, Screen.MAP in screens, "${BuildConfig.FLAVOR}: $screens")
        assertEquals(BuildConfig.FLAVOR in setOf("public", "laborKarte"), MapTab.AVAILABLE, BuildConfig.FLAVOR)
        assertEquals(
            listOf(Screen.MEASURE, Screen.MEASUREMENTS, Screen.MAP, Screen.SHARE, Screen.SETTINGS),
            appScreens(uploadAvailable = true, mapAvailable = true),
        )
        assertEquals(listOf(Screen.MEASURE, Screen.MEASUREMENTS, Screen.SETTINGS), appScreens(uploadAvailable = false, mapAvailable = false))
        assertEquals(listOf("Messen", "Messungen", "Karte", "Teilen", "Einstellungen"), Screen.entries.map(::screenLabel))
    }

    @Test
    fun theMapScreenIsCompiledOnlyIntoTheMapEditions() {
        val app = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.take(4)
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
            .firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/karte").isDirectory } ?: fail("app module not found")
        val gradle = File(app, "build.gradle.kts").readText()
        // src/karte only in public and laborKarte; src/nomap only in offline and labor.
        val blocks = Regex("""named\("(\w+)"\) \{([^}]*)}""").findAll(gradle).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(setOf("public", "laborKarte"), blocks.filterValues { "src/karte/kotlin" in it }.keys)
        assertEquals(setOf("offline", "labor"), blocks.filterValues { "src/nomap/kotlin" in it }.keys)
        assertTrue(File(app, "src/karte/kotlin/ch/lessnoise/app/ui/MapScreen.kt").isFile)
        assertFalse(File(app, "src/main/kotlin/ch/lessnoise/app/ui/MapScreen.kt").exists())
    }

    /** 0.8.2: the page's own dark style when the app is dark; never an algorithmic darkening by the WebView. */
    @Test
    fun theMapFollowsTheAppsDarkThemeWithThePagesOwnStyle() {
        val app = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.take(4)
            .flatMap { sequenceOf(it, File(it, "app"), File(it, "android/app")) }
            .firstOrNull { File(it, "build.gradle.kts").isFile && File(it, "src/karte").isDirectory } ?: fail("app module not found")
        val map = File(app, "src/karte/kotlin/ch/lessnoise/app/ui/MapScreen.kt").readText()
        // The same signal as LessNoiseTheme, passed into the screen and used where the WebView is made.
        assertTrue(map.contains("MapScreen(modifier, visible, onOpen, isDark = isSystemInDarkTheme())"))
        assertTrue(map.contains("WebView(MapDark.context(ctx, isDark))"))
        assertTrue(map.contains("ContextThemeWrapper(base, android.R.style.Theme_Material_NoActionBar)"), "a theme with isLightTheme = false")
        assertTrue(map.contains("MapDark.settings(web.settings, isDark)"))
        assertTrue(map.contains("WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)"))
        assertTrue(map.contains("WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY"))
        for (f in listOf("setAlgorithmicDarkeningAllowed(settings, true)", "DARK_STRATEGY_USER_AGENT_DARKENING_ONLY", "DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING")) {
            assertFalse(map.contains(f), f)
        }
        // The light theme of the Activity is what made the page light; it stays (Compose draws the app).
        val themes = File(app, "src/main/res/values/themes.xml").readText()
        assertTrue(themes.contains("android:Theme.Material.Light.NoActionBar"))
        // The page has its own dark style (no website change for this).
        val docs = File(app, "../../docs")
        assertTrue(File(docs, "karte.html").readText().contains("""<meta name="color-scheme" content="light dark">"""))
        assertTrue(File(docs, "map/karte.css").readText().contains("@media (prefers-color-scheme: dark)"))
    }
}
