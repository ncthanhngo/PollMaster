package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import com.ncthanhngo.pollmaster.ui.common.AppCard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun NavRow(text: String, onClick: () -> Unit) {
    AppCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

/** A table: its own keys, then the lists of columns and fixed rows. */
@Composable
fun TableForm(vm: TemplateEditorViewModel, path: DocPath) {
    val columns = vm.node(path + PathKey.Key("columns")).arr().orEmpty()
    val rows = vm.node(path + PathKey.Key("rows")).arr()
    val rowsPath = path + PathKey.Key("rows")
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FormColumn {
            NodeIssues(vm, path)
            IdField(vm, path)
            PropText(vm, path, "title", R.string.te_title_field)
            PropText(vm, path, "note", R.string.te_note, multiline = true)
            PropText(vm, path, "rowLabel", R.string.te_row_label)
            NavRow(stringResource(R.string.te_columns, columns.size)) { vm.open(EditorPage.Items(path + PathKey.Key("columns"))) }
            if (rows != null) {
                NavRow(stringResource(R.string.te_rows, rows.size)) { vm.open(EditorPage.Items(rowsPath)) }
                Text(stringResource(R.string.te_rows_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { vm.edit(EditOp.Set(path, "rows", null)) }) { Text(stringResource(R.string.te_drop_rows)) }
            } else {
                OutlinedButton(onClick = { if (vm.edit(EditOp.Set(path, "rows", JsonArray(emptyList())))) vm.open(EditorPage.Items(rowsPath)) }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.te_use_rows), maxLines = 1)
                }
            }
            JsonButton(vm, path)
        }
    }
}

/** A fixed row: its code and the value of each column that is given in advance. */
@Composable
fun RowForm(vm: TemplateEditorViewModel, path: DocPath) {
    val table = path.dropLast(2)
    val columns = vm.node(table + PathKey.Key("columns")).arr().orEmpty().mapNotNull { it.obj() }
    val fixed = vm.node(path).obj()?.get("fixed").obj()
    fun write(col: JsonObject, text: String) {
        val id = col["id"].str() ?: return
        val value = when {
            text.isBlank() -> null
            col["type"].str() in setOf("number", "integer") && text.trim().toDoubleOrNull() != null -> JsonPrimitive(text.trim().toDouble().let { if (it % 1.0 == 0.0) it.toLong() as Number else it })
            else -> JsonPrimitive(text)
        }
        val ops = if (fixed == null) arrayOf(EditOp.Set(path, "fixed", JsonObject(emptyMap())), EditOp.Set(path + PathKey.Key("fixed"), id, value)) else arrayOf(EditOp.Set(path + PathKey.Key("fixed"), id, value))
        vm.edit(*ops)
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FormColumn {
            NodeIssues(vm, path)
            IdField(vm, path)
            columns.forEach { col ->
                val id = col["id"].str().orEmpty()
                val current = (fixed?.get(id) as? JsonPrimitive)?.content.orEmpty()
                OutlinedTextField(
                    value = current, onValueChange = { write(col, it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("${col["label"].str().orEmpty().ifEmpty { id }} ($id)", maxLines = 1) },
                )
            }
            JsonButton(vm, path)
        }
    }
}
