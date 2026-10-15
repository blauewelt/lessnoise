import Foundation

/**
 * The lock-down of the tab «Karte» (MapScreen.kt of Android 0.6.3, ios/DESIGN.md §7) as pure
 * decisions, so that they are tested here and the WKWebView code only applies them:
 * - a navigation of the main frame stays in the app only for the map page itself
 *   ([MapLinks.staysInApp]); any other link opens in Safari (user taps) or is refused;
 * - every request (also images, scripts, the map tiles) is limited to [MapLinks.ALLOWED_HOSTS] by a
 *   WKContentRuleList ([contentRules]): block everything, then allow https on the four hosts — a
 *   navigation delegate alone does not see sub-resources;
 * - no geolocation (every permission prompt denied), no JavaScript bridge, a non-persistent website
 *   data store (no cookies, no storage kept), only the two scripts of [MapLinks] are run.
 */
public enum MapPolicy {
    public enum Decision: Equatable {
        case allow
        /** Open in Safari instead (a link the person tapped). */
        case openExternally(String)
        case cancel
    }

    /**
     * [url] the request's URL; [mainFrame] whether it is the top document; [userTapped] whether it
     * comes from a link activation (WKNavigationType.linkActivated) or a new-window request.
     */
    public static func decide(_ url: String, mainFrame: Bool, userTapped: Bool) -> Decision {
        if url == "about:blank" || url.hasPrefix("about:srcdoc") { return mainFrame ? .cancel : .allow }
        if mainFrame {
            if MapLinks.staysInApp(url) { return .allow }
            if userTapped && (url.hasPrefix("https://") || url.hasPrefix("http://") || url.hasPrefix("mailto:")) { return .openExternally(url) }
            return .cancel
        }
        // Sub-frames (none expected on the map page): only the allowed hosts.
        return MapLinks.allowedHost(url) ? .allow : .cancel
    }

    /**
     * One hostname as a WebKit url-filter regex: `^https://host/` (exact host, https only, default
     * port). WebKit matches against the normalised URL, which always has the «/» after the host;
     * its regex subset has no alternation, hence no `(…|…)`.
     */
    static func filter(_ host: String) -> String {
        "^https://" + host.replacingOccurrences(of: ".", with: "\\\\.") + "/"
    }

    /**
     * The WKContentRuleList JSON: block every load, then `ignore-previous-rules` for https on the
     * allowed hosts, plus data: URLs (Leaflet's inline images). Sorted for a stable output.
     */
    public static func contentRules() -> String {
        var rules = [#"{"trigger":{"url-filter":".*"},"action":{"type":"block"}}"#]
        for h in MapLinks.ALLOWED_HOSTS.sorted() {
            rules.append(#"{"trigger":{"url-filter":""# + filter(h) + #""},"action":{"type":"ignore-previous-rules"}}"#)
        }
        rules.append(#"{"trigger":{"url-filter":"^data:"},"action":{"type":"ignore-previous-rules"}}"#)
        return "[" + rules.joined(separator: ",") + "]"
    }

    /** One line when the page cannot be loaded. */
    public static let ERROR_TEXT = "Karte nicht erreichbar – Internet prüfen"
}

/**
 * The tab history on iOS. Android's system back returns to the previous tab ([TabHistory]); iOS
 * has no back gesture between tabs of a tab bar. Mapping chosen: the tab bar switches tabs as
 * usual and records them in [TabHistory]; tabs opened by a tap inside the app (the status line's
 * «Auf der Karte …» → «Karte», «Neu starten» → «Teilen») show a «‹ Zurück» button in the top bar
 * that returns to the tab before. No edge swipe (it would fight the map's own panning).
 */
public final class TabNavigator {
    private let _history = Locked(TabHistory())
    /** True when the current tab was opened from inside another tab (shows «‹ Zurück»). */
    private let _openedByLink = Locked(false)
    public let changes = Observers()

    public init(restore names: [String] = []) {
        if !names.isEmpty { _history.set(TabHistory.restore(names)) }
    }

    public var history: TabHistory { _history.get }
    public var current: Screen { history.current }
    public var showsBack: Bool { _openedByLink.get && history.canGoBack }

    /** The tab bar. */
    public func select(_ s: Screen) {
        _history.update { $0 = $0.select(s) }
        _openedByLink.set(false)
        changes.notify()
    }

    /** A tap inside the app that opens a tab. */
    public func open(_ s: Screen) {
        let before = current
        _history.update { $0 = $0.select(s) }
        _openedByLink.set(before != s)
        changes.notify()
    }

    /** «‹ Zurück». */
    public func back() {
        _history.update { $0 = $0.back() }
        _openedByLink.set(false)
        changes.notify()
    }
}
