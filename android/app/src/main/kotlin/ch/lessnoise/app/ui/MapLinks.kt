package ch.lessnoise.app.ui

import ch.lessnoise.dsp.geo.LocationNoise
import ch.lessnoise.dsp.geo.Lv95
import java.util.Locale

/**
 * The tab «Karte» (0.6.3, Karten editions only): which address it opens and what it may load. Pure
 * strings, so that MapLinksTest checks them on the JVM; the WebView that uses them lives in
 * src/karte/kotlin/.../ui/MapScreen.kt, which is compiled only into the editions with the map.
 *
 * - [url]: [Links.MAP], plus — only when the phone has a shared measurement — the fragment
 *   `#lat=…&lon=…&z=16` with the **shifted** point of the newest shared measurement (the same point
 *   that was sent; the app never keeps the true one), WGS84 rounded to 5 decimals (≈ 1 m, the point
 *   is whole LV95 metres anyway). Browsers never send the part after `#` to a server; the page's
 *   script reads it and centres the map there (docs/README.md, server/DESIGN.md §7).
 * - [staysInApp]: only the map page itself is shown in the tab; every other link (privacy page,
 *   GitHub, swisstopo …) opens in the browser.
 * - [allowedHost]: what the WebView may load at all — the map page's host and the hosts its
 *   Content-Security-Policy allows (swisstopo tiles, the project's API under both names). Anything
 *   else is refused in the WebView, whatever a page would try.
 */
object MapLinks {
    /** Zoom of the opened map: the 100 m field and its neighbours on a phone screen. */
    const val ZOOM = 16

    private const val HOST = "wenigerlaerm.ch"

    /** Hosts the WebView may contact: the page, swisstopo's tiles, the API (docs/karte.html CSP). */
    val ALLOWED_HOSTS: Set<String> = setOf(HOST, "wmts.geo.admin.ch", "api.lessnoise.ch", "api.stadtlaerm.ch")

    private val MAP_PAGE = Regex("""^${Regex.escape(Links.MAP)}([?#].*)?$""")

    /**
     * Without a point: the plain map page at its own anchor `#metric` (the map's first control; the
     * browser scrolls there, past the page head that fills a phone's first screen). The page's
     * default view is unchanged; the anchor is no view fragment.
     */
    const val NO_POINT_ANCHOR = "#metric"

    /** The address to open: the map page, centred on [point] if there is one (a shifted point, never a true location). */
    fun url(point: LocationNoise.Point?): String {
        if (point == null) return Links.MAP + NO_POINT_ANCHOR
        val ll = Lv95.lv95ToWgs84(point.e.toDouble(), point.n.toDouble())
        return Links.MAP + "#lat=" + deg(ll.lat) + "&lon=" + deg(ll.lon) + "&z=" + ZOOM
    }

    private fun deg(v: Double): String = String.format(Locale.ROOT, "%.5f", v)

    /** Whether [url] is the map page (with any query or fragment): shown in the tab. Anything else goes to the browser. */
    fun staysInApp(url: String): Boolean = MAP_PAGE.matches(url)

    /** Whether the WebView may load [url] at all: https only, and only [ALLOWED_HOSTS] (no port, no user info). */
    fun allowedHost(url: String): Boolean {
        val m = Regex("""^https://([^/?#]*)([/?#].*)?$""").matchEntire(url) ?: return false
        val authority = m.groupValues[1]
        return authority in ALLOWED_HOSTS
    }
}
