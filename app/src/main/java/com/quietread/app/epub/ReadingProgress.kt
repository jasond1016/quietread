package com.quietread.app.epub

data class ProgressTarget(
    val spineIndex: Int,
    val spineProgress: Float,
)

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
}
