package com.ncthanhngo.pollmaster.ui.projects

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.sites.MapPins
import com.ncthanhngo.pollmaster.data.RecordItem
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.rememberSitesCsvPicker

/** "Danh sách điểm" of a project (moved here from Settings): CSV import, count visited / not, view and search, clear. */
@Composable
fun ProjectSitesSection(project: Project, records: List<RecordItem>) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val all by container.sites.all.collectAsState()
    val sites = all[project.id].orEmpty()
    var viewing by remember { mutableStateOf(false) }
    val visited = remember(records) { MapPins.visitedCodes(records.mapNotNull { i -> i.template?.let { it to i.record } }) }
    val pickCsv = rememberSitesCsvPicker(project.id) { result ->
        val text = result.fold(
            { context.getString(R.string.settings_sites_imported, it) },
            { context.getString(R.string.settings_sites_error, it.message.orEmpty()) },
        )
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_sites), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_sites_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val done = sites.count { it.code in visited }
            Text(stringResource(R.string.project_sites_summary, sites.size, done, sites.size - done), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = pickCsv) { Text(stringResource(R.string.settings_sites_import)) }
                OutlinedButton(enabled = sites.isNotEmpty(), onClick = { viewing = true }) { Text(stringResource(R.string.project_sites_view)) }
            }
            OutlinedButton(enabled = sites.isNotEmpty(), onClick = { container.sites.clear(project.id) }) { Text(stringResource(R.string.settings_sites_clear)) }
        }
    }
    if (viewing) SitesSheet(sites, visited) { viewing = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SitesSheet(sites: List<com.ncthanhngo.pollmaster.core.sites.Site>, visited: Set<String>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = sites.filter { query.isBlank() || it.code.contains(query, true) || it.address.contains(query, true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.project_sites_search)) }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            )
            LazyColumn {
                items(shown, key = { it.code }) { s ->
                    val seen = s.code in visited
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.code, style = MaterialTheme.typography.titleSmall)
                            if (s.address.isNotEmpty()) Text(s.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (seen) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.project_sites_visited), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium)
                        } else {
                            Text(stringResource(R.string.project_sites_unvisited), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
