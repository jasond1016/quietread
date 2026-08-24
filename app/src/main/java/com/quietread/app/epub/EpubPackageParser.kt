package com.quietread.app.epub

import org.jsoup.Jsoup
import org.w3c.dom.Element
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object EpubPackageParser {
    fun findPackagePath(contentRoot: File): String {
        val container = File(contentRoot, "META-INF/container.xml")
        if (!container.isFile) throw InvalidEpubException("不是有效的 EPUB：缺少 container.xml")
        val rootFile = XmlDocuments.parse(container).firstDescendant("rootfile")
            ?: throw InvalidEpubException("不是有效的 EPUB：未找到内容包")
        return rootFile.getAttribute("full-path").trim().ifEmpty {
            throw InvalidEpubException("不是有效的 EPUB：内容包路径为空")
        }
    }

    fun parse(contentRoot: File, packageRelativePath: String = findPackagePath(contentRoot)): EpubPackage {
        val packageFile = resolveWithin(contentRoot, contentRoot, packageRelativePath)
        if (!packageFile.isFile) throw InvalidEpubException("EPUB 内容包不存在")
        val packageBase = packageFile.parentFile ?: contentRoot
        val document = XmlDocuments.parse(packageFile)
        val metadata = document.firstDescendant("metadata")
            ?: throw InvalidEpubException("EPUB 缺少元数据")

        val layout = metadata.descendants("meta").firstOrNull { meta ->
            meta.getAttribute("property") == "rendition:layout" ||
                meta.getAttribute("name").equals("rendition:layout", ignoreCase = true)
        }?.let { it.getAttribute("content").ifBlank { it.textContent } }?.trim()
        if (layout.equals("pre-paginated", ignoreCase = true)) {
            throw InvalidEpubException("暂不支持固定版式 EPUB")
        }

        rejectProtectedContent(contentRoot)

        val manifestElement = document.firstDescendant("manifest")
            ?: throw InvalidEpubException("EPUB 缺少资源清单")
        val manifest = manifestElement.childElements()
            .filter { it.localTag() == "item" }
            .mapNotNull { item ->
                val id = item.getAttribute("id")
                val href = item.getAttribute("href")
                if (id.isBlank() || href.isBlank()) null else id to ManifestItem(
                    id = id,
                    href = href,
                    mediaType = item.getAttribute("media-type"),
                    properties = item.getAttribute("properties").split(Regex("\\s+")).filter(String::isNotBlank).toSet(),
                )
            }.toMap()

        val spineElement = document.firstDescendant("spine")
            ?: throw InvalidEpubException("EPUB 缺少阅读顺序")
        val spine = spineElement.childElements()
            .filter { it.localTag() == "itemref" && !it.getAttribute("linear").equals("no", true) }
            .mapNotNull { itemRef -> manifest[itemRef.getAttribute("idref")] }
            .filter { it.mediaType.contains("html", ignoreCase = true) }
            .map { item ->
                val file = resolveWithin(contentRoot, packageBase, item.href)
                SpineItem(item.id, file, estimateReadingWeight(file))
            }
            .filter { it.file.isFile }
        if (spine.isEmpty()) throw InvalidEpubException("EPUB 没有可阅读的正文")

        val title = metadata.descendants("title").firstOrNull()?.textContent?.trim().orEmpty()
        val author = metadata.descendants("creator").firstOrNull()?.textContent?.trim()?.takeIf(String::isNotEmpty)
        val coverId = manifest.values.firstOrNull { "cover-image" in it.properties }?.id
            ?: metadata.descendants("meta").firstOrNull { it.getAttribute("name").equals("cover", true) }
                ?.getAttribute("content")
        val coverFile = coverId?.let(manifest::get)?.let { resolveWithin(contentRoot, packageBase, it.href) }
            ?.takeIf(File::isFile)

        val spinePaths = spine.mapIndexed { index, item -> item.file.canonicalFile to index }.toMap()
        val navItem = manifest.values.firstOrNull { "nav" in it.properties }
        val toc = when {
            navItem != null -> parseNav(
                navFile = resolveWithin(contentRoot, packageBase, navItem.href),
                contentRoot = contentRoot,
                spinePaths = spinePaths,
            )
            else -> {
                val ncxId = spineElement.getAttribute("toc")
                val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                ncx?.let {
                    parseNcx(resolveWithin(contentRoot, packageBase, it.href), contentRoot, spinePaths)
                }.orEmpty()
            }
        }

        return EpubPackage(
            title = title,
            author = author,
            coverFile = coverFile,
            contentRoot = contentRoot.canonicalFile,
            packageRelativePath = packageFile.relativeTo(contentRoot.canonicalFile).invariantSeparatorsPath,
            spine = spine,
            toc = toc,
        )
    }

    private fun parseNav(
        navFile: File,
        contentRoot: File,
        spinePaths: Map<File, Int>,
    ): List<TocEntry> {
        if (!navFile.isFile) return emptyList()
        val document = XmlDocuments.parse(navFile)
        val nav = document.descendants("nav").firstOrNull { element ->
            element.getAttribute("epub:type").contains("toc") ||
                element.getAttributeNS("http://www.idpf.org/2007/ops", "type").contains("toc")
        } ?: document.firstDescendant("nav") ?: return emptyList()
        val list = nav.childElements().firstOrNull { it.localTag() == "ol" } ?: nav.firstDescendant("ol") ?: return emptyList()
        return buildList { parseNavList(list, navFile.parentFile ?: contentRoot, contentRoot, spinePaths, 0, this) }
    }

    private fun parseNavList(
        list: Element,
        base: File,
        contentRoot: File,
        spinePaths: Map<File, Int>,
        depth: Int,
        output: MutableList<TocEntry>,
    ) {
        list.childElements().filter { it.localTag() == "li" }.forEach { item ->
            val link = item.childElements().firstOrNull { it.localTag() == "a" }
            if (link != null) addTocEntry(link.textContent, link.getAttribute("href"), base, contentRoot, spinePaths, depth, output)
            item.childElements().firstOrNull { it.localTag() == "ol" }?.let {
                parseNavList(it, base, contentRoot, spinePaths, depth + 1, output)
            }
        }
    }

    private fun parseNcx(
        ncxFile: File,
        contentRoot: File,
        spinePaths: Map<File, Int>,
    ): List<TocEntry> {
        if (!ncxFile.isFile) return emptyList()
        val navMap = XmlDocuments.parse(ncxFile).firstDescendant("navmap") ?: return emptyList()
        return buildList {
            fun visit(parent: Element, depth: Int) {
                parent.childElements().filter { it.localTag() == "navpoint" }.forEach { point ->
                    val title = point.firstDescendant("text")?.textContent.orEmpty()
                    val href = point.firstDescendant("content")?.getAttribute("src").orEmpty()
                    addTocEntry(title, href, ncxFile.parentFile ?: contentRoot, contentRoot, spinePaths, depth, this)
                    visit(point, depth + 1)
                }
            }
            visit(navMap, 0)
        }
    }

    private fun addTocEntry(
        rawTitle: String,
        href: String,
        base: File,
        contentRoot: File,
        spinePaths: Map<File, Int>,
        depth: Int,
        output: MutableList<TocEntry>,
    ) {
        if (href.isBlank()) return
        val file = runCatching { resolveWithin(contentRoot, base, href) }.getOrNull() ?: return
        val spineIndex = spinePaths[file.canonicalFile] ?: return
        val fragment = href.substringAfter('#', "").takeIf(String::isNotEmpty)?.let(::decodeComponent)
        output += TocEntry(rawTitle.trim().ifEmpty { "未命名章节" }, spineIndex, fragment, depth)
    }

    private fun rejectProtectedContent(contentRoot: File) {
        val encryption = File(contentRoot, "META-INF/encryption.xml")
        if (!encryption.isFile) return
        val allowedFontAlgorithms = setOf(
            "http://www.idpf.org/2008/embedding",
            "http://ns.adobe.com/pdf/enc#RC",
        )
        val algorithms = XmlDocuments.parse(encryption).descendants("encryptionmethod")
            .map { it.getAttribute("Algorithm").ifBlank { it.getAttribute("algorithm") } }
            .filter(String::isNotBlank)
        if (algorithms.any { it !in allowedFontAlgorithms }) {
            throw InvalidEpubException("暂不支持加密或受 DRM 保护的 EPUB")
        }
    }

    private fun resolveWithin(root: File, base: File, href: String): File {
        val pathPart = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URI(pathPart).path }.getOrNull()?.takeIf(String::isNotBlank)
            ?: decodeComponent(pathPart)
        val rootFile = root.canonicalFile
        val result = File(base, decoded).canonicalFile
        if (result != rootFile && !result.path.startsWith(rootFile.path + File.separator)) {
            throw InvalidEpubException("EPUB 包含非法路径")
        }
        return result
    }

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun estimateReadingWeight(file: File): Long {
        if (!file.isFile) return 1L
        return runCatching {
            val document = Jsoup.parse(file, null, "")
            val text = document.text()
            val characters = text.codePointCount(0, text.length).toLong()
            val illustrations = document.select("img,svg").size * IMAGE_READING_WEIGHT
            (characters + illustrations).coerceAtLeast(1L)
        }.getOrDefault(file.length().coerceAtLeast(1L))
    }

    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: Set<String>,
    )

    private const val IMAGE_READING_WEIGHT = 400L
}
