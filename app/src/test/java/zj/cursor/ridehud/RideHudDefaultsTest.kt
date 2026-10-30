package zj.cursor.ridehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import zj.cursor.ridehud.domain.RideHudDefaults

class RideHudDefaultsTest {
    @Test
    fun pauseEnterIsTwoKmh() {
        assertEquals(2.0f, RideHudDefaults.bikePauseEnterKmh, 0.01f)
    }

    @Test
    fun speedRawMaxMapsTo40Kmh() {
        assertEquals(40f, RideHudDefaults.speedKmhMax, 0.01f)
    }

    @Test
    fun avgSpeedMatchesPaceFormula() {
        val ms = (29 * 60 + 33) * 1000L
        val avg = RideHudDefaults.avgSpeedKmh(9750f, ms)
        assertEquals(19.80f, avg, 0.05f)
    }

    @Test
    fun sentinelMatchesDefaults() {
        assertTrue(RideHudDefaults.isInvalidGpsSentinel(-80.0, 90.0))
        assertFalse(RideHudDefaults.isInvalidGpsSentinel(120.0, 30.0))
    }

    @Test
    fun slopeValidity() {
        assertTrue(RideHudDefaults.isValidSlopePercent(0f))
        assertTrue(RideHudDefaults.isValidSlopePercent(100f))
        assertFalse(RideHudDefaults.isValidSlopePercent(-101f))
        assertFalse(RideHudDefaults.isValidSlopePercent(101f))
    }

    @Test
    fun paceDistanceRoundsRemainder() {
        assertEquals(100f, RideHudDefaults.paceDistanceM(100f), 0.01f)
        assertEquals(110f, RideHudDefaults.paceDistanceM(105f), 0.01f)
        assertEquals(104f, RideHudDefaults.paceDistanceM(104f), 0.01f)
    }
}
