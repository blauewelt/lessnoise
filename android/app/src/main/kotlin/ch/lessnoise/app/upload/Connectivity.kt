package ch.lessnoise.app.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the phone has a network connection at all, for the sharing status line («Kein Internet –
 * wird nachgeholt», app 0.6.3). Reads only the system's connection state (ACCESS_NETWORK_STATE,
 * Karten editions); opens no connection. Without the permission (Offline/Labor) it reports online.
 */
object Connectivity {
    fun online(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(true)
            awaitClose {}
            return@callbackFlow
        }
        fun now(): Boolean = try {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: SecurityException) {
            true
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(now()) }
            override fun onLost(network: Network) { trySend(now()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(now()) }
        }
        trySend(now())
        val registered = try {
            cm.registerDefaultNetworkCallback(callback)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
        awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
