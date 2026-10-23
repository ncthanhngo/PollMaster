import Foundation

public struct ValidationIssue: Identifiable, Equatable, Sendable {
    public enum Kind: Sendable { case required, reason, range, minCount, minRows }
    public var kind: Kind
    public var sectionId: String        // "header" or section id
    public var sectionTitle: String
    public var fieldId: String          // field id, table id
    public var rowId: String?
    public var columnId: String?
    public var label: String
    public var message: String
    public var id: String { [fieldId, rowId, columnId, message].compactMap { $0 }.joined(separator: "/") }
}

public struct RequiredProgress: Equatable, Sendable {
    public var done: Int
    public var total: Int
    public init(done: Int, total: Int) { self.done = done; self.total = total }
    public var fraction: Double { total == 0 ? 1 : Double(done) / Double(total) }
}

public enum Validator {
    public static let headerSectionId = "header"
    public static let headerSectionTitle = "Thông tin đầu biên bản"

    // MARK: single field

    /// Is a required field satisfied by this value?
    public static func isFilled(_ field: Field, _ fv: FieldValue?, lookup: (String) -> FieldValue?) -> Bool {
        guard let fv else { return false }
        if fv.flag == .notMeasurable { return !isBlankText(fv.reason) }
        if field.type == .composite {
            let parts = PartVisibility.visibleParts(of: field, value: fv, lookup: lookup)
            return !parts.isEmpty && parts.allSatisfy { fv.parts?[$0.id]?.isBlank == false }
        }
        if field.isMedia { return !fv.mediaInV.isEmpty }
        if field.type == .signature { return fv.v?.object?["file"] != nil }
        if field.type == .sessionTime { return SessionTime(fv.v) != nil }
        return fv.v?.isBlank == false
    }

    private static func isBlankText(_ s: String?) -> Bool { s?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true }

    /// Problems of one field value (required handled only if `field.required`).
    public static func issues(for field: Field, _ fv: FieldValue?, lookup: (String) -> FieldValue?) -> [(ValidationIssue.Kind, String)] {
        if field.isComputed { return [] }
        var out: [(ValidationIssue.Kind, String)] = []
        if fv?.flag == .notMeasurable {
            if isBlankText(fv?.reason) { out.append((.reason, "Cần nhập lý do KĐĐ")) }
            return out
        }
        if field.required, !isFilled(field, fv, lookup: lookup) { out.append((.required, "Bắt buộc nhập")) }
        if let fv, field.type == .number || field.type == .integer {
            let nums = field.samples != nil ? (fv.v?.array ?? []).compactMap(\.double) : [fv.v?.double].compactMap { $0 }
            if let m = field.min, nums.contains(where: { $0 < m }) { out.append((.range, "Giá trị phải ≥ \(ValueFormatter.number(m))")) }
            if let m = field.max, nums.contains(where: { $0 > m }) { out.append((.range, "Giá trị phải ≤ \(ValueFormatter.number(m))")) }
        }
        if let n = field.minCount, field.isMedia {
            let c = fv?.mediaInV.count ?? 0
            if c < n, field.required || c > 0 { out.append((.minCount, "Cần tối thiểu \(n) tệp (hiện có \(c))")) }
        }
        return out
    }

    // MARK: record

    private static func rowLookup(_ row: Row, _ record: Record) -> (String) -> FieldValue? {
        { row.values[$0] ?? record.values[$0] }
    }

    public static func validate(_ t: Template, _ r: Record) -> [ValidationIssue] {
        var out: [ValidationIssue] = []
        let recordLookup: (String) -> FieldValue? = { r.values[$0] }

        func add(_ field: Field, section: (String, String), row: Row?, table: TableDef?, lookup: (String) -> FieldValue?, fv: FieldValue?) {
            for (kind, msg) in issues(for: field, fv, lookup: lookup) {
                out.append(ValidationIssue(kind: kind, sectionId: section.0, sectionTitle: section.1,
                                           fieldId: table?.id ?? field.id, rowId: row?.id,
                                           columnId: table == nil ? nil : field.id,
                                           label: table == nil ? field.label : "\(table!.title) – \(field.label)", message: msg))
            }
        }

        let head = (headerSectionId, headerSectionTitle)
        for f in t.header { add(f, section: head, row: nil, table: nil, lookup: recordLookup, fv: r.values[f.id]) }
        for s in t.sections {
            let sec = (s.id, s.title)
            for item in s.items {
                switch item {
                case .field(let f): add(f, section: sec, row: nil, table: nil, lookup: recordLookup, fv: r.values[f.id])
                case .heading: break
                case .table(let tb):
                    let rows = r.rows(of: tb).filter { !$0.isEmpty }
                    let need = Swift.max(tb.minRows, tb.required ? 1 : 0)
                    if tb.required, rows.count < need {
                        out.append(ValidationIssue(kind: .minRows, sectionId: s.id, sectionTitle: s.title, fieldId: tb.id, rowId: nil,
                                                   columnId: nil, label: tb.title, message: "Cần tối thiểu \(need) dòng (hiện có \(rows.count))"))
                    }
                    for row in rows {
                        for col in tb.columns {
                            add(col, section: sec, row: row, table: tb, lookup: rowLookup(row, r), fv: row.values[col.id])
                        }
                    }
                }
            }
        }
        return out
    }

    /// Required items filled / total required (fields and tables).
    public static func progress(_ t: Template, _ r: Record) -> RequiredProgress {
        var done = 0, total = 0
        let lookup: (String) -> FieldValue? = { r.values[$0] }
        for f in t.allFields where f.required && !f.isComputed {
            total += 1
            if issues(for: f, r.values[f.id], lookup: lookup).isEmpty { done += 1 }
        }
        let tableIssues = Set(validate(t, r).map(\.fieldId))
        for tb in t.allTables where tb.required {
            total += 1
            // Done only with no issue at all on the table, including row-level ones.
            if !tableIssues.contains(tb.id) { done += 1 }
        }
        return RequiredProgress(done: done, total: total)
    }
}
