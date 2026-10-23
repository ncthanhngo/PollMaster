package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.ui.common.MessageDialog

/** Read-only result of a `computed` field with the ƒx marker; tap shows the formula. */
@Composable
fun ComputedBox(field: Field, env: EditorEnv) {
    var showFormula by remember { mutableStateOf(false) }
    val text = env.formatter.display(field, null, env.engine.computeField(field))
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        onClick = { showFormula = true },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ƒx", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.padding(4.dp))
            Text(
                text.ifEmpty { "—" } + (field.unit?.takeIf { text.isNotEmpty() }?.let { " $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    if (showFormula) {
        MessageDialog(stringResource(R.string.formula_title), field.formula.orEmpty()) { showFormula = false }
    }
}

@Composable
fun ReadOnlyBox(field: Field, fv: FieldValue?, scope: ValueScope, env: EditorEnv) {
    val fixed = scope.fixed(field.id)
    val text = if (fv != null) env.formatter.display(field, fv) else fixed?.asDouble()?.let(::formatShortest) ?: fixed.asText().orEmpty()
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            listOfNotNull(field.prefix, text, field.unit?.takeIf { text.isNotEmpty() }).joinToString(" ").ifEmpty { "—" },
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
