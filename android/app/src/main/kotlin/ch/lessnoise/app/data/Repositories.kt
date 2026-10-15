package ch.lessnoise.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import ch.lessnoise.app.data.Mappers.toEntity
import ch.lessnoise.app.data.Mappers.toEvent
import ch.lessnoise.app.data.Mappers.toRecord
import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.Csv
import ch.lessnoise.dsp.Iso
import ch.lessnoise.dsp.MinuteRecord
import ch.lessnoise.dsp.NoiseEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.ZoneId

import ch.lessnoise.dsp.EngineConfig
import ch.lessnoise.dsp.ThresholdMigration
import ch.lessnoise.dsp.WindRule

private val DEFAULT_INTERVAL_S: Int = ch.lessnoise.dsp.EngineConfig.DEFAULT_CLASSIFIER_INTERVAL_SECONDS.toInt()

data class AppSettings(
    /**
     * «Lärmspitze ab … dB über Grundrauschen» (Einstellungen › Erweitert): an event starts when LAF
     * reaches the local floor + this (dB, 3–20, 0.5 steps). Replaces the v0.3 `eventThresholdDb`.
     */
    val eventExcessDb: Double = EngineConfig.DEFAULT_EVENT_EXCESS_DB,
    /**
     * «Lärmspitzen erst ab … dB(A)» (Einstellungen › Erweitert): events count only if their LAFmax
     * reaches this level; also applied to stored events. Follows calibrations.
     */
    val eventMinLevelDb: Double = ch.lessnoise.dsp.EngineConfig.DEFAULT_EVENT_MIN_LEVEL_DB,
    /** Event category emphasised in the chart (the chips on «Messungen»). */
    val chartHighlightCategory: String = "loud_vehicle",
) {
    // Fixed since 0.6.1 («weniger Optionen»): these were settings up to 0.6.0 and are now always
    // their former defaults. Values stored by an older version are ignored (SettingsStore never
    // reads them). They stay properties so that the engine configuration and the Labor manifest
    // keep their shape.

    /** The local floor is the L90 over this trailing window (s). Was «Lokaler Hintergrund: Fenster». */
    val localFloorWindowSeconds: Int get() = EngineConfig.DEFAULT_LOCAL_FLOOR_WINDOW_SECONDS.toInt()
    /** Wind if the 20–200 Hz energy share is at least this … (was «Experten: Wind am Mikrofon») */
    val windLfShareMin: Double get() = WindRule.DEFAULT_LF_SHARE
    /** … or the low-frequency flutter at least this (dB). */
    val windFlutterMinDb: Double get() = WindRule.DEFAULT_FLUTTER_DB
    /** Wind events are not drawn in the chart (they are never counted). Was a switch, default on. */
    val showWindEvents: Boolean get() = false
    /** The on-device classifier always runs (was a switch). */
    val classifierEnabled: Boolean get() = true
    /** Once per second (EngineConfig.DEFAULT_CLASSIFIER_INTERVAL_SECONDS); was 1 | 2 | 5 s. */
    val classifierIntervalSeconds: Int get() = DEFAULT_INTERVAL_S
    /** Level adjustment of the classifier input, always on (was a switch). */
    val classifierNormalize: Boolean get() = true
    /** Partial wake lock while measuring, always (was a switch). */
    val wakeLock: Boolean get() = true
    /** The measurement keeps running when the app is swiped away (was a switch, default off). */
    val stopOnTaskRemoved: Boolean get() = false

    companion object {
        const val EVENT_MIN_LEVEL_MIN = 20.0
        const val EVENT_MIN_LEVEL_MAX = 70.0
        const val EVENT_EXCESS_MIN = ThresholdMigration.EXCESS_MIN
        const val EVENT_EXCESS_MAX = ThresholdMigration.EXCESS_MAX
    }
}

/**
 * v0.3 → v0.4 settings migration (pure, unit-tested): the stored `event_threshold_db` (over the
 * 5-min background, default 10) becomes `event_excess_db` (over the local floor) =
 * [ThresholdMigration.excessFromLegacyThreshold]. Since 0.6.1 nobody is told any more (the one-time
 * notice of 0.4–0.6.0 was removed).
 */
object SettingsMigration {
    /** [oldThresholdDb]: the stored v0.3 value, null if none was ever stored. */
    fun migrate(oldThresholdDb: Double?): Double =
        if (oldThresholdDb == null) EngineConfig.DEFAULT_EVENT_EXCESS_DB
        else ThresholdMigration.excessFromLegacyThreshold(oldThresholdDb)
}

/**
 * Small settings store on SharedPreferences, exposed as a StateFlow. Only the three remaining
 * settings are read and written; keys of the settings removed in 0.6.1 (local_floor_window_s,
 * wind_*, show_wind_events, classifier_*, wake_lock, stop_on_task_removed) are left untouched in
 * the file but never read, so they no longer change anything.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // v0.3 → v0.4: event_threshold_db (over the 5-min background) → event_excess_db (over the
        // local floor), once. The old key is left alone (a downgrade would still find it).
        if (!prefs.contains(KEY_EXCESS)) {
            val old = if (prefs.contains(KEY_LEGACY_THRESHOLD)) prefs.getFloat(KEY_LEGACY_THRESHOLD, 10f).toDouble() else null
            prefs.edit().putFloat(KEY_EXCESS, SettingsMigration.migrate(old).toFloat()).apply()
        }
        // The one-time notice about that migration is no longer shown (0.6.1).
        if (prefs.contains(KEY_NOTICE)) prefs.edit().remove(KEY_NOTICE).apply()
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state

    private fun read() = AppSettings(
        eventExcessDb = prefs.getFloat(KEY_EXCESS, EngineConfig.DEFAULT_EVENT_EXCESS_DB.toFloat()).toDouble()
            .coerceIn(AppSettings.EVENT_EXCESS_MIN, AppSettings.EVENT_EXCESS_MAX),
        eventMinLevelDb = prefs.getFloat("event_min_level_db", ch.lessnoise.dsp.EngineConfig.DEFAULT_EVENT_MIN_LEVEL_DB.toFloat())
            .toDouble().coerceIn(AppSettings.EVENT_MIN_LEVEL_MIN, AppSettings.EVENT_MIN_LEVEL_MAX),
        chartHighlightCategory = prefs.getString("chart_highlight", null) ?: "loud_vehicle",
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(_state.value)
        prefs.edit()
            .putFloat(KEY_EXCESS, n.eventExcessDb.toFloat())
            .putFloat("event_min_level_db", n.eventMinLevelDb.toFloat())
            .putString("chart_highlight", n.chartHighlightCategory)
            .apply()
        _state.value = n
    }

    companion object {
        const val KEY_EXCESS = "event_excess_db"
        const val KEY_LEGACY_THRESHOLD = "event_threshold_db"
        const val KEY_NOTICE = "excess_migration_notice"
    }
}

/** Calibration offsets per (device model, audio source), with full history. */
class CalibrationRepository(private val dao: CalibrationDao) {
    companion object {
        val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    data class Active(val entity: CalibrationEntity?) {
        val offsetDb: Double get() = entity?.offsetDb ?: Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        val calibrated: Boolean get() = entity?.isCalibrated == true
        val id: Long? get() = entity?.id
    }

    suspend fun active(source: String): Active = Active(dao.latest(deviceModel, source))
    fun activeFlow(source: String): Flow<CalibrationEntity?> = dao.latestFlow(deviceModel, source)
    fun history(): Flow<List<CalibrationEntity>> = dao.historyFlow()

    suspend fun save(
        source: String, offsetDb: Double, method: String, notes: String,
        referenceDb: Double? = null, measuredRawDb: Double? = null, stdDevDb: Double? = null,
        durationSeconds: Double? = null, calibratorNominalDb: Double? = null,
        toneFrequencyHz: Double? = null, tonality: Double? = null, warnings: List<String> = emptyList(),
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            CalibrationEntity(
                deviceModel = deviceModel, audioSource = source, offsetDb = offsetDb, method = method,
                createdEpochMs = now, createdIso = Iso.format(now, ZoneId.systemDefault()), notes = notes,
                referenceDb = referenceDb, measuredRawDb = measuredRawDb, stdDevDb = stdDevDb,
                durationSeconds = durationSeconds, calibratorNominalDb = calibratorNominalDb,
                toneFrequencyHz = toneFrequencyHz, tonality = tonality, warnings = warnings.joinToString(","),
            )
        )
    }

    suspend fun exportJson(): String {
        val all = dao.all()
        return buildJsonObject {
            put("app", "Weniger Lärm")
            put("format", "calibration-history-v1")
            put("default_offset_db", Acoustics.DEFAULT_CALIBRATION_OFFSET_DB)
            put("calibrations", buildJsonArray {
                all.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id)
                        put("device_model", c.deviceModel)
                        put("audio_source", c.audioSource)
                        put("offset_db", c.offsetDb)
                        put("method", c.method)
                        put("created", c.createdIso)
                        put("notes", c.notes)
                        c.referenceDb?.let { put("reference_db", it) }
                        c.measuredRawDb?.let { put("measured_raw_db", it) }
                        c.stdDevDb?.let { put("std_dev_1s_db", it) }
                        c.durationSeconds?.let { put("duration_s", it) }
                        c.calibratorNominalDb?.let { put("calibrator_nominal_db", it) }
                        c.toneFrequencyHz?.let { put("tone_frequency_hz", it) }
                        c.tonality?.let { put("tonality", it) }
                        if (c.warnings.isNotEmpty()) put("warnings", c.warnings)
                    })
                }
            })
        }.toString()
    }
}

/** Stored aggregates (minutes, events) and their export. */
class MeasurementRepository(private val dao: MeasurementDao) {
    suspend fun insert(m: MinuteRecord) = dao.insertMinute(m.toEntity())
    suspend fun insert(e: NoiseEvent) = dao.insertEvent(e.toEntity())

    fun recentEvents(limit: Int = 30): Flow<List<EventEntity>> = dao.recentEvents(limit)
    fun minutesFlow() = dao.minutesFlow()
    fun minutesBetween(fromMs: Long, toMs: Long) = dao.minutesBetween(fromMs, toMs)
    fun eventsBetween(fromMs: Long, toMs: Long) = dao.eventsBetween(fromMs, toMs)
    fun lastValidEndBefore(t: Long) = dao.lastValidEndBefore(t)
    fun firstValidStartAfter(t: Long) = dao.firstValidStartAfter(t)
    fun eventsFlow() = dao.eventsFlow()
    fun minuteCount() = dao.minuteCount()
    fun eventCount() = dao.eventCount()

    suspend fun allMinutes(): List<MinuteRecord> = dao.allMinutes().map { it.toRecord() }
    suspend fun allEvents(): List<NoiseEvent> = dao.allEvents().map { it.toEvent() }

    suspend fun clear() {
        dao.clearMinutes(); dao.clearEvents(); dao.clearMeasurements()
    }

    fun measurementsFlow() = dao.measurementsFlow()

    /**
     * «Start»: records a new measurement with its shifted [point] (null = not shared). Open rows left by a
     * measurement the system killed before «Stop» are closed first, at the end of their last minute.
     */
    suspend fun beginMeasurement(startMs: Long, point: ch.lessnoise.dsp.geo.LocationNoise.Point?): Long {
        val open = dao.openMeasurements().sortedBy { it.startMs }
        for ((i, m) in open.withIndex()) {
            val next = open.getOrNull(i + 1)?.startMs ?: startMs
            dao.endMeasurement(m.id, dao.lastMinuteEnd(m.startMs - ch.lessnoise.dsp.MeasurementSplitter.START_TOLERANCE_MS, next) ?: m.startMs)
        }
        return dao.insertMeasurement(
            MeasurementEntity(startMs = startMs, cell = point?.cell, pointE = point?.e, pointN = point?.n, sigmaM = point?.sigmaM)
        )
    }

    /** «Stop». */
    suspend fun endMeasurement(id: Long, endMs: Long) = dao.endMeasurement(id, endMs)

    /**
     * App 0.6.3: the shifted [point] found after «Starten» (or after switching sharing on while
     * measuring) becomes the point of the running measurement that started at [startMs], so that
     * all its minutes and events are shared, also those recorded before the fix. Only a measurement
     * that is still open and has no point is changed (an ended one stays unshared). The row is
     * inserted right after «Starten»; if the fix was faster, this waits for it (≤ 3 s).
     */
    suspend fun attachPoint(startMs: Long, point: ch.lessnoise.dsp.geo.LocationNoise.Point): Boolean {
        repeat(15) {
            if (dao.attachPoint(startMs, point.cell, point.e, point.n, point.sigmaM) > 0) return true
            kotlinx.coroutines.delay(200)
        }
        return false
    }

    /** Writes CSV exports into cache/exports (served via FileProvider). */
    suspend fun exportCsv(dir: File, bucketIds: List<String>): List<File> {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())
        val minutes = File(dir, "wenigerlaerm-minuten-$stamp.csv").apply { writeText(Csv.minutes(allMinutes(), bucketIds)) }
        val events = File(dir, "wenigerlaerm-ereignisse-$stamp.csv").apply { writeText(Csv.events(allEvents())) }
        return listOf(minutes, events)
    }
}
