package ch.lessnoise.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.container
import ch.lessnoise.app.edition.EditionInfo
import ch.lessnoise.app.update.UpdateCheck
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Snackbar host of the app's root Scaffold. */
val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Opens the update page in the browser. The app itself never asks a server for updates; the browser does
 * the request, and the version in the URL fragment never reaches the server.
 * Returns false if no app can open the link.
 */
fun openUpdatePage(context: Context): Boolean {
    val url = UpdateCheck.updateUrl(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, EditionInfo.updateEdition(BuildConfig.EDITION))
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    // No resolveActivity() pre-check: on Android 11+ it needs a <queries> manifest entry and
    // returns null without one. Starting the activity and catching the failure is equivalent.
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Opens the update page, or shows «Kein Browser gefunden». */
@Composable
fun rememberUpdateAction(): () -> Unit {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    return {
        if (!openUpdatePage(context)) scope.launch { snackbar.showSnackbar("Kein Browser gefunden") }
    }
}

/** Age of the installed build in days if the reminder should show today, else null. */
@Composable
fun updateReminderAgeDays(): Long? {
    val store = LocalContext.current.container.updateReminder
    val dismissedOn by store.dismissedOn.collectAsStateWithLifecycle()
    val buildDate = UpdateCheck.parseBuildDate(BuildConfig.BUILD_DATE) ?: return null
    val today = LocalDate.now()
    return if (UpdateCheck.shouldRemind(buildDate, today, dismissedOn)) UpdateCheck.ageDays(buildDate, today) else null
}

/**
 * «Neue Version? Jetzt prüfen» as a tappable line with «Später». Shows nothing unless the build is
 * old enough (UpdateCheck.shouldRemind) and not snoozed.
 */
@Composable
fun UpdateReminderLine(modifier: Modifier = Modifier) {
    updateReminderAgeDays() ?: return
    val store = LocalContext.current.container.updateReminder
    val open = rememberUpdateAction()
    UpdateReminderContent(onOpen = open, onLater = { store.dismiss() }, modifier = modifier)
}

/** The reminder line without the container (0.9.2, so the JVM renders draw it). */
@Composable
fun UpdateReminderContent(onOpen: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Neue Version? Jetzt prüfen",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.weight(1f).clickable(onClickLabel = "Update prüfen") { onOpen() },
        )
        TextButton(onClick = onLater) { Text("Später") }
    }
}

/** Settings card «Über die App»: version, edition, how to switch, update check, licence. */
@Composable
fun AboutAppCard() {
    val open = rememberUpdateAction()
    SectionCard("Über die App") {
        Text(
            "${EditionInfo.label(BuildConfig.EDITION)} ${BuildConfig.VERSION_NAME} vom ${UpdateCheck.displayBuildDate(BuildConfig.BUILD_DATE)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        // Which edition is installed, and how to switch (install the other APK over this one).
        // 0.8.6: body text in bodyMedium, the small print in bodySmall (onSurfaceVariant).
        Text(EditionInfo.summary(BuildConfig.EDITION), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        EditionInfo.switchHint(BuildConfig.EDITION)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton(onClick = open) { Text("Update prüfen") }
        Text("Öffnet wenigerlaerm.ch im Browser.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Offene Software (Apache-2.0) vom Verein Weniger Lärm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
