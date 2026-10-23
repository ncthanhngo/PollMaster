package com.ncthanhngo.pollmaster.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One-shot location through the platform LocationManager (GPS first, then network). */
class LocationHelper(private val context: Context) {
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enabledProviders(): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        return enabledProviders().mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** A fresh fix, or the last known one after [timeoutMs]; null without permission or providers. */
    suspend fun current(timeoutMs: Long = 15_000): Location? {
        if (!hasPermission()) return null
        val provider = enabledProviders().firstOrNull() ?: return null
        return withTimeoutOrNull(timeoutMs) { request(provider) } ?: lastKnown()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun request(provider: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                if (cont.isActive) cont.resume(loc)
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) cont.resume(location)
                }
            }
            cont.invokeOnCancellation { manager.removeUpdates(listener) }
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }
}
