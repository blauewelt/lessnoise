package ch.lessnoise.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import ch.lessnoise.app.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

// The tab «Karte» (0.6.3): the website's map page https://wenigerlaerm.ch/karte.html in a WebView.
// Compiled ONLY into the editions with the map (flavours public and laborKarte, source folder
// src/karte/); the Offline and Labor builds get src/nomap/.../MapTab.kt instead and contain no
// WebView. This file is the app's second and last network path besides upload/UploadClient.kt
// (UploadSourceIsolationTest). What it does, and what PRIVACY.md promises about it:
//
// - Nothing is loaded before the tab is first opened. The WebView is then kept until the app's
//   screen is closed, so that switching tabs does not reload the page; while another tab is shown
//   it is invisible and paused (WebView.onPause, JavaScript timers paused; a page still loading is
//   stopped, MapCentring.hidden), and nothing is loaded or re-centred. The page keeps its data up
//   to date by itself while visible (docs/map/karte.js: cells.json every 2 minutes); shown again,
//   the app asks it to check at once (MapLinks.CHECK_SCRIPT).
// - It loads the map page like a browser would: the page from wenigerlaerm.ch (GitHub Pages), map
//   tiles from wmts.geo.admin.ch (swisstopo) and the published map files from api.lessnoise.ch
//   (or api.stadtlaerm.ch, the same server). Any other host is refused here (MapLinks.allowedHost),
//   on top of the page's own Content-Security-Policy. Links to other pages open in the browser.
// - It sends no measurement and no location. The centre (the SHIFTED point of the running
//   measurement, else of the newest shared one) is in the URL fragment, which the WebView never
//   sends to a server; the page's script reads it on the phone. When the point appears or changes
//   after the page was loaded, the app replaces only the fragment (MapLinks.recentreScript; no
//   reload, no history entry). That and MapLinks.CHECK_SCRIPT are the only scripts it ever runs
//   in the page. The map tiles for the area on
//   screen are requested from swisstopo, as for anyone who looks at that area on the website.
// - Back never goes back in the page's history (fragment changes would swallow it): the system
//   back gesture belongs to the tabs (TabHistory). Links leaving the map page open in the browser.
// - Locked down: JavaScript on (Leaflet needs it); no DOM storage (the page uses none); no file or
//   content access; no JavaScript bridge; geolocation off and every permission request denied; no
//   cookies at all; mixed content never; Safe Browsing as Android's default (on).
// - Dark (0.8.2): the page has its own dark style (docs/style.css, docs/map/karte.css: `@media
//   (prefers-color-scheme: dark)`, which also greys the swisstopo tiles down by a CSS filter). The
//   WebView reports dark to the page exactly when the app is dark ([MapDark]); no algorithmic
//   darkening by the WebView, no website change.

/** The map tab of the editions with the map. */
object MapTab {
    const val AVAILABLE = true

    /**
     * [visible]: whether «Karte» is the tab shown. The caller keeps this composable once the tab was
     * opened (below the other tabs), so the WebView survives tab switches.
     */
    @Composable
    fun Content(modifier: Modifier = Modifier, visible: Boolean = true, onOpen: (Screen) -> Unit = {}) =
        // The same signal as the app's colours (LessNoiseTheme(dark = isSystemInDarkTheme())).
        MapScreen(modifier, visible, onOpen, isDark = isSystemInDarkTheme())
}

/**
 * 0.8.2: the page's light or dark style follows the app's theme. The app decides dark only in
 * Compose (`LessNoiseTheme(dark = isSystemInDarkTheme())`); its Activity theme
 * (res/values/themes.xml, `Theme.Material.Light.NoActionBar`, no values-night) always says
 * `isLightTheme = true`. WebView sets the page's `prefers-color-scheme` from the `isLightTheme` of
 * the theme it is created with (androidx.webkit WebSettingsCompat.setAlgorithmicDarkeningAllowed,
 * for apps with targetSdk ≥ 33, which this app is), so a WebView made with the Activity's context
 * would always be light. Hence:
 *
 * - [context]: when dark, the WebView is created with a theme whose `isLightTheme` is false
 *   (`Theme.Material.NoActionBar`); the page then gets `prefers-color-scheme: dark`.
 * - [settings]: algorithmic darkening is NOT allowed (the page's own dark CSS only, never an
 *   inversion by the WebView); on old WebViews without that feature (the pre-Android-13 "force
 *   dark" model) force dark ON with the strategy «web theme darkening only» when dark (the page's
 *   own dark style, nothing else), OFF when light.
 *
 * A change of the system's dark mode recreates the Activity (MainActivity does not handle uiMode
 * itself), so the WebView is created again with the new theme.
 */
object MapDark {
    fun context(base: Context, isDark: Boolean): Context =
        if (isDark) ContextThemeWrapper(base, android.R.style.Theme_Material_NoActionBar) else base

    @Suppress("DEPRECATION") // setForceDark / setForceDarkStrategy: only for WebViews without ALGORITHMIC_DARKENING
    fun settings(settings: WebSettings, isDark: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, if (isDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(settings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY)
            }
        }
    }
}

/** Loading state of the page. */
enum class MapLoad { LOADING, LOADED, ERROR }

/** One line when the page cannot be loaded. */
const val MAP_ERROR_TEXT = "Karte nicht erreichbar – Internet prüfen"

@Composable
fun MapScreen(modifier: Modifier = Modifier, visible: Boolean = true, onOpen: (Screen) -> Unit = {}, isDark: Boolean = false) {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val live by c.live.collectAsStateWithLifecycle()
    // The centre: the running measurement's shifted point, else the newest shared measurement's.
    val livePoint = live.sharedPoint
    val centring = remember { MapCentring() }
    var load by remember { mutableStateOf(MapLoad.LOADING) }
    var progress by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    // Whether the phone has a point at all (for the banner); null until known.
    var anyPoint by remember { mutableStateOf<Boolean?>(null) }
    // Counts the activity's ON_RESUME (coming back to the app with «Karte» shown counts as showing
    // it) and «Nochmals».
    var reshow by remember { mutableIntStateOf(0) }
    val shown by rememberUpdatedState(visible)
    // Behind the page until it has drawn (no white flash in dark mode).
    val pageBackground = MaterialTheme.colorScheme.background.toArgb()

    fun perform(w: WebView, step: MapCentring.Step) {
        when (step) {
            is MapCentring.Step.Load -> {
                load = MapLoad.LOADING
                progress = 0
                val target = step.url
                w.loadUrl(target)
            }
            // location.replace('#lat=…&lon=…&z=16'): only the fragment; the page's hashchange
            // handler re-centres (docs/map/karte.js). No reload, no history entry.
            is MapCentring.Step.Recentre -> w.evaluateJavascript(step.script, null)
            // window.KarteCheck(): the page's own check for new map files, at once.
            is MapCentring.Step.Check -> w.evaluateJavascript(step.script, null)
        }
    }

    // What the last run of the effect below saw (not state: it only tells «shown again» apart
    // from «the point changed while shown»).
    val last = remember { object { var visible = false; var reshow = -1 } }

    // Shown, hidden, back in the app, or the point changed while shown.
    LaunchedEffect(visible, livePoint, web, reshow) {
        val w = web ?: return@LaunchedEffect
        if (!visible) {
            if (centring.hidden()) w.stopLoading()
            w.onPause()
            w.pauseTimers()
            last.visible = false
            return@LaunchedEffect
        }
        val shownAgain = !last.visible || last.reshow != reshow
        last.visible = true
        last.reshow = reshow
        w.resumeTimers()
        w.onResume()
        val point = livePoint ?: withContext(Dispatchers.IO) { runCatching { c.measurements.latestSharedPoint() }.getOrNull() }
        anyPoint = point != null
        centring.show(point)?.let { perform(w, it) }
        // The page checks for new map files when it becomes visible; in case the WebView does not
        // tell it, the app asks once (the page runs one check at a time).
        if (shownAgain) centring.check()?.let { perform(w, it) }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> web?.let { it.onPause(); it.pauseTimers() }
                Lifecycle.Event.ON_RESUME -> if (shown) reshow++
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Column(modifier.fillMaxSize()) {
        if (visible) {
            anyPoint?.let { MapBannerSlot(hasAnyPoint = it, onOpen = onOpen) }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(MapDark.context(ctx, isDark)).also { w ->
                        w.setBackgroundColor(pageBackground)
                        configure(
                            w,
                            isDark = isDark,
                            onProgress = { progress = it },
                            onStarted = {
                                centring.pageStarted()
                                load = MapLoad.LOADING
                            },
                            onFinished = {
                                load = MapLoad.LOADED
                                centring.pageFinished()?.let { perform(w, it) }
                            },
                            onFailed = {
                                centring.failed()
                                load = MapLoad.ERROR
                            },
                            onExternal = { openLink(context, it) },
                        )
                        web = w
                    }
                },
                // Hidden: not drawn, no touches, not read out; the page is paused (above).
                update = { w -> w.visibility = if (visible) View.VISIBLE else View.INVISIBLE },
                onRelease = { w ->
                    w.stopLoading()
                    w.webChromeClient = null
                    w.destroy()
                    web = null
                },
            )
            if (visible) {
                // «Nochmals»: the error already made MapCentring forget the page; showing loads it.
                MapStatusContent(load = load, progress = progress, onRetry = { reshow++ })
            }
        }
    }
}

/** The banner above the map while this phone's values are not visible there yet (mapBanner). */
@Composable
private fun MapBannerSlot(hasAnyPoint: Boolean, onOpen: (Screen) -> Unit) {
    val c = LocalContext.current.container
    val share = LocalShareController.current
    val live by c.live.collectAsStateWithLifecycle()
    val s by c.upload.store.state.collectAsStateWithLifecycle()
    val locate by c.shareLocate.collectAsStateWithLifecycle()
    val online = rememberOnline()
    // «Gesendet HH:MM …» disappears 12 minutes after the upload: look at the clock now and then.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val banner = mapBanner(
        s = s, running = live.running, measurementStartMs = live.measurementStartMs, hasPoint = live.sharedPoint != null,
        hasAnyPoint = hasAnyPoint, locate = locate, online = online, nowMs = now, unterwegs = live.unterwegs, hm = { Fmt.sentAt(it) },
        moving = live.moving,
    ) ?: return
    MapBannerContent(banner, onTap = { a -> handleShareAction(a, share, onOpen) })
}

/**
 * One banner row (also rendered on the JVM, ShareScreenshots): since 0.8.3 the same status chip as on
 * «Messen» (icon, short text, «›» when tappable) on a strip of the app's background above the map.
 */
@Composable
fun MapBannerContent(banner: MapBanner, onTap: (ShareAction) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 8.dp)) {
        StatusChip(ShareStatus(banner.text, banner.action, banner.icon), onTap)
    }
}

/**
 * What lies over the page: a thin progress bar while it loads, and when it cannot be loaded one line
 * and «Nochmals» (also rendered on the JVM, ShareScreenshots; the WebView itself does not render there).
 */
@Composable
fun MapStatusContent(load: MapLoad, progress: Int, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (load) {
        MapLoad.LOADED -> Unit
        MapLoad.LOADING -> Box(modifier.fillMaxWidth()) {
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        MapLoad.ERROR -> Column(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(MAP_ERROR_TEXT, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = onRetry) { Text("Nochmals") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configure(
    web: WebView,
    isDark: Boolean,
    onProgress: (Int) -> Unit,
    onStarted: () -> Unit,
    onFinished: () -> Unit,
    onFailed: () -> Unit,
    onExternal: (String) -> Unit,
) {
    web.settings.apply {
        javaScriptEnabled = true                  // Leaflet and the map script
        domStorageEnabled = false                 // docs/map/*.js use no localStorage/sessionStorage
        allowFileAccess = false
        allowContentAccess = false
        setGeolocationEnabled(false)              // the page never gets the phone's location
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)          // target=_blank goes through shouldOverrideUrlLoading
        safeBrowsingEnabled = true                // Android's default, stated explicitly
    }
    MapDark.settings(web.settings, isDark)        // 0.8.2: the page's own dark style when the app is dark
    // No cookies for this WebView (the app has no other one): neither first- nor third-party.
    CookieManager.getInstance().apply {
        setAcceptCookie(false)
        setAcceptThirdPartyCookies(web, false)
    }
    // No addJavascriptInterface: the page cannot call into the app.
    web.webChromeClient = object : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) = onProgress(newProgress)
    }
    web.webViewClient = object : WebViewClient() {
        private var failed = false

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val u = request?.url?.toString() ?: return true
            if (MapLinks.staysInApp(u)) return false
            // Datenschutz, GitHub, swisstopo's attribution …: in the browser, not here.
            onExternal(u)
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val u = request?.url?.toString() ?: return null
            if (u.startsWith("data:") || u.startsWith("about:") || MapLinks.allowedHost(u)) return null
            // Any other host: refused before it is contacted.
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            failed = false
            onStarted()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (!failed) onFinished()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) { failed = true; onFailed() }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) { failed = true; onFailed() }
        }
    }
}
