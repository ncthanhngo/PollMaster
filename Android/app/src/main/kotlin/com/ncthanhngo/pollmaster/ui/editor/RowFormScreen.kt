package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.ncthanhngo.pollmaster.core.model.findTable
import com.ncthanhngo.pollmaster.core.model.sectionOf
import com.ncthanhngo.pollmaster.core.validation.HEADER_SECTION_ID
import com.ncthanhngo.pollmaster.ui.common.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowFormScreen(
    recordId: String,
    tableId: String,
    rowId: String,
    focus: String?,
    onBack: () -> Unit,
    onNextRow: (tableId: String, rowId: String) -> Unit,
    onOpenField: (sectionId: String, fieldId: String) -> Unit,
) {
    val container = LocalContainer.current
    val controller = remember(recordId) { container.records.editor(recordId) }
    val table = controller?.template?.findTable(tableId)
    if (controller == null || table == null) {
        EditorMissing(onBack)
        return
    }
    val env = rememberEditorEnv(controller)
    val views = tableRows(table, env.record.tables[tableId].orEmpty())
    val index = views.indexOfFirst { it.row.id == rowId }
    val view = views.getOrNull(index)
    if (view == null) {
        // Row was deleted (e.g. from another screen): leave the form.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val rowLabel = table.rowLabel ?: stringResource(R.string.row_default)
    val title = if (table.isFixed) fixedTitle(table, view) else "$rowLabel ${index + 1}"

    fun openField(fieldId: String) {
        onOpenField(controller.template.sectionOf(fieldId)?.id ?: HEADER_SECTION_ID, fieldId)
    }

    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        if (table.quickEntry && !table.isFixed) {
            QuickEntryForm(table, rowId, env, Modifier.padding(padding), onNextRow, ::openField)
        } else {
            val scope = RowScope(controller, env.record, tableId, view.row, view.fixed)
            val listState = rememberLazyListState()
            LaunchedEffect(focus) {
                val at = table.columns.indexOfFirst { it.id == focus }
                if (at >= 0) listState.scrollToItem(at)
            }
            Column(Modifier.padding(padding)) {
                table.stopwatch?.let { sw ->
                    if (!env.reviewMode) PinnedStopwatch(tableId, view.row, sw, controller, listState.canScrollBackward, ::openField)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(table.columns, key = { it.id }) { column -> FieldCard(column, scope, env, showCode = false) }
                }
            }
        }
    }
}
