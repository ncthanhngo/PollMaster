package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.isEmpty
import com.ncthanhngo.pollmaster.core.model.mediaItems
import com.ncthanhngo.pollmaster.core.validation.HEADER_SECTION_ID
import com.ncthanhngo.pollmaster.core.validation.Progress
import com.ncthanhngo.pollmaster.core.validation.Validator

/** "3 lượt" / "2 người": non-empty rows of a repeating table with its lowercase row label. */
data class RowCount(val count: Int, val noun: String)

/**
 * Statistics line of one outline entry: required x/y, rows of repeating tables, photo/video fields that have
 * a file (x of y) and whether a required / minCount photo field is still short.
 */
data class SectionStats(
    val required: Progress,
    val rows: List<RowCount>,
    val mediaFilled: Int,
    val mediaTotal: Int,
    val missingPhoto: Boolean,
) {
    /** `3/5 bắt buộc`, `3 lượt`, `ảnh/video 12/15` — only the parts that apply; empty when the section has nothing to count. */
    fun summary(): String = buildList {
        if (required.total > 0) add("${required.filled}/${required.total} bắt buộc")
        rows.forEach { add("${it.count} ${it.noun}") }
        if (mediaTotal > 0) add("ảnh/video $mediaFilled/$mediaTotal")
    }.joinToString(" · ")
}

object OutlineStats {
    fun compute(template: Template, record: Record): Map<String, SectionStats> {
        val progress = Validator(template).sectionProgress(record)
        fun stats(id: String, fields: List<Field>, tables: List<TableItem>): SectionStats {
            val media = fields.filter { it.type == "photo" || it.type == "video" }
            val counts = media.associateWith { record.values[it.id]?.mediaItems()?.size ?: 0 }
            return SectionStats(
                required = progress[id] ?: Progress(0, 0),
                rows = tables.filter { !it.isFixed }.map { t ->
                    RowCount(record.tables[t.id].orEmpty().count { !it.isEmpty() }, t.rowLabel?.lowercase() ?: "dòng")
                },
                mediaFilled = counts.values.count { it > 0 },
                mediaTotal = media.size,
                missingPhoto = counts.any { (f, n) -> n < maxOf(f.minCount ?: 0, if (f.required) 1 else 0) },
            )
        }
        return buildMap {
            put(HEADER_SECTION_ID, stats(HEADER_SECTION_ID, template.header, emptyList()))
            for (s in template.sections) {
                put(s.id, stats(s.id, s.items.filterIsInstance<Field>(), s.items.filterIsInstance<TableItem>()))
            }
        }
    }
}
