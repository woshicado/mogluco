package de.mogluco.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadoutNotificationTest {

    private val minute = 60_000L
    private val stale = 10 * minute
    private val noon = 1_700_000_000_000L

    @Test
    fun theStaleBoundaryIsWaitedForUntilJustPastIt() {
        assertEquals(stale + 1_000L, millisUntilStale(noon, noon, stale))
        assertEquals(4 * minute + 1_000L, millisUntilStale(noon, noon + 6 * minute, stale))
        // Exactly at the threshold the reading is not stale yet (isStale is "older than").
        assertEquals(1_000L, millisUntilStale(noon, noon + stale, stale))
    }

    @Test
    fun aStaleReadingHasNothingLeftToWaitFor() {
        assertEquals(0L, millisUntilStale(noon, noon + stale + 1, stale))
        assertEquals(0L, millisUntilStale(noon, noon + 8 * 60 * minute, stale))
    }

    @Test
    fun aReadingStampedAheadOfTheClockCountsAsNew() {
        assertEquals(stale + 1_000L, millisUntilStale(noon + minute, noon, stale))
    }

    @Test
    fun offTheLockScreenMeansSecretNotMerelyRedacted() {
        // Notification.VISIBILITY_PUBLIC = 1, VISIBILITY_SECRET = -1 (PRIVATE, 0, still shows the
        // small icon — the rendered value).
        assertEquals(1, readoutVisibility(showOnLockscreen = true))
        assertEquals(-1, readoutVisibility(showOnLockscreen = false))
    }

    @Test
    fun theTitleLosesItsTrendGlyphOnceStale() {
        assertEquals("112 mg/dL \u2192", readoutTitle("112", "mg/dL", "\u2192", stale = false))
        assertEquals("112 mg/dL", readoutTitle("112", "mg/dL", "\u2192", stale = true))
    }
}
