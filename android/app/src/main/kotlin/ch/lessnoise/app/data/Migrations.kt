package ch.lessnoise.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ch.lessnoise.dsp.MeasurementSplitter

/**
 * SQL for schema migrations, kept free of Android types so it can be tested on the JVM against
 * SQLite (see app/src/test/.../AppJvmTest.kt).
 */
object MigrationSql {
    /** v2 `minutes` table, exactly as Room generates it (verified by a unit test). */
    const val CREATE_MINUTES_V2 =
        "CREATE TABLE IF NOT EXISTS `minutes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `laeqDb` REAL, `lafMaxDb` REAL, `lafMinDb` REAL, " +
            "`l1Db` REAL, `l10Db` REAL, `l50Db` REAL, `l90Db` REAL, `eventCount` INTEGER NOT NULL, `dominantCategory` TEXT, " +
            "`categorySharesJson` TEXT NOT NULL, `classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, " +
            "`calibrationOffsetDb` REAL NOT NULL, `audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL, " +
            "`validSeconds` REAL NOT NULL, `coverage` REAL NOT NULL, `clockCorrections` INTEGER NOT NULL)"

    /** v3 `events` table, exactly as Room generates it (verified by a unit test). */
    const val CREATE_EVENTS_V3 =
        "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `lafMaxDb` REAL NOT NULL, `selDb` REAL NOT NULL, " +
            "`backgroundDb` REAL NOT NULL, `thresholdDb` REAL NOT NULL, `dominantCategory` TEXT, `dominantScore` REAL NOT NULL, " +
            "`topLabelsJson` TEXT NOT NULL, `classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, " +
            "`audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL, `min_level_db` REAL)"

    private const val V1_COLUMNS = "`id`, `startEpochMs`, `startIso`, `durationSeconds`, `laeqDb`, `lafMaxDb`, `lafMinDb`, " +
        "`l1Db`, `l10Db`, `l50Db`, `l90Db`, `eventCount`, `dominantCategory`, `categorySharesJson`, `classifierFrames`, " +
        "`calibrationId`, `calibrationOffsetDb`, `audioSource`, `calibrated`"

    /**
     * v1 → v2: level columns become nullable (minutes without valid audio), and validSeconds /
     * coverage / clockCorrections are added. v1 had no silence detection: a v1 minute whose LAeq
     * is below 0 dB(A) can only come from a silenced microphone, so it gets coverage 0 (and is
     * left out of summaries); all others are assumed fully valid.
     */
    val MIGRATE_1_2: List<String> = listOf(
        CREATE_MINUTES_V2.replace("`minutes`", "`minutes_v2`"),
        "INSERT INTO `minutes_v2` ($V1_COLUMNS, `validSeconds`, `coverage`, `clockCorrections`) " +
            "SELECT $V1_COLUMNS, CASE WHEN `laeqDb` < 0 THEN 0 ELSE `durationSeconds` END, " +
            "CASE WHEN `laeqDb` < 0 THEN 0 ELSE 1 END, 0 FROM `minutes`",
        "DROP TABLE `minutes`",
        "ALTER TABLE `minutes_v2` RENAME TO `minutes`",
        "CREATE INDEX IF NOT EXISTS `index_minutes_startEpochMs` ON `minutes` (`startEpochMs`)",
    )

    /**
     * v2 → v3: events store the absolute LAFmax floor that was in force (`min_level_db`). Events
     * from before have none (NULL); the app applies the current floor to them at read time.
     */
    val MIGRATE_2_3: List<String> = listOf(
        "ALTER TABLE `events` ADD COLUMN `min_level_db` REAL",
    )

    /** v4 `minutes` table, exactly as Room generates it for v0.3.2 – v0.3.4. */
    const val CREATE_MINUTES_V4_PREFIX =
        "CREATE TABLE IF NOT EXISTS `minutes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `laeqDb` REAL, `lafMaxDb` REAL, `lafMinDb` REAL, " +
            "`l1Db` REAL, `l10Db` REAL, `l50Db` REAL, `l90Db` REAL, `eventCount` INTEGER NOT NULL, `dominantCategory` TEXT, " +
            "`categorySharesJson` TEXT NOT NULL, `classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, " +
            "`calibrationOffsetDb` REAL NOT NULL, `audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL, " +
            "`validSeconds` REAL NOT NULL, `coverage` REAL NOT NULL, `clockCorrections` INTEGER NOT NULL, " +
            "`orig_laeq_db` REAL, `orig_lafmax_db` REAL, `orig_lafmin_db` REAL, `orig_l1_db` REAL, `orig_l10_db` REAL, " +
            "`orig_l50_db` REAL, `orig_l90_db` REAL, `recalibrated_from_id` TEXT, `recalibration_offset_db` REAL"
    const val CREATE_MINUTES_V4 = "$CREATE_MINUTES_V4_PREFIX)"

    /** v4 `events` table, exactly as Room generates it for v0.3.2 – v0.3.4. */
    const val CREATE_EVENTS_V4_PREFIX =
        "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `lafMaxDb` REAL NOT NULL, `selDb` REAL NOT NULL, " +
            "`backgroundDb` REAL NOT NULL, `thresholdDb` REAL NOT NULL, `dominantCategory` TEXT, `dominantScore` REAL NOT NULL, " +
            "`topLabelsJson` TEXT NOT NULL, `classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, " +
            "`audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL, `min_level_db` REAL, " +
            "`orig_lafmax_db` REAL, `orig_sel_db` REAL, `orig_background_db` REAL, `recalibrated_from_id` TEXT, " +
            "`recalibration_offset_db` REAL"
    const val CREATE_EVENTS_V4 = "$CREATE_EVENTS_V4_PREFIX)"

    /**
     * v3 → v4: re-evaluation of stored data with a later calibration. Minutes and events keep their
     * original level values (orig_*) and the calibration they were measured with
     * (recalibrated_from_id, recalibration_offset_db). All NULL until a record is re-evaluated.
     */
    val MIGRATE_3_4: List<String> =
        listOf("orig_laeq_db", "orig_lafmax_db", "orig_lafmin_db", "orig_l1_db", "orig_l10_db", "orig_l50_db", "orig_l90_db")
            .map { "ALTER TABLE `minutes` ADD COLUMN `$it` REAL" } +
            listOf(
                "ALTER TABLE `minutes` ADD COLUMN `recalibrated_from_id` TEXT",
                "ALTER TABLE `minutes` ADD COLUMN `recalibration_offset_db` REAL",
            ) +
            listOf("orig_lafmax_db", "orig_sel_db", "orig_background_db").map { "ALTER TABLE `events` ADD COLUMN `$it` REAL" } +
            listOf(
                "ALTER TABLE `events` ADD COLUMN `recalibrated_from_id` TEXT",
                "ALTER TABLE `events` ADD COLUMN `recalibration_offset_db` REAL",
            )

    /**
     * v4 → v5 (v0.4.0, detector v2): events gain the features (local floor, excess, rise/decay,
     * jaggedness, mid-band rise, LF share and flutter, the wind flag and the shape), minutes the
     * median local floor and the number of wind events. Existing rows: NULL features, wind 0,
     * wind_event_count 0 (no wind detection before v0.4.0).
     */
    val MIGRATE_4_5: List<String> =
        listOf("local_floor_db", "excess_db", "rise_s", "decay_s", "jaggedness", "mid_band_rise_db", "lf_share", "lf_flutter_db")
            .map { "ALTER TABLE `events` ADD COLUMN `$it` REAL" } +
            listOf(
                "ALTER TABLE `events` ADD COLUMN `wind` INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE `events` ADD COLUMN `shape` TEXT",
                "ALTER TABLE `minutes` ADD COLUMN `local_floor_db` REAL",
                "ALTER TABLE `minutes` ADD COLUMN `wind_event_count` INTEGER NOT NULL DEFAULT 0",
            )

    /** v5 `minutes` table, exactly as Room generates it (verified by a unit test). */
    const val CREATE_MINUTES_V5 = CREATE_MINUTES_V4_PREFIX +
        ", `local_floor_db` REAL, `wind_event_count` INTEGER NOT NULL DEFAULT 0)"

    /** v6 `measurements` table and its index, exactly as Room generates them (verified by a unit test). */
    const val CREATE_MEASUREMENTS_V6 =
        "CREATE TABLE IF NOT EXISTS `measurements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `start_ms` INTEGER NOT NULL, " +
            "`end_ms` INTEGER, `cell` TEXT)"
    const val CREATE_MEASUREMENTS_INDEX_V6 =
        "CREATE INDEX IF NOT EXISTS `index_measurements_start_ms` ON `measurements` (`start_ms`)"

    /** The query the migration 5 → 6 reads the stored minutes with. */
    const val SELECT_MINUTE_SPANS = "SELECT `startEpochMs`, `durationSeconds` FROM `minutes`"

    /**
     * v5 → v6 (app 0.6.0, «Messungen» instead of fixed 22–06 periods): the `measurements` table, filled from the
     * minutes already stored: [minutes] (start, duration in seconds) are split where more than
     * 10 minutes lie between two of them (MeasurementSplitter.split), each run becomes one
     * finished measurement without a cell (nothing recorded before 0.6.0 is shared per measurement).
     * Minutes and events themselves are not touched.
     */
    fun migrate5to6(minutes: List<Pair<Long, Double>>): List<String> =
        listOf(CREATE_MEASUREMENTS_V6, CREATE_MEASUREMENTS_INDEX_V6) +
            MeasurementSplitter.split(minutes).map { s ->
                "INSERT INTO `measurements` (`start_ms`, `end_ms`, `cell`) VALUES (${s.startMs}, ${s.endMs}, NULL)"
            }

    /**
     * v6 → v7 (app 0.6.2, location noise): the shifted point of a shared measurement and its σ.
     * Existing rows keep everything and get NULL points; a row of 0.6.0 or 0.6.1 that has a
     * cell but no point is therefore no longer sent (the app never sends the true hectare again).
     */
    val MIGRATE_6_7: List<String> = listOf(
        "ALTER TABLE `measurements` ADD COLUMN `point_e` INTEGER",
        "ALTER TABLE `measurements` ADD COLUMN `point_n` INTEGER",
        "ALTER TABLE `measurements` ADD COLUMN `sigma_m` INTEGER",
    )

    /** v7 `measurements` table, exactly as Room generates it (verified by a unit test). */
    const val CREATE_MEASUREMENTS_V7 =
        "CREATE TABLE IF NOT EXISTS `measurements` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `start_ms` INTEGER NOT NULL, " +
            "`end_ms` INTEGER, `cell` TEXT, `point_e` INTEGER, `point_n` INTEGER, `sigma_m` INTEGER)"

    /** v5 `events` table, exactly as Room generates it (verified by a unit test). */
    const val CREATE_EVENTS_V5 = CREATE_EVENTS_V4_PREFIX +
        ", `local_floor_db` REAL, `excess_db` REAL, `rise_s` REAL, `decay_s` REAL, `jaggedness` REAL, " +
        "`mid_band_rise_db` REAL, `lf_share` REAL, `lf_flutter_db` REAL, `wind` INTEGER NOT NULL DEFAULT 0, `shape` TEXT)"
}

/**
 * Records a re-evaluation with a calibration changes: same audio source, measured without or with
 * another calibration — the SQL form of `Recalibration.inScope` (checked by a unit test).
 */
object RecalibrationSql {
    const val SCOPE = "audioSource = :source AND (calibrationId IS NULL OR calibrationId != :calibrationId)"
}

/**
 * App 0.6.3: the shifted point found after «Starten» goes to the measurement that is still running
 * (no end) and has no point yet; an ended measurement is never shared afterwards (checked by a unit test).
 */
object AttachPointSql {
    const val UPDATE = "UPDATE measurements SET cell = :cell, point_e = :pointE, point_n = :pointN, sigma_m = :sigmaM " +
        "WHERE start_ms = :startMs AND end_ms IS NULL AND point_e IS NULL"
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_1_2.forEach { db.execSQL(it) }
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_2_3.forEach { db.execSQL(it) }
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_3_4.forEach { db.execSQL(it) }
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_4_5.forEach { db.execSQL(it) }
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val minutes = ArrayList<Pair<Long, Double>>()
        db.query(MigrationSql.SELECT_MINUTE_SPANS).use { c ->
            while (c.moveToNext()) minutes += c.getLong(0) to c.getDouble(1)
        }
        MigrationSql.migrate5to6(minutes).forEach { db.execSQL(it) }
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_6_7.forEach { db.execSQL(it) }
    }
}
