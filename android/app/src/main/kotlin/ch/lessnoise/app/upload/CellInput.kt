package ch.lessnoise.app.upload

import ch.lessnoise.dsp.geo.Lv95

/**
 * The placement step without a map: the contributor types or pastes where the phone is, and the
 * app turns it into an LV95 point **on the phone**. Accepted, in this order:
 *
 * - a hectare id: `h26833_12479`
 * - WGS84 decimal degrees as a map app copies them: `47.3769, 8.5417` (latitude first; swapped
 *   automatically if clearly given as longitude, latitude), also with decimal commas `47,3769 8,5417`
 * - Swiss LV95 coordinates as map.geo.admin.ch shows them: `2'683'304, 1'247'925` (E, N), and the
 *   older LV03 form `683'304, 247'925`
 *
 * The result ([Parsed.Ok]) holds the true LV95 point ([Parsed.Ok.e], [Parsed.Ok.n]; for a hectare id
 * its centre) and its hectare. Nothing typed here is stored or sent: the caller moves the point by
 * the location noise (LocationPolicy.fromPasted → dsp geo/LocationNoise.kt, server/DESIGN.md §2
 * rule 2) and keeps only the shifted point.
 *
 * Since 0.6.0 this is the fallback of the location fix at «Start» of a shared measurement
 * («Koordinaten einfügen»); the fix itself (ch.lessnoise.app.location) goes through [fromLatLon],
 * the same WGS84 → LV95 path as a pasted «47.3769, 8.5417».
 */
object CellInput {

    sealed interface Parsed {
        /** [cell]: the hectare of the true point; [e]/[n]: the true LV95 point in metres. */
        data class Ok(val cell: String, val how: String, val e: Double, val n: Double) : Parsed
        data class Error(val message: String) : Parsed
    }

    private val NUMBER = Regex("-?\\d+(?:[.,]\\d+)?")

    fun parse(text: String): Parsed {
        val t = text.trim()
        if (t.isEmpty()) return Parsed.Error("Bitte Koordinaten eingeben.")
        if (Lv95.isCellId(t.lowercase())) {
            val sw = Lv95.parseCell(t.lowercase())!!
            return check(sw.e + 50, sw.n + 50, "Zelle")
        }

        // Swiss thousands separators (2'683'304) and spaces inside numbers are removed first.
        val cleaned = t.replace(Regex("(?<=\\d)['’ʼ\u2009\u202F](?=\\d)"), "")
        val nums = NUMBER.findAll(cleaned).map { it.value.replace(',', '.').toDouble() }.toList()
        if (nums.size != 2) return Parsed.Error("Zwei Zahlen erwartet, z. B. «47.3769, 8.5417» oder «2'683'304, 1'247'925».")
        val (a, b) = nums
        return when {
            // LV95 E, N (or N, E)
            a in 2_400_000.0..2_900_000.0 && b in 1_000_000.0..1_400_000.0 -> check(a, b, "LV95")
            b in 2_400_000.0..2_900_000.0 && a in 1_000_000.0..1_400_000.0 -> check(b, a, "LV95")
            // LV03 y, x: add the LV95 false origin
            a in 400_000.0..900_000.0 && b in 0.0..400_000.0 -> check(a + 2_000_000, b + 1_000_000, "LV03")
            // WGS84: Switzerland is at 45.8–47.9° N, 5.9–10.5° E
            isWgs84Switzerland(a, b) -> fromLatLon(a, b)
            isWgs84Switzerland(b, a) -> fromLatLon(b, a)
            else -> Parsed.Error("Diese Koordinaten liegen nicht in der Schweiz.")
        }
    }

    /**
     * WGS84 latitude/longitude in decimal degrees → the LV95 point and the hectare that contains it,
     * computed on the phone (Lv95.wgs84ToLv95, the map's formulas). Used for pasted WGS84
     * coordinates and for the location fixes. The caller keeps the result in local variables only;
     * this function stores nothing.
     */
    fun fromLatLon(lat: Double, lon: Double, how: String = "WGS84"): Parsed =
        if (isWgs84Switzerland(lat, lon)) Lv95.wgs84ToLv95(lat, lon).let { check(it.e, it.n, how) }
        else Parsed.Error("Dieser Standort liegt nicht in der Schweiz.")

    private fun isWgs84Switzerland(lat: Double, lon: Double): Boolean = lat in 45.0..48.5 && lon in 5.0..11.0

    private fun check(e: Double, n: Double, how: String): Parsed {
        val cell = Lv95.lv95ToCell(e, n)
        return if (Lv95.isInServerRange(cell)) Parsed.Ok(cell, how, e, n) else Parsed.Error("Diese Zelle liegt nicht in der Schweiz.")
    }
}
