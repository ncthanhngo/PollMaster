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
import com.ncthanhngo.pollmaster.ui.projects.ProjectDot

/** Library row: icon, title, version / field-count pills, projects using it. */
@Composable
internal fun TemplateCard(t: Template, outdated: Boolean, users: List<Project>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AppCard(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Filled.Description, tint = if (outdated) scheme.onSurfaceVariant else scheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.id, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    StatusChip("v${t.version}", scheme.primaryContainer, scheme.onPrimaryContainer)
                    StatusChip(stringResource(R.string.template_fields_pill, t.fieldCount()), scheme.surfaceVariant, scheme.onSurfaceVariant)
                    if (outdated) StatusChip(stringResource(R.string.template_old_pill), scheme.tertiaryContainer, scheme.onTertiaryContainer)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    users.take(4).forEach { ProjectDot(it.color, 10.dp) }
                    Text(
                        if (users.isEmpty()) stringResource(R.string.template_unused) else stringResource(R.string.template_used_in, users.joinToString { it.code }),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TemplateSummary(template: Template) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Filled.Description, size = 56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(template.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                template.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        IconBadge(icon, size = 32.dp)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
