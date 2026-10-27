package com.ncthanhngo.pollmaster.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** "#RRGGBB" as a colour (template badges are always valid hex: see Template.badgeColor). */
fun hexColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

/** The template's short name on its colour (record cards, template edit preview). */
@Composable
fun TemplateBadge(text: String, hex: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(50)) {
    Text(
        text, maxLines = 1, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.background(hexColor(hex), shape).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
