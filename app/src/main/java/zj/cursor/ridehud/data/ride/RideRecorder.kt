package zj.cursor.ridehud.data.ride

import zj.cursor.ridehud.domain.CalorieEstimator
import zj.cursor.ridehud.domain.HuaweiHealthRideDefaults
import zj.cursor.ridehud.domain.RideMetrics
import zj.cursor.ridehud.domain.RideRecord
import zj.cursor.ridehud.domain.RideTrackPoint
import java.util.UUID
import kotlin.math.max

/**
 * Samples live metrics during a ride and builds a [RideRecord] on finish.
 *
 * Avg speed = Huawei PaceMapProducer (distance / 运动时间).
 * Max speed = Huawei RealTimePaceUtil (jdi): sample every 5 s.
 * Calories = TruSport EE total + active split (§11).
 */
class RideRecorder {
    private var startEpochMs = 0L
    private var weightKg = 70f
    private var calories = 0.0
    private var activeCalories = 0.0
    private var maxSpeed = 0f
    private var lastMaxSampleElapsedMs = -1L
    private var maxHr: Int? = null
    private var hrSum = 0.0
    private var hrCount = 0
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
        activeCalories = 0.0
        maxSpeed = 0f
        lastMaxSampleElapsedMs = -1L
        maxHr = null
        hrSum = 0.0
        hrCount = 0
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
        val activeRate = CalorieEstimator.activeKcalPerSecond(
            weightKg = weightKg,
            speedKmh = metrics.speedKmh,
            gradePercent = metrics.gradePercent,
            heartRateBpm = metrics.heartRateBpm,
        )
        calories += kcalRate * dtSec
        activeCalories += activeRate * dtSec

        // Huawei jdi RealTimePaceUtil: one sample every 5 s for max
        val elapsed = metrics.elapsedMs
        if (lastMaxSampleElapsedMs < 0L ||
            elapsed - lastMaxSampleElapsedMs >= HuaweiHealthRideDefaults.MAX_SPEED_SAMPLE_INTERVAL_MS
        ) {
            lastMaxSampleElapsedMs = elapsed
            val sample = metrics.speedKmh
            if (sample in 0.5f..HuaweiHealthRideDefaults.SPEED_KMH_HARD_MAX) {
                maxSpeed = max(maxSpeed, sample)
            }
        }

        metrics.heartRateBpm?.let { hr ->
            if (hr in 35..220) {
                hrSum += hr
                hrCount++
                maxHr = maxOf(maxHr ?: hr, hr)
            }
        }

        elevationGain = max(elevationGain, metrics.elevationGainM)
        elevationLoss = max(elevationLoss, metrics.elevationLossM)

        metrics.altitudeM?.let { alt ->
            minAltitude = minOf(minAltitude ?: alt, alt)
            maxAltitude = maxOf(maxAltitude ?: alt, alt)
        }

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

    fun currentActiveCaloriesKcal(): Float = activeCalories.toFloat()

    fun currentMaxSpeedKmh(): Float = maxSpeed

    fun finish(metrics: RideMetrics, endEpochMs: Long = System.currentTimeMillis()): RideRecord? {
        if (!active) return null
        active = false
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

        elevationGain = max(elevationGain, metrics.elevationGainM)
        elevationLoss = max(elevationLoss, metrics.elevationLossM)

        val elapsedMs = metrics.elapsedMs.coerceAtLeast(0L)
        val totalElapsedMs = metrics.totalElapsedMs.coerceAtLeast(elapsedMs)
        val distanceM = metrics.distanceM.coerceAtLeast(0f)
        val avgSpeed = RideMetrics.averageSpeedKmh(distanceM, elapsedMs)

        return RideRecord(
            id = UUID.randomUUID().toString(),
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            elapsedMs = elapsedMs,
            totalElapsedMs = totalElapsedMs,
            distanceM = distanceM,
            caloriesKcal = calories.toFloat(),
            activeCaloriesKcal = activeCalories.toFloat(),
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
