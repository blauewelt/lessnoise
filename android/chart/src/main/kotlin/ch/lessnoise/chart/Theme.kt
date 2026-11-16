package ch.lessnoise.chart

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// The app theme lives here (not in :app) so the chart can be rendered with it on the JVM.

// 0.8.3: every role Material 3 components read is set. Before, the roles left out (surfaceContainer*,
// outline*, tertiaryContainer, inverse*) fell back to Material's purple baseline (a lavender bottom bar),
// and secondaryContainer was a beige that the status card, the tab indicator and tonal elements used.
// The brand colours stay: navy primary, amber secondary (an accent), green tertiary; the containers
// are calm blue-grey tones of the navy, the surfaces neutral steps of the warm off-white / blue-black.
val LightColors = lightColorScheme(
    primary = Color(0xFF1F3A4D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F0),
    onPrimaryContainer = Color(0xFF0B1E2A),
    inversePrimary = Color(0xFF9CC6E4),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE5EC),
    onSecondaryContainer = Color(0xFF15232D),
    tertiary = Color(0xFF3E6B48),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8EBCF),
    onTertiaryContainer = Color(0xFF00210B),
    background = Color(0xFFF7F7F4),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFF7F7F4),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE4E4DE),
    onSurfaceVariant = Color(0xFF46484A),
    surfaceTint = Color(0xFF1F3A4D),
    inverseSurface = Color(0xFF2F312F),
    inverseOnSurface = Color(0xFFF1F1EE),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF76787A),
    outlineVariant = Color(0xFFC6C8C4),
    surfaceBright = Color(0xFFF7F7F4),
    surfaceDim = Color(0xFFD9D9D5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F1EE),
    surfaceContainer = Color(0xFFECECE8),
    surfaceContainerHigh = Color(0xFFE6E6E2),
    surfaceContainerHighest = Color(0xFFE0E0DC),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC6E4),
    onPrimary = Color(0xFF0B1E2A),
    primaryContainer = Color(0xFF2A4B61),
    onPrimaryContainer = Color(0xFFD3E4F0),
    inversePrimary = Color(0xFF1F3A4D),
    secondary = Color(0xFFF2C14E),
    onSecondary = Color(0xFF3F2E00),
    secondaryContainer = Color(0xFF2E3C47),
    onSecondaryContainer = Color(0xFFD6E3EC),
    tertiary = Color(0xFF9FD3A8),
    onTertiary = Color(0xFF0A3818),
    tertiaryContainer = Color(0xFF24502F),
    onTertiaryContainer = Color(0xFFC8EBCF),
    background = Color(0xFF111416),
    onBackground = Color(0xFFE2E3E1),
    surface = Color(0xFF111416),
    onSurface = Color(0xFFE2E3E1),
    surfaceVariant = Color(0xFF2A2E31),
    onSurfaceVariant = Color(0xFFC4C7CA),
    surfaceTint = Color(0xFF9CC6E4),
    inverseSurface = Color(0xFFE2E3E1),
    inverseOnSurface = Color(0xFF2F3133),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF8E9194),
    outlineVariant = Color(0xFF43474A),
    surfaceBright = Color(0xFF363A3C),
    surfaceDim = Color(0xFF111416),
    surfaceContainerLowest = Color(0xFF0C0F10),
    surfaceContainerLow = Color(0xFF171A1C),
    surfaceContainer = Color(0xFF1B1F21),
    surfaceContainerHigh = Color(0xFF25292B),
    surfaceContainerHighest = Color(0xFF303436),
)

@Composable
fun LessNoiseTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

/**
 * Chart data colours (validated for colour-vision deficiency and contrast), chosen per mode, never
 * derived by inversion. Text never uses these; it uses the theme's text colours.
 */
data class ChartPalette(val series1: Color, val series2: Color, val bandAlpha: Float) {
    companion object {
        // Band alpha: ~14 % on light; on the dark card 14 % was hard to see in renders, so 22 %.
        val Light = ChartPalette(series1 = Color(0xFF2A78D6), series2 = Color(0xFFEB6834), bandAlpha = 0.14f)
        val Dark = ChartPalette(series1 = Color(0xFF3987E5), series2 = Color(0xFFD95926), bandAlpha = 0.22f)

        @Composable
        @ReadOnlyComposable
        fun current(): ChartPalette = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Dark else Light
    }
}
