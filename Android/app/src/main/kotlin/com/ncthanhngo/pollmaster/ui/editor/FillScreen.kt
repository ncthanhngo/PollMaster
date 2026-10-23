package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.MissingFields
import com.ncthanhngo.pollmaster.core.editor.MissingStep
import com.ncthanhngo.pollmaster.core.format.KDD
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.findField
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.BackButton
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import com.ncthanhngo.pollmaster.ui.theme.TOUCH_ONE_HAND

/**
 * "Điền lần lượt": one missing item at a time as a full field card (compact rows off). Tiếp moves on (an item that is
 * still empty is skipped), Bỏ qua skips, KĐĐ marks it not measurable with a reason, tables open at the right place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FillScreen(
    recordId: String,
    onBack: () -> Unit,
    onOpenField: (sectionId: String, fieldId: String) -> Unit,
    onOpenRow: (tableId: String, rowId: String, columnId: String) -> Unit,
) {
    val container = LocalContainer.current
    val controller = remember(recordId) { container.records.editor(recordId) }
    if (controller == null) {
        EditorMissing(onBack)
        return
    }
    LaunchedEffect(controller) { controller.revealErrors() }
    val env = rememberEditorEnv(controller)
    val template = controller.template
    val steps = remember(env.record) { MissingFields.ordered(template, controller.issues(env.record)) }
    var skipped by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var currentKey by rememberSaveable { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf<MissingStep?>(null) }
    var total by rememberSaveable { mutableStateOf(steps.size) }
    var asking by remember { mutableStateOf(false) }
    val live = steps.firstOrNull { it.key == currentKey }

    LaunchedEffect(steps, currentKey) {
        when {
            live != null -> snapshot = live
            snapshot == null -> MissingFields.next(steps, skipped.toSet())?.let { currentKey = it.key; snapshot = it }
        }
        total = maxOf(total, steps.size)
    }

    fun advance(skipCurrent: Boolean) {
        val now = if (skipCurrent && currentKey != null) skipped + currentKey!! else skipped
        skipped = now
        val next = MissingFields.next(steps.filter { it.key != currentKey }, now.toSet())
        currentKey = next?.key
        snapshot = next
    }

    val step = snapshot
    val pendingOthers = steps.count { it.key !in skipped && it.key != currentKey }
    val position = (total - pendingOthers).coerceIn(1, maxOf(total, 1))

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.fill_position, position, total), style = MaterialTheme.typography.titleMedium)
                        step?.let { Text(it.issue.sectionTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = { TextButton(onClick = onBack) { Text(stringResource(R.string.fill_exit)) } },
            )
        },
        bottomBar = {
            if (step != null) {
                val touch = if (LocalUiPrefs.current.oneHand) TOUCH_ONE_HAND else 52.dp
                val field = template.findField(step.issue.targetId)
                Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { advance(true) }, modifier = Modifier.weight(1f).heightIn(min = touch)) { Text(stringResource(R.string.fill_skip)) }
                        OutlinedButton(
                            enabled = step.kind != MissingStep.Kind.TABLE && field?.flagsEnabled == true,
                            onClick = { asking = true }, modifier = Modifier.weight(1f).heightIn(min = touch),
                        ) { Text(KDD) }
                        Button(onClick = { advance(live != null) }, modifier = Modifier.weight(1.4f).heightIn(min = touch)) { Text(stringResource(R.string.fill_next)) }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step == null) {
                Text(stringResource(if (skipped.isEmpty()) R.string.fill_done else R.string.fill_done_skipped, skipped.size), style = MaterialTheme.typography.titleMedium)
                if (skipped.isNotEmpty()) {
                    Button(onClick = { skipped = emptyList(); steps.firstOrNull()?.let { currentKey = it.key; snapshot = it } }) { Text(stringResource(R.string.fill_again)) }
                }
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.fill_back_to_review)) }
            } else {
                val field = template.findField(step.issue.targetId)
                if (live == null) Text(stringResource(R.string.fill_resolved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                if (field != null && step.kind != MissingStep.Kind.TABLE) {
                    CompositionLocalProvider(LocalUiPrefs provides LocalUiPrefs.current.copy(compact = false)) {
                        FieldCard(field, env.scope, env)
                    }
                } else {
                    AppCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(step.issue.label, style = MaterialTheme.typography.titleMedium)
                            Text(step.issue.message(), color = MaterialTheme.colorScheme.error)
                            Button(onClick = {
                                val i = step.issue
                                if (i.rowId != null) onOpenRow(i.targetId, i.rowId, i.columnId.orEmpty()) else onOpenField(i.sectionId, i.targetId)
                            }) { Text(stringResource(R.string.fill_open)) }
                        }
                    }
                }
            }
        }
    }

    if (asking) KddReasonDialog(
        onConfirm = { reason ->
            asking = false
            snapshot?.issue?.targetId?.let { id ->
                env.scope.update(id) { it.copy(flag = FieldValue.FLAG_NOT_MEASURABLE, reason = reason) }
            }
        },
        onDismiss = { asking = false },
    )
}

@Composable
private fun KddReasonDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.field_menu_kdd)) },
        text = {
            OutlinedTextField(
                value = reason, onValueChange = { reason = it }, singleLine = true,
                label = { Text(stringResource(R.string.field_reason)) }, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = reason.isNotBlank(), onClick = { onConfirm(reason.trim()) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
