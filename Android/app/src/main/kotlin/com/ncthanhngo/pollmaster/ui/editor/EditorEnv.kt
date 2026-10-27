package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.ncthanhngo.pollmaster.core.editor.EditorController
import com.ncthanhngo.pollmaster.core.format.ValueFormatter
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.LocalContainer
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.tables
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.validation.Issue
import com.ncthanhngo.pollmaster.core.validation.Warning
import com.ncthanhngo.pollmaster.core.validation.WarningChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/** Read/write access to field values for one scope (record level or one table row). */
interface ValueScope {
    fun get(id: String): FieldValue?
    fun update(id: String, f: (FieldValue) -> FieldValue)

    /** Template-defined fixed data for a column of a fixed table row. */
    fun fixed(id: String): JsonElement? = null

    /** Key used to match validation issues. */
    fun key(id: String): String

    /** Name used for media files of this field. */
    fun mediaKey(id: String): String = id

    /** Other fields referenced by part showWhen conditions. */
    fun lookup(id: String): FieldValue?
}

class RecordScope(private val controller: EditorController, private val record: Record) : ValueScope {
    override fun get(id: String) = record.values[id]
    override fun update(id: String, f: (FieldValue) -> FieldValue) = controller.updateValue(id, f)
    override fun key(id: String) = id
    override fun lookup(id: String) = record.values[id]
}

class RowScope(
    private val controller: EditorController,
    private val record: Record,
    private val tableId: String,
    private val row: Row,
    private val fixed: Map<String, JsonElement>,
) : ValueScope {
    override fun get(id: String) = row.values[id]
    override fun update(id: String, f: (FieldValue) -> FieldValue) = controller.updateRowValue(tableId, row.id, id, f)
    override fun fixed(id: String) = fixed[id]
    override fun key(id: String) = "$tableId/${row.id}/$id"
    override fun mediaKey(id: String) = "$tableId-$id"
    override fun lookup(id: String) = row.values[id] ?: record.values[id]
}

/** Per-composition snapshot used by field cards. */
class EditorEnv(
    val controller: EditorController,
    val record: Record,
    private val issues: Map<String, Issue>,
    private val showErrors: Boolean,
    private val touched: Set<String>,
    private val warnings: Map<String, Warning> = emptyMap(),
    /** Metres from each GPS field to its planned point (see [WarningChecker.siteDistances]). */
    private val siteDistances: Map<String, Double> = emptyMap(),
    val checker: WarningChecker = WarningChecker(controller.template),
    val sites: List<Site> = emptyList(),
    val reviewMode: Boolean = false,
    /** Project of the record, whose list of planned sites is [sites]. */
    val projectId: String? = null,
    /** View-only by permission (`controller.readOnlyNote`): every card is read-only like review mode, but takes no notes. */
    val readOnly: Boolean = false,
) {
    /** Fields that use a photo field as evidence: photo field id -> ids of the referencing fields (template order). */
    val photoUsers: Map<String, List<String>> by lazy {
        val fields = controller.template.allFields() +
            controller.template.tables().flatMap { it.columns }
        fields.filter { it.photoRef != null }.groupBy({ it.photoRef!! }, { it.id })
    }

    fun warning(key: String): Warning? = warnings[key]

    fun siteDistance(fieldId: String): Double? = siteDistances[fieldId]

    val engine by lazy { controller.engine(record) }
    val formatter = ValueFormatter { record.values[it] }
    val scope = RecordScope(controller, record)

    /** The issue to display for [key], honoring "only after review or after the field was touched". */
    fun error(key: String): Issue? = issues[key]?.takeIf { showErrors || key in touched }

    fun issueFor(key: String): Issue? = issues[key]
}

/** Record state that updates synchronously with edits (needed so text fields never see stale values). */
@Composable
fun rememberRecordState(controller: EditorController): State<Record> {
    val state = remember(controller) { mutableStateOf(controller.record.value) }
    DisposableEffect(controller) {
        state.value = controller.record.value
        val remove = controller.addListener { state.value = it }
        onDispose { remove() }
    }
    return state
}

/** Validation issues and warnings of one record state; recomputed after typing pauses, not on every keystroke. */
private class Derived(
    val source: Record,
    val issues: Map<String, Issue>,
    val warnings: Map<String, Warning>,
    val siteDistances: Map<String, Double>,
)

private const val DERIVED_DELAY_MS = 200L

private fun derive(controller: EditorController, checker: WarningChecker, record: Record): Derived {
    val distances = checker.siteDistances(record)
    return Derived(
        record,
        controller.issues(record).groupBy { it.key }.mapValues { it.value.first() },
        checker.check(record, distances).groupBy { it.key }.mapValues { it.value.first() },
        distances,
    )
}

@Composable
fun rememberEditorEnv(controller: EditorController): EditorEnv {
    val container = LocalContainer.current
    val allSites by container.sites.all.collectAsState()
    val record by rememberRecordState(controller)
    val projectId = record.projectId
    val sites = allSites[projectId].orEmpty()
    val showErrors by controller.showErrors.collectAsState()
    val touched by controller.touched.collectAsState()
    val reviewMode by controller.reviewMode.collectAsState()
    val readOnly = controller.readOnlyNote.collectAsState().value != null
    val checker = remember(controller, sites) { WarningChecker(controller.template, sites) }
    // Values stay immediate (record); the costly scans catch up 200 ms after the last edit. A new screen or new sites compute at once.
    var derived by remember(controller, checker) { mutableStateOf(derive(controller, checker, record)) }
    LaunchedEffect(controller, checker, record) {
        if (derived.source !== record) {
            delay(DERIVED_DELAY_MS)
            derived = withContext(Dispatchers.Default) { derive(controller, checker, record) }
        }
    }
    return remember(controller, record, derived, showErrors, touched, sites, reviewMode, readOnly, projectId) {
        EditorEnv(
            controller, record, derived.issues, showErrors, touched, derived.warnings, derived.siteDistances,
            checker, sites, reviewMode || readOnly, projectId, readOnly,
        )
    }
}
