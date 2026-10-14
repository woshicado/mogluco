package de.mogluco.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorConnectionErrorTest {

    @Test
    fun aConnectTimeoutIsAnUnreachableSensorNotARejectedHandshake() {
        // 147 = GATT_CONNECTION_TIMEOUT: the sensor never answered, so no handshake was attempted.
        val error = SensorError.fromConnection(147, null)!!
        assertEquals("Sensor not responding", error.title)
        assertEquals(147, error.code)
        assertTrue(error.meaning, "can't reach the sensor" in error.meaning)
        assertTrue(error.meaning, "handshake" !in error.meaning)
    }

    @Test
    fun linkLossAndFailedConnectsWithoutAStageAreUnreachableToo() {
        assertEquals("Sensor not responding", SensorError.fromConnection(8, null)?.title)
        assertEquals("Sensor not responding", SensorError.fromConnection(133, null)?.title)
    }

    @Test
    fun aRecordedHandshakeStageStillReadsAsSetupFailure() {
        assertEquals("Sensor setup failed", SensorError.fromConnection(147, "SEC_CHAR_CHALLENGE_DATA")?.title)
        assertEquals("Sensor setup failed", SensorError.fromConnection(19, null)?.title)
    }

    @Test
    fun benignDisconnectsStayQuiet() {
        assertNull(SensorError.fromConnection(0, null))
        assertNull(SensorError.fromConnection(22, null))
    }
}
