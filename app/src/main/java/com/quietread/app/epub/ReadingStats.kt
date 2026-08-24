package com.quietread.app.epub

import kotlin.math.roundToLong

object ReadingStats {
    private const val DEFAULT_WEIGHT_PER_MINUTE = 300.0
    private const val MIN_SAMPLE_MS = 5 * 60_000L
    private const val MIN_WEIGHT_PER_MINUTE = 80.0
    private const val MAX_WEIGHT_PER_MINUTE = 1_200.0

    fun estimatedRemainingMs(totalWeight: Long, progress: Float, totalReadingMs: Long): Long? {
        if (totalWeight <= 0L || progress >= 0.999999f) return null
        val safeProgress = progress.coerceIn(0f, 1f)
        val observedWeight = totalWeight * safeProgress
        val rate = if (totalReadingMs >= MIN_SAMPLE_MS && observedWeight > 0.0) {
            (observedWeight / (totalReadingMs / 60_000.0))
                .coerceIn(MIN_WEIGHT_PER_MINUTE, MAX_WEIGHT_PER_MINUTE)
        } else {
            DEFAULT_WEIGHT_PER_MINUTE
        }
        val remainingWeight = totalWeight * (1.0 - safeProgress)
        return (remainingWeight / rate * 60_000.0).roundToLong().coerceAtLeast(0L)
    }

    fun formatDuration(durationMs: Long): String {
        val totalMinutes = (durationMs.coerceAtLeast(0L) / 60_000.0).roundToLong()
        if (totalMinutes < 1L) return "不足 1 分钟"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours == 0L -> "${minutes} 分钟"
            minutes == 0L -> "${hours} 小时"
            else -> "${hours} 小时 ${minutes} 分"
        }
    }
}
