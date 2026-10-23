package com.ncthanhngo.pollmaster.core.validation

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.formula.rowViews
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Section
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.asDouble
import com.ncthanhngo.pollmaster.core.model.asList
import com.ncthanhngo.pollmaster.core.model.asObject
import com.ncthanhngo.pollmaster.core.model.asText
import com.ncthanhngo.pollmaster.core.model.isEmpty
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import com.ncthanhngo.pollmaster.core.model.mediaItems
import com.ncthanhngo.pollmaster.core.model.visibleParts

const val HEADER_SECTION_ID = "header"
const val HEADER_SECTION_TITLE = "Thông tin đầu biên bản"

enum class IssueKind { REQUIRED, REASON_MISSING, BELOW_MIN, ABOVE_MAX, MIN_COUNT, MIN_ROWS }

/**
 * A validation problem. [targetId] is a field id or table id; [rowId] is set for problems inside a
 * table row. [detail] carries the limit (min/max/count) for the message.
 */
data class Issue(
    val kind: IssueKind,
    val sectionId: String,
    val sectionTitle: String,
    val targetId: String,
    val label: String,
    val rowId: String? = null,
    val columnId: String? = null,
    val detail: String? = null,
    /** Actual count (files or rows) for MIN_COUNT / MIN_ROWS messages. */
    val found: Int? = null,
) {
    /** Key used to show the issue on a field card (field id, or table/row/column). */
    val key: String get() = listOfNotNull(targetId, rowId, columnId).joinToString("/")

    /** User-facing message (wording fixed by the shared contract, section 6.3). */
    fun message(): String = when (kind) {
        IssueKind.REQUIRED -> "Bắt buộc nhập"
        IssueKind.REASON_MISSING -> "Cần nhập lý do KĐĐ"
        IssueKind.BELOW_MIN -> "Giá trị phải ≥ $detail"
        IssueKind.ABOVE_MAX -> "Giá trị phải ≤ $detail"
        IssueKind.MIN_COUNT -> "Cần tối thiểu $detail tệp (hiện có ${found ?: 0})"
        IssueKind.MIN_ROWS -> "Cần tối thiểu $detail dòng (hiện có ${found ?: 0})"
    }
}

private class Problem(val kind: IssueKind, val detail: String? = null, val found: Int? = null)

data class Progress(val filled: Int, val total: Int)

/** Implements the validation rules of the template schema (section 4). */
class Validator(private val template: Template) {

    fun validate(record: Record): List<Issue> {
        val issues = mutableListOf<Issue>()
        checkFields(HEADER_SECTION_ID, HEADER_SECTION_TITLE, template.header, record, issues)
        for (section in template.sections) {
            checkFields(section.id, section.title, section.items.filterIsInstance<Field>(), record, issues)
            section.items.filterIsInstance<TableItem>().forEach { checkTable(section, it, record, issues) }
        }
        return issues
    }

    /** Required items (fields and tables) with no issue at all vs. total required. */
    fun requiredProgress(record: Record): Progress {
        val withIssues = validate(record).map { it.targetId }.toSet()
        val required = (template.header + template.sections.flatMap { it.items.filterIsInstance<Field>() })
            .filter { it.required && it.type != "computed" }.map { it.id } +
            template.sections.flatMap { it.items.filterIsInstance<TableItem>() }.filter { it.required }.map { it.id }
        return Progress(required.count { it !in withIssues }, required.size)
    }

    private fun checkFields(
        sectionId: String,
        sectionTitle: String,
        fields: List<Field>,
        record: Record,
        out: MutableList<Issue>,
    ) {
        for (f in fields) {
            val fv = record.values[f.id]
            for (p in fieldProblems(f, fv) { record.values[it] }) {
                out += Issue(p.kind, sectionId, sectionTitle, f.id, f.label, detail = p.detail, found = p.found)
            }
        }
    }

    private fun checkTable(section: Section, table: TableItem, record: Record, out: MutableList<Issue>) {
        val views = table.rowViews(record).filter { !it.row.isEmpty() }
        val rows = views.map { it.row }
        val need = maxOf(table.minRows, 1)
        if (table.required && rows.size < need) {
            out += Issue(IssueKind.MIN_ROWS, section.id, section.title, table.id, table.title, detail = need.toString(), found = rows.size)
        }
        for (row in rows) {
            for (col in table.columns) {
                val fv = row.values[col.id]
                for (p in fieldProblems(col, fv) { row.values[it] ?: record.values[it] }) {
                    out += Issue(p.kind, section.id, section.title, table.id, col.label, rowId = row.id, columnId = col.id, detail = p.detail, found = p.found)
                }
            }
        }
    }

    private fun fieldProblems(
        f: Field,
        fv: FieldValue?,
        lookup: (String) -> FieldValue?,
    ): List<Problem> {
        if (f.type == "computed") return emptyList()
        if (fv?.flag == FieldValue.FLAG_NOT_MEASURABLE) {
            return if (fv.reason.isNullOrBlank()) listOf(Problem(IssueKind.REASON_MISSING)) else emptyList()
        }
        val problems = mutableListOf<Problem>()
        if (f.required && !isFilled(f, fv, lookup)) problems += Problem(IssueKind.REQUIRED)
        if (f.type == "number" || f.type == "integer") {
            val nums = if (f.samples != null) fv?.v.asList().mapNotNull { it.asDouble() } else listOfNotNull(fv?.v.asDouble())
            f.min?.let { min -> if (nums.any { it < min }) problems += Problem(IssueKind.BELOW_MIN, trim(min)) }
            f.max?.let { max -> if (nums.any { it > max }) problems += Problem(IssueKind.ABOVE_MAX, trim(max)) }
        }
        if (f.isMedia && f.minCount != null) {
            val count = fv?.mediaItems()?.size ?: 0
            if (count < f.minCount && (f.required || count > 0)) problems += Problem(IssueKind.MIN_COUNT, f.minCount.toString(), count)
        }
        return problems
    }

    private fun trim(d: Double) = com.ncthanhngo.pollmaster.core.format.formatNumber(d, 6)

    private fun isFilled(f: Field, fv: FieldValue?, lookup: (String) -> FieldValue?): Boolean = when {
        f.type == "composite" -> {
            val visible = f.visibleParts(fv, lookup)
            visible.isNotEmpty() && visible.all { !fv?.parts?.get(it.id).isEmptyJson() }
        }
        f.isMedia -> (fv?.mediaItems()?.size ?: 0) > 0
        f.type == "sessionTime" -> fv?.v.asObject()?.get("start").asText() != null
        f.type == "signature" -> fv?.v.asObject()?.get("file").asText()?.isNotBlank() == true
        else -> !fv?.v.isEmptyJson()
    }
}
