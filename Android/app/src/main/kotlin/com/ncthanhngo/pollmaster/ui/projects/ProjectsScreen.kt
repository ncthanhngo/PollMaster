package com.ncthanhngo.pollmaster.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxSize
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.ui.theme.attentionColor
import kotlinx.coroutines.launch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDayMonth
import com.ncthanhngo.pollmaster.core.project.ProjectStats
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.FirstFitText
import com.ncthanhngo.pollmaster.ui.common.ThemeToggleButton

/** Tab "Dự án": the ERP projects of the signed-in account, "Dự án công ty" then "Nội bộ" (like the ERP). Pull to refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onOpenProject: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val projects by container.projects.projects.collectAsState()
    val items by container.records.items.collectAsState()
    val sites by container.sites.all.collectAsState()
    val syncing by container.erp.syncing.collectAsState()
    val syncError by container.erp.syncError.collectAsState()
    val pickBundle = rememberBundlePicker()
    LaunchedEffect(Unit) { container.records.refresh() }

    val records = items.map { it.record }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_projects)) },
                actions = {
                    IconButton(onClick = pickBundle) { Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.project_import)) }
                    ThemeToggleButton()
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(syncing, onRefresh = { scope.launch { container.erp.sync() } }, Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                syncError?.let { e -> item(key = "error") { Text(e, color = attentionColor(), style = MaterialTheme.typography.bodySmall) } }
                if (projects.isEmpty()) item(key = "empty") { Text(stringResource(R.string.projects_empty), Modifier.padding(8.dp)) }
                for ((kind, title) in projectKindSections) {
                    val group = projects.filter { it.kind == kind }
                    if (group.isEmpty()) continue
                    item(key = "h-$kind") {
                        Text("${stringResource(title)} (${group.size})", Modifier.padding(top = 6.dp, start = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(group, key = { it.id }) { p -> ProjectCard(p, ProjectStats.of(p.id, records), sites[p.id].orEmpty().size) { onOpenProject(p.id) } }
                }
            }
        }
    }
}

/** Three single lines: name · code, client, templates, sites (shortened to fit) · counts and last activity. */
@Composable
private fun ProjectCard(project: Project, stats: ProjectStats, siteCount: Int, onClick: () -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    AppCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProjectAvatar(project, 44.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(project.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val head = listOfNotNull(project.code, project.client).joinToString(" · ")
                val extra = stringResource(R.string.project_card_extra, project.templates.size, siteCount)
                FirstFitText(listOf("$head · $extra", head, project.code), style = MaterialTheme.typography.bodyMedium, color = secondary)
                Text(
                    projectCounts(stats) + (stats.lastActivityAt?.let { " · " + displayDayMonth(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
