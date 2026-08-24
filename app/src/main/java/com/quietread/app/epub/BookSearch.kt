package com.quietread.app.epub

import com.quietread.app.data.ReadingLocator
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

data class BookSearchResult(
    val spineIndex: Int,
    val locator: ReadingLocator,
    val excerpt: String,
)

object BookSearch {
    private const val MAX_RESULTS = 200
    private const val CONTEXT_CHARS = 28

    fun search(epub: EpubPackage, rawQuery: String): List<BookSearchResult> {
        val query = rawQuery.trim()
        if (query.isEmpty()) return emptyList()
        val normalizedQuery = query.lowercase()
        return buildList {
            epub.spine.forEachIndexed { spineIndex, item ->
                if (size >= MAX_RESULTS) return@buildList
                val document = runCatching { Jsoup.parse(item.file, null, "") }.getOrNull() ?: return@forEachIndexed
                document.select("script, iframe, frame, object, embed, form, input, button, textarea, audio, video")
                    .remove()
                val body = document.body()
                val blocks = body.select("p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,figcaption")
                    .ifEmpty { listOf(body) }
                blocks.forEach { block ->
                    if (size >= MAX_RESULTS) return@forEach
                    val text = rawText(block)
                    val normalizedText = text.lowercase()
                    var from = 0
                    while (size < MAX_RESULTS) {
                        val match = normalizedText.indexOf(normalizedQuery, from)
                        if (match < 0) break
                        ReadingLocator.create(block.id(), elementPath(body, block), match)?.let { locator ->
                            add(BookSearchResult(spineIndex, locator, excerpt(text, match, query.length)))
                        }
                        from = match + query.length.coerceAtLeast(1)
                    }
                }
            }
        }
    }

    private fun rawText(element: Element): String = buildString {
        element.traverse { node, _ -> if (node is TextNode) append(node.wholeText) }
    }

    private fun elementPath(root: Element, target: Element): String? {
        if (target == root) return "."
        val parts = ArrayDeque<Int>()
        var current: Element? = target
        while (current != null && current != root) {
            val parent = current.parent() ?: return null
            val index = parent.children().indexOf(current)
            if (index < 0) return null
            parts.addFirst(index)
            current = parent
        }
        return if (current == root) parts.joinToString("/") else null
    }

    private fun excerpt(text: String, match: Int, queryLength: Int): String {
        val start = (match - CONTEXT_CHARS).coerceAtLeast(0)
        val end = (match + queryLength + CONTEXT_CHARS).coerceAtMost(text.length)
        val content = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return buildString {
            if (start > 0) append('…')
            append(content)
            if (end < text.length) append('…')
        }
    }
}
