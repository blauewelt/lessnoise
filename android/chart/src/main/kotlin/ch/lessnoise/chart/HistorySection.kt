package ch.lessnoise.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId

/** Callbacks of the history section. */
interface HistoryActions {
    fun onMode(mode: RangeMode)
    fun onShift(delta: Int)
    fun onHighlight(category: String)
    fun onSelect(selection: Selection?)
}

/**
 * The chart part of the «Messungen» screen: the two views («Messung», «Tagesverlauf»), stepping to
 * the previous/next measurement, the chart card with category chips and legend, and the summary row.
 *
 * [data] may belong to the previous window while the new one loads; it is then shown dimmed.
 *
 * [onPlayClip]: Labor build only (null in the public app), see [NoiseChart].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistorySection(
    window: TimeWindow,
    data: ChartData?,
    zone: ZoneId,
    highlight: String,
    eventFloorDb: Double,
    canGoNext: Boolean,
    selection: Selection?,
    actions: HistoryActions,
    modifier: Modifier = Modifier,
    onPlayClip: ((clipRef: String) -> Unit)? = null,
    /** Draw wind events (never counted). Fixed off in the app since 0.6.1 (AppSettings.showWindEvents). */
    showWindEvents: Boolean = true,
    /** An older measurement exists («‹»). */
    canGoPrevious: Boolean = true,
    /** 0.6.5: the phone's noise floor («Messgrenze» band, «≤» in «Grundrauschen»); null: none. */
    noiseFloor: ch.lessnoise.dsp.NoiseFloor.Limit? = null,
) {
    val windows = remember(zone) { Windows(zone) }
    val model = remember(data, highlight, eventFloorDb, zone, showWindEvents, noiseFloor) {
        data?.let { ChartModel.build(it, zone, highlight, eventFloorDb, showWindEvents, noiseFloor) }
    }
    val loading = data == null || data.window != window
    val scheme = MaterialTheme.colorScheme
    val palette = ChartPalette.current()
    val highlightName = ChartCategories.name(highlight)

    val margin = 16.dp
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 1. View and navigation (‹ › = previous / next measurement). «Tagesverlauf» only makes a
        // difference for a measurement of a day or more: the choice is shown only then (or while
        // that view is on, so it can be left).
        if (showsModeChoice(window)) {
            val modes = listOf(RangeMode.SPAN to "Messung", RangeMode.PROFILE to "Tagesverlauf")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = margin)) {
                modes.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = window.mode == m,
                        onClick = { actions.onMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = margin), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.onShift(-1) }, enabled = canGoPrevious, modifier = Modifier.semantics { contentDescription = "frühere Messung" }) {
                Text("‹", style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                windows.label(window), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { actions.onShift(1) }, enabled = canGoNext,
                modifier = Modifier.semantics { contentDescription = "spätere Messung" },
            ) { Text("›", style = MaterialTheme.typography.headlineMedium) }
        }

        // 2. Chart card.
        val cardColor = scheme.surface
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.dp, scheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(horizontal = margin),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (model?.isEmpty == false) "dB(A)" else "", style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                }
                val chartHeight = 248.dp
                if (model == null || model.isEmpty) {
                    Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.Center) {
                        Text(
                            if (model == null) "Lade …" else "Keine Messwerte.",
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    NoiseChart(
                        model = model, selection = selection, onSelect = actions::onSelect, onNavigate = actions::onShift,
                        canGoNext = canGoNext, surfaceColor = cardColor, height = chartHeight,
                        modifier = if (loading) Modifier.alpha(0.5f) else Modifier,
                        onPlayClip = onPlayClip,
                    )
                }
                if (model != null && model.hourly) {
                    Text(hourlyNote(window.mode, model.eventsCapped, eventFloorDb), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                // Legend (text colours; small swatches carry the series colours).
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    LegendItem(Swatch.Dot(palette.series2), highlightName)
                    if (model?.hourly != true) {
                        LegendItem(Swatch.Dot(scheme.onSurfaceVariant.copy(alpha = 0.55f).compositeOver(cardColor), small = true), "andere Spitzen")
                    }
                    LegendItem(
                        Swatch.Line(palette.series1),
                        when {
                            window.mode == RangeMode.PROFILE -> "Mittel je Tagesstunde"
                            model?.hourly == true -> "Mittel pro Stunde"
                            else -> "Mittel pro Minute"
                        },
                    )
                    LegendItem(Swatch.Band(palette.series1.copy(alpha = palette.bandAlpha).compositeOver(cardColor)), BAND_LEGEND)
                    if (model != null && model.windEvents.isNotEmpty()) {
                        LegendItem(Swatch.Hollow(scheme.onSurfaceVariant.copy(alpha = 0.55f).compositeOver(cardColor)), WIND_LEGEND)
                    }
                    if (onPlayClip != null && model != null && model.clipRefs.isNotEmpty()) {
                        LegendItem(Swatch.Ring(scheme.onSurface.copy(alpha = 0.85f)), "mit Clip (antippen › $PLAY_CLIP_LABEL)")
                    }
                }
            }
        }
        // Category chips (one scrolling row): which category is emphasised. The selected chip
        // carries an orange dot like the highlighted events.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = margin),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 0.9.2: what the chips are for (until 0.9.1 an unlabelled row).
            item(key = "label") {
                Box(Modifier.height(32.dp), contentAlignment = Alignment.CenterStart) {
                    Text(HIGHLIGHT_LABEL, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                }
            }
            items(ChartCategories.all, key = { it.first }) { (id, name) ->
                val selected = highlight == id
                FilterChip(
                    selected = selected, onClick = { actions.onHighlight(id) }, label = { Text(name) },
                    leadingIcon = if (selected) {
                        { Canvas(Modifier.size(10.dp)) { drawCircle(palette.series2) } }
                    } else null,
                )
            }
        }

        // 3. Summary.
        val s = model?.summary
        // Equal-height tiles; labels and notes may wrap to two lines on narrow phones.
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Max).padding(horizontal = margin),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The hum (steady background) and the bursts above it are separate quantities.
            StatTile("Grundrauschen", s?.humText ?: "–", HUM_NOTE, Modifier.weight(1f))
            StatTile("Lärmspitzen", s?.eventsPerHourText ?: "–", "pro Stunde", Modifier.weight(1f))
            val loud = s?.loudest
            StatTile(
                "Lauteste", loud?.let { "${ChartFmt.db(it.lafMaxDb)} dB(A)" } ?: (if (s == null) "–" else NO_PEAK),
                loud?.let { TimeFmt(zone, windows.crossesDates(window)).hm(it.startEpochMs) }, Modifier.weight(1f),
            )
        }
        if (s?.humLimitDb != null && model?.isEmpty == false) {
            Text(
                AT_FLOOR_NOTE, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = margin),
            )
        }
        if (s != null && model?.isEmpty == false) {
            Text(
                s.line(highlightName = highlightName), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = margin),
            )
        }
    }
}

/**
 * Note under an hourly chart: what the points are and which events are drawn (only the
 * highlighted category from [ChartModel.HOURLY_EVENT_MIN_DB] or the floor, at most
 * [ChartModel.HOURLY_EVENT_CAP]).
 */
fun hourlyNote(mode: RangeMode, capped: Boolean, @Suppress("UNUSED_PARAMETER") eventFloorDb: Double): String {
    val what = if (mode == RangeMode.PROFILE) "Alle Tage übereinander, pro Stunde" else "Pro Stunde"
    return if (capped) "$what; nur die ${ChartModel.HOURLY_EVENT_CAP} lautesten Spitzen." else "$what; nur die markierte Geräuschart."
}

/** The «Messung | Tagesverlauf» choice: for measurements of at least a day, or while «Tagesverlauf» is on. */
fun showsModeChoice(window: TimeWindow): Boolean =
    window.mode == RangeMode.PROFILE || window.durationMs >= 24 * Windows.HOUR_MS

private sealed interface Swatch {
    data class Dot(val color: Color, val small: Boolean = false) : Swatch
    data class Line(val color: Color) : Swatch
    data class Band(val color: Color) : Swatch
    data class Ring(val color: Color) : Swatch
    data class Hollow(val color: Color) : Swatch
}

@Composable
private fun LegendItem(swatch: Swatch, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
            when (swatch) {
                is Swatch.Dot -> drawCircle(swatch.color, if (swatch.small) size.height / 3 else size.height / 2, Offset(size.width / 2, size.height / 2))
                is Swatch.Line -> drawLine(swatch.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), StrokeCap.Round)
                is Swatch.Band -> drawRect(swatch.color)
                is Swatch.Hollow -> drawCircle(
                    swatch.color, size.height / 3, Offset(size.width / 2, size.height / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.25.dp.toPx()),
                )
                is Swatch.Ring -> drawCircle(
                    swatch.color, size.height / 2 - 1.dp.toPx(), Offset(size.width / 2, size.height / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.25.dp.toPx()),
                )
            }
        }
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sub ?: " ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
