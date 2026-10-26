package com.ncthanhngo.pollmaster.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs

/**
 * Flat white card on the gray background: the contrast is the separation. An outline only for state ([borderColor]:
 * notes, errors) or in high contrast (2 px); optional tint for notes.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hc = LocalUiPrefs.current.highContrast
    val width = if (hc) 2.dp else 1.dp
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = (borderColor ?: MaterialTheme.colorScheme.outline.takeIf { hc })?.let { BorderStroke(width, it) },
        content = content,
    )
}
