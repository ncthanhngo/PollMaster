package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateIssue
import com.ncthanhngo.pollmaster.core.templateeditor.text
import kotlinx.serialization.json.JsonPrimitive

/** The code of a node: locked when the base already has it. */
@Composable
fun IdField(vm: TemplateEditorViewModel, path: DocPath) {
    PropText(vm, path, "id", R.string.te_code, locked = vm.text(path, "id") in vm.lockedIds, supporting = R.string.te_code_hint)
}

/** Lint findings that sit at or under [path]. */
@Composable
fun NodeIssues(vm: TemplateEditorViewModel, path: DocPath) {
    val p = path.text
    val mine = vm.issues.filter { it.path == p || it.path.startsWith("$p.") || it.path.startsWith("$p[") }
    mine.forEach {
        val error = it.severity == TemplateIssue.Severity.ERROR
        Text(
            "${if (error) stringResource(R.string.te_issue_error) else stringResource(R.string.te_issue_warning)}: ${it.message}",
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else com.ncthanhngo.pollmaster.ui.theme.warnColor(),
        )
    }
}

@Composable
fun JsonButton(vm: TemplateEditorViewModel, path: DocPath) {
    Text(stringResource(R.string.te_advanced_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { vm.open(EditorPage.Json(path)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.te_json_advanced), maxLines = 1) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(vm: TemplateEditorViewModel, path: DocPath, choices: List<String>) {
    val current = vm.text(path, "type")
    var open by remember { mutableStateOf(false) }
    val shown = if (current in choices) choices else listOf(current) + choices
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = typeLabel(current), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(R.string.te_type)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            shown.forEach { t ->
                DropdownMenuItem(text = { Text(typeLabel(t)) }, onClick = { open = false; if (t != current) vm.edit(EditOp.Set(path, "type", JsonPrimitive(t))) })
            }
        }
    }
}

/** A field of the header or of a section, or a column of a table (same keys). */
@Composable
fun FieldForm(vm: TemplateEditorViewModel, path: DocPath, isColumn: Boolean) {
    val type = vm.text(path, "type")
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FormColumn {
            NodeIssues(vm, path)
            IdField(vm, path)
            PropText(vm, path, "label", R.string.te_label)
            TypeDropdown(vm, path, if (isColumn) COLUMN_TYPES else FIELD_TYPES)
            PropSwitch(vm, path, "required", R.string.te_required)
            if (type == "number" || type == "integer") {
                PropText(vm, path, "unit", R.string.te_unit)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PropNumber(vm, path, "min", R.string.te_min, Modifier.weight(1f))
                    PropNumber(vm, path, "max", R.string.te_max, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PropNumber(vm, path, "warnMin", R.string.te_warn_min, Modifier.weight(1f))
                    PropNumber(vm, path, "warnMax", R.string.te_warn_max, Modifier.weight(1f))
                }
                if (type == "number") PropNumber(vm, path, "decimals", R.string.te_decimals, Modifier.fillMaxWidth())
            }
            if (type != "signature") PropText(vm, path, "hint", R.string.te_hint, multiline = true)
            if (type == "singleChoice" || type == "multiChoice") OptionsEditor(vm, path)
            JsonButton(vm, path)
        }
    }
}

/** A sub-heading between fields. */
@Composable
fun HeadingForm(vm: TemplateEditorViewModel, path: DocPath) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FormColumn {
            NodeIssues(vm, path)
            IdField(vm, path)
            PropText(vm, path, "title", R.string.te_title_field)
            PropText(vm, path, "note", R.string.te_note, multiline = true)
            JsonButton(vm, path)
        }
    }
}

@Composable
fun SignatureForm(vm: TemplateEditorViewModel, path: DocPath) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FormColumn {
            NodeIssues(vm, path)
            IdField(vm, path)
            PropText(vm, path, "label", R.string.te_label)
            JsonButton(vm, path)
        }
    }
}
