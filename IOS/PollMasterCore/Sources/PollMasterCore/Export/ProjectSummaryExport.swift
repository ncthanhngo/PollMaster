import Foundation

/// "Xuất tổng hợp dự án" (schema §9.4): one xlsx, a sheet per template with one row per record, plus a sheet per table.
public enum ProjectSummaryExport {
    public enum Filter: String, CaseIterable, Sendable { case all, completed }

    /// Row 1 of the fixed columns (row 2 carries `fixedHeaders`).
    public static let fixedKeys = ["recordCode", "title", "status", "createdAt", "updatedAt", "completedAt"]
    public static let fixedHeaders = ["Mã phiếu", "Tiêu đề", "Trạng thái", "Tạo lúc", "Cập nhật lúc", "Hoàn thành lúc"]

    public struct Output: Sendable {
        public var parts: [(path: String, data: Data)]
        /// Records written as rows.
        public var recordCount: Int
        /// Records left out because their template is not in the library.
        public var skipped: Int
    }

    public static func fileName(_ p: Project, now: Date = Date()) -> String {
        "\(p.code)_tong-hop_\(ISODate.format(now, "yyyyMMdd-HHmm")).xlsx"
    }

    public static func recordsZipName(_ p: Project, now: Date = Date()) -> String {
        "\(p.code)_phieu_\(ISODate.format(now, "yyyyMMdd-HHmm")).zip"
    }

    public static func select(_ records: [Record], filter: Filter) -> [Record] {
        records.filter { filter == .all || $0.status == .completed }.sorted { ($0.createdAt, $0.id) < ($1.createdAt, $1.id) }
    }

    private static func status(_ r: Record) -> String {
        r.status == .completed ? "Hoàn thành" : (r.returned ? "Cần sửa" : "Nháp")
    }

    private static func time(_ d: Date?) -> String { d.map { ISODate.format($0, "dd/MM/yyyy HH:mm") } ?? "" }

    /// Column ids + labels of `lists`, in order, newer template versions first; ids seen again are skipped.
    private static func union(_ lists: [[(id: String, label: String)]]) -> [(id: String, label: String)] {
        var seen = Set<String>(), out: [(id: String, label: String)] = []
        for l in lists { for c in l where seen.insert(c.id).inserted { out.append(c) } }
        return out
    }

    private static func cell(_ number: Double?, _ text: String) -> XLSXExport.Cell { number.map { .number($0) } ?? .text(text) }

    /// `library`: every installed template version. `records` must already belong to `project`.
    public static func build(project: Project, records: [Record], library: [Template], filter: Filter = .all, now: Date = Date()) -> Output {
        let byKey = Dictionary(library.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        let chosen = select(records, filter: filter)
        var ids = project.templates.map(\.id)
        for r in chosen where !ids.contains(r.templateId) { ids.append(r.templateId) }
        ids = ids.reduce(into: []) { if !$0.contains($1) { $0.append($1) } }

        var used = Set<String>(), sheets: [(name: String, xml: String)] = [], written = 0
        for id in ids {
            let mine = chosen.filter { $0.templateId == id }
            let versions = Set(project.templates.filter { $0.id == id }.map(\.version) + mine.map(\.templateVersion))
            let templates = versions.compactMap { byKey["\(id)@\($0)"] }
                .sorted { TemplateVersion.isNewer($0.version, than: $1.version) }
            guard !templates.isEmpty else { continue }
            let blank = templates.map { t in ExportModelBuilder.build(t, Record(templateId: t.id, templateVersion: t.version), now: now) }
            let fields = union(blank.map { $0.entries.map { ($0.id, $0.label) } })
            let tableIds = union(blank.map { $0.tables.map { ($0.id, $0.title) } })
            var tableColumns: [String: [(id: String, label: String)]] = [:]
            for tb in tableIds { tableColumns[tb.id] = union(blank.map { m in m.tables.first { $0.id == tb.id }?.columns ?? [] }) }

            var rows: [[XLSXExport.Cell]] = [
                fixedKeys.map { XLSXExport.Cell.text($0) } + fields.map { .text($0.id) },
                fixedHeaders.map { .text($0) } + fields.map { .text($0.label) },
            ]
            var tableRows: [String: [[XLSXExport.Cell]]] = [:]
            for tb in tableIds {
                let cols = tableColumns[tb.id] ?? []
                tableRows[tb.id] = [[.empty] + cols.map { .text($0.id) }, [.text("Mã phiếu")] + cols.map { .text($0.label) }]
            }
            for r in mine {
                guard let t = byKey[r.key] else { continue }
                let m = ExportModelBuilder.build(t, r, now: now)
                let entries = Dictionary(m.entries.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
                let code = RecordNaming.recordCode(t, r) ?? r.id
                rows.append([.text(code), .text(m.recordTitle), .text(status(r)), .text(time(r.createdAt)), .text(time(r.updatedAt)), .text(time(r.completedAt))]
                    + fields.map { f in entries[f.id].map { cell($0.number, $0.display) } ?? .empty })
                for tb in m.tables {
                    let cols = tableColumns[tb.id] ?? []
                    let index = Dictionary(tb.columns.enumerated().map { ($1.id, $0) }, uniquingKeysWith: { a, _ in a })
                    for row in tb.rows {
                        tableRows[tb.id, default: []].append([.text(code)] + cols.map { c in
                            index[c.id].flatMap { $0 < row.count ? row[$0] : nil }.map { cell($0.number, $0.text) } ?? .empty
                        })
                    }
                }
                written += 1
            }
            let width = [Double](repeating: 16, count: fixedHeaders.count + fields.count)
            sheets.append((XLSXExport.sheetName(id, used: &used), XLSXExport.sheetXML(rows: rows, widths: width.enumerated().map { $0.offset == 1 ? 32 : $0.element }, headerRows: 2)))
            for tb in tableIds {
                let r = tableRows[tb.id] ?? []
                sheets.append((XLSXExport.sheetName("\(id)-\(tb.id)", used: &used),
                               XLSXExport.sheetXML(rows: r, widths: [Double](repeating: 18, count: (tableColumns[tb.id]?.count ?? 0) + 1), headerRows: 2)))
            }
        }
        if sheets.isEmpty { // nothing to list: still a valid workbook
            sheets.append((XLSXExport.sheetName("Tổng hợp", used: &used),
                           XLSXExport.sheetXML(rows: [fixedKeys.map { .text($0) }, fixedHeaders.map { .text($0) }], widths: fixedHeaders.map { _ in 16 }, headerRows: 2)))
        }
        return Output(parts: XLSXExport.workbook(sheets), recordCount: written, skipped: chosen.count - written)
    }

    public static func write(project: Project, records: [Record], library: [Template], filter: Filter = .all,
                             now: Date = Date(), to url: URL) throws -> Output {
        let out = build(project: project, records: records, library: library, filter: filter, now: now)
        try XLSXExport.write(out.parts, to: url)
        return out
    }

    /// Zip of every record (`<code|id>/record.json`, `export.json`, media) for the given records.
    public static func writeRecordsZip(project: Project, records: [Record], library: [Template], store: RecordStore, now: Date = Date(), to url: URL) throws {
        let byKey = Dictionary(library.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        var zip = ZipBuilder(), usedFolders = Set<String>()
        for r in records {
            let t = byKey[r.key]
            var folder = t.flatMap { RecordNaming.recordCode($0, r) } ?? r.id
            if !usedFolders.insert(folder).inserted { folder += "_\(r.id.prefix(8))"; usedFolders.insert(folder) }
            zip.add("\(folder)/record.json", data: try JSONCoding.makeEncoder().encode(r))
            if let t { zip.add("\(folder)/export.json", data: try ExportJSON.build(t, r, project: project)) }
            for rel in Set(RecordStore.mediaFiles(of: r)).sorted() {
                let file = store.fileURL(r.id, rel)
                if FileManager.default.fileExists(atPath: file.path) { zip.add("\(folder)/\(rel)", file: file) }
            }
        }
        try zip.write(to: url)
    }
}

extension Record {
    var key: String { "\(templateId)@\(templateVersion)" }
}
