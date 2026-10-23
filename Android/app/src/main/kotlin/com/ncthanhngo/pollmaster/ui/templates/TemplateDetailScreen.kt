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
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.ui.projects.ProjectChoiceDialog
import com.ncthanhngo.pollmaster.data.DeleteResult
import androidx.compose.runtime.collectAsState
import com.ncthanhngo.pollmaster.core.storage.newestVersions
import com.ncthanhngo.pollmaster.ui.common.BackButton
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.MessageDialog
import com.ncthanhngo.pollmaster.ui.records.rememberRecordCreator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateDetailScreen(id: String, version: String, onBack: () -> Unit, onRecordCreated: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val template = remember(id, version) { container.templates.find(id, version) }
    var confirmDelete by remember { mutableStateOf(false) }
    val all by container.templates.templates.collectAsState()
    val outdated = template != null && newestVersions(all).none { it.id == template.id && it.version == template.version } && all.any { it.id == template.id }
    var inUse by remember { mutableStateOf<Int?>(null) }
    val startRecord = rememberRecordCreator(onRecordCreated)
    val projects by container.projects.projects.collectAsState()
    val currentId by container.projects.currentId.collectAsState()
    var usedBy by remember { mutableStateOf<String?>(null) }
    var noProject by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<List<Project>?>(null) }

    /** New record goes to the current project when it lists this template, else to the only (or a chosen) project that does. */
    fun createRecord(t: Template) {
        val candidates = projects.filter { p -> !p.archived && p.templates.any { it.id == t.id } }
        val current = candidates.firstOrNull { it.id == currentId }
        when {
            current != null -> startRecord(t, current.id)
            candidates.size == 1 -> startRecord(t, candidates.first().id)
            candidates.isEmpty() -> noProject = true
            else -> choosing = candidates
        }
    }

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
                    onClick = { createRecord(template) },
                    enabled = !outdated,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.template_create_record)) }
                if (outdated) Text(stringResource(R.string.template_outdated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        is DeleteResult.UsedByProjects -> usedBy = r.codes.joinToString()
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
    usedBy?.let { codes ->
        MessageDialog(stringResource(R.string.template_delete), stringResource(R.string.template_used_by_projects, codes)) { usedBy = null }
    }
    if (noProject) MessageDialog(stringResource(R.string.template_create_record), stringResource(R.string.template_create_no_project)) { noProject = false }
    choosing?.let { list ->
        ProjectChoiceDialog(
            title = stringResource(R.string.pick_project), projects = list, multi = false,
            onDismiss = { choosing = null },
            onConfirm = { choosing = null; if (template != null) startRecord(template, it.first().id) },
        )
    }
    inUse?.let { count ->
        MessageDialog(stringResource(R.string.template_delete), stringResource(R.string.template_in_use, count)) { inUse = null }
    }
}
