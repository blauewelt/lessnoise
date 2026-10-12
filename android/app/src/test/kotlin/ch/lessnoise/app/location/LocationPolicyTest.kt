package ch.lessnoise.app.location

import ch.lessnoise.app.location.LocationPolicy.Outcome
import ch.lessnoise.app.location.LocationPolicy.Permission
import ch.lessnoise.app.ui.LOCATION_NEVER_SENT
import ch.lessnoise.app.ui.SHARE_SENT_BULLETS
import ch.lessnoise.app.ui.SIGMA_APPLIES
import ch.lessnoise.app.ui.SIGMA_EXPLANATION
import ch.lessnoise.app.ui.pastedText
import ch.lessnoise.app.ui.shareLocationBullets
import ch.lessnoise.app.ui.sharedMeasurementLine
import ch.lessnoise.app.ui.sigmaLabel
import ch.lessnoise.app.upload.CellInput
import ch.lessnoise.dsp.geo.LocationNoise
import ch.lessnoise.dsp.geo.Lv95
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * One location fix (0.6.0: at «Start» of a shared measurement, and «Laut! Melden»): lat/lon → LV95,
 * the location noise at «Start», and when a fix is accepted. Pure JVM.
 */
class LocationPolicyTest {

    private val secret = ByteArray(LocationNoise.SECRET_BYTES) { (it * 7).toByte() }
    private val noise50 = LocationPolicy.Noise(secret, 50)

    /** What «Start» must produce for a fix at (lat, lon): the true LV95 point moved by its hectare's offset. */
    private fun expected(lat: Double, lon: Double, sigma: Int = 50): LocationNoise.Point {
        val p = Lv95.wgs84ToLv95(lat, lon)
        return LocationNoise.shift(secret, p.e, p.n, sigma)
    }

    /**
     * Zürich points with the hectares computed by the website's docs/map/lv95.js (the fixture of
     * dsp/.../geo/Lv95Test.kt), so app and map agree; the first two are also in UploadTest.
     */
    private val zuerich = listOf(
        Triple(47.3769, 8.5417, "h26833_12479"),
        Triple(47.40717847, 8.51060962, "h26809_12512"), // ETH2 (server/DESIGN.md §3)
        Triple(47.3667, 8.5500, "h26839_12468"),
        Triple(47.3910, 8.5130, "h26811_12494"),
        Triple(47.3502, 8.5203, "h26817_12449"),
        Triple(47.4230, 8.5040, "h26803_12530"),
    )

    @Test
    fun latLonBecomesTheSamePointAsPastedCoordinates() {
        for ((lat, lon, cell) in zuerich) {
            val en = Lv95.wgs84ToLv95(lat, lon)
            assertEquals(CellInput.Parsed.Ok(cell, "WGS84", en.e, en.n), CellInput.fromLatLon(lat, lon))
            val pasted = CellInput.parse("$lat, $lon") as CellInput.Parsed.Ok
            assertEquals(cell, pasted.cell)
            val shifted = expected(lat, lon)
            assertEquals(Outcome.Shifted(shifted, 12), LocationPolicy.decide(lat, lon, 11.4, precise = true, noise = noise50))
            // Pasting the same coordinates gives the same shifted point (no accuracy).
            assertEquals(Outcome.Shifted(shifted, null), LocationPolicy.fromPasted(pasted, noise50))
        }
        assertIs<CellInput.Parsed.Error>(CellInput.fromLatLon(48.8566, 2.3522)) // Paris
        assertIs<CellInput.Parsed.Error>(CellInput.fromLatLon(8.5417, 47.3769)) // swapped: a fix is never swapped
        assertIs<CellInput.Parsed.Error>(CellInput.fromLatLon(Double.NaN, 8.5))
        assertIs<CellInput.Parsed.Error>(CellInput.fromLatLon(0.0, 0.0))
    }

    @Test
    fun accuracyAndPermissionDecideWhetherAPointIsSet() {
        val (lat, lon, _) = zuerich[0]
        val p = expected(lat, lon)
        // «Ungefähr» never sets a point, however accurate the number looks.
        assertEquals(Outcome.ApproximateOnly, LocationPolicy.decide(lat, lon, 5.0, precise = false, noise = noise50))
        assertEquals(Outcome.Shifted(p, 1), LocationPolicy.decide(lat, lon, 0.0, precise = true, noise = noise50))
        assertEquals(Outcome.Shifted(p, 13), LocationPolicy.decide(lat, lon, 12.01, precise = true, noise = noise50))
        assertEquals(Outcome.Shifted(p, 75), LocationPolicy.decide(lat, lon, 75.0, precise = true, noise = noise50))
        assertEquals(Outcome.TooInaccurate(76), LocationPolicy.decide(lat, lon, 75.1, precise = true, noise = noise50))
        assertEquals(Outcome.TooInaccurate(1500), LocationPolicy.decide(lat, lon, 1500.0, precise = true, noise = noise50))
        assertEquals(Outcome.TooInaccurate(null), LocationPolicy.decide(lat, lon, null, precise = true, noise = noise50))
        assertEquals(Outcome.TooInaccurate(null), LocationPolicy.decide(lat, lon, Double.NaN, precise = true, noise = noise50))
        assertEquals(Outcome.TooInaccurate(null), LocationPolicy.decide(lat, lon, -1.0, precise = true, noise = noise50))
        assertIs<Outcome.OutsideSwitzerland>(LocationPolicy.decide(48.8566, 2.3522, 5.0, precise = true, noise = noise50))

        assertEquals(Permission.PRECISE, LocationPolicy.permission(fineGranted = true, coarseGranted = true))
        assertEquals(Permission.PRECISE, LocationPolicy.permission(fineGranted = true, coarseGranted = false))
        assertEquals(Permission.APPROXIMATE_ONLY, LocationPolicy.permission(fineGranted = false, coarseGranted = true))
        assertEquals(Permission.DENIED, LocationPolicy.permission(fineGranted = false, coarseGranted = false))
    }

    @Test
    fun startSendsAShiftedPointThatIsStableForThePlace() {
        val (lat, lon, cell) = zuerich[0]
        val o = LocationPolicy.decide(lat, lon, 10.0, precise = true, noise = noise50) as Outcome.Shifted
        val truePoint = Lv95.wgs84ToLv95(lat, lon)
        // The result is the shifted point, not the true one, with the chosen σ …
        assertEquals(50, o.point.sigmaM)
        assertFalse(o.point.e == Math.round(truePoint.e).toInt() && o.point.n == Math.round(truePoint.n).toInt())
        // … the same at every «Start» at this place (so averaging measurements does not remove it) …
        repeat(3) { assertEquals(o, LocationPolicy.decide(lat, lon, 10.0, precise = true, noise = noise50)) }
        // … also for a slightly different fix in the same hectare: the same offset.
        val nearby = LocationPolicy.decide(lat + 0.00005, lon, 10.0, precise = true, noise = noise50) as Outcome.Shifted
        assertEquals(cell, CellInput.fromLatLon(lat + 0.00005, lon).let { (it as CellInput.Parsed.Ok).cell })
        val d1 = LocationNoise.offset(secret, cell, 50)
        assertEquals(Math.round(truePoint.e + d1.e).toInt(), o.point.e)
        assertEquals(Math.round(truePoint.n + d1.n).toInt(), o.point.n)
        assertTrue(kotlin.math.abs((nearby.point.n - o.point.n) - 5.6) < 1.5, "${nearby.point} vs ${o.point}") // 0.00005° ≈ 5.6 m north
        // Another σ or another secret: another point.
        val o100 = LocationPolicy.decide(lat, lon, 10.0, precise = true, noise = LocationPolicy.Noise(secret, 100)) as Outcome.Shifted
        assertEquals(100, o100.point.sigmaM)
        val other = LocationPolicy.Noise(ByteArray(LocationNoise.SECRET_BYTES) { 1 }, 50)
        assertFalse(o == LocationPolicy.decide(lat, lon, 10.0, precise = true, noise = other))
        // A pasted hectare id stands for its centre.
        val hid = CellInput.parse(cell) as CellInput.Parsed.Ok
        val sw = Lv95.parseCell(cell)!!
        assertEquals(sw.e + 50, hid.e); assertEquals(sw.n + 50, hid.n)
        assertEquals(LocationNoise.shift(secret, sw.e + 50, sw.n + 50, 50), LocationPolicy.fromPasted(hid, noise50).point)
        // The noise needs a 32-byte secret and one of the four σ.
        kotlin.test.assertFailsWith<IllegalArgumentException> { LocationPolicy.Noise(secret, 10) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { LocationPolicy.Noise(ByteArray(8), 50) }
    }

    @Test
    fun lastKnownOnlyIfFreshAndAccurate() {
        assertTrue(LocationPolicy.acceptLastKnown(ageMs = 0, accuracyM = 10.0))
        assertTrue(LocationPolicy.acceptLastKnown(ageMs = 120_000, accuracyM = 50.0))
        assertFalse(LocationPolicy.acceptLastKnown(ageMs = 120_001, accuracyM = 5.0))
        assertFalse(LocationPolicy.acceptLastKnown(ageMs = 10_000, accuracyM = 50.5))
        assertFalse(LocationPolicy.acceptLastKnown(ageMs = 10_000, accuracyM = null))
        assertFalse(LocationPolicy.acceptLastKnown(ageMs = 10_000, accuracyM = Double.NaN))
        assertFalse(LocationPolicy.acceptLastKnown(ageMs = -5, accuracyM = 5.0)) // clock oddity: not fresh
    }

    @Test
    fun providersAndBestFix() {
        assertEquals(listOf("gps", "network"), LocationPolicy.providersToAsk(gpsEnabled = true, networkEnabled = true))
        assertEquals(listOf("network"), LocationPolicy.providersToAsk(gpsEnabled = false, networkEnabled = true))
        assertEquals(listOf("gps"), LocationPolicy.providersToAsk(gpsEnabled = true, networkEnabled = false))
        assertEquals(emptyList(), LocationPolicy.providersToAsk(gpsEnabled = false, networkEnabled = false))
        assertTrue(LocationPolicy.better(8.0, 30.0))
        assertFalse(LocationPolicy.better(30.0, 8.0))
        assertTrue(LocationPolicy.better(80.0, null))
        assertFalse(LocationPolicy.better(null, 80.0))
        assertTrue(LocationPolicy.goodEnough(25.0))
        assertFalse(LocationPolicy.goodEnough(25.5))
        assertFalse(LocationPolicy.goodEnough(null))
        assertEquals(30_000L, LocationPolicy.TIMEOUT_MS)
    }

    @Test
    fun textsAreShortSwissGermanAndTrue() {
        val shifted = Outcome.Shifted(LocationNoise.Point(2682812, 1245537, 50), 12)
        assertEquals("Standort ± 12 m, verschoben um etwa 50 m", LocationPolicy.message(shifted))
        assertEquals("Eingefügter Ort, verschoben um etwa 100 m", LocationPolicy.message(Outcome.Shifted(LocationNoise.Point(2682812, 1245537, 100), null)))
        assertEquals("Standort wird bestimmt …", LocationPolicy.PROGRESS_TEXT)
        val outcomes = listOf(
            shifted, Outcome.ApproximateOnly, Outcome.TooInaccurate(140), Outcome.TooInaccurate(null),
            Outcome.OutsideSwitzerland("Dieser Standort liegt nicht in der Schweiz."), Outcome.LocationOff, Outcome.NoFix,
            Outcome.PermissionDenied,
        )
        val texts = outcomes.map { LocationPolicy.message(it) } + LOCATION_NEVER_SENT
        for (t in texts) {
            assertFalse(t.contains('ß'), t)
            assertFalse(Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
            assertFalse(t.contains('"') || t.contains('„') || t.contains('“'), t) // guillemets only
            // Owner: texts at most one short sentence.
            assertEquals(0, t.trimEnd('.').count { it == '.' }, t)
        }
        assertTrue(LocationPolicy.message(Outcome.ApproximateOnly).contains("«Genauer Standort»"))
        assertTrue(LocationPolicy.message(Outcome.TooInaccurate(140)).contains("± 140 m"))
        assertTrue(LocationPolicy.message(Outcome.NoFix).contains("30 Sekunden"))
        // The exact place leaves the phone only with a report; location is never read in the background.
        for (part in listOf("genauer Standort", "Meldung", "nie im Hintergrund")) assertTrue(LOCATION_NEVER_SENT.contains(part), part)
    }

    @Test
    fun shareTextsDescribeTheShiftedPoint() {
        assertEquals("Standort verschieben: um etwa 50 m (Standard)", sigmaLabel(50))
        assertEquals("Standort verschieben: um etwa 200 m", sigmaLabel(200))
        val all = LocationNoise.SIGMAS_M.flatMap { shareLocationBullets(it) + sigmaLabel(it) + pastedText("WGS84", it) + sharedMeasurementLine(it) } +
            SHARE_SENT_BULLETS + SIGMA_EXPLANATION + SIGMA_APPLIES
        for (t in all) {
            assertFalse(t.contains('ß'), t)
            assertFalse(Regex("\\b(Sie|Ihre?n?)\\b").containsMatchIn(t), t)
            assertFalse(t.contains('"') || t.contains('„') || t.contains('“'), t)
            // The hectare is no longer what protects the place: no «nur die Zelle/Hektare».
            assertFalse(Regex("(?i)nur (die|eine) (100-m-)?(zelle|hektare)|behalten wird die").containsMatchIn(t), t)
            assertFalse(t.contains("Nacht") || t.contains("nacht"), t)
        }
        assertTrue(shareLocationBullets(50)[0].contains("um etwa 50 m"))
        assertTrue(shareLocationBullets(100)[2].contains("200 m")) // 2σ
        assertTrue(SIGMA_EXPLANATION.contains("genaue Adresse") && SIGMA_EXPLANATION.contains("ungenauer"))
        assertTrue(SHARE_SENT_BULLETS[0].contains("verschoben"))
    }

    @Test
    fun reportFixNeedsPreciseFiftyMetresAndSwitzerland() {
        val zh = 47.37690 to 8.54170
        val ok = LocationPolicy.decideReport(zh.first, zh.second, 50.0, precise = true)
        assertTrue(ok is LocationPolicy.ReportOutcome.Fix, ok.toString())
        ok as LocationPolicy.ReportOutcome.Fix
        assertEquals(zh.first, ok.lat); assertEquals(zh.second, ok.lon); assertEquals(50, ok.accuracyText)
        assertTrue(ok.cell.matches(Regex("h\\d{5}_\\d{5}")), ok.cell)
        assertEquals(
            LocationPolicy.ReportOutcome.Problem(Outcome.TooInaccurate(51)),
            LocationPolicy.decideReport(zh.first, zh.second, 50.1, precise = true),
        )
        assertEquals(
            LocationPolicy.ReportOutcome.Problem(Outcome.TooInaccurate(null)),
            LocationPolicy.decideReport(zh.first, zh.second, null, precise = true),
        )
        assertEquals(
            LocationPolicy.ReportOutcome.Problem(Outcome.ApproximateOnly),
            LocationPolicy.decideReport(zh.first, zh.second, 5.0, precise = false),
        )
        assertTrue(LocationPolicy.decideReport(48.8566, 2.3522, 5.0, precise = true).let {
            it is LocationPolicy.ReportOutcome.Problem && it.outcome is Outcome.OutsideSwitzerland
        })
        // A report is stricter than a measurement's cell (75 m).
        assertTrue(LocationPolicy.REPORT_MAX_ACCURACY_M < LocationPolicy.MAX_ACCURACY_M)
    }
}
