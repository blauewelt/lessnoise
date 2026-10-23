package ch.lessnoise.app.position

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * LABOR·KARTE ONLY (0.6.9; the Online-Version gets the stand-in src/noposition/.../PositionWatch.kt):
 * while a «Messen» run goes, the measuring foreground service (type «location» added) reads the
 * position about once a minute at low power and feeds [PositionRule]. Only while the run goes;
 * removed at «Stopp». Never asks for a permission and never needs background location: without
 * ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION it does nothing ([foregroundType] null).
 *
 * Provider: Android's fused provider with QUALITY_BALANCED_POWER_ACCURACY (Android 12+; Wi-Fi and
 * cell, GPS only if another app has it on anyway), else the network provider; never a GPS request.
 * The Location objects are dropped in the callback: [PositionRule] keeps the first fix and the
 * latest distance in memory, nothing is stored, logged or sent.
 */
object PositionWatch {
    const val AVAILABLE = true

    /** About once a minute. */
    const val INTERVAL_MS = 60_000L

    /** A running watch. All calls on any thread. */
    interface Handle {
        /** Distance of the latest fix from the run's first one (m), null before a second fix. */
        val distanceM: Double?
        val moving: Boolean
        /** The upload hold this signal needs at [nowMs] ([PositionRule.holdMs]). */
        fun holdMs(nowMs: Long): Long
        /**
         * Ends the watch. Returns the time from which the run's end must be counted as «unterwegs»:
         * the last near fix if the latest fix was already away (a ride that was not confirmed yet), else null.
         */
        fun stop(): Long?
    }

    private fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * The foreground-service type to add for this signal, or null if location is not granted (then
     * the run has motion only). 0 before Android 10 (no service types; a foreground service may read
     * the position there anyway).
     */
    fun foregroundType(context: Context): Int? = foregroundType(granted(context), Build.VERSION.SDK_INT)

    /** [foregroundType] as a pure rule (PositionRuleTest). */
    fun foregroundType(granted: Boolean, sdk: Int): Int? {
        if (!granted) return null
        return if (sdk >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
    }

    /**
     * Starts reading on [handler]'s thread; [onChange] is called there with each change of the
     * signal. Null if location is not granted or no low-power provider is there.
     */
    @SuppressLint("MissingPermission") // granted() first; a later revocation throws SecurityException, caught
    fun start(
        context: Context, handler: Handler,
        /** 0.7.5: wall ms of the motion detector's latest reported movement, or null; read on [handler]'s thread. */
        lastMovementMs: () -> Long?,
        onChange: (PositionRule.Transition) -> Unit,
    ): Handle? {
        val app = context.applicationContext
        if (!granted(app)) return null
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        val rule = PositionRule()
        val lock = Any()
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
                val f = PositionRule.Fix(
                    timeMs = System.currentTimeMillis() - maxOf(0L, ageMs), latitude = l.latitude, longitude = l.longitude,
                    accuracyM = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                )
                val recent = PositionRule.motionRecent(lastMovementMs(), f.timeMs)
                val t = synchronized(lock) { rule.onFix(f, ageMs, motionRecent = recent) }
                if (t != null) onChange(t)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
                val request = LocationRequest.Builder(INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
                    .build()
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, { r -> handler.post(r) }, listener)
                true
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, INTERVAL_MS, 0f, listener, handler.looper)
                true
            } else false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!ok) return null
        return object : Handle {
            override val distanceM: Double? get() = synchronized(lock) { rule.distanceM.takeIf { rule.fixes >= 2 } }
            override val moving: Boolean get() = synchronized(lock) { rule.moving }
            override fun holdMs(nowMs: Long): Long = synchronized(lock) { rule.holdMs(nowMs) }
            override fun stop(): Long? {
                runCatching { lm.removeUpdates(listener) }
                val recent = PositionRule.motionRecent(lastMovementMs(), System.currentTimeMillis())
                return synchronized(lock) { rule.pendingAwaySinceMs(recent) }
            }
        }
    }
}
