package ch.lessnoise.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The Offline and Labor builds (flavours offline, labor): no tab «Karte», no map screen, no
 * WebView. This stand-in is all they contain of it (the real one is src/karte/.../MapScreen.kt,
 * compiled only into public and laborKarte; see android/app/build.gradle.kts → sourceSets).
 */
object MapTab {
    const val AVAILABLE = false

    @Composable
    fun Content(modifier: Modifier = Modifier) = Unit
}
