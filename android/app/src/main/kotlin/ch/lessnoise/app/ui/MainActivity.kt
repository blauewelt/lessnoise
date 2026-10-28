package ch.lessnoise.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import ch.lessnoise.app.icons.AppIcons
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.edition.EditionUi
import ch.lessnoise.app.service.MeasurementNotification
import ch.lessnoise.chart.LessNoiseTheme

class MainActivity : ComponentActivity() {
    // «Laut! Melden» opened from outside the app (always null in the Offline and Labor builds): since
    // 0.8.4 the app shortcut and the home-screen widget (editions with the map, src/karte/…/report/):
    // the time of the tap (now), nothing preselected. Labor·Karte also: the «Sehr laut» notification
    // (the event's time and suggested categories) and the notification's «Melden» (the time of the tap).
    // Read in onCreate (a cold start from the shortcut or the widget) and in onNewIntent (app open).
    private var alertPreset by mutableStateOf<ReportPreset?>(null)
    // 0.9.4.4: the measurement notification's own tap (MeasurementNotification.ACTION_SHOW_MEASURE): «Messen»
    // comes to the front, also when another tab was open. A counter, so a second tap switches again.
    private var showMeasure by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Not again after a rotation: the intent was taken the first time.
        if (savedInstanceState == null) takeAlert(intent)
        setContent { LessNoiseTheme { AppRoot(alertPreset, onAlertPresetUsed = { alertPreset = null }, showMeasure = showMeasure) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeAlert(intent)
    }

    private fun takeAlert(intent: Intent?) {
        if (MeasurementNotification.showsMeasure(intent?.action)) {
            showMeasure++
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        // The tap's time is now, when the activity receives it (also while it is already open: onNewIntent).
        val preset = LoudAlerts.presetFrom(intent, System.currentTimeMillis()) ?: return
        alertPreset = preset
        // Taken: a later recreation must not start the report again.
        setIntent(Intent(this, MainActivity::class.java))
    }
}

/** Tab icon. «Karte»: the pin (Icons.Filled.Map is only in the large extended icon set). */
fun screenIcon(s: Screen): ImageVector = when (s) {
    // 0.8.6: icons that say what the tab is (until 0.8.5 Home / DateRange / Place from the core set).
    Screen.MEASURE -> AppIcons.GraphicEq
    Screen.MEASUREMENTS -> AppIcons.History
    Screen.MAP -> AppIcons.Map
    Screen.SHARE -> Icons.Filled.Share
    Screen.SETTINGS -> Icons.Filled.Settings
}

/** The bottom bar (also rendered on the JVM, ShareScreenshots). */
@Composable
fun AppBottomBar(screens: List<Screen>, selected: Int, onSelect: (Int) -> Unit) {
    val labels = remember(screens) { screens.map(::screenLabel) }
    NavigationBar {
        screens.forEachIndexed { i, s ->
            val label = labels[i]
            NavigationBarItem(
                selected = selected == i,
                onClick = { onSelect(i) },
                icon = { Icon(screenIcon(s), contentDescription = label) },
                // Five tabs since 0.6.3: ONE label size for all tabs, the largest at which every
                // label (in practice «Einstellungen») fits one line in a tab's width.
                label = { BarLabel(label, labels, MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/**
 * [text] in the largest size (≤ [style]'s, ≥ 8 sp, half-sp steps) at which all of [all] fit the
 * available width on one line. Every tab has the same width, so every tab arrives at the same size.
 */
@Composable
private fun BarLabel(text: String, all: List<String>, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val max = constraints.maxWidth
        val fitted = remember(all, max, style) {
            if (max == Constraints.Infinity) return@remember style
            fun fits(s: TextStyle) = all.all { measurer.measure(it, s, maxLines = 1, softWrap = false).size.width <= max }
            var size = style.fontSize.value
            var s = style
            while (!fits(s) && size > 8f) {
                size -= 0.5f
                s = style.copy(fontSize = size.sp, letterSpacing = 0.sp)
            }
            s
        }
        Text(text, style = fitted, maxLines = 1, softWrap = false)
    }
}

/** Saves the tab history across configuration changes (as the tab names). */
private val TabHistorySaver = Saver<TabHistory, ArrayList<String>>(
    save = { ArrayList(it.save()) },
    restore = { TabHistory.restore(it) },
)

@Composable
fun AppRoot(alertPreset: ReportPreset? = null, onAlertPresetUsed: () -> Unit = {}, showMeasure: Int = 0) {
    val screens = remember { appScreens(BuildConfig.UPLOAD_AVAILABLE, MapTab.AVAILABLE) }
    // The undo stack of tabs opened (TabHistory): every switch, tapped or opened from a status
    // line or banner, pushes the tab left; back pops it; with nothing left back leaves the app
    // (no handler then).
    var history by rememberSaveable(stateSaver = TabHistorySaver) { mutableStateOf(TabHistory()) }
    val current = history.current.takeIf { it in screens } ?: Screen.MEASURE
    val snackbar = remember { SnackbarHostState() }
    // Karten editions: the consent at the first «Starten», the fix for the running measurement and
    // its dialogs (ShareFlow.kt). Null in the Offline and Labor builds.
    val share = if (BuildConfig.UPLOAD_AVAILABLE) rememberShareController() else null
    val open: (Screen) -> Unit = { s -> if (s in screens) history = history.select(s) }
    BackHandler(enabled = history.canGoBack) { history = history.back() }
    // «Laut! Melden» from the notification runs on «Messen» (a tab switch like any other).
    LaunchedEffect(alertPreset) { if (alertPreset != null) open(Screen.MEASURE) }
    // 0.9.4.4: a tap on the measurement notification shows «Messen» (the running measurement, and «Laut! Melden»).
    LaunchedEffect(showMeasure) { if (showMeasure > 0) open(Screen.MEASURE) }
    // The map tab's WebView is created the first time the tab is shown and then kept (paused while
    // another tab is shown) until the activity ends, so that switching tabs does not reload it.
    var mapOpened by remember { mutableStateOf(false) }
    SideEffect { if (current == Screen.MAP) mapOpened = true }
    Scaffold(
        // Public: emits nothing (same as Scaffold's default). Labor: the red warning banner.
        topBar = { EditionUi.Banner() },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { AppBottomBar(screens, screens.indexOf(current)) { i -> open(screens[i]) } },
    ) { padding ->
        val m = Modifier.padding(padding)
        CompositionLocalProvider(LocalSnackbarHost provides snackbar, LocalShareController provides share) {
            Box(Modifier.fillMaxSize()) {
                // Below the other tabs; invisible and paused unless «Karte» is shown (MapScreen.kt).
                if (MapTab.AVAILABLE && (mapOpened || current == Screen.MAP)) {
                    MapTab.Content(m, visible = current == Screen.MAP, onOpen = open)
                }
                when (current) {
                    Screen.MEASURE -> MeasureScreen(m, onOpen = open, reportPreset = alertPreset, onReportPresetUsed = onAlertPresetUsed)
                    Screen.MEASUREMENTS -> MeasurementsScreen(m)
                    Screen.MAP -> Unit
                    Screen.SHARE -> ShareScreen(m, onOpen = open)
                    Screen.SETTINGS -> SettingsScreen(m)
                }
            }
            share?.Dialogs()
            // Public: nothing. Labor: the clip player sheet.
            EditionUi.Overlay()
        }
    }
}
