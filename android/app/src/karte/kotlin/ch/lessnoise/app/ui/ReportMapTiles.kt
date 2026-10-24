package ch.lessnoise.app.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import ch.lessnoise.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 0.7.8, Labor·Karte only ([ENABLED] = BuildConfig.LABOR_UNTERWEGS): the real streets under the
 * arrow of «Wohin fuhr es?». swisstopo's grey map (swisstopo = the Federal Office of Topography),
 * the same tiles the website's map uses (docs/map/karte.js `TILES`), in Web Mercator (the tiling
 * scheme of web maps: the world as 2^z × 2^z square tiles of 256 pixels at zoom z).
 *
 * This object is the pure maths (unit-tested in src/testKarte, ReportMapTilesTest); the download is
 * [ReportTileFetcher] below, the drawing ReportDirectionUi.
 */
object ReportMapTiles {
    /** Tiles only in Labor·Karte; the Online-Version shows the rose without a map (PRIVACY.md). */
    val ENABLED: Boolean = BuildConfig.LABOR_UNTERWEGS

    /** THE tile address, as in docs/map/karte.js (`ReportMapTilesTest` compares the two). */
    const val URL_TEMPLATE = "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-grau/default/current/3857/{z}/{x}/{y}.jpeg"

    /** Zoom 18: one tile ≈ 103 m at Zürich's latitude (0.40 m per tile pixel). */
    const val ZOOM = 18
    const val TILE_PX = 256

    /** The canvas shows this many metres across its width. */
    const val VIEW_M = 300.0

    /** Tiles are drawn faint, so that the arrow and the rose stay in front. */
    const val TILE_ALPHA = 0.6f

    const val TIMEOUT_MS = 5_000

    /** A grey-map tile is 5–40 kB; anything larger than this is not a tile. */
    const val MAX_BYTES = 512 * 1024

    /** Downloads at the same time (one host; the tiles nearest to the fix come first). */
    const val PARALLEL = 4

    /** WGS84 semi-major axis, the sphere radius of Web Mercator. */
    private const val EARTH_RADIUS_M = 6_378_137.0

    /** Web Mercator's latitude limit (the map is square). */
    private const val MAX_LAT = 85.05112877980659

    /** Where the grey map has data: the website map's bounds (docs/map/karte.js `BOUNDS`, Switzerland). */
    private const val SOUTH = 45.80
    private const val WEST = 5.90
    private const val NORTH = 47.85
    private const val EAST = 10.55

    data class Tile(val z: Int, val x: Int, val y: Int)

    /** A place in fractional tile coordinates at zoom [z] (x east, y south; tile (x, y) spans [x, x+1) × [y, y+1)). */
    data class TilePoint(val z: Int, val x: Double, val y: Double)

    private fun tiles(z: Int): Double = (1L shl z).toDouble()

    /** Latitude/longitude (degrees, WGS84) → fractional tile coordinates (the standard Web Mercator formula). */
    fun tilePoint(lat: Double, lon: Double, z: Int = ZOOM): TilePoint {
        val n = tiles(z)
        val phi = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        val x = (lon + 180.0) / 360.0 * n
        val y = (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0 * n
        return TilePoint(z, x, y)
    }

    /** The inverse: fractional tile coordinates → (latitude, longitude). */
    fun latLon(p: TilePoint): Pair<Double, Double> {
        val n = tiles(p.z)
        val lon = p.x / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * p.y / n))))
        return lat to lon
    }

    /** The tile that contains [p]. */
    fun tileOf(p: TilePoint): Tile = Tile(p.z, floor(p.x).toInt(), floor(p.y).toInt())

    /** Where [p] lies inside its own tile, in tile pixels from the tile's top-left corner (0 ≤ x, y < 256). */
    fun pixelInTile(p: TilePoint): Pair<Double, Double> =
        (p.x - floor(p.x)) * TILE_PX to (p.y - floor(p.y)) * TILE_PX

    /** Ground metres per tile pixel at latitude [lat] and zoom [z]. */
    fun metresPerPixel(lat: Double, z: Int = ZOOM): Double =
        2.0 * PI * EARTH_RADIUS_M * cos(Math.toRadians(lat)) / (TILE_PX * tiles(z))

    /**
     * Every tile that touches the disc of [radiusM] around the place (default: half the view, so the
     * turning circle is covered in every direction), nearest first. At zoom 18 in Switzerland a tile
     * is 103–107 m, so this is 3 to 4 tiles each way and always contains the 3 × 3 tiles around the
     * fix's own tile.
     */
    fun tilesAround(lat: Double, lon: Double, radiusM: Double = VIEW_M / 2, z: Int = ZOOM): List<Tile> {
        val p = tilePoint(lat, lon, z)
        val r = radiusM / (metresPerPixel(lat, z) * TILE_PX)
        val max = (1L shl z) - 1
        val xs = floor(p.x - r).toLong().coerceAtLeast(0)..floor(p.x + r).toLong().coerceAtMost(max)
        val ys = floor(p.y - r).toLong().coerceAtLeast(0)..floor(p.y + r).toLong().coerceAtMost(max)
        val out = mutableListOf<Pair<Double, Tile>>()
        for (x in xs) for (y in ys) {
            // Distance from the fix to the nearest point of the tile (0 inside it).
            val dx = (x - p.x).coerceAtLeast(0.0).coerceAtLeast(p.x - (x + 1))
            val dy = (y - p.y).coerceAtLeast(0.0).coerceAtLeast(p.y - (y + 1))
            val d = hypot(dx, dy)
            if (d <= r) out += d to Tile(z, x.toInt(), y.toInt())
        }
        return out.sortedWith(compareBy({ it.first }, { it.second.y }, { it.second.x })).map { it.second }
    }

    /** Whether the grey map has data there; outside, no tile is requested at all. */
    fun inSwitzerland(lat: Double, lon: Double): Boolean = lat in SOUTH..NORTH && lon in WEST..EAST

    /** The address of [t]. */
    fun url(t: Tile): String =
        URL_TEMPLATE.replace("{z}", t.z.toString()).replace("{x}", t.x.toString()).replace("{y}", t.y.toString())

    /**
     * Canvas pixels per tile pixel, so that [canvasPx] (the canvas width) shows [VIEW_M] metres:
     * independent of the screen density, since it depends only on the canvas's own pixel width.
     */
    fun canvasScale(canvasPx: Double, lat: Double, z: Int = ZOOM): Double = canvasPx * metresPerPixel(lat, z) / VIEW_M

    /**
     * Where tile [t] goes on a canvas whose centre is the fix [p], drawn at [scale] canvas pixels per
     * tile pixel: left, top, right, bottom in canvas pixels relative to the centre (before rotation).
     */
    fun placement(t: Tile, p: TilePoint, scale: Double): DoubleArray {
        val k = TILE_PX * scale
        return doubleArrayOf((t.x - p.x) * k, (t.y - p.y) * k, (t.x + 1 - p.x) * k, (t.y + 1 - p.y) * k)
    }
}

/**
 * Downloads tiles for one direction step (0.7.8, Labor·Karte only). The tiles are kept only in
 * memory by the caller and only while the step is on screen; nothing is written anywhere (no disk
 * cache: `useCaches = false`, no HttpResponseCache is installed in the app). One host,
 * wmts.geo.admin.ch (from [ReportMapTiles.URL_TEMPLATE] only), GET, HTTPS, no redirects, no cookies
 * (the app installs no CookieHandler), a fixed User-Agent without device details (as UploadClient),
 * 5 s timeouts. Any failure gives null: that tile is simply not drawn. [close] (when the step goes
 * away) aborts the open connections and makes every later [fetch] return null.
 */
class ReportTileFetcher {
    private val open: MutableSet<HttpURLConnection> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var closed = false

    /** Blocks; call on Dispatchers.IO. */
    fun fetch(tile: ReportMapTiles.Tile): ImageBitmap? {
        if (!ReportMapTiles.ENABLED || closed) return null
        val conn = try {
            URI(ReportMapTiles.url(tile)).toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        open += conn
        try {
            if (closed) return null
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = ReportMapTiles.TIMEOUT_MS
            conn.readTimeout = ReportMapTiles.TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "LessNoise")
            conn.setRequestProperty("Accept", "image/jpeg")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = conn.inputStream.use { readCapped(it) } ?: return null
            if (closed) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            return null
        } finally {
            open -= conn
            conn.disconnect()
        }
    }

    fun close() {
        closed = true
        val all = synchronized(open) { open.toList() }
        for (c in all) {
            try {
                c.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > ReportMapTiles.MAX_BYTES) return null
            out.write(buf, 0, n)
        }
    }
}
