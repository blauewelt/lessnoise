package ch.lessnoise.app.location

import ch.lessnoise.app.location.LocationPolicy.Outcome
import ch.lessnoise.app.location.LocationPolicy.Permission
import ch.lessnoise.app.ui.LOCATION_NEVER_SENT
import ch.lessnoise.app.upload.CellInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One location fix (0.6.0: at «Start» of a shared measurement, and «Laut! Melden»): lat/lon → cell, and when a fix is accepted. Pure JVM. */
class LocationPolicyTest {

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
    fun latLonBecomesTheSameHectareAsPastedCoordinates() {
        for ((lat, lon, cell) in zuerich) {
            assertEquals(CellInput.Parsed.Ok(cell, "WGS84"), CellInput.fromLatLon(lat, lon))
            assertEquals(cell, (CellInput.parse("$lat, $lon") as CellInput.Parsed.Ok).cell)
            assertEquals(Outcome.Hectare(cell, 12), LocationPolicy.decide(lat, lon, 11.4, precise = true))
        }
        assertIs<CellInput.Parsed.Error>(CellInput.fromLatLon(48.8566, 2.3522)) // Paris
        assertIs<CellInput.Parsed.Error>(CellInput.fromLatLon(8.5417, 47.3769)) // swapped: a fix is never swapped
        assertIs<CellInput.Parsed.Error>(CellInput.fromLatLon(Double.NaN, 8.5))
        assertIs<CellInput.Parsed.Error>(CellInput.fromLatLon(0.0, 0.0))
    }

    @Test
    fun accuracyAndPermissionDecideWhetherTheHectareIsSet() {
        val (lat, lon, cell) = zuerich[0]
        // «Ungefähr» never sets a hectare, however accurate the number looks.
        assertEquals(Outcome.ApproximateOnly, LocationPolicy.decide(lat, lon, 5.0, precise = false))
        assertEquals(Outcome.Hectare(cell, 1), LocationPolicy.decide(lat, lon, 0.0, precise = true))
        assertEquals(Outcome.Hectare(cell, 13), LocationPolicy.decide(lat, lon, 12.01, precise = true))
        assertEquals(Outcome.Hectare(cell, 75), LocationPolicy.decide(lat, lon, 75.0, precise = true))
        assertEquals(Outcome.TooInaccurate(76), LocationPolicy.decide(lat, lon, 75.1, precise = true))
        assertEquals(Outcome.TooInaccurate(1500), LocationPolicy.decide(lat, lon, 1500.0, precise = true))
        assertEquals(Outcome.TooInaccurate(null), LocationPolicy.decide(lat, lon, null, precise = true))
        assertEquals(Outcome.TooInaccurate(null), LocationPolicy.decide(lat, lon, Double.NaN, precise = true))
        assertEquals(Outcome.TooInaccurate(null), LocationPolicy.decide(lat, lon, -1.0, precise = true))
        assertIs<Outcome.OutsideSwitzerland>(LocationPolicy.decide(48.8566, 2.3522, 5.0, precise = true))

        assertEquals(Permission.PRECISE, LocationPolicy.permission(fineGranted = true, coarseGranted = true))
        assertEquals(Permission.PRECISE, LocationPolicy.permission(fineGranted = true, coarseGranted = false))
        assertEquals(Permission.APPROXIMATE_ONLY, LocationPolicy.permission(fineGranted = false, coarseGranted = true))
        assertEquals(Permission.DENIED, LocationPolicy.permission(fineGranted = false, coarseGranted = false))
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
        assertEquals("Gefunden (± 12 m)", LocationPolicy.message(Outcome.Hectare("h26828_12455", 12)))
        assertEquals("Standort wird bestimmt …", LocationPolicy.PROGRESS_TEXT)
        val outcomes = listOf(
            Outcome.Hectare("h26828_12455", 12), Outcome.ApproximateOnly, Outcome.TooInaccurate(140), Outcome.TooInaccurate(null),
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
        assertTrue(LocationPolicy.message(Outcome.NoFix).contains("Kein Standort"))
        // The exact place leaves the phone only with a report; location is never read in the background.
        for (part in listOf("genauer Standort", "Meldung", "nie im Hintergrund")) assertTrue(LOCATION_NEVER_SENT.contains(part), part)
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
