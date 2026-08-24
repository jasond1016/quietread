package com.quietread.app.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class BookSearchTest {
    @Test
    fun returnsStableLocatorForEveryMatch() {
        val root = createTempDirectory("quietread-search-").toFile()
        try {
            val chapter = File(root, "chapter.xhtml").apply {
                writeText("<html><body><section><p>安静阅读，也安静思考。</p></section></body></html>")
            }
            val epub = EpubPackage(
                title = "test",
                author = null,
                coverFile = null,
                contentRoot = root,
                packageRelativePath = "content.opf",
                spine = listOf(SpineItem("chapter", chapter, 12)),
                toc = emptyList(),
            )

            val results = BookSearch.search(epub, "安静")

            assertEquals(2, results.size)
            assertEquals("0/0", results[0].locator.elementPath)
            assertEquals(0, results[0].locator.textOffset)
            assertEquals(6, results[1].locator.textOffset)
            assertTrue(results[0].excerpt.contains("安静阅读"))
        } finally {
            root.deleteRecursively()
        }
    }
}
