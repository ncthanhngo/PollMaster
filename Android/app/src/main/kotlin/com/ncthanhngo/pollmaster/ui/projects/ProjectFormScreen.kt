package com.ncthanhngo.pollmaster.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectCodes
import com.ncthanhngo.pollmaster.core.project.ProjectDraft
import com.ncthanhngo.pollmaster.core.project.ProjectException
import com.ncthanhngo.pollmaster.ui.common.BackButton
import kotlinx.coroutines.launch

/** Create ([projectId] null) or edit a project: name, code (auto from the name until edited), client, description, colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFormScreen(projectId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val existing = remember(projectId) { container.projects.get(projectId) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var code by rememberSaveable { mutableStateOf(existing?.code.orEmpty()) }
    var codeEdited by rememberSaveable { mutableStateOf(existing != null) }
    var client by rememberSaveable { mutableStateOf(existing?.client.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(existing?.color ?: Project.COLORS.first()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val all by container.projects.projects.collectAsState()
    val taken = all.filter { it.id != projectId }.map { it.code }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existing == null) R.string.project_form_new else R.string.project_form_edit)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (!codeEdited) code = ProjectCodes.generate(it, taken).takeIf { c -> it.isNotBlank() } ?: ""
                },
                label = { Text(stringResource(R.string.project_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(ProjectCodes.MAX_LENGTH); codeEdited = true },
                label = { Text(stringResource(R.string.project_code)) },
                supportingText = { Text(stringResource(R.string.project_code_hint)) },
                isError = code.isNotEmpty() && (!ProjectCodes.isValid(code) || code in taken),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            OutlinedTextField(client, { client = it }, label = { Text(stringResource(R.string.project_client)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.project_description)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.project_color), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Project.COLORS.forEach { hex ->
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(projectColor(hex))
                            .border(if (hex == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { color = hex },
                    )
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                enabled = name.isNotBlank() && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val draft = ProjectDraft(name, code, client, description, color)
                    scope.launch {
                        try {
                            if (existing == null) onSaved(container.projects.create(draft).id)
                            else { container.projects.update(existing.id, draft); onSaved(existing.id) }
                        } catch (e: ProjectException) {
                            error = e.message
                        }
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}
