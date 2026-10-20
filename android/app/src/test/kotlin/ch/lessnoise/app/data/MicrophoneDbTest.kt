package ch.lessnoise.app.data

import ch.lessnoise.app.audio.MicChoice
import ch.lessnoise.app.upload.Payloads
import ch.lessnoise.dsp.calibration.Recalibration
import ch.lessnoise.dsp.geo.LocationNoise
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 0.7.2: calibrations, minutes and events carry the microphone (MicChoice.key). The migration 9 → 10
 * makes every existing row «builtin»; a calibration belongs to one microphone; a minute sends its
 * microphone's kind in `audio_source`.
 */
class MicrophoneDbTest {
    private val usbKey = "usb:0123456789abcdef"

    private fun columns(c: Connection, table: String): List<String> =
        c.createStatement().use { st -> st.executeQuery("PRAGMA table_info(`$table`)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } } }

    private fun v9(c: Connection) = c.createStatement().use { st ->
        st.execute(MigrationSql.CREATE_MINUTES_V5)
        st.execute(MigrationSql.CREATE_EVENTS_V5)
        st.execute(MigrationSql.CREATE_CALIBRATIONS_V9 + ")")
        st.execute(MigrationSql.CREATE_CALIBRATIONS_INDEX)
    }

    private fun insertCalibration(c: Connection, id: Long, offset: Double, created: Long, mic: String? = null, source: String = "UNPROCESSED") =
        c.createStatement().use { st ->
            st.execute(
                "INSERT INTO calibrations (id, deviceModel, audioSource, offsetDb, method, createdEpochMs, createdIso, notes, warnings" +
                    (if (mic != null) ", mic" else "") + ") VALUES ($id, 'Google Pixel 8', '$source', $offset, 'reference_meter', $created, '', '', ''" +
                    (if (mic != null) ", '$mic'" else "") + ")"
            )
        }

    private fun latest(c: Connection, mic: String, source: String = "UNPROCESSED"): Long? {
        val sql = "SELECT id FROM calibrations WHERE " +
            CalibrationSql.LATEST.replace(":model", "?").replace(":source", "?").replace(":mic", "?")
        return c.prepareStatement(sql).use { ps ->
            ps.setString(1, "Google Pixel 8"); ps.setString(2, source); ps.setString(3, mic)
            ps.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }
    }

    @Test
    fun migration9to10MakesEveryRowBuiltInAndKeepsIt() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            v9(c)
            insertCalibration(c, 1, 121.0, 1000)
            insertCalibration(c, 2, 118.5, 2000)
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO minutes (startEpochMs, startIso, durationSeconds, eventCount, categorySharesJson, classifierFrames, " +
                        "calibrationOffsetDb, audioSource, calibrated, validSeconds, coverage, clockCorrections) VALUES " +
                        "(0, 'm', 60, 0, '{}', 0, 118.5, 'UNPROCESSED', 1, 60, 1, 0)"
                )
                st.execute(
                    "INSERT INTO events (startEpochMs, startIso, durationSeconds, lafMaxDb, selDb, backgroundDb, thresholdDb, dominantScore, " +
                        "topLabelsJson, classifierFrames, audioSource, calibrated) VALUES (0, 'e', 1, 60, 61, 40, 6.5, 0.5, '[]', 1, 'UNPROCESSED', 1)"
                )
                MigrationSql.MIGRATE_9_10.forEach { st.execute(it) }
                for (table in listOf("calibrations", "minutes", "events")) {
                    st.executeQuery("SELECT DISTINCT mic FROM `$table`").use { rs ->
                        val mics = buildList { while (rs.next()) add(rs.getString(1)) }
                        assertEquals(listOf("builtin"), mics, table)
                    }
                }
                st.executeQuery("SELECT COUNT(*), MAX(mic_label) FROM calibrations").use { rs ->
                    rs.next(); assertEquals(2, rs.getInt(1)); assertNull(rs.getString(2))
                }
            }
            // The migrated tables have exactly Room's v10 columns.
            c.createStatement().use { st ->
                st.execute(MigrationSql.CREATE_MINUTES_V10.replace("`minutes`", "`fresh_minutes`"))
                st.execute(MigrationSql.CREATE_EVENTS_V10.replace("`events`", "`fresh_events`"))
                st.execute(MigrationSql.CREATE_CALIBRATIONS_V10.replace("`calibrations`", "`fresh_calibrations`"))
            }
            assertEquals(columns(c, "fresh_minutes"), columns(c, "minutes"))
            assertEquals(columns(c, "fresh_events"), columns(c, "events"))
            assertEquals(columns(c, "fresh_calibrations"), columns(c, "calibrations"))
            // The built-in microphone's calibration is still the newest one of 0.7.1.
            assertEquals(2L, latest(c, MicChoice.BUILTIN))
            assertNull(latest(c, usbKey))
        }
    }

    @Test
    fun calibrationLookupIsPerMicrophone() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { it.execute(MigrationSql.CREATE_CALIBRATIONS_V10) }
            insertCalibration(c, 1, 121.0, 1000, MicChoice.BUILTIN)
            insertCalibration(c, 2, 110.0, 2000, usbKey)
            insertCalibration(c, 3, 119.0, 1500, MicChoice.BUILTIN)
            insertCalibration(c, 4, 112.0, 3000, "usb:fedcba9876543210")
            insertCalibration(c, 5, 117.0, 4000, MicChoice.BUILTIN, source = "VOICE_RECOGNITION")
            // The newest of the same microphone (and source); a newer one of another microphone does not count.
            assertEquals(3L, latest(c, MicChoice.BUILTIN))
            assertEquals(2L, latest(c, usbKey))
            assertEquals(4L, latest(c, "usb:fedcba9876543210"))
            assertEquals(5L, latest(c, MicChoice.BUILTIN, "VOICE_RECOGNITION"))
            // A microphone never calibrated: none — the measurement is uncalibrated (default offset).
            assertNull(latest(c, "bluetooth:0000000000000000"))
            assertEquals(false, CalibrationRepository.Active(null).calibrated)
        }
    }

    private fun minute(mic: String) = MinuteEntity(
        id = 7, startEpochMs = 0, startIso = "2026-10-20T12:00:00+02:00", durationSeconds = 60.0,
        laeqDb = 50.0, lafMaxDb = 60.0, lafMinDb = 40.0, l1Db = 58.0, l10Db = 54.0, l50Db = 49.0, l90Db = 44.0,
        eventCount = 0, dominantCategory = null, categorySharesJson = "{}", classifierFrames = 0, calibrationId = null,
        calibrationOffsetDb = 121.95, audioSource = "UNPROCESSED", calibrated = false, validSeconds = 60.0, coverage = 1.0,
        clockCorrections = 0, mic = mic,
    )

    @Test
    fun theMinuteSendsTheMicrophoneKindNeverItsKey() {
        val p = LocationNoise.Point(2682437, 1247391, 50)
        assertEquals("UNPROCESSED", Payloads.minute(minute(MicChoice.BUILTIN), p)["audio_source"]!!.jsonPrimitive.content)
        assertEquals("UNPROCESSED+usb", Payloads.minute(minute(usbKey), p)["audio_source"]!!.jsonPrimitive.content)
        assertEquals("UNPROCESSED+bluetooth", Payloads.minute(minute("bluetooth:0123456789abcdef"), p)["audio_source"]!!.jsonPrimitive.content)
        val body = Payloads.minute(minute(usbKey), p).toString()
        assertEquals(false, "0123456789abcdef" in body, body)
        // Nothing else changed: the same fields as for the built-in microphone.
        assertEquals(Payloads.minute(minute(MicChoice.BUILTIN), p).keys, Payloads.minute(minute(usbKey), p).keys)
    }

    @Test
    fun reEvaluationKeepsTheMicrophone() {
        val target = Recalibration.Target(9, 115.0, "UNPROCESSED")
        assertEquals(usbKey, RecalibrationMapping.minute(minute(usbKey), target).mic)
        val e = EventEntity(
            id = 3, startEpochMs = 0, startIso = "e", durationSeconds = 1.0, lafMaxDb = 60.0, selDb = 61.0, backgroundDb = 40.0,
            thresholdDb = 6.5, dominantCategory = "road_traffic", dominantScore = 0.5f, topLabelsJson = "[]", classifierFrames = 1,
            calibrationId = null, audioSource = "UNPROCESSED", calibrated = false, mic = usbKey,
        )
        assertEquals(usbKey, RecalibrationMapping.event(e, emptyMap(), target).mic)
    }
}
