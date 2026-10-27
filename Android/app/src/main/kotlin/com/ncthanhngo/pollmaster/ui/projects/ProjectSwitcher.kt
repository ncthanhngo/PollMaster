package com.ncthanhngo.pollmaster.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStats
import com.ncthanhngo.pollmaster.ui.common.IconBadge

/** Sheet to pick the current project of the records tab: search, "Tất cả dự án", the ERP projects by kind, import package. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSwitcherSheet(
    projects: List<Project>,
    currentId: String?,
    statsOf: (Project) -> ProjectStats,
    onSelect: (String?) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = projects.filter { query.isBlank() || it.name.contains(query, true) || it.code.contains(query, true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.project_switch), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.project_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, null) } },
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item(key = "all") {
                    SwitcherRow(stringResource(R.string.project_all), null, null, currentId == null, null) { onSelect(null) }
                }
                for ((kind, title) in projectKindSections) {
                    val group = shown.filter { it.kind == kind }
                    if (group.isEmpty()) continue
                    item(key = "h-$kind") {
                        Text(stringResource(title), Modifier.padding(top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(group, key = { it.id }) { p ->
                        SwitcherRow(p.name, p.code, p, currentId == p.id, projectCounts(statsOf(p))) { onSelect(p.id) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Download, null)
                    Text(stringResource(R.string.project_import), Modifier.padding(start = 4.dp), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SwitcherRow(name: String, code: String?, project: Project?, selected: Boolean, counts: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (project != null) ProjectAvatar(project, 36.dp) else IconBadge(Icons.Filled.Layers, tint = Color.Gray, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            code?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            counts?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}
