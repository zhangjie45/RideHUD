package zj.cursor.ridehud.domain

/**
 * One completed ride, ready for local storage and export / later watch calibration.
 */
data class RideRecord(
    val id: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val elapsedMs: Long,
    val distanceM: Float,
    val caloriesKcal: Float,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val avgHeartRateBpm: Float?,
    val maxHeartRateBpm: Int?,
    val elevationGainM: Float,
    val elevationLossM: Float,
    val minAltitudeM: Float?,
    val maxAltitudeM: Float?,
    val weightKgUsed: Float,
    val calorieFormulaVersion: String,
    /** Fill later when user provides watch calories for calibration. */
    val watchCaloriesKcal: Float? = null,
    val notes: String? = null,
    val trackPoints: List<RideTrackPoint> = emptyList(),
)

data class RideTrackPoint(
    val elapsedMs: Long,
    val epochMs: Long,
    val speedKmh: Float,
    val distanceM: Float,
    val heartRateBpm: Int?,
    val altitudeM: Float?,
    val gradePercent: Float?,
    val caloriesKcalCumulative: Float,
)
