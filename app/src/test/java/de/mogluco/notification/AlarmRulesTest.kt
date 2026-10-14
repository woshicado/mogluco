package de.mogluco.notification

import de.mogluco.data.SensorInfo
import de.mogluco.notification.AlarmController.Kind
import de.mogluco.ui.settings.alarmDurationStepDown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRulesTest {

    private val minute = 60_000L
    private val noon = 1_700_000_000_000L

    @Test
    fun dismissedHighDoesNotDelayLow() {
        // Repeat 120 min; High dismissed at 12:00, Low at 13:00.
        assertFalse(dismissThrottles(Kind.LOW, Kind.HIGH, noon, noon + 60 * minute, 120 * minute))
        assertFalse(dismissThrottles(Kind.LOSS, Kind.HIGH, noon, noon + 60 * minute, 120 * minute))
    }

    @Test
    fun sameKindIsThrottledOnlyWithinTheRepeatWindow() {
        assertTrue(dismissThrottles(Kind.HIGH, Kind.HIGH, noon, noon + 60 * minute, 120 * minute))
        assertFalse(dismissThrottles(Kind.HIGH, Kind.HIGH, noon, noon + 120 * minute, 120 * minute))
    }

    @Test
    fun nothingIsThrottledWithoutADismissOrARepeatInterval() {
        assertFalse(dismissThrottles(Kind.LOW, null, 0L, noon, 120 * minute))
        assertFalse(dismissThrottles(Kind.LOW, Kind.LOW, noon, noon + minute, 0L))
    }

    @Test
    fun aHeldBackExcursionRepeatsOneIntervalAfterTheDismissNotAfterTheEarlierRing() {
        // Repeat 15 min. Rings at 12:00, dismissed at 12:14, back in range at 12:15, low again at
        // 12:16: held back by the dismiss, and from then on the repeat counts from the dismiss.
        val repeat = 15 * minute
        val dismissedAt = noon + 14 * minute
        assertTrue(dismissThrottles(Kind.LOW, Kind.LOW, dismissedAt, noon + 16 * minute, repeat))
        // Counted from the ring at 12:00 the repeat would already be due at 12:17 — a throttle of
        // one minute. Counted from the dismiss it holds until 12:29.
        assertTrue(repeatDue(noon, noon + 17 * minute, repeat))
        assertFalse(repeatDue(dismissedAt, noon + 17 * minute, repeat))
        assertFalse(repeatDue(dismissedAt, noon + 28 * minute, repeat))
        assertTrue(repeatDue(dismissedAt, noon + 29 * minute, repeat))
    }

    @Test
    fun aLowOrHighEndsWithASilenceAsLongAsSignalLossTakes() {
        val loss = 16 * minute
        // Readings a minute apart: the same excursion goes on.
        assertFalse(silenceEndsExcursion(AlarmController.Kind.LOW, noon - minute, noon, loss))
        assertFalse(silenceEndsExcursion(AlarmController.Kind.HIGH, noon - 15 * minute, noon, loss))
        // Out of reach for hours: the value may have been in range in between.
        assertTrue(silenceEndsExcursion(AlarmController.Kind.LOW, noon - 3 * 60 * minute, noon, loss))
        assertTrue(silenceEndsExcursion(AlarmController.Kind.HIGH, noon - loss, noon, loss))
        // "Signal lost" ends with any reading anyway, and nothing is nothing.
        assertFalse(silenceEndsExcursion(AlarmController.Kind.LOSS, noon - 3 * 60 * minute, noon, loss))
        assertFalse(silenceEndsExcursion(null, noon - 3 * 60 * minute, noon, loss))
        // The first reading ever in this install.
        assertFalse(silenceEndsExcursion(AlarmController.Kind.LOW, 0L, noon, loss))
    }

    @Test
    fun repeatOffNeverRepeats() {
        assertFalse(repeatDue(noon, noon + 24 * 60 * minute, 0L))
    }

    @Test
    fun onlyAnUnansweredRingIsExpectedBackAfterARestart() {
        assertTrue(ringExpectedOnRestore(dismissed = false, snoozeUntil = 0L, heldBack = false))
        assertFalse(ringExpectedOnRestore(dismissed = true, snoozeUntil = 0L, heldBack = false))
        assertFalse(ringExpectedOnRestore(dismissed = false, snoozeUntil = noon, heldBack = false))
        // Held back by an earlier dismiss, the excursion never rang: nothing to bring back.
        assertFalse(ringExpectedOnRestore(dismissed = false, snoozeUntil = 0L, heldBack = true))
        // Rung for its whole duration: not cut short, so only the repeat rings it again.
        assertFalse(ringExpectedOnRestore(dismissed = false, snoozeUntil = 0L, heldBack = false, timedOut = true))
    }

    @Test
    fun aRestoredRingIsWaitedForOnlyBrieflyAndOnlyWhileTheServiceHasOneSaved() {
        val grace = AlarmController.RING_RESTART_GRACE_MS
        assertEquals(grace, ringRestartWaitMillis(savedRingPending = true, sinceStartMillis = 0L))
        assertEquals(1_000L, ringRestartWaitMillis(savedRingPending = true, sinceStartMillis = grace - 1_000L))
        assertEquals(0L, ringRestartWaitMillis(savedRingPending = true, sinceStartMillis = grace))
        assertEquals(0L, ringRestartWaitMillis(savedRingPending = true, sinceStartMillis = 60 * minute))
        // Nothing saved (reboot long ago, a fallback alarm): the ring is lost, ring at once.
        assertEquals(0L, ringRestartWaitMillis(savedRingPending = false, sinceStartMillis = 0L))
    }

    @Test
    fun aSavedRingStandsOnlyForARingThatWasOnRecently() {
        // Stamped every minute while ringing; a restart after a process death comes within seconds.
        assertTrue(savedRingFresh(noon, noon + 2_000L))
        assertTrue(savedRingFresh(noon, noon + SAVED_RING_MAX_AGE_MS))
        // Left behind by a reboot / app update / force-stop hours ago.
        assertFalse(savedRingFresh(noon, noon + SAVED_RING_MAX_AGE_MS + 1))
        assertFalse(savedRingFresh(noon, noon + 8 * 60 * minute))
        // A copy without a stamp (older build), or one from a clock that was set since.
        assertFalse(savedRingFresh(0L, noon))
        assertTrue(savedRingFresh(noon + 30_000L, noon))
        assertFalse(savedRingFresh(noon + 60 * minute, noon))
    }

    @Test
    fun anEstablishedSensorCountsFromItsLastReading() {
        val sensor = SensorInfo(id = "A", activationMillis = noon - 3 * 24 * 60 * minute)
        val last = noon - 5 * minute
        // Whenever the process started: a restart does not move the deadline of a sensor that
        // has delivered.
        assertEquals(last, lossCountsFrom(last, sensor, sensorSeenMillis = noon - minute, now = noon))
        assertEquals(last - 8 * 60 * minute, lossCountsFrom(last - 8 * 60 * minute, sensor, noon - minute, noon))
        // Also with no sensor card, or a sensor past its wear time.
        assertEquals(last, lossCountsFrom(last, null, noon - minute, noon))
        val expired = SensorInfo(id = "A", activationMillis = noon - 16 * 24 * 60 * minute)
        assertEquals(last, lossCountsFrom(last, expired, noon - minute, noon))
    }

    @Test
    fun aNewSensorCountsFromTheEndOfItsWarmUp() {
        val sensor = SensorInfo(id = "B", activationMillis = noon)
        val warmupEnd = noon + 60 * minute
        // The previous sensor was deactivated (marker dropped), or this is the first sensor ever.
        assertEquals(warmupEnd, lossCountsFrom(0L, sensor, noon, noon + 10 * minute))
        assertEquals(warmupEnd, lossCountsFrom(0L, sensor, noon, noon + 8 * 60 * minute))
        // Replaced without deactivating: the marker is the previous sensor's, from before the
        // activation or from during the warm-up.
        assertEquals(warmupEnd, lossCountsFrom(noon - 20 * minute, sensor, noon, noon + 10 * minute))
        assertEquals(warmupEnd, lossCountsFrom(noon + 30 * minute, sensor, noon, noon + 90 * minute))
        // A process restart during the warm-up changes nothing.
        assertEquals(warmupEnd, lossCountsFrom(0L, sensor, noon + 40 * minute, noon + 45 * minute))
        // Once it has delivered, its readings count.
        assertEquals(noon + 61 * minute, lossCountsFrom(noon + 61 * minute, sensor, noon, noon + 90 * minute))
    }

    @Test
    fun aSensorThatNeverDeliveredGetsItsWindowFromWhenItWasFirstSeen() {
        // Taken over (or scanned again after a deactivation) days into its life: not "lost" the
        // moment it is registered.
        val sensor = SensorInfo(id = "B", activationMillis = noon - 5 * 24 * 60 * minute)
        assertEquals(noon, lossCountsFrom(0L, sensor, sensorSeenMillis = noon, now = noon))
        assertEquals(noon, lossCountsFrom(noon - 14 * 24 * 60 * minute, sensor, noon, noon + 5 * minute))
        // The same after a process start with a sensor that is past its warm-up and still silent.
        val silent = SensorInfo(id = "C", activationMillis = noon - 3 * 60 * minute)
        assertEquals(noon, lossCountsFrom(0L, silent, sensorSeenMillis = noon, now = noon + minute))
    }

    @Test
    fun aReadingOfTheSensorBeforeDoesNotCountAsDelivered() {
        // B was started elsewhere five days ago and is taken over at noon; the sensor before it
        // last delivered half an hour earlier — after B's warm-up, but it was not B's reading.
        val taken = SensorInfo(id = "B", activationMillis = noon - 5 * 24 * 60 * minute)
        val before = noon - 30 * minute
        assertEquals(noon, lossCountsFrom(before, taken, sensorSeenMillis = noon, now = noon, previousSensorsReading = before))
        // Its own first reading is one, and from there the plain rule holds.
        assertEquals(noon + 2 * minute, lossCountsFrom(noon + 2 * minute, taken, noon, noon + 20 * minute, before))
        // No sensor before it in this process: the marker is taken as its own, as it always was.
        assertEquals(before, lossCountsFrom(before, taken, sensorSeenMillis = noon, now = noon))
    }

    @Test
    fun withoutAReadingOrASensorThereIsNothingToWaitFor() {
        assertEquals(0L, lossCountsFrom(0L, null, 0L, noon))
        // A sensor past its wear time has no readings due, nor one whose start is not known.
        val expired = SensorInfo(id = "A", activationMillis = noon - 16 * 24 * 60 * minute)
        assertEquals(0L, lossCountsFrom(0L, expired, noon, noon))
        assertEquals(0L, lossCountsFrom(0L, SensorInfo(id = "A", activationMillis = 0L), noon, noon))
    }

    @Test
    fun aPassedDeadlineIsNotACheckThatHasRun() {
        // Readings stop at 12:00, signal loss after 16 min.
        val deadline = noon + 16 * minute
        assertEquals(deadline, firstLossCheckAt(deadline, checked = false, now = noon + minute))
        assertEquals(deadline, firstLossCheckAt(deadline, checked = false, now = deadline - 1))
        // The phone slept and the inexact one-shot is late; at 12:18 a Dismiss comes in from the
        // watch and arms again: the check is due now, not "done" (0 would cancel it).
        assertEquals(noon + 18 * minute, firstLossCheckAt(deadline, checked = false, now = noon + 18 * minute))
        assertEquals(deadline, firstLossCheckAt(deadline, checked = false, now = deadline))
        // Once it has run it is not handed out again, however often that is asked: no loop of
        // checks that arm the next one for "now".
        for (minutes in listOf(16L, 17L, 18L, 60L, 8 * 60L)) {
            assertEquals(0L, firstLossCheckAt(deadline, checked = true, now = noon + minutes * minute))
        }
        // Before the deadline it makes no difference (a longer delay was set after the check).
        assertEquals(deadline, firstLossCheckAt(deadline, checked = true, now = noon + minute))
    }

    /** [lossRealertAt] for a sensor that is in use and settled after a restart. */
    private fun realertAt(snoozeUntil: Long, lastFired: Long, repeat: Long, now: Long, sensorActive: Boolean = true) =
        lossRealertAt(sensorActive, ringRestartWaitMillis = 0L, snoozeUntil, lastFired, repeat, now)

    @Test
    fun aPendingSignalLossOfASilentSensorIsLookedAtAgainWhenItsRepeatOrSnoozeIsDue() {
        val repeat = 15 * minute
        // Rang at 12:00: the repeat is due at 12:15, whenever that is asked.
        assertEquals(noon + repeat, realertAt(0L, noon, repeat, now = noon + 1_000L))
        assertEquals(noon + repeat, realertAt(0L, noon, repeat, now = noon + 14 * minute))
        // Snoozed until 12:30: then, not at the repeat before it.
        assertEquals(noon + 30 * minute, realertAt(noon + 30 * minute, noon, repeat, now = noon + 20 * minute))
        // Due or overdue (the check is about to ring, or ran late): the minute cadence.
        assertEquals(noon + 16 * minute, realertAt(0L, noon, repeat, now = noon + 15 * minute))
        assertEquals(noon + 31 * minute, realertAt(noon + 30 * minute, noon, repeat, now = noon + 30 * minute))
        // Repeat off and not snoozed: nothing rings again by itself.
        assertEquals(0L, realertAt(0L, noon, 0L, now = noon + minute))
        // With the repeat off a snooze still ends.
        assertEquals(noon + 30 * minute, realertAt(noon + 30 * minute, noon, 0L, now = noon + minute))
    }

    @Test
    fun aRestoredRingIsLookedAtAgainOnceItsRestartIsSettled() {
        val now = noon + 3 * minute
        assertEquals(now + 4_000L, lossRealertAt(true, ringRestartWaitMillis = 4_000L, 0L, noon, 15 * minute, now))
        // Also with the repeat off, where nothing else would look again.
        assertEquals(now + 4_000L, lossRealertAt(true, ringRestartWaitMillis = 4_000L, 0L, noon, 0L, now))
    }

    @Test
    fun aSignalLossOfAnExpiredOrEndedSensorIsNotLookedAtAgain() {
        val repeat = 15 * minute
        // Rang at 12:00 and timed out; the sensor's wear time ended at 12:05. The check decides
        // nothing from then on, so the last ring stays at 12:00 — a minute overdue at every
        // minute, for days, were it armed.
        for (minutes in listOf(5L, 6L, 15L, 16L, 60L, 3 * 24 * 60L)) {
            assertEquals(0L, realertAt(0L, noon, repeat, now = noon + minutes * minute, sensorActive = false))
        }
        // The same for one that was snoozed: the snooze never ends by a check.
        assertEquals(0L, realertAt(noon + 30 * minute, noon, repeat, now = noon + 20 * minute, sensorActive = false))
        assertEquals(0L, realertAt(noon + 30 * minute, noon, repeat, now = noon + 31 * minute, sensorActive = false))
        assertEquals(0L, realertAt(noon + 30 * minute, noon, 0L, now = noon + 24 * 60 * minute, sensorActive = false))
        // Nor is a restored ring waited for: there is nothing it could be rung again for.
        assertEquals(0L, lossRealertAt(false, ringRestartWaitMillis = 4_000L, 0L, noon, repeat, noon + minute))
    }

    @Test
    fun aSignalLossEndsWithTheSensorItWasAboutOnlyWhenAnotherOneIsInUse() {
        // Replaced, with or without "Deactivate" in between.
        assertTrue(lossOfAnotherSensor(lossSensorId = "A", sensorId = "B"))
        // The same sensor: after a restart, or when its card is published again.
        assertFalse(lossOfAnotherSensor("A", "A"))
        // No sensor shown now (not heard from yet after a process start, or one that ended and
        // has no card any more), or none shown when the loss was reported: what was reported
        // stands — also a state saved by a version that did not keep the sensor.
        assertFalse(lossOfAnotherSensor("A", null))
        assertFalse(lossOfAnotherSensor(null, "B"))
        assertFalse(lossOfAnotherSensor(null, null))
    }

    @Test
    fun aRingOfTheExcursionBeforeEndsWhenAHeldBackOneBegins() {
        // "Signal lost" rings, readings return as a High that a dismiss still holds back.
        assertTrue(ringOfEarlierExcursion(Kind.LOSS, ringIsTest = false, kind = Kind.HIGH))
        assertTrue(ringOfEarlierExcursion(Kind.LOW, ringIsTest = false, kind = Kind.HIGH))
        // Not the ring of this kind, a test ring, or one whose kind is not known.
        assertFalse(ringOfEarlierExcursion(Kind.HIGH, ringIsTest = false, kind = Kind.HIGH))
        assertFalse(ringOfEarlierExcursion(Kind.LOSS, ringIsTest = true, kind = Kind.HIGH))
        assertFalse(ringOfEarlierExcursion(null, ringIsTest = false, kind = Kind.HIGH))
    }

    @Test
    fun durationStepsDownToFifteenBeforeUntilDismissed() {
        assertEquals(15, alarmDurationStepDown(20)) // a typed value under 30 s
        assertEquals(15, alarmDurationStepDown(29))
        assertEquals(15, alarmDurationStepDown(30))
        assertEquals(45, alarmDurationStepDown(60))
        assertEquals(0, alarmDurationStepDown(15))
        assertEquals(0, alarmDurationStepDown(0))
    }
}
