package de.mogluco.ui.calendar

import de.mogluco.data.SensorInfo
import de.mogluco.ui.util.startOfDay
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorOnDayTest {

    private fun day(year: Int, month: Int, dom: Int, hour: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, dom, hour, 0, 0)
        }.timeInMillis

    private val old = SensorInfo(id = "OLD00000001", activationMillis = day(2026, 8, 7, 10))
    private val current = SensorInfo(id = "CUR00000002", activationMillis = day(2026, 8, 21, 18))

    @Test
    fun pastDayNamesThePastSensorWithItsDayNumber() {
        val d = startOfDay(day(2026, 8, 14))
        val s = sensorOnDay(current, listOf(old, current), d)
        assertEquals("OLD00000001", s?.id)
        assertEquals(8, sensorDayNumber(s!!, d))
    }

    @Test
    fun overlapPicksTheNewerSensorFromItsActivationDay() {
        // Old sensor's life runs to 22 Aug; the new one started 21 Aug and takes over that day.
        assertEquals("CUR00000002", sensorOnDay(current, listOf(old), startOfDay(day(2026, 8, 21)))?.id)
        assertEquals("OLD00000001", sensorOnDay(current, listOf(old), startOfDay(day(2026, 8, 20)))?.id)
    }

    @Test
    fun dayBeforeAnyKnownSensorHasNone() {
        assertNull(sensorOnDay(current, listOf(old), startOfDay(day(2026, 8, 1))))
    }

    @Test
    fun currentSensorStillShownWhenWornPastExpiry() {
        val d = startOfDay(day(2026, 9, 20))
        assertEquals("CUR00000002", sensorOnDay(current, emptyList(), d)?.id)
    }
}
