package com.ncthanhngo.pollmaster.ui.projects

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.ncthanhngo.pollmaster.core.format.displayDayMonthTime
import com.ncthanhngo.pollmaster.core.project.ProjectStats
import com.ncthanhngo.pollmaster.platform.ShareHelper
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.BackButton
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.MessageDialog
import kotlinx.coroutines.launch

/** Project detail: stats, its templates, its sites and the actions (share, summary, records, archive, delete). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(projectId: String, onBack: () -> Unit, onEdit: () -> Unit, onShowRecords: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by container.projects.projects.collectAsState()
    val items by container.records.items.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    var confirmDelete by remember { mutableStateOf(false) }
    var hasRecords by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { container.records.refresh() }

    if (project == null) {
        Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) }) { Text(stringResource(R.string.template_not_found), Modifier.padding(it).padding(24.dp)) }
        return
    }
    val mine = items.filter { it.projectId == projectId }
    val stats = ProjectStats.of(projectId, mine.map { it.record }, container.projects.chungId())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project.name, maxLines = 1) },
                navigationIcon = { BackButton(onBack) },
                actions = { IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectDot(project.color, 14.dp)
                    Text(listOfNotNull(project.code, project.client).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                }
                project.description?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            item { StatsCard(stats, mine.maxByOrNull { it.record.updatedAt }?.title) }
            item { ProjectTemplatesSection(project) }
            item { ProjectSitesSection(project, mine) }
            item {
                AppCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.project_actions), style = MaterialTheme.typography.titleSmall)
                        ActionButton(R.string.project_open_records, onShowRecords)
                        ActionButton(R.string.project_share) {
                            scope.launch {
                                try {
                                    ShareHelper.share(context, listOf(container.bundles.export(projectId)), context.getString(R.string.bundle_share_title))
                                } catch (e: Exception) { // shown to the user
                                    Toast.makeText(context, context.getString(R.string.export_error, e.message.orEmpty()), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        ActionButton(R.string.project_summary) { summary = true }
                        HorizontalDivider()
                        ActionButton(if (project.archived) R.string.project_unarchive else R.string.project_archive) {
                            scope.launch { container.projects.setArchived(projectId, !project.archived) }
                        }
                        TextButton(onClick = { hasRecords = stats.total > 0; confirmDelete = true }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.project_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (summary) SummaryExportSheet(projectId, onDismiss = { summary = false })
    if (confirmDelete) {
        if (hasRecords) {
            MessageDialog(stringResource(R.string.project_delete), stringResource(R.string.project_delete_has_records, stats.total)) { confirmDelete = false }
        } else {
            ConfirmDialog(
                title = stringResource(R.string.project_delete),
                message = stringResource(R.string.project_delete_confirm, project.name),
                confirmLabel = stringResource(R.string.action_delete),
                onConfirm = { scope.launch { if (container.projects.delete(projectId)) onBack() } },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

@Composable
private fun ActionButton(label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
}

@Composable
private fun StatsCard(stats: ProjectStats, latestTitle: String?) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.project_stats), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(stats.draft, R.string.project_stat_draft)
                Stat(stats.completed, R.string.project_stat_completed)
                Stat(stats.returned, R.string.project_stat_returned)
            }
            if (latestTitle != null && stats.lastActivityAt != null) {
                Text(
                    stringResource(R.string.project_stat_latest, "$latestTitle · ${displayDayMonthTime(stats.lastActivityAt)}"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
