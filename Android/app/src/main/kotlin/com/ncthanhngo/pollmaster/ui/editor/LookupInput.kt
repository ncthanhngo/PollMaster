package com.ncthanhngo.pollmaster.ui.editor

import com.ncthanhngo.pollmaster.ui.theme.accentTonalColors
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.ui.common.rememberSitesCsvPicker
import kotlinx.serialization.json.JsonElement

/** Text input plus "Chọn từ danh sách": picking a site fills the other fields named by `lookup.fill`. */
@Composable
fun LookupInput(field: Field, fv: FieldValue?, scope: ValueScope, env: EditorEnv, enabled: Boolean, onValue: (JsonElement?) -> Unit) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<Site?>(null) }
    val controller = env.controller
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScalarInput(field.copy(lookup = null), fv?.v, onValue, enabled)
        // No imported list yet: the button loads one (CSV picker) and opens the chooser right after.
        val loadCsv = rememberSitesCsvPicker(env.projectId) { result ->
            result.onSuccess { choosing = true }
                .onFailure { Toast.makeText(context, context.getString(R.string.settings_sites_error, it.message.orEmpty()), Toast.LENGTH_LONG).show() }
        }
        FilledTonalButton(enabled = enabled, colors = accentTonalColors(), onClick = { if (env.sites.isEmpty()) loadCsv() else choosing = true }) {
            Text(stringResource(if (env.sites.isEmpty()) R.string.lookup_load_csv else R.string.lookup_choose))
        }
    }
    if (choosing) {
        SitePicker(env.sites, onDismiss = { choosing = false }) { site ->
            choosing = false
            if (controller.lookupPlan(field, site).overwrites.isEmpty()) {
                controller.applyLookup(field, site, overwrite = false)
                controller.touch(scope.key(field.id))
            } else {
                conflict = site
            }
        }
    }
    conflict?.let { site ->
        val targets = controller.lookupPlan(field, site).overwrites.keys.joinToString(", ")
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text(stringResource(R.string.lookup_overwrite_title)) },
            text = { Text(stringResource(R.string.lookup_overwrite_message, targets)) },
            confirmButton = {
                TextButton(onClick = { controller.applyLookup(field, site, overwrite = true); conflict = null }) { Text(stringResource(R.string.action_overwrite)) }
            },
            dismissButton = {
                TextButton(onClick = { controller.applyLookup(field, site, overwrite = false); conflict = null }) { Text(stringResource(R.string.lookup_keep)) }
            },
        )
    }
}

/** Bottom sheet listing the imported sites, searchable by code or address. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SitePicker(sites: List<Site>, onDismiss: () -> Unit, onPick: (Site) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = sites.filter { query.isBlank() || it.code.contains(query, true) || it.address.contains(query, true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.lookup_search)) }, modifier = Modifier.fillMaxWidth(),
            )
            if (sites.isEmpty()) Text(stringResource(R.string.lookup_empty), Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(shown) { site ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(site) }.padding(vertical = 10.dp)) {
                        Text(site.code, style = MaterialTheme.typography.titleSmall)
                        if (site.address.isNotBlank()) Text(site.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
