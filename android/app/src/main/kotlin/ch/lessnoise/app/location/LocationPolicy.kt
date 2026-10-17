package ch.lessnoise.app.location

import ch.lessnoise.app.upload.CellInput
import ch.lessnoise.dsp.geo.LocationNoise
import kotlin.math.ceil

/**
 * One location fix (Karten editions only): the rules, as pure Kotlin so that the JVM tests check
 * them (LocationPolicyTest). The Android side, which asks the LocationManager for one fix, is
 * [LocationFetcher]. Two uses since 0.6.0:
 *
 * 1. At «Start» of a shared measurement: [decide] turns the fix into the LV95 point and moves it at
 *    once by the location noise ([LocationNoise.shift], server/DESIGN.md §2 rule 2); what leaves
 *    this object is an [Outcome], which holds at most the **shifted** point and the accuracy in
 *    metres. Pasted coordinates go the same way ([fromPasted]).
 * 2. «Laut! Melden»: [decideReport] keeps the coordinates in a [ReportOutcome.Fix], the ONE result
 *    that carries them. The report screen puts it into the report queue (upload/Reports.kt), which
 *    deletes the coordinates once the server has the report.
 *
 * Nothing here stores or logs.
 */
object LocationPolicy {

    /**
     * Worst accepted accuracy (Android's 68 % radius) at «Start». Kept from the hectare era: the
     * shifted point should be off by the noise the contributor chose, not by a poor fix on top.
     */
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

    /**
     * The phone's location-noise secret (NoiseSecret, never sent) and the setting «Standort
     * verschieben» (σ in metres): what [decide] and [fromPasted] need to move the point.
     */
    class Noise(val secret: ByteArray, val sigmaM: Int) {
        init {
            require(secret.size == LocationNoise.SECRET_BYTES && LocationNoise.isSigma(sigmaM))
        }
    }

    /** The result of the fix at «Start»: the shifted point, or why there is none. Holds no true coordinates. */
    sealed interface Outcome {
        /**
         * Accepted: the point to send, already moved by the location noise, and the fix's accuracy
         * in whole metres (rounded up; null for pasted coordinates).
         */
        data class Shifted(val point: LocationNoise.Point, val accuracyM: Int?) : Outcome

        /** Only «Ungefähr» was allowed: a fix would be off by up to ~2 km; none was requested. */
        data object ApproximateOnly : Outcome

        /** The fix at «Start» was too inaccurate (over [MAX_ACCURACY_M]); [accuracyM] null = the phone gave none. Never for a report. */
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
     * The decision for one fix. A point is produced only with «Genauer Standort» and an accuracy of
     * at most [MAX_ACCURACY_M]; otherwise the screen explains why and offers the alternatives.
     * [accuracyM] null = the fix reports no accuracy (treated as too inaccurate). The true point
     * exists only inside this function; the result holds the shifted one.
     */
    fun decide(lat: Double, lon: Double, accuracyM: Double?, precise: Boolean, noise: Noise): Outcome {
        if (!precise) return Outcome.ApproximateOnly
        val acc = accuracyM?.takeIf { !it.isNaN() && it >= 0 }
        if (acc == null || acc > MAX_ACCURACY_M) return Outcome.TooInaccurate(acc?.let { roundUp(it) })
        return when (val p = CellInput.fromLatLon(lat, lon, how = "Standort")) {
            is CellInput.Parsed.Ok -> Outcome.Shifted(shift(p, noise), roundUp(acc))
            is CellInput.Parsed.Error -> Outcome.OutsideSwitzerland(p.message)
        }
    }

    /** «Koordinaten einfügen»: the pasted place, moved by the location noise like a fix. */
    fun fromPasted(parsed: CellInput.Parsed.Ok, noise: Noise): Outcome.Shifted = Outcome.Shifted(shift(parsed, noise), null)

    private fun shift(p: CellInput.Parsed.Ok, noise: Noise): LocationNoise.Point =
        LocationNoise.shift(noise.secret, p.e, p.n, noise.sigmaM)

    /** The result of the fix for a report: the coordinates, or why there are none. */
    sealed interface ReportOutcome {
        /**
         * Accepted: the place as the phone has it, its accuracy (m, sent with the report and shown
         * in the confirmation) and its cell. Goes straight into the report queue.
         */
        data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val cell: String) : ReportOutcome {
            val accuracyText: Int get() = roundUp(accuracyM)
        }

        /** No usable fix; [outcome] says why (never [Outcome.Shifted], never [Outcome.TooInaccurate]). */
        data class Problem(val outcome: Outcome) : ReportOutcome
    }

    /**
     * «Laut! Melden»: precise permission and a place in Switzerland. Since 0.6.7 a report never
     * refuses a fix because of its accuracy: the fix is used as it is, and its accuracy goes with
     * the report and into the confirmation («Standort ± 107 m»). A fix that reports no accuracy
     * at all (rare; the server needs one) counts as no fix ([Outcome.NoFix], «Nochmals»).
     */
    fun decideReport(lat: Double, lon: Double, accuracyM: Double?, precise: Boolean): ReportOutcome {
        if (!precise) return ReportOutcome.Problem(Outcome.ApproximateOnly)
        val acc = accuracyM?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0 }
            ?: return ReportOutcome.Problem(Outcome.NoFix)
        return when (val p = CellInput.fromLatLon(lat, lon, how = "Standort")) {
            is CellInput.Parsed.Ok -> ReportOutcome.Fix(lat, lon, acc, p.cell)
            is CellInput.Parsed.Error -> ReportOutcome.Problem(Outcome.OutsideSwitzerland(p.message))
        }
    }

    internal fun roundUp(m: Double): Int = maxOf(1, ceil(m).toInt())

    /** «Gefunden (± 12 m), verschoben um etwa 50 m»; no cell id, it means nothing to people (0.6.1). */
    fun shiftedLine(o: Outcome.Shifted): String =
        (o.accuracyM?.let { "Gefunden (± $it m)" } ?: "Erkannt") + ", verschoben um etwa ${o.point.sigmaM} m"

    /** Shown while waiting for the fix. */
    const val PROGRESS_TEXT = "Standort wird bestimmt …"

    /** Why there is no usable fix, one short sentence (Swiss spelling, «du»); the buttons say what to do. */
    fun message(o: Outcome): String = when (o) {
        is Outcome.Shifted -> shiftedLine(o)
        Outcome.ApproximateOnly -> "Bitte «Genauer Standort» erlauben (App-Einstellungen)."
        is Outcome.TooInaccurate ->
            "Zu ungenau" + (o.accuracyM?.let { " (± $it m)" } ?: "") + " – am Fenster mit WLAN klappt es meist besser."
        is Outcome.OutsideSwitzerland -> o.message
        Outcome.LocationOff -> "Der Standort des Telefons ist ausgeschaltet."
        Outcome.NoFix -> "Kein Standort gefunden – versuch es am Fenster nochmals."
        Outcome.PermissionDenied -> "Ohne Erlaubnis für «Genauer Standort» geht es nicht."
    }
}
