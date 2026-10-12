package ch.stadtlaerm.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One location fix for «Standort bestimmen», with the platform [LocationManager] only (no Google
 * Play Services: the app is open source and adds no proprietary library). Called only from the
 * button in «Messwerte teilen» while the screen is open; never in the background, never on a
 * schedule. The rules are in [LocationPolicy].
 *
 * 1. Permission: ACCESS_FINE_LOCATION → go on; only ACCESS_COARSE_LOCATION → ApproximateOnly
 *    (no fix is requested: «Ungefähr» is off by up to ~2 km); none → PermissionDenied.
 * 2. Providers: GPS and network, whichever are enabled; none → LocationOff.
 * 3. A last-known location only if younger than 2 min and accurate to ≤ 50 m.
 * 4. Otherwise one current fix from every enabled provider at the same time: on API 30+
 *    `getCurrentLocation`, on API 26–29 `requestLocationUpdates` removed after the first update.
 *    The first fix with ≤ 25 m ends the wait; otherwise the best fix when all providers answered,
 *    or when [LocationPolicy.TIMEOUT_MS] is over. Cancelling the coroutine (the «Abbrechen» button,
 *    leaving the screen) cancels the requests.
 *
 * The coordinates exist only in local variables here and in [LocationPolicy.decide]; the result is
 * an [LocationPolicy.Outcome] (hectare id and accuracy). Nothing is stored or logged.
 */
object LocationFetcher {

    fun hasPreciseLocation(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun permission(context: Context): LocationPolicy.Permission =
        LocationPolicy.permission(
            fineGranted = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
            coarseGranted = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        )

    /** The two permissions, requested together (Android 12+ then offers «Genau» / «Ungefähr»). */
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(context: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    /** Must be called on the main thread (the API 26–29 listener runs on the main looper). */
    @SuppressLint("MissingPermission") // checked first: permission() == PRECISE
    suspend fun locate(context: Context): LocationPolicy.Outcome {
        when (permission(context)) {
            LocationPolicy.Permission.DENIED -> return LocationPolicy.Outcome.PermissionDenied
            LocationPolicy.Permission.APPROXIMATE_ONLY -> return LocationPolicy.Outcome.ApproximateOnly
            LocationPolicy.Permission.PRECISE -> Unit
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return LocationPolicy.Outcome.LocationOff
        val providers = LocationPolicy.providersToAsk(
            gpsEnabled = enabled(lm, LocationManager.GPS_PROVIDER),
            networkEnabled = enabled(lm, LocationManager.NETWORK_PROVIDER),
        )
        if (providers.isEmpty()) return LocationPolicy.Outcome.LocationOff

        return try {
            val fix = freshLastKnown(lm, providers) ?: currentFix(context, lm, providers)
                ?: return LocationPolicy.Outcome.NoFix
            LocationPolicy.decide(fix.latitude, fix.longitude, accuracyOf(fix), precise = true)
        } catch (_: SecurityException) {
            // The permission was revoked in the meantime.
            LocationPolicy.Outcome.PermissionDenied
        }
    }

    private fun enabled(lm: LocationManager, provider: String): Boolean =
        try { lm.isProviderEnabled(provider) } catch (_: IllegalArgumentException) { false }

    private fun accuracyOf(l: Location): Double? = if (l.hasAccuracy()) l.accuracy.toDouble() else null

    private fun ageMs(l: Location): Long = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    @SuppressLint("MissingPermission")
    private fun freshLastKnown(lm: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { p -> lm.getLastKnownLocation(p) }
            .filter { LocationPolicy.acceptLastKnown(ageMs(it), accuracyOf(it)) }
            .minByOrNull { accuracyOf(it) ?: Double.POSITIVE_INFINITY }

    /** One request per provider at the same time; see the class comment for when it stops. */
    @SuppressLint("MissingPermission")
    private suspend fun currentFix(context: Context, lm: LocationManager, providers: List<String>): Location? {
        // Every provider sends exactly one answer: a fix, or null (no fix / provider switched off).
        val answers = Channel<Location?>(capacity = Channel.UNLIMITED)
        val cancels = mutableListOf<() -> Unit>()
        try {
            for (p in providers) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    cancels += { signal.cancel() }
                    lm.getCurrentLocation(p, signal, ContextCompat.getMainExecutor(context)) { loc -> answers.trySend(loc) }
                } else {
                    val listener = SingleUpdateListener(lm) { loc -> answers.trySend(loc) }
                    cancels += { lm.removeUpdates(listener) }
                    lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                }
            }
            var best: Location? = null
            var answered = 0
            withTimeoutOrNull(LocationPolicy.TIMEOUT_MS) {
                while (answered < providers.size) {
                    val loc = answers.receive()
                    answered++
                    if (loc != null && (best == null || LocationPolicy.better(accuracyOf(loc), best?.let { accuracyOf(it) }))) best = loc
                    if (LocationPolicy.goodEnough(best?.let { accuracyOf(it) })) break
                }
            }
            return best
        } finally {
            cancels.forEach { runCatching { it() } }
            answers.close()
        }
    }

    /**
     * API 26–29: the first update, then the listener removes itself. All four callbacks are
     * implemented: before API 30 the interface had no default methods, and Android would call them.
     */
    private class SingleUpdateListener(
        private val lm: LocationManager,
        private val onAnswer: (Location?) -> Unit,
    ) : LocationListener {
        private var done = false

        private fun answer(loc: Location?) {
            if (done) return
            done = true
            lm.removeUpdates(this)
            onAnswer(loc)
        }

        override fun onLocationChanged(location: Location) = answer(location)
        override fun onProviderDisabled(provider: String) = answer(null)
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
}
