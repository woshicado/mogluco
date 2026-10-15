package de.mogluco.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorConnectionErrorTest {

    @Test
    fun aConnectTimeoutIsNoConnectionNotARejectedHandshake() {
        // 147 = GATT_CONNECTION_TIMEOUT: the sensor never answered, so no handshake was attempted.
        val error = SensorError.fromConnection(147, null)!!
        assertEquals("No connection to sensor", error.title)
        assertEquals(147, error.code)
        assertTrue(error.meaning, "doesn't answer" in error.meaning && "keeps retrying" in error.meaning)
        assertTrue(error.meaning, "handshake" !in error.meaning)
    }

    @Test
    fun linkLossAndFailedConnectsWithoutAStageAreNoConnectionToo() {
        for (status in listOf(8, 62, 133)) {
            assertEquals("No connection to sensor", SensorError.fromConnection(status, null)?.title)
        }
    }

    @Test
    fun theSensorClosingTheLinkIsNotClaimedAsAFailedHandshake() {
        // 19 without a recorded stage: the sensor closed the link; nothing says where or why.
        val error = SensorError.fromConnection(19, null)!!
        assertEquals("Sensor keeps disconnecting", error.title)
        assertTrue(error.meaning, "another device" in error.meaning)
    }

    @Test
    fun onlyARecordedHandshakeStageReadsAsSetupFailure() {
        val error = SensorError.fromConnection(147, "SEC_CHAR_CHALLENGE_DATA")!!
        assertEquals("Sensor setup failed", error.title)
        assertTrue(error.meaning, "SEC_CHAR_CHALLENGE_DATA" in error.meaning)
        assertEquals("Sensor setup failed", SensorError.fromConnection(0, "SEC_CHAR_CHALLENGE_DATA")?.title)
    }

    @Test
    fun benignDisconnectsStayQuiet() {
        assertNull(SensorError.fromConnection(0, null))
        assertNull(SensorError.fromConnection(22, null))
    }
}
