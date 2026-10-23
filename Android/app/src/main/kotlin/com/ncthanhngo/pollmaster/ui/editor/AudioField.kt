package com.ncthanhngo.pollmaster.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.nextMediaName
import com.ncthanhngo.pollmaster.core.format.formatElapsed
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Media
import com.ncthanhngo.pollmaster.core.model.mediaItems
import com.ncthanhngo.pollmaster.core.model.mediaToJson
import com.ncthanhngo.pollmaster.platform.AudioPlayer
import com.ncthanhngo.pollmaster.platform.AudioRecorder
import com.ncthanhngo.pollmaster.platform.audioDurationSeconds
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.PermissionRationale
import kotlinx.coroutines.delay
import java.io.File

/** Round record button (red while recording, shows the time) and the list of recordings with play / delete. */
@Composable
fun AudioField(field: Field, fv: FieldValue?, scope: ValueScope, env: EditorEnv, enabled: Boolean) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val recordId = env.record.id
    val mediaDir = remember(recordId) { container.records.mediaDir(recordId) }
    val recorder = remember { AudioRecorder(context) }
    val player = remember { AudioPlayer() }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf<String?>(null) }
    var rationale by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Media?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val items = fv?.mediaItems().orEmpty()

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun finish() {
        val ok = recorder.stop()
        recording = false
        val src = pending?.let(::File)
        pending = null
        if (src == null) return
        if (!ok) {
            src.delete()
            return
        }
        val name = nextMediaName(env.controller.template, env.controller.record.value, scope.mediaKey(field.id), "m4a", mediaDir.list()?.toSet().orEmpty())
        val dest = File(mediaDir, name)
        if (!src.renameTo(dest)) {
            runCatching { src.copyTo(dest, overwrite = true) }.onFailure { toast(context.getString(R.string.media_error, it.message.orEmpty())); return }
            src.delete()
        }
        scope.update(field.id) { cur -> cur.copy(v = mediaToJson(cur.mediaItems() + Media("media/$name", nowIso()))) }
        env.controller.touch(scope.key(field.id))
    }

    fun begin() {
        val file = container.media.newCaptureFile("m4a")
        try {
            recorder.start(file)
            pending = file.path
            seconds = 0
            recording = true
        } catch (e: Exception) {
            toast(context.getString(R.string.media_error, e.message.orEmpty()))
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else toast(context.getString(R.string.perm_mic_denied))
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(500)
            seconds++
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (recorder.isRecording) finish()
            player.stop()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                shape = CircleShape,
                color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                contentColor = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp).clickable(enabled = enabled) {
                    when {
                        recording -> finish()
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> begin()
                        else -> rationale = true
                    }
                },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, stringResource(R.string.audio_record))
                }
            }
            Text(
                if (recording) formatElapsed((seconds / 2).toDouble()) else stringResource(R.string.audio_record),
                style = MaterialTheme.typography.titleMedium,
                color = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        items.forEach { m ->
            val file = File(mediaDir, m.file.substringAfterLast('/'))
            val duration = remember(file.path) { audioDurationSeconds(file) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (playing == m.file) {
                        player.stop(); playing = null
                    } else {
                        runCatching { player.play(file) { playing = null } }
                            .onSuccess { playing = m.file }
                            .onFailure { toast(context.getString(R.string.media_error, it.message.orEmpty())) }
                    }
                }) { Icon(if (playing == m.file) Icons.Filled.Stop else Icons.Filled.PlayArrow, stringResource(R.string.audio_play)) }
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    duration?.let { Text(formatElapsed(it.toDouble()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (enabled) IconButton(onClick = { deleting = m }) { Icon(Icons.Filled.Delete, stringResource(R.string.media_delete_title)) }
            }
        }
    }
    if (rationale) {
        PermissionRationale(
            message = stringResource(R.string.perm_mic_message),
            onAllow = { rationale = false; permission.launch(Manifest.permission.RECORD_AUDIO) },
            onDismiss = { rationale = false },
        )
    }
    deleting?.let { m ->
        ConfirmDialog(
            title = stringResource(R.string.media_delete_title),
            message = m.file.substringAfterLast('/'),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                scope.update(field.id) { cur -> cur.copy(v = mediaToJson(cur.mediaItems().filter { it.file != m.file })) }
                File(mediaDir, m.file.substringAfterLast('/')).delete()
            },
            onDismiss = { deleting = null },
        )
    }
}
