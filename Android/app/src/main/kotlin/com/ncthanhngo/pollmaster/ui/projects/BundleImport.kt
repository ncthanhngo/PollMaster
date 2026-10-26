package com.ncthanhngo.pollmaster.ui.projects

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.project.BundleContents
import com.ncthanhngo.pollmaster.core.project.BundleException
import com.ncthanhngo.pollmaster.core.project.ImportPlan
import com.ncthanhngo.pollmaster.ui.templates.ImportErrorDialog
import kotlinx.coroutines.launch

/** Returns a function that opens the file picker; the chosen package goes to [ImportBundleHost] for the preview. */
@Composable
fun rememberBundlePicker(): () -> Unit {
    val container = LocalContainer.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) container.pendingBundle.value = uri
    }
    return remember(picker) { { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "*/*")) } }
}

private sealed interface BundleState {
    data class Preview(val contents: BundleContents, val plan: ImportPlan) : BundleState
    data class Conflicts(val contents: BundleContents, val plan: ImportPlan) : BundleState
    data class Failed(val message: String) : BundleState
}

/** Shows the preview (name, code, templates, sites) of the package waiting in `pendingBundle` and imports it on "Nhập". */
@Composable
fun ImportBundleHost(onImported: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri: Uri? by container.pendingBundle.collectAsState()
    var state by remember { mutableStateOf<BundleState?>(null) }

    fun close() {
        state = null
        container.pendingBundle.value = null
    }

    fun import(contents: BundleContents, overwrite: Boolean) {
        scope.launch {
            try {
                val outcome = container.bundles.import(contents, overwrite)
                Toast.makeText(context, context.getString(R.string.bundle_done, outcome.project.name), Toast.LENGTH_SHORT).show()
                close()
                onImported(outcome.project.id)
            } catch (e: Exception) { // reported to the user, never swallowed
                state = BundleState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        state = try {
            val contents = container.bundles.read(u)
            BundleState.Preview(contents, container.bundles.plan(contents))
        } catch (e: BundleException) {
            BundleState.Failed(e.errors.joinToString("\n"))
        }
    }

    when (val s = state) {
        is BundleState.Failed -> ImportErrorDialog(s.message) { close() }
        is BundleState.Preview -> PreviewDialog(
            s.contents, s.plan,
            onImport = { if (s.plan.conflicts.isEmpty()) import(s.contents, false) else state = BundleState.Conflicts(s.contents, s.plan) },
            onDismiss = ::close,
        )
        is BundleState.Conflicts -> AlertDialog(
            onDismissRequest = ::close,
            title = { Text(stringResource(R.string.overwrite_title)) },
            text = { Text(stringResource(R.string.bundle_conflict, s.plan.conflicts.size, s.plan.conflicts.joinToString { "${it.id} v${it.version}" })) },
            confirmButton = { TextButton(onClick = { import(s.contents, true) }) { Text(stringResource(R.string.bundle_overwrite_templates)) } },
            dismissButton = { TextButton(onClick = { import(s.contents, false) }) { Text(stringResource(R.string.bundle_keep_templates)) } },
        )
        null -> Unit
    }
}

@Composable
private fun PreviewDialog(contents: BundleContents, plan: ImportPlan, onImport: () -> Unit, onDismiss: () -> Unit) {
    val p = contents.project
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bundle_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectAvatar(p, 40.dp)
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.bundle_preview_info, plan.code, contents.templates.size, contents.sites?.size ?: 0), style = MaterialTheme.typography.bodyMedium)
                p.client?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                contents.templates.forEach {
                    Text("${it.template.title} · ${it.template.id} v${it.template.version}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (plan.dropped.isNotEmpty()) Text(stringResource(R.string.bundle_dropped, plan.dropped.joinToString { "${it.id} v${it.version}" }), color = MaterialTheme.colorScheme.error)
                if (plan.updates != null) Text(stringResource(R.string.bundle_update), Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.primary)
                else if (plan.code != p.code) Text(stringResource(R.string.bundle_code_suffix, plan.code), Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.tertiary)
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) { Text(stringResource(if (plan.updates != null) R.string.bundle_update_action else R.string.bundle_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
