package com.quietread.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingLocatorTest {
    @Test
    fun createsSanitizedLocatorFromWebViewValues() {
        val locator = ReadingLocator.create(" paragraph-1 ", "2/4/1", 37)

        assertEquals("paragraph-1", locator?.elementId)
        assertEquals("2/4/1", locator?.elementPath)
        assertEquals(37, locator?.textOffset)
    }

    @Test
    fun acceptsBodyPathAndClampsOffset() {
        val locator = ReadingLocator.create("", ".", -5)

        assertNull(locator?.elementId)
        assertEquals(".", locator?.elementPath)
        assertEquals(0, locator?.textOffset)
    }

    @Test
    fun rejectsMalformedOrOversizedPaths() {
        assertNull(ReadingLocator.create(null, "1/a/3", 0))
        assertNull(ReadingLocator.create(null, "1/../3", 0))
        assertNull(ReadingLocator.create(null, "1".repeat(1_025), 0))
    }
}
