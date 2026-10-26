package com.ncthanhngo.pollmaster.ui.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.fieldCount
import com.ncthanhngo.pollmaster.core.model.tables
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.IconBadge
import com.ncthanhngo.pollmaster.ui.common.StatTile
import com.ncthanhngo.pollmaster.ui.common.StatusChip
import com.ncthanhngo.pollmaster.ui.projects.ProjectAvatar
import com.ncthanhngo.pollmaster.ui.theme.warnColor

/** Library row, two lines never wrapped: title, then "BB-TDP-01 · v1.2 · 180 trường" + avatars of the projects using it. */
@Composable
internal fun TemplateCard(t: Template, outdated: Boolean, users: List<Project>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AppCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Filled.Description, tint = if (outdated) scheme.onSurfaceVariant else scheme.primary, size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val meta = "${t.id} · v${t.version} · " + stringResource(R.string.template_fields_pill, t.fieldCount())
                    val old = stringResource(R.string.template_old_pill)
                    val warn = warnColor()
                    Text(
                        buildAnnotatedString {
                            append(meta)
                            if (outdated) withStyle(SpanStyle(color = warn)) { append(" · $old") }
                        },
                        Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (users.isNotEmpty()) {
                        val label = stringResource(R.string.template_used_by_count, users.size, users.joinToString { it.name })
                        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy((-3).dp)) {
                            users.take(3).forEach { ProjectAvatar(it, 16.dp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TemplateSummary(template: Template) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Filled.Description, size = 44.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(template.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                template.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(template.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusChip("v${template.version}", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${template.sections.size + 1}", stringResource(R.string.template_stat_sections), Modifier.weight(1f))
            StatTile("${template.fieldCount()}", stringResource(R.string.template_stat_fields), Modifier.weight(1f))
            StatTile("${template.tables().size}", stringResource(R.string.template_stat_tables), Modifier.weight(1f))
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
internal fun SectionRow(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, size = 28.dp)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}
