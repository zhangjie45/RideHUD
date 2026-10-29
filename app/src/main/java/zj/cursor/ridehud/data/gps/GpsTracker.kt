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
 *
 * Speed strategy (aligned with common cycling trackers / OpenTracks guidance):
 * - Prefer Doppler [Location.getSpeed] over distance/time (position noise → jumpiness)
 * - GPS provider only (network locations make speed flicker)
 * - Reject low-accuracy fixes, clamp unrealistic acceleration, median + EMA smooth
 *
 * Refs: OpenTracks discussions on GPS vs distance speed; cycling_analyzer / cycling-assistant
 * (median buffer, accel clamp, implausible-speed reject).
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

    /** Rolling window for median filter (odd size). */
    private val speedWindow = ArrayDeque<Float>(MEDIAN_WINDOW)

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
        _sample.value = GpsSample()

        try {
            // GPS only — network provider speed/positions cause HUD flicker on many phones
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
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
        if (location.provider != null &&
            location.provider != LocationManager.GPS_PROVIDER
        ) {
            return
        }
        // Poor fix → keep last smoothed speed, don't feed noise into the filter
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_M) {
            return
        }

        val previous = lastLocation
        lastLocation = location

        val rawSpeedMps = resolveRawSpeedMps(location, previous) ?: return
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val dtSpeed = if (lastSpeedUpdateElapsedMs == 0L) {
            1f
        } else {
            ((nowElapsed - lastSpeedUpdateElapsedMs) / 1000f).coerceIn(0.3f, 3f)
        }
        lastSpeedUpdateElapsedMs = nowElapsed

        // Clamp acceleration so one bad Doppler spike can't swing the HUD
        val maxDelta = MAX_ACCEL_MPS2 * dtSpeed
        val accelClamped = if (smoothedSpeedMps == 0f && speedWindow.isEmpty()) {
            rawSpeedMps
        } else {
            val baseline = if (speedWindow.isEmpty()) smoothedSpeedMps else median(speedWindow)
            rawSpeedMps.coerceIn(baseline - maxDelta, baseline + maxDelta)
        }.coerceIn(0f, MAX_PLAUSIBLE_MPS)

        pushSpeed(accelClamped)
        val medianSpeed = median(speedWindow)

        smoothedSpeedMps = if (smoothedSpeedMps <= 0.01f) {
            medianSpeed
        } else {
            EMA_ALPHA * medianSpeed + (1f - EMA_ALPHA) * smoothedSpeedMps
        }

        val displayMps = if (smoothedSpeedMps < STOPPED_MPS) 0f else smoothedSpeedMps
        val speedKmh = displayMps * 3.6f

        // Distance: reject GPS wander while stopped (common OpenTracks/Strava pain point)
        if (previous != null) {
            val delta = previous.distanceTo(location)
            val dt = (location.time - previous.time) / 1000f
            val maxPlausibleDelta = MAX_PLAUSIBLE_MPS * dt.coerceAtLeast(0.5f)
            val stoppedDrift = displayMps < STOPPED_MPS &&
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
     * Prefer chip Doppler speed ([Location.getSpeed]); only fall back to Δs/Δt
     * when the provider omitted speed (rare on modern GPS).
     */
    private fun resolveRawSpeedMps(location: Location, previous: Location?): Float? {
        if (location.hasSpeed() && location.speed >= 0f) {
            val doppler = location.speed
            if (doppler > MAX_PLAUSIBLE_MPS) return null
            return doppler
        }
        if (previous == null) return 0f
        val dt = (location.time - previous.time) / 1000f
        if (dt < 0.5f) return null
        val derived = previous.distanceTo(location) / dt
        if (derived > MAX_PLAUSIBLE_MPS) return null
        return derived
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
        private const val MAX_JUMP_M = 60f
        /** ~0.35 g — aggressive for a bike, enough to kill GPS spikes. */
        private const val MAX_ACCEL_MPS2 = 2.5f
        /** ~72 km/h; higher treated as GPS glitch for this HUD. */
        private const val MAX_PLAUSIBLE_MPS = 20f
        private const val STOPPED_MPS = 0.55f // ~2 km/h
        private const val MEDIAN_WINDOW = 5
        /** Heavier smoothing than before (was ~0.35 new). */
        private const val EMA_ALPHA = 0.22f
    }
}
