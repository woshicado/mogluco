package de.mogluco.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class StuckSetupTest {

    private val minute = 60_000L
    private val grace = 5 * minute
    private val day = 24 * 60 * minute

    @Test
    fun aNewSensorIsStuckFiveMinutesAfterItsWarmUp() {
        // Activated in this app: it has been waiting since before the warm-up ended.
        val warmupEnd = 100 * day
        assertEquals(warmupEnd + grace, stuckSetupAt(warmupEnd, waitingSince = warmupEnd - 60 * minute))
    }

    @Test
    fun anEstablishedSensorGetsTheGraceFromWhenTheAppStartedWaiting() {
        // Cold start, or a take-over of a sensor that has been running elsewhere: the warm-up
        // ended days ago, and that alone must not make it "stuck" before the first reading.
        val warmupEnd = 100 * day
        val coldStart = warmupEnd + 6 * day
        assertEquals(coldStart + grace, stuckSetupAt(warmupEnd, waitingSince = coldStart))
    }

    @Test
    fun theWaitKeepsItsStartForTheSameSensor() {
        val wait = FirstReadingWait()
        assertEquals(1_000L, wait.since("3MH00CK4F2A", now = 1_000L))
        // A later look (recomposition, rotation) must not restart the grace.
        assertEquals(1_000L, wait.since("3MH00CK4F2A", now = 90_000L))
    }

    @Test
    fun theWaitRestartsForAnotherSensorAndAfterAReset() {
        val wait = FirstReadingWait()
        wait.since("3MH00BX2E9C", now = 1_000L)
        assertEquals(50_000L, wait.since("3MH00CK4F2A", now = 50_000L))
        // A reading arrived (or the sensor was removed): a later wait on it is a new one.
        wait.reset()
        assertEquals(70_000L, wait.since("3MH00CK4F2A", now = 70_000L))
    }
}
