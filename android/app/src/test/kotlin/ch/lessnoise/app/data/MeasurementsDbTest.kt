package ch.lessnoise.app.data

import ch.lessnoise.dsp.MeasurementSpan
import ch.lessnoise.dsp.geo.LocationNoise
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Messungen» (app 0.6.0): the migrations 5 → 6 and 6 → 7 keep every record, and rows become
 * measurements (shared = with a shifted point).
 */
class MeasurementsDbTest {
    private val min = 60_000L

    @Test
    fun migration5to6KeepsAllDataAndDerivesMeasurementsFromGaps() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(MigrationSql.CREATE_MINUTES_V5)
                st.execute(MigrationSql.CREATE_EVENTS_V5)
                // Run 1: 0..30 min, a 9-min gap (same run), 39..60; run 2 starts 11 min after the end of run 1.
                val starts = (0 until 30).map { it * min } + (39 until 60).map { it * min } + (71 until 80).map { it * min }
                for ((i, t) in starts.withIndex()) {
                    st.execute(
                        "INSERT INTO minutes (startEpochMs, startIso, durationSeconds, laeqDb, eventCount, categorySharesJson, " +
                            "classifierFrames, calibrationOffsetDb, audioSource, calibrated, validSeconds, coverage, clockCorrections) " +
                            "VALUES ($t, 'm$i', ${if (i == starts.lastIndex) 30.5 else 60.0}, 40.0, 0, '{}', 0, 112.0, 'UNPROCESSED', 0, 60.0, 1.0, 0)"
                    )
                }
                st.execute(
                    "INSERT INTO events (startEpochMs, startIso, durationSeconds, lafMaxDb, selDb, backgroundDb, thresholdDb, " +
                        "dominantScore, topLabelsJson, classifierFrames, audioSource, calibrated) VALUES " +
                        "(${5 * min}, 'e', 1.0, 60.0, 61.0, 30.0, 10.0, 0.0, '[]', 0, 'UNPROCESSED', 0)"
                )
                val read = ArrayList<Pair<Long, Double>>()
                st.executeQuery(MigrationSql.SELECT_MINUTE_SPANS).use { rs -> while (rs.next()) read += rs.getLong(1) to rs.getDouble(2) }
                MigrationSql.migrate5to6(read).forEach { st.execute(it) }

                val rows = ArrayList<List<Any?>>()
                st.executeQuery("SELECT start_ms, end_ms, cell FROM measurements ORDER BY start_ms").use { rs ->
                    while (rs.next()) rows += listOf(rs.getLong(1), rs.getLong(2), rs.getString(3))
                }
                assertEquals(listOf(listOf<Any?>(0L, 60 * min, null), listOf<Any?>(71 * min, 79 * min + 30_500, null)), rows)
                st.executeQuery("SELECT COUNT(*) FROM minutes").use { rs -> rs.next(); assertEquals(starts.size, rs.getInt(1)) }
                st.executeQuery("SELECT COUNT(*) FROM events").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='measurements'").use { rs ->
                    assertTrue(rs.next()); assertEquals("index_measurements_start_ms", rs.getString(1))
                }
                // A new measurement after the migration gets the next id.
                st.execute("INSERT INTO measurements (start_ms, cell) VALUES (${100 * min}, 'h26833_12479')")
                st.executeQuery("SELECT id, end_ms FROM measurements WHERE cell IS NOT NULL").use { rs ->
                    assertTrue(rs.next()); assertEquals(3L, rs.getLong(1)); rs.getLong(2); assertTrue(rs.wasNull())
                }
            }
        }
    }

    @Test
    fun migrationOfAnEmptyDatabaseOnlyCreatesTheTable() {
        assertEquals(
            listOf(MigrationSql.CREATE_MEASUREMENTS_V6, MigrationSql.CREATE_MEASUREMENTS_INDEX_V6),
            MigrationSql.migrate5to6(emptyList()),
        )
    }

    @Test
    fun migration6to7AddsThePointAndKeepsEveryRow() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(MigrationSql.CREATE_MEASUREMENTS_V6)
                st.execute(MigrationSql.CREATE_MEASUREMENTS_INDEX_V6)
                st.execute("INSERT INTO measurements (start_ms, end_ms, cell) VALUES (0, 1000, NULL), (2000, NULL, 'h26833_12479')")
                MigrationSql.MIGRATE_6_7.forEach { st.execute(it) }
                val rows = ArrayList<List<Any?>>()
                st.executeQuery("SELECT id, start_ms, cell, point_e, point_n, sigma_m FROM measurements ORDER BY id").use { rs ->
                    while (rs.next()) rows += listOf(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4), rs.getObject(5), rs.getObject(6))
                }
                assertEquals(listOf(listOf<Any?>(1L, 0L, null, null, null, null), listOf<Any?>(2L, 2000L, "h26833_12479", null, null, null)), rows)
                // Same columns as a freshly created v7 table (what Room checks when it opens the database).
                fun columns(table: String): List<String> {
                    val out = ArrayList<String>()
                    st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                        while (rs.next()) out += "${rs.getString("name")}|${rs.getString("type")}|${rs.getInt("notnull")}|${rs.getInt("pk")}|${rs.getString("dflt_value")}"
                    }
                    return out
                }
                st.execute(MigrationSql.CREATE_MEASUREMENTS_V7.replace("`measurements`", "`fresh`"))
                assertEquals(columns("fresh"), columns("measurements"))
                st.execute("INSERT INTO measurements (start_ms, cell, point_e, point_n, sigma_m) VALUES (3000, 'h26824_12473', 2682437, 1247391, 50)")
                st.executeQuery("SELECT COUNT(*) FROM measurements WHERE point_e IS NOT NULL").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
            }
        }
    }

    @Test
    fun onlyRowsWithAPointAreShared() {
        val p = LocationNoise.Point(2682437, 1247391, 50)
        assertEquals(p, MeasurementEntity(id = 1, startMs = 0, cell = p.cell, pointE = p.e, pointN = p.n, sigmaM = 50).sharedPoint())
        // A row of a 0.6.0 test build before v7: a cell, no point → not shared any more.
        assertEquals(null, MeasurementEntity(id = 2, startMs = 0, cell = "h26833_12479").sharedPoint())
        assertEquals(null, MeasurementEntity(id = 3, startMs = 0, pointE = 1, pointN = null, sigmaM = 50).sharedPoint())
    }

    private val p1 = LocationNoise.Point(2682437, 1247391, 50)
    private val p4 = LocationNoise.Point(2683310, 1247980, 100)

    private fun shared(id: Long, startMs: Long, endMs: Long?, p: LocationNoise.Point) =
        MeasurementEntity(id = id, startMs = startMs, endMs = endMs, cell = p.cell, pointE = p.e, pointN = p.n, sigmaM = p.sigmaM)

    @Test
    fun rowsBecomeMeasurementsWithRunningAndKilledOnes() {
        val rows = listOf(
            shared(1, 0, 20 * min, p1),
            // Killed before «Stop»: its end is the end of its last minute.
            MeasurementEntity(id = 2, startMs = 60 * min, endMs = null, cell = null),
            // Started and stopped without a single minute: left out.
            MeasurementEntity(id = 3, startMs = 90 * min, endMs = 90 * min + 5_000, cell = null),
            // Running now.
            shared(4, 100 * min, null, p4),
        )
        val minutes = (0 until 20).map { it * min to (it + 1) * min } +
            (60 until 75).map { it * min to (it + 1) * min } +
            listOf(100 * min + 2_000 to 101 * min)
        val now = 101 * min + 30_000
        val list = MeasurementList.resolve(rows.shuffled(), minutes.shuffled(), now, running = true)
        assertEquals(
            listOf(
                Measurement(1, MeasurementSpan(0, 20 * min), p1, running = false),
                Measurement(2, MeasurementSpan(60 * min, 75 * min), null, running = false),
                Measurement(4, MeasurementSpan(100 * min, now), p4, running = true),
            ),
            list,
        )
        assertEquals(listOf("h26824_12473", null, "h26833_12479"), list.map { it.cell })
        // Not running (the app restarted after a kill): the open newest row ends at its last minute.
        assertEquals(MeasurementSpan(100 * min, 101 * min), MeasurementList.resolve(rows, minutes, now, running = false).last().span)
        // A running measurement shows even before its first minute is stored.
        val fresh = MeasurementList.resolve(listOf(MeasurementEntity(id = 9, startMs = now - 1_000)), emptyList(), now, running = true)
        assertEquals(listOf(Measurement(9, MeasurementSpan(now - 1_000, now), null, running = true)), fresh)
    }
}
