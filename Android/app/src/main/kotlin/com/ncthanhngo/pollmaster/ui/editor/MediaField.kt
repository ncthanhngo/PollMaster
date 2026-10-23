package com.ncthanhngo.pollmaster.ui.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.nextMediaName
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Media
import com.ncthanhngo.pollmaster.core.model.mediaItems
import com.ncthanhngo.pollmaster.core.model.mediaToJson
import com.ncthanhngo.pollmaster.platform.LocationHelper
import com.ncthanhngo.pollmaster.ui.common.ConfirmDialog
import com.ncthanhngo.pollmaster.ui.common.PermissionRationale
import kotlinx.coroutines.launch
import java.io.File

/**
 * Thumbnails plus capture / library buttons. [asValue] = the field is a photo/video field
 * (media stored in `v`); otherwise the media is the field's attached photos.
 */
@Composable
fun MediaField(
    field: Field,
    fv: FieldValue?,
    scope: ValueScope,
    env: EditorEnv,
    enabled: Boolean,
    asValue: Boolean,
    /** Evidence row of a field with `photoRef`: small thumbnails and one capture button, no library or delete. */
    referenceOnly: Boolean = false,
    /** Overrides the capture button text (e.g. "Quay video lượt này"). */
    captureLabel: String? = null,
    /** Increment to open the camera from outside (e.g. OCR without photos). */
    captureTrigger: Int = 0,
) {
    val context = LocalContext.current
    val container = LocalContainer.current
    val isVideo = asValue && field.type == "video"
    val items = if (asValue) fv?.mediaItems().orEmpty() else fv?.media.orEmpty()
    val recordId = env.record.id
    val mediaDir = remember(recordId) { container.records.mediaDir(recordId) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Media?>(null) }
    var deleting by remember { mutableStateOf<Media?>(null) }
    var rationale by remember { mutableStateOf(false) }
    val canAdd = enabled && (field.maxCount == null || items.size < field.maxCount)

    fun setItems(list: List<Media>) {
        scope.update(field.id) { if (asValue) it.copy(v = mediaToJson(list)) else it.copy(media = list) }
        env.controller.touch(scope.key(field.id))
    }

    fun addFile(src: File) {
        container.scope.launch {
            try {
                val record = env.controller.record.value
                val dir = mediaDir
                val name = nextMediaName(env.controller.template, record, scope.mediaKey(field.id), if (isVideo) src.extension.lowercase().ifEmpty { "mp4" } else "jpg", dir.list()?.toSet().orEmpty())
                val loc = LocationHelper(context).lastKnown()
                val media = if (isVideo) container.media.finishVideo(src, File(dir, name), loc, nowIso())
                else container.media.finishImage(src, File(dir, name), loc, nowIso())
                scope.update(field.id) { cur ->
                    if (asValue) cur.copy(v = mediaToJson(cur.mediaItems() + media)) else cur.copy(media = cur.media + media)
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.media_error, e.message.orEmpty()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pending?.let { path -> if (ok) addFile(File(path)) else File(path).delete() }
        pending = null
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        pending?.let { path -> if (ok) addFile(File(path)) else File(path).delete() }
        pending = null
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        uris.forEach { uri ->
            container.scope.launch {
                try {
                    addFile(container.media.copyToCapture(uri, if (isVideo) container.media.extensionOf(uri, "mp4") else "jpg"))
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.media_error, e.message.orEmpty()), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun openVideo(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(container.media.uriFor(file), "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
    }

    fun launchCapture() {
        val file = container.media.newCaptureFile(if (isVideo) "mp4" else "jpg")
        pending = file.path
        val uri = container.media.uriFor(file)
        if (isVideo) captureVideo.launch(uri) else takePicture.launch(uri)
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCapture() else Toast.makeText(context, R.string.perm_camera_denied, Toast.LENGTH_LONG).show()
    }

    fun requestCapture() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCapture()
        else rationale = true
    }
    LaunchedEffect(captureTrigger) { if (captureTrigger > 0) requestCapture() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { m ->
                    val file = File(mediaDir, m.file.substringAfterLast('/'))
                    Thumbnail(file, isVideo, { if (isVideo) openVideo(file) else viewing = m }, if (enabled && !referenceOnly) ({ deleting = m }) else null, if (referenceOnly) 48.dp else 84.dp)
                }
            }
        }
        if (canAdd) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::requestCapture) {
                    Text(
                        if (referenceOnly) stringResource(R.string.media_capture_ref, field.id)
                        else if (captureLabel != null) captureLabel
                        else stringResource(if (isVideo) R.string.media_record else R.string.media_capture),
                    )
                }
                if (!referenceOnly) OutlinedButton(onClick = {
                    pick.launch(PickVisualMediaRequest(if (isVideo) ActivityResultContracts.PickVisualMedia.VideoOnly else ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(R.string.media_library)) }
            }
        }
    }

    if (rationale) {
        PermissionRationale(
            message = stringResource(R.string.perm_camera_message),
            onAllow = { rationale = false; cameraPermission.launch(Manifest.permission.CAMERA) },
            onDismiss = { rationale = false },
        )
    }
    deleting?.let { m ->
        ConfirmDialog(
            title = stringResource(R.string.media_delete_title),
            message = m.file.substringAfterLast('/'),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                setItems(items.filter { it.file != m.file })
                File(mediaDir, m.file.substringAfterLast('/')).delete()
            },
            onDismiss = { deleting = null },
        )
    }
    viewing?.let { m ->
        val file = File(mediaDir, m.file.substringAfterLast('/'))
        if (!isVideo) {
            Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(color = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.fillMaxSize()) {
                    Box {
                        AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        IconButton(onClick = { viewing = null }, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = androidx.compose.ui.graphics.Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(file: File, video: Boolean, onOpen: () -> Unit, onDelete: (() -> Unit)?, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen)) {
        if (video) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(36.dp))
                    Text(file.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        } else {
            AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.media_delete_title), tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
