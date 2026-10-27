package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.templateeditor.DocPath
import com.ncthanhngo.pollmaster.core.templateeditor.EditOp
import com.ncthanhngo.pollmaster.core.templateeditor.PathKey
import com.ncthanhngo.pollmaster.core.templateeditor.arr
import com.ncthanhngo.pollmaster.core.templateeditor.obj
import com.ncthanhngo.pollmaster.core.templateeditor.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun listKey(path: DocPath): String = (path.lastOrNull() as? PathKey.Key)?.name.orEmpty()

@Composable
private fun describe(n: JsonObject, key: String): RowText {
    val id = n["id"].str().orEmpty()
    return when {
        key == "rows" -> RowText(id, n["fixed"].obj()?.values?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString(" · ")?.ifEmpty { null })
        key == "signatures" -> RowText(n["label"].str().orEmpty().ifEmpty { id }, id)
        n["kind"].str() == "heading" -> RowText(n["title"].str().orEmpty().ifEmpty { id }, "$id · ${stringResource(R.string.te_kind_heading)}")
        n["kind"].str() == "table" -> RowText(
            n["title"].str().orEmpty().ifEmpty { id }, "$id · ${stringResource(R.string.te_kind_table, n["columns"].arr()?.size ?: 0)}",
        )
        else -> RowText(n["label"].str().orEmpty().ifEmpty { id }, "$id · ${typeLabel(n["type"].str().orEmpty())}")
    }
}

/** Header, a section's items, a table's columns or rows, or the signatures: a list with its add buttons; a section also shows its own keys. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemsPage(vm: TemplateEditorViewModel, path: DocPath) {
    val key = listKey(path)
    val section = if (key == "items") path.dropLast(1) else null
    val size = vm.node(path).arr()?.size ?: 0
    var moving by remember { mutableStateOf<Int?>(null) }

    fun add(kind: String, type: String? = null) {
        if (vm.edit(EditOp.NewItem(path, null, kind, type))) vm.open(EditorPage.Item(path, size))
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (section != null) FormColumn {
            NodeIssues(vm, section)
            IdField(vm, section)
            PropText(vm, section, "title", R.string.te_section_title)
            PropText(vm, section, "note", R.string.te_note, multiline = true)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val sectionIndex = (section?.lastOrNull() as? PathKey.Index)?.i
            NodeList(
                vm, path, duplicable = true,
                onOpen = { vm.open(EditorPage.Item(path, it)) },
                extraActions = { i -> if (sectionIndex != null) listOf(RowAction(R.string.te_move_to) { moving = i }) else emptyList() },
            ) { describe(it, key) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (key) {
                    "header" -> AddButton(R.string.te_add_field) { add("field") }
                    "items" -> {
                        AddButton(R.string.te_add_field) { add("field") }
                        AddButton(R.string.te_add_heading) { add("heading") }
                        AddButton(R.string.te_add_table) { add("table") }
                    }
                    "columns" -> AddButton(R.string.te_add_column) { add("field", "text") }
                    "rows" -> AddButton(R.string.te_add_row) { add("row") }
                    "signatures" -> AddButton(R.string.te_add_signature) { add("signature") }
                }
            }
        }
    }

    val from = moving
    if (from != null && section != null) {
        val sections = vm.node(listOf(PathKey.Key("sections"))).arr().orEmpty()
        val current = (section.last() as PathKey.Index).i
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text(stringResource(R.string.te_move_to_title)) },
            text = {
                Column {
                    sections.forEachIndexed { i, s ->
                        if (i != current) TextButton(
                            onClick = { moving = null; vm.edit(EditOp.MoveItem(current, from, i)) }, modifier = Modifier.fillMaxWidth(),
                        ) { Text("${s.obj()?.get("id").str().orEmpty()} · ${s.obj()?.get("title").str().orEmpty()}", maxLines = 1) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun AddButton(label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(stringResource(label), maxLines = 1) }
}

/** The form of one node of a list, by what the list is. Gone (deleted, or moved by a jump that no longer fits): leaves the page. */
@Composable
fun ItemPage(vm: TemplateEditorViewModel, listPath: DocPath, index: Int) {
    val path = listPath + PathKey.Index(index)
    val node = vm.node(path).obj()
    if (node == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.back() }
        Text(stringResource(R.string.te_item_missing), Modifier.padding(24.dp))
        return
    }
    when (listKey(listPath)) {
        "signatures" -> SignatureForm(vm, path)
        "rows" -> RowForm(vm, path)
        "columns" -> FieldForm(vm, path, isColumn = true)
        else -> when (node["kind"].str()) {
            "heading" -> HeadingForm(vm, path)
            "table" -> TableForm(vm, path)
            else -> FieldForm(vm, path, isColumn = false)
        }
    }
}
