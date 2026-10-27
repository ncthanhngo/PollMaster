package com.ncthanhngo.pollmaster.ui.templateeditor

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.sync.PublishOutcome
import com.ncthanhngo.pollmaster.core.templateeditor.TemplatePublishRules
import com.ncthanhngo.pollmaster.ui.common.MessageDialog
import kotlinx.coroutines.launch

/** A sentence the release answered with; [then] runs when it is closed. */
private class Note(val title: String, val body: String, val then: (() -> Unit)? = null)

/**
 * Release sheet: target project (chosen for a new template), "v1.0 → v1.1", and the other managed projects on the template, ticked.
 * Answers of the server come back as dialogs: another release since the draft began (Vẫn phát hành / Hủy), a taken code, blocking errors.
 */
@Composable
fun PublishDialog(vm: TemplateEditorViewModel, onDismiss: () -> Unit, onDone: () -> Unit, onShowIssues: () -> Unit, onTakenId: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by container.templates.templates.collectAsState()
    val draft = vm.draft ?: return
    val doc = draft.doc
    val isNew = draft.baseId == null
    val targets = remember(draft.draftId) { if (isNew) container.templateDrafts.publishTargets() else container.templateDrafts.managedProjects(draft.templateId).ifEmpty { container.templateDrafts.publishTargets() } }
    var target by remember { mutableStateOf(targets.firstOrNull { it.id == draft.projectId } ?: targets.firstOrNull()) }
    val others = remember(target?.id) { target?.let { container.templateDrafts.otherProjects(draft, it) }.orEmpty() }
    var unticked by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Note?>(null) }
    val kind = TemplatePublishRules.publishKind(vm.base, doc)
    val canPublish = container.templatePublisher.canPublish(target)
    val summary = when (kind) {
        TemplatePublishRules.Kind.NEW_TEMPLATE -> stringResource(R.string.te_publish_new)
        TemplatePublishRules.Kind.META -> stringResource(R.string.te_publish_meta)
        TemplatePublishRules.Kind.NEW_VERSION -> {
            val known = library.filter { it.id == draft.templateId }.map { it.version } + listOfNotNull(draft.baseVersion)
            stringResource(R.string.te_publish_version, draft.baseVersion.orEmpty(), draft.publishVersion ?: TemplatePublishRules.nextVersion(known))
        }
    }

    fun run(force: Boolean) {
        val chosen = target ?: return
        busy = true
        scope.launch {
            val result = vm.publish(chosen, others.map { it.id }.filter { it !in unticked }, force)
            busy = false
            when (val o = result.outcome) {
                is PublishOutcome.Published -> {
                    if (o.failedProjects.isEmpty()) {
                        Toast.makeText(context, context.getString(R.string.te_publish_done, o.version), Toast.LENGTH_SHORT).show()
                        onDone()
                    } else {
                        val names = o.failedProjects.joinToString("\n") { "• ${container.projects.get(it.projectId)?.name ?: it.projectId}: ${it.message}" }
                        message = Note(context.getString(R.string.te_publish_partial_title), context.getString(R.string.te_publish_partial_body, names), onDone)
                    }
                }
                is PublishOutcome.StaleBase -> stale = o.serverVersion
                is PublishOutcome.Invalid -> { onDismiss(); onShowIssues() }
                PublishOutcome.IdTaken -> message = Note(context.getString(R.string.te_publish_title), context.getString(R.string.te_id_taken)) { onDismiss(); onTakenId() }
                null -> message = Note(context.getString(R.string.te_publish_title), result.error.orEmpty())
            }
        }
    }

    val staleVersion = stale
    val note = message
    when {
        staleVersion != null -> AlertDialog(
            onDismissRequest = { stale = null },
            title = { Text(stringResource(R.string.te_stale_title)) },
            text = { Text(stringResource(R.string.te_stale_message, staleVersion, draft.baseVersion.orEmpty())) },
            confirmButton = { TextButton(onClick = { stale = null; run(true) }) { Text(stringResource(R.string.te_publish_anyway)) } },
            dismissButton = { TextButton(onClick = { stale = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
        note != null -> MessageDialog(note.title, note.body) {
            message = null
            note.then?.invoke()
        }
        else -> AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text(stringResource(R.string.te_publish_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary, style = MaterialTheme.typography.titleSmall)
                    if (isNew && targets.size > 1) {
                        Text(stringResource(R.string.te_publish_target), style = MaterialTheme.typography.labelLarge)
                        targets.forEach { p -> ProjectRow(p, selected = p.id == target?.id) { target = p; unticked = emptySet() } }
                    } else target?.let { Text(stringResource(R.string.te_publish_target) + ": " + it.name, style = MaterialTheme.typography.bodyMedium) }
                    if (others.isNotEmpty() && kind != TemplatePublishRules.Kind.META) {
                        Text(stringResource(R.string.te_publish_others), style = MaterialTheme.typography.labelLarge)
                        others.forEach { p ->
                            Row(Modifier.fillMaxWidth().clickable { unticked = if (p.id in unticked) unticked - p.id else unticked + p.id }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = p.id !in unticked, onCheckedChange = { unticked = if (it) unticked - p.id else unticked + p.id })
                                Text("${p.name} (${p.code})", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(stringResource(R.string.te_publish_others_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        vm.errors.isNotEmpty() -> Text(stringResource(R.string.te_publish_errors), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        !canPublish -> Text(stringResource(R.string.te_publish_needs), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        busy -> Text(stringResource(R.string.te_publish_busy), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(enabled = canPublish && !busy && target != null && vm.errors.isEmpty(), onClick = { run(false) }) { Text(stringResource(R.string.te_publish), maxLines = 1) } },
            dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ProjectRow(p: Project, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text("${p.name} (${p.code})", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
