package zj.cursor.ridehud.domain

/**
 * RideHUD cycling calorie estimator.
 *
 * - Moving: speed-scaling quadratic (poly = A·v² + B·v + C, v ≤ 6 m/s)
 * - Idle: rest rate `(weight * 0.02171) / 60` kcal/s
 * - Active: max(total − rest, 0)
 * - Helper: ACSM bike MET=8 for manual-duration style estimates
 *
 * Evidence: docs/rea-metric-evidence.md
 */
object CalorieEstimator {
    const val FORMULA_VERSION = "ridehud-v3-speed-scaling-bike"

    /**
     * Instantaneous **total** metabolic kilocalories per second.
     */
    fun kcalPerSecond(
        weightKg: Float,
        speedKmh: Float,
        gradePercent: Float? = null,
        heartRateBpm: Int? = null,
    ): Double {
        if (weightKg <= 0f) return 0.0
        val movingSpeed = speedKmh.coerceAtLeast(0f)
        if (movingSpeed < RideMetrics.MOVING_SPEED_KMH) {
            return restKcalPerSecond(weightKg)
        }
        @Suppress("UNUSED_PARAMETER")
        val ignoredGrade = gradePercent
        @Suppress("UNUSED_PARAMETER")
        val ignoredHr = heartRateBpm
        return speedScalingKcalPerSecond(weightKg, movingSpeed)
    }

    /** Instantaneous **active** kcal/s = max(total − rest, 0). */
    fun activeKcalPerSecond(
        weightKg: Float,
        speedKmh: Float,
        gradePercent: Float? = null,
        heartRateBpm: Int? = null,
    ): Double {
        val total = kcalPerSecond(weightKg, speedKmh, gradePercent, heartRateBpm)
        val rest = restKcalPerSecond(weightKg)
        return (total - rest).coerceAtLeast(0.0)
    }

    fun restKcalPerSecond(weightKg: Float): Double {
        if (weightKg <= 0f) return 0.0
        return weightKg * RideHudDefaults.SIT_REST_KCAL_PER_KG_PER_MIN / 60.0
    }

    /**
     * ```
     * poly = a*v² + b*v + c
     * kcal/s = poly * 3.5 * weight / 12000
     * ```
     */
    fun speedScalingKcalPerSecond(weightKg: Float, speedKmh: Float): Double {
        val v = (speedKmh / 3.6f).coerceIn(0f, RideHudDefaults.SPEED_SCALING_CAP_MPS)
        val a = RideHudDefaults.CYCLING_SPEED_SCALING_A
        val b = RideHudDefaults.CYCLING_SPEED_SCALING_B
        val c = RideHudDefaults.CYCLING_SPEED_SCALING_C
        val poly = a * v * v + b * v + c
        return poly * 3.5 * weightKg / 12_000.0
    }

    fun acsmBikeKcal(weightKg: Float, durationMs: Long, met: Float = RideHudDefaults.BIKE_METS): Float {
        if (weightKg <= 0f || durationMs <= 0L) return 0f
        val minutes = durationMs / 60_000.0
        return ((met * 3.5 * minutes * weightKg) / 200.0).toFloat()
    }

    fun cyclingSpeedScalingMet(speedKmh: Float): Float {
        val v = (speedKmh / 3.6f).coerceIn(0f, RideHudDefaults.SPEED_SCALING_CAP_MPS)
        val a = RideHudDefaults.CYCLING_SPEED_SCALING_A
        val b = RideHudDefaults.CYCLING_SPEED_SCALING_B
        val c = RideHudDefaults.CYCLING_SPEED_SCALING_C
        return a * v * v + b * v + c
    }
}
