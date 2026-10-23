package com.ncthanhngo.pollmaster.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Display preferences from Settings that cards and inputs react to. */
data class UiPrefs(val compact: Boolean = true, val highContrast: Boolean = false)

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

/** Soft problems (warnings) use amber, distinct from the error red. */
val WarnLight = Color(0xFF8A5A00)
val WarnDark = Color(0xFFFFC857)
val OkLight = Color(0xFF1B6B3A)
val OkDark = Color(0xFF7FD99B)

// Secondary text (onSurfaceVariant) is >= 4.5:1 on the white card (light) and on the dark card (dark).
private val Light = lightColorScheme(
    primary = Color(0xFF1B6B5A),
    secondary = Color(0xFF3F5753),
    tertiary = Color(0xFF8A5100),
    background = Color(0xFFE9EEEC),
    surface = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF3F6F5),
    surfaceVariant = Color(0xFFEEF2F1),
    onSurfaceVariant = Color(0xFF45524E),
    outline = Color(0xFF6B7773),
    outlineVariant = Color(0xFFB8C3BF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF6FD6BE),
    secondary = Color(0xFFB1CCC7),
    tertiary = Color(0xFFFFB86B),
    background = Color(0xFF0E1211),
    surface = Color(0xFF1C2321),
    surfaceContainerLowest = Color(0xFF1C2321),
    surfaceContainerLow = Color(0xFF1C2321),
    surfaceContainer = Color(0xFF242C2A),
    surfaceVariant = Color(0xFF2B3432),
    onSurfaceVariant = Color(0xFFC4D0CC),
    outline = Color(0xFF8E9B97),
    outlineVariant = Color(0xFF4A5753),
)

private fun highContrast(base: ColorScheme, dark: Boolean): ColorScheme {
    val bg = if (dark) Color.Black else Color.White
    val fg = if (dark) Color.White else Color.Black
    return base.copy(
        background = bg, surface = bg, surfaceContainerLowest = bg, surfaceContainerLow = bg,
        surfaceContainer = if (dark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0),
        surfaceVariant = if (dark) Color(0xFF1A1A1A) else Color(0xFFEDEDED),
        onBackground = fg, onSurface = fg, onSurfaceVariant = fg,
        outline = fg, outlineVariant = fg,
        primary = if (dark) Color(0xFF9CF5E0) else Color(0xFF004D3E),
        onPrimary = if (dark) Color.Black else Color.White,
        error = if (dark) Color(0xFFFF8A80) else Color(0xFF9B0000),
    )
}

@Composable
fun PollMasterTheme(prefs: UiPrefs = UiPrefs(), content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = (if (dark) Dark else Light).let { if (prefs.highContrast) highContrast(it, dark) else it }
    val density = LocalDensity.current
    // High contrast also enlarges text by 10 %.
    val scaled = if (prefs.highContrast) Density(density.density, density.fontScale * 1.1f) else density
    CompositionLocalProvider(LocalUiPrefs provides prefs, LocalDensity provides scaled) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun warnColor(): Color = if (LocalUiPrefs.current.highContrast) MaterialTheme.colorScheme.onSurface
else if (isSystemInDarkTheme()) WarnDark else WarnLight

@Composable
fun okColor(): Color = if (LocalUiPrefs.current.highContrast) MaterialTheme.colorScheme.onSurface
else if (isSystemInDarkTheme()) OkDark else OkLight
