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
    val hasBarometer: Boolean = false,
)

/**
 * Hybrid elevation: barometer for relative height (preferred) + GPS for absolute calibration.
 * Grade is climb/run over a short distance window.
 */
class ElevationTracker(context: Context) {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val pressureSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private var running = false
    private var hasBarometer = pressureSensor != null

    private var rawBaroAltitudeM: Float? = null
    private var altitudeOffsetM = 0f
    private var calibrated = false
    private var smoothedAltitudeM: Float? = null
    private var smoothedGradePercent = 0f
    private var hasGrade = false

    private var lastDistanceM = 0f
    private val path = ArrayDeque<PathPoint>()

    private var latest = ElevationSample(hasBarometer = hasBarometer)
    var onSample: ((ElevationSample) -> Unit)? = null

    private val pressureListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running || event.sensor.type != Sensor.TYPE_PRESSURE) return
            val pressureHpa = event.values[0]
            if (pressureHpa <= 0f) return
            rawBaroAltitudeM = pressureToAltitudeM(pressureHpa)
            publish()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        if (running) return
        running = true
        hasBarometer = pressureSensor != null
        rawBaroAltitudeM = null
        altitudeOffsetM = 0f
        calibrated = false
        smoothedAltitudeM = null
        smoothedGradePercent = 0f
        hasGrade = false
        lastDistanceM = 0f
        path.clear()
        latest = ElevationSample(hasBarometer = hasBarometer)
        onSample?.invoke(latest)

        pressureSensor?.let {
            sensorManager.registerListener(
                pressureListener,
                it,
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
                        0.9f * altitudeOffsetM + 0.1f * targetOffset
                    }
                } else if (!hasBarometer) {
                    updateSmoothedAltitude(gpsAltitude)
                }
            } else if (!hasBarometer && location.accuracy <= 40f) {
                updateSmoothedAltitude(gpsAltitude)
            }
        }

        updateGradePath(distanceM)
        publish()
    }

    private fun currentAltitudeM(): Float? {
        val baro = rawBaroAltitudeM
        return when {
            baro != null -> {
                val alt = baro + altitudeOffsetM
                updateSmoothedAltitude(alt)
                smoothedAltitudeM
            }
            else -> smoothedAltitudeM
        }
    }

    private fun updateSmoothedAltitude(raw: Float) {
        smoothedAltitudeM = smoothedAltitudeM?.let { 0.75f * it + 0.25f * raw } ?: raw
    }

    private fun updateGradePath(distanceM: Float) {
        val altitude = currentAltitudeM() ?: return
        if (distanceM + 0.5f < lastDistanceM) {
            path.clear()
            lastDistanceM = distanceM
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

        while (path.size > 1 && distanceM - path.first().distanceM > GRADE_WINDOW_M * 2) {
            path.removeFirst()
        }

        val oldest = path.firstOrNull() ?: return
        val window = distanceM - oldest.distanceM
        if (window < GRADE_WINDOW_M) return

        val rise = altitude - oldest.altitudeM
        val rawGrade = (rise / window) * 100f
        if (abs(rawGrade) > MAX_GRADE_PERCENT) return

        smoothedGradePercent = if (!hasGrade) {
            hasGrade = true
            rawGrade
        } else {
            0.7f * smoothedGradePercent + 0.3f * rawGrade
        }
    }

    private fun publish() {
        val altitude = currentAltitudeM()
        latest = ElevationSample(
            altitudeM = altitude,
            gradePercent = if (hasGrade) smoothedGradePercent else null,
            hasBarometer = hasBarometer,
        )
        onSample?.invoke(latest)
    }

    companion object {
        private const val GRADE_WINDOW_M = 25f
        private const val MIN_POINT_SPACING_M = 3f
        private const val MAX_GRADE_PERCENT = 35f
        private const val SEA_LEVEL_PRESSURE_HPA = 1013.25f

        fun pressureToAltitudeM(pressureHpa: Float): Float {
            return (44330f * (1f - (pressureHpa / SEA_LEVEL_PRESSURE_HPA).toDouble().pow(1.0 / 5.255)))
                .toFloat()
        }

        fun gradePercent(riseM: Float, runM: Float): Float? {
            if (runM < 1f) return null
            return (riseM / runM) * 100f
        }

        private fun verticalAccuracyOk(location: Location): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            return !location.hasVerticalAccuracy() || location.verticalAccuracyMeters <= 20f
        }
    }

    private data class PathPoint(val distanceM: Float, val altitudeM: Float)
}
