package com.quietread.app.data

import com.quietread.app.epub.EpubPackage
import com.quietread.app.epub.SpineItem
import com.quietread.app.epub.TocEntry
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class AnnotationDocumentsTest {
    @Test
    fun markdownIncludesChapterQuoteAndThought() {
        val root = createTempDirectory("quietread-export-").toFile()
        try {
            val chapter = root.resolve("chapter.xhtml").apply { writeText("<p>正文</p>") }
            val book = BookRecord(
                id = "book",
                sha256 = "hash",
                title = "静读",
                author = "作者",
                coverPath = null,
                epubPath = root.resolve("book.epub").path,
                contentRoot = root.path,
                packagePath = "content.opf",
                lastSpineIndex = 0,
                lastSpineProgress = 0f,
                lastLocator = null,
                overallProgress = 0f,
                totalReadingMs = 0L,
                importedAt = 0L,
                lastOpenedAt = 0L,
            )
            val epub = EpubPackage(
                "静读",
                "作者",
                null,
                root,
                "content.opf",
                listOf(SpineItem("chapter", chapter, 2)),
                listOf(TocEntry("第一章", 0, null, 0)),
            )
            val locator = ReadingLocator(null, "0", 0)
            val annotation = BookAnnotation(
                id = "annotation",
                bookId = book.id,
                type = AnnotationType.THOUGHT,
                spineIndex = 0,
                startLocator = locator,
                endLocator = locator.copy(textOffset = 2),
                selectedText = "安静阅读",
                note = "值得重读",
                createdAt = 0L,
                updatedAt = 0L,
            )

            val markdown = AnnotationDocuments.markdown(book, epub, listOf(annotation))

            assertTrue(markdown.contains("# 《静读》标记"))
            assertTrue(markdown.contains("## 想法 · 第一章"))
            assertTrue(markdown.contains("> 安静阅读"))
            assertTrue(markdown.contains("值得重读"))
        } finally {
            root.deleteRecursively()
        }
    }
}
