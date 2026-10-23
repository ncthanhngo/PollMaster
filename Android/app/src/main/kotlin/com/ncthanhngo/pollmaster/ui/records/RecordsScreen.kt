package com.ncthanhngo.pollmaster.ui.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.data.RecordItem
import com.ncthanhngo.pollmaster.core.storage.StoredRecord
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.MessageDialog
import com.ncthanhngo.pollmaster.ui.editor.ExportSheet
import kotlinx.coroutines.launch

private enum class StatusFilter { ALL, DRAFT, COMPLETED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(onOpenRecord: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val items by container.records.items.collectAsState()
    val broken by container.records.broken.collectAsState()
    var brokenInfo by remember { mutableStateOf<StoredRecord.Broken?>(null) }
    var brokenDelete by remember { mutableStateOf<StoredRecord.Broken?>(null) }
    val templates by container.templates.templates.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(StatusFilter.ALL) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RecordItem?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(templates) { container.records.refresh() }

    val visible = items.filter { item ->
        (query.isBlank() || item.title.contains(query, ignoreCase = true)) &&
            when (filter) {
                StatusFilter.ALL -> true
                StatusFilter.DRAFT -> !item.record.isCompleted
                StatusFilter.COMPLETED -> item.record.isCompleted
            }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_records)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { picking = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.record_new))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.records_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, null) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == StatusFilter.ALL, { filter = StatusFilter.ALL }, { Text(stringResource(R.string.filter_all)) })
                FilterChip(filter == StatusFilter.DRAFT, { filter = StatusFilter.DRAFT }, { Text(stringResource(R.string.status_draft)) })
                FilterChip(filter == StatusFilter.COMPLETED, { filter = StatusFilter.COMPLETED }, { Text(stringResource(R.string.status_completed)) })
            }
            val visibleBroken = if (filter == StatusFilter.ALL) broken.filter { query.isBlank() || it.id.contains(query, ignoreCase = true) } else emptyList()
            if (visible.isEmpty() && visibleBroken.isEmpty()) {
                Text(
                    stringResource(R.string.records_empty),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleBroken, key = { "broken-" + it.id }) { b ->
                    BrokenCard(b, onOpen = { brokenInfo = b }, onDelete = { brokenDelete = b })
                }
                items(visible, key = { it.record.id }) { item ->
                    RecordCard(
                        item = item,
                        onOpen = { onOpenRecord(item.record.id) },
                        onDuplicate = {
                            scope.launch {
                                container.records.duplicate(item)
                                container.records.refresh()
                            }
                        },
                        onExport = { exporting = item.record.id },
                        onDelete = { deleting = item },
                    )
                }
            }
        }
    }

    if (picking) {
        TemplatePickerDialog(
            templates = templates,
            onDismiss = { picking = false },
            onPick = { t ->
                picking = false
                scope.launch { onOpenRecord(container.records.create(t).id) }
            },
        )
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_title),
            message = stringResource(R.string.delete_record_message, item.title),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch {
                    container.records.delete(item.record.id)
                    container.records.refresh()
                }
            },
            onDismiss = { deleting = null },
        )
    }
    brokenInfo?.let { b ->
        MessageDialog(title = b.id, message = b.error, onDismiss = { brokenInfo = null })
    }
    brokenDelete?.let { b ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_title),
            message = stringResource(R.string.delete_record_message, b.id),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch {
                    container.records.delete(b.id)
                    container.records.refresh()
                }
            },
            onDismiss = { brokenDelete = null },
        )
    }
    exporting?.let { id -> ExportSheet(recordId = id, onDismiss = { exporting = null }) }
}

@Composable
private fun RecordCard(item: RecordItem, onOpen: () -> Unit, onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        item.template?.title ?: stringResource(R.string.record_template_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = onOpen,
                    label = {
                        Text(stringResource(if (item.record.status == Record.STATUS_COMPLETED) R.string.status_completed else R.string.status_draft))
                    },
                )
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate)) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { menu = false; onExport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(
                stringResource(R.string.updated_at, displayDateTime(item.record.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val p = item.progress
            if (p.total > 0) {
                LinearProgressIndicator(progress = { p.filled.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.progress_required, p.filled, p.total), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun BrokenCard(b: StoredRecord.Broken, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.id, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(b.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            AssistChip(
                onClick = onOpen,
                label = { Text(stringResource(R.string.record_read_error), color = MaterialTheme.colorScheme.error) },
            )
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
