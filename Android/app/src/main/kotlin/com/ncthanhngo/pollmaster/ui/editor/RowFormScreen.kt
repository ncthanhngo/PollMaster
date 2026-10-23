package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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

    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 1) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        if (table.quickEntry && !table.isFixed) {
            QuickEntryForm(table, rowId, env, Modifier.padding(padding), onNextRow)
        } else {
            val scope = RowScope(controller, env.record, tableId, view.row, view.fixed)
            val listState = rememberLazyListState()
            LaunchedEffect(focus) {
                val at = table.columns.indexOfFirst { it.id == focus }
                if (at >= 0) listState.scrollToItem(at + (if (table.stopwatch != null) 1 else 0))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                table.stopwatch?.let { sw -> if (!env.reviewMode) item(key = "stopwatch") { StopwatchBlock(tableId, view.row, sw, controller) } }
                items(table.columns, key = { it.id }) { column -> FieldCard(column, scope, env, showCode = false) }
            }
        }
    }
}
