package com.ncthanhngo.pollmaster.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.update.AppUpdate

/** "Có bản mới" dialog, asked once per build; "Cập nhật" downloads the APK in the browser, which then offers to install it. */
@Composable
fun UpdatePrompt() {
    val updates = LocalContainer.current.updates
    val context = LocalContext.current
    val u by updates.prompt.collectAsState()
    u?.let { update ->
        AlertDialog(
            onDismissRequest = updates::dismissPrompt,
            title = { Text(stringResource(R.string.update_title)) },
            text = { Text(stringResource(R.string.update_message, update.version, update.build)) },
            confirmButton = {
                TextButton(onClick = { updates.dismissPrompt(); openUpdate(context, update) }) { Text(stringResource(R.string.update_action)) }
            },
            dismissButton = { TextButton(onClick = updates::dismissPrompt) { Text(stringResource(R.string.update_later)) } },
        )
    }
}

fun openUpdate(context: Context, update: AppUpdate) {
    val url = update.downloadUrl ?: return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {}
}
