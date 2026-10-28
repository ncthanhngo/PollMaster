package com.ncthanhngo.pollmaster.ui.editor

import com.ncthanhngo.pollmaster.ui.theme.accentTonalColors
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import com.ncthanhngo.pollmaster.ui.common.FirstFitText
import com.ncthanhngo.pollmaster.ui.templateeditor.PreviewTopBar
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

/** One list item of a section: a heading, consecutive fields drawn as one grouped block, a table on its own, a round group. */
private sealed interface Row0 {
    val key: String
    data class Heading(val heading: HeadingItem) : Row0 { override val key get() = heading.id }
    data class Group(val fields: List<Field>) : Row0 { override val key get() = "g-" + fields.first().id }
    data class Table(val table: TableItem) : Row0 { override val key get() = table.id }
    data class Round(val heading: HeadingItem, val fields: List<Field>) : Row0 { override val key get() = heading.id }
    data object ResultsTitle : Row0 { override val key get() = "results-title" }
    data class Results(val fields: List<Field>) : Row0 { override val key get() = "results" }
}

private fun Row0.holds(id: String?): Boolean = when (this) {
    is Row0.Heading -> heading.id == id
    is Row0.Group -> fields.any { it.id == id }
    is Row0.Table -> table.id == id
    is Row0.Round -> fields.any { it.id == id }
    Row0.ResultsTitle -> false
    is Row0.Results -> fields.any { it.id == id }
}

/** Fields that follow each other (after a heading or at the start) share one block; tables and round groups break it. */
private fun segments(ordered: List<Item>, groups: Map<String, List<Field>>): List<Row0> {
    val grouped = groups.values.flatten().map { it.id }.toSet()
    val out = mutableListOf<Row0>()
    for (item in ordered) {
        if (item.id in grouped) continue
        when (item) {
            is HeadingItem -> out += if (item.id in groups) Row0.Round(item, groups.getValue(item.id)) else Row0.Heading(item)
            is TableItem -> out += Row0.Table(item)
            is Field -> {
                val last = out.lastOrNull()
                if (last is Row0.Group) out[out.lastIndex] = Row0.Group(last.fields + item) else out += Row0.Group(listOf(item))
            }
        }
    }
    return out
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
        segments(ordered, RoundEntryRules.groups(ordered)) +
            if (results.isEmpty()) emptyList() else listOf(Row0.ResultsTitle, Row0.Results(results))
    }
    val listState: LazyListState = rememberLazyListState()

    LaunchedEffect(focus) {
        val at = rows.indexOfFirst { it.holds(focus) }
        if (at >= 0) listState.scrollToItem(at + (if (section?.note != null) 1 else 0))
    }
    // Leaving a section backs the record up when something changed.
    DisposableEffect(recordId, sectionId) { onDispose { container.backup.requestIfNeeded(recordId) } }

    fun track(id: String) = controller.setPosition(sectionId, id)

    Scaffold(
        topBar = {
            PreviewTopBar(recordId) { TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                actions = { if (!env.reviewMode) SectionMenu(sectionId, controller) },
            ) }
        },
        // Pinned: previous / check (k/N) / next, so the next section never needs a scroll to the end.
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val touch = Modifier.weight(1f).then(Modifier.heightIn(min = if (LocalUiPrefs.current.oneHand) TOUCH_ONE_HAND else 48.dp))
                    val pad = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    // one line per button at any font size: words when they fit, else the arrow / counter alone
                    val prev = stringResource(R.string.section_prev)
                    val next = stringResource(R.string.section_next)
                    val count = "${index + 1}/${ids.size}"
                    FilledTonalButton(modifier = touch.semantics { contentDescription = prev }, contentPadding = pad, colors = accentTonalColors(), enabled = index > 0, onClick = { onSwitchSection(ids[index - 1]) }) {
                        FirstFitText(listOf("‹ $prev", "‹"))
                    }
                    FilledTonalButton(modifier = touch, contentPadding = pad, colors = accentTonalColors(), onClick = onReview) {
                        FirstFitText(listOf(stringResource(R.string.action_review) + " " + count, count))
                    }
                    FilledTonalButton(modifier = touch.semantics { contentDescription = next }, contentPadding = pad, colors = accentTonalColors(), enabled = index in 0 until ids.lastIndex, onClick = { onSwitchSection(ids[index + 1]) }) {
                        FirstFitText(listOf("$next ›", "›"))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            section?.note?.let { note -> item(key = "note") { Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(rows, key = { it.key }) { row ->
                val field: @Composable (Field) -> Unit = { f ->
                    BringIntoViewWhen(f.id == focus) { Box(Modifier.trackTouch { track(f.id) }) { FieldCard(f, env.scope, env) } }
                }
                when (row) {
                    is Row0.Heading -> Box(Modifier.trackTouch { track(row.heading.id) }) { SectionHeading(row.heading) }
                    is Row0.Group -> FieldGroupCard(row.fields) { field(it) }
                    is Row0.Table -> Box(Modifier.trackTouch { track(row.table.id) }) {
                        TableCard(row.table, env) { rowId -> track(row.table.id); onOpenRow(row.table.id, rowId) }
                    }
                    is Row0.Round -> RoundGroup(row.heading, row.fields, env, focus, ::track)
                    Row0.ResultsTitle -> Text(
                        stringResource(R.string.results_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                    is Row0.Results -> FieldGroupCard(row.fields) { field(it) }
                }
            }
        }
    }
}

/** Group title (bold, larger) above the block of fields that follow it. */
@Composable
internal fun SectionHeading(heading: HeadingItem) {
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(heading.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        heading.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Scrolls [content] into view when [active] (the focused field inside a grouped block, after the list jumped to the block). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BringIntoViewWhen(active: Boolean, content: @Composable () -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(active) { if (active) requester.bringIntoView() }
    Box(Modifier.bringIntoViewRequester(requester)) { content() }
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
