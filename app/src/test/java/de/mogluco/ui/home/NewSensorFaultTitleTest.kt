package de.mogluco.ui.home

import de.mogluco.data.SensorError
import org.junit.Assert.assertEquals
import org.junit.Test

class NewSensorFaultTitleTest {

    @Test
    fun theTitleNamesTheNewSensorAndKeepsTheErrorBannersWording() {
        val error = SensorError.fromConnection(19, "SEC_CHAR_CHALLENGE_DATA")!!
        // ErrorBanner's "<title> (<source> <n>)", so that the same failure reads the same on both.
        assertEquals(
            "New sensor 3MH00CK4F2A: Sensor setup failed (Bluetooth status 19)",
            newSensorFaultTitle("3MH00CK4F2A", error),
        )
    }
}
