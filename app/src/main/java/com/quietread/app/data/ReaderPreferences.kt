package com.quietread.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ReaderTheme { LIGHT, DARK }

data class ReaderSettings(
    val fontScale: Float = 1f,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
)

class ReaderPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())

    val settings: StateFlow<ReaderSettings> = mutableSettings

    fun setFontScale(scale: Float) {
        val safeScale = scale.coerceIn(0.8f, 1.5f)
        preferences.edit().putFloat(KEY_FONT_SCALE, safeScale).apply()
        mutableSettings.value = mutableSettings.value.copy(fontScale = safeScale)
    }

    fun setTheme(theme: ReaderTheme) {
        preferences.edit().putString(KEY_THEME, theme.name).apply()
        mutableSettings.value = mutableSettings.value.copy(theme = theme)
    }

    private fun read(): ReaderSettings {
        val theme = runCatching {
            ReaderTheme.valueOf(preferences.getString(KEY_THEME, ReaderTheme.LIGHT.name).orEmpty())
        }.getOrDefault(ReaderTheme.LIGHT)
        return ReaderSettings(
            fontScale = preferences.getFloat(KEY_FONT_SCALE, 1f).coerceIn(0.8f, 1.5f),
            theme = theme,
        )
    }

    private companion object {
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_THEME = "theme"
    }
}
