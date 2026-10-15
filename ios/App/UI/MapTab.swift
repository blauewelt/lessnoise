import LessNoiseKit
import SwiftUI
import UIKit
import WebKit

/**
 * The tab «Karte» (MapScreen.kt of Android 0.6.3; ios/DESIGN.md §7): https://wenigerlaerm.ch/karte.html
 * in a WKWebView, locked down:
 * - nothing is loaded before the tab is first opened; the web view is kept while other tabs are
 *   shown (no reload when switching), a page still loading is stopped when the tab is hidden;
 * - a WKContentRuleList blocks every request except https to the four hosts of
 *   [MapLinks.ALLOWED_HOSTS] (also images, scripts, tiles); the page is loaded only once the rule
 *   list is installed (fails closed);
 * - only the map page itself stays inside; any other link the person taps opens in Safari;
 * - a non-persistent website data store (no cookies, no storage kept), no JavaScript bridge,
 *   media capture denied, and a document-start script that makes geolocation fail (the page never
 *   asks for it; this is defence in depth);
 * - the centre (the SHIFTED point) is in the fragment, which is never sent; a new point re-centres
 *   with [MapLinks.recentreScript] (location.replace: no reload, no history entry); the only other
 *   script is [MapLinks.CHECK_SCRIPT].
 */
@MainActor
final class MapWebController: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    enum Load: Equatable { case idle, loading, loaded, error }

    @Published private(set) var load: Load = .idle
    @Published private(set) var progress: Double = 0
    private(set) var webView: WKWebView?
    private let centring = MapCentring()
    private var rulesReady = false
    private var pendingShow: LocationNoise.Point??
    private var progressObservation: NSKeyValueObservation?

    static let geolocationOff = """
        (function(){try{var g=navigator.geolocation;if(!g)return;var d=function(s,e){if(e)e({code:1,message:'denied',PERMISSION_DENIED:1});};\
        g.getCurrentPosition=d;g.watchPosition=function(s,e){d(s,e);return 0;};}catch(x){}})();
        """

    func makeWebView() -> WKWebView {
        if let w = webView { return w }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.allowsInlineMediaPlayback = false
        config.mediaTypesRequiringUserActionForPlayback = .all
        config.dataDetectorTypes = []
        config.userContentController.addUserScript(WKUserScript(source: MapWebController.geolocationOff, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        let w = WKWebView(frame: .zero, configuration: config)
        w.navigationDelegate = self
        w.uiDelegate = self
        w.allowsLinkPreview = false
        w.allowsBackForwardNavigationGestures = false
        progressObservation = w.observe(\.estimatedProgress, options: [.new]) { [weak self] wv, _ in
            Task { @MainActor in self?.progress = wv.estimatedProgress }
        }
        webView = w
        WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "lessnoise-map-hosts", encodedContentRuleList: MapPolicy.contentRules()) { [weak self] list, _ in
            Task { @MainActor in
                guard let self = self else { return }
                guard let list = list else {
                    // No rule list, no page: the web view would otherwise not be restricted to the four hosts.
                    self.load = .error
                    return
                }
                w.configuration.userContentController.add(list)
                self.rulesReady = true
                if let p = self.pendingShow { self.pendingShow = nil; self.show(p) }
            }
        }
        return w
    }

    /** The tab is shown (or the point changed while shown). */
    func show(_ point: LocationNoise.Point?) {
        guard let w = webView else { return }
        guard rulesReady else { pendingShow = .some(point); return }
        if let step = centring.show(point) { perform(w, step) }
    }

    /** The tab was shown again: a loaded page checks for new map files at once. */
    func shownAgain() {
        guard let w = webView, let step = centring.check() else { return }
        perform(w, step)
    }

    func hidden() {
        guard let w = webView else { return }
        if centring.hidden() { w.stopLoading(); load = .idle }
    }

    func retry(_ point: LocationNoise.Point?) {
        centring.failed()
        show(point)
    }

    private func perform(_ w: WKWebView, _ step: MapCentring.Step) {
        switch step {
        case let .load(url):
            load = .loading
            progress = 0
            if let u = URL(string: url) { w.load(URLRequest(url: u)) }
        case let .recentre(script), let .check(script):
            w.evaluateJavaScript(script, completionHandler: nil)
        }
    }

    // WKNavigationDelegate

    // The async forms of the delegate methods (their ObjC selectors are the completion-handler ones;
    // AppTests checks that WebKit sees them).
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        let url = action.request.url?.absoluteString ?? ""
        let main = action.targetFrame?.isMainFrame ?? true
        switch MapPolicy.decide(url, mainFrame: main, userTapped: action.navigationType == .linkActivated || action.targetFrame == nil) {
        case .allow:
            return .allow
        case let .openExternally(u):
            if let x = URL(string: u) { UIApplication.shared.open(x, options: [:], completionHandler: nil) }
            return .cancel
        case .cancel:
            return .cancel
        }
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        centring.pageStarted()
        load = .loading
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        load = .loaded
        if let step = centring.pageFinished() { perform(webView, step) }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { failed(error) }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { failed(error) }

    private func failed(_ error: Error) {
        // A cancelled navigation (a link that went to Safari) is not a failure of the page.
        if (error as NSError).code == NSURLErrorCancelled { return }
        centring.failed()
        load = .error
    }

    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse) async -> WKNavigationResponsePolicy {
        if response.isForMainFrame, let http = response.response as? HTTPURLResponse, http.statusCode >= 400 {
            centring.failed()
            load = .error
            return .cancel
        }
        return .allow
    }

    // WKUIDelegate: no new windows (target=_blank goes through the policy above), no camera/microphone.

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let u = action.request.url, case let .openExternally(x) = MapPolicy.decide(u.absoluteString, mainFrame: true, userTapped: true),
           let url = URL(string: x) {
            UIApplication.shared.open(url)
        }
        return nil
    }

    // The async name of webView:requestMediaCapturePermissionForOrigin:initiatedByFrame:type:decisionHandler:.
    func webView(_ webView: WKWebView, decideMediaCapturePermissionsFor origin: WKSecurityOrigin, initiatedBy frame: WKFrameInfo,
                 type: WKMediaCaptureType) async -> WKPermissionDecision {
        .deny
    }
}

/** The kept WKWebView inside SwiftUI. */
struct MapWebView: UIViewRepresentable {
    let controller: MapWebController
    func makeUIView(context: Context) -> WKWebView { controller.makeWebView() }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct MapTabView: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var map: MapWebController
    let visible: Bool
    @State private var now = Date()
    private let timer = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        let point = model.services.mapPoint()
        VStack(spacing: 0) {
            if let b = banner(point) {
                StatusLine(status: ShareStatus(b.text, b.action), onTap: { model.handle($0) })
                    .padding(.horizontal, 8).padding(.vertical, 6)
            }
            ZStack(alignment: .top) {
                MapWebView(controller: map)
                switch map.load {
                case .loading, .idle:
                    ProgressView(value: map.progress > 0.01 && map.progress < 0.99 ? map.progress : nil)
                        .progressViewStyle(.linear)
                case .error:
                    VStack(spacing: 16) {
                        Spacer()
                        Text(MapPolicy.ERROR_TEXT).multilineTextAlignment(.center)
                        Button("Nochmals") { map.retry(point) }.buttonStyle(.borderedProminent)
                        Spacer()
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(.systemBackground))
                case .loaded:
                    EmptyView()
                }
            }
        }
        .onAppear { if visible { map.show(point); map.shownAgain() } }
        .onChange(of: visible) { v in
            if v { map.show(point); map.shownAgain() } else { map.hidden() }
        }
        .onChange(of: point) { p in if visible { map.show(p) } }
        .onReceive(timer) { now = $0 }
    }

    private func banner(_ point: LocationNoise.Point?) -> MapBanner? {
        let l = model.live
        return mapBanner(
            model.upload, running: l.running, measurementStartMs: l.measurementStartMs, hasPoint: l.sharedPoint != nil,
            hasAnyPoint: point != nil, locate: model.services.share.locate, online: model.online,
            nowMs: Int(now.timeIntervalSince1970 * 1000), hm: { Fmt.hm($0) }
        )
    }
}
