package com.ncthanhngo.pollmaster.ui.editor

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.format.displayDayMonthTime
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.data.RecordItem
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog

private sealed interface Dialog {
    data object None : Dialog
    data object Kdd : Dialog
    data object PickRecord : Dialog
    data class ConfirmCopy(val source: Record, val conflicts: Int) : Dialog
}

/** Section "⋯" menu: mark all empty fields KĐĐ, and copy this section from another record of the same template. */
@Composable
fun SectionMenu(sectionId: String, controller: EditorController) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dialog>(Dialog.None) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.section_menu_kdd)) }, onClick = { open = false; dialog = Dialog.Kdd })
            DropdownMenuItem(text = { Text(stringResource(R.string.section_menu_copy)) }, onClick = { open = false; dialog = Dialog.PickRecord })
        }
    }
    when (val d = dialog) {
        Dialog.None -> Unit
        Dialog.Kdd -> KddDialog(controller.emptyFlaggableCount(sectionId), onDismiss = { dialog = Dialog.None }) { reason ->
            val n = controller.markEmptyNotMeasurable(sectionId, reason)
            Toast.makeText(context, context.getString(R.string.section_kdd_done, n), Toast.LENGTH_SHORT).show()
        }
        Dialog.PickRecord -> RecordPicker(controller, onDismiss = { dialog = Dialog.None }) { source ->
            val conflicts = controller.copyConflicts(source, sectionId)
            if (conflicts == 0) {
                controller.copySectionFrom(source, sectionId)
                dialog = Dialog.None
            } else {
                dialog = Dialog.ConfirmCopy(source, conflicts)
            }
        }
        is Dialog.ConfirmCopy -> ConfirmDialog(
            title = stringResource(R.string.section_copy_title),
            message = stringResource(R.string.section_copy_overwrite, d.conflicts),
            confirmLabel = stringResource(R.string.action_overwrite),
            onConfirm = { controller.copySectionFrom(d.source, sectionId) },
            onDismiss = { dialog = Dialog.None },
        )
    }
}

@Composable
private fun KddDialog(count: Int, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.section_menu_kdd)) },
        text = {
            Column {
                Text(stringResource(if (count == 0) R.string.section_kdd_none else R.string.section_kdd_confirm, count))
                if (count > 0) OutlinedTextField(
                    value = reason, onValueChange = { reason = it }, label = { Text(stringResource(R.string.field_reason)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            if (count > 0) TextButton(enabled = reason.isNotBlank(), onClick = { onApply(reason); onDismiss() }) { Text(stringResource(R.string.section_kdd_apply)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { if (count > 0) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RecordPicker(controller: EditorController, onDismiss: () -> Unit, onPick: (Record) -> Unit) {
    val container = LocalContainer.current
    val items = remember {
        container.records.items.value.filter {
            it.record.id != controller.record.value.id && it.record.templateId == controller.template.id &&
                it.record.templateVersion == controller.template.version
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.section_menu_copy)) },
        text = {
            if (items.isEmpty()) Text(stringResource(R.string.section_copy_none)) else LazyColumn {
                items(items, key = { it.record.id }) { item -> PickRow(item) { container.records.load(item.record.id)?.let(onPick) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PickRow(item: RecordItem, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(displayDayMonthTime(item.record.updatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
