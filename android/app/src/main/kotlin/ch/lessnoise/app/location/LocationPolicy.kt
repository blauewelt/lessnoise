package ch.lessnoise.app.location

import ch.lessnoise.app.upload.CellInput
import kotlin.math.ceil

/**
 * One location fix (Karten editions only): the rules, as pure Kotlin so that the JVM tests check
 * them (LocationPolicyTest). The Android side, which asks the LocationManager for one fix, is
 * [LocationFetcher]. Two uses since 0.6.0:
 *
 * 1. At «Start» of a shared measurement: [decide] turns the fix into the 100 m cell; what leaves
 *    this object is an [Outcome], which holds at most the cell id and the accuracy in metres.
 * 2. «Laut! Melden»: [decideReport] keeps the coordinates in a [ReportOutcome.Fix], the ONE result
 *    that carries them. The report screen puts it into the report queue (upload/Reports.kt), which
 *    deletes the coordinates once the server has the report.
 *
 * Nothing here stores or logs.
 */
object LocationPolicy {

    /** Worst accepted accuracy (Android's 68 % radius). A 100 m hectare needs better than this. */
    const val MAX_ACCURACY_M = 75.0

    /** Worst accepted accuracy of a «Laut! Melden» report. */
    const val REPORT_MAX_ACCURACY_M = 50.0

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

    /** The result of the fix at «Start»: the cell, or why there is none. Holds no coordinates. */
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

    /** The result of the fix for a report: the coordinates, or why there are none. */
    sealed interface ReportOutcome {
        /** Accepted: exact place, accuracy (m) and its cell. Goes straight into the report queue. */
        data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val cell: String) : ReportOutcome {
            val accuracyText: Int get() = roundUp(accuracyM)
        }

        /** No usable fix; [outcome] says why (never [Outcome.Hectare]). */
        data class Problem(val outcome: Outcome) : ReportOutcome
    }

    /** «Laut! Melden»: precise permission, accuracy ≤ [REPORT_MAX_ACCURACY_M] and a place in Switzerland. */
    fun decideReport(lat: Double, lon: Double, accuracyM: Double?, precise: Boolean): ReportOutcome {
        if (!precise) return ReportOutcome.Problem(Outcome.ApproximateOnly)
        val acc = accuracyM?.takeIf { !it.isNaN() && it >= 0 }
        if (acc == null || acc > REPORT_MAX_ACCURACY_M) return ReportOutcome.Problem(Outcome.TooInaccurate(acc?.let { roundUp(it) }))
        return when (val p = CellInput.fromLatLon(lat, lon, how = "Standort")) {
            is CellInput.Parsed.Ok -> ReportOutcome.Fix(lat, lon, acc, p.cell)
            is CellInput.Parsed.Error -> ReportOutcome.Problem(Outcome.OutsideSwitzerland(p.message))
        }
    }

    internal fun roundUp(m: Double): Int = maxOf(1, ceil(m).toInt())

    /** «Gefunden (± 12 m)»: the cell id means nothing to people (0.6.1). */
    fun hectareLine(@Suppress("UNUSED_PARAMETER") cell: String, accuracyM: Int): String = "Gefunden (± $accuracyM m)"

    /** Shown while waiting for the fix. */
    const val PROGRESS_TEXT = "Standort wird bestimmt …"

    /** Why there is no usable fix, one short sentence (Swiss spelling, «du»); the buttons say what to do. */
    fun message(o: Outcome): String = when (o) {
        is Outcome.Hectare -> hectareLine(o.cell, o.accuracyM)
        Outcome.ApproximateOnly -> "Bitte «Genauer Standort» erlauben (App-Einstellungen)."
        is Outcome.TooInaccurate ->
            "Zu ungenau" + (o.accuracyM?.let { " (± $it m)" } ?: "") + " – am Fenster mit WLAN klappt es meist besser."
        is Outcome.OutsideSwitzerland -> o.message
        Outcome.LocationOff -> "Der Standort des Telefons ist ausgeschaltet."
        Outcome.NoFix -> "Kein Standort gefunden – versuch es am Fenster nochmals."
        Outcome.PermissionDenied -> "Ohne Erlaubnis für «Genauer Standort» geht es nicht."
    }
}
