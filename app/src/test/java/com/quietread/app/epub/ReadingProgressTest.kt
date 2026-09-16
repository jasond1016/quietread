package com.quietread.app.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingProgressTest {
    @Test
    fun overallProgressUsesReadableContentWeights() {
        assertEquals(0.25f, ReadingProgress.overall(listOf(100, 300), 0, 1f), 0.0001f)
        assertEquals(0.625f, ReadingProgress.overall(listOf(100, 300), 1, 0.5f), 0.0001f)
        assertEquals(1f, ReadingProgress.overall(listOf(100, 300), 1, 1f), 0.0001f)
    }

    @Test
    fun overallTargetMapsBackToChapterAndLocalPosition() {
        val first = ReadingProgress.target(listOf(100, 300), 0.125f)
        val second = ReadingProgress.target(listOf(100, 300), 0.625f)

        assertEquals(0, first.spineIndex)
        assertEquals(0.5f, first.spineProgress, 0.0001f)
        assertEquals(1, second.spineIndex)
        assertEquals(0.5f, second.spineProgress, 0.0001f)
    }
}
