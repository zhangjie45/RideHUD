package zj.cursor.ridehud.data.ride

import zj.cursor.ridehud.domain.CalorieEstimator
import zj.cursor.ridehud.domain.RideMetrics
import zj.cursor.ridehud.domain.RideRecord
import zj.cursor.ridehud.domain.RideTrackPoint
import java.util.UUID
import kotlin.math.max

/**
 * Samples live metrics during a ride and builds a [RideRecord] on finish.
 *
 * Max-speed / elevation / avg use the same filtering ideas as OpenTracks & cycling_analyzer:
 * reject implausible peaks, prefer moving-time average, use hysteresis gain from ElevationTracker.
 */
class RideRecorder {
    private var startEpochMs = 0L
    private var weightKg = 70f
    private var calories = 0.0
    private var maxSpeed = 0f
    private val recentSpeeds = ArrayDeque<Float>(3)
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
        maxSpeed = 0f
        recentSpeeds.clear()
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
        calories += kcalRate * dtSec

        // cycling_analyzer: max of short moving average, reject absurd peaks
        recentSpeeds.addLast(metrics.speedKmh)
        while (recentSpeeds.size > 3) recentSpeeds.removeFirst()
        val windowAvg = recentSpeeds.average().toFloat()
        if (windowAvg in 0.5f..MAX_PLAUSIBLE_SPEED_KMH) {
            maxSpeed = max(maxSpeed, windowAvg)
        }

        metrics.heartRateBpm?.let { hr ->
            if (hr in 35..220) {
                hrSum += hr
                hrCount++
                maxHr = maxOf(maxHr ?: hr, hr)
            }
        }

        // Prefer OpenTracks-style gain from ElevationTracker when present
        elevationGain = max(elevationGain, metrics.elevationGainM)
        elevationLoss = max(elevationLoss, metrics.elevationLossM)

        metrics.altitudeM?.let { alt ->
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
        val movingMs = metrics.movingElapsedMs.coerceAtLeast(0L)
        val distanceM = metrics.distanceM.coerceAtLeast(0f)
        val avgBasisMs = if (movingMs >= 1_000L) movingMs else elapsedMs
        val avgSpeed = RideMetrics.averageSpeedKmh(distanceM, avgBasisMs)

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
        private const val MAX_PLAUSIBLE_SPEED_KMH = 72f
    }
}
