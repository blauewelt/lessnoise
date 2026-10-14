package ch.lessnoise.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.rememberCoroutineScope
import ch.lessnoise.app.BuildConfig
import kotlinx.coroutines.launch
import ch.lessnoise.app.container
import ch.lessnoise.app.data.EventEntity
import ch.lessnoise.app.edition.EditionUi
import ch.lessnoise.app.service.LiveState
import ch.lessnoise.app.service.MeasurementService

@Composable
fun MeasureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = context.container
    val live by c.live.collectAsStateWithLifecycle()
    val events by remember { c.measurements.recentEvents(30) }.collectAsStateWithLifecycle(emptyList())
    val calibrating by c.calibrationActive.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    // Karten editions with «Teilen» on: one location fix at «Starten» (LocationFlows.kt).
    val sharedStart = if (BuildConfig.UPLOAD_AVAILABLE) rememberSharedStart { point -> MeasurementService.start(context, point) } else null
    fun startMeasurement() {
        val sharing = BuildConfig.UPLOAD_AVAILABLE && c.upload.store.state.value.enabled
        if (sharing && sharedStart != null) sharedStart() else MeasurementService.start(context)
    }
    val requestPermission = rememberMicPermissionLauncher { granted ->
        if (granted) startMeasurement()
    }
    val report = if (BuildConfig.UPLOAD_AVAILABLE) {
        rememberReportAction { scope.launch { snackbar.showSnackbar("Meldung wird gesendet.") } }
    } else null

    MeasureContent(
        live = live, events = events, calibrating = calibrating, modifier = modifier,
        categoryName = { categoryName(context, it) },
        onStart = { if (hasMicPermission(context)) startMeasurement() else requestPermission() },
        onStop = { MeasurementService.stop(context) },
        onReport = report,
        updateLine = { UpdateReminderLine() },
        eventMarker = { EditionUi.EventMarker(it) },
    )
}

/**
 * «Messen» without the app container (also rendered on the JVM for the website's screenshot).
 * Before «Starten»: one placement hint, one microphone line, the button. While measuring: the
 * level now, the Grundrauschen, what it sounds like, since when, and the last Lärmspitzen.
 */
@Composable
fun MeasureContent(
    live: LiveState,
    events: List<EventEntity>,
    calibrating: Boolean,
    categoryName: (String?) -> String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onReport: (() -> Unit)? = null,
    updateLine: @Composable () -> Unit = {},
    eventMarker: @Composable (Long) -> Unit = {},
) {
    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Text(
                "Messen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }

        if (!live.running) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(PLACEMENT_HINT, style = MaterialTheme.typography.bodyMedium)
                    Text(EditionUi.MIC_LINE, style = MaterialTheme.typography.bodyMedium)
                    MoreLink(Links.MEASURE)
                }
            }
        }

        if (live.running && (live.micSilenced || live.invalidAudio)) {
            item {
                SectionCard {
                    Text(
                        if (live.micSilenced) "Mikrofon ist stumm (z. B. Anruf). Diese Zeit zählt nicht."
                        else "Kein Mikrofonsignal. Diese Zeit zählt nicht.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (live.running) {
            item {
                SectionCard {
                    Text("Jetzt", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Fmt.db(live.lafDb), fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("dB(A)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                    }
                    LevelBar(live.lafDb)
                    Spacer(Modifier.height(4.dp))
                    StatRow("Grundrauschen", "${Fmt.db(live.backgroundDb)} dB(A)")
                }
            }
            item {
                SectionCard("Klingt nach") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(categoryColor(live.dominantCategory)))
                        Spacer(Modifier.width(8.dp))
                        Text(categoryName(live.dominantCategory), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live.running) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Stoppen", fontSize = 18.sp) }
                    Text("Läuft seit ${Fmt.hm(live.startedAtMs)}. Der Bildschirm darf aus sein.", style = MaterialTheme.typography.bodySmall)
                    live.sharedPoint?.let { Text(sharedMeasurementLine(it.sigmaM), style = MaterialTheme.typography.bodySmall) }
                } else {
                    Button(
                        onClick = onStart,
                        enabled = !live.starting && !calibrating,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(if (live.starting) "Startet …" else "Starten", fontSize = 18.sp) }
                    // «Laut! Melden»: Karten editions only, while no measurement runs.
                    if (onReport != null && !live.starting) {
                        OutlinedButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) { Text("Laut! Melden") }
                    }
                }
                if (calibrating) Text("Erst nach dem Kalibrieren möglich.", style = MaterialTheme.typography.bodySmall)
                if (!live.running) updateLine()
                live.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }

        if (live.running) {
            item {
                Text(
                    "Letzte Lärmspitzen",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (events.isEmpty()) {
                item { Text("Noch keine.", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            items(events, key = { it.id }) { e -> EventRow(e, categoryName, eventMarker) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The one placement hint on «Messen» (0.6.1). */
const val PLACEMENT_HINT = "Leg das Telefon ans offene Fenster. Für lange Messungen: ans Ladegerät."

@Composable
fun LevelBar(db: Double?) {
    val lo = 20.0
    val hi = 100.0
    val p = if (db == null || db.isNaN()) 0f else ((db - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()
    val color = when {
        db == null -> MaterialTheme.colorScheme.primary
        db >= 75 -> MaterialTheme.colorScheme.error
        db >= 55 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Column {
        LinearProgressIndicator(
            progress = { p },
            modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        Row(Modifier.fillMaxWidth()) {
            Text("20", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("60", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("100 dB", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun EventRow(e: EventEntity, categoryName: (String?) -> String, marker: @Composable (Long) -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(e.dominantCategory)))
            Spacer(Modifier.width(8.dp))
            Text(Fmt.hm(e.startEpochMs), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
            Text(
                "${Fmt.db(e.lafMaxDb)} dB(A)", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.width(92.dp),
            )
            Text(
                categoryName(e.dominantCategory ?: "n/a"), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            marker(e.id)
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.surfaceVariant)
    }
}
