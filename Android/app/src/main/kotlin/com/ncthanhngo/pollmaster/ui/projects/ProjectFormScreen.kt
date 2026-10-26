package com.ncthanhngo.pollmaster.ui.projects

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectCodes
import com.ncthanhngo.pollmaster.core.project.ProjectDraft
import com.ncthanhngo.pollmaster.core.project.ProjectException
import com.ncthanhngo.pollmaster.ui.common.BackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Create ([projectId] null) or edit a project: avatar, name, code (auto from the name until edited), client, description, colour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFormScreen(projectId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val existing = remember(projectId) { container.projects.get(projectId) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var code by rememberSaveable { mutableStateOf(existing?.code.orEmpty()) }
    var codeEdited by rememberSaveable { mutableStateOf(existing != null) }
    var client by rememberSaveable { mutableStateOf(existing?.client.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    val all by container.projects.projects.collectAsState()
    var color by rememberSaveable { mutableStateOf(existing?.color ?: Project.nextColor(all.mapNotNull { it.color })) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val avatars by container.projects.avatars.collectAsState()
    // picked photo (256 px JPEG) not saved yet; dropPhoto = "Bỏ ảnh" on a project that has one
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var dropPhoto by remember { mutableStateOf(false) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            photo = runCatching { withContext(Dispatchers.IO) { container.media.squareJpeg(container.media.copyToCapture(uri, "jpg")) } }
                .getOrElse { e -> error = e.message; null }
            if (photo != null) dropPhoto = false
        }
    }
    val preview = remember(photo) { photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
        ?: existing?.takeIf { !dropPhoto }?.let { avatars[it.id] }
    val taken = all.filter { it.id != projectId }.map { it.code }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existing == null) R.string.project_form_new else R.string.project_form_edit)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AvatarPicker(
                preview, name, color,
                onPick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = { photo = null; dropPhoto = true },
            )
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (!codeEdited) code = ProjectCodes.generate(it, taken).takeIf { c -> it.isNotBlank() } ?: ""
                },
                label = { Text(stringResource(R.string.project_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(ProjectCodes.MAX_LENGTH); codeEdited = true },
                label = { Text(stringResource(R.string.project_code)) },
                supportingText = { Text(stringResource(R.string.project_code_hint)) },
                isError = code.isNotEmpty() && (!ProjectCodes.isValid(code) || code in taken),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            OutlinedTextField(client, { client = it }, label = { Text(stringResource(R.string.project_client)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.project_description)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.project_color), style = MaterialTheme.typography.titleSmall)
            Project.COLORS.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { hex ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(projectColor(hex)).clickable { color = hex },
                            contentAlignment = Alignment.Center,
                        ) { if (hex == color) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White) }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                enabled = name.isNotBlank() && code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val draft = ProjectDraft(name, code, client, description, color)
                    scope.launch {
                        try {
                            val id = if (existing == null) container.projects.create(draft).id
                            else existing.id.also { container.projects.update(it, draft) }
                            if (photo != null || dropPhoto) container.projects.setAvatar(id, photo)
                            onSaved(id)
                        } catch (e: ProjectException) {
                            error = e.message
                        }
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

/** Big avatar preview (photo, else initials on the chosen colour) with "Chọn ảnh" / "Bỏ ảnh". */
@Composable
private fun AvatarPicker(photo: ImageBitmap?, name: String, color: String, onPick: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val shape = RoundedCornerShape(24.dp)
        if (photo != null) {
            Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp).clip(shape))
        } else {
            Box(Modifier.size(88.dp).clip(shape).background(projectColor(color)), contentAlignment = Alignment.Center) {
                if (name.isBlank()) Icon(Icons.Filled.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                else Text(Project.initials(name), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onPick) {
                Icon(Icons.Filled.Photo, contentDescription = null)
                Text(stringResource(if (photo != null) R.string.project_avatar_change else R.string.project_avatar_pick), Modifier.padding(start = 6.dp))
            }
            if (photo != null) OutlinedButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = null)
                Text(stringResource(R.string.project_avatar_remove), Modifier.padding(start = 6.dp))
            }
        }
    }
}
