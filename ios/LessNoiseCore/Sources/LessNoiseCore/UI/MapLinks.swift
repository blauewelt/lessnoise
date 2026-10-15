import Foundation

/** The project's web pages (Links in ui/Common.kt). */
public enum Links {
    private static let GUIDE = "https://wenigerlaerm.ch/anleitung.html"
    public static let MEASURE = GUIDE + "#messen"
    public static let CALIBRATE = GUIDE + "#kalibrieren"
    public static let SHARE = GUIDE + "#teilen"
    public static let DATA = GUIDE + "#daten"
    public static let PRIVACY = "https://wenigerlaerm.ch/datenschutz.html"
    public static let all = [MEASURE, CALIBRATE, SHARE, DATA, PRIVACY]
    /** The noise map, shown inside the app in the tab «Karte». */
    public static let MAP = "https://wenigerlaerm.ch/karte.html"
}

/**
 * The tab «Karte» (ui/MapLinks.kt): which address it opens and what the web view may load. On iOS
 * a WKWebView with a `WKNavigationDelegate` that applies [allowedHost] to every navigation and
 * [staysInApp] to link taps, a non-persistent data store, and no other script than the two below.
 */
public enum MapLinks {
    /** Zoom of the opened map: the 100 m field and its neighbours on a phone screen. */
    public static let ZOOM = 16
    private static let HOST = "wenigerlaerm.ch"
    /** Hosts the web view may contact: the page, swisstopo's tiles, the API (docs/karte.html CSP). */
    public static let ALLOWED_HOSTS: Set<String> = [HOST, "wmts.geo.admin.ch", "api.lessnoise.ch", "api.stadtlaerm.ch"]
    /** Without a point: the plain map page at its own anchor `#metric`. */
    public static let NO_POINT_ANCHOR = "#metric"

    /** The address to open: the map page, centred on [point] if there is one (a shifted point). */
    public static func url(_ point: LocationNoise.Point?) -> String { Links.MAP + (viewFragment(point) ?? NO_POINT_ANCHOR) }

    /** The view fragment `#lat=…&lon=…&z=16` of [point] (a shifted point), or nil without one. */
    public static func viewFragment(_ point: LocationNoise.Point?) -> String? {
        guard let p = point else { return nil }
        let ll = Lv95.lv95ToWgs84(Double(p.e), Double(p.n))
        return "#lat=" + JavaFormat.fixed(ll.lat, 5) + "&lon=" + JavaFormat.fixed(ll.lon, 5) + "&z=" + String(ZOOM)
    }

    /** `^#lat=-?\d{1,3}\.\d{5}&lon=-?\d{1,3}\.\d{5}&z=\d{1,2}$` */
    static func isViewFragment(_ f: String) -> Bool {
        var u = Substring(f)
        func lit(_ s: String) -> Bool { if u.hasPrefix(s) { u = u.dropFirst(s.count); return true }; return false }
        func digits(_ min: Int, _ max: Int) -> Bool {
            var n = 0
            while let c = u.first, c.isASCII, c.isNumber, n < max { u = u.dropFirst(); n += 1 }
            if let c = u.first, c.isASCII, c.isNumber { return false } // more digits than allowed
            return n >= min
        }
        func coord() -> Bool {
            _ = lit("-")
            return digits(1, 3) && lit(".") && digits(5, 5)
        }
        return lit("#lat=") && coord() && lit("&lon=") && coord() && lit("&z=") && digits(1, 2) && u.isEmpty
    }

    public enum ScriptError: Error, Equatable { case notAViewFragment(String) }

    /** The one script the app runs in the page: replace only the fragment (no reload, no history entry). */
    public static func recentreScript(_ fragment: String) throws -> String {
        guard isViewFragment(fragment) else { throw ScriptError.notAViewFragment(fragment) }
        return "location.replace('\(fragment)')"
    }

    /** The only other script: ask the kept page to check for new map files at once. */
    public static let CHECK_SCRIPT = "window.KarteCheck && window.KarteCheck()"

    /** Java's `.` does not match line terminators, so the Kotlin regexes refuse any URL containing one. */
    private static func hasLineTerminator(_ s: String) -> Bool {
        s.unicodeScalars.contains { $0 == "\n" || $0 == "\r" || $0 == "\u{85}" || $0 == "\u{2028}" || $0 == "\u{2029}" }
    }

    /** Whether [url] is the map page (with any query or fragment): shown in the tab; anything else goes to the browser. */
    public static func staysInApp(_ url: String) -> Bool {
        guard url.hasPrefix(Links.MAP), !hasLineTerminator(url) else { return false }
        let rest = url.dropFirst(Links.MAP.count)
        return rest.isEmpty || rest.first == "?" || rest.first == "#"
    }

    /** Whether the web view may load [url] at all: https only, and only [ALLOWED_HOSTS] (no port, no user info). */
    public static func allowedHost(_ url: String) -> Bool {
        guard url.hasPrefix("https://"), !hasLineTerminator(url) else { return false }
        let afterScheme = url.dropFirst("https://".count)
        let authority = afterScheme.prefix { $0 != "/" && $0 != "?" && $0 != "#" }
        return ALLOWED_HOSTS.contains(String(authority))
    }
}

/**
 * What the tab «Karte» does with its page (MapCentring in MapLinks.kt): load once, then only
 * re-centre by fragment; stop a load when the tab is hidden; ask a kept page to check for new files.
 */
public final class MapCentring {
    public enum Step: Equatable {
        case load(String)
        case recentre(String)
        case check(String)
    }

    private var loaded = false
    private var ready = false
    private var centredOn: String?
    private var pending: String?

    public init() {}

    /** The tab is shown (or the point changed while it is shown). */
    public func show(_ point: LocationNoise.Point?) -> Step? {
        if !loaded {
            loaded = true
            ready = false
            pending = nil
            centredOn = MapLinks.viewFragment(point)
            return .load(MapLinks.url(point))
        }
        guard let f = MapLinks.viewFragment(point) else { return nil }
        if !ready {
            pending = f != centredOn ? f : nil
            return nil
        }
        if f == centredOn { return nil }
        centredOn = f
        return .recentre(try! MapLinks.recentreScript(f))
    }

    /** The web view started loading the page. */
    public func pageStarted() { ready = false }

    /** The tab became visible again: a loaded page checks for new map files at once. */
    public func check() -> Step? { loaded && ready ? .check(MapLinks.CHECK_SCRIPT) : nil }

    /** The page has loaded: a re-centring that waited for it. */
    public func pageFinished() -> Step? {
        if !loaded { return nil }
        ready = true
        guard let f = pending else { return nil }
        pending = nil
        if f == centredOn { return nil }
        centredOn = f
        return .recentre(try! MapLinks.recentreScript(f))
    }

    /** The page could not be loaded: load it again at the next [show]. */
    public func failed() {
        loaded = false
        ready = false
        pending = nil
    }

    /** Another tab was chosen. True: the page is still loading; stop it. */
    public func hidden() -> Bool {
        if !loaded || ready { return false }
        failed()
        return true
    }
}
