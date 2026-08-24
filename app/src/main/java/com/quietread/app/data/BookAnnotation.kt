package com.quietread.app.data

enum class AnnotationType { BOOKMARK, HIGHLIGHT, THOUGHT }

data class BookAnnotation(
    val id: String,
    val bookId: String,
    val type: AnnotationType,
    val spineIndex: Int,
    val startLocator: ReadingLocator,
    val endLocator: ReadingLocator? = null,
    val selectedText: String? = null,
    val note: String? = null,
    val color: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
