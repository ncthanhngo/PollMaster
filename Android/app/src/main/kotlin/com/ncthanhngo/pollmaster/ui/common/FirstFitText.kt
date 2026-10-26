package com.ncthanhngo.pollmaster.ui.common

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * One line: the first of [variants] (longest first) that fits, so a row drops its less useful parts instead of
 * cutting a word in half; the last one is ellipsized if even it does not fit.
 */
@Composable
fun FirstFitText(
    variants: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    var index by remember(variants) { mutableIntStateOf(0) }
    Text(
        variants[index], modifier, color = color, style = style, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (it.hasVisualOverflow && index < variants.lastIndex) index++ },
    )
}
