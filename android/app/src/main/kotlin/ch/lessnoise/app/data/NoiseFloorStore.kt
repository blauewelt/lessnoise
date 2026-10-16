package ch.lessnoise.app.data

import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.NoiseFloor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The phone's noise floor («Messgrenze», 0.6.5) for the audio source the app measures with, kept
 * in memory. The arithmetic is [NoiseFloor] (dsp); here floorRaw comes from two SQL queries (the
 * count of valid minutes, then the one rawL90 at the percentile's rank, `ORDER BY … LIMIT 1 OFFSET`),
 * so no minute is loaded into memory.
 *
 * [refresh] runs once at app start (LessNoiseApp) and once after every finished measurement
 * (MeasurementService, after the last minute is stored), and after «Alle löschen»; never during a
 * measurement, so the live measurement never waits for it. [limit] combines the cached estimate
 * with the active calibration of that source, so a new calibration moves the shown floor at once.
 */
class NoiseFloorStore(
    private val dao: MeasurementDao,
    activeCalibration: Flow<CalibrationEntity?>,
    val audioSource: String,
    private val scope: CoroutineScope,
) {
    private val estimate = MutableStateFlow<NoiseFloor.Estimate?>(null)

    /** The floor with the active calibration offset, or null (fewer than [NoiseFloor.MIN_MINUTES] valid minutes). */
    val limit: StateFlow<NoiseFloor.Limit?> = combine(estimate, activeCalibration) { e, cal ->
        e?.let { NoiseFloor.Limit(it, cal?.offsetDb ?: Acoustics.DEFAULT_CALIBRATION_OFFSET_DB) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun refresh() {
        scope.launch {
            estimate.value = try { compute(dao, audioSource) } catch (_: Exception) { estimate.value }
        }
    }

    companion object {
        /** floorRaw of [source] from the database, or null. */
        suspend fun compute(dao: MeasurementDao, source: String): NoiseFloor.Estimate? {
            val n = dao.noiseFloorMinuteCount(source)
            if (n < NoiseFloor.MIN_MINUTES) return null
            val raw = dao.noiseFloorRawL90At(source, NoiseFloor.rankIndex(n)) ?: return null
            return NoiseFloor.Estimate(source, raw, n)
        }
    }
}
