package ch.lessnoise.app.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ch.lessnoise.app.container
import ch.lessnoise.dsp.WindRule
import ch.lessnoise.dsp.classify.CategoryMapper
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val SwissLocale: Locale = Locale("de", "CH")

object Fmt {
    fun db(v: Double?, decimals: Int = 1): String =
        // Decimal comma everywhere (as on the website): «45,1 dB(A)». de-CH would give «45.1».
        if (v == null || v.isNaN() || v.isInfinite()) "–" else String.format(Locale.GERMANY, "%.${decimals}f", v)

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss", SwissLocale)
    private val hm = DateTimeFormatter.ofPattern("HH:mm", SwissLocale)
    private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", SwissLocale)

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(time)
    fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)
    fun dateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateTime)

    fun duration(seconds: Double): String = when {
        seconds < 60 -> String.format(Locale.GERMANY, "%.1f s", seconds)
        seconds < 3600 -> String.format(SwissLocale, "%d min %02d s", (seconds / 60).toInt(), (seconds % 60).toInt())
        else -> String.format(SwissLocale, "%d h %02d min", (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
    }

    fun percent(v: Double): String = String.format(SwissLocale, "%.0f %%", v * 100)

    /** «3 h 05 min», «45 min». */
    fun hoursMinutes(ms: Long): String {
        val min = (ms / 60_000L).coerceAtLeast(0)
        return if (min < 60) "$min min" else String.format(SwissLocale, "%d h %02d min", min / 60, min % 60)
    }

    /** «Sa 10.10. 22:14». */
    fun weekdayDateTime(ms: Long): String {
        val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return "${ch.lessnoise.chart.Windows.weekday(z.toLocalDate())} ${z.dayOfMonth}.${z.monthValue}. ${z.format(hm)}"
    }
}

/** German display name of a category id. */
fun categoryName(context: Context, id: String?): String = when (id) {
    null -> "–"
    // Old rows from when the classifier could be switched off (up to 0.6.0).
    "n/a" -> "–"
    // Not a classifier category: the detector's wind flag (v0.4.0).
    WindRule.CATEGORY -> WindRule.NAME_DE
    else -> try { context.container.categoryMapper.nameDe(id) } catch (_: Exception) { id }
}

fun categoryColor(id: String?): Color = when (id) {
    "loud_vehicle" -> Color(0xFFD1495B)
    "road_traffic" -> Color(0xFFEDAE49)
    "rail_tram" -> Color(0xFF00798C)
    "aircraft" -> Color(0xFF6C5B7B)
    "construction" -> Color(0xFF8D6A3F)
    "voices" -> Color(0xFF3E8E41)
    "music" -> Color(0xFF30638E)
    CategoryMapper.UNCLASSIFIED -> Color(0xFF9E9E9E)
    WindRule.CATEGORY -> Color(0xFFB0BEC5)
    else -> Color(0xFFBDBDBD)
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).then(modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/**
 * The «Mehr dazu» pages on the website (0.6.1). The anchors are the sections of anleitung.html
 * (messen, kalibrieren, teilen, daten); privacy questions go to datenschutz.html. Opening one is a
 * plain browser link: the app itself makes no request (the offline edition has no INTERNET
 * permission at all).
 */
object Links {
    private const val GUIDE = "https://wenigerlaerm.ch/anleitung.html"
    const val MEASURE = "$GUIDE#messen"
    const val CALIBRATE = "$GUIDE#kalibrieren"
    const val SHARE = "$GUIDE#teilen"
    const val DATA = "$GUIDE#daten"
    const val PRIVACY = "https://wenigerlaerm.ch/datenschutz.html"
    val all = listOf(MEASURE, CALIBRATE, SHARE, DATA, PRIVACY)
}

/** Opens [url] in the browser; false if no app can open it. */
fun openLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** «Mehr dazu»: a small text button that opens [url] (snackbar «Kein Browser gefunden» otherwise). */
@Composable
fun MoreLink(url: String, label: String = "Mehr dazu") {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = { if (!openLink(context, url)) scope.launch { snackbar.showSnackbar("Kein Browser gefunden") } },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    ) { Text(label) }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

fun hasMicPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Returns a launcher that requests microphone (+ notification) permission and calls [onResult]. */
@Composable
fun rememberMicPermissionLauncher(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        onResult(res[Manifest.permission.RECORD_AUDIO] == true)
    }
    return {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        launcher.launch(perms.toTypedArray())
    }
}

/** Shares exported files through the system share sheet (FileProvider, read-only grant). */
fun shareFiles(context: Context, files: List<File>, mime: String, subject: String) {
    val authority = "${context.packageName}.fileprovider"
    val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = mime
    intent.putExtra(Intent.EXTRA_SUBJECT, subject)
    intent.clipData = ClipData.newRawUri(null, uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
