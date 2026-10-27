package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.ncthanhngo.pollmaster.platform.isOnline
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.ErpMarker
import com.ncthanhngo.pollmaster.ui.theme.okColor
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
    var record by remember { mutableStateOf(container.records.load(recordId)) }
    var sending by remember { mutableStateOf(false) }
    var sendError by rememberSaveable { mutableStateOf<String?>(null) }
    var sentNow by rememberSaveable { mutableStateOf(false) }
    var confirmDraft by remember { mutableStateOf(false) }
    val account by container.erp.account.collectAsState()
    val canSend = account != null && isOnline(context)

    fun send() {
        sending = true
        sendError = null
        sentNow = false
        scope.launch {
            try {
                container.erpUpload.upload(recordId)
                record = container.records.load(recordId)
                sentNow = true
            } catch (e: Exception) {
                sendError = e.message ?: e.javaClass.simpleName
            } finally {
                sending = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!busy && !sending) onDismiss() }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)
            ExportFormat.entries.forEach { format ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = format.key in selected,
                        enabled = !busy && !sending,
                        onCheckedChange = { on -> selected = if (on) selected + format.key else selected - format.key },
                    )
                    Text(stringResource(formatLabel(format)))
                }
            }
            error?.let { Text(stringResource(R.string.export_error, it), color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    enabled = selected.isNotEmpty() && !busy && !sending,
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
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            record?.let { ErpMarker(it) }
            if (sentNow) Text(stringResource(R.string.erp_sent), color = okColor())
            sendError?.let { Text(stringResource(R.string.erp_send_error, it), color = MaterialTheme.colorScheme.error) }
            if (!canSend) Text(stringResource(R.string.erp_need_online), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedButton(
                    enabled = canSend && !busy && !sending && record != null,
                    onClick = { if (record?.isCompleted == true) send() else confirmDraft = true },
                ) { Text(stringResource(R.string.erp_send)) }
                if (sending) {
                    CircularProgressIndicator(Modifier.padding(4.dp))
                    Text(stringResource(R.string.erp_sending))
                }
            }
        }
    }
    if (confirmDraft) {
        ConfirmDialog(
            title = stringResource(R.string.erp_send_draft_title),
            message = stringResource(R.string.erp_send_draft_message),
            confirmLabel = stringResource(R.string.erp_send),
            onConfirm = ::send,
            onDismiss = { confirmDraft = false },
        )
    }
}


private fun formatLabel(f: ExportFormat): Int = when (f) {
    ExportFormat.ZIP -> R.string.fmt_zip
    ExportFormat.XLSX -> R.string.fmt_xlsx
    ExportFormat.CSV -> R.string.fmt_csv
    ExportFormat.PDF -> R.string.fmt_pdf
    ExportFormat.DOCX -> R.string.fmt_docx
}
