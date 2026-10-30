package zj.cursor.ridehud

import org.junit.Assert.assertEquals
import org.junit.Test
import zj.cursor.ridehud.domain.BikeAutoPauseResumeStrategy
import zj.cursor.ridehud.domain.BikeAutoPauseResumeStrategy.Action

class BikeAutoPauseResumeStrategyTest {
    @Test
    fun pauseWhenTwoAdjacentBelow2Kmh() {
        val s = BikeAutoPauseResumeStrategy()
        // Need 4 samples; last two both < 2 km/h
        assertEquals(Action.None, s.onSpeedKmh(10f))
        assertEquals(Action.None, s.onSpeedKmh(8f))
        assertEquals(Action.None, s.onSpeedKmh(1f))
        assertEquals(Action.Pause, s.onSpeedKmh(1f))
    }

    @Test
    fun resumeOnRisingWindowTo85() {
        val s = BikeAutoPauseResumeStrategy()
        // Fill window without triggering pause (avoid two consecutive <2)
        s.onSpeedKmh(3f)
        s.onSpeedKmh(4f)
        s.onSpeedKmh(5f)
        // Rising 2-window ending ≥ 8.5
        assertEquals(Action.Resume, s.onSpeedKmh(9f))
    }

    @Test
    fun resumeOnThreeAbove6() {
        val s = BikeAutoPauseResumeStrategy()
        s.onSpeedKmh(3f)
        assertEquals(Action.None, s.onSpeedKmh(7f))
        assertEquals(Action.None, s.onSpeedKmh(7.5f))
        // 7, 7.5, 8 — three consecutive > 6
        assertEquals(Action.Resume, s.onSpeedKmh(8f))
    }
}
