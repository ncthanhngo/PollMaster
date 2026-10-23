package com.ncthanhngo.pollmaster.ui.templates

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.platform.GuideExport
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.IconBadge

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
    val accent = MaterialTheme.colorScheme.primary
    AppCard {
        Column(
            Modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = 0.14f), Color.Transparent))).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(Icons.Filled.AutoAwesome, size = 44.dp)
                Column {
                    Text(stringResource(R.string.ai_card_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.ai_card_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Step(1, stringResource(R.string.ai_card_step1))
            Step(2, stringResource(R.string.ai_card_step2))
            OutlinedButton(onClick = { attempt({ GuideExport.share(context, context.getString(R.string.ai_share_guide)) }) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                Text(stringResource(R.string.ai_share_guide), Modifier.padding(start = 8.dp))
            }
            Button(onClick = onPaste, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp))
                Text(stringResource(R.string.paste_json), Modifier.padding(start = 8.dp))
            }
            if (GuideExport.canSaveToDownloads) {
                TextButton(onClick = { attempt({ GuideExport.saveToDownloads(context) }, R.string.ai_guide_saved) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ai_save_guide))
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
