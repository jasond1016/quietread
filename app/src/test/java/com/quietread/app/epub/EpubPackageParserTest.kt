package com.quietread.app.epub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EpubPackageParserTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanUp() {
        roots.forEach(File::deleteRecursively)
    }

    @Test
    fun parsesReflowableEpub3MetadataSpineAndNavigation() {
        val root = createBook(layout = "reflowable")

        val epub = EpubPackageParser.parse(root)

        assertEquals("安静的一章", epub.title)
        assertEquals("静阅作者", epub.author)
        assertNotNull(epub.coverFile)
        assertEquals(1, epub.spine.size)
        assertEquals("第一章", epub.toc.single().title)
        assertEquals(0, epub.toc.single().spineIndex)
        assertEquals("start", epub.toc.single().fragment)
    }

    @Test
    fun rejectsFixedLayoutBook() {
        val root = createBook(layout = "pre-paginated")

        val error = assertThrows(InvalidEpubException::class.java) {
            EpubPackageParser.parse(root)
        }

        assertEquals("暂不支持固定版式 EPUB", error.message)
    }

    private fun createBook(layout: String): File {
        val root = Files.createTempDirectory("quietread-parser-").toFile().also(roots::add)
        File(root, "META-INF").mkdirs()
        File(root, "OPS").mkdirs()
        File(root, "META-INF/container.xml").writeText(
            """
            <?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>
            """.trimIndent(),
        )
        File(root, "OPS/package.opf").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">quietread-test</dc:identifier>
                <dc:title>安静的一章</dc:title>
                <dc:creator>静阅作者</dc:creator>
                <meta property="rendition:layout">$layout</meta>
              </metadata>
              <manifest>
                <item id="cover" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="chapter"/></spine>
            </package>
            """.trimIndent(),
        )
        File(root, "OPS/nav.xhtml").writeText(
            """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body><nav epub:type="toc"><ol><li><a href="chapter.xhtml#start">第一章</a></li></ol></nav></body>
            </html>
            """.trimIndent(),
        )
        File(root, "OPS/chapter.xhtml").writeText("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1 id=\"start\">第一章</h1></body></html>")
        File(root, "OPS/cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
        return root
    }
}
