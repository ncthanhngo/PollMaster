package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.formatShortest
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.isEmpty
import com.ncthanhngo.pollmaster.core.model.pairedTable
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import kotlinx.serialization.json.JsonElement

/** The ordered rows of a table: stored rows (repeat tables) or template rows merged with stored data (fixed tables). */
data class TableRowView(val row: Row, val fixed: Map<String, JsonElement>)

fun tableRows(table: TableItem, stored: List<Row>): List<TableRowView> {
    val defs = table.rows
    return if (defs != null) defs.map { d -> TableRowView(stored.firstOrNull { it.id == d.id } ?: Row(d.id), d.fixed) }
    else stored.map { TableRowView(it, emptyMap()) }
}

@Composable
fun TableCard(table: TableItem, env: EditorEnv, onOpenRow: (String) -> Unit) {
    val rows = tableRows(table, env.record.tables[table.id].orEmpty())
    var deleting by remember { mutableStateOf<TableRowView?>(null) }
    val issue = env.error(table.id)
    val rowLabel = table.rowLabel ?: stringResource(R.string.row_default)

    val quickPaired = table.quickEntry && env.controller.template.pairedTable(table.id) != null
    AppCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(table.title, style = MaterialTheme.typography.titleMedium)
            table.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (rows.isEmpty()) {
                Text(stringResource(if (table.isFixed) R.string.table_empty else R.string.table_empty_add), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rows.forEachIndexed { index, view ->
                RowCard(
                    title = if (table.isFixed) fixedTitle(table, view) else "$rowLabel ${index + 1}",
                    summary = summary(table, view, env),
                    done = !view.row.isEmpty(),
                    menu = if (table.isFixed || env.reviewMode) null else RowMenu(
                        onDuplicate = { env.controller.duplicateRow(table.id, view.row.id)?.let(onOpenRow) },
                        onDelete = { deleting = view },
                    ),
                    onClick = { onOpenRow(view.row.id) },
                )
            }
            if (!table.isFixed && !env.reviewMode) {
                Button(onClick = { onOpenRow(env.controller.addRow(table.id)) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(if (quickPaired) R.string.table_add_entry else R.string.table_add_row), modifier = Modifier.padding(start = 6.dp))
                }
            }
            issue?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    deleting?.let { view ->
        ConfirmDialog(
            title = stringResource(R.string.row_delete),
            message = stringResource(R.string.row_delete_confirm),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { env.controller.deleteRow(table.id, view.row.id) },
            onDismiss = { deleting = null },
        )
    }
}

private class RowMenu(val onDuplicate: () -> Unit, val onDelete: () -> Unit)

@Composable
private fun RowCard(title: String, summary: String, done: Boolean, menu: RowMenu?, onClick: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    AppCard(
        Modifier.clickable(onClick = onClick),
        container = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                else if (!done) Text("—", style = MaterialTheme.typography.bodySmall)
            }
            if (menu != null) {
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate)) }, onClick = { open = false; menu.onDuplicate() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { open = false; menu.onDelete() })
                    }
                }
            }
        }
    }
}

/** Title of a fixed row: its readonly columns joined (e.g. "S04 · Lệch ngang trái / phải · 5 mm"). */
fun fixedTitle(table: TableItem, view: TableRowView): String =
    table.columns.filter { it.readonly }.mapNotNull { c ->
        view.fixed[c.id]?.let { it.asText() ?: it.asDouble()?.let { d -> formatShortest(d) } }
    }.joinToString(" · ").ifEmpty { view.row.id }

/** First (up to 3) editable columns that have a value, as "label: value". */
private fun summary(table: TableItem, view: TableRowView, env: EditorEnv): String =
    table.columns.filter { !it.readonly }.mapNotNull { c ->
        val fv = view.row.values[c.id] ?: return@mapNotNull null
        val text = env.formatter.display(c, fv).ifEmpty { return@mapNotNull null }
        "${c.label}: $text"
    }.take(3).joinToString("  ·  ")
