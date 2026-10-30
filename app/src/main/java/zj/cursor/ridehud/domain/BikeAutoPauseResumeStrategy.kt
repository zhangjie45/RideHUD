package zj.cursor.ridehud.domain

/**
 * Huawei Health [Track_BikeAutoPauseResumeStrategy] (`jdk`) port.
 *
 * Input: rolling queue of up to 4 speed samples in **m/s**.
 * RideHUD feeds km/h and converts internally.
 *
 * Evidence: docs/rea-huawei-health-evidence.md §2.1
 * Constants: [HuaweiHealthRideDefaults]
 */
class BikeAutoPauseResumeStrategy(
    private val windowSize: Int = HuaweiHealthRideDefaults.BIKE_SPEED_WINDOW,
) {
    enum class Action { None, Pause, Resume }

    private val speedsMps = ArrayDeque<Float>(windowSize)

    fun reset() {
        speedsMps.clear()
    }

    fun onSpeedKmh(speedKmh: Float): Action {
        val mps = (speedKmh.coerceAtLeast(0f)) / 3.6f
        speedsMps.addLast(mps)
        while (speedsMps.size > windowSize) {
            speedsMps.removeFirst()
        }
        if (speedsMps.size < windowSize) return Action.None

        val list = speedsMps.toList()
        if (shouldPause(list)) return Action.Pause
        if (shouldResume(list)) return Action.Resume
        return Action.None
    }

    private fun shouldPause(list: List<Float>): Boolean {
        val thr = HuaweiHealthRideDefaults.BIKE_PAUSE_MPS
        for (i in 0 until list.size - 1) {
            if (list[i] < thr && list[i + 1] < thr) return true
        }
        return false
    }

    private fun shouldResume(list: List<Float>): Boolean {
        val d = HuaweiHealthRideDefaults
        if (hasRisingWindow(list, n = 2, thr = d.BIKE_RESUME_STRONG_MPS)) return true
        if (hasRisingWindow(list, n = 3, thr = d.BIKE_RESUME_MID_MPS)) return true
        if (hasRisingWindow(list, n = 4, thr = d.BIKE_RESUME_WEAK_MPS)) return true
        val flat = d.BIKE_RESUME_FLAT_MPS
        for (i in 0..list.size - 3) {
            if (list[i] > flat && list[i + 1] > flat && list[i + 2] > flat) {
                return true
            }
        }
        return false
    }

    /** Strictly increasing window of length [n] whose last sample ≥ [thr]. */
    private fun hasRisingWindow(list: List<Float>, n: Int, thr: Float): Boolean {
        if (list.size < n) return false
        for (start in 0..list.size - n) {
            var rising = true
            for (k in start until start + n - 1) {
                if (list[k + 1] <= list[k]) {
                    rising = false
                    break
                }
            }
            if (rising && list[start + n - 1] >= thr) return true
        }
        return false
    }
}
