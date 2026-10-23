package com.ncthanhngo.pollmaster.core.export

import com.ncthanhngo.pollmaster.core.format.displayDateTime
import com.ncthanhngo.pollmaster.core.format.recordTitle
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.Template
import com.ncthanhngo.pollmaster.core.model.allFields
import com.ncthanhngo.pollmaster.core.model.recordCode
import com.ncthanhngo.pollmaster.core.model.tables
import com.ncthanhngo.pollmaster.core.storage.compareVersions

/** "Xuất tổng hợp": one workbook per project, one row per record (schema 9.4). */
object ProjectSummary {
    private val FIXED_KEYS = listOf("recordCode", "title", "status", "createdAt", "updatedAt", "completedAt")
    private val FIXED_LABELS = listOf("Mã phiếu", "Tiêu đề", "Trạng thái", "Tạo lúc", "Cập nhật lúc", "Hoàn thành lúc")
    private const val CODE_COLUMN = "Mã phiếu"

    /**
     * A sheet per template id plus a `{templateId}-{tableId}` sheet per table. Columns are the union over every version
     * seen ([templates] and the versions of [entries]), newest version's order first. [entries] pair each record with the
     * template version it was written with; rows are in `createdAt` order. Row 1 holds keys / field codes, row 2 labels.
     * Never empty: a workbook needs a sheet, so without any template a single "Tổng hợp" sheet is returned.
     */
    fun sheets(templates: List<Template>, entries: List<Pair<Template, Record>>, completedOnly: Boolean): List<SheetData> {
        val used = entries.filter { !completedOnly || it.second.isCompleted }
        val byId = (templates + used.map { it.first }).distinct().groupBy { it.id }
        val sheets = byId.keys.sorted().flatMap { id ->
            val versions = byId.getValue(id).sortedWith { a, b -> compareVersions(b.version, a.version) }
            val mine = used.filter { it.first.id == id }.sortedBy { it.second.createdAt }
            val tableIds = versions.flatMap { v -> v.tables().map { it.id } }.distinct()
            listOf(templateSheet(id, versions, mine)) + tableIds.map { tableSheet(id, it, versions, mine) }
        }
        return sheets.ifEmpty { listOf(SheetData("Tổng hợp", listOf("Không có mẫu"), emptyList())) }
    }

    private fun templateSheet(id: String, versions: List<Template>, mine: List<Pair<Template, Record>>): SheetData {
        val fields = versions.flatMap { it.allFields() }.distinctBy { it.id }
        val rows = mine.map { (t, r) ->
            val byId = ExportBuilder(t, r).infoRows().associateBy { it.field.id }
            val fixed = listOf(
                code(t, r), recordTitle(t, r), if (r.isCompleted) "Hoàn thành" else "Nháp",
                displayDateTime(r.createdAt), displayDateTime(r.updatedAt), r.completedAt?.let(::displayDateTime).orEmpty(),
            ).map<String, Cell> { Cell.Text(it) }
            fixed + fields.map { f -> byId[f.id]?.let { row -> row.number?.let { Cell.Num(it) } ?: Cell.Text(row.text) } ?: Cell.Text("") }
        }
        val labels = (FIXED_LABELS + fields.map { it.label }).map { Cell.Text(it) }
        return SheetData(id, FIXED_KEYS + fields.map { it.id }, listOf<List<Cell>>(labels) + rows, headerRows = 2)
    }

    private fun tableSheet(id: String, tableId: String, versions: List<Template>, mine: List<Pair<Template, Record>>): SheetData {
        val columns = versions.flatMap { v -> v.tables().firstOrNull { it.id == tableId }?.columns.orEmpty() }.distinctBy { it.id }
        val rows = mine.flatMap { (t, r) ->
            val own = t.tables().firstOrNull { it.id == tableId } ?: return@flatMap emptyList()
            val code = Cell.Text(code(t, r))
            ExportBuilder(t, r).tableCells(own).map { cells ->
                // Columns are matched by id so an older version with other columns still lines up.
                listOf(code) + columns.map { c -> own.columns.indexOfFirst { it.id == c.id }.let { i -> if (i < 0) Cell.Text("") else cells[i] } }
            }
        }
        return SheetData(
            "$id-$tableId", listOf(CODE_COLUMN) + columns.map { it.id },
            listOf<List<Cell>>(listOf<Cell>(Cell.Text(CODE_COLUMN)) + columns.map { Cell.Text(it.label) }) + rows, headerRows = 2,
        )
    }

    private fun code(t: Template, r: Record) = t.recordCode(r) ?: r.id
}
