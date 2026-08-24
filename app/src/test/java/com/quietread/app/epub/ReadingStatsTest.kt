package com.quietread.app.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingStatsTest {
    @Test
    fun usesDefaultRateBeforeEnoughReadingHistory() {
        assertEquals(60_000L, ReadingStats.estimatedRemainingMs(600, 0.5f, 60_000L))
    }

    @Test
    fun learnsRateFromReadingHistory() {
        assertEquals(10 * 60_000L, ReadingStats.estimatedRemainingMs(6_000, 0.5f, 10 * 60_000L))
    }

    @Test
    fun completedBookHasNoRemainingEstimate() {
        assertNull(ReadingStats.estimatedRemainingMs(1_000, 1f, 10 * 60_000L))
    }

    @Test
    fun formatsShortAndLongDurations() {
        assertEquals("不足 1 分钟", ReadingStats.formatDuration(10_000L))
        assertEquals("1 小时 5 分", ReadingStats.formatDuration(65 * 60_000L))
    }
}
