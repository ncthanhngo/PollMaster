package com.ncthanhngo.pollmaster.ui.templates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.fieldCount
import com.ncthanhngo.pollmaster.core.storage.InstallResult
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.MessageDialog
import kotlinx.coroutines.launch

private sealed interface ImportState {
    data class Failed(val message: String) : ImportState
    data class Overwrite(val json: String, val template: Template) : ImportState
    data class Done(val template: Template) : ImportState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(onOpenTemplate: (Template) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val templates by container.templates.templates.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var askUrl by rememberSaveable { mutableStateOf(false) }
    var state by remember { mutableStateOf<ImportState?>(null) }

    LaunchedEffect(Unit) { container.templates.refresh() }

    fun importJson(json: String) {
        scope.launch {
            state = when (val r = container.templates.install(json, overwrite = false)) {
                is InstallResult.Installed -> ImportState.Done(r.template)
                is InstallResult.Exists -> ImportState.Overwrite(json, r.template)
                is InstallResult.Invalid -> ImportState.Failed(r.message)
            }
        }
    }

    fun guarded(block: suspend () -> String) {
        scope.launch {
            try {
                importJson(block())
            } catch (e: Exception) {
                state = ImportState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) guarded { container.templates.readUri(uri) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_templates)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { menu = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.template_import))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_file)) },
                        onClick = {
                            menu = false
                            picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                        },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.import_url)) }, onClick = { menu = false; askUrl = true })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (templates.isEmpty()) {
                Text(stringResource(R.string.templates_empty), modifier = Modifier.padding(24.dp))
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(templates, key = { "${it.id}@${it.version}" }) { t ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenTemplate(t) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.template_meta, t.id, t.version, t.fieldCount()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (askUrl) UrlDialog(onDismiss = { askUrl = false }, onSubmit = { url -> guarded { container.templates.download(url) } })

    when (val s = state) {
        is ImportState.Failed -> MessageDialog(stringResource(R.string.import_error), s.message) { state = null }
        is ImportState.Overwrite -> ConfirmDialog(
            title = stringResource(R.string.overwrite_title),
            message = stringResource(R.string.overwrite_message, s.template.id, s.template.version),
            confirmLabel = stringResource(R.string.action_overwrite),
            onConfirm = {
                scope.launch {
                    state = when (val r = container.templates.install(s.json, overwrite = true)) {
                        is InstallResult.Installed -> ImportState.Done(r.template)
                        is InstallResult.Invalid -> ImportState.Failed(r.message)
                        is InstallResult.Exists -> null
                    }
                }
            },
            onDismiss = { if (state is ImportState.Overwrite) state = null },
        )
        is ImportState.Done -> {
            val context = LocalContext.current
            LaunchedEffect(s) {
                android.widget.Toast.makeText(context, context.getString(R.string.import_ok, s.template.title), android.widget.Toast.LENGTH_SHORT).show()
                state = null
            }
        }
        null -> Unit
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_url_title)) },
        text = {
            OutlinedTextField(url, { url = it }, singleLine = true, placeholder = { Text(stringResource(R.string.url_hint)) })
        },
        confirmButton = {
            TextButton(enabled = url.isNotBlank(), onClick = { onSubmit(url); onDismiss() }) { Text(stringResource(R.string.import_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
