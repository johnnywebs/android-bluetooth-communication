package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import com.example.model.UserLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationTracker(private val context: Context) {
    private val TAG = "LocationTracker"
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocation = MutableStateFlow<UserLocation?>(null)
    val currentLocation: StateFlow<UserLocation?> = _currentLocation.asStateFlow()

    private var isTracking = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking || locationManager == null) return

        try {
            // Check last known location first for immediate coordinates
            var bestLocation: Location? = null
            for (provider in locationManager.getProviders(true)) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }
            bestLocation?.let { updateLocation(it) }

            // Request GPS updates
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L, // 3 seconds
                    2f,    // 2 meters
                    locationListener
                )
            }

            // Also request Network updates as fallback
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    4000L,
                    3f,
                    locationListener
                )
            }

            isTracking = true
            Log.d(TAG, "Location tracking started")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location tracking", e)
        }
    }

    fun stopTracking() {
        if (!isTracking || locationManager == null) return
        try {
            locationManager.removeUpdates(locationListener)
            isTracking = false
            Log.d(TAG, "Location tracking stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location tracking", e)
        }
    }

    private fun updateLocation(loc: Location) {
        _currentLocation.value = UserLocation(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitude = loc.altitude,
            accuracy = loc.accuracy,
            timestamp = loc.time
        )
    }

    companion object {
        fun calculateDistanceAndBearing(
            from: UserLocation,
            to: UserLocation
        ): Pair<Float, Float> {
            val results = FloatArray(2)
            Location.distanceBetween(
                from.latitude,
                from.longitude,
                to.latitude,
                to.longitude,
                results
            )
            val distanceMeters = results[0]
            val bearingDegrees = (results[1] + 360f) % 360f
            return Pair(distanceMeters, bearingDegrees)
        }
    }
}
