package com.ncthanhngo.pollmaster.ui.templates

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.storage.TemplateText

private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

@Composable
fun PasteJsonDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var noJson by remember { mutableStateOf(false) }
    // Prefill once from the clipboard when it holds JSON (reading it is allowed while the app is in the foreground).
    LaunchedEffect(Unit) {
        if (text.isEmpty()) clipboardText(context)?.let { c -> TemplateText.extractJson(c)?.let { text = it } }
    }
    fun paste() {
        val json = clipboardText(context)?.let(TemplateText::extractJson)
        if (json == null) Toast.makeText(context, R.string.paste_no_json, Toast.LENGTH_SHORT).show() else { text = json; noJson = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_json_title)) },
        text = {
            OutlinedTextField(
                text, { text = it; noJson = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 320.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                placeholder = { Text(stringResource(R.string.paste_json_hint)) },
                isError = noJson,
                supportingText = if (noJson) ({ Text(stringResource(R.string.paste_no_json)) }) else null,
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                val json = TemplateText.extractJson(text)
                if (json == null) noJson = true else { onSubmit(json); onDismiss() }
            }) { Text(stringResource(R.string.import_action)) }
        },
        dismissButton = {
            TextButton(onClick = ::paste) { Text(stringResource(R.string.paste_from_clipboard)) }
        },
    )
}

@Composable
fun ImportErrorDialog(message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_error)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = {
            TextButton(onClick = {
                val report = TemplateText.errorReport(context.getString(R.string.errors_prefix), message)
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("errors", report))
                Toast.makeText(context, R.string.errors_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy_errors)) }
        },
    )
}
