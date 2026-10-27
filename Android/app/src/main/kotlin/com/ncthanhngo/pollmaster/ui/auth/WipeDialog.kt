package com.ncthanhngo.pollmaster.ui.auth

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.data.ErpSession.WipePrompt

/** First ERP sign-in on a device with old data: back up and wipe, retry or force after failed backups, or cancel (sign out). */
@Composable
fun WipeDialog(prompt: WipePrompt, onBackup: () -> Unit, onForce: () -> Unit, onCancel: () -> Unit) {
    val failed = prompt.failed > 0
    AlertDialog(
        onDismissRequest = {}, // answered explicitly; a stray tap must not decide about deleting data
        title = { Text(stringResource(if (failed) R.string.login_wipe_failed_title else R.string.login_wipe_title)) },
        text = {
            Text(
                when {
                    prompt.busy -> stringResource(R.string.login_wipe_busy)
                    failed -> stringResource(R.string.login_wipe_failed_message, prompt.failed, prompt.records)
                    else -> stringResource(R.string.login_wipe_message, prompt.records, prompt.projects)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onBackup, enabled = !prompt.busy) {
                Text(stringResource(if (failed) R.string.login_wipe_retry else R.string.login_wipe_confirm))
            }
        },
        dismissButton = {
            Row {
                if (failed) TextButton(onClick = onForce, enabled = !prompt.busy) {
                    Text(stringResource(R.string.login_wipe_force), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onCancel, enabled = !prompt.busy) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
