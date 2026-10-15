package ch.lessnoise.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.lessnoise.app.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

// The tab «Karte» (0.6.3): the website's map page https://wenigerlaerm.ch/karte.html in a WebView.
// Compiled ONLY into the editions with the map (flavours public and laborKarte, source folder
// src/karte/); the Offline and Labor builds get src/nomap/.../MapTab.kt instead and contain no
// WebView. This file is the app's second and last network path besides upload/UploadClient.kt
// (UploadSourceIsolationTest). What it does, and what PRIVACY.md promises about it:
//
// - It loads only while the tab is open: nothing is prefetched, nothing runs in the background; the
//   WebView is destroyed when another tab is chosen.
// - It loads the map page like a browser would: the page from wenigerlaerm.ch (GitHub Pages), map
//   tiles from wmts.geo.admin.ch (swisstopo) and the published map files from api.lessnoise.ch
//   (or api.stadtlaerm.ch, the same server). Any other host is refused here (MapLinks.allowedHost),
//   on top of the page's own Content-Security-Policy. Links to other pages open in the browser.
// - It sends no measurement and no location. The centre (the SHIFTED point of the newest shared
//   measurement, MapLinks.url) is in the URL fragment, which the WebView never sends to a server;
//   the page's script reads it on the phone. The map tiles for the area on screen are requested
//   from swisstopo, as for anyone who looks at that area on the website.
// - Locked down: JavaScript on (Leaflet needs it); no DOM storage (the page uses none); no file or
//   content access; no JavaScript bridge; geolocation off and every permission request denied; no
//   cookies at all; mixed content never; Safe Browsing as Android's default (on).

/** The map tab of the editions with the map. */
object MapTab {
    const val AVAILABLE = true

    @Composable
    fun Content(modifier: Modifier = Modifier) = MapScreen(modifier)
}

/** Loading state of the page. */
enum class MapLoad { LOADING, LOADED, ERROR }

/** One line when the page cannot be loaded. */
const val MAP_ERROR_TEXT = "Karte nicht erreichbar – Internet prüfen"

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    // The centre: the running measurement's shifted point, else the newest shared measurement's.
    // Resolved once when the tab opens; null = no shared measurement yet: the plain page.
    val url by produceState<String?>(initialValue = null) {
        val point = c.live.value.sharedPoint ?: withContext(Dispatchers.IO) { runCatching { c.measurements.latestSharedPoint() }.getOrNull() }
        value = MapLinks.url(point)
    }
    var load by remember { mutableStateOf(MapLoad.LOADING) }
    var progress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    BackHandler(enabled = canGoBack) { web?.goBack() }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> web?.onPause()
                Lifecycle.Event.ON_RESUME -> web?.onResume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Box(modifier.fillMaxSize()) {
        val target = url
        if (target != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).also { w ->
                        configure(
                            w,
                            onLoad = { load = it },
                            onProgress = { progress = it },
                            onHistory = { canGoBack = w.canGoBack() },
                            onExternal = { openLink(context, it) },
                        )
                        web = w
                        w.loadUrl(target)
                    }
                },
                onRelease = { w ->
                    w.stopLoading()
                    w.webChromeClient = null
                    w.destroy()
                    web = null
                },
            )
        }
        MapStatusContent(load = if (target == null) MapLoad.LOADING else load, progress = progress, onRetry = {
            load = MapLoad.LOADING
            web?.let { w -> if (target != null) w.loadUrl(target) }
        })
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
    onLoad: (MapLoad) -> Unit,
    onProgress: (Int) -> Unit,
    onHistory: () -> Unit,
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
            onLoad(MapLoad.LOADING)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (!failed) onLoad(MapLoad.LOADED)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) { failed = true; onLoad(MapLoad.ERROR) }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) { failed = true; onLoad(MapLoad.ERROR) }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = onHistory()
    }
}
