package com.ncthanhngo.pollmaster.ui.templates

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.platform.GuideExport

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
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_card_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ai_card_text), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { attempt({ GuideExport.share(context, context.getString(R.string.ai_share_guide)) }) }) {
                    Text(stringResource(R.string.ai_share_guide))
                }
                OutlinedButton(onClick = onPaste) { Text(stringResource(R.string.paste_json)) }
            }
            if (GuideExport.canSaveToDownloads) {
                TextButton(onClick = { attempt({ GuideExport.saveToDownloads(context) }, R.string.ai_guide_saved) }) {
                    Text(stringResource(R.string.ai_save_guide))
                }
            }
        }
    }
}
