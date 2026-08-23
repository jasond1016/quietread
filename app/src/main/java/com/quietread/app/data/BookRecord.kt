package com.quietread.app.data

data class BookRecord(
    val id: String,
    val sha256: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val epubPath: String,
    val contentRoot: String,
    val packagePath: String,
    val lastSpineIndex: Int,
    val lastSpineProgress: Float,
    val overallProgress: Float,
    val importedAt: Long,
    val lastOpenedAt: Long,
)

data class ReadingPosition(
    val spineIndex: Int,
    val spineProgress: Float,
    val overallProgress: Float,
)
