package zj.cursor.ridehud.domain

data class RideMetrics(
    val speedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val heartRateBpm: Int? = null,
    val distanceM: Float = 0f,
    /** 运动时间 — excludes auto/manual pause (sport duration (excludes pause)). */
    val elapsedMs: Long = 0L,
    /** 总时长 — wall clock from ride start (includes pauses). */
    val totalElapsedMs: Long = 0L,
    /**
     * Kept for compatibility; with auto-pause this tracks the same as [elapsedMs]
     * (avg speed = distance / 运动时间).
     */
    val movingElapsedMs: Long = 0L,
    val altitudeM: Float? = null,
    val gradePercent: Float? = null,
    val elevationGainM: Float = 0f,
    val elevationLossM: Float = 0f,
    val caloriesKcal: Float = 0f,
    /** Active calories (total − rest), active vs total split. */
    val activeCaloriesKcal: Float = 0f,
    val hasBarometer: Boolean = false,
    val isPaused: Boolean = false,
) {
    companion object {
        /** RideHUD bike auto-pause enter (~2 km/h) — idle MET / calorie gate. */
        val MOVING_SPEED_KMH: Float get() = RideHudDefaults.bikePauseEnterKmh

        /** pace (distance / sport time) / jjk: distance / 运动时间. */
        fun averageSpeedKmh(distanceM: Float, elapsedMs: Long): Float =
            RideHudDefaults.avgSpeedKmh(distanceM, elapsedMs)
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
