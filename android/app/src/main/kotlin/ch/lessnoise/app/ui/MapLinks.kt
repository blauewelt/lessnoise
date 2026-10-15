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
    fun url(point: LocationNoise.Point?): String = Links.MAP + (viewFragment(point) ?: NO_POINT_ANCHOR)

    /** The view fragment `#lat=…&lon=…&z=16` of [point] (a shifted point), or null without one. */
    fun viewFragment(point: LocationNoise.Point?): String? {
        if (point == null) return null
        val ll = Lv95.lv95ToWgs84(point.e.toDouble(), point.n.toDouble())
        return "#lat=" + deg(ll.lat) + "&lon=" + deg(ll.lon) + "&z=" + ZOOM
    }

    private fun deg(v: Double): String = String.format(Locale.ROOT, "%.5f", v)

    private val VIEW_FRAGMENT = Regex("""^#lat=-?\d{1,3}\.\d{5}&lon=-?\d{1,3}\.\d{5}&z=\d{1,2}$""")

    /**
     * The one script the app ever runs in the page (0.6.3 map tab fix): replace only the fragment,
     * `location.replace('#lat=…&lon=…&z=16')` — no reload and no new history entry; the page's
     * hashchange handler moves the view (docs/map/karte.js). Only a [viewFragment] is accepted
     * (digits, dots and the three names), so nothing else can end up in the script.
     */
    fun recentreScript(fragment: String): String {
        require(VIEW_FRAGMENT.matches(fragment)) { "not a view fragment: $fragment" }
        return "location.replace('$fragment')"
    }

    /** Whether [url] is the map page (with any query or fragment): shown in the tab. Anything else goes to the browser. */
    fun staysInApp(url: String): Boolean = MAP_PAGE.matches(url)

    /** Whether the WebView may load [url] at all: https only, and only [ALLOWED_HOSTS] (no port, no user info). */
    fun allowedHost(url: String): Boolean {
        val m = Regex("""^https://([^/?#]*)([/?#].*)?$""").matchEntire(url) ?: return false
        val authority = m.groupValues[1]
        return authority in ALLOWED_HOSTS
    }
}

/**
 * What the tab «Karte» does with its page (0.6.3 map tab fix; pure, MapCentringTest). The WebView
 * stays alive across tab switches; this decides when it is loaded and when it is only re-centred.
 *
 * - [show] — the tab became visible, or the point changed while it is visible: the first time (or
 *   after an error, or when the page was loaded [staleMs] or longer ago, so that it shows the map
 *   files of the last 10 minutes) the page is loaded at [MapLinks.url]. Otherwise, if there is a
 *   point and the page is not centred on it yet, only the fragment is replaced
 *   ([MapLinks.recentreScript]); while the page is still loading, that waits for [pageFinished].
 *   Without a point the page is left as it is. «Centred on» means: the fragment the app last
 *   loaded or set; if you moved the map yourself, it is not moved back until the point changes.
 * - [hidden] — another tab was chosen: a page still loading is stopped (true) and loaded again
 *   when the tab is shown, so that nothing is loaded while the tab is not shown.
 */
class MapCentring(private val staleMs: Long = STALE_MS) {
    sealed interface Step {
        /** Load the page at [url] (always a [MapLinks.url]). */
        data class Load(val url: String) : Step

        /** Run [script] in the page (always a [MapLinks.recentreScript]). */
        data class Recentre(val script: String) : Step
    }

    private var loadedAtMs: Long? = null
    private var ready = false
    private var centredOn: String? = null
    private var pending: String? = null

    /** The tab is shown (or the point changed while it is shown); [nowMs] a monotonic clock. */
    fun show(point: LocationNoise.Point?, nowMs: Long): Step? {
        val loaded = loadedAtMs
        if (loaded == null || nowMs - loaded >= staleMs) {
            loadedAtMs = nowMs
            ready = false
            pending = null
            centredOn = MapLinks.viewFragment(point)
            return Step.Load(MapLinks.url(point))
        }
        val f = MapLinks.viewFragment(point) ?: return null
        if (!ready) {
            pending = f.takeIf { it != centredOn }
            return null
        }
        if (f == centredOn) return null
        centredOn = f
        return Step.Recentre(MapLinks.recentreScript(f))
    }

    /** The WebView started loading the page. */
    fun pageStarted() {
        ready = false
    }

    /** The page has loaded: a re-centring that waited for it. */
    fun pageFinished(): Step? {
        if (loadedAtMs == null) return null
        ready = true
        val f = pending ?: return null
        pending = null
        if (f == centredOn) return null
        centredOn = f
        return Step.Recentre(MapLinks.recentreScript(f))
    }

    /** The page could not be loaded: load it again at the next [show] («Nochmals» or the tab shown again). */
    fun failed() {
        loadedAtMs = null
        ready = false
        pending = null
    }

    /** Another tab was chosen. True: the page is still loading; stop it (it is loaded again at the next [show]). */
    fun hidden(): Boolean {
        if (loadedAtMs == null || ready) return false
        failed()
        return true
    }

    companion object {
        /** A page older than this is loaded again when the tab is shown: the server publishes the map every 10 minutes. */
        const val STALE_MS = 10 * 60_000L
    }
}
