package com.ncthanhngo.pollmaster.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Font scale at which secondary actions stop growing (iOS: Dynamic Type xxLarge). */
private const val FIELD_ACTION_MAX_FONT_SCALE = 1.3f

/**
 * Secondary actions inside a field card (Chụp, Đọc chữ từ ảnh, Nạp CSV…): their text stops growing at
 * [FIELD_ACTION_MAX_FONT_SCALE], so with one-line labels they never wrap or outweigh the field they serve.
 */
@Composable
fun FieldActionScale(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(FIELD_ACTION_MAX_FONT_SCALE)), content = content)
}
