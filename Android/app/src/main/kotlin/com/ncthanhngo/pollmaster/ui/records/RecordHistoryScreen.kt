package com.ncthanhngo.pollmaster.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.format.displayDateTimeDashed
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.sync.ChangeLabels
import com.ncthanhngo.pollmaster.core.sync.FieldChange
import com.ncthanhngo.pollmaster.core.sync.RecordChange
import com.ncthanhngo.pollmaster.ui.common.AppCard
import com.ncthanhngo.pollmaster.ui.common.BackButton
import kotlinx.coroutines.CancellationException
import java.io.IOException

private sealed interface History {
    data object Loading : History
    data class Failed(val message: String) : History
    data class Loaded(val log: List<RecordChange>) : History
}

/** "Lịch sử sửa" of a record (ux-spec N): the ERP's change log, newest save first, one line per changed field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordHistoryScreen(recordId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    var state by remember { mutableStateOf<History>(History.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var record by remember { mutableStateOf<Record?>(null) }
    var template by remember { mutableStateOf<Template?>(null) }
    val notSynced = stringResource(R.string.history_not_synced)
    val offline = stringResource(R.string.history_offline)

    LaunchedEffect(recordId, attempt) {
        state = History.Loading
        val r = container.records.load(recordId)
        record = r
        template = r?.let { container.templates.find(it.templateId, it.templateVersion) }
        val pid = r?.projectId
        state = when {
            r == null || pid == null -> History.Failed("")
            r.serverRev == null -> History.Failed(notSynced)
            else -> try {
                History.Loaded(container.sync.changes(pid, recordId))
            } catch (e: CancellationException) { throw e } catch (e: IOException) { History.Failed(offline) } catch (e: Exception) { History.Failed(e.message.orEmpty()) }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.record_history)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                History.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.history_loading), Modifier.padding(top = 8.dp))
                }
                is History.Failed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.history_failed), style = MaterialTheme.typography.titleMedium)
                    if (s.message.isNotEmpty()) Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.history_retry)) }
                }
                is History.Loaded ->
                    if (s.log.isEmpty()) Text(stringResource(R.string.history_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(s.log, key = { it.rev }) { entry -> Entry(entry, template, record) }
                    }
            }
        }
    }
}

@Composable
private fun Entry(e: RecordChange, template: Template?, record: Record?) {
    val who = e.changedByName.ifEmpty { stringResource(R.string.history_unknown_user) }
    val title = listOfNotNull(who, displayDateTimeDashed(e.changedAt), if (e.source == "legacy") stringResource(R.string.history_legacy) else null).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (e.created) Text(stringResource(R.string.history_created))
                e.changes.forEach { Line(it, template, record) }
            }
        }
    }
}

@Composable
private fun Line(c: FieldChange, template: Template?, record: Record?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(ChangeLabels.label(c.path, c.label, template, record), fontWeight = FontWeight.Medium)
        Text(
            stringResource(R.string.history_arrow, ChangeLabels.valueText(c.path, c.old, template), ChangeLabels.valueText(c.path, c.new, template)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
