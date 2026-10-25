package ch.lessnoise.app.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import ch.lessnoise.app.icons.AppIcons
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

    /**
     * 0.8.6: a past moment for the status chip «Auf der Karte · …»: «18:42» today, «gestern 18:42»,
     * «23.10. 18:42» earlier this year, «23.10.2025 18:42» before. Until 0.8.5 the chip showed only
     * the time, so a send of last night read like a send at «00:00» of today.
     */
    fun sentAt(ms: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val day = at.toLocalDate()
        val time = at.format(hm)
        return when {
            !day.isBefore(today) -> time
            day == today.minusDays(1) -> "gestern $time"
            day.year == today.year -> String.format(SwissLocale, "%02d.%02d. %s", day.dayOfMonth, day.monthValue, time)
            else -> String.format(SwissLocale, "%02d.%02d.%d %s", day.dayOfMonth, day.monthValue, day.year, time)
        }
    }

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

/**
 * A card section (0.8.6: a plain `surfaceContainer` card, 12 dp corners, 16 dp inside, 16 dp side
 * margins, 8 dp between cards; until 0.8.5 surfaceVariant at 55 %, which looked different in light and
 * dark). [title] in titleMedium; the content 8 dp apart.
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    /** List rows (ListItem, which brings its own 16 dp sides): no side padding, 8 dp top and bottom. */
    rows: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SCREEN_GUTTER, vertical = 6.dp).then(modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            if (rows) Modifier.padding(vertical = 8.dp) else Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(if (rows) 0.dp else 8.dp),
        ) {
            if (title != null) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = if (rows) Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp) else Modifier,
                )
            }
            content()
        }
    }
}

/** 0.8.6: non-row content inside a `SectionCard(rows = true)`: 16 dp sides, 8 dp between, 8 dp above and below. */
@Composable
fun CardBlock(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** 0.8.6: a fold-out inside a card: a text button with a trailing chevron (instead of «… ▸ / ▾»). */
@Composable
fun FoldButton(label: String, open: Boolean, onToggle: () -> Unit) {
    TextButton(
        onClick = onToggle,
        contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
        // The label in line with the text above (the button's own 12 dp start padding).
        modifier = Modifier.offset(x = (-12).dp),
    ) {
        Text(label)
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    }
}

/** 0.8.6: the side margin of every screen. */
val SCREEN_GUTTER = 16.dp

/** 0.8.6: the one screen title of every tab (headlineSmall, bold, 16 dp gutters, 20 dp above, 8 dp below). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = SCREEN_GUTTER, end = SCREEN_GUTTER, top = 20.dp, bottom = 8.dp),
    )
}

/** 0.8.6: a section heading outside a card (titleMedium), aligned with the cards' text. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = SCREEN_GUTTER, end = SCREEN_GUTTER, top = 16.dp, bottom = 4.dp),
    )
}

/** 0.8.6: helper text (bodyMedium, onSurfaceVariant) — the one style of every hint line. */
@Composable
fun HelperText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier, textAlign = textAlign)
}

/**
 * 0.8.6: a fold-out header (Material list row with a trailing expand chevron) instead of «… ▸ / ▾» in
 * a text button.
 */
@Composable
fun FoldRow(label: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = leading?.let { { Icon(it, contentDescription = null) } },
        trailingContent = { Icon(if (open) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onToggle),
    )
}

/** 0.8.6: a navigation row (leading icon, label, optional supporting line, trailing chevron). */
@Composable
fun NavRow(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(AppIcons.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
    )
}

/** 0.8.6: a switch as a list row (label, supporting text under it, trailing Switch); the whole row toggles. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, supporting: String? = null, icon: ImageVector? = null, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        // The row carries the semantics (also «disabled»); a caller that disables it fades the row itself (0.8.8).
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clip(RoundedCornerShape(12.dp)).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/**
 * 0.8.8: the header of a card section with an icon: the icon (24 dp, primary), the title (titleMedium,
 * SemiBold, as [SectionCard]'s title) and one line under it (bodySmall, onSurfaceVariant). For a
 * `SectionCard(rows = true)`: 16 dp sides, 8 dp above and below, aligned with the list rows' text.
 */
@Composable
fun CardHeader(icon: ImageVector, title: String, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 0.8.6: a destructive action: outlined, in the error colour (Material keeps destructive actions out of filled buttons). */
@Composable
fun DestructiveButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = Icons.Outlined.Delete) {
    val cs = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
        border = BorderStroke(1.dp, if (enabled) cs.error else cs.onSurface.copy(alpha = 0.12f)),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(text)
    }
}

/** 0.8.6: the confirm button of a delete dialog, in the error colour. */
@Composable
fun DestructiveTextButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(text) }
}

/** 0.8.6: an empty or loading state: a centred icon (48 dp, onSurfaceVariant) and one line under it. */
@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** 0.8.6: the one height of the stacked full-width buttons on «Messen» (filled → tonal → outlined). */
val STACK_BUTTON_HEIGHT = 56.dp

/** 0.8.6: the one label style of those buttons (labelLarge at 18 sp). */
@Composable
fun stackButtonLabel(): TextStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 18.sp, lineHeight = 24.sp)

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

    /**
     * The noise map, shown inside the app in the tab «Karte» (0.6.3, Karten editions only; see
     * MapLinks for the one fragment the app may add). Not a «Mehr dazu» link, hence not in [all].
     */
    const val MAP = "https://wenigerlaerm.ch/karte.html"
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
fun MoreLink(url: String, label: String = "Mehr dazu", icon: ImageVector? = null) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = { if (!openLink(context, url)) scope.launch { snackbar.showSnackbar("Kein Browser gefunden") } },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(label)
    }
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
