import Foundation

/// Hand-written SpreadsheetML workbook (inline strings, no shared strings part).
public enum XLSXExport {
    enum Cell { case text(String), number(Double), empty }

    static func column(_ index: Int) -> String {
        var n = index + 1, s = ""
        while n > 0 { s = String(UnicodeScalar(UInt8(65 + (n - 1) % 26))) + s; n = (n - 1) / 26 }
        return s
    }

    static func sheetName(_ raw: String, used: inout Set<String>) -> String {
        var name = String(raw.map { "[]:*?/\\".contains($0) ? "_" : $0 }.prefix(31))
        if name.isEmpty { name = "Sheet" }
        var candidate = name, n = 2
        while used.contains(candidate.lowercased()) {
            let suffix = " (\(n))"
            candidate = String(name.prefix(31 - suffix.count)) + suffix; n += 1
        }
        used.insert(candidate.lowercased())
        return candidate
    }

    static func sheetXML(rows: [[Cell]], widths: [Double], headerRows: Int = 1) -> String {
        var x = XML.header + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
        x += "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"\(headerRows)\" topLeftCell=\"A\(headerRows + 1)\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
        x += "<cols>" + widths.enumerated().map { "<col min=\"\($0 + 1)\" max=\"\($0 + 1)\" width=\"\($1)\" customWidth=\"1\"/>" }.joined() + "</cols><sheetData>"
        for (r, row) in rows.enumerated() {
            x += "<row r=\"\(r + 1)\">"
            for (c, cell) in row.enumerated() {
                let ref = "\(column(c))\(r + 1)", style = r < headerRows ? 1 : 0
                switch cell {
                case .text(let s) where !s.isEmpty:
                    x += "<c r=\"\(ref)\" s=\"\(style)\" t=\"inlineStr\"><is><t xml:space=\"preserve\">\(XML.escape(s))</t></is></c>"
                case .number(let d): x += "<c r=\"\(ref)\" s=\"\(style)\"><v>\(d)</v></c>"
                default: x += "<c r=\"\(ref)\" s=\"\(style)\"/>"
                }
            }
            x += "</row>"
        }
        return x + "</sheetData></worksheet>"
    }

    static let styles = XML.header + """
    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><sz val="11"/><name val="Arial"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF4"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>
    """

    public static func build(_ model: ExportModel) -> [(path: String, data: Data)] {
        var used = Set<String>()
        var sheets: [(name: String, xml: String)] = []

        let projectRow: [[Cell]] = model.project.map { [[.text("Dự án"), .text($0.code), .text("Dự án"), .text($0.name), .empty, .empty, .empty, .empty, .empty]] } ?? []
        let infoRows: [[Cell]] = [CSVExport.infoHeaders.map { .text($0) }] + projectRow + model.entries.map { e in
            [.text(e.sectionTitle), .text(e.id), .text(e.label), e.number.map { .number($0) } ?? .text(e.display),
             .text(e.unit), .text(e.flag), .text(e.reason), .text(e.note), .text(e.review)]
        }
        sheets.append((sheetName("Thông tin", used: &used), sheetXML(rows: infoRows, widths: [24, 10, 44, 28, 10, 7, 24, 30, 30])))

        for t in model.tables {
            var rows: [[Cell]] = [t.columns.map { .text($0.label) }]
            rows += t.rows.map { $0.map { c in c.number.map { .number($0) } ?? .text(c.text) } }
            sheets.append((sheetName(t.id, used: &used), sheetXML(rows: rows, widths: t.columns.map { _ in 20 })))
        }

        return workbook(sheets)
    }

    /// Package parts (content types, workbook, styles, one part per sheet) for sheets already rendered to XML.
    static func workbook(_ sheets: [(name: String, xml: String)]) -> [(path: String, data: Data)] {
        let ns = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        var parts: [(String, String)] = []
        parts.append(("[Content_Types].xml", XML.header + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + sheets.indices.map { "<Override PartName=\"/xl/worksheets/sheet\($0 + 1).xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }.joined() + "</Types>"))
        parts.append(("_rels/.rels", XML.header + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"\(ns)/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"))
        parts.append(("xl/workbook.xml", XML.header + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"\(ns)\"><sheets>"
            + sheets.enumerated().map { "<sheet name=\"\(XML.escape($1.name))\" sheetId=\"\($0 + 1)\" r:id=\"rId\($0 + 1)\"/>" }.joined() + "</sheets></workbook>"))
        parts.append(("xl/_rels/workbook.xml.rels", XML.header + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + sheets.indices.map { "<Relationship Id=\"rId\($0 + 1)\" Type=\"\(ns)/worksheet\" Target=\"worksheets/sheet\($0 + 1).xml\"/>" }.joined()
            + "<Relationship Id=\"rId\(sheets.count + 1)\" Type=\"\(ns)/styles\" Target=\"styles.xml\"/></Relationships>"))
        parts.append(("xl/styles.xml", styles))
        for (i, s) in sheets.enumerated() { parts.append(("xl/worksheets/sheet\(i + 1).xml", s.xml)) }
        return parts.map { ($0.0, Data($0.1.utf8)) }
    }

    public static func write(_ model: ExportModel, to url: URL) throws {
        try write(build(model), to: url)
    }

    static func write(_ parts: [(path: String, data: Data)], to url: URL) throws {
        var zip = ZipBuilder()
        for p in parts { zip.add(p.path, data: p.data) }
        try zip.write(to: url)
    }
}
