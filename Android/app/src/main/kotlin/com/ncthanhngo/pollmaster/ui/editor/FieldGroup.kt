package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs

/** True inside a [FieldGroupCard]: field views draw as rows, not as their own cards. */
val LocalInFieldGroup = compositionLocalOf { false }

/**
 * One block for consecutive fields, rows separated by hairlines (grouped form, like iOS Settings) instead of a card per
 * field: the section reads as a few groups, not a wall of cards.
 */
@Composable
fun <T> FieldGroupCard(items: List<T>, modifier: Modifier = Modifier, row: @Composable (T) -> Unit) {
    val hc = LocalUiPrefs.current.highContrast
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .then(if (hc) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), shape) else Modifier),
    ) {
        CompositionLocalProvider(LocalInFieldGroup provides true) {
            items.forEachIndexed { i, item ->
                if (i > 0) {
                    HorizontalDivider(
                        Modifier.padding(start = 16.dp), thickness = if (hc) 1.dp else 0.5.dp,
                        color = if (hc) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                }
                row(item)
            }
        }
    }
}

/** Field chrome: a row inside a [FieldGroupCard], or its own card elsewhere. [stroke] marks an error or a checker note. */
@Composable
fun FieldContainer(modifier: Modifier = Modifier, stroke: Color? = null, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val spacing = if (compact) 4.dp else 8.dp
    if (LocalInFieldGroup.current) {
        Column(
            modifier.fillMaxWidth()
                .background(stroke?.copy(alpha = 0.07f) ?: Color.Transparent)
                .drawBehind { if (stroke != null) drawRect(stroke, size = Size(3.dp.toPx(), size.height)) }
                .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
            content = content,
        )
    } else {
        AppCard(modifier, borderColor = stroke) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}
