package com.ncthanhngo.pollmaster.ui.projects

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectRules
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.storage.compareVersions
import com.ncthanhngo.pollmaster.core.storage.newestVersions
import com.ncthanhngo.pollmaster.data.SyncService
import com.ncthanhngo.pollmaster.ui.common.AppCard
import kotlinx.coroutines.launch

/** "Mẫu của dự án": the templates new records can use; add from the library, remove, switch to a newer version. */
@Composable
fun ProjectTemplatesSection(project: Project) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val library by container.templates.templates.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Changes go through the server: they need the network and the manager role (dimmed otherwise; a tap says why).
    val allowed = container.sync.canChangeTemplates(project)
    val signedIn = container.erp.account.collectAsState().value != null

    /** Runs [action] when allowed; the sentence it returns (a refusal, no network) is shown. */
    fun change(action: suspend () -> String?) {
        if (!allowed) { Toast.makeText(context, SyncService.TEMPLATE_NEEDS_MANAGE, Toast.LENGTH_SHORT).show(); return }
        scope.launch { action()?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    }
    val libraryRefs = library.map { TemplateRef(it.id, it.version) }

    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.project_templates), style = MaterialTheme.typography.titleSmall)
            if (project.templates.isEmpty()) Text(stringResource(R.string.project_templates_empty), style = MaterialTheme.typography.bodyMedium)
            project.templates.forEach { ref ->
                val t = library.firstOrNull { it.id == ref.id && it.version == ref.version }
                val newer = ProjectRules.newerVersion(ref, libraryRefs, ::compareVersions)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t?.title ?: ref.id, style = MaterialTheme.typography.bodyLarge)
                        Text("${ref.id} · v${ref.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (newer != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.project_template_newer, newer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f, fill = false))
                                TextButton(
                                    onClick = { change { container.sync.useTemplateVersion(project.id, TemplateRef(ref.id, newer)) } },
                                    modifier = Modifier.alpha(if (allowed) 1f else 0.45f),
                                ) { Text(stringResource(R.string.project_template_use)) }
                            }
                        }
                    }
                    IconButton(onClick = { change { container.sync.removeTemplate(project.id, ref) } }, modifier = Modifier.alpha(if (allowed) 1f else 0.45f)) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.project_template_remove))
                    }
                }
            }
            OutlinedButton(
                onClick = { if (allowed) adding = true else Toast.makeText(context, SyncService.TEMPLATE_NEEDS_MANAGE, Toast.LENGTH_SHORT).show() },
                modifier = Modifier.fillMaxWidth().alpha(if (allowed) 1f else 0.45f),
            ) { Text(stringResource(R.string.project_template_add)) }
            if (signedIn) Text(stringResource(R.string.project_templates_shared), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (adding) {
        val candidates = newestVersions(library).filter { t -> project.templates.none { it.id == t.id } }.sortedBy { it.title }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.project_template_pick)) },
            text = {
                if (candidates.isEmpty()) Text(stringResource(R.string.project_template_none_left))
                else LazyColumn {
                    items(candidates, key = { "${it.id}@${it.version}" }) { t ->
                        Column(Modifier.fillMaxWidth().clickable {
                            adding = false
                            change { container.sync.assignTemplates(project.id, listOf(TemplateRef(t.id, t.version))) }
                        }.padding(vertical = 10.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text("${t.id} · v${t.version}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
