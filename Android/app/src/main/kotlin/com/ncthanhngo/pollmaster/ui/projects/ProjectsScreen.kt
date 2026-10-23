package com.ncthanhngo.pollmaster.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDayMonthTime
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStats
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.ThemeToggleButton

/** Tab "Dự án": active projects first, archived ones folded at the end (ux-spec M). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onOpenProject: (String) -> Unit, onNewProject: () -> Unit) {
    val container = LocalContainer.current
    val projects by container.projects.projects.collectAsState()
    val items by container.records.items.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val pickBundle = rememberBundlePicker()
    LaunchedEffect(Unit) { container.records.refresh() }

    val records = items.map { it.record }
    val chung = container.projects.chungId()
    val active = projects.filter { !it.archived }
    val archived = projects.filter { it.archived }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_projects)) }, actions = { ThemeToggleButton() }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { menu = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.project_new))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_new)) }, onClick = { menu = false; onNewProject() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.project_import)) }, onClick = { menu = false; pickBundle() })
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (projects.isEmpty()) item(key = "empty") { Text(stringResource(R.string.projects_empty), Modifier.padding(8.dp)) }
            items(active, key = { it.id }) { p -> ProjectCard(p, ProjectStats.of(p.id, records, chung)) { onOpenProject(p.id) } }
            if (archived.isNotEmpty()) {
                item(key = "archived") {
                    Row(Modifier.fillMaxWidth().clickable { showArchived = !showArchived }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.projects_archived, archived.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Icon(if (showArchived) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                }
                if (showArchived) items(archived, key = { "a-" + it.id }) { p -> ProjectCard(p, ProjectStats.of(p.id, records, chung)) { onOpenProject(p.id) } }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: Project, stats: ProjectStats, onClick: () -> Unit) {
    AppCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.padding(top = 5.dp)) { ProjectDot(project.color, 14.dp) }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(project.code, project.client).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.project_card_meta, project.templates.size, stats.draft, stats.completed), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stats.lastActivityAt?.let { stringResource(R.string.project_last_activity, displayDayMonthTime(it)) } ?: stringResource(R.string.project_no_activity),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
