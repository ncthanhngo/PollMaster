package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDayMonthTime
import com.ncthanhngo.pollmaster.core.sync.ConflictInfo
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.theme.attentionColor
import kotlinx.coroutines.launch

/**
 * A record the device and the server both changed (or the server lost): the choice is the user's (ux-spec N). The dialog opens by
 * itself once per screen; "Chọn cách xử lý" opens it again.
 */
@Composable
fun ConflictCard(recordId: String, readOnly: Boolean, serverRev: Int?, onDeleted: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ConflictInfo?>(null) }
    var asking by rememberSaveable { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId, serverRev) { info = container.sync.conflictInfo(recordId) }

    val message = when (val i = info) {
        ConflictInfo.Deleted -> stringResource(R.string.conflict_deleted)
        is ConflictInfo.Edited -> when {
            i.by != null && i.at != null -> stringResource(R.string.conflict_edited, i.by, displayDayMonthTime(i.at.toString()))
            i.by != null -> stringResource(R.string.conflict_edited_by, i.by)
            else -> stringResource(R.string.conflict_edited_unknown)
        }
        null -> stringResource(R.string.conflict_edited_unknown)
    }
    fun run(work: suspend () -> String?) {
        asking = false; working = true; failure = null
        scope.launch { failure = work(); working = false }
    }

    AppCard(container = MaterialTheme.colorScheme.tertiaryContainer, borderColor = attentionColor()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.titleSmall, color = attentionColor())
            Text(message, color = MaterialTheme.colorScheme.onTertiaryContainer)
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { asking = true }, enabled = !working) {
                Text(stringResource(if (working) R.string.conflict_working else R.string.conflict_choose))
            }
        }
    }
    if (!asking) return
    AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(stringResource(R.string.conflict_title)) },
        text = {
            Text(message + if (info != ConflictInfo.Deleted) "\n\n" + stringResource(R.string.conflict_take_hint) else "")
        },
        confirmButton = {
            Column {
                if (info == ConflictInfo.Deleted) {
                    if (container.records.canPost(container.recordStore.load(recordId)?.projectId)) {
                        TextButton(onClick = { run { container.sync.restore(recordId); null } }) { Text(stringResource(R.string.conflict_restore)) }
                    }
                    TextButton(onClick = { asking = false; scope.launch { container.records.delete(recordId); container.records.refresh(); onDeleted() } }) {
                        Text(stringResource(R.string.conflict_delete_local))
                    }
                } else {
                    if (!readOnly) TextButton(onClick = { run { container.sync.keepMine(recordId) } }) { Text(stringResource(R.string.conflict_keep_mine)) }
                    TextButton(onClick = { run { container.sync.takeServer(recordId) } }) { Text(stringResource(R.string.conflict_take_server)) }
                }
                TextButton(onClick = { asking = false }) { Text(stringResource(R.string.conflict_later)) }
            }
        },
    )
}
