package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.isSystemInDarkTheme
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.PhotoCamera
import com.ncthanhngo.pollmaster.core.editor.OutlineStats
import com.ncthanhngo.pollmaster.core.editor.SectionStats
import com.ncthanhngo.pollmaster.ui.theme.warnColor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.format.displayTime
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.validation.HEADER_SECTION_ID
import com.ncthanhngo.pollmaster.core.validation.Progress
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.BackButton
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.MessageDialog
import com.ncthanhngo.pollmaster.ui.theme.okColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineScreen(
    recordId: String,
    onBack: () -> Unit,
    onOpenSection: (sectionId: String, focus: String?) -> Unit,
    onReview: () -> Unit,
) {
    val container = LocalContainer.current
    val controller = remember(recordId) { container.records.editor(recordId) }
    val saveError by container.records.saveError.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var showInstructions by rememberSaveable { mutableStateOf(false) }
    var confirmReturn by remember { mutableStateOf(false) }

    fun leave() {
        container.records.release(recordId)
        container.backup.requestIfNeeded(recordId)
        onBack()
    }
    BackHandler(onBack = ::leave)

    if (controller == null) {
        EditorMissing(::leave)
        return
    }
    val template = controller.template
    val env = rememberEditorEnv(controller)
    val record = env.record
    val savedAt by controller.savedAt.collectAsState()
    val checked by controller.showErrors.collectAsState()
    val issues = remember(record) { controller.issues(record) }
    val progress = remember(record) { controller.progress(record) }
    val perSection = remember(record) { controller.sectionProgress(record) }
    val stats = remember(record) { OutlineStats.compute(template, record) }
    val resume = record.lastPosition?.takeIf { it.field != null }?.let { pos ->
        val field = template.header.plus(template.sections.flatMap { s -> s.items.filterIsInstance<Field>() })
            .firstOrNull { it.id == pos.field }?.let { "${pos.field} ${it.label}" }
        // A table title already starts with its id ("E1. Nhật ký …").
        (field ?: template.sections.flatMap { s -> s.items.filterIsInstance<TableItem>() }.firstOrNull { it.id == pos.field }?.title)
            ?.let { pos to it }
    }

    val systemDark = isSystemInDarkTheme()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(recordTitle(template, record), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.progress_saved, progress.filled, progress.total, displayTime(savedAt)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackButton(::leave) },
                actions = {
                    OutlinedButton(onClick = onReview, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(stringResource(R.string.action_review) + if (checked && issues.isNotEmpty()) " (${issues.size})" else "", maxLines = 1)
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { menu = false; exporting = true })
                            DropdownMenuItem(
                                text = { Text(stringResource(if (LocalUiPrefs.current.dark) R.string.theme_switch_light else R.string.theme_switch_dark)) },
                                onClick = { menu = false; container.settings.setThemeMode(container.settings.themeMode.value.toggled(systemDark)) },
                            )
                            if (template.instructions != null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_instructions)) }, onClick = { menu = false; showInstructions = true })
                            }
                            if (record.isCompleted || env.reviewMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (env.reviewMode) R.string.review_mode_exit else R.string.review_mode)) },
                                    onClick = { menu = false; controller.setReviewMode(!env.reviewMode) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (saveError != null) {
                item {
                    Text(
                        stringResource(R.string.save_error, saveError.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { container.records.clearSaveError() },
                    )
                }
            }
            if (env.reviewMode) {
                item {
                    AppCard(container = MaterialTheme.colorScheme.tertiaryContainer, borderColor = MaterialTheme.colorScheme.tertiary) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.review_mode_banner), color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { confirmReturn = true }) { Text(stringResource(R.string.review_return)) }
                                TextButton(onClick = { controller.setReviewMode(false) }) { Text(stringResource(R.string.review_mode_exit)) }
                            }
                        }
                    }
                }
            } else if (record.isCompleted) {
                item {
                    AppCard(container = MaterialTheme.colorScheme.primaryContainer, borderColor = MaterialTheme.colorScheme.primary) {
                        Text(
                            stringResource(R.string.completed_banner, record.completedAt?.let { displayDateTime(it) }.orEmpty()),
                            Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            resume?.let { (pos, text) ->
                item {
                    Button(onClick = { onOpenSection(pos.section, pos.field) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.resume_at, text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                OutlineRow(stringResource(R.string.header_section), perSection[HEADER_SECTION_ID], stats[HEADER_SECTION_ID], checked) { onOpenSection(HEADER_SECTION_ID, null) }
            }
            items(template.sections, key = { it.id }) { s ->
                OutlineRow(s.title, perSection[s.id], stats[s.id], checked) { onOpenSection(s.id, null) }
            }
        }
    }

    if (exporting) ExportSheet(recordId, onDismiss = { exporting = false })
    if (showInstructions) {
        MessageDialog(stringResource(R.string.menu_instructions), template.instructions.orEmpty()) { showInstructions = false }
    }
    if (confirmReturn) {
        ConfirmDialog(
            title = stringResource(R.string.review_return),
            message = stringResource(R.string.review_return_message),
            confirmLabel = stringResource(R.string.review_return),
            onConfirm = { controller.returnForFix(); controller.setReviewMode(false) },
            onDismiss = { confirmReturn = false },
        )
    }
}

/** Neutral gray "filled/required" until the first review; "✓" green when complete; red count only after reviewing. */
@Composable
private fun OutlineRow(title: String, progress: Progress?, stats: SectionStats?, checked: Boolean, onClick: () -> Unit) {
    AppCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                val summary = stats?.summary().orEmpty()
                if (summary.isNotEmpty() || stats?.missingPhoto == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (stats?.missingPhoto == true) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.outline_photo_missing), tint = warnColor(), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (progress != null && progress.total > 0) {
                val missing = progress.total - progress.filled
                when {
                    missing == 0 -> Text("✓", color = okColor(), style = MaterialTheme.typography.titleMedium)
                    checked -> Text(stringResource(R.string.section_missing, missing), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    else -> Text("${progress.filled}/${progress.total}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
