package ch.stadtlaerm.app.location

import ch.stadtlaerm.app.upload.CellInput
import kotlin.math.ceil

/**
 * «Standort bestimmen» (since v0.5.1, Karten-Version and Labor-Version mit Karte only): the rules,
 * as pure Kotlin so that the JVM tests check them (LocationPolicyTest). The Android side, which asks
 * the LocationManager for one fix, is [LocationFetcher].
 *
 * Privacy (server/DESIGN.md §2.2, PRIVACY.md): the coordinates of a fix exist only as local
 * variables of [LocationFetcher.locate] and of [decide]; what leaves this object is an [Outcome],
 * which holds at most the hectare id and the accuracy in metres. Nothing here stores or logs.
 */
object LocationPolicy {

    /** Worst accepted accuracy (Android's 68 % radius). A 100 m hectare needs better than this. */
    const val MAX_ACCURACY_M = 75.0

    /** Stop waiting for the other provider once a fix is at least this good. */
    const val GOOD_ENOUGH_M = 25.0

    /** How long to wait for a fix before giving up (the cancel button ends it earlier). */
    const val TIMEOUT_MS = 30_000L

    /** A last-known location is used only if it is this fresh … */
    const val LAST_KNOWN_MAX_AGE_MS = 2 * 60_000L

    /** … and at least this accurate. Otherwise a current fix is requested. */
    const val LAST_KNOWN_MAX_ACCURACY_M = 50.0

    /** LocationManager provider names (LocationManager.GPS_PROVIDER / NETWORK_PROVIDER). */
    const val GPS = "gps"
    const val NETWORK = "network"

    /** What the person allowed. On Android 12+ «Ungefähr» grants only ACCESS_COARSE_LOCATION. */
    enum class Permission { PRECISE, APPROXIMATE_ONLY, DENIED }

    fun permission(fineGranted: Boolean, coarseGranted: Boolean): Permission = when {
        fineGranted -> Permission.PRECISE
        coarseGranted -> Permission.APPROXIMATE_ONLY
        else -> Permission.DENIED
    }

    /** The result of one «Standort bestimmen». Holds no coordinates. */
    sealed interface Outcome {
        /** Accepted: the hectare to use and the fix's accuracy in whole metres (rounded up). */
        data class Hectare(val cell: String, val accuracyM: Int) : Outcome

        /** Only «Ungefähr» was allowed: a fix would be off by up to ~2 km; none was requested. */
        data object ApproximateOnly : Outcome

        /** The fix was too inaccurate for a 100 m hectare; [accuracyM] null = the phone gave none. */
        data class TooInaccurate(val accuracyM: Int?) : Outcome

        /** The fix is not in Switzerland (or the hectare is outside the server's range). */
        data class OutsideSwitzerland(val message: String) : Outcome

        /** Neither the GPS nor the network provider is switched on. */
        data object LocationOff : Outcome

        /** No fix within [TIMEOUT_MS]. */
        data object NoFix : Outcome

        /** The location permission was refused. */
        data object PermissionDenied : Outcome
    }

    /**
     * The providers to ask, GPS first (it is the precise one; network is usually faster indoors).
     * Both are asked at the same time; see [LocationFetcher].
     */
    fun providersToAsk(gpsEnabled: Boolean, networkEnabled: Boolean): List<String> =
        listOfNotNull(GPS.takeIf { gpsEnabled }, NETWORK.takeIf { networkEnabled })

    /**
     * Whether a last-known location may be used instead of a new fix: only if it is younger than
     * [LAST_KNOWN_MAX_AGE_MS] and reports an accuracy of at most [LAST_KNOWN_MAX_ACCURACY_M].
     * [accuracyM] null = the location reports no accuracy.
     */
    fun acceptLastKnown(ageMs: Long, accuracyM: Double?): Boolean =
        ageMs in 0..LAST_KNOWN_MAX_AGE_MS && accuracyM != null && !accuracyM.isNaN() && accuracyM <= LAST_KNOWN_MAX_ACCURACY_M

    /** Whether a fix with this accuracy ends the wait for the other provider. */
    fun goodEnough(accuracyM: Double?): Boolean = accuracyM != null && !accuracyM.isNaN() && accuracyM <= GOOD_ENOUGH_M

    /** Whether fix a (accuracy [a]) is better than fix b (accuracy [b]); no accuracy counts as worst. */
    fun better(a: Double?, b: Double?): Boolean {
        val x = a?.takeIf { !it.isNaN() } ?: Double.POSITIVE_INFINITY
        val y = b?.takeIf { !it.isNaN() } ?: Double.POSITIVE_INFINITY
        return x < y
    }

    /**
     * The decision for one fix. The hectare is set only with «Genauer Standort» and an accuracy of
     * at most [MAX_ACCURACY_M]; otherwise the screen explains why and offers the alternatives.
     * [accuracyM] null = the fix reports no accuracy (treated as too inaccurate).
     */
    fun decide(lat: Double, lon: Double, accuracyM: Double?, precise: Boolean): Outcome {
        if (!precise) return Outcome.ApproximateOnly
        val acc = accuracyM?.takeIf { !it.isNaN() && it >= 0 }
        if (acc == null || acc > MAX_ACCURACY_M) return Outcome.TooInaccurate(acc?.let { roundUp(it) })
        return when (val p = CellInput.fromLatLon(lat, lon, how = "Standort")) {
            is CellInput.Parsed.Ok -> Outcome.Hectare(p.cell, roundUp(acc))
            is CellInput.Parsed.Error -> Outcome.OutsideSwitzerland(p.message)
        }
    }

    private fun roundUp(m: Double): Int = maxOf(1, ceil(m).toInt())

    /** «Hektare h26833_12479 · Genauigkeit ± 12 m». */
    fun hectareLine(cell: String, accuracyM: Int): String = "Hektare $cell · Genauigkeit ± $accuracyM m"

    /** Shown while waiting for the fix. */
    const val PROGRESS_TEXT = "Standort wird bestimmt …"

    /** One line under the button, before the permission dialog. */
    const val RATIONALE_TEXT =
        "Dafür fragt die App nach der Standort-Berechtigung. Gelesen wird der Standort nur, wenn du hier tippst, einmal; behalten wird nur die Hektare."

    /** The explanation for an outcome (Swiss spelling, «du»). */
    fun message(o: Outcome): String = when (o) {
        is Outcome.Hectare -> hectareLine(o.cell, o.accuracyM)
        Outcome.ApproximateOnly ->
            "Du hast nur den ungefähren Standort erlaubt. Für eine Hektare (100 × 100 m) braucht die App «Genauer Standort»: " +
                "In den App-Einstellungen unter «Berechtigungen → Standort» «Genauen Standort verwenden» einschalten und nochmals " +
                "«Standort bestimmen» tippen – oder die Koordinaten einfügen."
        is Outcome.TooInaccurate ->
            (if (o.accuracyM != null) "Der Standort ist nur auf ± ${o.accuracyM} m genau. " else "Das Telefon hat keine Genauigkeit angegeben. ") +
                "Für eine Hektare (100 × 100 m) reicht das nicht, sie wurde nicht übernommen. Versuch es nochmals am Fenster oder im Freien, " +
                "mit eingeschaltetem WLAN – oder füge die Koordinaten ein."
        is Outcome.OutsideSwitzerland -> o.message + " Die Lärmkarte gibt es nur für die Schweiz."
        Outcome.LocationOff ->
            "Die Standortdienste des Telefons sind ausgeschaltet. Schalte sie in den Einstellungen ein und tippe nochmals " +
                "«Standort bestimmen» – oder füge die Koordinaten ein."
        Outcome.NoFix ->
            "In ${TIMEOUT_MS / 1000} Sekunden kam kein Standort. Am Fenster oder im Freien klappt es meist besser; " +
                "du kannst es nochmals versuchen oder die Koordinaten einfügen."
        Outcome.PermissionDenied ->
            "Ohne Standort-Berechtigung kann die App den Standort nicht bestimmen. Du kannst sie in den App-Einstellungen " +
                "erlauben («Genauer Standort», «Nur während der Nutzung der App») – oder die Koordinaten einfügen."
    }
}
