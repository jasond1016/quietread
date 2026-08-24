package com.quietread.app.epub

import kotlin.math.roundToInt

data class ProgressTarget(
    val spineIndex: Int,
    val spineProgress: Float,
)

data class EstimatedPage(val current: Int, val total: Int)

object ReadingProgress {
    fun overall(weights: List<Long>, spineIndex: Int, spineProgress: Float): Float {
        if (weights.isEmpty()) return 0f
        val safeIndex = spineIndex.coerceIn(weights.indices)
        val safeWeights = weights.map { it.coerceAtLeast(1L) }
        val total = safeWeights.sum().coerceAtLeast(1L).toDouble()
        val before = safeWeights.take(safeIndex).sum().toDouble()
        val current = safeWeights[safeIndex].toDouble()
        return ((before + current * spineProgress.coerceIn(0f, 1f)) / total).toFloat().coerceIn(0f, 1f)
    }

    fun target(weights: List<Long>, overallProgress: Float): ProgressTarget {
        if (weights.isEmpty()) return ProgressTarget(0, 0f)
        val safeWeights = weights.map { it.coerceAtLeast(1L) }
        val target = overallProgress.coerceIn(0f, 0.999999f) * safeWeights.sum().coerceAtLeast(1L)
        var consumed = 0L
        safeWeights.forEachIndexed { index, weight ->
            if (target < consumed + weight || index == safeWeights.lastIndex) {
                return ProgressTarget(
                    spineIndex = index,
                    spineProgress = ((target - consumed) / weight.toFloat()).coerceIn(0f, 1f),
                )
            }
            consumed += weight
        }
        return ProgressTarget(safeWeights.lastIndex, 1f)
    }

    fun estimatedBookPage(
        weights: List<Long>,
        spineIndex: Int,
        chapterPage: Int,
        chapterPageCount: Int,
    ): EstimatedPage {
        if (weights.isEmpty()) return EstimatedPage(1, 1)
        val safeWeights = weights.map { it.coerceAtLeast(1L) }
        val safeIndex = spineIndex.coerceIn(safeWeights.indices)
        val safeChapterPages = chapterPageCount.coerceAtLeast(1)
        val safePage = chapterPage.coerceIn(0, safeChapterPages - 1)
        val weightPerPage = (safeWeights[safeIndex].toDouble() / safeChapterPages)
            .coerceIn(MIN_WEIGHT_PER_PAGE, MAX_WEIGHT_PER_PAGE)
        val beforePages = (safeWeights.take(safeIndex).sum() / weightPerPage).roundToInt()
        val afterPages = (safeWeights.drop(safeIndex + 1).sum() / weightPerPage).roundToInt()
        val total = (beforePages + safeChapterPages + afterPages).coerceAtLeast(1)
        return EstimatedPage(
            current = (beforePages + safePage + 1).coerceIn(1, total),
            total = total,
        )
    }

    private const val MIN_WEIGHT_PER_PAGE = 120.0
    private const val MAX_WEIGHT_PER_PAGE = 3_000.0
}
