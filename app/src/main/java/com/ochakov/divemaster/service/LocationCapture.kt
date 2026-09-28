package com.ochakov.divemaster.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import java.util.concurrent.Executor

/** A position with its accuracy (metres, -1 if unknown) and fix time. */
data class LocationFix(val lat: Double, val lon: Double, val accuracyM: Double, val epochMs: Long)

/**
 * Where did the dive happen? Platform LocationManager only, no Play Services
 * dependency. Two uses: the freshest last-known position at dive start
 * (free, may be minutes old) and one fresh fix requested at the first
 * surface touch after the dive. GPS needs sky and a cold start after a dive
 * can take a while, so a null result is retried up to [MAX_ATTEMPTS] times.
 * Everything is best-effort and silent without the location permission.
 */
class LocationCapture(private val context: Context) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var inFlight: CancellationSignal? = null

    val permitted: Boolean
        get() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Freshest last-known position across all providers, or null. */
    fun lastKnown(): LocationFix? {
        if (!permitted) return null
        return manager.allProviders
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toFix()
    }

    /**
     * One fresh fix, delivered on a binder thread at most once; nothing is
     * delivered if every attempt times out. A new request cancels a pending one.
     */
    fun requestFix(onFix: (LocationFix) -> Unit) {
        if (!permitted) return
        inFlight?.cancel()
        val provider = PROVIDERS.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) {
            Log.i(TAG, "no enabled location provider")
            return
        }
        attempt(provider, 1, onFix)
    }

    private fun attempt(provider: String, number: Int, onFix: (LocationFix) -> Unit) {
        val signal = CancellationSignal()
        inFlight = signal
        runCatching {
            // The platform times each call out after ~30 s; retry to cover a cold GPS start.
            manager.getCurrentLocation(provider, signal, DIRECT_EXECUTOR) { location ->
                when {
                    signal.isCanceled -> Unit
                    location != null -> {
                        inFlight = null
                        Log.i(TAG, "fix from $provider on attempt $number: +/-${location.accuracy} m")
                        onFix(location.toFix())
                    }

                    number < MAX_ATTEMPTS -> attempt(provider, number + 1, onFix)
                    else -> {
                        inFlight = null
                        Log.i(TAG, "no fix from $provider after $MAX_ATTEMPTS attempts")
                    }
                }
            }
        }.onFailure { Log.w(TAG, "location request failed", it) }
    }

    fun cancel() {
        inFlight?.cancel()
        inFlight = null
    }

    private fun Location.toFix() =
        LocationFix(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else -1.0, time)

    private companion object {
        const val TAG = "DiveLocation"
        const val MAX_ATTEMPTS = 3
        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER)
        val DIRECT_EXECUTOR = Executor { it.run() }
    }
}
