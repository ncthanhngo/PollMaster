package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.formula.FormulaEngine
import com.ncthanhngo.pollmaster.core.formula.RecordSource
import com.ncthanhngo.pollmaster.core.format.nowIso
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.SignatureValue
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.findTable
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

    private val _touched = MutableStateFlow<Set<String>>(emptySet())
    val touched: StateFlow<Set<String>> = _touched.asStateFlow()

    private val validator = Validator(template)

    fun issues(r: Record = _record.value): List<Issue> = validator.validate(r)
    fun progress(r: Record = _record.value): Progress = validator.requiredProgress(r)
    fun engine(r: Record = _record.value) = FormulaEngine(RecordSource(template, r))

    fun revealErrors() {
        _showErrors.value = true
    }

    fun touch(key: String) {
        if (key !in _touched.value) _touched.update { it + key }
    }

    fun flush() = saver.flush()

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

    fun addRow(tableId: String): String {
        val id = UUID.randomUUID().toString()
        mutate { r -> r.copy(tables = r.tables + (tableId to (r.tables[tableId].orEmpty() + Row(id)))) }
        return id
    }

    fun duplicateRow(tableId: String, rowId: String): String? {
        val source = _record.value.tables[tableId]?.firstOrNull { it.id == rowId } ?: return null
        val id = UUID.randomUUID().toString()
        val columns = template.findTable(tableId)?.columns.orEmpty().associateBy { it.id }
        val copy = source.copy(
            id = id,
            stopwatchStart = null,
            values = source.values.filterKeys { columns[it]?.isMedia != true }.mapValues { it.value.copy(media = emptyList()) },
        )
        mutate { r ->
            val rows = r.tables[tableId].orEmpty()
            val at = rows.indexOfFirst { it.id == rowId } + 1
            r.copy(tables = r.tables + (tableId to rows.toMutableList().apply { add(at, copy) }))
        }
        return id
    }

    fun deleteRow(tableId: String, rowId: String) = mutate { r ->
        r.copy(tables = r.tables + (tableId to r.tables[tableId].orEmpty().filter { it.id != rowId }))
    }

    // ---- stopwatch ----

    fun startStopwatch(tableId: String, rowId: String) =
        updateRow(tableId, rowId) { it.copy(stopwatchStart = clock()) }

    /** Records the elapsed seconds since T0 in the next empty stopwatch column. */
    fun markStopwatch(tableId: String, rowId: String, seconds: Long) {
        val table = template.findTable(tableId) ?: return
        val def = table.stopwatch ?: return
        val row = _record.value.tables[tableId]?.firstOrNull { it.id == rowId } ?: return
        val column = Stopwatch.nextColumn(def, row) ?: return
        updateRowValue(tableId, rowId, column) { it.copy(v = JsonPrimitive(seconds)) }
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
            r.copy(status = Record.STATUS_COMPLETED, completedAt = now, values = values)
        }
        saver.flush()
        return true
    }

    fun reopen() {
        mutate { it.copy(status = Record.STATUS_DRAFT, completedAt = null) }
        saver.flush()
    }
}
