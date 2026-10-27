package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.formula.FormulaEngine
import com.ncthanhngo.pollmaster.core.formula.RecordSource
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Position
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.SignatureValue
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.findTable
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import com.ncthanhngo.pollmaster.core.model.pairedTable
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.model.normalized
import com.ncthanhngo.pollmaster.core.storage.DebouncedSaver
import com.ncthanhngo.pollmaster.core.validation.Issue
import com.ncthanhngo.pollmaster.core.validation.Progress
import com.ncthanhngo.pollmaster.core.validation.Validator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Holds the record being edited. Every mutation produces a new immutable [Record] and hands it to the
 * [DebouncedSaver] (autosave). Pure Kotlin so it can be unit-tested.
 */
class EditorController(
    val template: Template,
    initial: Record,
    private val saver: DebouncedSaver,
    private val clock: () -> String = ::nowIso,
) {
    private val _record = MutableStateFlow(initial)
    val record: StateFlow<Record> = _record.asStateFlow()

    private val _showErrors = MutableStateFlow(false)
    /** Becomes true after the user opens the review screen; field errors are then shown everywhere. */
    val showErrors: StateFlow<Boolean> = _showErrors.asStateFlow()

    private val _reviewMode = MutableStateFlow(false)
    /** Read-only inspection mode of a reviewer (session only): cards show values and take review notes. */
    val reviewMode: StateFlow<Boolean> = _reviewMode.asStateFlow()

    fun setReviewMode(on: Boolean) {
        _reviewMode.value = on
    }

    private val _touched = MutableStateFlow<Set<String>>(emptySet())
    val touched: StateFlow<Set<String>> = _touched.asStateFlow()

    private val validator = Validator(template)

    fun issues(r: Record = _record.value): List<Issue> = validator.validate(r)
    fun progress(r: Record = _record.value): Progress = validator.requiredProgress(r)
    fun sectionProgress(r: Record = _record.value) = validator.sectionProgress(r)
    fun engine(r: Record = _record.value) = FormulaEngine(RecordSource(template, r))

    fun revealErrors() {
        _showErrors.value = true
    }

    fun touch(key: String) {
        if (key !in _touched.value) _touched.update { it + key }
    }

    private val _savedAt = MutableStateFlow(initial.updatedAt)
    /** Timestamp of the last successful write ("Đã lưu HH:mm"); starts at the record's last update. */
    val savedAt: StateFlow<String> = _savedAt.asStateFlow()

    fun noteSaved() {
        _savedAt.value = clock()
    }

    private val acknowledged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Remembers that the user confirmed an out-of-range value so it is asked only once. */
    fun acknowledgeWarning(key: String) {
        acknowledged += key
    }

    fun isWarningAcknowledged(key: String): Boolean = key in acknowledged

    fun flush() = saver.flush()

    /** Drops unsaved changes (the record is gone); nothing is written afterwards. */
    fun discard() = saver.cancel()

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(Record) -> Unit>()

    /** Registers a callback invoked synchronously after every change; returns a function that removes it. */
    fun addListener(listener: (Record) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun mutate(block: (Record) -> Record) {
        val updated = _record.updateAndGet { block(it).copy(updatedAt = clock()) }
        listeners.forEach { it(updated) }
        saver.submit(updated)
    }

    /** Bookkeeping change (position, backup time): saved, but neither bumps `updatedAt` nor notifies the UI. */
    private fun mutateQuietly(block: (Record) -> Record) {
        saver.submit(_record.updateAndGet(block))
    }

    /** Remembers where the user is editing so the outline can offer "Tiếp tục". */
    fun setPosition(section: String, field: String?) {
        val position = Position(section, field)
        if (_record.value.lastPosition != position) mutateQuietly { it.copy(lastPosition = position) }
    }

    /** Records a finished backup that covered everything up to [at]. */
    fun markBackedUp(at: String) = mutateQuietly { it.copy(backupAt = at) }

    // ---- values ----

    fun updateValue(fieldId: String, f: (FieldValue) -> FieldValue) = mutate { r ->
        val next = f(r.values[fieldId] ?: FieldValue()).normalized()
        r.copy(values = if (next == null) r.values - fieldId else r.values + (fieldId to next))
    }

    fun updateRowValue(tableId: String, rowId: String, columnId: String, f: (FieldValue) -> FieldValue) =
        updateRow(tableId, rowId) { row ->
            val next = f(row.values[columnId] ?: FieldValue()).normalized()
            row.copy(values = if (next == null) row.values - columnId else row.values + (columnId to next))
        }

    // ---- tables ----

    private fun updateRow(tableId: String, rowId: String, f: (Row) -> Row) = mutate { r ->
        val rows = r.tables[tableId].orEmpty()
        val updated = if (rows.any { it.id == rowId }) rows.map { if (it.id == rowId) f(it) else it } else rows + f(Row(rowId))
        r.copy(tables = r.tables + (tableId to updated))
    }

    /** The table and, for paired tables, its partner: they always hold the same row ids in the same order. */
    private fun linked(tableId: String): List<TableItem> =
        listOfNotNull(template.findTable(tableId), template.pairedTable(tableId))

    /** Adds a row (same id in paired tables); `carryOver` columns start with the previous row's value. */
    fun addRow(tableId: String): String {
        val id = UUID.randomUUID().toString()
        val tables = linked(tableId)
        mutate { r ->
            r.copy(
                tables = r.tables + tables.associate { t ->
                    val rows = r.tables[t.id].orEmpty()
                    t.id to rows + Row(id, values = carriedValues(t, rows.lastOrNull()))
                },
            )
        }
        return id
    }

    private fun carriedValues(table: TableItem, previous: Row?): Map<String, FieldValue> {
        if (previous == null) return emptyMap()
        return table.columns.filter { it.carryOver }.mapNotNull { c ->
            val fv = previous.values[c.id] ?: return@mapNotNull null
            if (fv.v.isEmptyJson()) null
            else FieldValue(v = fv.v, other = fv.other, parts = fv.parts).normalized()?.let { c.id to it }
        }.toMap()
    }

    fun duplicateRow(tableId: String, rowId: String): String? {
        if (_record.value.tables[tableId]?.any { it.id == rowId } != true) return null
        val id = UUID.randomUUID().toString()
        val tables = linked(tableId)
        mutate { r ->
            r.copy(
                tables = r.tables + tables.associate { t ->
                    val rows = r.tables[t.id].orEmpty()
                    val source = rows.firstOrNull { it.id == rowId }
                    val copy = if (source == null) Row(id) else duplicate(t, source, id)
                    val at = rows.indexOfFirst { it.id == rowId } + 1
                    t.id to rows.toMutableList().apply { add(at.coerceAtMost(size), copy) }
                },
            )
        }
        return id
    }

    private fun duplicate(table: TableItem, source: Row, id: String): Row {
        val columns = table.columns.associateBy { it.id }
        return source.copy(
            id = id,
            stopwatchStart = null,
            values = source.values.filterKeys { columns[it]?.isMedia != true }
                .mapValues { it.value.copy(media = emptyList(), review = null) },
        )
    }

    fun deleteRow(tableId: String, rowId: String) {
        val tables = linked(tableId)
        mutate { r ->
            r.copy(tables = r.tables + tables.associate { t -> t.id to r.tables[t.id].orEmpty().filter { it.id != rowId } })
        }
    }

    // ---- stopwatch ----

    fun startStopwatch(tableId: String, rowId: String) =
        updateRow(tableId, rowId) { it.copy(stopwatchStart = clock()) }

    /** Records the elapsed seconds since T0 in the next empty stopwatch column; returns that column (null if none). */
    fun markStopwatch(tableId: String, rowId: String, seconds: Long): String? {
        val table = template.findTable(tableId) ?: return null
        val def = table.stopwatch ?: return null
        val row = _record.value.tables[tableId]?.firstOrNull { it.id == rowId } ?: return null
        val column = Stopwatch.nextColumn(def, row) ?: return null
        updateRowValue(tableId, rowId, column) { it.copy(v = JsonPrimitive(seconds)) }
        return column
    }

    /** Clears the last mark; with no marks left, clears T0. */
    fun undoStopwatch(tableId: String, rowId: String) {
        val def = template.findTable(tableId)?.stopwatch ?: return
        val row = _record.value.tables[tableId]?.firstOrNull { it.id == rowId } ?: return
        val last = Stopwatch.lastMarked(def, row)
        if (last != null) updateRowValue(tableId, rowId, last) { it.copy(v = null) }
        else updateRow(tableId, rowId) { it.copy(stopwatchStart = null) }
    }

    // ---- signatures & status ----

    fun setSignature(id: String, value: SignatureValue?) = mutate { r ->
        r.copy(signatures = if (value == null) r.signatures - id else r.signatures + (id to value))
    }

    /** Completes the record when there are no issues. Returns false (and changes nothing) otherwise. */
    fun complete(): Boolean {
        if (issues().isNotEmpty()) return false
        val now = clock()
        val sessionIds = template.allFields().filter { it.type == "sessionTime" }.map { it.id }
        mutate { r ->
            val values = r.values.toMutableMap()
            for (id in sessionIds) {
                val obj = values[id]?.v as? JsonObject ?: continue
                values[id] = values.getValue(id).copy(v = JsonObject(obj + ("end" to JsonPrimitive(now))))
            }
            r.copy(status = Record.STATUS_COMPLETED, completedAt = now, values = values, returned = false)
        }
        saver.flush()
        return true
    }

    fun reopen() {
        mutate { it.copy(status = Record.STATUS_DRAFT, completedAt = null) }
        saver.flush()
    }

    /** Reviewer sends a completed record back to the surveyor: draft again, flagged `returned`. */
    fun returnForFix() {
        mutate { it.copy(status = Record.STATUS_DRAFT, completedAt = null, returned = true) }
        saver.flush()
    }

    /** Sets (or with blank [text] removes) the reviewer's note of a top-level field. */
    fun setReview(fieldId: String, text: String?) = updateValue(fieldId) { it.copy(review = text) }

    // ---- section tools & lookup ----

    fun emptyFlaggableCount(sectionId: String): Int = SectionTools.emptyFlaggable(template, _record.value, sectionId).size

    /** Marks every empty flaggable field of the section KĐĐ with one shared [reason]; returns how many. */
    fun markEmptyNotMeasurable(sectionId: String, reason: String): Int {
        val count = emptyFlaggableCount(sectionId)
        if (count > 0 && reason.isNotBlank()) mutate { SectionTools.markNotMeasurable(template, it, sectionId, reason) }
        return count
    }

    fun copyConflicts(source: Record, sectionId: String): Int =
        SectionTools.copyConflicts(template, _record.value, source, sectionId)

    fun copySectionFrom(source: Record, sectionId: String) = mutate { SectionTools.copySection(template, it, source, sectionId) }

    /** Plan of the fills a [site] choice makes for [field] (see [LookupFill]). */
    fun lookupPlan(field: Field, site: Site): FillPlan = LookupFill.plan(field, site, _record.value, clock())

    /** Sets [field] to the site code and writes the fills; overwrites non-empty targets only when [overwrite]. */
    fun applyLookup(field: Field, site: Site, overwrite: Boolean) {
        val plan = lookupPlan(field, site)
        mutate { r ->
            val values = r.values.toMutableMap()
            values[field.id] = (values[field.id] ?: FieldValue()).copy(v = JsonPrimitive(site.code))
            val writes = if (overwrite) plan.fresh + plan.overwrites else plan.fresh
            for ((target, v) in writes) values[target] = (values[target] ?: FieldValue()).copy(v = v, flag = null, reason = null)
            r.copy(values = values)
        }
    }
}
