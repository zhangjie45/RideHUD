package zj.cursor.ridehud.data.ride

import zj.cursor.ridehud.domain.CalorieEstimator
import zj.cursor.ridehud.domain.RideMetrics
import zj.cursor.ridehud.domain.RideRecord
import zj.cursor.ridehud.domain.RideTrackPoint
import java.util.UUID
import kotlin.math.max

/**
 * Samples live metrics during a ride and builds a [RideRecord] on finish.
 */
class RideRecorder {
    private var startEpochMs = 0L
    private var weightKg = 70f
    private var calories = 0.0
    private var maxSpeed = 0f
    private var maxHr: Int? = null
    private var hrSum = 0.0
    private var hrCount = 0
    private var lastAltitude: Float? = null
    private var elevationGain = 0f
    private var elevationLoss = 0f
    private var minAltitude: Float? = null
    private var maxAltitude: Float? = null
    private val points = mutableListOf<RideTrackPoint>()
    private var lastSampleElapsedMs = -1L
    private var active = false

    fun start(weightKg: Float, startEpochMs: Long = System.currentTimeMillis()) {
        this.weightKg = weightKg
        this.startEpochMs = startEpochMs
        calories = 0.0
        maxSpeed = 0f
        maxHr = null
        hrSum = 0.0
        hrCount = 0
        lastAltitude = null
        elevationGain = 0f
        elevationLoss = 0f
        minAltitude = null
        maxAltitude = null
        points.clear()
        lastSampleElapsedMs = -1L
        active = true
    }

    /**
     * Call about once per second while not paused.
     */
    fun onTick(metrics: RideMetrics, epochMs: Long = System.currentTimeMillis(), dtSec: Double = 1.0) {
        if (!active || metrics.isPaused) return

        val kcalRate = CalorieEstimator.kcalPerSecond(
            weightKg = weightKg,
            speedKmh = metrics.speedKmh,
            gradePercent = metrics.gradePercent,
            heartRateBpm = metrics.heartRateBpm,
        )
        calories += kcalRate * dtSec

        maxSpeed = max(maxSpeed, metrics.speedKmh)
        metrics.heartRateBpm?.let { hr ->
            hrSum += hr
            hrCount++
            maxHr = maxOf(maxHr ?: hr, hr)
        }

        metrics.altitudeM?.let { alt ->
            lastAltitude?.let { prev ->
                val delta = alt - prev
                if (delta > 0.3f) elevationGain += delta
                if (delta < -0.3f) elevationLoss += -delta
            }
            lastAltitude = alt
            minAltitude = minOf(minAltitude ?: alt, alt)
            maxAltitude = maxOf(maxAltitude ?: alt, alt)
        }

        val elapsed = metrics.elapsedMs
        if (lastSampleElapsedMs < 0L || elapsed - lastSampleElapsedMs >= SAMPLE_INTERVAL_MS) {
            lastSampleElapsedMs = elapsed
            points += RideTrackPoint(
                elapsedMs = elapsed,
                epochMs = epochMs,
                speedKmh = metrics.speedKmh,
                distanceM = metrics.distanceM,
                heartRateBpm = metrics.heartRateBpm,
                altitudeM = metrics.altitudeM,
                gradePercent = metrics.gradePercent,
                caloriesKcalCumulative = calories.toFloat(),
            )
        }
    }

    fun currentCaloriesKcal(): Float = calories.toFloat()

    fun finish(metrics: RideMetrics, endEpochMs: Long = System.currentTimeMillis()): RideRecord? {
        if (!active) return null
        active = false
        // Ensure final point captured
        if (points.isEmpty() || points.last().elapsedMs != metrics.elapsedMs) {
            points += RideTrackPoint(
                elapsedMs = metrics.elapsedMs,
                epochMs = endEpochMs,
                speedKmh = metrics.speedKmh,
                distanceM = metrics.distanceM,
                heartRateBpm = metrics.heartRateBpm,
                altitudeM = metrics.altitudeM,
                gradePercent = metrics.gradePercent,
                caloriesKcalCumulative = calories.toFloat(),
            )
        }

        val elapsedMs = metrics.elapsedMs.coerceAtLeast(0L)
        val distanceM = metrics.distanceM.coerceAtLeast(0f)
        val hours = elapsedMs / 3_600_000.0
        val avgSpeed = if (hours > 0) (distanceM / 1000.0 / hours).toFloat() else 0f

        return RideRecord(
            id = UUID.randomUUID().toString(),
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            elapsedMs = elapsedMs,
            distanceM = distanceM,
            caloriesKcal = calories.toFloat(),
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed,
            avgHeartRateBpm = if (hrCount > 0) (hrSum / hrCount).toFloat() else null,
            maxHeartRateBpm = maxHr,
            elevationGainM = elevationGain,
            elevationLossM = elevationLoss,
            minAltitudeM = minAltitude,
            maxAltitudeM = maxAltitude,
            weightKgUsed = weightKg,
            calorieFormulaVersion = CalorieEstimator.FORMULA_VERSION,
            trackPoints = points.toList(),
        )
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 1_000L
    }
}
