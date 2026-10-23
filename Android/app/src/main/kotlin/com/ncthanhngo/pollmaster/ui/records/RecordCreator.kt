package com.ncthanhngo.pollmaster.ui.records

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Template
import kotlinx.coroutines.launch

/**
 * Returns a function that creates a record of a template and reports its id. While the surveyor name is not set in
 * Settings it asks for it once (dialog), stores it and fills `HD-surveyor` of the new record.
 */
@Composable
fun rememberRecordCreator(onCreated: (String) -> Unit): (Template) -> Unit {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Template?>(null) }

    fun create(t: Template) {
        scope.launch { onCreated(container.records.create(t).id) }
    }
    pending?.let { t ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.surveyor_ask_title)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.settings_surveyor)) }, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    container.settings.setSurveyorName(name.trim())
                    pending = null
                    create(t)
                }) { Text(stringResource(R.string.surveyor_ask_save)) }
            },
            dismissButton = { TextButton(onClick = { pending = null; create(t) }) { Text(stringResource(R.string.surveyor_ask_skip)) } },
        )
    }
    return remember(container) {
        { t: Template -> if (container.settings.surveyorName.value.isBlank()) pending = t else create(t) }
    }
}
