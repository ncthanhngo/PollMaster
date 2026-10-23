package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.isEmpty
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import com.ncthanhngo.pollmaster.core.model.itemsOf
import com.ncthanhngo.pollmaster.core.model.normalized
import com.ncthanhngo.pollmaster.core.model.pairedTable

/** Bulk operations on one section (ux-spec C: section menu). Pure functions over [Record]. */
object SectionTools {

    private fun fieldsOf(template: Template, sectionId: String) = template.itemsOf(sectionId).filterIsInstance<Field>()

    private fun hasData(fv: FieldValue?) =
        fv != null && (!fv.v.isEmptyJson() || fv.flag != null || fv.parts.values.any { !it.isEmptyJson() } || fv.media.isNotEmpty())

    /** Fields of the section that offer flags, are editable and hold nothing yet. */
    fun emptyFlaggable(template: Template, record: Record, sectionId: String): List<Field> =
        fieldsOf(template, sectionId).filter {
            it.flagsEnabled && !it.readonly && it.type != "computed" && !hasData(record.values[it.id])
        }

    /** Marks every field from [emptyFlaggable] as KĐĐ with the shared [reason]; other fields are untouched. */
    fun markNotMeasurable(template: Template, record: Record, sectionId: String, reason: String): Record {
        val targets = emptyFlaggable(template, record, sectionId)
        if (targets.isEmpty() || reason.isBlank()) return record
        val values = record.values.toMutableMap()
        for (f in targets) {
            values[f.id] = (values[f.id] ?: FieldValue()).copy(flag = FieldValue.FLAG_NOT_MEASURABLE, reason = reason.trim())
        }
        return record.copy(values = values)
    }

    private fun copyable(f: Field) = f.type != "sessionTime" && f.type != "signature" && f.type != "computed"

    /** Tables of the section plus their paired tables (paired tables are one data set sharing row ids). */
    private fun tablesOf(template: Template, sectionId: String): List<TableItem> {
        val own = template.itemsOf(sectionId).filterIsInstance<TableItem>()
        return (own + own.mapNotNull { template.pairedTable(it.id) }).distinctBy { it.id }
    }

    /** How many items of [target] copying would replace: fields with data in both records, tables with rows in both. */
    fun copyConflicts(template: Template, target: Record, source: Record, sectionId: String): Int {
        val fields = fieldsOf(template, sectionId).count { copyable(it) && hasData(target.values[it.id]) && copiedValue(it, source.values[it.id]) != null }
        val tables = tablesOf(template, sectionId).count { target.tables[it.id].orEmpty().any { r -> !r.isEmpty() } && source.tables[it.id].orEmpty().isNotEmpty() }
        return fields + tables
    }

    /** Source value without media, review notes; null when nothing would be copied. */
    private fun copiedValue(f: Field, fv: FieldValue?): FieldValue? =
        fv?.copy(media = emptyList(), review = null, v = if (f.isMedia) null else fv.v)?.normalized()?.takeIf { hasData(it) || !it.note.isNullOrBlank() }

    /**
     * Copies values and tables of [sectionId] from [source] (same template) into [target], without media.
     * Empty source entries never erase data in [target].
     */
    fun copySection(template: Template, target: Record, source: Record, sectionId: String): Record {
        val values = target.values.toMutableMap()
        for (f in fieldsOf(template, sectionId).filter(::copyable)) copiedValue(f, source.values[f.id])?.let { values[f.id] = it }
        val tables = target.tables.toMutableMap()
        val copiedTables = tablesOf(template, sectionId).filter { source.tables[it.id].orEmpty().isNotEmpty() }
        for (t in copiedTables) {
            val rows = source.tables.getValue(t.id)
            val mediaColumns = t.columns.filter { it.isMedia }.map { it.id }.toSet()
            tables[t.id] = rows.map { row ->
                Row(
                    id = row.id,
                    values = row.values.filterKeys { it !in mediaColumns }
                        .mapValues { it.value.copy(media = emptyList(), review = null) }
                        .mapNotNull { (k, v) -> v.normalized()?.let { k to it } }.toMap(),
                )
            }
        }
        // A paired table that was not copied must still list the same row ids (empty rows) as its partner.
        for (t in copiedTables) {
            val partner = template.pairedTable(t.id) ?: continue
            if (partner in copiedTables) continue
            val existing = target.tables[partner.id].orEmpty()
            tables[partner.id] = tables.getValue(t.id).map { row -> existing.firstOrNull { it.id == row.id } ?: Row(row.id) }
        }
        return target.copy(values = values, tables = tables)
    }
}
