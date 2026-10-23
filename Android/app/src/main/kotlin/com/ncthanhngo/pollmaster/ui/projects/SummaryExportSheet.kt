package com.ncthanhngo.pollmaster.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.platform.ShareHelper
import kotlinx.coroutines.launch

/** "Xuất tổng hợp": all records or only completed ones, optionally with the JSON + media zip of every record. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryExportSheet(projectId: String, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var completedOnly by remember { mutableStateOf(false) }
    var withZip by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(completedOnly) { count = container.projectExport.count(projectId, completedOnly) }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.titleLarge)
            CheckRow(completedOnly, !busy, R.string.summary_only_completed) { completedOnly = it }
            CheckRow(withZip, !busy, R.string.summary_with_zip) { withZip = it }
            Text(
                stringResource(if (count > 0) R.string.summary_count else R.string.summary_none, count),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(stringResource(R.string.export_error, it), color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    enabled = count > 0 && !busy,
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val files = container.projectExport.export(projectId, completedOnly, withZip)
                                ShareHelper.share(context, files, context.getString(R.string.share_title))
                                onDismiss()
                            } catch (e: Exception) { // shown in the sheet
                                error = e.message ?: e.javaClass.simpleName
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.action_export)) }
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(stringResource(R.string.exporting))
                }
            }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, label: Int, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Text(stringResource(label))
    }
}
