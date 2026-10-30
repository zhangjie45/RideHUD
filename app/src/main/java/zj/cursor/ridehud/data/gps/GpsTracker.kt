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
import zj.cursor.ridehud.domain.HuaweiHealthRideDefaults
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
 * Huawei Health alignment (docs/rea-huawei-health-evidence.md):
 * - SpeedSource-style Δd/Δt cross-check + 10× sticky correction
 * - Invalid sentinel (-80, 90); stop threshold = bike auto-pause enter (~2 km/h)
 * - Distance only while counting (pause freezes)
 */
class GpsTracker(context: Context) {
    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _sample = MutableStateFlow(GpsSample())
    val sample: StateFlow<GpsSample> = _sample.asStateFlow()

    private var lastLocation: Location? = null
    private var lastSpeedUpdateElapsedMs: Long = 0L
    private var totalDistanceM = 0f
    private var smoothedSpeedMps = 0f
    private var running = false
    /** When false (ride paused), keep last distance — don't add GPS drift. */
    private var countingDistance = true

    /** Rolling window for median filter (odd size). */
    private val speedWindow = ArrayDeque<Float>(MEDIAN_WINDOW)
    /** SpeedSource sticky: Doppler reported 10× too large. */
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
        lastSpeedUpdateElapsedMs = 0L
        smoothedSpeedMps = 0f
        speedWindow.clear()
        speedNeedDivideTen = false
        _sample.value = GpsSample()

        try {
            // GPS for speed/distance; network only helps altitude indoors (never drives speed).
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
            // Seed altitude ASAP from cache (common indoors before a fresh fix)
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
            // Drop previous fix so resume doesn't invent a teleport delta
            lastLocation = null
        }
    }

    private fun onNewLocation(location: Location) {
        if (!running) return
        val provider = location.provider
        val isGps = provider == null || provider == LocationManager.GPS_PROVIDER
        val isNetwork = provider == LocationManager.NETWORK_PROVIDER
        if (!isGps && !isNetwork) return

        if (HuaweiHealthRideDefaults.isInvalidGpsSentinel(location.longitude, location.latitude)) {
            return
        }

        // Network / coarse GPS: forward for altitude only — never touch speed/distance filters
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

        val cleanedKmh = cleanSpeedKmh(location, previous) ?: run {
            publishAltitudeOnly(location)
            return
        }
        val rawSpeedMps = cleanedKmh / 3.6f
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val dtSpeed = if (lastSpeedUpdateElapsedMs == 0L) {
            1f
        } else {
            ((nowElapsed - lastSpeedUpdateElapsedMs) / 1000f).coerceIn(0.3f, 3f)
        }
        lastSpeedUpdateElapsedMs = nowElapsed

        val maxMps = HuaweiHealthRideDefaults.SPEED_KMH_HARD_MAX / 3.6f
        val stoppedMps = HuaweiHealthRideDefaults.BIKE_PAUSE_MPS
        // Clamp acceleration so one bad Doppler spike can't swing the HUD
        val maxDelta = MAX_ACCEL_MPS2 * dtSpeed
        val accelClamped = if (smoothedSpeedMps == 0f && speedWindow.isEmpty()) {
            rawSpeedMps
        } else {
            val baseline = if (speedWindow.isEmpty()) smoothedSpeedMps else median(speedWindow)
            rawSpeedMps.coerceIn(baseline - maxDelta, baseline + maxDelta)
        }.coerceIn(0f, maxMps)

        pushSpeed(accelClamped)
        val medianSpeed = median(speedWindow)

        smoothedSpeedMps = if (smoothedSpeedMps <= 0.01f) {
            medianSpeed
        } else {
            EMA_ALPHA * medianSpeed + (1f - EMA_ALPHA) * smoothedSpeedMps
        }

        val displayMps = if (smoothedSpeedMps < stoppedMps) 0f else smoothedSpeedMps
        val speedKmh = displayMps * 3.6f

        // Distance: reject GPS wander while stopped
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
     * Huawei [SpeedSource.cleanSpeedData]: cross-check Doppler vs Δd/Δt;
     * if Doppler looks ~10× high, divide and sticky-flag; clamp to Health raw max.
     */
    private fun cleanSpeedKmh(location: Location, previous: Location?): Float? {
        val d = HuaweiHealthRideDefaults
        var kmh = when {
            location.hasSpeed() && location.speed >= 0f -> location.speed * 3.6f
            previous != null -> {
                val dt = (location.time - previous.time) / 1000f
                if (dt < 0.5f) return null
                previous.distanceTo(location) / dt * 3.6f
            }
            else -> 0f
        }
        if (speedNeedDivideTen) {
            kmh /= 10f
        } else if (previous != null && kmh >= 5f) {
            val dt = (location.time - previous.time) / 1000f
            if (dt >= 0.5f) {
                val viaDist = previous.distanceTo(location) / dt * 3.6f
                if (viaDist > 0.3f && kmh / viaDist > d.SPEED_TENFOLD_RATIO) {
                    kmh /= 10f
                    speedNeedDivideTen = true
                }
            }
        }
        // Health legal raw [0, 4000] → [0, 40] km/h; hard-cap higher spikes for descents
        if (kmh < 0f || kmh > d.SPEED_KMH_HARD_MAX) return 0f
        if (kmh > d.speedKmhMax) {
            // Soft Health clamp for display path still allows up to HARD_MAX after accel filter
            return kmh
        }
        return kmh
    }

    /** Keep last speed/distance; only refresh location for elevation. */
    private fun publishAltitudeOnly(location: Location) {
        val current = _sample.value
        _sample.value = current.copy(
            hasFix = current.hasFix ||
                (location.hasAccuracy() && location.accuracy < ALTITUDE_MAX_ACCURACY_M),
            location = location,
        )
    }

    private fun pushSpeed(mps: Float) {
        speedWindow.addLast(mps)
        while (speedWindow.size > MEDIAN_WINDOW) {
            speedWindow.removeFirst()
        }
    }

    private fun median(values: Collection<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) * 0.5f
        }
    }

    companion object {
        private const val MIN_TIME_MS = 1000L
        private const val MIN_DISTANCE_M = 0f
        private const val MAX_ACCURACY_M = 25f
        /** Looser than speed gate so HUD altitude can appear indoors / before GPS lock. */
        private const val ALTITUDE_MAX_ACCURACY_M = 50f
        private const val MAX_JUMP_M = 60f
        /** ~0.35 g — aggressive for a bike, enough to kill GPS spikes. */
        private const val MAX_ACCEL_MPS2 = 2.5f
        private const val MEDIAN_WINDOW = 5
        private const val EMA_ALPHA = 0.22f
    }
}
