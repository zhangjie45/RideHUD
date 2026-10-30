package zj.cursor.ridehud.domain

/**
 * Defaults lifted from Huawei Health APK REA notes.
 *
 * Source of truth: [docs/rea-huawei-health-evidence.md] §2 / §3 / §5 / §6 / §10
 * Prefer these over ad-hoc OpenTracks numbers wherever the phone can approximate Health.
 */
object HuaweiHealthRideDefaults {
    // --- BikeAutoPauseResumeStrategy (jdk) — exact m/s from APK ---
    const val BIKE_SPEED_WINDOW = 4
    const val BIKE_PAUSE_MPS = 0.5555556f
    const val BIKE_RESUME_STRONG_MPS = 2.3611112f
    const val BIKE_RESUME_MID_MPS = 2.0833335f
    const val BIKE_RESUME_WEAK_MPS = 1.8055556f
    const val BIKE_RESUME_FLAT_MPS = 1.6666667f

    val bikePauseEnterKmh: Float get() = BIKE_PAUSE_MPS * 3.6f // 2.0
    val bikeResumeStrongKmh: Float get() = BIKE_RESUME_STRONG_MPS * 3.6f // 8.5
    val bikeResumeMidKmh: Float get() = BIKE_RESUME_MID_MPS * 3.6f // 7.5
    val bikeResumeWeakKmh: Float get() = BIKE_RESUME_WEAK_MPS * 3.6f // 6.5
    val bikeResumeFlatKmh: Float get() = BIKE_RESUME_FLAT_MPS * 3.6f // 6.0

    /** AR still auto-pause delay (jdh). */
    const val AR_STILL_PAUSE_MS = 10_000L
    /** Power-save still delay (jdo) — not used for ride HUD. */
    const val POWER_SAVE_STILL_MS = 120_000L

    // --- TrackAltitudeMgr ---
    /** Creeping-wave threshold on barometer path. */
    const val ALTITUDE_NOISE_M = 0.6f
    /** Phone GNSS-only fallback (Health baro path is quieter). */
    const val ALTITUDE_GPS_HYSTERESIS_M = 3f

    // --- SpeedSource raw units: 1 raw = 0.01 km/h (log: 500 ⇒ 5 km/h) ---
    const val SPEED_RAW_MAX = 4000
    const val SPEED_RAW_PER_KMH = 100
    val speedKmhMax: Float get() = SPEED_RAW_MAX.toFloat() / SPEED_RAW_PER_KMH // 40 km/h Health clamp
    /** RideHUD allows slightly above Health clamp for steep descents; hard reject above this. */
    const val SPEED_KMH_HARD_MAX = 72f
    /** cleanSpeedData: raw/temp > 5 → treat as 10× inflated. */
    const val SPEED_TENFOLD_RATIO = 5f

    const val CADENCE_RAW_DIVISOR = 2

    // --- Invalid GPS sentinel (HwExerciseConstants + GeoUtils::IsPausePoint) ---
    const val INVALID_GPS_LON = -80.0
    const val INVALID_GPS_LAT = 90.0
    const val INVALID_GPS_EPS = 1e-6

    // --- Avg / max speed (PaceMapProducer / jjk) ---
    // avgSpeed_kmh = distance_m / moving_time_s * 3.6
    const val PACE_UNIT_M_BIKE = 1000
    /** Pace map remainder ≥ 5 m rounds toward next 10 m segment. */
    const val PACE_REMAINDER_ROUND_M = 5
    const val PACE_SEGMENT_M = 10

    // --- SlopeLinkageDataManager (device slope is int %; we approximate with OLS + same buffer) ---
    const val SLOPE_BUFFER_N = 5
    const val SLOPE_REFRESH_MS = 1_000L
    const val SLOPE_VALID_MIN_EXCLUSIVE = -101
    const val SLOPE_VALID_MAX_EXCLUSIVE = 101
    const val SLOPE_PERSIST_SCALE = 10

    /** TRANS_INTO_KILOMETER */
    const val METERS_PER_KM = 1000

    // --- Calories (§11) ---
    /** SportTypeConfig mets for bike 259/265 (manual ACSM). */
    const val BIKE_METS = 8f
    /** ActiveCalorie sit component: rest_kcal/s = weight * this / 60. */
    const val SIT_REST_KCAL_PER_KG_PER_MIN = 0.02171
    /**
     * TruSport EE CyclingSpeedScaling float[3] @ libtrusport_ee.so
     * poly = A*v² + B*v + C, v in m/s capped at [TRUSPORT_SPEED_CAP_MPS].
     */
    const val CYCLING_SPEED_SCALING_A = 0.12543802f
    const val CYCLING_SPEED_SCALING_B = 0.39899185f
    const val CYCLING_SPEED_SCALING_C = 1.8341284f
    const val TRUSPORT_SPEED_CAP_MPS = 6.0f

    // --- Max speed RealTimePaceUtil (jdi) ---
    const val MAX_SPEED_SAMPLE_INTERVAL_MS = 5_000L
    const val REALTIME_PACE_WINDOW = 12

    fun avgSpeedKmh(distanceM: Float, movingTimeMs: Long): Float {
        if (movingTimeMs < 1_000L || distanceM <= 0f) return 0f
        val hours = movingTimeMs / 3_600_000.0
        if (hours <= 0.0) return 0f
        return (distanceM / METERS_PER_KM / hours).toFloat()
    }

    /** Health-style distance used for pace segments (remainder ≥ 5 → + to next 10 m). */
    fun paceDistanceM(distanceM: Float): Float {
        if (distanceM <= 0f) return 0f
        val rem = (distanceM % PACE_SEGMENT_M).toInt()
        return if (rem >= PACE_REMAINDER_ROUND_M) {
            distanceM + (PACE_SEGMENT_M - rem)
        } else {
            distanceM
        }
    }

    fun isValidSlopePercent(slope: Float): Boolean =
        slope > SLOPE_VALID_MIN_EXCLUSIVE && slope < SLOPE_VALID_MAX_EXCLUSIVE

    fun clampSlopePercent(slope: Float): Float? =
        if (isValidSlopePercent(slope)) slope else null

    fun isInvalidGpsSentinel(lon: Double, lat: Double): Boolean =
        kotlin.math.abs(lon - INVALID_GPS_LON) < INVALID_GPS_EPS &&
            kotlin.math.abs(lat - INVALID_GPS_LAT) < INVALID_GPS_EPS
}
