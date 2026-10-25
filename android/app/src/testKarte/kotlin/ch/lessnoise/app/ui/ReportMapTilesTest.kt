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

    // ---- 0.8.1 (same build): the map moved under the dot ---------------------------------------

    private fun closePair(e: Pair<Double, Double>, a: Pair<Double, Double>, tol: Double, what: String) {
        close(e.first, a.first, tol, "$what x"); close(e.second, a.second, tol, "$what y")
    }

    @Test
    fun aDragIsTurnedBackByTheDiscsRotation() {
        // 0°: the phone points north, the map is north-up; up the screen (dy < 0) is up the map.
        closePair(0.0 to -100.0, ReportMapTiles.screenDragToMapOffset(0.0, -100.0, 0.0), 1e-9, "0°")
        closePair(30.0 to 40.0, ReportMapTiles.screenDragToMapOffset(30.0, 40.0, 0.0), 1e-9, "0° any")
        // The phone points east: the map is drawn turned by −90° (counter-clockwise), north is to
        // the left of the screen. A drag up the screen moves the map along the phone's direction,
        // east: map offset (+100, 0) — the map slides east under the dot (which then lies west of the fix).
        closePair(100.0 to 0.0, ReportMapTiles.screenDragToMapOffset(0.0, -100.0, -90.0), 1e-9, "−90° up")
        // … and a drag to the right of the screen (= south on the ground) moves the map south.
        closePair(0.0 to 100.0, ReportMapTiles.screenDragToMapOffset(100.0, 0.0, -90.0), 1e-9, "−90° right")
        // +90° (the phone points west) mirrors it: the map moves west.
        closePair(-100.0 to 0.0, ReportMapTiles.screenDragToMapOffset(0.0, -100.0, 90.0), 1e-9, "90° up")
        // 180°: the phone points south, the map is upside down; up the screen is down the map.
        closePair(0.0 to 100.0, ReportMapTiles.screenDragToMapOffset(0.0, -100.0, 180.0), 1e-9, "180° up")
        closePair(-30.0 to -40.0, ReportMapTiles.screenDragToMapOffset(30.0, 40.0, 180.0), 1e-9, "180° any")
        // Turning back keeps the length, and drawing with the rotation gives the screen drag again
        // (Compose's rotate: positive = clockwise on a y-down screen).
        for (deg in listOf(-213.0, -90.0, -47.5, 0.0, 12.0, 90.0, 180.0, 359.0)) {
            val (mx, my) = ReportMapTiles.screenDragToMapOffset(17.0, -23.0, deg)
            close(hypot(17.0, -23.0), hypot(mx, my), 1e-9, "length $deg")
            val a = Math.toRadians(deg)
            close(17.0, mx * cos(a) - my * sin(a), 1e-9, "back x $deg"); close(-23.0, mx * sin(a) + my * cos(a), 1e-9, "back y $deg")
        }
    }

    @Test
    fun anOffsetIsAKnownDistanceOnTheGround() {
        val fix = lat to lon
        val mpp = ReportMapTiles.metresPerPixel(lat)
        // A 200-px canvas shows 300 m: 1.5 m per canvas pixel, so 100 px = 150 m.
        val scale = ReportMapTiles.canvasScale(200.0, lat)
        close(1.5, mpp / scale, 1e-12, "m per canvas px")
        // The map moved 100 px DOWN (south) puts the dot 150 m NORTH of the fix.
        val (nLat, nLon) = ReportMapTiles.offsetToLatLon(fix, 0.0 to 100.0, ReportMapTiles.ZOOM, scale)
        close(lon, nLon, 1e-12, "same longitude")
        // 150 m of latitude: 150 / 111 133 m per degree at 47.4° ≈ 0.0013497° (Mercator's local scale is
        // exact to ≈ 1e-5 relative over 150 m); the tolerance is 1 % of the distance.
        close(150.0, (nLat - lat) * 111_133.0, 1.5, "150 m north")
        // The map moved 100 px to the right (east) puts the dot 150 m WEST.
        val (wLat, wLon) = ReportMapTiles.offsetToLatLon(fix, 100.0 to 0.0, ReportMapTiles.ZOOM, scale)
        close(lat, wLat, 1e-9, "same latitude")
        close(-150.0, (wLon - lon) * 111_320.0 * cos(Math.toRadians(lat)), 1.5, "150 m west")
        // Zero is the fix; latLonToOffset is the inverse.
        closePair(fix, ReportMapTiles.offsetToLatLon(fix, 0.0 to 0.0, ReportMapTiles.ZOOM, scale), 1e-12, "zero")
        closePair(0.0 to 100.0, ReportMapTiles.latLonToOffset(fix, nLat to nLon, ReportMapTiles.ZOOM, scale), 1e-6, "inverse")
        close(150.0, ReportMapTiles.offsetMetres(0.0 to 100.0, lat, scale), 1e-9, "offsetMetres")
        // Phone pointing east (disc turned by −90°), the finger 100 px DOWN the screen.
        val d = ReportMapTiles.screenDragToMapOffset(0.0, 100.0, -90.0)
        val (eLat, eLon) = ReportMapTiles.offsetToLatLon(fix, d, ReportMapTiles.ZOOM, scale)
        close(lat, eLat, 1e-9, "east: same latitude")
        // Dragging down the screen pulls the map towards the reporter: the dot moves forward, east.
        close(150.0, (eLon - lon) * 111_320.0 * cos(Math.toRadians(lat)), 1.5, "150 m east")
    }

    @Test
    fun theDragStopsAt2km() {
        val scale = ReportMapTiles.canvasScale(200.0, lat) // 1.5 m per canvas px
        assertEquals(2_000.0, ReportMapTiles.MAX_DRAG_M)
        // 1000 px = 1.5 km: kept as it is.
        closePair(0.0 to 1000.0, ReportMapTiles.clampOffset(0.0 to 1000.0, lat, scale), 0.0, "1.5 km")
        // 3000 px = 4.5 km diagonally: shortened onto 2 km in the same direction.
        val c = ReportMapTiles.clampOffset(1800.0 to -2400.0, lat, scale)
        close(2_000.0, ReportMapTiles.offsetMetres(c, lat, scale), 1e-6, "on the limit")
        close(1800.0 / -2400.0, c.first / c.second, 1e-12, "same direction")
        // The place there is 2 km from the fix on the ground (Mercator, ≈ 0.1 % over 2 km).
        val north = ReportMapTiles.clampOffset(0.0 to 10_000.0, lat, scale)
        val (la, _) = ReportMapTiles.offsetToLatLon(lat to lon, north, ReportMapTiles.ZOOM, scale)
        close(2_000.0, (la - lat) * 111_133.0, 5.0, "2 km north")
    }

    @Test
    fun theFarthestTilesAreDroppedFirst() {
        val p = ReportMapTiles.tilePoint(lat, lon)
        val c = ReportMapTiles.tileOf(p)
        val near = (0 until 10).map { Tile(18, c.x + it, c.y) }
        assertEquals(emptyList(), ReportMapTiles.evict(near, p, max = 10))
        assertEquals(listOf(Tile(18, c.x + 9, c.y), Tile(18, c.x + 8, c.y)), ReportMapTiles.evict(near, p, max = 8))
        assertEquals(64, ReportMapTiles.CACHE_MAX)
        // The 150 m rule around a moved centre: the tiles of a place 1 km east are other tiles.
        val east = ReportMapTiles.offsetToLatLon(lat to lon, -1000.0 / 1.5 to 0.0, 18, ReportMapTiles.canvasScale(200.0, lat))
        val moved = ReportMapTiles.tilesAround(east.first, east.second)
        assertTrue(moved.size in 9..16)
        assertTrue(moved.none { it in ReportMapTiles.tilesAround(lat, lon) })
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
        // 0.8.1 (same build): the drag exists only with the flag and the streets shown; tiles for a
        // moved centre only after the finger lifts, debounced.
        assertTrue(ui.contains("val canDrag = ReportMapTiles.ENABLED && fix != null && tiles.isNotEmpty() && r != null"))
        assertTrue(ui.contains("rememberStreetTiles(fix, centre.takeUnless { dragging })"))
        assertTrue(ui.contains("if (c != f) delay(TILE_DEBOUNCE_MS)"))
        assertTrue(ui.contains("DirectionArrow(frozenDeg ?: r?.magneticDeg,"), "the disc is frozen while the finger is down")
        // 0.8.1: the disc of the one «Laut! Melden» dialog is 200 dp (still 300 m across, canvasScale).
        assertEquals(200, ReportDirectionUi.DISC_DP)
        // 0.8.6: the 200 dp square is a Box holding the canvas (filling it) and the credit in its corner.
        assertTrue(ui.contains("Box(Modifier.widthIn(max = DISC_DP.dp).fillMaxWidth().aspectRatio(1f))") && ui.contains("Canvas(Modifier.fillMaxSize().then(drag))"))
        // The preview tiles (JVM renders) replace the download only when passed; the app never passes them.
        assertTrue(ui.contains("val tiles = previewTiles ?: rememberStreetTiles("))
        assertEquals("Telefon in die Fahrtrichtung halten", ReportDirectionUi.POINT_HINT)
        // Nothing is written: no file, preference or log in either file.
        val bad = Regex("""\bLog\.|println|SharedPreferences|putString|\bFile\b|FileOutputStream|cacheDir|filesDir|HttpResponseCache|CookieHandler""")
        for (src in listOf(code, ui)) {
            val c = src.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            assertEquals(null, bad.find(c)?.value)
        }
        assertTrue(code.contains("conn.useCaches = false") && code.contains("conn.instanceFollowRedirects = false"))
    }
}
