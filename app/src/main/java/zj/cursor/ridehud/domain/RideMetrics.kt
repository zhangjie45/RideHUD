package zj.cursor.ridehud.domain

data class RideMetrics(
    val speedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val heartRateBpm: Int? = null,
    val distanceM: Float = 0f,
    /** 运动时间 — excludes auto/manual pause (Huawei TIME_ONE_SECOND_DURATION). */
    val elapsedMs: Long = 0L,
    /** 总时长 — wall clock from ride start (includes pauses). */
    val totalElapsedMs: Long = 0L,
    /**
     * Kept for compatibility; with Huawei auto-pause this tracks the same as [elapsedMs]
     * (avg speed = distance / 运动时间).
     */
    val movingElapsedMs: Long = 0L,
    val altitudeM: Float? = null,
    val gradePercent: Float? = null,
    val elevationGainM: Float = 0f,
    val elevationLossM: Float = 0f,
    val caloriesKcal: Float = 0f,
    /** Active calories (total − rest), Huawei activeCalorie split. */
    val activeCaloriesKcal: Float = 0f,
    val hasBarometer: Boolean = false,
    val isPaused: Boolean = false,
) {
    companion object {
        /** Huawei bike auto-pause enter (~2 km/h) — idle MET / calorie gate. */
        val MOVING_SPEED_KMH: Float get() = HuaweiHealthRideDefaults.bikePauseEnterKmh

        /** Huawei PaceMapProducer / jjk: distance / 运动时间. */
        fun averageSpeedKmh(distanceM: Float, elapsedMs: Long): Float =
            HuaweiHealthRideDefaults.avgSpeedKmh(distanceM, elapsedMs)
    }
}

enum class HrConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Reconnecting,
}

data class ScannedHrDevice(
    val address: String,
    val name: String,
    val rssi: Int,
)
