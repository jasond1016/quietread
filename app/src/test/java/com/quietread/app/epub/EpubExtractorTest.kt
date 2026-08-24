package com.quietread.app.epub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubExtractorTest {
    private val root = Files.createTempDirectory("quietread-extractor-").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun extractsNormalResources() {
        val epub = zip("OPS/chapter.xhtml" to "正文")
        val output = File(root, "out")

        EpubExtractor.extract(epub, output)

        assertEquals("正文", File(output, "OPS/chapter.xhtml").readText())
    }

    @Test
    fun rejectsPathTraversal() {
        val epub = zip("../outside.txt" to "bad")

        assertThrows(InvalidEpubException::class.java) {
            EpubExtractor.extract(epub, File(root, "out"))
        }
    }

    @Test
    fun rejectsContentLargerThanAvailableBudget() {
        val epub = zip("OPS/chapter.xhtml" to "正文内容")

        val error = assertThrows(InvalidEpubException::class.java) {
            EpubExtractor.extract(epub, File(root, "out"), byteBudget = 4)
        }

        assertEquals("设备存储空间不足，无法解压 EPUB", error.message)
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File(root, "book-${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
