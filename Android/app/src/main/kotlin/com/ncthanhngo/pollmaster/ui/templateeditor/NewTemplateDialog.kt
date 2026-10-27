package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.storage.newestVersions
import com.ncthanhngo.pollmaster.ui.common.MessageDialog
import kotlinx.coroutines.launch

/**
 * "Tạo mẫu mới": an empty template or a copy of one in the library. [project] is where it will be released (the release sheet can still
 * change it); null = the open project, else the first one the user manages.
 */
@Composable
fun NewTemplateDialog(project: Project?, onCreated: (draftId: String) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val library by container.templates.templates.collectAsState()
    val currentId by container.projects.currentId.collectAsState()
    val targets = remember { container.templateDrafts.publishTargets() }
    val target = project ?: targets.firstOrNull { it.id == currentId } ?: targets.firstOrNull()
    var cloning by remember { mutableStateOf(false) }
    if (target == null) {
        MessageDialog(stringResource(R.string.te_new_template), stringResource(R.string.te_need_project), onDismiss)
        return
    }
    fun create(from: Template?) {
        scope.launch { onCreated(container.templateDrafts.create(target, from).draftId) }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (cloning) R.string.te_clone_pick else R.string.te_new_template)) },
        text = {
            if (!cloning) Column {
                TextButton(onClick = { create(null) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.te_new_blank), maxLines = 1) }
                TextButton(onClick = { cloning = true }, Modifier.fillMaxWidth(), enabled = library.isNotEmpty()) { Text(stringResource(R.string.te_new_clone), maxLines = 1) }
            } else LazyColumn {
                items(newestVersions(library).sortedBy { it.title }, key = { "${it.id}@${it.version}" }) { t ->
                    Column(Modifier.fillMaxWidth().clickable { create(t) }.padding(vertical = 10.dp)) {
                        Text(t.title, style = MaterialTheme.typography.titleSmall)
                        Text("${t.id} · v${t.version}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
