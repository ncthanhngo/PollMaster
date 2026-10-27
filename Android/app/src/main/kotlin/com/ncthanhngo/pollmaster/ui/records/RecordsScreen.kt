package com.ncthanhngo.pollmaster.ui.records

import com.ncthanhngo.pollmaster.ui.common.ThemeToggleButton
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.automirrored.filled.Undo
import com.ncthanhngo.pollmaster.ui.common.IconBadge
import com.ncthanhngo.pollmaster.ui.theme.attentionColor
import com.ncthanhngo.pollmaster.ui.theme.okColor
import com.ncthanhngo.pollmaster.ui.theme.warnColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDayMonthTime
import com.ncthanhngo.pollmaster.data.RecordItem
import com.ncthanhngo.pollmaster.core.storage.StoredRecord
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.FirstFitText
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.StatusChip
import com.ncthanhngo.pollmaster.ui.common.MessageDialog
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectRules
import com.ncthanhngo.pollmaster.core.project.ProjectStats
import com.ncthanhngo.pollmaster.ui.editor.ExportSheet
import com.ncthanhngo.pollmaster.ui.projects.ProjectChoiceDialog
import com.ncthanhngo.pollmaster.ui.projects.ProjectAvatar
import com.ncthanhngo.pollmaster.ui.projects.ProjectSwitcherSheet
import com.ncthanhngo.pollmaster.ui.projects.rememberBundlePicker
import kotlinx.coroutines.launch

/** Title of the records tab: avatar + name of the current project (or "Tất cả dự án") and a drop-down arrow. */
@Composable
private fun ProjectTitle(project: com.ncthanhngo.pollmaster.core.project.Project?, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (project != null) ProjectAvatar(project, 24.dp)
        Text(project?.name ?: stringResource(R.string.project_all), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.project_switch))
    }
}

private enum class StatusFilter { ALL, DRAFT, COMPLETED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(onOpenRecord: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val items by container.records.items.collectAsState()
    val broken by container.records.broken.collectAsState()
    var brokenInfo by remember { mutableStateOf<StoredRecord.Broken?>(null) }
    var brokenDelete by remember { mutableStateOf<StoredRecord.Broken?>(null) }
    val templates by container.templates.templates.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(StatusFilter.ALL) }
    var showMap by rememberSaveable { mutableStateOf(false) }
    val allSites by container.sites.all.collectAsState()
    val projects by container.projects.projects.collectAsState()
    val currentId by container.projects.currentId.collectAsState()
    val project = projects.firstOrNull { it.id == currentId }
    // Planned sites of the current project, or of every project when none is selected.
    val sites = if (currentId == null) allSites.values.flatten() else allSites[currentId].orEmpty()
    var switcher by remember { mutableStateOf(false) }
    var pickingProject by remember { mutableStateOf(false) }
    var pickingFor by remember { mutableStateOf<com.ncthanhngo.pollmaster.core.project.Project?>(null) }
    var noTemplates by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<RecordItem?>(null) }
    val pickBundle = rememberBundlePicker()
    var deleting by remember { mutableStateOf<RecordItem?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val startRecord = rememberRecordCreator(onOpenRecord)

    LaunchedEffect(templates) { container.records.refresh() }

    val visible = items.filter { item ->
        (currentId == null || item.projectId == currentId) &&
            (query.isBlank() || item.title.contains(query, ignoreCase = true)) &&
            when (filter) {
                StatusFilter.ALL -> true
                StatusFilter.DRAFT -> !item.record.isCompleted
                StatusFilter.COMPLETED -> item.record.isCompleted
            }
    }

    /** Starts a record in [p]: its only template is used at once, several are offered. */
    fun beginIn(p: com.ncthanhngo.pollmaster.core.project.Project) {
        val usable = p.templates.mapNotNull { ref -> templates.firstOrNull { it.id == ref.id && it.version == ref.version } }
        when {
            usable.isEmpty() -> noTemplates = true
            usable.size == 1 -> startRecord(usable.first(), p.id)
            else -> pickingFor = p
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { ProjectTitle(project) { switcher = true } }, actions = { ThemeToggleButton() })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (project != null) beginIn(project) else pickingProject = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.record_new))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                SegmentedButton(!showMap, { showMap = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.view_list)) }
                SegmentedButton(showMap, { showMap = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.view_map)) }
            }
            if (!showMap) OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.records_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, null) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == StatusFilter.ALL, { filter = StatusFilter.ALL }, { Text(stringResource(R.string.filter_all)) })
                FilterChip(filter == StatusFilter.DRAFT, { filter = StatusFilter.DRAFT }, { Text(stringResource(R.string.status_draft)) })
                FilterChip(filter == StatusFilter.COMPLETED, { filter = StatusFilter.COMPLETED }, { Text(stringResource(R.string.status_completed)) })
            }
            if (showMap) {
                RecordsMap(visible, sites, onOpenRecord, Modifier.weight(1f).padding(top = 8.dp))
                return@Column
            }
            val visibleBroken = if (filter == StatusFilter.ALL) broken.filter { query.isBlank() || it.id.contains(query, ignoreCase = true) } else emptyList()
            if (visible.isEmpty() && visibleBroken.isEmpty()) {
                Text(
                    stringResource(R.string.records_empty),
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleBroken, key = { "broken-" + it.id }) { b ->
                    BrokenCard(b, onOpen = { brokenInfo = b }, onDelete = { brokenDelete = b })
                }
                items(visible, key = { it.record.id }) { item ->
                    RecordCard(
                        item = item,
                        project = projects.firstOrNull { it.id == item.projectId },
                        onMove = { moving = item },
                        onOpen = { onOpenRecord(item.record.id) },
                        onDuplicate = {
                            scope.launch {
                                container.records.duplicate(item)
                                container.records.refresh()
                            }
                        },
                        onExport = { exporting = item.record.id },
                        onDelete = { deleting = item },
                    )
                }
            }
        }
    }

    pickingFor?.let { p ->
        TemplatePickerDialog(
            templates = p.templates.mapNotNull { ref -> templates.firstOrNull { it.id == ref.id && it.version == ref.version } },
            onDismiss = { pickingFor = null },
            onPick = { t ->
                pickingFor = null
                startRecord(t, p.id)
            },
        )
    }
    if (pickingProject) {
        val active = projects.filter { !it.archived }
        if (active.isEmpty()) {
            MessageDialog(stringResource(R.string.pick_project), stringResource(R.string.no_active_projects)) { pickingProject = false }
        } else {
            ProjectChoiceDialog(
                title = stringResource(R.string.pick_project), projects = active, multi = false,
                onDismiss = { pickingProject = false }, onConfirm = { pickingProject = false; beginIn(it.first()) },
            )
        }
    }
    if (noTemplates) MessageDialog(stringResource(R.string.pick_template), stringResource(R.string.project_no_templates)) { noTemplates = false }
    if (switcher) {
        val records = items.map { it.record }
        ProjectSwitcherSheet(
            projects = projects, currentId = currentId,
            statsOf = { ProjectStats.of(it.id, records) },
            onSelect = { container.projects.select(it); switcher = false },
            onImport = { switcher = false; pickBundle() },
            onDismiss = { switcher = false },
        )
    }
    moving?.let { item ->
        val targets = ProjectRules.moveTargets(item.record, projects, item.projectId)
        if (targets.isEmpty()) {
            MessageDialog(stringResource(R.string.record_move), stringResource(R.string.record_move_none, item.record.templateId)) { moving = null }
        } else {
            val context = androidx.compose.ui.platform.LocalContext.current
            ProjectChoiceDialog(
                title = stringResource(R.string.record_move_title), projects = targets, multi = false,
                onDismiss = { moving = null },
                onConfirm = { chosen ->
                    moving = null
                    scope.launch {
                        container.backup.deleteBackup(item.record.id)
                        container.records.move(item.record.id, chosen.first().id)
                        container.records.refresh()
                        android.widget.Toast.makeText(context, context.getString(R.string.record_moved, chosen.first().name), android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_title),
            message = stringResource(R.string.delete_record_message, item.title),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch {
                    container.records.delete(item.record.id)
                    container.records.refresh()
                }
            },
            onDismiss = { deleting = null },
        )
    }
    brokenInfo?.let { b ->
        MessageDialog(title = b.id, message = b.error, onDismiss = { brokenInfo = null })
    }
    brokenDelete?.let { b ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_title),
            message = stringResource(R.string.delete_record_message, b.id),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                scope.launch {
                    container.records.delete(b.id)
                    container.records.refresh()
                }
            },
            onDismiss = { brokenDelete = null },
        )
    }
    exporting?.let { id -> ExportSheet(recordId = id, onDismiss = { exporting = null }) }
}

/** Three lines, none wrapping: title · project avatar + name (or code) and date · status, backup flag and progress. */
@Composable
private fun RecordCard(item: RecordItem, project: Project?, onMove: () -> Unit, onOpen: () -> Unit, onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    AppCard(Modifier.clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(item)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val style = MaterialTheme.typography.bodyMedium
                    if (project != null) {
                        ProjectAvatar(project, 16.dp)
                        // full name when it fits, else the short code, never a cut-off name
                        FirstFitText(listOf(project.name, project.code), Modifier.weight(1f, fill = false), style = style, color = secondary)
                        Text("·", style = style, color = secondary)
                    }
                    Text(displayDayMonthTime(item.record.updatedAt), style = style, color = secondary, maxLines = 1, softWrap = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordChips(item)
                    val p = item.progress
                    if (p.total > 0) {
                        LinearProgressIndicator(
                            progress = { p.filled.toFloat() / p.total }, modifier = Modifier.weight(1f),
                            color = if (item.record.isCompleted) okColor() else MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text("${p.filled}/${p.total}", style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, softWrap = false)
                    }
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate)) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.record_move)) }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** 44x44 photo of the record, or a status icon (draft, returned, completed) when there is no photo yet. */
@Composable
private fun Thumb(item: RecordItem) {
    val file = item.thumbnail
    if (file != null) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(44.dp)) {
            AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        return
    }
    val r = item.record
    when {
        r.isCompleted -> IconBadge(Icons.Filled.Verified, tint = okColor(), size = 44.dp)
        r.returned -> IconBadge(Icons.AutoMirrored.Filled.Undo, tint = attentionColor(), size = 44.dp)
        else -> IconBadge(Icons.Filled.Description, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 44.dp)
    }
}

/** One status chip ("Cần sửa" replaces "Nháp" on a returned record), then a cloud-off icon while the record is not backed up. */
@Composable
internal fun RecordChips(item: RecordItem) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalUiPrefs.current.dark
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            item.record.isCompleted ->
                StatusChip(stringResource(R.string.status_completed), if (dark) Color(0xFF1E4D33) else Color(0xFFD7F0DF), if (dark) Color(0xFFBFF0D0) else Color(0xFF0E4A25))
            item.record.returned ->
                StatusChip(stringResource(R.string.status_returned), if (dark) Color(0xFF5A3300) else Color(0xFFFFE0B8), if (dark) Color(0xFFFFD9A8) else Color(0xFF6A3A00))
            else -> StatusChip(stringResource(R.string.status_draft), scheme.surfaceVariant, scheme.onSurfaceVariant)
        }
        if (item.needsBackup) {
            Icon(Icons.Filled.CloudOff, contentDescription = stringResource(R.string.status_not_backed_up), tint = warnColor(), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun BrokenCard(b: StoredRecord.Broken, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AppCard(Modifier.clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.id, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(b.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            StatusChip(stringResource(R.string.record_read_error), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
