package zj.cursor.ridehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import zj.cursor.ridehud.domain.CalorieEstimator
import zj.cursor.ridehud.domain.RideMetrics

class RideMetricsTest {
    @Test
    fun averageSpeedUsesElapsedHours() {
        // 10 km in 30 minutes → 20 km/h
        val avg = RideMetrics.averageSpeedKmh(10_000f, 30 * 60_000L)
        assertEquals(20f, avg, 0.05f)
    }

    @Test
    fun stoppedCaloriesIgnoreGradeAndHrBoost() {
        val moving = CalorieEstimator.kcalPerSecond(70f, 20f, 8f, 150)
        val stopped = CalorieEstimator.kcalPerSecond(70f, 1f, 8f, 150)
        assertTrue(moving > stopped * 2)
    }
}
