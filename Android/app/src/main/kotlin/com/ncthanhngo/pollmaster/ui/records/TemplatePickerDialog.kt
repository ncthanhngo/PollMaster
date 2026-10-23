package com.ncthanhngo.pollmaster.ui.records

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Template

@Composable
fun TemplatePickerDialog(templates: List<Template>, onDismiss: () -> Unit, onPick: (Template) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pick_template)) },
        text = {
            if (templates.isEmpty()) {
                Text(stringResource(R.string.templates_empty))
            } else {
                LazyColumn {
                    items(templates, key = { "${it.id}@${it.version}" }) { t ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 10.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text("${t.id} · v${t.version}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
