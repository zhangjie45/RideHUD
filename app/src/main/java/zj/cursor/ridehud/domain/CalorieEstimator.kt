package zj.cursor.ridehud.domain

/**
 * Cycling calorie estimator (versioned for later calibration against watch data).
 *
 * Base: Compendium MET × ACSM kcal/min formula, adjusted by grade and optional HR.
 * Export every ride with [FORMULA_VERSION] so we can recompute after tuning.
 */
object CalorieEstimator {
    const val FORMULA_VERSION = "ridehud-v2-met-grade-hr-moving"

    /**
     * Instantaneous metabolic kilocalories per second.
     */
    fun kcalPerSecond(
        weightKg: Float,
        speedKmh: Float,
        gradePercent: Float?,
        heartRateBpm: Int?,
    ): Double {
        if (weightKg <= 0f) return 0.0
        val movingSpeed = speedKmh.coerceAtLeast(0f)
        // Below moving threshold: idle MET only (don't inflate with grade/HR while stopped)
        if (movingSpeed < RideMetrics.MOVING_SPEED_KMH) {
            return metForSpeedKmh(movingSpeed) * 3.5 * weightKg / 200.0 / 60.0
        }
        var met = metForSpeedKmh(movingSpeed) * gradeFactor(gradePercent)
        met *= heartRateFactor(heartRateBpm, movingSpeed).toFloat()
        // ACSM: kcal/min = MET × 3.5 × bodyMassKg / 200
        return met * 3.5 * weightKg / 200.0 / 60.0
    }

    fun metForSpeedKmh(speedKmh: Float): Float = when {
        speedKmh < RideMetrics.MOVING_SPEED_KMH -> 1.8f // stopped / waiting
        speedKmh < 16f -> 4.0f
        speedKmh < 19f -> 6.8f
        speedKmh < 22f -> 8.0f
        speedKmh < 25f -> 10.0f
        speedKmh < 30f -> 12.0f
        else -> 15.8f
    }

    fun gradeFactor(gradePercent: Float?): Float {
        val g = (gradePercent ?: 0f).coerceIn(-15f, 20f)
        // Uphill costs more; downhill less but not below ~70%
        return (1f + g / 100f * 2.5f).coerceIn(0.7f, 1.8f)
    }

    /**
     * Mild HR nudge when heart rate is available; disabled when nearly stopped.
     */
    fun heartRateFactor(heartRateBpm: Int?, speedKmh: Float): Double {
        if (heartRateBpm == null || speedKmh < 3f) return 1.0
        val hr = heartRateBpm.coerceIn(60, 200)
        // Around 100 bpm ~ baseline; 180 bpm ~ +15%
        return 1.0 + ((hr - 100).coerceAtLeast(0) / 80.0) * 0.15
    }
}
