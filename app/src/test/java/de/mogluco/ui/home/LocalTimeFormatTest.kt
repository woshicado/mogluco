package de.mogluco.ui.home

import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LocalTimeFormatTest {

    private lateinit var savedZone: TimeZone

    @Before
    fun saveZone() {
        savedZone = TimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun followsAChangeOfTheDefaultTimeZone() {
        val noonUtc = 12 * 60 * 60 * 1000L
        val format = LocalTimeFormat("HH:mm")

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("12:00", format.format(noonUtc))

        // The process outlives a trip: the same formatter must now show the new zone's time.
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+09:00"))
        assertEquals("21:00", format.format(noonUtc))
        assertEquals("21:30", format.format(noonUtc + 30 * 60 * 1000L))
    }

    @Test
    fun listTimesFollowTheZoneToo() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("00:00", timeShort(0L))
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-05:00"))
        assertEquals("19:00", timeShort(0L))
    }
}
