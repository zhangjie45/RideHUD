package zj.cursor.ridehud.data.gps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import kotlin.math.abs
import kotlin.math.pow

data class ElevationSample(
    /** Meters above sea level; null until first usable reading. */
    val altitudeM: Float? = null,
    /** Grade percent, e.g. 5.2 means +5.2%. null until enough distance. */
    val gradePercent: Float? = null,
    /** Cumulative ascent with OpenTracks-style hysteresis (meters). */
    val elevationGainM: Float = 0f,
    /** Cumulative descent with hysteresis (meters). */
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

    /** OpenTracks: only count gain/loss after ±3 m from last committed anchor. */
    private var gainAnchorAltitudeM: Float? = null
    private var elevationGainM = 0f
    private var elevationLossM = 0f

    private var lastDistanceM = 0f
    private val path = ArrayDeque<PathPoint>()

    private var latest = ElevationSample(hasBarometer = hasBarometer)
    var onSample: ((ElevationSample) -> Unit)? = null

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running || event.sensor.type != Sensor.TYPE_PRESSURE) return
            val pressureHpa = event.values[0]
            if (pressureHpa <= 0f) return
            // Smooth pressure first — raw baro at SENSOR_DELAY_UI is noisy for grade
            smoothedPressureHpa = smoothedPressureHpa?.let {
                PRESSURE_EMA * it + (1f - PRESSURE_EMA) * pressureHpa
            } ?: pressureHpa
            rawBaroAltitudeM = pressureToAltitudeM(smoothedPressureHpa!!)
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
        gainAnchorAltitudeM = null
        elevationGainM = 0f
        elevationLossM = 0f
        lastDistanceM = 0f
        path.clear()
        latest = ElevationSample(hasBarometer = hasBarometer)
        onSample?.invoke(latest)

        pressureSensor?.let {
            sensorManager.registerListener(
                pressureListener,
                it,
                // ~5 Hz is enough; UI rate over-samples and adds jitter
                SensorManager.SENSOR_DELAY_NORMAL,
            )
        }
    }

    fun stop() {
        running = false
        if (pressureSensor != null) {
            sensorManager.unregisterListener(pressureListener)
        }
    }

    /**
     * Feed GPS/location updates for absolute calibration and grade path.
     */
    fun onLocation(location: Location, distanceM: Float) {
        if (!running) return

        val gpsAltitude = if (location.hasAltitude()) location.altitude.toFloat() else null
        if (gpsAltitude != null) {
            val gpsOk = location.accuracy in 0f..25f && verticalAccuracyOk(location)
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
                } else if (!hasBarometer) {
                    ingestAltitude(gpsAltitude, gpsOnly = true)
                }
            } else if (!hasBarometer && location.accuracy <= 35f) {
                ingestAltitude(gpsAltitude, gpsOnly = true)
            } else if (hasBarometer && rawBaroAltitudeM != null) {
                ingestAltitude(rawBaroAltitudeM!! + altitudeOffsetM, gpsOnly = false)
            }
        }

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
     * OpenTracks / cycling_analyzer style: ignore sub-threshold wobble;
     * only commit climb/descent once altitude moves ≥ [GAIN_HYSTERESIS_M] from anchor.
     */
    private fun updateElevationGainLoss(smoothedAlt: Float) {
        val anchor = gainAnchorAltitudeM
        if (anchor == null) {
            gainAnchorAltitudeM = smoothedAlt
            return
        }
        val delta = smoothedAlt - anchor
        when {
            delta >= GAIN_HYSTERESIS_M -> {
                elevationGainM += delta
                gainAnchorAltitudeM = smoothedAlt
            }
            delta <= -GAIN_HYSTERESIS_M -> {
                elevationLossM += -delta
                gainAnchorAltitudeM = smoothedAlt
            }
        }
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
        if (abs(ols) > MAX_GRADE_PERCENT) return

        smoothedGradePercent = if (!hasGrade) {
            hasGrade = true
            ols
        } else {
            GRADE_EMA * smoothedGradePercent + (1f - GRADE_EMA) * ols
        }
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
        private const val MAX_GRADE_PERCENT = 30f
        /** OpenTracks default hysteresis for ascent/descent counting. */
        private const val GAIN_HYSTERESIS_M = 3f
        private const val SEA_LEVEL_PRESSURE_HPA = 1013.25f

        private const val PRESSURE_EMA = 0.88f
        private const val BARO_ALT_EMA = 0.82f
        private const val GPS_ALT_EMA = 0.92f
        private const val GRADE_EMA = 0.78f

        fun pressureToAltitudeM(pressureHpa: Float): Float {
            return (44330f * (1f - (pressureHpa / SEA_LEVEL_PRESSURE_HPA).toDouble().pow(1.0 / 5.255)))
                .toFloat()
        }

        fun gradePercent(riseM: Float, runM: Float): Float? {
            if (runM < 1f) return null
            return (riseM / runM) * 100f
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
