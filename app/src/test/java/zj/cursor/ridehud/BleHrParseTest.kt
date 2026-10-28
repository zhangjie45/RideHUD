package zj.cursor.ridehud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import zj.cursor.ridehud.data.ble.BleHrClient

class BleHrParseTest {
    @Test
    fun parseUint8HeartRate() {
        val value = byteArrayOf(0x00, 0x48) // flags=0, bpm=72
        assertEquals(72, BleHrClient.parseHeartRate(value))
    }

    @Test
    fun parseUint16HeartRate() {
        val value = byteArrayOf(0x01, 0x2C.toByte(), 0x01) // flags=1, bpm=300
        assertEquals(300, BleHrClient.parseHeartRate(value))
    }

    @Test
    fun parseEmptyReturnsNull() {
        assertNull(BleHrClient.parseHeartRate(byteArrayOf()))
        assertNull(BleHrClient.parseHeartRate(null))
    }
}
