package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.HeadingItem
import com.ncthanhngo.pollmaster.core.model.Item
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.validation.HEADER_SECTION_ID
import com.ncthanhngo.pollmaster.ui.common.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionScreen(
    recordId: String,
    sectionId: String,
    focus: String?,
    onBack: () -> Unit,
    onSwitchSection: (String) -> Unit,
    onOpenRow: (tableId: String, rowId: String) -> Unit,
) {
    val container = LocalContainer.current
    val controller = remember(recordId) { container.records.editor(recordId) }
    if (controller == null) {
        EditorMissing(onBack)
        return
    }
    val template = controller.template
    val env = rememberEditorEnv(controller)
    val ids = remember(template) { listOf(HEADER_SECTION_ID) + template.sections.map { it.id } }
    val index = ids.indexOf(sectionId)
    val section = template.sections.firstOrNull { it.id == sectionId }
    val title = section?.title ?: stringResource(R.string.header_section)
    val items: List<Item> = section?.items ?: template.header
    val listState: LazyListState = rememberLazyListState()

    LaunchedEffect(focus) {
        val at = items.indexOfFirst { it.id == focus }
        if (at >= 0) listState.scrollToItem(at + (if (section?.note != null) 1 else 0))
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = { BackButton(onBack) }) },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(enabled = index > 0, onClick = { onSwitchSection(ids[index - 1]) }) { Text(stringResource(R.string.section_prev)) }
                OutlinedButton(enabled = index in 0 until ids.lastIndex, onClick = { onSwitchSection(ids[index + 1]) }) { Text(stringResource(R.string.section_next)) }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            section?.note?.let { note -> item(key = "note") { Text(note, style = MaterialTheme.typography.bodyMedium) } }
            items(items, key = { it.id }) { item ->
                when (item) {
                    is Field -> FieldCard(item, env.scope, env)
                    is HeadingItem -> Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    is TableItem -> TableCard(item, env) { rowId -> onOpenRow(item.id, rowId) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorMissing(onBack: () -> Unit) {
    Scaffold(topBar = { androidx.compose.material3.TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Text(stringResource(R.string.record_template_missing), Modifier.padding(padding).padding(24.dp))
    }
}
