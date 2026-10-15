package de.mogluco.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorErrorSelectionTest {

    private val handshake = SensorError.fromConnection(19, "SEC_CHAR_CHALLENGE_DATA")!!

    @Test
    fun warmUpOutranksTheReadingDerivedCodes() {
        // Placeholder readings during warm-up: an invalid one (state 6) or one at the edge of the
        // range (alarm 4/5) must leave the warm-up note in place.
        assertNull(SensorError.select(state = 6, connection = null, alarm = 0, warmingUp = true))
        for (alarm in 4..5) assertNull(SensorError.select(state = 0, connection = null, alarm = alarm, warmingUp = true))
    }

    @Test
    fun conclusiveFailuresStillShowDuringWarmUp() {
        assertEquals(9, SensorError.select(state = 9, connection = null, alarm = 0, warmingUp = true)?.code)
        assertEquals(handshake, SensorError.select(state = 6, connection = handshake, alarm = 4, warmingUp = true))
    }

    @Test
    fun afterWarmUpTheUsualPriorityApplies() {
        assertEquals(6, SensorError.select(state = 6, connection = handshake, alarm = 4, warmingUp = false)?.code)
        assertEquals(handshake, SensorError.select(state = 0, connection = handshake, alarm = 4, warmingUp = false))
        assertEquals(4, SensorError.select(state = 0, connection = null, alarm = 4, warmingUp = false)?.code)
        assertNull(SensorError.select(state = 0, connection = null, alarm = 0, warmingUp = false))
    }

    @Test
    fun everyCodeIsLabelledWithItsSourceNotAsASensorCode() {
        assertEquals("Mogluco code 6", SensorError.fromSensorState(6)!!.codeLabel)
        assertEquals("Mogluco code 4", SensorError.fromGlucoseAlarm(4)!!.codeLabel)
        assertEquals("Bluetooth status 147", SensorError.fromConnection(147, null)!!.codeLabel)
        assertEquals("Bluetooth status 19", handshake.codeLabel)
        val note = SensorError.fromSensorState(6)!!.sourceNote
        assertTrue(note, "Mogluco's own code, not an error code from the sensor" in note && "serial number" in note)
        val bluetooth = SensorError.fromConnection(147, null)!!.sourceNote
        assertTrue(bluetooth, "phone's Bluetooth, not an error code from the sensor" in bluetooth)
    }

    @Test
    fun glucoseAvailableAgainIsNoFault() {
        // isAgain=3 comes with the first valid reading after a gap: data is back, not missing.
        assertNull(SensorError.fromGlucoseAlarm(3))
        assertNull(SensorError.select(state = 0, connection = null, alarm = 3, warmingUp = false))
    }

    @Test
    fun aReadingAtTheRangeEdgeIsMarkedAsMoglucosButNoSensorFault() {
        // Still named as Mogluco's code, but the sensor works: no hint about reporting a failed one.
        for (alarm in 4..5) {
            val note = SensorError.fromGlucoseAlarm(alarm)!!.sourceNote
            assertEquals("This is Mogluco's own code, not an error code from the sensor.", note)
        }
    }

    @Test
    fun anInvalidReadingIsDescribedAsUsuallyPassing() {
        val error = SensorError.fromSensorState(6)!!
        assertEquals("Invalid reading", error.title)
        assertTrue(error.meaning, "usually clears with the next reading" in error.meaning)
    }
}
