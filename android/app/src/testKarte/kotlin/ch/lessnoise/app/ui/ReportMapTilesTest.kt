package ch.lessnoise.app.ui

import ch.lessnoise.app.BuildConfig
import ch.lessnoise.app.ui.ReportMapTiles.Tile
import ch.lessnoise.app.ui.ReportMapTiles.TilePoint
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 0.7.8: the tile arithmetic of the streets under the arrow in «Wohin fuhr es?» (Labor·Karte). No
 * tile is fetched here: the maths only.
 *
 * Fixture, computed in Python 3 with the same standard Web Mercator formula
 * (x = (lon + 180) / 360 · 2^z, y = (1 − ln(tan φ + 1 / cos φ) / π) / 2 · 2^z,
 * metres per pixel = 2π · 6378137 · cos φ / (256 · 2^z)), for Zürich main station 47.3779 N, 8.5403 E:
 *
 *   z 18: x = 137290.85667555555, y = 91797.99413031054 → tile (137290, 91797),
 *         fix at pixel (219.3089, 254.4974) in that tile; 0.40437567366720667 m per pixel
 *   z 17: x = 68645.42833777778,  y = 45898.99706515527 → tile (68645, 45898)
 *   tiles touching the 150 m disc at z 18 (nearest first, ties by y then x), 14 of them:
 *   (137290,91797) (137290,91798) (137291,91797) (137291,91798) (137289,91797) (137289,91798)
 *   (137290,91796) (137291,91796) (137290,91799) (137291,91799) (137292,91797) (137292,91798)
 *   (137289,91796) (137289,91799)
 */
class ReportMapTilesTest {
    private val lat = 47.3779
    private val lon = 8.5403

    private fun close(expected: Double, actual: Double, tol: Double, what: String) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    @Test
    fun zurichMainStationAtZoom18() {
        val p = ReportMapTiles.tilePoint(lat, lon)
        assertEquals(18, p.z)
        close(137290.85667555555, p.x, 1e-6, "x")
        close(91797.99413031054, p.y, 1e-6, "y")
        assertEquals(Tile(18, 137290, 91797), ReportMapTiles.tileOf(p))
        val (px, py) = ReportMapTiles.pixelInTile(p)
        close(219.3089422211051, px, 1e-3, "pixel x")
        close(254.4973594993353, py, 1e-3, "pixel y")
        close(0.40437567366720667, ReportMapTiles.metresPerPixel(lat), 1e-9, "m/px")
        val q = ReportMapTiles.tilePoint(lat, lon, 17)
        assertEquals(Tile(17, 68645, 45898), ReportMapTiles.tileOf(q))
        close(68645.42833777778, q.x, 1e-6, "x17")
    }

    @Test
    fun theFormulaAtKnownCorners() {
        // The equator and the prime meridian are the centre of the world square.
        val c = ReportMapTiles.tilePoint(0.0, 0.0, 1)
        close(1.0, c.x, 1e-12, "x"); close(1.0, c.y, 1e-12, "y")
        val nw = ReportMapTiles.tilePoint(85.05112877980659, -180.0, 3)
        close(0.0, nw.x, 1e-9, "x"); close(0.0, nw.y, 1e-6, "y")
        // At the equator one zoom-0 pixel is the equator's length / 256.
        close(156543.03392804097, ReportMapTiles.metresPerPixel(0.0, 0), 1e-6, "m/px z0")
        // One zoom level halves the metres per pixel.
        close(ReportMapTiles.metresPerPixel(lat, 17) / 2, ReportMapTiles.metresPerPixel(lat, 18), 1e-12, "halving")
    }

    @Test
    fun roundTrips() {
        for ((la, lo) in listOf(lat to lon, 46.0 to 6.1, 47.8 to 10.5, 46.2044 to 6.1432, -33.86 to 151.21, 0.0 to 0.0)) {
            for (z in listOf(0, 10, 17, 18, 20)) {
                val p = ReportMapTiles.tilePoint(la, lo, z)
                val (bl, bo) = ReportMapTiles.latLon(p)
                close(la, bl, 1e-9, "lat $la z $z"); close(lo, bo, 1e-9, "lon $lo z $z")
                // The pixel inside the tile plus the tile gives back the fractional position.
                val t = ReportMapTiles.tileOf(p)
                val (px, py) = ReportMapTiles.pixelInTile(p)
                assertTrue(px >= 0 && px < 256 && py >= 0 && py < 256, "$px $py")
                close(p.x, t.x + px / 256, 1e-9, "x back"); close(p.y, t.y + py / 256, 1e-9, "y back")
            }
        }
        // A tile's top-left (north-west) corner: just south-east of it is that tile, just north the one above.
        val (la, lo) = ReportMapTiles.latLon(TilePoint(18, 137290.0, 91797.0))
        assertEquals(Tile(18, 137290, 91797), ReportMapTiles.tileOf(ReportMapTiles.tilePoint(la - 1e-7, lo + 1e-7)))
        assertEquals(Tile(18, 137290, 91796), ReportMapTiles.tileOf(ReportMapTiles.tilePoint(la + 1e-7, lo + 1e-7)))
        assertEquals(Tile(18, 137289, 91797), ReportMapTiles.tileOf(ReportMapTiles.tilePoint(la - 1e-7, lo - 1e-7)))
    }

    @Test
    fun theTilesAroundTheFixCoverTheTurningDisc() {
        val tiles = ReportMapTiles.tilesAround(lat, lon)
        assertEquals(
            listOf(
                137290 to 91797, 137290 to 91798, 137291 to 91797, 137291 to 91798, 137289 to 91797, 137289 to 91798,
                137290 to 91796, 137291 to 91796, 137290 to 91799, 137291 to 91799, 137292 to 91797, 137292 to 91798,
                137289 to 91796, 137289 to 91799,
            ).map { Tile(18, it.first, it.second) },
            tiles,
        )
        assertEquals(Tile(18, 137290, 91797), tiles.first(), "the fix's own tile first")
        // Always the 3 × 3 block around the fix's tile, and never more than 4 × 4, anywhere in Switzerland.
        for (la in listOf(45.81, 46.5, 47.3779, 47.84)) for (lo in listOf(5.91, 7.4, 8.5403, 10.54)) {
            val p = ReportMapTiles.tilePoint(la, lo)
            val c = ReportMapTiles.tileOf(p)
            val got = ReportMapTiles.tilesAround(la, lo).toSet()
            for (dx in -1..1) for (dy in -1..1) assertTrue(Tile(18, c.x + dx, c.y + dy) in got, "$la $lo: ${c.x + dx},${c.y + dy}")
            assertTrue(got.size in 9..16, "$la $lo: ${got.size}")
            assertEquals(got.size, ReportMapTiles.tilesAround(la, lo).size, "no duplicates")
        }
        // Every point of the 150 m disc lies in one of the tiles (checked on rings around the fix).
        val p = ReportMapTiles.tilePoint(lat, lon)
        val set = tiles.toSet()
        val rTiles = 150.0 / (ReportMapTiles.metresPerPixel(lat) * 256)
        for (k in 0 until 360) for (f in listOf(0.25, 0.5, 0.75, 1.0)) {
            val a = Math.toRadians(k.toDouble())
            val q = TilePoint(18, p.x + sin(a) * rTiles * f, p.y - cos(a) * rTiles * f)
            assertTrue(ReportMapTiles.tileOf(q) in set, "uncovered at $k° × $f")
        }
    }

    @Test
    fun theViewIs300MetresAcrossAtAnyDensity() {
        val mpp = ReportMapTiles.metresPerPixel(lat)
        for (density in listOf(1.0, 2.0, 2.625, 3.5)) {
            val canvasPx = 240 * density
            val s = ReportMapTiles.canvasScale(canvasPx, lat)
            // canvasPx canvas pixels = canvasPx / s tile pixels = that × mpp metres.
            close(300.0, canvasPx / s * mpp, 1e-9, "density $density")
        }
        // Placement: the fix is the canvas centre; a tile is 256 · scale canvas pixels.
        val p = ReportMapTiles.tilePoint(lat, lon)
        val s = ReportMapTiles.canvasScale(720.0, lat)
        val box = ReportMapTiles.placement(ReportMapTiles.tileOf(p), p, s)
        val (px, py) = ReportMapTiles.pixelInTile(p)
        close(-px * s, box[0], 1e-6, "left"); close(-py * s, box[1], 1e-6, "top")
        close(256 * s, box[2] - box[0], 1e-6, "width"); close(256 * s, box[3] - box[1], 1e-6, "height")
        // 150 m from the centre is the canvas edge (360 px of a 720 px canvas).
        close(360.0, 150.0 / mpp * s, 1e-6, "edge")
        assertTrue(hypot(box[0], box[1]) < 360.0)
    }

    @Test
    fun theAddressIsTheWebsiteMapsAndOnlyInSwitzerland() {
        assertEquals(
            "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-grau/default/current/3857/18/137290/91797.jpeg",
            ReportMapTiles.url(Tile(18, 137290, 91797)),
        )
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.take(5)
            .firstOrNull { File(it, "docs/map/karte.js").isFile } ?: fail("repository root not found")
        val js = File(root, "docs/map/karte.js").readText()
        val tiles = Regex("""var TILES = "([^"]+)";""").find(js)?.groupValues?.get(1) ?: fail("no TILES in karte.js")
        assertEquals(tiles, ReportMapTiles.URL_TEMPLATE, "the same tiles as the website's map")
        assertTrue(js.contains("var BOUNDS = [[45.80, 5.90], [47.85, 10.55]];"), "the bounds of ReportMapTiles.inSwitzerland")
        // The host is one the map tab allows too.
        assertTrue(MapLinks.allowedHost(ReportMapTiles.url(Tile(18, 1, 1))))
        assertTrue(ReportMapTiles.inSwitzerland(lat, lon))
        assertFalse(ReportMapTiles.inSwitzerland(48.1372, 11.5756)) // München
        assertFalse(ReportMapTiles.inSwitzerland(45.4642, 9.19)) // Milano
        assertEquals(18, ReportMapTiles.ZOOM)
        assertEquals(5_000, ReportMapTiles.TIMEOUT_MS)
    }

    /** Tiles only in Labor·Karte; the Online-Version (flavour public) shows the rose alone. */
    @Test
    fun onlyInLaborKarte() {
        assertEquals(BuildConfig.FLAVOR == "laborKarte", ReportMapTiles.ENABLED, BuildConfig.FLAVOR)
        val app = listOf(File("."), File("app")).first { File(it, "src/karte").isDirectory }
        val code = File(app, "src/karte/kotlin/ch/lessnoise/app/ui/ReportMapTiles.kt").readText()
        assertTrue(code.contains("val ENABLED: Boolean = BuildConfig.LABOR_UNTERWEGS"))
        assertTrue(code.contains("if (!ReportMapTiles.ENABLED || closed) return null"), "the fetcher checks the flag itself")
        val ui = File(app, "src/karte/kotlin/ch/lessnoise/app/ui/ReportDirectionUi.kt").readText()
        assertTrue(ui.contains("if (!ReportMapTiles.ENABLED || f == null"), "the step starts no download without the flag")
        // Nothing is written: no file, preference or log in either file.
        val bad = Regex("""\bLog\.|println|SharedPreferences|putString|\bFile\b|FileOutputStream|cacheDir|filesDir|HttpResponseCache|CookieHandler""")
        for (src in listOf(code, ui)) {
            val c = src.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            assertEquals(null, bad.find(c)?.value)
        }
        assertTrue(code.contains("conn.useCaches = false") && code.contains("conn.instanceFollowRedirects = false"))
    }
}
