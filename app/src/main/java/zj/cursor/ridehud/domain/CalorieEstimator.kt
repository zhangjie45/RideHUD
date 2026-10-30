package zj.cursor.ridehud.domain

/**
 * Cycling calorie estimator aligned with Huawei Health REA notes.
 *
 * Phone outdoor bike (sport 259) with watch: Health only **passthrough** — we cannot.
 * Phone-side formulas recovered from APK / native:
 * - **Realtime**: TruSport EE `CyclingSpeedScaling` quadratic (§11.5 `libtrusport_ee.so`)
 * - **Idle / rest**: sit rate from ActiveCalorie path `(weight * 0.02171) / 60` kcal/s (§11.3)
 * - **Manual ACSM**: `MET=8` → `(8 * 3.5 * minutes * kg) / 200` (§11.4) — available as helper
 *
 * Evidence: docs/rea-huawei-health-evidence.md §11
 */
object CalorieEstimator {
    const val FORMULA_VERSION = "ridehud-v3-trusport-ee-bike"

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
        // TruSport path has no grade/HR in the recovered native sample push.
        // Keep unused params for call-site compatibility / future watch passthrough.
        @Suppress("UNUSED_PARAMETER")
        val ignoredGrade = gradePercent
        @Suppress("UNUSED_PARAMETER")
        val ignoredHr = heartRateBpm
        return truSportEeKcalPerSecond(weightKg, movingSpeed)
    }

    /**
     * Instantaneous **active** kcal/s = max(total − rest, 0)
     * (same split idea as ActiveCalorieAlgorithmSource for skip).
     */
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

    /** §11.3 sit/rest component. */
    fun restKcalPerSecond(weightKg: Float): Double {
        if (weightKg <= 0f) return 0.0
        return weightKg * HuaweiHealthRideDefaults.SIT_REST_KCAL_PER_KG_PER_MIN / 60.0
    }

    /**
     * §11.5 TruSport EE cycling (speed capped at 6 m/s).
     *
     * ```
     * poly = a*v² + b*v + c
     * kcal/s = poly * 3.5 * weight / 12000
     * ```
     * (= ACSM form with MET := poly).
     */
    fun truSportEeKcalPerSecond(weightKg: Float, speedKmh: Float): Double {
        val v = (speedKmh / 3.6f).coerceIn(0f, HuaweiHealthRideDefaults.TRUSPORT_SPEED_CAP_MPS)
        val a = HuaweiHealthRideDefaults.CYCLING_SPEED_SCALING_A
        val b = HuaweiHealthRideDefaults.CYCLING_SPEED_SCALING_B
        val c = HuaweiHealthRideDefaults.CYCLING_SPEED_SCALING_C
        val poly = a * v * v + b * v + c
        return poly * 3.5 * weightKg / 12_000.0
    }

    /** §11.4 manual history ACSM with SportTypeConfig mets=8. */
    fun acsmBikeKcal(weightKg: Float, durationMs: Long, met: Float = HuaweiHealthRideDefaults.BIKE_METS): Float {
        if (weightKg <= 0f || durationMs <= 0L) return 0f
        val minutes = durationMs / 60_000.0
        return ((met * 3.5 * minutes * weightKg) / 200.0).toFloat()
    }

    fun cyclingSpeedScalingMet(speedKmh: Float): Float {
        val v = (speedKmh / 3.6f).coerceIn(0f, HuaweiHealthRideDefaults.TRUSPORT_SPEED_CAP_MPS)
        val a = HuaweiHealthRideDefaults.CYCLING_SPEED_SCALING_A
        val b = HuaweiHealthRideDefaults.CYCLING_SPEED_SCALING_B
        val c = HuaweiHealthRideDefaults.CYCLING_SPEED_SCALING_C
        return a * v * v + b * v + c
    }
}
