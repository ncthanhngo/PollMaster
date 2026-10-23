package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.KDD
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asText

/** Card for one field: badge + label, input by type, flag/reason, note and validation message. */
@Composable
fun FieldCard(field: Field, scope: ValueScope, env: EditorEnv, modifier: Modifier = Modifier, showCode: Boolean = true) {
    val fv = scope.get(field.id)
    val key = scope.key(field.id)
    val kdd = fv?.flag == FieldValue.FLAG_NOT_MEASURABLE
    var noteOpen by rememberSaveable(key) { mutableStateOf(!fv?.note.isNullOrBlank()) }
    val editable = !field.readonly && field.type != "computed"
    val error = env.error(key)

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (showCode) Badge(field.id)
                when (fv?.flag) {
                    FieldValue.FLAG_ESTIMATE -> Badge("~", warn = true)
                    FieldValue.FLAG_NOT_MEASURABLE -> Badge(KDD, warn = true)
                }
                Text(
                    field.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (field.required) Text("*", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                if (editable) {
                    FieldMenu(field, fv, onNote = { noteOpen = true }) { f -> scope.update(field.id, f); env.controller.touch(key) }
                }
            }
            field.method?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.alpha(if (kdd) 0.4f else 1f)) {
                when {
                    field.type == "computed" -> ComputedBox(field, env)
                    field.readonly -> ReadOnlyBox(field, fv, scope, env)
                    else -> FieldBody(field, fv, scope, env, enabled = !kdd)
                }
            }
            if (kdd) {
                OutlinedTextField(
                    value = fv?.reason.orEmpty(),
                    onValueChange = { text -> scope.update(field.id) { it.copy(reason = text) }; env.controller.touch(key) },
                    label = { Text(stringResource(R.string.field_reason)) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (noteOpen || !fv?.note.isNullOrBlank()) {
                OutlinedTextField(
                    value = fv?.note.orEmpty(),
                    onValueChange = { text -> scope.update(field.id) { it.copy(note = text) } },
                    label = { Text(stringResource(R.string.field_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FieldMenu(field: Field, fv: FieldValue?, onNote: () -> Unit, update: ((FieldValue) -> FieldValue) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (field.flagsEnabled) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.field_menu_estimate)) },
                    onClick = {
                        open = false
                        update { it.copy(flag = if (it.flag == FieldValue.FLAG_ESTIMATE) null else FieldValue.FLAG_ESTIMATE, reason = null) }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.field_menu_kdd)) },
                    onClick = {
                        open = false
                        update { it.copy(flag = if (it.flag == FieldValue.FLAG_NOT_MEASURABLE) null else FieldValue.FLAG_NOT_MEASURABLE) }
                    },
                )
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.field_menu_note)) }, onClick = { open = false; onNote() })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.field_menu_clear)) },
                onClick = { open = false; update { FieldValue(note = fv?.note) } },
            )
        }
    }
}

@Composable
fun Badge(text: String, warn: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (warn) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun ComputedBox(field: Field, env: EditorEnv) {
    var showFormula by remember { mutableStateOf(false) }
    val text = env.formatter.display(field, null, env.engine.computeField(field))
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        onClick = { showFormula = true },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("ƒx", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.padding(4.dp))
            Text(
                text.ifEmpty { "—" } + (field.unit?.takeIf { text.isNotEmpty() }?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    if (showFormula) {
        com.ncthanhngo.pollmaster.ui.common.MessageDialog(
            title = stringResource(R.string.formula_title),
            message = field.formula.orEmpty(),
            onDismiss = { showFormula = false },
        )
    }
}

@Composable
private fun ReadOnlyBox(field: Field, fv: FieldValue?, scope: ValueScope, env: EditorEnv) {
    val fixed = scope.fixed(field.id)
    val text = if (fv != null) {
        env.formatter.display(field, fv)
    } else {
        fixed?.asDouble()?.let { com.ncthanhngo.pollmaster.core.format.formatShortest(it) } ?: fixed.asText().orEmpty()
    }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            listOfNotNull(field.prefix, text, field.unit?.takeIf { text.isNotEmpty() }).joinToString(" ").ifEmpty { "—" },
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
