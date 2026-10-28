package com.ncthanhngo.pollmaster.ui.templates

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.platform.GuideExport
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs

/** Brand green, same in light and dark (white text stays >= 4.5:1); plain black in high contrast. */
private val BrandStart = Color(0xFF21806B)
private val BrandEnd = Color(0xFF145245)

/** Dark brand green for text on white: fixed (not the dark-mode primary), so it stays readable in both modes. */
private val BrandInk = Color(0xFF145C4D)

@Composable
private fun brandBrush(): Brush =
    if (LocalUiPrefs.current.highContrast) Brush.linearGradient(listOf(Color.Black, Color.Black)) else Brush.linearGradient(listOf(BrandStart, BrandEnd))

/** One-line brand strip at the top of the Templates tab: the AI flow is the first thing offered, at the height of a row. */
@Composable
fun AiGuideStrip(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(brandBrush())
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White)
        Text(stringResource(R.string.ai_card_title), Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(stringResource(R.string.ai_start), color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White)
    }
}

/** The AI flow opened from the strip or the + menu: the two-step card in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiGuideSheet(onPaste: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp).navigationBarsPadding()) {
            AiGuideCard(onPaste = { onDismiss(); onPaste() })
        }
    }
}

/** The AI authoring flow as two steps, each with its own action, on a solid brand block (flat colour blocking). */
@Composable
fun AiGuideCard(onPaste: () -> Unit) {
    val context = LocalContext.current
    fun attempt(block: () -> Unit, okMessage: Int? = null) {
        try {
            block()
            okMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.ai_guide_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(brandBrush()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White)
            }
            Column {
                Text(stringResource(R.string.ai_card_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text(stringResource(R.string.ai_card_subtitle), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }
        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f))) {
            Step(1, stringResource(R.string.ai_card_step1), stringResource(R.string.ai_card_step1_detail), stringResource(R.string.ai_send), Icons.Filled.Share) {
                attempt({ GuideExport.share(context, context.getString(R.string.ai_share_guide)) })
            }
            HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.22f))
            Step(2, stringResource(R.string.ai_card_step2), stringResource(R.string.ai_card_step2_detail), stringResource(R.string.ai_paste), Icons.Filled.ContentPaste, onPaste)
        }
        if (GuideExport.canSaveToDownloads) {
            TextButton(onClick = { attempt({ GuideExport.saveToDownloads(context) }, R.string.ai_guide_saved) }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_save_guide), color = Color.White)
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, detail: String, action: String, icon: ImageVector, onClick: () -> Unit) {
    val ink = if (LocalUiPrefs.current.highContrast) Color.Black else BrandInk
    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = ink)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        }
        // same-size white pill for both steps, so the two actions line up (a minimum: large text widens it, never cuts it)
        Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Color.White, contentColor = ink, modifier = Modifier.sizeIn(minWidth = 88.dp, minHeight = 48.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(action, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
    }
}
