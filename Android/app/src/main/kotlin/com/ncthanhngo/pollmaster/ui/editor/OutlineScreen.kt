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
import com.ncthanhngo.pollmaster.ui.common.ErpMarker
import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.format.displayTime
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.validation.HEADER_SECTION_ID
import com.ncthanhngo.pollmaster.core.validation.Progress
import com.ncthanhngo.pollmaster.ui.common.AppCard
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.ui.common.IconBadge
import com.ncthanhngo.pollmaster.ui.common.StatusChip
import com.ncthanhngo.pollmaster.ui.projects.ProjectAvatar
import kotlin.math.roundToInt
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
        container.sync.request(controller?.record?.value?.projectId)
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
                            stringResource(R.string.outline_saved, displayTime(savedAt)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ErpMarker(record)
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
            item {
                val project = container.projects.get(record.projectId)
                ProgressHero(project, progress, resume?.takeIf { !record.isCompleted }?.let { (pos, text) -> text to { onOpenSection(pos.section, pos.field) } })
            }
            item {
                Text(
                    stringResource(R.string.outline_toc), Modifier.padding(start = 4.dp, top = 8.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** Project of the record, progress at a glance and the "continue where I left off" button (the screen's one primary action). */
@Composable
private fun ProgressHero(project: Project?, progress: Progress, resume: Pair<String, () -> Unit>?) {
    val fraction = if (progress.total > 0) progress.filled.toFloat() / progress.total else 1f
    val tint = if (fraction >= 1f) okColor() else MaterialTheme.colorScheme.primary
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            project?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectAvatar(p, 24.dp)
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${progress.filled}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.outline_required_of, progress.total), Modifier.weight(1f).padding(start = 6.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("${(fraction * 100).roundToInt()} %", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = tint)
            }
            LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth(), color = tint, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            resume?.let { (text, open) ->
                Button(onClick = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.resume_at, text), Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Status icon (done ✓, started half, not started hollow, nothing required list) · title · stats line; a red count only
 * after the first review (neutral before, ux-spec C).
 */
@Composable
private fun OutlineRow(title: String, progress: Progress?, stats: SectionStats?, checked: Boolean, onClick: () -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    AppCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                progress != null && progress.total > 0 && progress.filled == progress.total -> IconBadge(Icons.Filled.Check, tint = okColor(), size = 32.dp)
                progress != null && progress.filled > 0 -> IconBadge(Icons.Filled.Contrast, size = 32.dp)
                progress != null && progress.total > 0 -> IconBadge(Icons.Outlined.Circle, tint = secondary, size = 32.dp)
                else -> IconBadge(Icons.AutoMirrored.Filled.List, tint = secondary, size = 32.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stats?.summary()?.ifEmpty { null } ?: stringResource(R.string.outline_nothing_required), style = MaterialTheme.typography.bodyMedium, color = secondary)
                    if (stats?.missingPhoto == true) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.outline_photo_missing), tint = warnColor(), modifier = Modifier.size(16.dp))
                    }
                }
            }
            val missing = progress?.let { it.total - it.filled } ?: 0
            if (checked && missing > 0) StatusChip("$missing", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        }
    }
}
