package com.quietread.app.epub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EpubResourceResolverTest {
    private val parent = Files.createTempDirectory("quietread-resources-").toFile()
    private val contentRoot = File(parent, "content").apply { mkdirs() }

    @After
    fun cleanUp() {
        parent.deleteRecursively()
    }

    @Test
    fun resolvesOnlyFilesInsideCurrentBook() {
        val chapter = File(contentRoot, "OPS/images/cover.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertEquals(chapter.canonicalFile, EpubResourceResolver.resolve(contentRoot, "OPS/images/cover.jpg"))
        assertNull(EpubResourceResolver.resolve(contentRoot, "OPS/images"))
    }

    @Test
    fun rejectsTraversalOutsideCurrentBook() {
        File(parent, "secret.txt").writeText("secret")

        assertNull(EpubResourceResolver.resolve(contentRoot, "../secret.txt"))
        assertNull(EpubResourceResolver.resolve(contentRoot, ""))
    }
}
