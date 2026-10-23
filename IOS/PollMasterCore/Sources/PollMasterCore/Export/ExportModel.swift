import Foundation

public struct ExportEntry: Equatable, Sendable {
    public var sectionTitle: String
    public var id: String
    public var label: String
    public var method: String?
    public var display: String
    public var number: Double?        // set when the value is a plain number (kept numeric in xlsx)
    public var unit: String
    public var flag: String           // "~", "KĐĐ" or ""
    public var reason: String
    public var note: String
    public var isComputed: Bool
}

public struct ExportCell: Equatable, Sendable {
    public var text: String
    public var number: Double?
}

public struct ExportTable: Equatable, Sendable {
    public var id: String
    public var title: String
    public var note: String?
    public var columns: [(id: String, label: String)]
    public var isFixed: Bool
    public var rowTitles: [String]           // "{rowLabel} {n}" for repeat tables, empty for fixed tables
    public var rows: [[ExportCell]]

    public static func == (a: ExportTable, b: ExportTable) -> Bool {
        a.id == b.id && a.rows == b.rows && a.rowTitles == b.rowTitles && a.columns.map(\.id) == b.columns.map(\.id)
    }
}

public enum ExportItem: Equatable, Sendable {
    case heading(title: String, note: String?)
    case entry(ExportEntry)
    case table(ExportTable)
}

public struct ExportPhoto: Equatable, Sendable {
    public var caption: String
    public var file: String           // relative to record folder
}

public struct ExportSection: Equatable, Sendable {
    public var id: String
    public var title: String
    public var note: String?
    public var items: [ExportItem]
    public var photos: [ExportPhoto]
}

public struct ExportSignature: Equatable, Sendable {
    public var label: String
    public var name: String
    public var signedAt: Date?
    public var file: String?
}

public struct ExportModel: Sendable {
    public var title: String
    public var subtitle: String?
    public var recordTitle: String
    public var baseName: String
    public var sections: [ExportSection]      // header first
    public var signatures: [ExportSignature]

    public var entries: [ExportEntry] {
        sections.flatMap { $0.items.compactMap { if case .entry(let e) = $0 { return e } else { return nil } } }
    }
    public var tables: [ExportTable] {
        sections.flatMap { $0.items.compactMap { if case .table(let t) = $0 { return t } else { return nil } } }
    }
}

public enum ExportModelBuilder {
    private static let imageExt: Set<String> = ["jpg", "jpeg", "png", "heic"]
    private static func isImage(_ f: String) -> Bool { imageExt.contains((f as NSString).pathExtension.lowercased()) }

    /// Numeric spreadsheet value (rounded as displayed) for plain number/integer cells; nil for anything else.
    public static func numericValue(_ f: Field, _ fv: FieldValue?) -> Double? {
        guard [.number, .integer].contains(f.type), f.samples == nil, f.parts == nil, fv?.flag == nil, let d = fv?.v?.double else { return nil }
        return f.decimals.map { FormulaMath.round(d, $0) } ?? d
    }

    public static func build(_ t: Template, _ r: Record, now: Date = Date()) -> ExportModel {
        let computed = ComputedEngine(template: t, record: r).computeAll()
        let recordLookup: (String) -> FieldValue? = { r.values[$0] }

        func entry(_ f: Field, section: String) -> ExportEntry {
            let fv = r.values[f.id]
            var e = ExportEntry(sectionTitle: section, id: f.id, label: f.label, method: f.method, display: "", number: nil,
                                unit: f.type == .gps ? "" : (f.unit ?? ""), flag: "", reason: fv?.reason ?? "", note: fv?.note ?? "", isComputed: f.isComputed)
            if f.isComputed {
                let v = computed[f.id]
                e.display = ValueFormatter.displayComputed(f, v)
                if let d = v?.double { e.number = FormulaMath.round(d, f.displayDecimals) }
            } else {
                e.display = ValueFormatter.display(f, fv, lookup: recordLookup)
                e.number = numericValue(f, fv)
                e.flag = fv?.flag == .estimate ? "~" : (fv?.flag == .notMeasurable ? "KĐĐ" : "")
                if fv?.flag == .notMeasurable { e.number = nil }
            }
            return e
        }

        func files(_ f: Field, _ fv: FieldValue?) -> [ExportPhoto] {
            guard let fv else { return [] }
            return (fv.mediaInV + (fv.media ?? [])).filter { isImage($0.file) }.map { ExportPhoto(caption: "\(f.id) – \($0.fileName)", file: $0.file) }
        }

        func table(_ tb: TableDef) -> (ExportTable, [ExportPhoto]) {
            let fixed = Dictionary((tb.rows ?? []).map { ($0.id, $0.fixed) }, uniquingKeysWith: { a, _ in a })
            var photos: [ExportPhoto] = []
            var titles: [String] = []
            let rows: [[ExportCell]] = r.rows(of: tb).enumerated().map { idx, row in
                if !tb.isFixed { titles.append("\(tb.rowLabel ?? "Dòng") \(idx + 1)") }
                let lookup: (String) -> FieldValue? = { row.values[$0] ?? r.values[$0] }
                return tb.columns.map { col in
                    let fv = row.values[col.id]
                    photos += files(col, fv)
                    if fv == nil || fv!.isBlank, let fx = fixed[row.id]?[col.id] {
                        let text = fx.string ?? fx.double.map { ValueFormatter.number($0, decimals: col.decimals) } ?? ""
                        return ExportCell(text: text, number: numericValue(col, FieldValue(v: fx)))
                    }
                    return ExportCell(text: ValueFormatter.display(col, fv, lookup: lookup), number: numericValue(col, fv))
                }
            }
            return (ExportTable(id: tb.id, title: tb.title, note: tb.note, columns: tb.columns.map { ($0.id, $0.label) },
                                isFixed: tb.isFixed, rowTitles: titles, rows: rows), photos)
        }

        func section(id: String, title: String, note: String?, items: [Item]) -> ExportSection {
            var out: [ExportItem] = [], photos: [ExportPhoto] = []
            for item in items {
                switch item {
                case .heading(let h): out.append(.heading(title: h.title, note: h.note))
                case .field(let f):
                    out.append(.entry(entry(f, section: title)))
                    photos += files(f, r.values[f.id])
                case .table(let tb):
                    let (tab, ph) = table(tb)
                    out.append(.table(tab)); photos += ph
                }
            }
            return ExportSection(id: id, title: title, note: note, items: out, photos: photos)
        }

        var sections = [section(id: Validator.headerSectionId, title: Validator.headerSectionTitle, note: nil,
                                items: t.header.map { .field($0) })]
        sections += t.sections.map { section(id: $0.id, title: $0.title, note: $0.note, items: $0.items) }
        let sigs = t.signatures.map { s -> ExportSignature in
            let e = r.signatures[s.id]
            return ExportSignature(label: s.label, name: e?.name ?? "", signedAt: e?.signedAt, file: e?.file)
        }
        return ExportModel(title: t.title, subtitle: t.subtitle, recordTitle: RecordNaming.title(t, r),
                           baseName: RecordNaming.exportBaseName(t, r, now: now), sections: sections, signatures: sigs)
    }
}
