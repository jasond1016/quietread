package com.quietread.app.data

import com.quietread.app.epub.EpubPackage
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object AnnotationDocuments {
    fun markdown(book: BookRecord, epub: EpubPackage, annotations: List<BookAnnotation>): String = buildString {
        appendLine("# 《${book.title}》标记")
        book.author?.let { appendLine("\n作者：$it") }
        appendLine("\n共 ${annotations.size} 条标记。")
        annotations.sortedWith(annotationPositionComparator).forEach { annotation ->
            val kind = when (annotation.type) {
                AnnotationType.BOOKMARK -> "书签"
                AnnotationType.HIGHLIGHT -> "划线"
                AnnotationType.THOUGHT -> "想法"
            }
            val chapter = epub.toc.lastOrNull { it.spineIndex <= annotation.spineIndex }?.title
                ?: "第 ${annotation.spineIndex + 1} 章"
            appendLine("\n## $kind · $chapter")
            annotation.selectedText?.trim()?.takeIf(String::isNotEmpty)?.lines()?.forEach {
                appendLine("> ${it.ifEmpty { " " }}")
            }
            annotation.note?.trim()?.takeIf(String::isNotEmpty)?.let { appendLine("\n$it") }
            appendLine("\n_${formatTime(annotation.createdAt)}_")
        }
    }

    fun backup(book: BookRecord, annotations: List<BookAnnotation>): String = JSONObject().apply {
        put("version", 1)
        put("bookSha256", book.sha256)
        put("bookTitle", book.title)
        put("exportedAt", System.currentTimeMillis())
        put("annotations", JSONArray().apply { annotations.forEach { put(it.toJson()) } })
    }.toString(2)

    fun restore(raw: String, targetBookId: String): List<BookAnnotation> {
        val root = JSONObject(raw)
        require(root.optInt("version") == 1) { "不支持的备份版本" }
        val values = root.optJSONArray("annotations") ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                val item = values.optJSONObject(index) ?: continue
                parseAnnotation(item, targetBookId)?.let(::add)
            }
        }
    }

    fun backupBookHash(raw: String): String {
        val root = JSONObject(raw)
        require(root.optInt("version") == 1) { "不支持的备份版本" }
        return root.optString("bookSha256").takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("备份缺少书籍标识")
    }

    private fun BookAnnotation.toJson() = JSONObject().apply {
        put("type", type.name)
        put("spineIndex", spineIndex)
        put("start", startLocator.toJson())
        endLocator?.let { put("end", it.toJson()) }
        selectedText?.let { put("selectedText", it) }
        note?.let { put("note", it) }
        color?.let { put("color", it) }
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    private fun ReadingLocator.toJson() = JSONObject().apply {
        elementId?.let { put("elementId", it) }
        put("elementPath", elementPath)
        put("textOffset", textOffset)
    }

    private fun parseAnnotation(value: JSONObject, bookId: String): BookAnnotation? {
        val type = runCatching { AnnotationType.valueOf(value.optString("type")) }.getOrNull() ?: return null
        val start = value.optJSONObject("start")?.toLocator() ?: return null
        val end = value.optJSONObject("end")?.toLocator()
        if (type != AnnotationType.BOOKMARK && end == null) return null
        val now = System.currentTimeMillis()
        return BookAnnotation(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            type = type,
            spineIndex = value.optInt("spineIndex", -1).takeIf { it >= 0 } ?: return null,
            startLocator = start,
            endLocator = end,
            selectedText = value.optionalString("selectedText")?.take(20_000),
            note = value.optionalString("note")?.take(20_000),
            color = value.takeIf { it.has("color") }?.optInt("color"),
            createdAt = value.optLong("createdAt", now).coerceAtLeast(0L),
            updatedAt = value.optLong("updatedAt", now).coerceAtLeast(0L),
        )
    }

    private fun JSONObject.toLocator(): ReadingLocator? = ReadingLocator.create(
        elementId = optionalString("elementId"),
        elementPath = optionalString("elementPath"),
        textOffset = optInt("textOffset", 0),
    )

    private fun JSONObject.optionalString(key: String): String? =
        takeIf { has(key) && !isNull(key) }?.optString(key)?.takeIf(String::isNotEmpty)

    private fun formatTime(epochMs: Long): String = runCatching {
        TIME_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }.getOrDefault("")

    private val annotationPositionComparator = compareBy<BookAnnotation> { it.spineIndex }
        .thenBy { pathOrderKey(it.startLocator.elementPath) }
        .thenBy { it.startLocator.textOffset }
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun pathOrderKey(path: String): String = if (path == ".") "" else {
        path.split('/').joinToString("/") { it.toIntOrNull()?.toString()?.padStart(8, '0') ?: it }
    }
}
