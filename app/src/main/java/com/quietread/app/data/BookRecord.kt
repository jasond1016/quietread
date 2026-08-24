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
    val lastLocator: ReadingLocator?,
    val overallProgress: Float,
    val totalReadingMs: Long,
    val importedAt: Long,
    val lastOpenedAt: Long,
)

data class ReadingPosition(
    val spineIndex: Int,
    val spineProgress: Float,
    val locator: ReadingLocator?,
    val overallProgress: Float,
)

data class ReadingLocator(
    val elementId: String?,
    val elementPath: String,
    val textOffset: Int,
) {
    companion object {
        private val validPath = Regex("(?:\\.|\\d+(?:/\\d+)*)")

        fun create(elementId: String?, elementPath: String?, textOffset: Int): ReadingLocator? {
            val path = elementPath?.trim()?.takeIf { it.length <= 1_024 && validPath.matches(it) }
                ?: return null
            return ReadingLocator(
                elementId = elementId?.trim()?.takeIf(String::isNotEmpty)?.take(256),
                elementPath = path,
                textOffset = textOffset.coerceIn(0, 10_000_000),
            )
        }
    }
}
