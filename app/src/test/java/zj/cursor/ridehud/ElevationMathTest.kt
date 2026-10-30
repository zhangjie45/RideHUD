package zj.cursor.ridehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zj.cursor.ridehud.data.gps.ElevationTracker

class ElevationMathTest {
    @Test
    fun seaLevelPressureIsNearZeroAltitude() {
        val altitude = ElevationTracker.pressureToAltitudeM(1013.25f)
        assertTrue(kotlin.math.abs(altitude) < 1f)
    }

    @Test
    fun lowerPressureMeansHigherAltitude() {
        val sea = ElevationTracker.pressureToAltitudeM(1013.25f)
        val mountain = ElevationTracker.pressureToAltitudeM(900f)
        assertTrue(mountain > sea + 800f)
    }

    @Test
    fun gradePercentBasic() {
        assertEquals(10f, ElevationTracker.gradePercent(10f, 100f)!!, 0.01f)
        assertEquals(-5f, ElevationTracker.gradePercent(-5f, 100f)!!, 0.01f)
        assertNull(ElevationTracker.gradePercent(1f, 0.5f))
    }

    @Test
    fun olsGradeMatchesConstantClimb() {
        // +10% over 100 m, with a bit of altitude noise — OLS should stay near 10%
        val points = (0..20).map { i ->
            val d = i * 5f
            val noise = if (i % 2 == 0) 0.3f else -0.2f
            ElevationTracker.PathPoint(d, d * 0.10f + noise)
        }
        val grade = ElevationTracker.olsGradePercent(points)!!
        assertEquals(10f, grade, 0.5f)
    }

    @Test
    fun olsGradeDownhill() {
        val points = listOf(
            ElevationTracker.PathPoint(0f, 100f),
            ElevationTracker.PathPoint(25f, 95f),
            ElevationTracker.PathPoint(50f, 90f),
            ElevationTracker.PathPoint(75f, 85f),
            ElevationTracker.PathPoint(100f, 80f),
        )
        val grade = ElevationTracker.olsGradePercent(points)!!
        assertEquals(-20f, grade, 0.2f)
    }

    @Test
    fun olsNeedsEnoughPoints() {
        assertNull(
            ElevationTracker.olsGradePercent(
                listOf(
                    ElevationTracker.PathPoint(0f, 10f),
                    ElevationTracker.PathPoint(10f, 11f),
                ),
            ),
        )
    }

    @Test
    fun creepingWaveIgnoresSubThresholdNoise() {
        // ±0.3 m wobble around 100 — below Huawei 0.6 m threshold → no gain/loss
        val heights = listOf(100f, 100.3f, 99.8f, 100.2f, 99.9f, 100.1f)
        val (gain, loss) = ElevationTracker.creepingWaveGainLoss(
            heights,
            ElevationTracker.BARO_CREEPING_WAVE_M,
        )
        assertEquals(0f, gain, 0.01f)
        assertEquals(0f, loss, 0.01f)
    }

    @Test
    fun creepingWaveCountsClimbAndDescent() {
        // Climb 10 m then descend 4 m with 0.6 m gate
        val heights = listOf(100f, 103f, 106f, 110f, 108f, 106f)
        val (gain, loss) = ElevationTracker.creepingWaveGainLoss(
            heights,
            ElevationTracker.BARO_CREEPING_WAVE_M,
        )
        assertEquals(10f, gain, 0.05f)
        assertEquals(4f, loss, 0.05f)
    }
}

