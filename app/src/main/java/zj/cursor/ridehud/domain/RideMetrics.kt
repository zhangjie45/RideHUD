package zj.cursor.ridehud.domain

data class RideMetrics(
    val speedKmh: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val heartRateBpm: Int? = null,
    val distanceM: Float = 0f,
    val elapsedMs: Long = 0L,
    val altitudeM: Float? = null,
    val gradePercent: Float? = null,
    val caloriesKcal: Float = 0f,
    val hasBarometer: Boolean = false,
    val isPaused: Boolean = false,
) {
    companion object {
        fun averageSpeedKmh(distanceM: Float, elapsedMs: Long): Float {
            if (elapsedMs < 1_000L || distanceM <= 0f) return 0f
            val hours = elapsedMs / 3_600_000.0
            if (hours <= 0.0) return 0f
            return (distanceM / 1000.0 / hours).toFloat()
        }
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
