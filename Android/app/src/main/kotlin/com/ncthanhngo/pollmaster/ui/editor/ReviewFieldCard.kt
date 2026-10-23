package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.format.formatShortest
import com.ncthanhngo.pollmaster.ui.common.AppCard

/** Read-only card of review mode: shows the value; tapping opens the reviewer's note (stored in `FieldValue.review`). */
@Composable
fun ReviewFieldCard(field: Field, scope: ValueScope, env: EditorEnv, modifier: Modifier, showCode: Boolean) {
    val fv = scope.get(field.id)
    var editing by remember { mutableStateOf(false) }
    val shown = when {
        field.type == "computed" -> env.formatter.display(field, null, env.engine.computeField(field))
        fv != null -> env.formatter.display(field, fv)
        else -> scope.fixed(field.id)?.let { it.asText() ?: it.asDouble()?.let(::formatShortest) }.orEmpty()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        fv?.review?.takeIf { it.isNotBlank() }?.let { ReviewBox(it, onResolved = null) }
        AppCard(Modifier.clickable(enabled = scope is RecordScope) { editing = true }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showCode) Badge(field.id)
                    FieldTitle(field, Modifier.weight(1f))
                }
                Text(
                    listOf(shown, field.unit.orEmpty().takeIf { shown.isNotEmpty() }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "—" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (fv?.review.isNullOrBlank() && scope is RecordScope) {
                    Text(stringResource(R.string.review_tap_to_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(fv?.review.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("${field.id} · ${field.label}", maxLines = 2) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.review_note_label)) },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { env.controller.touch(scope.key(field.id)); scope.update(field.id) { it.copy(review = text.trim()) }; editing = false }) {
                    Text(stringResource(R.string.review_save))
                }
            },
            dismissButton = {
                Row {
                    if (!fv?.review.isNullOrBlank()) TextButton(onClick = { scope.update(field.id) { it.copy(review = null) }; editing = false }) { Text(stringResource(R.string.action_delete)) }
                    TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}
