package ch.lessnoise.app.data

import ch.lessnoise.dsp.Acoustics
import ch.lessnoise.dsp.MinuteRecord
import ch.lessnoise.dsp.NoiseFloor
import java.sql.Connection
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The SQL of the noise floor estimate (0.6.5, [NoiseFloorSql], used by the Room DAO) gives exactly
 * the Kotlin reference [NoiseFloor.estimate], on the `minutes` table as Room creates it (unchanged
 * since v5, [MigrationSql.CREATE_MINUTES_V5]; SQLite via JDBC, as the other schema tests).
 */
class NoiseFloorSqlTest {
    private val default = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB

    private fun record(i: Int, l90: Double?, offset: Double, source: String, coverage: Double, laeq: Double?) = MinuteRecord(
        i * 60_000L, "m$i", 60.0, laeq ?: Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
        l90 ?: Double.NaN, 0, null, emptyMap(), 0, null, offset, source, false, validSeconds = 60.0 * coverage,
    )

    private fun insert(c: Connection, i: Int, l90: Double?, offset: Double, source: String, coverage: Double, laeq: Double?, mic: String? = null) {
        c.createStatement().use { st ->
            st.execute(
                "INSERT INTO minutes (startEpochMs, startIso, durationSeconds, laeqDb, l90Db, eventCount, categorySharesJson, " +
                    "classifierFrames, calibrationOffsetDb, audioSource, calibrated, validSeconds, coverage, clockCorrections" +
                    (if (mic != null) ", mic" else "") + ") VALUES " +
                    "(${i * 60_000L}, 'm$i', 60, ${laeq ?: "NULL"}, ${l90 ?: "NULL"}, 0, '{}', 0, $offset, '$source', 0, ${60 * coverage}, $coverage, 0" +
                    (if (mic != null) ", '$mic'" else "") + ")"
            )
        }
    }

    private fun sqlEstimate(c: Connection, source: String): NoiseFloor.Estimate? {
        val n = c.prepareStatement(NoiseFloorSql.COUNT.replace(":source", "?")).use { ps ->
            ps.setString(1, source); ps.executeQuery().use { it.next(); it.getInt(1) }
        }
        if (n < NoiseFloor.MIN_MINUTES) return null
        val raw = c.prepareStatement(NoiseFloorSql.RAW_L90_AT.replace(":source", "?").replace(":offset", "?")).use { ps ->
            ps.setString(1, source); ps.setInt(2, NoiseFloor.rankIndex(n))
            ps.executeQuery().use { if (it.next()) it.getDouble(1) else null }
        } ?: return null
        return NoiseFloor.Estimate(source, raw, n)
    }

    @Test
    fun sqlMatchesTheKotlinReference() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { it.execute(MigrationSql.CREATE_MINUTES_V10) }
            val rnd = Random(42)
            val records = ArrayList<MinuteRecord>()
            fun add(l90: Double?, offset: Double, source: String, coverage: Double = 1.0, laeq: Double? = (l90 ?: 40.0) + 5) {
                val i = records.size
                insert(c, i, l90, offset, source, coverage, laeq)
                records += record(i, l90, offset, source, coverage, laeq)
            }
            // 59 valid UNPROCESSED minutes: no floor yet, in SQL and in Kotlin.
            repeat(59) { add(25.0 + rnd.nextDouble() * 20, default, "UNPROCESSED") }
            assertNull(sqlEstimate(c, "UNPROCESSED"))
            assertNull(NoiseFloor.estimate("UNPROCESSED", records))
            // Invalid ones that must not count: low coverage, no LAeq, no L90.
            add(10.0, default, "UNPROCESSED", coverage = 0.3)
            add(10.0, default, "UNPROCESSED", laeq = null)
            add(null, default, "UNPROCESSED")
            assertNull(sqlEstimate(c, "UNPROCESSED"))
            // More minutes, some re-evaluated (another offset), a glitch, and the other source.
            repeat(140) { add(30.0 + rnd.nextDouble() * 25, if (it % 3 == 0) default + 9.6 else default, "UNPROCESSED") }
            add(-20.0, default, "UNPROCESSED")
            repeat(80) { add(40.0 + rnd.nextDouble() * 10, default, "VOICE_RECOGNITION") }
            for (source in listOf("UNPROCESSED", "VOICE_RECOGNITION")) {
                val sql = sqlEstimate(c, source)!!
                val ref = NoiseFloor.estimate(source, records)!!
                assertEquals(ref.minutes, sql.minutes, source)
                assertEquals(ref.floorRawDb, sql.floorRawDb, 1e-9, source)
            }
            assertEquals(200, sqlEstimate(c, "UNPROCESSED")!!.minutes)
            // The glitch (-20 dB(A) shown) is not the floor.
            assertEquals(true, sqlEstimate(c, "UNPROCESSED")!!.floorRawDb > -20.0 - default)
            // 0.7.2: minutes of another microphone (a quiet USB mic) do not count: the floor is the phone's.
            val before = sqlEstimate(c, "UNPROCESSED")!!
            repeat(300) { insert(c, 10_000 + it, 5.0, default, "UNPROCESSED", 1.0, 10.0, mic = "usb:0123456789abcdef") }
            assertEquals(before, sqlEstimate(c, "UNPROCESSED"))
        }
    }
}
