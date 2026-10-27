package com.ncthanhngo.pollmaster.ui.projects

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectStats

/** Identification colour of a project (hex string), or the theme primary when unset or unreadable. */
@Composable
fun projectColor(hex: String?): Color =
    hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.primary

/** Project identity: its photo, else white initials on the project colour (rounded square, like an app icon). */
@Composable
fun ProjectAvatar(project: Project?, size: Dp = 40.dp) {
    val avatars by LocalContainer.current.projects.avatars.collectAsState()
    val shape = RoundedCornerShape(size * 0.28f)
    val photo = project?.let { avatars[it.id] }
    if (photo != null) {
        Image(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(project?.let { projectColor(it.color) } ?: Color.Gray), contentAlignment = Alignment.Center) {
            Text(
                project?.let { Project.initials(it.name) } ?: "∗",
                color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1,
                // tight, centred line box: the default font padding pushes small initials below the middle
                style = with(LocalDensity.current) { (size * 0.4f).toSp() }.let { fs ->
                    TextStyle(
                        fontSize = fs, lineHeight = fs, platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                    )
                },
            )
        }
    }
}

/** "2 nháp · 1 cần sửa · 5 hoàn thành": one short line everywhere (cards, switcher), same split as the detail statistics. */
@Composable
fun projectCounts(stats: ProjectStats): String {
    val parts = listOfNotNull(
        stringResource(R.string.count_draft, stats.draft - stats.returned),
        stats.returned.takeIf { it > 0 }?.let { stringResource(R.string.count_returned, it) },
        stringResource(R.string.count_completed, stats.completed),
    )
    return parts.joinToString(" · ")
}

/**
 * Dialog listing [projects] (name, code). With [multi] the user ticks any number and confirms; otherwise a tap on a row
 * confirms at once. [message] is shown above the list.
 */
@Composable
fun ProjectChoiceDialog(
    title: String,
    projects: List<Project>,
    multi: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<Project>) -> Unit,
    message: String? = null,
    confirmLabel: String = stringResource(R.string.action_add),
    dismissLabel: String = stringResource(R.string.action_cancel),
) {
    var ticked by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                LazyColumn {
                    items(projects, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (multi) ticked = if (p.id in ticked) ticked - p.id else ticked + p.id else onConfirm(listOf(p))
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (multi) Checkbox(checked = p.id in ticked, onCheckedChange = null)
                            ProjectAvatar(p, 32.dp)
                            Column {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                Text(p.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = if (multi) {
            { TextButton(enabled = ticked.isNotEmpty(), onClick = { onConfirm(projects.filter { it.id in ticked }) }) { Text(confirmLabel) } }
        } else {
            {}
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** ERP project kinds in list order with their section title ("Dự án công ty", "Nội bộ"). */
val projectKindSections: List<Pair<String, Int>> = listOf("company" to R.string.project_kind_company, "internal" to R.string.project_kind_internal)
