package zj.cursor.ridehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import zj.cursor.ridehud.domain.HuaweiHealthRideDefaults

class HuaweiHealthRideDefaultsTest {
    @Test
    fun pauseEnterIsTwoKmh() {
        assertEquals(2.0f, HuaweiHealthRideDefaults.bikePauseEnterKmh, 0.01f)
    }

    @Test
    fun speedRawMaxMapsTo40Kmh() {
        assertEquals(40f, HuaweiHealthRideDefaults.speedKmhMax, 0.01f)
    }

    @Test
    fun avgSpeedMatchesHuaweiPaceFormula() {
        // 9.75 km in 29:33 → ≈ 19.80 km/h
        val ms = (29 * 60 + 33) * 1000L
        val avg = HuaweiHealthRideDefaults.avgSpeedKmh(9750f, ms)
        assertEquals(19.80f, avg, 0.05f)
    }

    @Test
    fun sentinelMatchesHealthDefaults() {
        assertTrue(HuaweiHealthRideDefaults.isInvalidGpsSentinel(-80.0, 90.0))
        assertFalse(HuaweiHealthRideDefaults.isInvalidGpsSentinel(120.0, 30.0))
    }

    @Test
    fun slopeValidityMatchesLinkage() {
        assertTrue(HuaweiHealthRideDefaults.isValidSlopePercent(0f))
        assertTrue(HuaweiHealthRideDefaults.isValidSlopePercent(100f))
        assertFalse(HuaweiHealthRideDefaults.isValidSlopePercent(-101f))
        assertFalse(HuaweiHealthRideDefaults.isValidSlopePercent(101f))
    }

    @Test
    fun paceDistanceRoundsRemainder() {
        assertEquals(100f, HuaweiHealthRideDefaults.paceDistanceM(100f), 0.01f)
        assertEquals(110f, HuaweiHealthRideDefaults.paceDistanceM(105f), 0.01f)
        assertEquals(104f, HuaweiHealthRideDefaults.paceDistanceM(104f), 0.01f)
    }
}
