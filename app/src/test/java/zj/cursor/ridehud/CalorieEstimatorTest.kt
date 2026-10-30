package zj.cursor.ridehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import zj.cursor.ridehud.domain.CalorieEstimator
import zj.cursor.ridehud.domain.HuaweiHealthRideDefaults

class CalorieEstimatorTest {
    @Test
    fun fasterSpeedBurnsMore() {
        val slow = CalorieEstimator.kcalPerSecond(70f, 15f)
        val fast = CalorieEstimator.kcalPerSecond(70f, 28f)
        assertTrue(fast > slow)
    }

    @Test
    fun activeIsBelowOrEqualTotal() {
        val total = CalorieEstimator.kcalPerSecond(70f, 20f)
        val active = CalorieEstimator.activeKcalPerSecond(70f, 20f)
        assertTrue(active <= total + 1e-9)
        assertTrue(active > 0.0)
    }

    @Test
    fun stoppedIsRestOnlyActiveZero() {
        val rest = CalorieEstimator.restKcalPerSecond(70f)
        val total = CalorieEstimator.kcalPerSecond(70f, 0f)
        val active = CalorieEstimator.activeKcalPerSecond(70f, 0f)
        assertEquals(rest, total, 1e-9)
        assertEquals(0.0, active, 1e-9)
    }

    @Test
    fun truSportCapsSpeedAt6Mps() {
        val atCap = CalorieEstimator.truSportEeKcalPerSecond(70f, 21.6f) // 6 m/s
        val above = CalorieEstimator.truSportEeKcalPerSecond(70f, 40f)
        assertEquals(atCap, above, 1e-6)
    }

    @Test
    fun acsmManualMatchesMet8() {
        // 30 min, 70 kg, MET 8 → (8*3.5*30*70)/200 = 294
        val kcal = CalorieEstimator.acsmBikeKcal(70f, 30 * 60_000L)
        assertEquals(294f, kcal, 0.5f)
        assertEquals(8f, HuaweiHealthRideDefaults.BIKE_METS, 0.01f)
    }

    @Test
    fun formulaVersionIsTruSport() {
        assertEquals("ridehud-v3-trusport-ee-bike", CalorieEstimator.FORMULA_VERSION)
    }
}
