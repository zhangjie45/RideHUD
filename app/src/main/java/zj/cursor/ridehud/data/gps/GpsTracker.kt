package zj.cursor.ridehud.data.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class GpsSample(
    val speedKmh: Float = 0f,
    val distanceM: Float = 0f,
    val hasFix: Boolean = false,
    val location: Location? = null,
)

/**
 * Phone GPS speed/distance for cycling HUD.
 * Uses LocationManager so it works on Huawei devices without Google Play Services.
 */
class GpsTracker(context: Context) {
    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _sample = MutableStateFlow(GpsSample())
    val sample: StateFlow<GpsSample> = _sample.asStateFlow()

    private var lastLocation: Location? = null
    private var totalDistanceM = 0f
    private var smoothedSpeedMps = 0f
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onNewLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        totalDistanceM = 0f
        lastLocation = null
        smoothedSpeedMps = 0f
        _sample.value = GpsSample()

        val minTimeMs = 1000L
        val minDistanceM = 0f
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        } catch (_: SecurityException) {
            running = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        try {
            locationManager.removeUpdates(listener)
        } catch (_: SecurityException) {
        }
    }

    fun resetDistance() {
        totalDistanceM = 0f
        lastLocation = null
        val current = _sample.value
        _sample.value = current.copy(distanceM = 0f)
    }

    private fun onNewLocation(location: Location) {
        if (!running) return
        // Prefer GPS-quality fixes; allow network only when no GPS yet
        if (location.provider == LocationManager.NETWORK_PROVIDER &&
            lastLocation?.provider == LocationManager.GPS_PROVIDER &&
            location.accuracy > 25f
        ) {
            return
        }

        val previous = lastLocation
        if (previous != null) {
            val delta = previous.distanceTo(location)
            val dt = (location.time - previous.time) / 1000f
            // Filter GPS jumps
            if (delta < 80f && dt > 0f && location.accuracy <= 40f) {
                totalDistanceM += delta
            }
        }
        lastLocation = location

        val rawSpeedMps = when {
            location.hasSpeed() && location.speed >= 0f -> location.speed
            previous != null -> {
                val dt = (location.time - previous.time) / 1000f
                if (dt > 0.3f) previous.distanceTo(location) / dt else smoothedSpeedMps
            }
            else -> 0f
        }

        // Light exponential smoothing to reduce HUD flicker
        smoothedSpeedMps = if (smoothedSpeedMps == 0f) {
            rawSpeedMps
        } else {
            0.65f * smoothedSpeedMps + 0.35f * rawSpeedMps
        }

        // Clamp tiny noise to zero when nearly stopped
        val displayMps = if (smoothedSpeedMps < 0.4f) 0f else smoothedSpeedMps
        val speedKmh = displayMps * 3.6f

        _sample.value = GpsSample(
            speedKmh = speedKmh,
            distanceM = totalDistanceM,
            hasFix = location.accuracy < 50f || abs(speedKmh) > 0.5f,
            location = location,
        )
    }
}
