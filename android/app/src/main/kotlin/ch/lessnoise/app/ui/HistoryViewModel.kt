package ch.lessnoise.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.lessnoise.app.container
import ch.lessnoise.app.data.AppSettings
import ch.lessnoise.app.data.Mappers.toEvent
import ch.lessnoise.app.data.Mappers.toRecord
import ch.lessnoise.app.data.Measurement
import ch.lessnoise.app.data.MeasurementList
import ch.lessnoise.app.edition.EditionClips
import ch.lessnoise.chart.ChartData
import ch.lessnoise.chart.RangeMode
import ch.lessnoise.chart.TimeWindow
import ch.lessnoise.chart.Windows
import ch.lessnoise.dsp.MeasurementSummarizer
import ch.lessnoise.dsp.MeasurementSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

/** One row of the «Messungen» list. */
data class MeasurementItem(val measurement: Measurement, val summary: MeasurementSummary)

/**
 * State of the «Messungen» screen: the list of measurements (newest first) and the chart of the
 * selected one, as its own time span («Messung») or by hour of day («Tagesverlauf»).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.measurements
    val zone: ZoneId = ZoneId.systemDefault()
    val windows = Windows(zone)

    val settings: StateFlow<AppSettings> = c.settings.state

    private val floor = settings.map { it.eventMinLevelDb }.distinctUntilChanged()
    private val running = c.live.map { it.running }.distinctUntilChanged()

    /** All measurements with their summaries, newest first; events below the current floor are not counted. */
    val items: StateFlow<List<MeasurementItem>?> =
        combine(repo.measurementsFlow(), repo.minutesFlow(), repo.eventsFlow(), floor, running) { rows, m, e, f, run ->
            val minutes = m.map { it.toRecord() }
            val events = e.map { it.toEvent() }
            val list = MeasurementList.resolve(
                rows, minutes.map { it.startEpochMs to it.startEpochMs + Math.round(it.durationSeconds * 1000) },
                System.currentTimeMillis(), run,
            )
            val summaries = MeasurementSummarizer.summarizeAll(list.map { it.span }, minutes, events, f)
            val bySpan = summaries.associateBy { it.span }
            list.reversed().map { MeasurementItem(it, bySpan.getValue(it.span)) }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val selectedId = MutableStateFlow<Long?>(null)
    private val _mode = MutableStateFlow(RangeMode.SPAN)
    val mode: StateFlow<RangeMode> = _mode

    /** The measurement shown in the chart: the selected one, else the newest (the running one first). */
    val selected: StateFlow<MeasurementItem?> = combine(items, selectedId) { list, id ->
        list?.firstOrNull { it.measurement.id == id } ?: list?.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val window: StateFlow<TimeWindow?> = combine(selected, _mode) { s, mode ->
        s?.let { TimeWindow(mode, it.measurement.span.startMs, it.measurement.span.endMs) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Data of the current window. Keeps its last value while a new window loads. Labor build: the
     * events' clip references come from the clip index; public: none.
     */
    val data: StateFlow<ChartData?> = window.filterNotNull().flatMapLatest { w ->
        combine(
            repo.minutesBetween(w.startMs, w.endMs),
            repo.eventsBetween(w.startMs, w.endMs),
            flowOf(Unit),
            EditionClips.version,
        ) { m, e, _, _ ->
            // Neighbouring data belongs to other measurements: never an "interruption".
            ChartData(
                w, m.map { it.toRecord() }, e.map { it.toEvent() }, System.currentTimeMillis(), null, null,
                clipRefs = EditionClips.clipRefs(getApplication(), e),
            )
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setMode(mode: RangeMode) {
        _mode.value = mode
    }

    /** ‹ (−1) older, › (+1) newer measurement. */
    fun shift(delta: Int) {
        val list = items.value ?: return
        val i = list.indexOfFirst { it.measurement.id == selected.value?.measurement?.id }
        if (i < 0) return
        // The list is newest first: "newer" is a smaller index.
        list.getOrNull(i - delta)?.let { selectedId.value = it.measurement.id }
    }

    fun canShift(delta: Int): Boolean {
        val list = items.value ?: return false
        val i = list.indexOfFirst { it.measurement.id == selected.value?.measurement?.id }
        return i >= 0 && list.getOrNull(i - delta) != null
    }

    fun show(id: Long) {
        selectedId.value = id
    }

    fun setHighlight(category: String) {
        c.settings.update { it.copy(chartHighlightCategory = category) }
    }
}
