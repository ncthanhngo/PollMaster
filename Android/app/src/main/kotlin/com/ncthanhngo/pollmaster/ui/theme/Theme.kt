package com.ncthanhngo.pollmaster.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1B6B5A),
    secondary = Color(0xFF4A6360),
    tertiary = Color(0xFF8A5100),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF6FD6BE),
    secondary = Color(0xFFB1CCC7),
    tertiary = Color(0xFFFFB86B),
)

@Composable
fun PollMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
