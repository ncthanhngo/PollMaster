package com.ncthanhngo.pollmaster.ui.editor

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import com.ncthanhngo.pollmaster.ui.theme.TOUCH_ONE_HAND
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.validation.Issue
import com.ncthanhngo.pollmaster.core.validation.reviewNotes
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.BackButton
import com.ncthanhngo.pollmaster.ui.theme.warnColor
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    recordId: String,
    onBack: () -> Unit,
    onJumpField: (sectionId: String, fieldId: String) -> Unit,
    onJumpRow: (tableId: String, rowId: String, columnId: String) -> Unit,
    onFillOneByOne: () -> Unit,
) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val controller = remember(recordId) { container.records.editor(recordId) }
    if (controller == null) {
        EditorMissing(onBack)
        return
    }
    LaunchedEffect(controller) { controller.revealErrors() }
    val env = rememberEditorEnv(controller)
    val record = env.record
    val issues = remember(record) { controller.issues(record) }
    val warnings = remember(record, env.sites) { env.checker.check(record) }
    val notes = remember(record) { reviewNotes(controller.template, record) }
    var confirmReopen by remember { mutableStateOf(false) }
    val template = controller.template
    val progress = remember(record) { controller.progress(record) }
    val missing = (progress.total - progress.filled).takeIf { it > 0 } ?: issues.distinctBy { it.targetId }.size
    val groups = remember(issues) { issues.groupBy { it.sectionId } }
    // Which section groups are open: null = default (only the first one).
    var openIds by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openIds?.split('|')?.toSet() ?: groups.keys.take(1).toSet()
    fun toggle(id: String) { openIds = (if (id in open) open - id else open + id).joinToString("|") }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.review_title)) }, navigationIcon = { BackButton(onBack) }) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (record.isCompleted) {
                    OutlinedButton(onClick = { confirmReopen = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reopen)) }
                } else {
                    Button(
                        enabled = issues.isEmpty(),
                        onClick = {
                            if (controller.complete()) {
                                Toast.makeText(context, R.string.complete_done, Toast.LENGTH_SHORT).show()
                                container.backup.requestIfNeeded(recordId)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.complete)) }
                    if (issues.isNotEmpty()) {
                        Text(
                            stringResource(R.string.review_missing_count, missing),
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (record.isCompleted) {
                item {
                    AppCard(container = MaterialTheme.colorScheme.primaryContainer, borderColor = MaterialTheme.colorScheme.primary) {
                        Text(stringResource(R.string.completed_banner, record.completedAt?.let { displayDateTime(it) }.orEmpty()), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            if (issues.isEmpty()) {
                item { Text(stringResource(R.string.review_ok), style = MaterialTheme.typography.bodyLarge) }
            } else {
                item(key = "summary") {
                    AppCard(container = MaterialTheme.colorScheme.errorContainer, borderColor = MaterialTheme.colorScheme.error) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.review_missing_count, missing),
                                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Button(
                                onClick = onFillOneByOne,
                                modifier = Modifier.fillMaxWidth().heightIn(min = if (LocalUiPrefs.current.oneHand) TOUCH_ONE_HAND else 52.dp),
                            ) { Text(stringResource(R.string.review_fill_one_by_one)) }
                        }
                    }
                }
            }
            groups.forEach { (sectionId, list) ->
                item(key = "h-$sectionId") {
                    Row(
                        Modifier.fillMaxWidth().clickable { toggle(sectionId) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${list.first().sectionTitle} (${list.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Icon(if (sectionId in open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (sectionId in open) {
                    items(list, key = { "${it.key}-${it.kind}" }) { issue ->
                        IssueRow(issue) {
                            if (issue.rowId != null) onJumpRow(issue.targetId, issue.rowId, issue.columnId.orEmpty())
                            else onJumpField(issue.sectionId, issue.targetId)
                        }
                    }
                }
            }
            if (warnings.isNotEmpty()) {
                item(key = "warn-title") {
                    Text(stringResource(R.string.review_group_warnings), style = MaterialTheme.typography.titleSmall, color = warnColor(), modifier = Modifier.padding(top = 8.dp))
                }
                items(warnings, key = { "w-${it.key}-${it.kind}" }) { w ->
                    ListRow("${w.targetId} · ${w.label}", w.message, warnColor()) {
                        if (w.rowId != null) onJumpRow(w.targetId, w.rowId, w.columnId.orEmpty()) else onJumpField(w.sectionId, w.targetId)
                    }
                }
            }
            if (notes.isNotEmpty()) {
                item(key = "notes-title") {
                    Text(stringResource(R.string.review_group_notes), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 8.dp))
                }
                items(notes, key = { "n-${it.targetId}" }) { n ->
                    ListRow("${n.targetId} · ${n.label}", n.text, MaterialTheme.colorScheme.tertiary) {
                        onJumpField(n.sectionId, n.targetId)
                    }
                }
            }
            if (template.signatures.isNotEmpty()) {
                item { Text(stringResource(R.string.signatures_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(template.signatures, key = { "sig-${it.id}" }) { def ->
                    SignatureCard(def, record.id, controller, record.signatures[def.id], enabled = !record.isCompleted)
                }
            }
        }
    }

    if (confirmReopen) {
        ConfirmDialog(
            title = stringResource(R.string.reopen_title),
            message = stringResource(R.string.reopen_message),
            confirmLabel = stringResource(R.string.reopen),
            onConfirm = { controller.reopen() },
            onDismiss = { confirmReopen = false },
        )
    }
}

@Composable
private fun IssueRow(issue: Issue, onClick: () -> Unit) = ListRow(
    (if (issue.rowId == null) "${issue.targetId} · " else "${issue.targetId} › ") + issue.label,
    issue.message(), MaterialTheme.colorScheme.error, onClick,
)

@Composable
private fun ListRow(title: String, message: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    AppCard(Modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}
