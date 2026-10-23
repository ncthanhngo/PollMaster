package com.ncthanhngo.pollmaster.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.data.DeleteResult
import com.ncthanhngo.pollmaster.ui.common.BackButton
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.MessageDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDetailScreen(id: String, version: String, onBack: () -> Unit, onRecordCreated: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val template = remember(id, version) { container.templates.find(id, version) }
    var confirmDelete by remember { mutableStateOf(false) }
    var inUse by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(template?.title ?: id) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (template != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.template_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (template == null) {
            Text(stringResource(R.string.template_not_found), Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("${template.id} · v${template.version}", style = MaterialTheme.typography.labelLarge)
                template.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            item {
                Button(
                    onClick = { scope.launch { onRecordCreated(container.records.create(template).id) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.template_create_record)) }
            }
            template.instructions?.let { text ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.template_instructions), style = MaterialTheme.typography.titleSmall)
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.template_sections), style = MaterialTheme.typography.titleSmall) }
            items(template.sections, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.section_item_count, s.items.count { it is Field }),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete && template != null) {
        ConfirmDialog(
            title = stringResource(R.string.template_delete),
            message = stringResource(R.string.template_delete_message, template.title),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch {
                    when (val r = container.templates.delete(template)) {
                        DeleteResult.Deleted -> onBack()
                        is DeleteResult.InUse -> inUse = r.count
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    inUse?.let { count ->
        MessageDialog(stringResource(R.string.template_delete), stringResource(R.string.template_in_use, count)) { inUse = null }
    }
}
