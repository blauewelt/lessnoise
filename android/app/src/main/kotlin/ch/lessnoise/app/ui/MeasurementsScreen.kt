package ch.lessnoise.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import ch.lessnoise.app.icons.AppIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.lessnoise.app.edition.EditionClips
import ch.lessnoise.chart.ChartFmt
import ch.lessnoise.chart.HistoryActions
import ch.lessnoise.chart.HistorySection
import ch.lessnoise.chart.RangeMode
import ch.lessnoise.chart.Selection
import ch.lessnoise.chart.TimeWindow
import ch.lessnoise.chart.clipRefsInTimeOrder
import kotlinx.coroutines.launch

/** «Messungen»: the chart of the selected measurement and the list of all measurements. */
@Composable
fun MeasurementsScreen(modifier: Modifier = Modifier, vm: HistoryViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val window by vm.window.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val noiseFloor by vm.noiseFloor.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The tooltip belongs to one window: navigating closes it.
    val selectionState = remember(window) { mutableStateOf<Selection?>(null) }
    val actions = remember(vm, selectionState) {
        object : HistoryActions {
            override fun onMode(mode: RangeMode) = vm.setMode(mode)
            override fun onShift(delta: Int) = vm.shift(delta)
            override fun onHighlight(category: String) = vm.setHighlight(category)
            override fun onSelect(selection: Selection?) { selectionState.value = selection }
        }
    }

    val w: TimeWindow? = window
    MeasurementsContent(
        items = items, selectedId = selected?.measurement?.id, modifier = modifier, listState = listState,
        chart = if (items.isNullOrEmpty() || w == null) null else ({
            HistorySection(
                window = w, data = data, zone = vm.zone, highlight = settings.chartHighlightCategory,
                eventFloorDb = settings.eventMinLevelDb,
                canGoNext = vm.canShift(1), canGoPrevious = vm.canShift(-1),
                selection = selectionState.value, actions = actions,
                // Labor build only: play an event's clip; «‹»/«›» step through the window's clips.
                onPlayClip = EditionClips.player?.let { play ->
                    { ref: String -> play(ref, data?.clipRefsInTimeOrder(settings.eventMinLevelDb) ?: listOf(ref)) }
                },
                showWindEvents = settings.showWindEvents,
                noiseFloor = noiseFloor,
            )
        }),
        onSelect = { id ->
            vm.show(id)
            scope.launch { listState.animateScrollToItem(0) }
        },
    )
}

/**
 * «Messungen» without the view model (0.8.6; also rendered on the JVM): the title, the chart of the
 * selected measurement ([chart], null while loading or without measurements), «Alle Messungen» as
 * cards (the selected one in primaryContainer, «geteilt» as a small chip), and the empty and loading
 * states with a centred icon.
 */
@Composable
fun MeasurementsContent(
    items: List<MeasurementItem>?,
    selectedId: Long?,
    chart: (@Composable () -> Unit)?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        item { ScreenTitle("Messungen") }
        val list = items
        when {
            list == null -> item { EmptyState(AppIcons.History, "Lade …") }
            list.isEmpty() || chart == null -> item { EmptyState(AppIcons.GraphicEq, "Noch keine Messung. Starte eine unter «Messen».") }
            else -> {
                item { chart() }
                item { SectionHeading("Alle Messungen", Modifier.padding(top = 12.dp)) }
                items(list, key = { it.measurement.id }) { m ->
                    MeasurementCard(m, selected = m.measurement.id == selectedId, zone = zone) { onSelect(m.measurement.id) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * «Grundrauschen» as shown: «41,2 dB(A)», or «≤ 34,0 dB(A)» when it is at the phone's noise floor
 * (0.6.5, [limitDb] = floor + 3 dB, null otherwise).
 */
fun humText(humDb: Double?, limitDb: Double?): String =
    if (limitDb != null) "≤ ${Fmt.db(limitDb)} dB(A)" else "${Fmt.db(humDb)} dB(A)"

/** «Sa 10.10. 22:14 – So 11.10. 06:30», «läuft seit Sa 10.10. 22:14». */
fun measurementTitle(m: ch.lessnoise.app.data.Measurement, zone: java.time.ZoneId): String =
    if (m.running) "läuft seit ${Fmt.weekdayDateTime(m.span.startMs)}"
    else ch.lessnoise.chart.Windows(zone).label(TimeWindow.span(m.span.startMs, m.span.endMs))

@Composable
private fun MeasurementCard(item: MeasurementItem, selected: Boolean, zone: java.time.ZoneId, onClick: () -> Unit) {
    val m = item.measurement
    val s = item.summary
    val cs = MaterialTheme.colorScheme
    SectionCard(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).semantics { this.selected = selected },
        containerColor = if (selected) cs.primaryContainer else cs.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                measurementTitle(m, zone),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (m.cell != null) SharedChip()
        }
        StatRow("Dauer", Fmt.hoursMinutes(m.span.durationMs))
        StatRow("Mittel", "${Fmt.db(s.laeqDb)} dB(A)")
        StatRow("Grundrauschen", humText(s.humDb, s.humLimitDb))
        StatRow(
            "Lärmspitzen pro Stunde",
            if (s.eventsPerHour.isNaN()) "–" else ChartFmt.comma(s.eventsPerHour, if (s.eventsPerHour < 10) 1 else 0),
        )
    }
}

/** 0.8.6: «geteilt» as a small outlined chip with the cloud icon (until 0.8.5 a filled rounded label). */
@Composable
private fun SharedChip() {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp), color = Color.Transparent, border = BorderStroke(1.dp, cs.outline)) {
        Row(Modifier.padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.CloudDone, contentDescription = null, tint = cs.tertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("geteilt", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}
