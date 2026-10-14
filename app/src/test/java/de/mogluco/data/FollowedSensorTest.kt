package de.mogluco.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of two sensors that are active at once the live path follows: :shared's
 * [takesOverLivePath], the decision table of NativeGlucoseRepository.followsSensor.
 */
class FollowedSensorTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val window = 6 * minute
    private val warmup = SensorInfo.WARMUP_MS
    private val noon = 1_700_000_000_000L

    /** What the followed sensor is in, in the table tests. */
    private enum class Followed {
        /** Activated half an hour ago, no reading of it anywhere. */
        NEVER_DELIVERED,
        /** Three days old and it never started; the process has been running all along. */
        NEVER_STARTED,
        DELIVERING,
        SILENT,
        /** Not heard by this process, but the store holds a reading of it. */
        SILENT_STORED,
        /** Not heard, nothing stored yet — two minutes after the process started. */
        SILENT_PROCESS_START,
        ENDED,
    }

    private enum class Reporter(val start: (Long) -> Long) {
        LATER({ it + 60 * 60_000L }),
        EARLIER({ it - 10 * 24 * 60 * 60_000L }),
        UNKNOWN({ 0L }),
        TIE({ it }),
    }

    private fun takesOver(followed: Followed, reporter: Reporter, reading: Boolean): Boolean {
        val start = if (followed == Followed.NEVER_DELIVERED) noon - 30 * minute else noon - 3 * day
        return takesOverLivePath(
            reading = reading,
            reporterStartMillis = reporter.start(start),
            followedStartMillis = start,
            followedLastHeardMillis = when (followed) {
                Followed.DELIVERING -> noon - minute
                Followed.SILENT, Followed.ENDED -> noon - 20 * minute
                else -> 0L
            },
            followedHasStoredReading = followed == Followed.SILENT_STORED,
            followedEnded = followed == Followed.ENDED,
            processStartMillis = if (followed == Followed.SILENT_PROCESS_START) noon - 2 * minute else noon - 5 * day,
            now = noon,
            streamingWindowMillis = window,
        )
    }

    private fun assertRow(followed: Followed, reporter: Reporter, reading: Boolean, connection: Boolean) {
        assertEquals("$followed / $reporter / reading", reading, takesOver(followed, reporter, reading = true))
        assertEquals("$followed / $reporter / connection", connection, takesOver(followed, reporter, reading = false))
    }

    // --- the table, cell by cell ---

    @Test
    fun neverDeliveredYieldsToAnyReadingAndToALaterSensorsConnection() {
        for (followed in listOf(Followed.NEVER_DELIVERED, Followed.NEVER_STARTED)) {
            assertRow(followed, Reporter.LATER, reading = true, connection = true)
            assertRow(followed, Reporter.EARLIER, reading = true, connection = false)
            assertRow(followed, Reporter.UNKNOWN, reading = true, connection = false)
            assertRow(followed, Reporter.TIE, reading = true, connection = false)
        }
    }

    @Test
    fun deliveringYieldsOnlyToAReadingOfALaterSensor() {
        assertRow(Followed.DELIVERING, Reporter.LATER, reading = true, connection = false)
        assertRow(Followed.DELIVERING, Reporter.EARLIER, reading = false, connection = false)
        assertRow(Followed.DELIVERING, Reporter.UNKNOWN, reading = false, connection = false)
        assertRow(Followed.DELIVERING, Reporter.TIE, reading = false, connection = false)
    }

    @Test
    fun silentDoesNotYieldToAnEarlierSensor() {
        for (followed in listOf(Followed.SILENT, Followed.SILENT_STORED, Followed.SILENT_PROCESS_START)) {
            assertRow(followed, Reporter.LATER, reading = true, connection = true)
            assertRow(followed, Reporter.EARLIER, reading = false, connection = false)
            assertRow(followed, Reporter.UNKNOWN, reading = true, connection = false)
            assertRow(followed, Reporter.TIE, reading = true, connection = false)
        }
    }

    @Test
    fun endedYieldsToAnyReading() {
        assertRow(Followed.ENDED, Reporter.LATER, reading = true, connection = true)
        assertRow(Followed.ENDED, Reporter.EARLIER, reading = true, connection = false)
        assertRow(Followed.ENDED, Reporter.UNKNOWN, reading = true, connection = false)
        assertRow(Followed.ENDED, Reporter.TIE, reading = true, connection = false)
    }

    // --- the edges of the states, for a reading of an earlier-activated sensor ---

    private fun earlierReadingTakesOver(
        start: Long = noon - 3 * day,
        lastHeard: Long = 0L,
        stored: Boolean = false,
        ended: Boolean = false,
        processStart: Long = noon - 5 * day,
    ) = takesOverLivePath(
        reading = true,
        reporterStartMillis = start - day,
        followedStartMillis = start,
        followedLastHeardMillis = lastHeard,
        followedHasStoredReading = stored,
        followedEnded = ended,
        processStartMillis = processStart,
        now = noon,
        streamingWindowMillis = window,
    )

    @Test
    fun aSingleReadingEndsNeverDelivered() {
        // Heard once, during its warm-up, and silent since.
        assertFalse(earlierReadingTakesOver(start = noon - 30 * minute, lastHeard = noon - 10 * minute))
        assertFalse(earlierReadingTakesOver(lastHeard = noon - 2 * day))
        assertFalse(earlierReadingTakesOver(stored = true))
    }

    @Test
    fun afterAProcessStartASensorPastItsWarmUpGetsTheWindowToShow() {
        assertFalse(earlierReadingTakesOver(processStart = noon))
        assertFalse(earlierReadingTakesOver(processStart = noon - window))
        // Then it has had its chance: nothing heard, nothing stored — it never delivered.
        assertTrue(earlierReadingTakesOver(processStart = noon - window - 1))
    }

    @Test
    fun aSensorStillWarmingUpAtTheProcessStartGetsNoSuchWindow() {
        val processStart = noon - minute
        assertFalse(earlierReadingTakesOver(start = processStart - warmup, processStart = processStart))
        assertTrue(earlierReadingTakesOver(start = processStart - warmup + 1, processStart = processStart))
    }

    @Test
    fun deliveringLastsExactlyTheWindow() {
        // An "ended" sensor, so that the window is all that holds the earlier one back.
        assertFalse(earlierReadingTakesOver(lastHeard = noon - window, ended = true))
        assertTrue(earlierReadingTakesOver(lastHeard = noon - window - 1, ended = true))
    }

    @Test
    fun aSensorThatStillDeliversIsNotTakenAsEnded() {
        assertFalse(earlierReadingTakesOver(lastHeard = noon - minute, ended = true))
    }

    @Test
    fun aKnownStartCountsAsLaterThanAnUnknownOne() {
        fun takesOver(reading: Boolean, lastHeard: Long) = takesOverLivePath(
            reading = reading,
            reporterStartMillis = noon - 3 * day,
            followedStartMillis = 0L,
            followedLastHeardMillis = lastHeard,
            followedHasStoredReading = false,
            followedEnded = false,
            processStartMillis = noon - 5 * day,
            now = noon,
            streamingWindowMillis = window,
        )
        assertTrue(takesOver(reading = true, lastHeard = noon - minute))
        assertFalse(takesOver(reading = false, lastHeard = noon - minute))
        assertTrue(takesOver(reading = false, lastHeard = 0L))
    }

    // --- sequences: the session bookkeeping of the repository's two listeners, replayed ---

    /** followsSensor + beginSessionIfNeeded + the listeners' lastHeardMillis, without the engine. */
    private inner class LivePath(
        private val starts: Map<String, Long>,
        private val processStart: Long = noon - 5 * day,
        private val stored: Set<String> = emptySet(),
        private val ended: Set<String> = emptySet(),
    ) {
        var followed: String? = null
        var lastHeard = 0L
        var now = noon

        private fun follows(serial: String, reading: Boolean): Boolean {
            val current = followed
            if (current == null || current == serial) return true
            return takesOverLivePath(
                reading = reading,
                reporterStartMillis = starts[serial] ?: 0L,
                followedStartMillis = starts[current] ?: 0L,
                followedLastHeardMillis = lastHeard,
                followedHasStoredReading = current in stored,
                followedEnded = current in ended,
                processStartMillis = processStart,
                now = now,
                streamingWindowMillis = window,
            )
        }

        private fun begin(serial: String) {
            if (followed != serial) {
                followed = serial
                lastHeard = 0L
            }
        }

        /** A reading of [serial] after [afterMinutes]; whether the live path took it. */
        fun reading(serial: String, afterMinutes: Long = 0): Boolean {
            now += afterMinutes * minute
            if (!follows(serial, reading = true)) return false
            begin(serial)
            lastHeard = now
            return true
        }

        /** A connection event of [serial] (or its activation scan) after [afterMinutes]. */
        fun connection(serial: String, afterMinutes: Long = 0): Boolean {
            now += afterMinutes * minute
            if (!follows(serial, reading = false)) return false
            begin(serial)
            return true
        }

        /** The process starts with [serial] followed (followNewestSensorAtStart). */
        fun startFollowing(serial: String) = begin(serial)
    }

    private val old = "OLD"
    private val new = "NEW"

    /** OLD activated ten days ago, NEW [newAgeMinutes] ago. */
    private fun twoSensors(newAgeMinutes: Long) =
        mapOf(old to noon - 10 * day, new to noon - newAgeMinutes * minute)

    @Test
    fun oneSensorIsAlwaysFollowed() {
        val path = LivePath(mapOf(old to 0L), processStart = noon)
        assertTrue(path.connection(old))
        assertTrue(path.reading(old, afterMinutes = 1))
        assertTrue(path.reading(old, afterMinutes = 600)) // after hours of silence
        assertTrue(path.connection(old, afterMinutes = 30))
        assertEquals(old, path.followed)
    }

    @Test
    fun theReplacedSensorDoesNotTakeThePathBackWhenTheNewOneGoesSilent() {
        val path = LivePath(twoSensors(newAgeMinutes = 5 * 60))
        assertTrue(path.reading(new))
        assertFalse(path.reading(old, afterMinutes = 1))
        // NEW drops out; OLD keeps transmitting, for hours.
        repeat(180) { assertFalse(path.reading(old, afterMinutes = 1)) }
        assertFalse(path.connection(old))
        assertEquals(new, path.followed)
        assertTrue(path.reading(new, afterMinutes = 1))
    }

    @Test
    fun theOldSensorStaysThroughTheNewOnesWarmUpAndIsLeftOutFromItsFirstReading() {
        val path = LivePath(twoSensors(newAgeMinutes = 0))
        assertTrue(path.reading(old))
        // The activation scan and the new sensor's connections do not switch while OLD delivers.
        assertFalse(path.connection(new))
        repeat(60) {
            assertTrue(path.reading(old, afterMinutes = 1))
            assertFalse(path.connection(new))
        }
        assertEquals(old, path.followed)
        assertTrue(path.reading(new, afterMinutes = 1))
        repeat(20) {
            assertFalse(path.reading(old, afterMinutes = 1))
            assertTrue(path.reading(new))
        }
        assertEquals(new, path.followed)
    }

    @Test
    fun afterANormalReplacementTheNewSensorsWarmUpShowsAtOnce() {
        val path = LivePath(twoSensors(newAgeMinutes = 0))
        assertTrue(path.reading(old))
        // OLD is taken off and silent; NEW is scanned ten minutes later.
        assertTrue(path.connection(new, afterMinutes = 10))
        assertEquals(new, path.followed)
        assertTrue(path.connection(new, afterMinutes = 20))
        assertTrue(path.reading(new, afterMinutes = 35))
    }

    @Test
    fun anOldSensorThatComesBackDuringTheWarmUpIsFollowedUntilTheNewOnesFirstReading() {
        val path = LivePath(twoSensors(newAgeMinutes = 0))
        assertTrue(path.reading(old))
        assertTrue(path.connection(new, afterMinutes = 10))
        assertTrue(path.reading(old, afterMinutes = 5)) // back within reach, NEW still warming up
        assertFalse(path.connection(new, afterMinutes = 1))
        assertEquals(old, path.followed)
        assertTrue(path.reading(new, afterMinutes = 50))
        assertFalse(path.reading(old, afterMinutes = 1))
    }

    @Test
    fun aNewSensorThatNeverStartsDoesNotShutOutTheOldOne() {
        val path = LivePath(twoSensors(newAgeMinutes = 0))
        assertTrue(path.reading(old))
        // OLD is out of reach for a while; NEW, which will never deliver, connects meanwhile.
        assertTrue(path.connection(new, afterMinutes = 3 * 60))
        assertEquals(new, path.followed)
        assertTrue(path.reading(old, afterMinutes = 30))
        assertFalse(path.connection(new, afterMinutes = 1))
        assertEquals(old, path.followed)
    }

    @Test
    fun afterAProcessStartTheOldSensorDoesNotGetInBeforeTheWarmedUpNewOne() {
        // NEW is followed from the start, or was the first to report (a connection).
        for (seeded in listOf(true, false)) {
            val path = LivePath(twoSensors(newAgeMinutes = 5 * 60), processStart = noon)
            if (seeded) path.startFollowing(new) else assertTrue(path.connection(new))
            assertFalse(path.connection(old))
            assertFalse(path.reading(old, afterMinutes = 1))
            assertTrue(path.reading(new))
            // … and having delivered, NEW keeps the path through any silence.
            repeat(120) { assertFalse(path.reading(old, afterMinutes = 1)) }
            assertEquals(new, path.followed)
        }
        // OLD connected first: NEW's connection takes over before either has delivered.
        val path = LivePath(twoSensors(newAgeMinutes = 5 * 60), processStart = noon)
        assertTrue(path.connection(old))
        assertTrue(path.connection(new))
        assertFalse(path.reading(old, afterMinutes = 1))
        assertEquals(new, path.followed)
    }

    @Test
    fun afterAProcessStartASensorWithStoredReadingsKeepsThePathWhileOutOfReach() {
        val path = LivePath(twoSensors(newAgeMinutes = 5 * 60), processStart = noon, stored = setOf(new))
        path.startFollowing(new)
        repeat(180) { assertFalse(path.reading(old, afterMinutes = 1)) }
        assertEquals(new, path.followed)
    }

    @Test
    fun afterAProcessStartASensorNeverHeardOfYieldsOnceTheWindowIsOver() {
        // No reading of NEW in the store (it never started — or this is the watch, which has no
        // such list): OLD is held back for the window, not for good.
        val path = LivePath(twoSensors(newAgeMinutes = 5 * 60), processStart = noon)
        path.startFollowing(new)
        repeat(6) { assertFalse(path.reading(old, afterMinutes = 1)) }
        assertTrue(path.reading(old, afterMinutes = 1))
        assertEquals(old, path.followed)
        // NEW's first reading still takes over, and then for good.
        assertTrue(path.reading(new, afterMinutes = 1))
        repeat(30) { assertFalse(path.reading(old, afterMinutes = 1)) }
    }

    @Test
    fun afterAProcessStartDuringTheWarmUpTheOldSensorIsStillFollowed() {
        val path = LivePath(twoSensors(newAgeMinutes = 20), processStart = noon)
        assertTrue(path.connection(new))
        assertTrue(path.reading(old, afterMinutes = 1))
        assertFalse(path.connection(new))
        assertEquals(old, path.followed)
    }

    @Test
    fun anEndedSensorLetsTheEarlierOneBack() {
        val path = LivePath(twoSensors(newAgeMinutes = 5 * 60), ended = setOf(new))
        assertTrue(path.reading(new))
        assertFalse(path.reading(old, afterMinutes = 1)) // NEW was heard a minute ago
        assertTrue(path.reading(old, afterMinutes = 6))
        assertEquals(old, path.followed)
    }

    @Test
    fun unknownStartsNeitherAlternateNorDeadlock() {
        val path = LivePath(emptyMap())
        // Both deliver every minute: whoever was first stays.
        assertTrue(path.reading("X"))
        repeat(30) {
            assertFalse(path.reading("Y"))
            assertFalse(path.connection("Y"))
            assertTrue(path.reading("X", afterMinutes = 1))
        }
        // X stops: Y takes over with its first reading after the window, and then stays.
        assertFalse(path.reading("Y", afterMinutes = 6))
        assertTrue(path.reading("Y", afterMinutes = 1))
        repeat(30) {
            assertFalse(path.reading("X"))
            assertTrue(path.reading("Y", afterMinutes = 1))
        }
        // A sensor that never delivers does not hold the path either.
        val idle = LivePath(emptyMap(), processStart = noon)
        assertTrue(idle.connection("X"))
        assertFalse(idle.connection("Y"))
        assertTrue(idle.reading("Y", afterMinutes = 1))
        assertEquals("Y", idle.followed)
    }

    @Test
    fun readingsOfTwoSensorsOutOfOrderFollowTheLaterActivatedOne() {
        // The mirror-only watch: both sensors' readings arrive, in any order and in bursts.
        val path = LivePath(twoSensors(newAgeMinutes = 5 * 60), processStart = noon)
        val order = listOf(old, old, new, old, new, new, old, old, old, new, old)
        val taken = order.map { path.reading(it) }
        val firstNew = order.indexOf(new)
        order.forEachIndexed { i, serial ->
            assertEquals("reading $i of $serial", serial == new || i < firstNew, taken[i])
        }
        assertEquals(new, path.followed)
    }
}
