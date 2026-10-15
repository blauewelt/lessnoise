package ch.lessnoise.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.edition.EditionUi
import ch.lessnoise.chart.LessNoiseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LessNoiseTheme { AppRoot() } }
    }
}

/** Tab icon. «Karte»: the pin (Icons.Filled.Map is only in the large extended icon set). */
fun screenIcon(s: Screen): ImageVector = when (s) {
    Screen.MEASURE -> Icons.Filled.Home
    Screen.MEASUREMENTS -> Icons.Filled.DateRange
    Screen.MAP -> Icons.Filled.Place
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

@Composable
fun AppRoot() {
    val screens = remember { appScreens(BuildConfig.UPLOAD_AVAILABLE, MapTab.AVAILABLE) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    // Karten editions: the consent at the first «Starten», the fix for the running measurement and
    // its dialogs (ShareFlow.kt). Null in the Offline and Labor builds.
    val share = if (BuildConfig.UPLOAD_AVAILABLE) rememberShareController() else null
    val open: (Screen) -> Unit = { s -> screens.indexOf(s).takeIf { it >= 0 }?.let { selected = it } }
    Scaffold(
        // Public: emits nothing (same as Scaffold's default). Labor: the red warning banner.
        topBar = { EditionUi.Banner() },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { AppBottomBar(screens, selected) { selected = it } },
    ) { padding ->
        val m = Modifier.padding(padding)
        CompositionLocalProvider(LocalSnackbarHost provides snackbar, LocalShareController provides share) {
            when (screens.getOrNull(selected) ?: Screen.MEASURE) {
                Screen.MEASURE -> MeasureScreen(m, onOpen = open)
                Screen.MEASUREMENTS -> MeasurementsScreen(m)
                // Loaded only while this tab is shown (MapScreen.kt); nothing in the background.
                Screen.MAP -> MapTab.Content(m)
                Screen.SHARE -> ShareScreen(m, onOpen = open)
                Screen.SETTINGS -> SettingsScreen(m)
            }
            share?.Dialogs()
            // Public: nothing. Labor: the clip player sheet.
            EditionUi.Overlay()
        }
    }
}
