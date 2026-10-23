import Foundation

/// A non-blocking finding ("Cần xem lại") or a checker note ("Ghi chú kiểm tra").
public struct ReviewFinding: Identifiable, Equatable, Sendable {
    public enum Kind: Sendable { case warning, review }
    public var kind: Kind
    public var sectionId: String
    public var sectionTitle: String
    public var fieldId: String          // field id or table id
    public var rowId: String?
    public var columnId: String?
    public var label: String
    public var message: String
    public var id: String { [kind == .warning ? "w" : "r", fieldId, rowId, columnId, message].compactMap { $0 }.joined(separator: "/") }
}

public enum Warnings {
    private static func range(_ lo: Double?, _ hi: Double?, unit: String?) -> String? {
        guard lo != nil || hi != nil else { return nil }
        let u = unit.flatMap { $0.isEmpty ? nil : " \($0)" } ?? ""
        let body: String
        switch (lo, hi) {
        case let (l?, h?): body = "\(ValueFormatter.number(l))–\(ValueFormatter.number(h))"
        case let (l?, nil): body = "≥ \(ValueFormatter.number(l))"
        case let (nil, h?): body = "≤ \(ValueFormatter.number(h))"
        default: return nil
        }
        return "Ngoài khoảng thường gặp \(body)\(u)"
    }

    private static func outside(_ nums: [Double], _ lo: Double?, _ hi: Double?) -> Bool {
        nums.contains { ($0 < (lo ?? -.infinity)) || ($0 > (hi ?? .infinity)) }
    }

    /// Warning texts of one field value: `warnMin/warnMax` (value, samples, number parts) and GPS accuracy.
    public static func messages(for field: Field, _ fv: FieldValue?, lookup: (String) -> FieldValue? = { _ in nil }) -> [String] {
        guard let fv, fv.flag != .notMeasurable, !field.isComputed else { return [] }
        var out: [String] = []
        if field.type == .number || field.type == .integer {
            let nums = field.samples != nil ? (fv.v?.array ?? []).compactMap(\.double) : [fv.v?.double].compactMap { $0 }
            if outside(nums, field.warnMin, field.warnMax), let m = range(field.warnMin, field.warnMax, unit: field.unit) { out.append(m) }
        }
        for p in PartVisibility.visibleParts(of: field, value: fv, lookup: lookup) where p.type == .number || p.type == .integer {
            if let n = fv.parts?[p.id]?.double, outside([n], p.warnMin, p.warnMax), let m = range(p.warnMin, p.warnMax, unit: p.unit) {
                out.append(m)
            }
        }
        if field.type == .gps, let acc = GPSValue(fv.v)?.accuracy, acc > field.gpsMaxAccuracy {
            out.append("Độ chính xác GPS kém (±\(ValueFormatter.number(acc, decimals: 0)) m)")
        }
        return out
    }

    public static func plannedDistanceMessage(_ meters: Double) -> String? {
        meters > 100 ? "Cách điểm kế hoạch \(ValueFormatter.number(meters, decimals: 0)) m" : nil
    }

    /// Every warning of a record, grouped by position (header first). Never counted as errors or progress.
    public static func all(_ t: Template, _ r: Record, sites: [Site] = []) -> [ReviewFinding] {
        var out: [ReviewFinding] = []
        let recordLookup: (String) -> FieldValue? = { r.values[$0] }
        let distances = Dictionary(SiteLookup.plannedDistances(t, r, sites: sites).map { ($0.fieldId, $0.meters) }, uniquingKeysWith: { a, _ in a })

        func add(_ f: Field, _ sec: (String, String), table: TableDef? = nil, row: Row? = nil, fv: FieldValue?, lookup: (String) -> FieldValue?) {
            var msgs = messages(for: f, fv, lookup: lookup)
            if table == nil, let d = distances[f.id], let m = plannedDistanceMessage(d) { msgs.append(m) }
            for m in msgs {
                out.append(ReviewFinding(kind: .warning, sectionId: sec.0, sectionTitle: sec.1, fieldId: table?.id ?? f.id, rowId: row?.id,
                                         columnId: table == nil ? nil : f.id, label: table == nil ? f.label : "\(table!.title) – \(f.label)", message: m))
            }
        }
        let head = (Validator.headerSectionId, Validator.headerSectionTitle)
        for f in t.header { add(f, head, fv: r.values[f.id], lookup: recordLookup) }
        for s in t.sections {
            for item in s.items {
                switch item {
                case .field(let f): add(f, (s.id, s.title), fv: r.values[f.id], lookup: recordLookup)
                case .heading: break
                case .table(let tb):
                    for row in r.rows(of: tb) where !row.isEmpty {
                        for col in tb.columns {
                            add(col, (s.id, s.title), table: tb, row: row, fv: row.values[col.id], lookup: { row.values[$0] ?? r.values[$0] })
                        }
                    }
                }
            }
        }
        return out
    }

    /// Checker notes (`FieldValue.review`) on header and section fields.
    public static func reviewNotes(_ t: Template, _ r: Record) -> [ReviewFinding] {
        var out: [ReviewFinding] = []
        func add(_ f: Field, _ sec: (String, String)) {
            guard let text = r.values[f.id]?.review else { return }
            out.append(ReviewFinding(kind: .review, sectionId: sec.0, sectionTitle: sec.1, fieldId: f.id, rowId: nil, columnId: nil,
                                     label: f.label, message: text))
        }
        t.header.forEach { add($0, (Validator.headerSectionId, Validator.headerSectionTitle)) }
        for s in t.sections { s.fields.forEach { add($0, (s.id, s.title)) } }
        return out
    }
}
