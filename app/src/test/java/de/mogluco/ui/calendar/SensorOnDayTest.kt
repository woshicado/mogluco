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

    @Test
    fun failedSensorDoesNotClaimTheDaysAfterItsLastReading() {
        // Activated 1 Aug, last stored reading 5 Aug; the next sensor was only applied on 9 Aug.
        val failed = SensorInfo(
            id = "BAD00000003",
            activationMillis = day(2026, 8, 1, 10),
            endMillis = day(2026, 8, 5, 14),
        )
        val next = SensorInfo(id = "NEW00000004", activationMillis = day(2026, 8, 9, 9))
        val history = listOf(failed, next)

        val lastDay = startOfDay(day(2026, 8, 5))
        val s = sensorOnDay(next, history, lastDay)
        assertEquals("BAD00000003", s?.id)
        assertEquals(5, sensorDayNumber(s!!, lastDay))
        for (dom in 6..8) {
            assertNull(sensorOnDay(next, history, startOfDay(day(2026, 8, dom))))
        }
        assertEquals("NEW00000004", sensorOnDay(next, history, startOfDay(day(2026, 8, 9)))?.id)
        // Its end marker sits on its last day, not on the nominal expiry inside the next one's life.
        val bounded = knownSensors(next, history).first { it.id == "BAD00000003" }
        assertEquals(lastDay, startOfDay(bounded.expiryMillis()))
    }

    @Test
    fun pastSensorNeverRunsPastTheNextActivation() {
        // No last reading known: the nominal life (to 22 Aug) is still cut at the next activation.
        val bounded = knownSensors(current, listOf(old)).first { it.id == "OLD00000001" }
        assertEquals(current.activationMillis, bounded.expiryMillis())
        assertEquals(current.expiryMillis(), knownSensors(current, listOf(old)).last().expiryMillis())
    }

    @Test
    fun pastSensorUsesItsRealLifetime() {
        val fourteen = SensorInfo(id = "OLD00000001", activationMillis = day(2026, 8, 1, 10), lifetimeDays = 14)
        val history = listOf(fourteen)
        val s = sensorOnDay(current, history, startOfDay(day(2026, 8, 15)))
        assertEquals(14, sensorDayNumber(s!!, startOfDay(day(2026, 8, 15))))
        assertEquals(startOfDay(day(2026, 8, 15)), startOfDay(s.expiryMillis()))
        assertNull(sensorOnDay(current, history, startOfDay(day(2026, 8, 16))))
    }

    @Test
    fun newestStoredSensorKeepsItsNominalLifeWithoutACurrentOne() {
        // Before the first reading after an app start there is no current sensor: the newest
        // stored one may still be worn, so its last reading (two days old here) is not its end.
        val worn = old.copy(endMillis = day(2026, 8, 12, 8))
        assertEquals("OLD00000001", sensorOnDay(null, listOf(worn), startOfDay(day(2026, 8, 14)))?.id)
    }
}
