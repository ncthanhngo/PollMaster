package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.R
import com.ncthanhngo.pollmaster.core.editor.RoundEntryRules
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.HeadingItem
import com.ncthanhngo.pollmaster.core.model.Item
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.validation.HEADER_SECTION_ID
import com.ncthanhngo.pollmaster.ui.common.BackButton
import com.ncthanhngo.pollmaster.ui.theme.LocalUiPrefs
import com.ncthanhngo.pollmaster.ui.theme.TOUCH_ONE_HAND

/** Moves the computed fields of a section that has tables into a "Kết quả" block after the last table. */
private fun arrange(items: List<Item>): Pair<List<Item>, List<Field>> {
    if (items.none { it is TableItem }) return items to emptyList()
    val results = items.filterIsInstance<Field>().filter { it.type == "computed" }
    val rest = items.filterNot { it in results }
    val lastTable = rest.indexOfLast { it is TableItem }
    return rest.take(lastTable + 1) + rest.drop(lastTable + 1) to results
}

private sealed interface Row0 {
    data class Entry(val item: Item) : Row0
    data class Round(val heading: HeadingItem, val fields: List<Field>) : Row0
    data object ResultsTitle : Row0
    data class Result(val field: Field) : Row0
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionScreen(
    recordId: String,
    sectionId: String,
    focus: String?,
    onBack: () -> Unit,
    onSwitchSection: (String) -> Unit,
    onOpenRow: (tableId: String, rowId: String) -> Unit,
    onReview: () -> Unit,
) {
    val container = LocalContainer.current
    val controller = remember(recordId) { container.records.editor(recordId) }
    if (controller == null) {
        EditorMissing(onBack)
        return
    }
    val template = controller.template
    val env = rememberEditorEnv(controller)
    val ids = remember(template) { listOf(HEADER_SECTION_ID) + template.sections.map { it.id } }
    val index = ids.indexOf(sectionId)
    val section = template.sections.firstOrNull { it.id == sectionId }
    val title = section?.title ?: stringResource(R.string.header_section)
    val raw: List<Item> = section?.items ?: template.header
    val (ordered, results) = remember(raw) { arrange(raw) }
    val rows = remember(ordered, results) {
        val groups = RoundEntryRules.groups(ordered)
        val grouped = groups.values.flatten().map { it.id }.toSet()
        ordered.filterNot { it.id in grouped }.map<Item, Row0> { item ->
            if (item is HeadingItem && item.id in groups) Row0.Round(item, groups.getValue(item.id)) else Row0.Entry(item)
        } + if (results.isEmpty()) emptyList() else listOf(Row0.ResultsTitle) + results.map { Row0.Result(it) }
    }
    val listState: LazyListState = rememberLazyListState()

    LaunchedEffect(focus) {
        val at = rows.indexOfFirst { (it is Row0.Entry && it.item.id == focus) || (it is Row0.Round && it.fields.any { f -> f.id == focus }) }
        if (at >= 0) listState.scrollToItem(at + (if (section?.note != null) 1 else 0))
    }
    // Leaving a section backs the record up when something changed.
    DisposableEffect(recordId, sectionId) { onDispose { container.backup.requestIfNeeded(recordId) } }

    fun track(id: String) = controller.setPosition(sectionId, id)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = { if (!env.reviewMode) SectionMenu(sectionId, controller) },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val oneHand = LocalUiPrefs.current.oneHand
                    val touch = if (oneHand) Modifier.heightIn(min = TOUCH_ONE_HAND) else Modifier
                    TextButton(modifier = touch, enabled = index > 0, onClick = { onSwitchSection(ids[index - 1]) }) { Text("‹ " + stringResource(R.string.section_prev)) }
                    // One-hand mode: the review shortcut sits in the middle of the bottom bar.
                    if (oneHand) OutlinedButton(modifier = touch, onClick = onReview) { Text(stringResource(R.string.action_review)) }
                    else Text("${index + 1}/${ids.size}", style = MaterialTheme.typography.titleSmall)
                    TextButton(modifier = touch, enabled = index in 0 until ids.lastIndex, onClick = { onSwitchSection(ids[index + 1]) }) { Text(stringResource(R.string.section_next) + " ›") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            section?.note?.let { note -> item(key = "note") { Text(note, style = MaterialTheme.typography.bodyMedium) } }
            items(rows, key = { r ->
                when (r) {
                    is Row0.Entry -> r.item.id
                    is Row0.Round -> r.heading.id
                    Row0.ResultsTitle -> "results-title"
                    is Row0.Result -> "result-" + r.field.id
                }
            }) { row ->
                when (row) {
                    is Row0.Entry -> Box(Modifier.trackTouch { track(row.item.id) }) {
                        when (val item = row.item) {
                            is Field -> FieldCard(item, env.scope, env)
                            is HeadingItem -> Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                            is TableItem -> TableCard(item, env) { rowId -> track(item.id); onOpenRow(item.id, rowId) }
                        }
                    }
                    is Row0.Round -> RoundGroup(row.heading, row.fields, env, focus, ::track)
                    Row0.ResultsTitle -> Text(
                        stringResource(R.string.results_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    is Row0.Result -> FieldCard(row.field, env.scope, env)
                }
            }
        }
    }
}

/** Reports touches without consuming them (so inputs still work). */
internal fun Modifier.trackTouch(onTouch: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.pressed && !it.previousPressed }) onTouch()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorMissing(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = {}, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Text(stringResource(R.string.record_template_missing), Modifier.padding(padding).padding(24.dp))
    }
}
