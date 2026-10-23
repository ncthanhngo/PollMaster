package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.pairedTable
import com.ncthanhngo.pollmaster.ui.common.AppCard
import kotlinx.serialization.json.JsonElement

/** One column of the "Ghi lượt" form together with the scope it reads and writes. */
private class Slot(val field: Field, val scope: ValueScope)

/** Writes to [first] and, for columns both paired tables define (O/T), to [second] as well. */
private class MirrorScope(private val first: ValueScope, private val second: ValueScope) : ValueScope {
    override fun get(id: String) = first.get(id)
    override fun update(id: String, f: (FieldValue) -> FieldValue) {
        first.update(id, f)
        second.update(id, f)
    }
    override fun fixed(id: String): JsonElement? = first.fixed(id)
    override fun key(id: String) = first.key(id)
    override fun mediaKey(id: String) = first.mediaKey(id)
    override fun lookup(id: String) = first.lookup(id)
}

private fun rowOf(env: EditorEnv, table: TableItem, rowId: String): Row =
    env.record.tables[table.id].orEmpty().firstOrNull { it.id == rowId } ?: Row(rowId)

private fun isShort(f: Field) = f.type == "integer" || f.type == "number" || f.type == "text"

/**
 * "Ghi lượt": one form for a row of a quick-entry table and, when paired, its partner table. Stopwatch on top,
 * one-tap grids for choices, short numbers side by side, video last, and "Lượt tiếp theo".
 */
@Composable
fun QuickEntryForm(table: TableItem, rowId: String, env: EditorEnv, modifier: Modifier, onNextRow: (String, String) -> Unit) {
    val controller = env.controller
    val partner = controller.template.pairedTable(table.id)
    val swTable = listOfNotNull(table, partner).firstOrNull { it.stopwatch != null }
    val swColumns = swTable?.stopwatch?.columns.orEmpty().toSet()
    val tables = listOfNotNull(table, partner).sortedBy { it === swTable }
    val scopes = tables.associate { t -> t.id to RowScope(controller, env.record, t.id, rowOf(env, t, rowId), emptyMap()) }

    val slots = mutableListOf<Slot>()
    for (t in tables) for (c in t.columns) {
        if (t === swTable && c.id in swColumns) continue
        val earlier = slots.firstOrNull { it.field.id == c.id && it.field.label == c.label }
        if (earlier != null) {
            val index = slots.indexOf(earlier)
            slots[index] = Slot(earlier.field, MirrorScope(earlier.scope, scopes.getValue(t.id)))
        } else {
            slots += Slot(c, scopes.getValue(t.id))
        }
    }
    val (media, inputs) = slots.partition { it.field.isMedia }

    // Short number / text fields share a row two at a time; the rest stay full width.
    val blocks = mutableListOf<List<Slot>>()
    var run = mutableListOf<Slot>()
    fun flush() { run.chunked(2).forEach { blocks += it }; run = mutableListOf() }
    for (s in inputs) if (isShort(s.field)) run += s else { flush(); blocks += listOf(s) }
    flush()

    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (swTable != null && !env.reviewMode) {
            item(key = "stopwatch") {
                StopwatchBlock(swTable.id, rowOf(env, swTable, rowId), swTable.stopwatch!!, controller)
            }
        }
        item(key = "fields") {
            AppCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    blocks.forEachIndexed { i, block ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            block.forEach { slot -> Box(Modifier.weight(1f)) { QuickField(slot, env) } }
                            if (block.size == 1 && isShort(block[0].field)) Box(Modifier.weight(1f)) {}
                        }
                    }
                }
            }
        }
        media.forEach { slot ->
            item(key = "media-" + slot.field.id) {
                AppCard {
                    Column(Modifier.padding(12.dp)) {
                        MediaField(
                            slot.field, slot.scope.get(slot.field.id), slot.scope, env, enabled = !env.reviewMode, asValue = true,
                            captureLabel = stringResource(R.string.quick_record_video),
                        )
                    }
                }
            }
        }
        if (!env.reviewMode) {
            item(key = "next") {
                Button(
                    onClick = { onNextRow(table.id, controller.addRow(table.id)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.quick_next)) }
            }
        }
    }
}

@Composable
private fun QuickField(slot: Slot, env: EditorEnv) {
    val field = slot.field
    val fv = slot.scope.get(field.id)
    val error = env.error(slot.scope.key(field.id))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldTitle(field)
        FieldBody(field, fv, slot.scope, env, enabled = !env.reviewMode, quick = true)
        error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
