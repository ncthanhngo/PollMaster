package com.ncthanhngo.pollmaster.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    val checker: WarningChecker = WarningChecker(controller.template),
    val sites: List<Site> = emptyList(),
    val reviewMode: Boolean = false,
) {
    /** Fields that use a photo field as evidence: photo field id -> ids of the referencing fields (template order). */
    val photoUsers: Map<String, List<String>> by lazy {
        val fields = controller.template.allFields() +
            controller.template.tables().flatMap { it.columns }
        fields.filter { it.photoRef != null }.groupBy({ it.photoRef!! }, { it.id })
    }

    fun warning(key: String): Warning? = warnings[key]

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

@Composable
fun rememberEditorEnv(controller: EditorController): EditorEnv {
    val sites by LocalContainer.current.sites.sites.collectAsState()
    val record by rememberRecordState(controller)
    val showErrors by controller.showErrors.collectAsState()
    val touched by controller.touched.collectAsState()
    val reviewMode by controller.reviewMode.collectAsState()
    return remember(controller, record, showErrors, touched, sites, reviewMode) {
        val checker = WarningChecker(controller.template, sites)
        EditorEnv(
            controller, record, controller.issues(record).groupBy { it.key }.mapValues { it.value.first() }, showErrors, touched,
            checker.check(record).groupBy { it.key }.mapValues { it.value.first() }, checker, sites, reviewMode,
        )
    }
}
