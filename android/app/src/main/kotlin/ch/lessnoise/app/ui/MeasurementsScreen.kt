package ch.lessnoise.app.ui

import androidx.compose.foundation.background
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

    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        item {
            Text(
                "Messungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        val list = items
        val w: TimeWindow? = window
        when {
            list == null -> item { Text("Lade …", Modifier.padding(16.dp)) }
            list.isEmpty() || w == null -> item { Text("Noch keine Messung. Starte eine unter «Messen».", Modifier.padding(16.dp)) }
            else -> {
                item {
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
                }
                item {
                    Text(
                        "Alle Messungen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.measurement.id }) { m ->
                    MeasurementCard(m, selected = m.measurement.id == selected?.measurement?.id) {
                        vm.show(m.measurement.id)
                        scope.launch { listState.animateScrollToItem(0) }
                    }
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
private fun MeasurementCard(item: MeasurementItem, selected: Boolean, onClick: () -> Unit) {
    val m = item.measurement
    val s = item.summary
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                measurementTitle(m, java.time.ZoneId.systemDefault()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (m.cell != null) {
                Text(
                    "geteilt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
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
