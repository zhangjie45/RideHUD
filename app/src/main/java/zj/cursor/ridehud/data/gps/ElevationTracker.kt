package zj.cursor.ridehud.data.gps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import zj.cursor.ridehud.domain.HuaweiHealthRideDefaults
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.pow

data class ElevationSample(
    /** Meters above sea level; null until first usable reading. */
    val altitudeM: Float? = null,
    /** Grade percent, e.g. 5.2 means +5.2%. null until enough distance. */
    val gradePercent: Float? = null,
    /** Cumulative ascent (Huawei creeping-wave / OpenTracks hysteresis). */
    val elevationGainM: Float = 0f,
    /** Cumulative descent. */
    val elevationLossM: Float = 0f,
    val hasBarometer: Boolean = false,
)

/**
 * Hybrid elevation + grade for cycling HUD.
 *
 * Elevation: barometer relative height (preferred) + slow GPS absolute calibration.
 * Grade: OLS slope of altitude vs distance over a rolling distance window
 * (same idea as Barberfish / Garmin slope datafields — distance-based, not time-based
 * endpoint deltas, which jitter badly with GPS/baro noise).
 */
class ElevationTracker(context: Context) {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private var running = false
    private var hasBarometer = pressureSensor != null

    private var smoothedPressureHpa: Float? = null
    private var rawBaroAltitudeM: Float? = null
    private var altitudeOffsetM = 0f
    private var calibrated = false
    private var smoothedAltitudeM: Float? = null
    private var smoothedGradePercent = 0f
    private var hasGrade = false

    /** Extreme altitude of current creeping-wave leg (Huawei TrackAltitudeMgr style). */
    private var waveExtremeM: Float? = null
    /** 0 unknown, +1 ascending, -1 descending. */
    private var waveDirection = 0
    private var elevationGainM = 0f
    private var elevationLossM = 0f
    /** Updated from [onLocation]; barometer samples honor the same gate. */
    private var movingForGainLoss = false
    private var usingGpsOnlyAltitude = false

    private var lastDistanceM = 0f
    private val path = ArrayDeque<PathPoint>()
    /** Huawei SlopeLinkage: buffer of N OLS samples, flush ~1 s as rounded avg %. */
    private val slopeBuffer = ArrayDeque<Float>(HuaweiHealthRideDefaults.SLOPE_BUFFER_N)
    private var lastSlopeFlushElapsedMs = 0L

    private var latest = ElevationSample(hasBarometer = hasBarometer)
    var onSample: ((ElevationSample) -> Unit)? = null

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running || event.sensor.type != Sensor.TYPE_PRESSURE) return
            val pressureHpa = event.values[0]
            if (pressureHpa <= 0f) return
            // Smooth pressure first — raw baro is noisy for grade; first sample paints immediately
            smoothedPressureHpa = smoothedPressureHpa?.let {
                PRESSURE_EMA * it + (1f - PRESSURE_EMA) * pressureHpa
            } ?: pressureHpa
            rawBaroAltitudeM = pressureToAltitudeM(smoothedPressureHpa!!)
            usingGpsOnlyAltitude = false
            // Same as pre-opt: show baro (+ GPS offset once calibrated) without waiting for GPS
            ingestAltitude(rawBaroAltitudeM!! + altitudeOffsetM, gpsOnly = false)
            publishAltitudeOnly()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (running) return
        running = true
        hasBarometer = pressureSensor != null
        smoothedPressureHpa = null
        rawBaroAltitudeM = null
        altitudeOffsetM = 0f
        calibrated = false
        smoothedAltitudeM = null
        smoothedGradePercent = 0f
        hasGrade = false
        waveExtremeM = null
        waveDirection = 0
        elevationGainM = 0f
        elevationLossM = 0f
        movingForGainLoss = false
        usingGpsOnlyAltitude = !hasBarometer
        lastDistanceM = 0f
        path.clear()
        slopeBuffer.clear()
        lastSlopeFlushElapsedMs = 0L
        latest = ElevationSample(hasBarometer = hasBarometer)
        onSample?.invoke(latest)

        pressureSensor?.let {
            sensorManager.registerListener(
                pressureListener,
                it,
                // UI rate for fast first paint; pressure EMA still kills grade jitter
                SensorManager.SENSOR_DELAY_UI,
            )
        }
    }

    fun stop() {
        running = false
        if (pressureSensor != null) {
            sensorManager.unregisterListener(pressureListener)
        }
    }

    /** When ride is paused, freeze gain/loss and follow altitude as the new extreme. */
    fun setMovingForGainLoss(moving: Boolean) {
        movingForGainLoss = moving
        if (!moving) {
            waveExtremeM = smoothedAltitudeM
            waveDirection = 0
        }
    }

    /**
     * Feed GPS/location updates for absolute calibration and grade path.
     *
     * @param speedKmh display speed; when nearly stopped, gain/loss freeze and the
     *   anchor follows altitude so parking-lot baro noise isn't counted as climb.
     */
    fun onLocation(location: Location, distanceM: Float, speedKmh: Float = 0f) {
        if (!running) return
        movingForGainLoss = speedKmh >= HuaweiHealthRideDefaults.bikePauseEnterKmh

        val gpsAltitude = if (location.hasAltitude()) location.altitude.toFloat() else null
        if (gpsAltitude != null) {
            val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
            val gpsOk = accuracy in 0f..25f && verticalAccuracyOk(location)
            if (gpsOk) {
                val baro = rawBaroAltitudeM
                if (baro != null) {
                    val targetOffset = gpsAltitude - baro
                    altitudeOffsetM = if (!calibrated) {
                        calibrated = true
                        targetOffset
                    } else {
                        // Very slow GPS absolute drift correction (baro owns relative grade)
                        0.97f * altitudeOffsetM + 0.03f * targetOffset
                    }
                    usingGpsOnlyAltitude = false
                    ingestAltitude(baro + altitudeOffsetM, gpsOnly = false)
                } else {
                    usingGpsOnlyAltitude = true
                    ingestAltitude(gpsAltitude, gpsOnly = true)
                }
            } else if (!hasBarometer && accuracy <= ALTITUDE_DISPLAY_ACCURACY_M) {
                usingGpsOnlyAltitude = true
                ingestAltitude(gpsAltitude, gpsOnly = true)
            } else if (hasBarometer && rawBaroAltitudeM != null) {
                usingGpsOnlyAltitude = false
                ingestAltitude(rawBaroAltitudeM!! + altitudeOffsetM, gpsOnly = false)
            }
        }

        // Grade stays distance-based; network/coarse fixes won't grow distance so path freezes
        updateGradePath(distanceM)
        publish()
    }

    private fun currentAltitudeM(): Float? = smoothedAltitudeM

    private fun ingestAltitude(raw: Float, gpsOnly: Boolean) {
        val alpha = if (gpsOnly) GPS_ALT_EMA else BARO_ALT_EMA
        smoothedAltitudeM = smoothedAltitudeM?.let { alpha * it + (1f - alpha) * raw } ?: raw
        updateElevationGainLoss(smoothedAltitudeM!!)
    }

    /**
     * Huawei [TrackAltitudeMgr] creeping-wave (threshold 0.6 m on baro path).
     * GPS-only uses a larger threshold (3 m) — phone GNSS vertical noise is worse than baro.
     * While stopped, slide the extreme with altitude (no accumulate).
     */
    private fun updateElevationGainLoss(smoothedAlt: Float) {
        val thr = if (usingGpsOnlyAltitude || !hasBarometer) {
            HuaweiHealthRideDefaults.ALTITUDE_GPS_HYSTERESIS_M
        } else {
            HuaweiHealthRideDefaults.ALTITUDE_NOISE_M
        }
        val extreme = waveExtremeM
        if (extreme == null) {
            waveExtremeM = smoothedAlt
            waveDirection = 0
            return
        }
        if (!movingForGainLoss) {
            waveExtremeM = smoothedAlt
            waveDirection = 0
            return
        }
        val result = creepingWaveStep(
            altitudeM = smoothedAlt,
            extremeM = extreme,
            direction = waveDirection,
            thresholdM = thr,
        )
        elevationGainM += result.gainDeltaM
        elevationLossM += result.lossDeltaM
        waveExtremeM = result.extremeM
        waveDirection = result.direction
    }

    private fun updateGradePath(distanceM: Float) {
        val altitude = currentAltitudeM() ?: return
        if (distanceM + 0.5f < lastDistanceM) {
            path.clear()
            lastDistanceM = distanceM
            hasGrade = false
            return
        }
        // Nearly stopped: freeze grade path growth so OLS doesn't go wild on altitude noise
        if (path.isNotEmpty() && distanceM - lastDistanceM < 0.3f) {
            return
        }
        lastDistanceM = distanceM

        val last = path.lastOrNull()
        if (last != null && distanceM - last.distanceM < MIN_POINT_SPACING_M) {
            path.removeLast()
            path.addLast(PathPoint(distanceM, altitude))
        } else {
            path.addLast(PathPoint(distanceM, altitude))
        }

        while (path.size > 1 && distanceM - path.first().distanceM > GRADE_WINDOW_M) {
            path.removeFirst()
        }

        val window = distanceM - (path.firstOrNull()?.distanceM ?: distanceM)
        if (window < MIN_GRADE_WINDOW_M || path.size < MIN_GRADE_POINTS) return

        val ols = olsGradePercent(path) ?: return
        // Huawei SlopeLinkage: discard outside ±101 exclusive
        val clamped = HuaweiHealthRideDefaults.clampSlopePercent(ols) ?: return
        slopeBuffer.addLast(clamped)
        while (slopeBuffer.size > HuaweiHealthRideDefaults.SLOPE_BUFFER_N) {
            slopeBuffer.removeFirst()
        }
        val now = SystemClock.elapsedRealtime()
        val due = lastSlopeFlushElapsedMs == 0L ||
            now - lastSlopeFlushElapsedMs >= HuaweiHealthRideDefaults.SLOPE_REFRESH_MS ||
            slopeBuffer.size >= HuaweiHealthRideDefaults.SLOPE_BUFFER_N
        if (!due || slopeBuffer.isEmpty()) return
        lastSlopeFlushElapsedMs = now
        // SlopeLinkageDataManager.d: round(sum/count)
        val avg = slopeBuffer.sum() / slopeBuffer.size
        val rounded = avg.roundToInt().toFloat()
        val published = HuaweiHealthRideDefaults.clampSlopePercent(rounded) ?: return
        smoothedGradePercent = published
        hasGrade = true
        slopeBuffer.clear()
    }

    private fun publishAltitudeOnly() {
        val altitude = currentAltitudeM()
        latest = latest.copy(
            altitudeM = altitude,
            elevationGainM = elevationGainM,
            elevationLossM = elevationLossM,
            hasBarometer = hasBarometer,
        )
        onSample?.invoke(latest)
    }

    private fun publish() {
        val altitude = currentAltitudeM()
        latest = ElevationSample(
            altitudeM = altitude,
            gradePercent = if (hasGrade) smoothedGradePercent else null,
            elevationGainM = elevationGainM,
            elevationLossM = elevationLossM,
            hasBarometer = hasBarometer,
        )
        onSample?.invoke(latest)
    }

    companion object {
        /** Barberfish uses ~30 m OLS window; slightly longer for phone GPS noise. */
        private const val GRADE_WINDOW_M = 40f
        private const val MIN_GRADE_WINDOW_M = 20f
        private const val MIN_POINT_SPACING_M = 4f
        private const val MIN_GRADE_POINTS = 4
        /** Match pre-opt fallback so altitude paints before a tight GPS lock. */
        private const val ALTITUDE_DISPLAY_ACCURACY_M = 50f
        private const val SEA_LEVEL_PRESSURE_HPA = 1013.25f

        private const val PRESSURE_EMA = 0.88f
        private const val BARO_ALT_EMA = 0.82f
        private const val GPS_ALT_EMA = 0.92f

        /** @see HuaweiHealthRideDefaults.ALTITUDE_NOISE_M */
        val BARO_CREEPING_WAVE_M: Float get() = HuaweiHealthRideDefaults.ALTITUDE_NOISE_M

        fun pressureToAltitudeM(pressureHpa: Float): Float {
            return (44330f * (1f - (pressureHpa / SEA_LEVEL_PRESSURE_HPA).toDouble().pow(1.0 / 5.255)))
                .toFloat()
        }

        fun gradePercent(riseM: Float, runM: Float): Float? {
            if (runM < 1f) return null
            return (riseM / runM) * 100f
        }

        data class CreepingWaveStep(
            val extremeM: Float,
            val direction: Int,
            val gainDeltaM: Float,
            val lossDeltaM: Float,
        )

        /**
         * One online step of Huawei-style creeping-wave filter + ascent/descent accumulate.
         * Sub-threshold reversals are ignored; confirmed legs accumulate every sample.
         */
        fun creepingWaveStep(
            altitudeM: Float,
            extremeM: Float,
            direction: Int,
            thresholdM: Float,
        ): CreepingWaveStep {
            var extreme = extremeM
            var dir = direction
            var gain = 0f
            var loss = 0f
            when (dir) {
                0 -> {
                    when {
                        altitudeM >= extreme + thresholdM -> {
                            gain = altitudeM - extreme
                            extreme = altitudeM
                            dir = 1
                        }
                        altitudeM <= extreme - thresholdM -> {
                            loss = extreme - altitudeM
                            extreme = altitudeM
                            dir = -1
                        }
                    }
                }
                1 -> {
                    when {
                        altitudeM >= extreme -> {
                            gain = altitudeM - extreme
                            extreme = altitudeM
                        }
                        altitudeM <= extreme - thresholdM -> {
                            loss = extreme - altitudeM
                            extreme = altitudeM
                            dir = -1
                        }
                    }
                }
                else -> {
                    when {
                        altitudeM <= extreme -> {
                            loss = extreme - altitudeM
                            extreme = altitudeM
                        }
                        altitudeM >= extreme + thresholdM -> {
                            gain = altitudeM - extreme
                            extreme = altitudeM
                            dir = 1
                        }
                    }
                }
            }
            return CreepingWaveStep(extreme, dir, gain, loss)
        }

        /**
         * Batch form of [creepingWaveStep] — mirrors `TrackAltitudeMgr.d(heights, threshold)`.
         */
        fun creepingWaveGainLoss(
            heights: List<Float>,
            thresholdM: Float = BARO_CREEPING_WAVE_M,
        ): Pair<Float, Float> {
            if (heights.isEmpty()) return 0f to 0f
            var extreme = heights.first()
            var dir = 0
            var gain = 0f
            var loss = 0f
            for (i in 1 until heights.size) {
                val step = creepingWaveStep(heights[i], extreme, dir, thresholdM)
                extreme = step.extremeM
                dir = step.direction
                gain += step.gainDeltaM
                loss += step.lossDeltaM
            }
            return gain to loss
        }

        /**
         * Ordinary least-squares slope of altitude (y) vs distance (x), as percent grade.
         * More stable than endpoint rise/run over the same window.
         */
        fun olsGradePercent(points: List<PathPoint>): Float? {
            if (points.size < 3) return null
            val n = points.size.toDouble()
            var sumX = 0.0
            var sumY = 0.0
            var sumXX = 0.0
            var sumXY = 0.0
            for (p in points) {
                val x = p.distanceM.toDouble()
                val y = p.altitudeM.toDouble()
                sumX += x
                sumY += y
                sumXX += x * x
                sumXY += x * y
            }
            val denom = n * sumXX - sumX * sumX
            if (abs(denom) < 1e-6) return null
            val slope = (n * sumXY - sumX * sumY) / denom
            val grade = (slope * 100.0).toFloat()
            if (!grade.isFinite()) return null
            return grade
        }

        private fun verticalAccuracyOk(location: Location): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            return !location.hasVerticalAccuracy() || location.verticalAccuracyMeters <= 20f
        }
    }

    data class PathPoint(val distanceM: Float, val altitudeM: Float)
}
