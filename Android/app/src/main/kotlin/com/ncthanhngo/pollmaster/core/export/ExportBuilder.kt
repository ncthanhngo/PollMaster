package com.ncthanhngo.pollmaster.core.export

import com.ncthanhngo.pollmaster.core.format.KDD
import com.ncthanhngo.pollmaster.core.format.ValueFormatter
import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.formula.FormulaEngine
import com.ncthanhngo.pollmaster.core.formula.RecordSource
import com.ncthanhngo.pollmaster.core.formula.RowView
import com.ncthanhngo.pollmaster.core.formula.rowViews
import com.ncthanhngo.pollmaster.core.model.Field
import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.model.HeadingItem
import com.ncthanhngo.pollmaster.core.model.Media
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.TableItem
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.mediaItems
import com.ncthanhngo.pollmaster.core.project.ProjectRef
import com.ncthanhngo.pollmaster.core.validation.HEADER_SECTION_TITLE

/** One field line of the "Thông tin" sheet / document tables. */
data class InfoRow(
    val section: String,
    val field: Field,
    val value: FieldValue?,
    val text: String,
    val number: Double?,
    val flagText: String,
)

private const val DEFAULT_ROW_LABEL = "Dòng"
private const val INDEX_COLUMN_WEIGHT = 0.12f

/** Builds sheets and the neutral document from a record. */
class ExportBuilder(private val template: Template, private val record: Record, private val project: ProjectRef? = null) {
    private val engine = FormulaEngine(RecordSource(template, record))
    private val formatter = ValueFormatter { record.values[it] }

    fun infoRows(): List<InfoRow> {
        val rows = mutableListOf<InfoRow>()
        template.header.forEach { rows += infoRow(HEADER_SECTION_TITLE, it) }
        for (s in template.sections) s.items.filterIsInstance<Field>().forEach { rows += infoRow(s.title, it) }
        return rows
    }

    private fun infoRow(section: String, f: Field): InfoRow {
        val fv = record.values[f.id]
        val computed = if (f.type == "computed") engine.computeField(f) else null
        val flag = when (fv?.flag) {
            FieldValue.FLAG_ESTIMATE -> "~"
            FieldValue.FLAG_NOT_MEASURABLE -> KDD
            else -> ""
        }
        return InfoRow(section, f, fv, formatter.display(f, fv, computed), formatter.plainNumber(f, fv, computed), flag)
    }

    fun infoSheet(): SheetData {
        val headers = listOf("Mục", "Mã", "Thông số", "Giá trị", "Đơn vị", "Cờ", "Lý do", "Ghi chú", "Kiểm tra")
        val rows = infoRows().map { r ->
            listOf(
                Cell.Text(r.section), Cell.Text(r.field.id), Cell.Text(r.field.label),
                r.number?.let { Cell.Num(it) } ?: Cell.Text(r.text),
                Cell.Text(r.field.unit.orEmpty()), Cell.Text(r.flagText),
                Cell.Text(r.value?.reason.orEmpty()), Cell.Text(r.value?.note.orEmpty()),
                Cell.Text(r.value?.review.orEmpty()),
            )
        }
        val projectRow = project?.let { listOf(listOf<Cell>(Cell.Text(""), Cell.Text(""), Cell.Text("Dự án"), Cell.Text(it.display()), Cell.Text(""), Cell.Text(""), Cell.Text(""), Cell.Text(""), Cell.Text(""))) }.orEmpty()
        return SheetData("Thông tin", headers, projectRow + rows)
    }

    fun tableSheets(): List<SheetData> = template.sections.flatMap { s -> s.items.filterIsInstance<TableItem>() }
        .map { t -> SheetData(t.id, t.columns.map { it.label }, tableCells(t)) }

    /** Rows of one table as cells (numbers stay numbers), in template order. */
    fun tableCells(t: TableItem): List<List<Cell>> = t.rowViews(record).map { view -> t.columns.map { cell(it, view) } }

    fun allSheets(): List<SheetData> = listOf(infoSheet()) + tableSheets()

    /** Stored value of a column, or the template's fixed data when the row has none. */
    private fun cellValue(col: Field, view: RowView): FieldValue? =
        view.row.values[col.id] ?: view.fixed[col.id]?.let { FieldValue(v = it) }

    private fun cellText(col: Field, view: RowView): String = formatter.display(col, cellValue(col, view))

    private fun cell(col: Field, view: RowView): Cell {
        val num = formatter.plainNumber(col, cellValue(col, view), null)
        return if (num != null) Cell.Num(num) else Cell.Text(cellText(col, view))
    }

    // ---- document (PDF / Word) ----

    fun document(): ExportDoc {
        val blocks = mutableListOf<DocBlock>()
        blocks += DocBlock.Title(template.title, template.subtitle)
        project?.let { blocks += DocBlock.Paragraph("Dự án: ${it.display()}") }
        val infoBySection = infoRows().groupBy { it.section }
        blocks += DocBlock.SectionTitle(HEADER_SECTION_TITLE)
        blocks += fieldGrid(infoBySection[HEADER_SECTION_TITLE].orEmpty())
        for (s in template.sections) {
            blocks += DocBlock.SectionTitle(s.title)
            s.note?.let { blocks += DocBlock.Paragraph(it) }
            val sectionRows = infoBySection[s.title].orEmpty().associateBy { it.field.id }
            var pending = mutableListOf<InfoRow>()
            fun flush() {
                if (pending.isNotEmpty()) blocks += fieldGrid(pending)
                pending = mutableListOf()
            }
            for (item in s.items) {
                when (item) {
                    is Field -> sectionRows[item.id]?.let { pending += it }
                    is HeadingItem -> {
                        flush()
                        blocks += DocBlock.Heading(item.title)
                        item.note?.let { blocks += DocBlock.Paragraph(it) }
                    }
                    is TableItem -> {
                        flush()
                        blocks += DocBlock.Heading(item.title)
                        item.note?.let { blocks += DocBlock.Paragraph(it) }
                        blocks += tableGrid(item)
                    }
                }
            }
            flush()
            val images = sectionImages(s.items)
            if (images.isNotEmpty()) blocks += DocBlock.Images(images)
        }
        if (template.signatures.isNotEmpty()) {
            blocks += DocBlock.PageBreak
            blocks += DocBlock.SectionTitle("Chữ ký")
            template.signatures.forEach { sig ->
                val v = record.signatures[sig.id]
                blocks += DocBlock.Signature(sig.label, v?.name, v?.signedAt?.let { displayDateTime(it) }, v?.file)
            }
        }
        return ExportDoc(blocks)
    }

    private fun fieldGrid(rows: List<InfoRow>) = DocBlock.Grid(
        listOf("Mã", "Thông số", "Giá trị", "Ghi chú"),
        rows.map { r ->
            val unit = r.field.unit?.takeIf { it.isNotBlank() && r.text.isNotEmpty() && r.flagText != KDD && r.field.parts.isEmpty() }
            listOf(r.field.id, r.field.label, if (unit != null) "${r.text} $unit" else r.text, r.value?.note.orEmpty())
        },
        listOf(0.14f, 0.38f, 0.30f, 0.18f),
    )

    private fun tableGrid(t: TableItem): DocBlock.Grid {
        val views = t.rowViews(record)
        val w = 1f / t.columns.size.coerceAtLeast(1)
        if (t.isFixed) {
            return DocBlock.Grid(t.columns.map { it.label }, views.map { v -> t.columns.map { cellText(it, v) } }, List(t.columns.size) { w })
        }
        // Repeating tables get a leading "#" column: "{rowLabel} {n}".
        val label = t.rowLabel ?: DEFAULT_ROW_LABEL
        val rows = views.mapIndexed { i, v -> listOf("$label ${i + 1}") + t.columns.map { cellText(it, v) } }
        return DocBlock.Grid(
            listOf("#") + t.columns.map { it.label }, rows,
            listOf(INDEX_COLUMN_WEIGHT) + List(t.columns.size) { w * (1f - INDEX_COLUMN_WEIGHT) },
        )
    }

    private fun sectionImages(items: List<com.ncthanhngo.pollmaster.core.model.Item>): List<DocImage> {
        val out = mutableListOf<DocImage>()
        fun add(id: String, media: List<Media>) = media.filter { isImage(it.file) }.forEach {
            out += DocImage(it.file, "$id – ${it.file.substringAfterLast('/')}")
        }
        for (item in items) {
            when (item) {
                is Field -> record.values[item.id]?.let { fv ->
                    if (item.type == "photo") add(item.id, fv.mediaItems())
                    add(item.id, fv.media)
                }
                is TableItem -> item.rowViews(record).map { it.row }.forEach { row ->
                    item.columns.forEach { c ->
                        row.values[c.id]?.let { fv ->
                            if (c.type == "photo") add("${item.id}.${c.id}", fv.mediaItems())
                            add("${item.id}.${c.id}", fv.media)
                        }
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private fun isImage(file: String) = file.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png")
}
