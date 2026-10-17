package ch.lessnoise.app.position

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.6.9, Labor·Karte only (src/testLaborKarte): the position as a second «unterwegs» signal. */
class PositionRuleTest {
    private val lat0 = 47.3769
    private val lon0 = 8.5417
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * cos(lat0 * PI / 180)

    private fun fix(t: Long, eastM: Double, northM: Double, acc: Double?) =
        PositionRule.Fix(t, lat0 + northM / mPerDegLat, lon0 + eastM / mPerDegLon, acc)

    @Test
    fun aStillPhoneWithEightyMetreDriftIsNeverMoving() {
        // 8 hours of fixes once a minute, scattered uniformly within 80 m, accuracy 20–60 m; 200 nights.
        var falseMoving = 0
        repeat(200) { seed ->
            val r = Random(seed)
            val rule = PositionRule()
            for (i in 0 until 480) {
                val a = r.nextDouble(0.0, 2 * PI)
                val d = 80 * sqrt(r.nextDouble())
                val t = rule.onFix(fix(i * 60_000L, d * cos(a), d * sin(a), r.nextDouble(20.0, 60.0)))
                if (t != null && t.moving) { falseMoving++; return@repeat }
            }
        }
        assertEquals(0, falseMoving)
        // The worst case allowed by ±80 m: two fixes 160 m apart (> 150 m) do not make a ride.
        val rule = PositionRule()
        assertNull(rule.onFix(fix(0, -80.0, 0.0, 10.0)))
        repeat(PositionRule.AWAY_FIXES - 1) { assertNull(rule.onFix(fix((it + 1) * 60_000L, 80.0, 0.0, 10.0))) }
        assertNull(rule.onFix(fix(10 * 60_000L, 0.0, 0.0, 10.0)))
        assertFalse(rule.moving)
    }

    @Test
    fun aTramRideIsMovingAndItsSpanStartsAtTheLastNearFix() {
        val rule = PositionRule()
        // 5 minutes at home, then a tram at 5 m/s (300 m per minute).
        for (i in 0..4) assertNull(rule.onFix(fix(i * 60_000L, 10.0 * (i % 2), 0.0, 30.0)))
        val transitions = (1..10).mapNotNull { k -> rule.onFix(fix((4 + k) * 60_000L, 300.0 * k, 0.0, 40.0)) }
        assertEquals(1, transitions.size)
        val t = transitions[0]
        assertTrue(t.moving)
        assertEquals((4 + PositionRule.AWAY_FIXES) * 60_000L, t.atMs)
        assertEquals(4 * 60_000L, t.spanStartMs, "the span starts at the last fix still near the start")
        assertEquals(150.0, t.thresholdM)
        assertTrue(rule.moving)
        assertEquals(3000.0, rule.distanceM!!, 5.0)
        // While MOVING the upload need not hold for the position (the open span covers new records).
        assertEquals(0L, rule.holdMs(20 * 60_000L))
    }

    @Test
    fun theThresholdIsTheSumOfTheAccuracyRadiiWhenLarger() {
        val rule = PositionRule()
        rule.onFix(fix(0, 0.0, 0.0, 200.0))
        // 350 m away with 200 + 200 m accuracy: not away.
        repeat(5) { assertNull(rule.onFix(fix((it + 1) * 60_000L, 350.0, 0.0, 200.0))) }
        assertFalse(rule.moving)
        // 450 m away: away.
        val t = (6..9).mapNotNull { rule.onFix(fix(it * 60_000L, 450.0, 0.0, 200.0)) }.single()
        assertEquals(400.0, t.thresholdM)
    }

    @Test
    fun poorOrStaleFixesAreIgnored() {
        val rule = PositionRule()
        assertNull(rule.onFix(fix(0, 0.0, 0.0, 600.0)))
        assertNull(rule.referenceMs, "a 600 m fix is not even the reference")
        assertNull(rule.onFix(fix(0, 0.0, 0.0, null)))
        assertNull(rule.onFix(fix(0, 0.0, 0.0, 30.0), ageMs = 5 * 60_000L))
        assertNull(rule.referenceMs)
        rule.onFix(fix(60_000L, 0.0, 0.0, 30.0))
        assertEquals(60_000L, rule.referenceMs)
        // Cell-tower fixes far away with accuracy 2 km: ignored, never MOVING.
        repeat(10) { assertNull(rule.onFix(fix((it + 2) * 60_000L, 1500.0, 0.0, 2000.0))) }
        assertFalse(rule.moving)
        assertEquals(1, rule.fixes)
        // Out of order: dropped.
        rule.onFix(fix(30 * 60_000L, 0.0, 0.0, 30.0))
        assertNull(rule.onFix(fix(20 * 60_000L, 5000.0, 0.0, 30.0)))
        assertEquals(0.0, rule.distanceM!!, 1.0)
        assertTrue(PositionRule.accept(500.0, 0))
        assertFalse(PositionRule.accept(500.1, 0))
    }

    @Test
    fun comingBackToTheStartEndsItAndTheReferenceIsNeverReplaced() {
        val rule = PositionRule()
        rule.onFix(fix(0, 0.0, 0.0, 30.0))
        val ref = rule.referenceMs
        (1..4).forEach { rule.onFix(fix(it * 60_000L, 1000.0 * it, 0.0, 30.0)) }
        assertTrue(rule.moving)
        // 30 minutes standing somewhere else: still MOVING (no new reference: the run's point is its start).
        (5..35).forEach { assertNull(rule.onFix(fix(it * 60_000L, 4000.0, 0.0, 30.0))) }
        assertTrue(rule.moving)
        assertEquals(ref, rule.referenceMs)
        // Back home: two near fixes end it.
        assertNull(rule.onFix(fix(40 * 60_000L, 20.0, 0.0, 30.0)))
        val end = rule.onFix(fix(41 * 60_000L, 10.0, 0.0, 30.0))
        assertNotNull(end)
        assertFalse(end.moving)
        assertEquals(ref, rule.referenceMs)
    }

    @Test
    fun theUploadHoldCoversWhatThePositionMayStillDecide() {
        val rule = PositionRule()
        assertEquals(0L, rule.holdMs(0L), "no fix: no hold")
        rule.onFix(fix(0, 0.0, 0.0, 30.0))
        rule.onFix(fix(60_000L, 10.0, 0.0, 30.0))
        assertEquals(60_000L - 60_000L + PositionRule.HOLD_MARGIN_MS, rule.holdMs(60_000L))
        // Away fixes not confirmed yet: everything since the last near fix is held.
        rule.onFix(fix(120_000L, 400.0, 0.0, 30.0))
        rule.onFix(fix(180_000L, 700.0, 0.0, 30.0))
        assertEquals(190_000L - 60_000L + PositionRule.HOLD_MARGIN_MS, rule.holdMs(190_000L))
        assertEquals(60_000L, rule.pendingAwaySinceMs(), "at «Stopp» now: the run's end since the last near fix is unterwegs")
        // The span the confirming away fix opens starts at the last near fix, i.e. before every held record.
        assertNull(rule.onFix(fix(240_000L, 1000.0, 0.0, 30.0)))
        val t = rule.onFix(fix(300_000L, 1300.0, 0.0, 30.0))!!
        assertEquals(60_000L, t.spanStartMs)
        // Capped: a fix that never comes cannot hold for ever.
        val r2 = PositionRule()
        r2.onFix(fix(0, 0.0, 0.0, 30.0))
        assertEquals(PositionRule.MAX_HOLD_MS, r2.holdMs(3_600_000L))
    }

    @Test
    fun noLocationPermissionMeansMotionOnly() {
        assertNull(PositionWatch.foregroundType(granted = false, sdk = 34))
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, PositionWatch.foregroundType(granted = true, sdk = 34))
        assertEquals(0, PositionWatch.foregroundType(granted = true, sdk = 28))
        // start() returns null without permission, and the service only passes the type when it is granted.
        val watch = File(app(), "src/laborKarte/kotlin/ch/lessnoise/app/position/PositionWatch.kt").readText()
        assertTrue(watch.contains("if (!granted(app)) return null"))
        val motion = File(app(), "src/karte/kotlin/ch/lessnoise/app/motion/MotionWatch.kt").readText()
        assertTrue(motion.contains("if (labor && position) {"))
        assertTrue(motion.contains("if (BuildConfig.LABOR_UNTERWEGS) PositionWatch.foregroundType(context) else null"))
        val service = File(app(), "src/main/kotlin/ch/lessnoise/app/service/MeasurementService.kt").readText()
        assertTrue(service.contains("motion = MotionWatch.start(this@MeasurementService, container, position = positionAllowed)"))
        assertTrue(service.contains("ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), first or positionType)"))
        // Low power: balanced fused provider or network, about once a minute; never a GPS request, never a permission prompt.
        assertTrue(watch.contains("QUALITY_BALANCED_POWER_ACCURACY"))
        assertTrue(watch.contains("LocationManager.NETWORK_PROVIDER"))
        assertEquals(60_000L, PositionWatch.INTERVAL_MS)
        for (bad in listOf("GPS_PROVIDER", "QUALITY_HIGH_ACCURACY", "requestPermissions", "ACCESS_BACKGROUND_LOCATION", "getLastKnownLocation")) {
            assertFalse(watch.contains(bad), bad)
        }
    }

    @Test
    fun positionsNeverReachTheDatabaseOrAnUpload() {
        val src = File(app(), "src")
        // 1. Only the position package touches a fix; it imports no project class (no Room, no upload, no Labor log).
        val position = File(src, "laborKarte/kotlin/ch/lessnoise/app/position").walkTopDown().filter { it.isFile }.toList()
        assertEquals(listOf("PositionRule.kt", "PositionWatch.kt"), position.map { it.name }.sorted())
        for (f in position) {
            val t = f.readText()
            assertFalse(Regex("""import ch\.lessnoise\.""").containsMatchIn(t), "${f.name} imports project code")
            for (bad in listOf("Room", "Dao", "SharedPreferences", "File(", "Log.", "UploadClient", "Payloads", "ManifestFile")) assertFalse(t.contains(bad), "${f.name}: $bad")
        }
        // 2. What leaves PositionRule/PositionWatch carries no coordinate: the transition and the handle.
        val rule = File(src, "laborKarte/kotlin/ch/lessnoise/app/position/PositionRule.kt").readText()
        val tr = Regex("""data class Transition\(([^)]*)\)""").find(rule)!!.groupValues[1]
        assertFalse(Regex("(?i)latitude|longitude|\\blat\\b|\\blon\\b").containsMatchIn(tr), tr)
        val watch = File(src, "laborKarte/kotlin/ch/lessnoise/app/position/PositionWatch.kt").readText()
        val handle = watch.substring(watch.indexOf("interface Handle"), watch.indexOf("private fun granted"))
        assertFalse(Regex("(?i)latitude|longitude|Location\\b").containsMatchIn(handle), handle)
        // 3. The motion watch, the Labor log and the database code never see a coordinate.
        val readers = listOf(
            "karte/kotlin/ch/lessnoise/app/motion/MotionWatch.kt", "karte/kotlin/ch/lessnoise/app/motion/Unterwegs.kt",
            "labor/kotlin/ch/lessnoise/app/labor/Manifest.kt", "labor/kotlin/ch/lessnoise/app/edition/Edition.kt",
            "main/kotlin/ch/lessnoise/app/data/Database.kt", "main/kotlin/ch/lessnoise/app/data/Repositories.kt",
        )
        for (p in readers) assertFalse(Regex("""\blatitude\b|\blongitude\b""").containsMatchIn(File(src, p).readText()), p)
        // 4. The upload package knows nothing of it.
        for (f in File(src, "main/kotlin/ch/lessnoise/app/upload").walkTopDown().filter { it.isFile }) {
            val t = f.readText()
            for (bad in listOf("PositionWatch", "PositionRule", "unterwegsReadout", "distanceM")) assertFalse(t.contains(bad), "${f.name}: $bad")
        }
        // 5. The database schema has no new table or column: still v9 with moving_spans (start/end only).
        val db = File(src, "main/kotlin/ch/lessnoise/app/data/Database.kt").readText()
        assertTrue(db.contains("version = 9, exportSchema = false"))
    }

    @Test
    fun theMinuteLineHasNoCoordinates() {
        val line = ch.lessnoise.app.labor.ManifestLines.motionMinute(
            "none", 1_760_000_040_000L, java.time.ZoneId.of("Europe/Zurich"), 0.1234, 0.5, 60, 400, "MOVING", motion = false, position = true,
        )
        assertTrue(line.startsWith("{\"type\":\"motion_minute\",\"session\":\"none\",\"time\":\"2025-10-09T10:54:00.000+02:00\""), line)
        assertTrue(line.contains("\"median\":0.123,\"max\":0.5,\"seconds\":60,\"distanceM\":400,\"state\":\"MOVING\",\"byMotion\":false,\"byPosition\":true"), line)
        assertFalse(Regex("(?i)lat|lon").containsMatchIn(line), line)
    }

    private fun app(): File = listOf(File("."), File("app")).first { File(it, "src/laborKarte").isDirectory }
}
