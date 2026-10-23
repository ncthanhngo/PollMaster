package com.ncthanhngo.pollmaster.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.validation.HEADER_SECTION_ID
import com.ncthanhngo.pollmaster.ui.common.BackButton
import com.ncthanhngo.pollmaster.ui.common.MessageDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineScreen(recordId: String, onBack: () -> Unit, onOpenSection: (String) -> Unit, onReview: () -> Unit) {
    val container = LocalContainer.current
    val controller = remember(recordId) { container.records.editor(recordId) }
    val saveError by container.records.saveError.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var exporting by rememberSaveable { mutableStateOf(false) }
    var showInstructions by rememberSaveable { mutableStateOf(false) }

    fun leave() {
        container.records.release(recordId)
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
    val issues = remember(record) { controller.issues(record) }
    val progress = remember(record) { controller.progress(record) }
    val missing = issues.groupingBy { it.sectionId }.eachCount()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(recordTitle(template, record), maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.progress_required, progress.filled, progress.total), style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { BackButton(::leave) },
                actions = {
                    Button(onClick = onReview) { Text(stringResource(R.string.action_review)) }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { menu = false; exporting = true })
                            if (template.instructions != null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_instructions)) }, onClick = { menu = false; showInstructions = true })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
            if (record.isCompleted) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Text(
                            stringResource(R.string.completed_banner, record.completedAt?.let { displayDateTime(it) }.orEmpty()),
                            Modifier.padding(12.dp),
                        )
                    }
                }
            }
            item {
                OutlineRow(stringResource(R.string.header_section), missing[HEADER_SECTION_ID] ?: 0) { onOpenSection(HEADER_SECTION_ID) }
            }
            items(template.sections, key = { it.id }) { s ->
                OutlineRow(s.title, missing[s.id] ?: 0) { onOpenSection(s.id) }
            }
        }
    }

    if (exporting) ExportSheet(recordId, onDismiss = { exporting = false })
    if (showInstructions) {
        MessageDialog(stringResource(R.string.menu_instructions), template.instructions.orEmpty()) { showInstructions = false }
    }
}

@Composable
private fun OutlineRow(title: String, missing: Int, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (missing > 0) {
                Badge(stringResource(R.string.section_missing, missing), warn = true)
            }
        }
    }
}
