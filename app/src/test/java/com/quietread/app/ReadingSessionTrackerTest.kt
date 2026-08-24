package com.quietread.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingSessionTrackerTest {
    @Test
    fun capsTimeAtIdleTimeout() {
        var now = 1_000L
        val tracker = ReadingSessionTracker(now = { now }, idleTimeoutMs = 90_000L)
        tracker.start("book")
        now += 120_000L

        assertEquals(90_000L, tracker.accrue()?.elapsedMs)
    }

    @Test
    fun interactionExtendsActiveWindow() {
        var now = 0L
        val tracker = ReadingSessionTracker(now = { now }, idleTimeoutMs = 90_000L)
        tracker.start("book")
        now = 60_000L
        tracker.interact()
        now = 120_000L

        assertEquals(120_000L, tracker.accrue()?.elapsedMs)
        tracker.pause()
        assertNull(tracker.accrue())
    }
}
