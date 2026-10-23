package com.ncthanhngo.pollmaster.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.findField
import com.ncthanhngo.pollmaster.core.model.mediaItems
import com.ncthanhngo.pollmaster.platform.Dictation
import com.ncthanhngo.pollmaster.platform.TextRecognizer
import com.ncthanhngo.pollmaster.ui.common.PermissionRationale
import java.io.File

/** Joins dictated / recognised [added] text to the end of [current] (one space, or a new line for OCR lines). */
fun appendText(current: String?, added: String, separator: String = " "): String =
    if (current.isNullOrBlank()) added else current.trimEnd() + separator + added

/** 🎤 button of multiline fields: speaks into the field. Hidden when the device has no recognition service. */
@Composable
fun DictationButton(enabled: Boolean, onText: (String) -> Unit) {
    val context = LocalContext.current
    val dictation = remember { Dictation(context) }
    if (!dictation.isAvailable) return
    var listening by remember { mutableStateOf(false) }
    var rationale by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { dictation.release() } }

    fun begin() {
        listening = true
        dictation.start(onPartial = {}, onResult = { text ->
            listening = false
            if (text.isNullOrBlank()) Toast.makeText(context, R.string.dictation_none, Toast.LENGTH_SHORT).show() else onText(text)
        })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else Toast.makeText(context, R.string.perm_mic_denied, Toast.LENGTH_LONG).show()
    }
    IconButton(enabled = enabled, onClick = {
        when {
            listening -> dictation.stop()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()
            else -> rationale = true
        }
    }) {
        Icon(
            if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = stringResource(R.string.dictation_start),
            tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
    if (rationale) {
        PermissionRationale(
            message = stringResource(R.string.perm_mic_message),
            onAllow = { rationale = false; permission.launch(Manifest.permission.RECORD_AUDIO) },
            onDismiss = { rationale = false },
        )
    }
}

/**
 * "Đọc chữ từ ảnh" for a text / multiline field with `photoRef`: recognises text on the referenced field's photos and
 * lets the user pick a line. Without photos it asks the evidence row below to open the camera first ([onNeedPhoto]).
 */
@Composable
fun OcrButton(field: Field, env: EditorEnv, enabled: Boolean, onNeedPhoto: () -> Unit, onPick: (String) -> Unit) {
    val ref = field.photoRef?.let { env.controller.template.findField(it) } ?: return
    val container = LocalContainer.current
    val context = LocalContext.current
    val dir = remember(env.record.id) { container.records.mediaDir(env.record.id) }
    val photos = env.record.values[ref.id]?.mediaItems().orEmpty().filter { it.file.endsWith(".jpg", true) }
    var waiting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var run by remember { mutableStateOf(0) }

    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        busy = true
        lines = try {
            TextRecognizer.lines(photos.map { File(dir, it.file.substringAfterLast('/')) })
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.ocr_failed, e.message.orEmpty()), Toast.LENGTH_LONG).show()
            null
        }
        busy = false
    }
    // A photo taken after pressing the button starts the recognition by itself.
    LaunchedEffect(photos.size) {
        if (waiting && photos.isNotEmpty()) { waiting = false; run++ }
    }
    OutlinedButton(enabled = enabled && !busy, onClick = {
        if (photos.isEmpty()) { waiting = true; onNeedPhoto() } else run++
    }) { Text(stringResource(if (busy) R.string.ocr_reading else R.string.ocr_read, ref.id)) }

    lines?.let { found ->
        AlertDialog(
            onDismissRequest = { lines = null },
            title = { Text(stringResource(R.string.ocr_title)) },
            text = {
                if (found.isEmpty()) Text(stringResource(R.string.ocr_empty))
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    found.forEach { line ->
                        Text(line, Modifier.fillMaxWidth().clickable { lines = null; onPick(line) }.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { lines = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
