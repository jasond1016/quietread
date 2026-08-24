package com.quietread.app.epub

import java.io.File

data class SpineItem(
    val id: String,
    val file: File,
    val readingWeight: Long,
)

data class TocEntry(
    val title: String,
    val spineIndex: Int,
    val fragment: String?,
    val depth: Int,
)

data class EpubPackage(
    val title: String,
    val author: String?,
    val coverFile: File?,
    val contentRoot: File,
    val packageRelativePath: String,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
)

class InvalidEpubException(message: String, cause: Throwable? = null) : Exception(message, cause)
