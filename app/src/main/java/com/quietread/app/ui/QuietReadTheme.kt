package com.quietread.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5D50),
    onPrimary = Color.White,
    background = Color(0xFFF5F1E8),
    onBackground = Color(0xFF252722),
    surface = Color(0xFFFFFBF3),
    onSurface = Color(0xFF252722),
    surfaceVariant = Color(0xFFE8E1D4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ACDBB),
    background = Color(0xFF171916),
    onBackground = Color(0xFFE4E5DE),
    surface = Color(0xFF20231F),
    onSurface = Color(0xFFE4E5DE),
    surfaceVariant = Color(0xFF343832),
)

@Composable
fun QuietReadTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
