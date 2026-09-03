package com.blindnav.app.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Locale

class LocationHelper(private val context: Context) {

    private val tag = "LocationHelper"

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null
    var isContinuousTrackingActive = false
        private set

    var lastLocation: Location? = null
        private set

    var lastAddress: String? = null
        private set

    var lastStreetName: String? = null
        private set

    fun hasLocationPermission(): Boolean {
        val finePerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarsePerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return finePerm || coarsePerm
    }

    /**
     * Starts continuous high-accuracy location tracking, like Google Maps navigation.
     * Updates every [intervalMs] or when user moves [minDisplacementMeters].
     */
    fun startContinuousTracking(
        intervalMs: Long = 5000L,
        minDisplacementMeters: Float = 4.0f,
        onLocationUpdate: (Location, String, Boolean) -> Unit
    ) {
        if (!hasLocationPermission()) {
            Log.w(tag, "Location permission not granted for continuous tracking.")
            return
        }

        if (isContinuousTrackingActive) {
            stopContinuousTracking()
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(2500L)
            .setMinUpdateDistanceMeters(minDisplacementMeters)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastLocation = loc

                Thread {
                    val address = reverseGeocode(loc.latitude, loc.longitude)
                    val street = extractStreetName(loc.latitude, loc.longitude)
                    val streetChanged = street != null && street != lastStreetName

                    lastAddress = address
                    if (street != null) {
                        lastStreetName = street
                    }

                    onLocationUpdate(loc, address ?: "Lat: ${String.format(Locale.US, "%.4f", loc.latitude)}, Lng: ${String.format(Locale.US, "%.4f", loc.longitude)}", streetChanged)
                }.start()
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            isContinuousTrackingActive = true
            Log.i(tag, "Started continuous real-time Maps-style location tracking.")
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException starting location updates: ${e.message}")
        }
    }

    fun stopContinuousTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        isContinuousTrackingActive = false
        Log.i(tag, "Stopped continuous location tracking.")
    }

    /**
     * Fetches current location once asynchronously.
     */
    fun getCurrentLocation(
        onResult: (Location?, String?, String?) -> Unit
    ) {
        if (!hasLocationPermission()) {
            onResult(null, null, null)
            return
        }

        try {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (location != null) {
                    lastLocation = location
                    Thread {
                        val address = reverseGeocode(location.latitude, location.longitude)
                        lastAddress = address
                        val mapsUrl = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                        onResult(location, address, mapsUrl)
                    }.start()
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                        if (lastLoc != null) {
                            lastLocation = lastLoc
                            Thread {
                                val address = reverseGeocode(lastLoc.latitude, lastLoc.longitude)
                                lastAddress = address
                                val mapsUrl = "https://maps.google.com/?q=${lastLoc.latitude},${lastLoc.longitude}"
                                onResult(lastLoc, address, mapsUrl)
                            }.start()
                        } else {
                            onResult(null, null, null)
                        }
                    }.addOnFailureListener {
                        onResult(null, null, null)
                    }
                }
            }.addOnFailureListener {
                onResult(null, null, null)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
            onResult(null, null, null)
        }
    }

    /**
     * Opens user's live position directly in Google Maps walking navigation mode.
     */
    fun openInGoogleMaps() {
        val loc = lastLocation
        val uri = if (loc != null) {
            Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}(Current+Position)&mode=w")
        } else {
            Uri.parse("geo:0,0?q=my+location")
        }

        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(mapIntent)
        } catch (e: Exception) {
            // Fallback to browser Google Maps if Maps app is not installed
            val browserUri = if (loc != null) {
                Uri.parse("https://www.google.com/maps/search/?api=1&query=${loc.latitude},${loc.longitude}")
            } else {
                Uri.parse("https://www.google.com/maps")
            }
            val browserIntent = Intent(Intent.ACTION_VIEW, browserUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(browserIntent)
        }
    }

    /**
     * Reverse geocodes coordinates to a human-readable street / city address.
     */
    fun reverseGeocode(latitude: Double, longitude: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            formatAddress(addresses)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractStreetName(latitude: Double, longitude: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                addresses[0].thoroughfare ?: addresses[0].featureName
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun formatAddress(addresses: List<Address>?): String? {
        if (addresses.isNullOrEmpty()) return null
        val address = addresses[0]
        val feature = address.featureName ?: ""
        val thoroughfare = address.thoroughfare ?: ""
        val locality = address.locality ?: address.subAdminArea ?: ""

        val parts = listOf(feature, thoroughfare, locality).filter { it.isNotBlank() }.distinct()
        return if (parts.isNotEmpty()) parts.joinToString(", ") else address.getAddressLine(0)
    }

    /**
     * Formats heading/bearing in degrees into cardinal directions (North, South-East, etc.)
     */
    fun getCompassDirection(bearing: Float): String {
        val directions = arrayOf("North", "North-East", "East", "South-East", "South", "South-West", "West", "North-West")
        val index = (((bearing % 360) + 22.5) / 45.0).toInt() % 8
        return directions[index]
    }
}
