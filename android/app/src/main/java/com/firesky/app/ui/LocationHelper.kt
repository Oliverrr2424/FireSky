package com.firesky.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.firesky.app.data.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

object LocationHelper {
    const val FALLBACK_NAME = "Current location"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(true)
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        val lastKnown = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 60_000L) return lastKnown

        val location = withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { continuation ->
                var resumed = false
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(result: Location) {
                        if (!resumed) {
                            resumed = true
                            runCatching { manager.removeUpdates(this) }
                            continuation.resume(result)
                        }
                    }

                    override fun onProviderDisabled(provider: String) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                }
                providers.forEach { provider ->
                    runCatching {
                        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
            }
        }
        return location ?: lastKnown
    }

    /** Builds a Place for a device fix, named after the nearest locality when the geocoder knows it. */
    suspend fun placeFor(context: Context, location: Location): Place {
        val address = reverseGeocode(context, location.latitude, location.longitude)
        val name = address?.let { it.locality ?: it.subLocality ?: it.subAdminArea }?.takeIf { it.isNotBlank() }
        return Place(
            name = name ?: FALLBACK_NAME,
            admin1 = if (name != null) address?.adminArea.orEmpty() else "",
            countryCode = address?.countryCode?.uppercase(Locale.US).orEmpty(),
            latitude = String.format(Locale.US, "%.4f", location.latitude).toDouble(),
            longitude = String.format(Locale.US, "%.4f", location.longitude).toDouble(),
        )
    }

    private suspend fun reverseGeocode(context: Context, latitude: Double, longitude: Double): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return withTimeoutOrNull(8_000L) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    runCatching {
                        geocoder.getFromLocation(
                            latitude,
                            longitude,
                            1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                                }

                                override fun onError(errorMessage: String?) {
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            },
                        )
                    }.onFailure { if (continuation.isActive) continuation.resume(null) }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
                }
            }
        }
    }
}
