package zj.cursor.ridehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import zj.cursor.ridehud.domain.CalorieEstimator

class CalorieEstimatorTest {
    @Test
    fun fasterSpeedBurnsMore() {
        val slow = CalorieEstimator.kcalPerSecond(70f, 15f, 0f, null)
        val fast = CalorieEstimator.kcalPerSecond(70f, 28f, 0f, null)
        assertTrue(fast > slow)
    }

    @Test
    fun uphillBurnsMoreThanFlat() {
        val flat = CalorieEstimator.kcalPerSecond(70f, 20f, 0f, null)
        val up = CalorieEstimator.kcalPerSecond(70f, 20f, 8f, null)
        assertTrue(up > flat)
    }

    @Test
    fun formulaVersionIsStable() {
        assertEquals("ridehud-v1-met-grade-hr", CalorieEstimator.FORMULA_VERSION)
    }
}
