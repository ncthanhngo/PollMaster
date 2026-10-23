package com.ncthanhngo.pollmaster.ui.editor

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.data.ExportFormat
import com.ncthanhngo.pollmaster.platform.ShareHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(recordId: String, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(container.settings.exportFormats) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)
            ExportFormat.entries.forEach { format ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = format.key in selected,
                        enabled = !busy,
                        onCheckedChange = { on -> selected = if (on) selected + format.key else selected - format.key },
                    )
                    Text(stringResource(formatLabel(format)))
                }
            }
            error?.let { Text(stringResource(R.string.export_error, it), color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    enabled = selected.isNotEmpty() && !busy,
                    onClick = {
                        busy = true
                        error = null
                        container.settings.exportFormats = selected
                        scope.launch {
                            try {
                                val formats = ExportFormat.entries.filter { it.key in selected }.toSet()
                                val files = container.export.export(recordId, formats)
                                ShareHelper.share(context, files, context.getString(R.string.share_title))
                                onDismiss()
                            } catch (e: Exception) {
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

private fun formatLabel(f: ExportFormat): Int = when (f) {
    ExportFormat.ZIP -> R.string.fmt_zip
    ExportFormat.XLSX -> R.string.fmt_xlsx
    ExportFormat.CSV -> R.string.fmt_csv
    ExportFormat.PDF -> R.string.fmt_pdf
    ExportFormat.DOCX -> R.string.fmt_docx
}
