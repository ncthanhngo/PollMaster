package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.isEmpty
import com.ncthanhngo.pollmaster.ui.common.AppCard

/**
 * Row of an `inline` fixed table (schema 8): the readonly columns are the label, the first choice column is answered
 * right here with one tap, and the other columns open in a small sheet with ✎.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InlineRow(table: TableItem, view: TableRowView, choice: Field, env: EditorEnv) {
    var sheet by remember { mutableStateOf(false) }
    val scope = RowScope(env.controller, env.record, table.id, view.row, view.fixed)
    val others = table.columns.filter { !it.readonly && it.id != choice.id }
    val hasOther = others.any { view.row.values[it.id]?.isEmpty() == false }
    AppCard(Modifier.clickable(enabled = others.isNotEmpty()) { sheet = true }, container = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(fixedTitle(table, view), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ScalarInput(
                        field = choice,
                        value = scope.get(choice.id)?.v,
                        onValue = { e -> scope.update(choice.id) { it.copy(v = e) }; env.controller.touch(scope.key(choice.id)) },
                        enabled = true,
                        quick = true,
                    )
                }
                if (others.isNotEmpty()) {
                    IconButton(onClick = { sheet = true }) {
                        Icon(
                            Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit),
                            tint = if (hasOther) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (sheet && others.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fixedTitle(table, view), style = MaterialTheme.typography.titleMedium)
                others.forEach { FieldCard(it, scope, env, showCode = false) }
                TextButton(onClick = { sheet = false }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}
