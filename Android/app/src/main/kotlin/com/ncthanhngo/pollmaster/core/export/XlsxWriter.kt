package com.ncthanhngo.pollmaster.core.export

import com.ncthanhngo.pollmaster.core.format.formatShortest
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Hand-written SpreadsheetML package: inline strings, one bold header style, frozen first row. */
object XlsxWriter {
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    fun write(sheets: List<SheetData>, out: OutputStream) {
        val names = uniqueNames(sheets.map { it.name })
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes(sheets.size))
            zip.put("_rels/.rels", rootRels())
            zip.put("xl/workbook.xml", workbook(names))
            zip.put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            zip.put("xl/styles.xml", styles())
            sheets.forEachIndexed { i, s -> zip.put("xl/worksheets/sheet${i + 1}.xml", sheet(s)) }
        }
    }

    fun sheetName(raw: String): String =
        raw.replace(Regex("[\\[\\]:*?/\\\\]"), "_").trim().ifEmpty { "Sheet" }.take(31)

    private fun uniqueNames(raw: List<String>): List<String> {
        val used = HashSet<String>()
        return raw.map { r ->
            val base = sheetName(r)
            var name = base
            var n = 2
            while (!used.add(name.lowercase())) {
                val suffix = " ($n)"
                name = base.take(31 - suffix.length) + suffix
                n++
            }
            name
        }
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(n: Int) = XML_DECL +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        (1..n).joinToString("") {
            "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        } + "</Types>"

    private fun rootRels() = XML_DECL + "<Relationships xmlns=\"$PKG_REL\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"

    private fun workbook(names: List<String>) = XML_DECL + "<workbook xmlns=\"$NS\" xmlns:r=\"$REL_NS\"><sheets>" +
        names.mapIndexed { i, n -> "<sheet name=\"${xmlEscape(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
        "</sheets></workbook>"

    private fun workbookRels(n: Int) = XML_DECL + "<Relationships xmlns=\"$PKG_REL\">" +
        (1..n).joinToString("") { "<Relationship Id=\"rId$it\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet$it.xml\"/>" } +
        "<Relationship Id=\"rId${n + 1}\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/></Relationships>"

    private fun styles() = XML_DECL + "<styleSheet xmlns=\"$NS\">" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"

    private fun sheet(s: SheetData): String {
        val widths = s.headers.indices.map { c ->
            val longest = (listOf(s.headers[c]) + s.rows.map { r -> r.getOrNull(c)?.let(::textLen).orEmpty() }).maxOf { it.length }
            (longest + 2).coerceIn(8, 60)
        }
        val sb = StringBuilder(XML_DECL)
        sb.append("<worksheet xmlns=\"$NS\"><sheetViews><sheetView workbookViewId=\"0\">")
        sb.append("<pane ySplit=\"${s.headerRows}\" topLeftCell=\"A${s.headerRows + 1}\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        sb.append("<cols>")
        widths.forEachIndexed { i, w -> sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        sb.append("</cols><sheetData>")
        sb.append("<row r=\"1\">")
        s.headers.forEachIndexed { c, h -> sb.append(textCell(ref(c, 1), h, 1)) }
        sb.append("</row>")
        s.rows.forEachIndexed { r, row ->
            sb.append("<row r=\"${r + 2}\">")
            row.forEachIndexed { c, cell ->
                when (cell) {
                    is Cell.Num -> sb.append("<c r=\"${ref(c, r + 2)}\"><v>${formatShortest(cell.value)}</v></c>")
                    is Cell.Text -> if (cell.value.isNotEmpty()) sb.append(textCell(ref(c, r + 2), cell.value, if (r < s.headerRows - 1) 1 else 0))
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun textLen(c: Cell) = when (c) {
        is Cell.Text -> c.value.lineSequence().maxOfOrNull { it.length }?.let { "x".repeat(it) }.orEmpty()
        is Cell.Num -> formatShortest(c.value)
    }

    private fun textCell(ref: String, text: String, style: Int) =
        "<c r=\"$ref\" t=\"inlineStr\"${if (style != 0) " s=\"$style\"" else ""}><is><t xml:space=\"preserve\">${xmlEscape(text)}</t></is></c>"

    /** Column index (0-based) and row to A1 notation. */
    fun ref(col: Int, row: Int): String {
        var n = col
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + n % 26)
            n = n / 26 - 1
        } while (n >= 0)
        return "$sb$row"
    }
}
