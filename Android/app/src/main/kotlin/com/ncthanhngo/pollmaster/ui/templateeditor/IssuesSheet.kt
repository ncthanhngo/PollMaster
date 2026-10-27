package com.ncthanhngo.pollmaster.ui.templateeditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.templateeditor.TemplateIssue
import com.ncthanhngo.pollmaster.ui.common.FirstFitText
import com.ncthanhngo.pollmaster.ui.common.StatusChip
import com.ncthanhngo.pollmaster.ui.theme.okColor
import com.ncthanhngo.pollmaster.ui.theme.warnColor

/** "2 lỗi · 1 cảnh báo" pill on the top bar; red with errors, amber with warnings only, green when clean. */
@Composable
fun IssuesChip(issues: List<TemplateIssue>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val errors = issues.count { it.severity == TemplateIssue.Severity.ERROR }
    val warnings = issues.size - errors
    val (container, content) = when {
        errors > 0 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        warnings > 0 -> warnColor().copy(alpha = 0.18f) to warnColor()
        else -> okColor().copy(alpha = 0.16f) to okColor()
    }
    val text = when {
        errors > 0 && warnings > 0 -> stringResource(R.string.te_issues_chip, errors, warnings)
        errors > 0 -> stringResource(R.string.te_issues_chip_errors, errors)
        warnings > 0 -> stringResource(R.string.te_issues_chip_warnings, warnings)
        else -> stringResource(R.string.te_issues_none)
    }
    StatusChip(text, container, content, modifier.clickable(onClick = onClick))
}

/** Every error and warning; a tap closes the sheet and jumps to the place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesSheet(issues: List<TemplateIssue>, onJump: (TemplateIssue) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(stringResource(R.string.te_issues_title), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            if (issues.isEmpty()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = okColor())
                    Text(stringResource(R.string.te_issues_none), Modifier.padding(start = 12.dp))
                }
            }
            LazyColumn {
                itemsIndexed(issues, key = { i, it -> "$i|${it.code}|${it.path}" }) { _, issue ->
                    val error = issue.severity == TemplateIssue.Severity.ERROR
                    Row(Modifier.fillMaxWidth().clickable { onJump(issue) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Icon(if (error) Icons.Filled.ErrorOutline else Icons.Filled.WarningAmber, null, tint = if (error) MaterialTheme.colorScheme.error else warnColor())
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(issue.message, style = MaterialTheme.typography.bodyMedium)
                            FirstFitText(listOf(issue.path), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
