package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.templateeditor.DocPath
import com.ncthanhngo.pollmaster.core.templateeditor.EditOp
import com.ncthanhngo.pollmaster.core.templateeditor.PathKey
import com.ncthanhngo.pollmaster.core.templateeditor.arr
import com.ncthanhngo.pollmaster.core.templateeditor.obj
import com.ncthanhngo.pollmaster.core.templateeditor.str
import com.ncthanhngo.pollmaster.core.templateeditor.text
import com.ncthanhngo.pollmaster.ui.common.AppCard
import kotlinx.serialization.json.JsonObject

/** An extra entry of a row's menu. */
class RowAction(val label: Int, val onClick: () -> Unit)

/** One-line main text and a smaller second line for a node. */
class RowText(val main: String, val sub: String?)

/** The nodes of the list at [path] as rows: tap to open, ↑ ↓ to reorder, ⋯ for the rest. Deleting asks first and names what uses the node. */
@Composable
fun NodeList(
    vm: TemplateEditorViewModel,
    path: DocPath,
    duplicable: Boolean,
    onOpen: (Int) -> Unit,
    extraActions: (Int) -> List<RowAction> = { emptyList() },
    describe: @Composable (JsonObject) -> RowText,
) {
    val list = vm.node(path).arr().orEmpty()
    var deleting by remember(path.text) { mutableStateOf<Int?>(null) }
    if (list.isEmpty()) return
    AppCard {
        list.forEachIndexed { i, node ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val text = describe(node.obj() ?: JsonObject(emptyMap()))
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(text.main, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    text.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                IconButton(enabled = i > 0, onClick = { vm.edit(EditOp.Move(path, i, i - 1)) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.te_move_up))
                }
                IconButton(enabled = i < list.lastIndex, onClick = { vm.edit(EditOp.Move(path, i, i + 1)) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.te_move_down))
                }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menu = false; onOpen(i) })
                        if (duplicable) DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate)) }, onClick = { menu = false; vm.edit(EditOp.Duplicate(path, i)) })
                        extraActions(i).forEach { a -> DropdownMenuItem(text = { Text(stringResource(a.label)) }, onClick = { menu = false; a.onClick() }) }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.te_menu_json)) },
                            onClick = { menu = false; vm.open(EditorPage.Json(path + PathKey.Index(i))) },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; deleting = i })
                    }
                }
            }
        }
    }
    deleting?.let { i ->
        val node = list.getOrNull(i).obj()
        val name = node?.get("label").str() ?: node?.get("title").str() ?: node?.get("id").str().orEmpty()
        val refs = remember(i, vm.draft) { vm.danglingAfterDelete(path, i) }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.te_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.te_delete_message, name))
                    if (refs.isNotEmpty()) {
                        Text(stringResource(R.string.te_delete_used), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                        refs.take(6).forEach { Text("• ${it.path}", style = MaterialTheme.typography.bodySmall) }
                        if (refs.size > 6) Text("…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { deleting = null; vm.edit(EditOp.Delete(path, i)) }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
