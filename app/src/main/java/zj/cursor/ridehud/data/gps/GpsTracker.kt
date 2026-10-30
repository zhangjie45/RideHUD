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
import zj.cursor.ridehud.domain.RideHudDefaults
import kotlin.math.abs

data class GpsSample(
    val speedKmh: Float = 0f,
    val distanceM: Float = 0f,
    val hasFix: Boolean = false,
    val location: Location? = null,
)

/**
 * Phone GPS speed/distance for cycling HUD.
 *
 * Instant speed ≈ REA [SpeedSource]:
 * raw (Doppler) → cleanSpeedData → **light** EMA (kill chip jitter, not 1s stairs).
 *
 * Max speed sampling (5 s) stays in [RideRecorder].
 */
class GpsTracker(context: Context) {
    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _sample = MutableStateFlow(GpsSample())
    val sample: StateFlow<GpsSample> = _sample.asStateFlow()

    private var lastLocation: Location? = null
    private var totalDistanceM = 0f
    private var displaySpeedKmh = 0f
    private var running = false
    /** When false (ride paused), keep last distance — don't add GPS drift. */
    private var countingDistance = true
    /** SpeedSource sticky: raw looked ~10× too large. */
    private var speedNeedDivideTen = false

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
        displaySpeedKmh = 0f
        speedNeedDivideTen = false
        _sample.value = GpsSample()

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            seedLastKnownForAltitude()
        } catch (_: SecurityException) {
            running = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownForAltitude() {
        val cached = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            }
        }.filter { it.hasAltitude() }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else 999f }
        cached?.let { publishAltitudeOnly(it) }
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

    fun setCountingDistance(enabled: Boolean) {
        countingDistance = enabled
        if (!enabled) {
            lastLocation = null
        }
    }

    private fun onNewLocation(location: Location) {
        if (!running) return
        val provider = location.provider
        val isGps = provider == null || provider == LocationManager.GPS_PROVIDER
        val isNetwork = provider == LocationManager.NETWORK_PROVIDER
        if (!isGps && !isNetwork) return

        if (RideHudDefaults.isInvalidGpsSentinel(location.longitude, location.latitude)) {
            return
        }

        val speedQuality = isGps &&
            location.hasAccuracy() &&
            location.accuracy <= MAX_ACCURACY_M
        if (!speedQuality) {
            if (location.hasAltitude() &&
                (!location.hasAccuracy() || location.accuracy <= ALTITUDE_MAX_ACCURACY_M)
            ) {
                publishAltitudeOnly(location)
            }
            return
        }

        val previous = lastLocation
        lastLocation = location

        // SpeedSource clean, then one-pole EMA only (α high → still snappy)
        val cleaned = cleanSpeedKmh(location, previous)
        displaySpeedKmh = if (displaySpeedKmh <= 0.01f) {
            cleaned
        } else {
            DISPLAY_EMA * cleaned + (1f - DISPLAY_EMA) * displaySpeedKmh
        }
        val speedKmh = displaySpeedKmh

        val maxMps = RideHudDefaults.SPEED_KMH_HARD_MAX / 3.6f
        val stoppedMps = RideHudDefaults.BIKE_PAUSE_MPS
        val displayMps = speedKmh / 3.6f

        if (countingDistance && previous != null) {
            val delta = previous.distanceTo(location)
            val dt = (location.time - previous.time) / 1000f
            val maxPlausibleDelta = maxMps * dt.coerceAtLeast(0.5f)
            val stoppedDrift = displayMps < stoppedMps &&
                delta < maxOf(3f, location.accuracy * 0.6f)
            if (!stoppedDrift &&
                delta in 0.5f..minOf(MAX_JUMP_M, maxPlausibleDelta) &&
                dt > 0f &&
                location.accuracy <= MAX_ACCURACY_M
            ) {
                totalDistanceM += delta
            }
        }

        _sample.value = GpsSample(
            speedKmh = speedKmh,
            distanceM = totalDistanceM,
            hasFix = location.accuracy < 40f || abs(speedKmh) > 0.5f,
            location = location,
        )
    }

    /**
     * Port of SpeedSource.cleanSpeedData + generateData assign.
     *
     * REA: compare raw vs Δd/Δt temp; if raw/temp > 5 → raw/10 sticky;
     * legal range else → 0. Then assign as display speed.
     */
    private fun cleanSpeedKmh(location: Location, previous: Location?): Float {
        val d = RideHudDefaults
        // Prefer Doppler (chip speed); else Δd/Δt like SpeedSource rate path
        var rawKmh = when {
            location.hasSpeed() && location.speed >= 0f -> location.speed * 3.6f
            previous != null -> {
                val dt = (location.time - previous.time) / 1000.0
                if (dt <= 1e-9) 0f
                else (previous.distanceTo(location) / dt * 3.6).toFloat()
            }
            else -> 0f
        }

        val tempKmh = if (previous != null) {
            val dt = (location.time - previous.time) / 1000.0
            if (dt <= 1e-9) 0f
            else (previous.distanceTo(location) / dt * 3.6).toFloat()
        } else {
            0f
        }

        if (speedNeedDivideTen) {
            rawKmh /= 10f
        } else if (rawKmh >= 5f && tempKmh > 0.3f && rawKmh / tempKmh > d.SPEED_TENFOLD_RATIO) {
            rawKmh /= 10f
            speedNeedDivideTen = true
        }

        // REA legal [0, 4000] raw → [0, 40] km/h else 0; allow HARD_MAX for steep descents
        if (rawKmh < 0f || rawKmh > d.SPEED_KMH_HARD_MAX) return 0f
        return rawKmh
    }

    private fun publishAltitudeOnly(location: Location) {
        val current = _sample.value
        _sample.value = current.copy(
            hasFix = current.hasFix ||
                (location.hasAccuracy() && location.accuracy < ALTITUDE_MAX_ACCURACY_M),
            location = location,
        )
    }

    companion object {
        /** Deliver as fast as the provider will. */
        private const val MIN_TIME_MS = 0L
        private const val MIN_DISTANCE_M = 0f
        private const val MAX_ACCURACY_M = 25f
        private const val ALTITUDE_MAX_ACCURACY_M = 50f
        private const val MAX_JUMP_M = 60f
        /**
         * Light display blend only — was 0.22 (+ median) which stair-stepped;
         * 0.65 keeps SpeedSource snappiness while damping Doppler flicker.
         */
        private const val DISPLAY_EMA = 0.65f
    }
}
