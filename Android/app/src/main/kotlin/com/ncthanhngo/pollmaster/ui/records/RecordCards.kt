package com.ncthanhngo.pollmaster.ui.records

import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.automirrored.filled.Undo
import com.ncthanhngo.pollmaster.ui.common.IconBadge
import com.ncthanhngo.pollmaster.ui.theme.attentionColor
import com.ncthanhngo.pollmaster.ui.theme.okColor
import com.ncthanhngo.pollmaster.ui.theme.warnColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDayMonthTime
import com.ncthanhngo.pollmaster.data.RecordItem
import com.ncthanhngo.pollmaster.core.storage.StoredRecord
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.FirstFitText
import com.ncthanhngo.pollmaster.ui.common.StatusChip
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.ui.projects.ProjectAvatar

/** Three lines, none wrapping: title · project avatar + name (or code) and date · status, backup flag and progress. */
@Composable
internal fun RecordCard(item: RecordItem, project: Project?, onMove: () -> Unit, onOpen: () -> Unit, onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    AppCard(Modifier.clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(item)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val style = MaterialTheme.typography.bodyMedium
                    if (project != null) {
                        ProjectAvatar(project, 16.dp)
                        // full name when it fits, else the short code, never a cut-off name
                        FirstFitText(listOf(project.name, project.code), Modifier.weight(1f, fill = false), style = style, color = secondary)
                        Text("·", style = style, color = secondary)
                    }
                    Text(displayDayMonthTime(item.record.updatedAt), style = style, color = secondary, maxLines = 1, softWrap = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordChips(item)
                    val p = item.progress
                    if (p.total > 0) {
                        LinearProgressIndicator(
                            progress = { p.filled.toFloat() / p.total }, modifier = Modifier.weight(1f),
                            color = if (item.record.isCompleted) okColor() else MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text("${p.filled}/${p.total}", style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, softWrap = false)
                    }
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_duplicate)) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.record_move)) }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** 48x48 photo of the record, or a status icon (draft, returned, completed) when there is no photo yet. */
@Composable
private fun Thumb(item: RecordItem) {
    val file = item.thumbnail
    if (file != null) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
            AsyncImage(model = file, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        return
    }
    val r = item.record
    when {
        r.isCompleted -> IconBadge(Icons.Filled.Verified, tint = okColor(), size = 48.dp)
        r.returned -> IconBadge(Icons.AutoMirrored.Filled.Undo, tint = attentionColor(), size = 48.dp)
        else -> IconBadge(Icons.Filled.Description, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 48.dp)
    }
}

/** One status chip ("Cần sửa" replaces "Nháp" on a returned record), then a cloud-off icon while the record is not backed up. */
@Composable
internal fun RecordChips(item: RecordItem) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalUiPrefs.current.dark
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            item.record.isCompleted ->
                StatusChip(stringResource(R.string.status_completed), if (dark) Color(0xFF1E4D33) else Color(0xFFD7F0DF), if (dark) Color(0xFFBFF0D0) else Color(0xFF0E4A25))
            item.record.returned ->
                StatusChip(stringResource(R.string.status_returned), if (dark) Color(0xFF5A3300) else Color(0xFFFFE0B8), if (dark) Color(0xFFFFD9A8) else Color(0xFF6A3A00))
            else -> StatusChip(stringResource(R.string.status_draft), scheme.surfaceVariant, scheme.onSurfaceVariant)
        }
        if (item.needsBackup) {
            Icon(Icons.Filled.CloudOff, contentDescription = stringResource(R.string.status_not_backed_up), tint = warnColor(), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun BrokenCard(b: StoredRecord.Broken, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    AppCard(Modifier.clickable(onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.id, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(b.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            StatusChip(stringResource(R.string.record_read_error), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
