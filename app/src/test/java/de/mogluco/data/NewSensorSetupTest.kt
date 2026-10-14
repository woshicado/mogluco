package de.mogluco.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The setup failure of a newly activated sensor that the live path does not follow yet (the
 * previous one still delivers): :shared's [NewSensorSetup], which NativeGlucoseRepository feeds
 * with the connection events [takesOverLivePath] leaves out, and publishes as newSensorFault.
 */
class NewSensorSetupTest {

    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val window = 6 * minute
    private val warmup = SensorInfo.WARMUP_MS
    private val noon = 1_700_000_000_000L
    private val threshold = 3

    private val old = "OLD"
    private val new = "NEW"

    /** The stage the engine records for a failed Libre 3 handshake (upstream issue #405). */
    private val stage = "SEC_CHAR_CHALLENGE_DATA"

    /** A disconnect of NEW, activated [ageMinutes] ago. */
    private fun NewSensorSetup.disconnect(
        status: Int,
        detail: String? = null,
        ageMinutes: Long = 10,
        serial: String = new,
        now: Long = noon,
    ) = onConnectionEvent(serial, now - ageMinutes * minute, connected = false, status, detail, now)

    private fun NewSensorSetup.connect(ageMinutes: Long = 10, serial: String = new, now: Long = noon) =
        onConnectionEvent(serial, now - ageMinutes * minute, connected = true, status = 0, detail = null, now)

    // --- the classification, as for the followed sensor ---

    @Test
    fun aRecordedHandshakeFailureShowsAtOnceAlsoDuringTheWarmUp() {
        val setup = NewSensorSetup(threshold)
        val fault = setup.disconnect(status = 19, detail = stage)
        assertEquals(new, fault?.id)
        assertEquals(noon - 10 * minute, fault?.activationMillis)
        // The very error the followed sensor's card would carry.
        assertEquals(SensorError.fromConnection(19, stage), fault?.error)
        assertEquals("Sensor setup failed", fault?.error?.title)
    }

    @Test
    fun aHandshakeTheAppAbortedItselfCountsToo() {
        // Status 0 is benign on its own, not with a recorded stage.
        assertNotNull(NewSensorSetup(threshold).disconnect(status = 0, detail = stage))
    }

    @Test
    fun aBenignDisconnectIsNoFailure() {
        val setup = NewSensorSetup(threshold)
        repeat(10) {
            assertNull(setup.disconnect(status = 0, ageMinutes = 2 * 60))
            assertNull(setup.disconnect(status = 22, ageMinutes = 2 * 60))
        }
    }

    @Test
    fun aBareErrorStatusIsIgnoredDuringTheWarmUp() {
        val setup = NewSensorSetup(threshold)
        repeat(10) { assertNull(setup.disconnect(status = 133, ageMinutes = 30)) }
        assertNull(setup.disconnect(status = 133, ageMinutes = 59))
        // … and none of those drops was counted towards the threshold.
        assertNull(setup.disconnect(status = 133, ageMinutes = 60))
        assertNull(setup.disconnect(status = 133, ageMinutes = 61))
        assertNotNull(setup.disconnect(status = 133, ageMinutes = 62))
    }

    @Test
    fun aBareErrorStatusAfterTheWarmUpMustRecur() {
        val setup = NewSensorSetup(threshold)
        assertNull(setup.disconnect(status = 8, ageMinutes = 90))
        assertNull(setup.connect(ageMinutes = 90))
        assertNull(setup.disconnect(status = 0, ageMinutes = 90)) // benign: neither counts nor resets
        assertNull(setup.disconnect(status = 19, ageMinutes = 90))
        val fault = setup.disconnect(status = 133, ageMinutes = 90)
        assertEquals(SensorError.fromConnection(133, null), fault?.error)
    }

    @Test
    fun aConnectDoesNotClearTheFailure() {
        // Every failed handshake begins with a connect; only a reading proves the setup worked.
        val setup = NewSensorSetup(threshold)
        val fault = setup.disconnect(status = 19, detail = stage)
        assertEquals(fault, setup.connect())
        assertEquals(fault, setup.disconnect(status = 0))
        assertEquals(fault, setup.fault)
    }

    @Test
    fun theLatestFailureIsTheOneShown() {
        val setup = NewSensorSetup(threshold)
        setup.disconnect(status = 19, detail = stage)
        assertEquals(SensorError.fromConnection(147, "other stage"), setup.disconnect(status = 147, detail = "other stage")?.error)
    }

    // --- which sensor, and until when ---

    @Test
    fun clearForgetsTheSensorAndItsCount() {
        val setup = NewSensorSetup(threshold)
        setup.disconnect(status = 133, ageMinutes = 90)
        setup.disconnect(status = 133, ageMinutes = 90)
        setup.clear()
        assertNull(setup.serial)
        assertNull(setup.fault)
        assertNull(setup.disconnect(status = 133, ageMinutes = 90))
        assertNull(setup.disconnect(status = 133, ageMinutes = 90))
        assertNotNull(setup.disconnect(status = 133, ageMinutes = 90))
    }

    @Test
    fun onlyTheMostRecentlyActivatedSensorIsWatched() {
        val setup = NewSensorSetup(threshold)
        val fault = setup.disconnect(status = 19, detail = stage, ageMinutes = 10)
        // Another one, activated before it: left out, and it does not disturb the count.
        assertEquals(fault, setup.disconnect(status = 147, detail = stage, ageMinutes = 5 * 60, serial = "BETWEEN"))
        assertEquals(new, setup.serial)
        // One activated after it replaces it, starting afresh.
        assertNull(setup.connect(ageMinutes = 1, serial = "NEWER"))
        assertEquals("NEWER", setup.serial)
        assertNull(setup.fault)
    }

    // --- with the live path: the repository's connection listener, replayed ---

    /**
     * The connection/reading listeners of NativeGlucoseRepository as far as two sensors go, without
     * the engine: the followed sensor by [takesOverLivePath], and what the gate leaves out handed
     * to [NewSensorSetup] if that sensor was activated later.
     */
    private inner class Repo(private val starts: Map<String, Long>) {
        var followed: String? = null
        private var lastHeard = 0L
        var now = noon
        val setup = NewSensorSetup(threshold)

        /** The followed sensor's own failure count — must not move for another sensor's events. */
        var followedFailures = 0

        private fun follows(serial: String, reading: Boolean): Boolean {
            val current = followed
            if (current == null || current == serial) return true
            return takesOverLivePath(
                reading = reading,
                reporterStartMillis = starts[serial] ?: 0L,
                followedStartMillis = starts[current] ?: 0L,
                followedLastHeardMillis = lastHeard,
                followedHasStoredReading = false,
                followedEnded = false,
                processStartMillis = noon - 5 * day,
                now = now,
                streamingWindowMillis = window,
            )
        }

        private fun begin(serial: String) {
            if (followed != serial) {
                followed = serial
                lastHeard = 0L
                followedFailures = 0
                setup.clear()
            }
        }

        fun reading(serial: String, afterMinutes: Long = 0) {
            now += afterMinutes * minute
            if (!follows(serial, reading = true)) return
            begin(serial)
            lastHeard = now
        }

        fun disconnect(serial: String, status: Int, detail: String?, afterMinutes: Long = 0) {
            now += afterMinutes * minute
            if (!follows(serial, reading = false)) {
                val start = starts[serial] ?: 0L
                if (start > (starts[followed] ?: 0L)) {
                    setup.onConnectionEvent(serial, start, connected = false, status, detail, now)
                }
                return
            }
            begin(serial)
            if (SensorError.fromConnection(status, detail) != null) followedFailures++
        }
    }

    private fun twoSensors() = mapOf(old to noon - 10 * day, new to noon)

    @Test
    fun aNewSensorsFailedHandshakeShowsWhileTheOldSensorKeepsTheLivePath() {
        val repo = Repo(twoSensors())
        repo.reading(old)
        // NEW was just applied and activated; OLD is worn on and delivers every minute.
        repeat(30) {
            repo.disconnect(new, status = 19, detail = stage)
            assertEquals(new, repo.setup.fault?.id)
            repo.reading(old, afterMinutes = 1)
        }
        assertEquals(old, repo.followed)
        assertEquals(0, repo.followedFailures)
    }

    @Test
    fun theFailureIsGoneOnceTheNewSensorDeliversOrTakesOver() {
        // Its first reading: followed from then on, with a card of its own.
        val delivered = Repo(twoSensors())
        delivered.reading(old)
        delivered.disconnect(new, status = 19, detail = stage)
        delivered.reading(new, afterMinutes = 61)
        assertEquals(new, delivered.followed)
        assertNull(delivered.setup.fault)

        // OLD goes quiet: NEW's next connection event takes the path, and reports on its card.
        val tookOver = Repo(twoSensors())
        tookOver.reading(old)
        tookOver.disconnect(new, status = 19, detail = stage)
        assertNotNull(tookOver.setup.fault)
        tookOver.disconnect(new, status = 19, detail = stage, afterMinutes = 7)
        assertEquals(new, tookOver.followed)
        assertNull(tookOver.setup.fault)
        assertEquals(1, tookOver.followedFailures)
    }

    @Test
    fun theReplacedSensorIsNeverReportedAsANewOne() {
        val repo = Repo(twoSensors())
        repo.reading(new)
        // OLD, still within reach, fails to connect over and over.
        repeat(10) { repo.disconnect(old, status = 133, detail = stage, afterMinutes = 1) }
        assertEquals(new, repo.followed)
        assertNull(repo.setup.serial)
        assertNull(repo.setup.fault)
    }

    @Test
    fun withOneSensorNothingIsEverWatched() {
        val repo = Repo(mapOf(old to noon - 10 * day))
        repo.reading(old)
        repeat(5) { repo.disconnect(old, status = 133, detail = stage, afterMinutes = 10) }
        assertNull(repo.setup.serial)
        assertEquals(5, repo.followedFailures)
    }
}
