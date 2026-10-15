package ch.lessnoise.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableIntStateOf
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

private enum class Screen { MEASURE, MEASUREMENTS, SHARE, SETTINGS }

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

/**
 * «Messen · Messungen · Einstellungen», plus «Teilen» in the editions with the Lärmkarte (0.6.1;
 * Kalibrieren and Daten moved into Einstellungen).
 */
private val tabs = buildList {
    add(Tab(Screen.MEASURE, "Messen", Icons.Filled.Home))
    add(Tab(Screen.MEASUREMENTS, "Messungen", Icons.Filled.DateRange))
    if (BuildConfig.UPLOAD_AVAILABLE) add(Tab(Screen.SHARE, "Teilen", Icons.Filled.Share))
    add(Tab(Screen.SETTINGS, "Einstellungen", Icons.Filled.Settings))
}

@Composable
fun AppRoot() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    // Karten editions: «Auf die Lärmkarte?», the location fix and the status line (LocationFlows.kt).
    val share = if (BuildConfig.UPLOAD_AVAILABLE) rememberShareFlow() else null
    share?.openShareTab = { tabs.indexOfFirst { it.screen == Screen.SHARE }.takeIf { it >= 0 }?.let { selected = it } }
    Scaffold(
        // Public: emits nothing (same as Scaffold's default). Labor: the red warning banner.
        topBar = { EditionUi.Banner() },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        CompositionLocalProvider(LocalSnackbarHost provides snackbar, LocalShareFlow provides share) {
            when (tabs.getOrNull(selected)?.screen ?: Screen.MEASURE) {
                Screen.MEASURE -> MeasureScreen(m)
                Screen.MEASUREMENTS -> MeasurementsScreen(m)
                Screen.SHARE -> ShareScreen(m)
                Screen.SETTINGS -> SettingsScreen(m)
            }
            // Public: nothing. Labor: the clip player sheet.
            EditionUi.Overlay()
        }
    }
}
