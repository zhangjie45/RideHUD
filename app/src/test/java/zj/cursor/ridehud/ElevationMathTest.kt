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
}
