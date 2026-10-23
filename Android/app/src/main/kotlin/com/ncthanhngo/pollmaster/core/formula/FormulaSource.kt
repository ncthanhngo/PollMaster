package com.ncthanhngo.pollmaster.core.formula

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.findTable
import kotlinx.serialization.json.JsonElement

/** A table row together with its template-defined fixed data. */
data class RowView(val row: Row, val fixed: Map<String, JsonElement>)

/**
 * Rows of this table for [record]. Fixed tables always follow the template rows (in order), merged with the
 * stored row of the same id; stored rows with unknown ids are ignored. Repeating tables use the stored rows.
 */
fun TableItem.rowViews(record: Record): List<RowView> {
    val stored = record.tables[id].orEmpty()
    val defs = rows ?: return stored.map { RowView(it, it.fixed) }
    return defs.map { def -> RowView(stored.firstOrNull { it.id == def.id } ?: Row(def.id), def.fixed) }
}

/** Data access used by the formula engine. */
interface FormulaSource {
    fun field(id: String): Field?
    fun value(id: String): FieldValue?
    fun rows(tableId: String): List<RowView>
}

/** [FormulaSource] over a record; [template] may be null (field definitions then unknown). */
class RecordSource(private val template: Template?, private val record: Record) : FormulaSource {
    private val fields: Map<String, Field> by lazy { template?.allFields()?.associateBy { it.id } ?: emptyMap() }

    override fun field(id: String): Field? = fields[id]

    override fun value(id: String): FieldValue? = record.values[id]

    override fun rows(tableId: String): List<RowView> {
        val table = template?.findTable(tableId)
        if (table != null) return table.rowViews(record)
        return record.tables[tableId].orEmpty().map { RowView(it, it.fixed) }
    }
}
