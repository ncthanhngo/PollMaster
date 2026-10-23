import Foundation

/// Human readable rendering of values, shared by UI, exports and record titles.
public enum ValueFormatter {
    /// Rounded half away from zero to `decimals` (when given), no trailing zeros, "." separator, shortest representation.
    public static func number(_ x: Double, decimals: Int? = nil) -> String {
        var v = x
        if let d = decimals { v = FormulaMath.round(x, d) }
        guard v.isFinite else { return "" }
        if v == v.rounded(), abs(v) < 1e15 { return String(Int64(v)) }
        return Decimal(string: "\(v)").map { "\($0)" } ?? "\(v)"
    }

    /// `mm:ss`, or `h:mm:ss` from one hour.
    public static func mmss(_ seconds: Double) -> String {
        let s = Int(seconds.rounded())
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) : String(format: "%02d:%02d", s / 60, s % 60)
    }

    /// Scalar (single sample / part) display without flag decoration or unit.
    public static func scalar(_ v: JSONValue?, field: Field, other: [String: String]? = nil) -> String {
        guard let v, !v.isEmpty else { return "" }
        switch field.type {
        case .singleChoice:
            guard let s = v.string else { return "" }
            return withOther(field.optionLabel(s), s, field, other)
        case .multiChoice:
            return (v.array ?? []).compactMap(\.string).map { withOther(field.optionLabel($0), $0, field, other) }.joined(separator: ", ")
        case .yesNo:
            let l = field.labels ?? ["Có", "Không"]
            if v.string == "unknown" { return "KĐĐ" }
            guard let b = v.bool else { return "" }
            return b ? (l.first ?? "Có") : (l.count > 1 ? l[1] : "Không")
        case .number, .integer, .computed:
            return v.double.map { number($0, decimals: field.decimals ?? (field.type == .computed ? 2 : nil)) } ?? ""
        case .rating: return v.double.map { "\(number($0))/\(field.ratingMax)" } ?? ""
        case .compass: return v.double.map { number($0, decimals: 0) + "°" } ?? ""
        case .elapsed: return v.double.map(mmss) ?? ""
        case .date:
            return v.string.flatMap { ISODate.parse($0, pattern: "yyyy-MM-dd") }.map { ISODate.format($0, "dd/MM/yyyy") } ?? (v.string ?? "")
        case .month:
            return v.string.flatMap { ISODate.parse($0, pattern: "yyyy-MM") }.map { ISODate.format($0, "MM/yyyy") } ?? (v.string ?? "")
        case .datetime:
            return v.string.flatMap(ISODate.parse).map { ISODate.format($0, "dd/MM/yyyy HH:mm") } ?? (v.string ?? "")
        case .sessionTime:
            guard let st = SessionTime(v) else { return "" }
            let a = ISODate.format(st.start, "dd/MM/yyyy HH:mm")
            return "\(a) – " + (st.end.map { ISODate.format($0, "dd/MM/yyyy HH:mm") } ?? "…")
        case .gps:
            guard let g = GPSValue(v) else { return "" }
            let acc = g.accuracy.map { " (±\(number($0, decimals: 0)) m)" } ?? ""
            return "\(String(format: "%.6f", g.lat)), \(String(format: "%.6f", g.lng))\(acc)"
        case .photo, .video, .audio:
            let fv = FieldValue(v: v)
            return fv.mediaInV.map(\.fileName).joined(separator: ", ")
        case .signature: return v.object?["file"]?.string.map { ($0 as NSString).lastPathComponent } ?? ""
        default: return v.string ?? v.double.map { number($0) } ?? ""
        }
    }

    private static func withOther(_ label: String, _ value: String, _ field: Field, _ other: [String: String]?) -> String {
        guard field.options?.first(where: { $0.value == value })?.withText == true, let t = other?[value], !t.isEmpty else { return label }
        return "\(label): \(t)"
    }

    private static func partsText(_ field: Field, _ fv: FieldValue, lookup: (String) -> FieldValue?) -> String {
        PartVisibility.visibleParts(of: field, value: fv, lookup: lookup).compactMap { p in
            let text = scalar(fv.parts?[p.id], field: p.asField)
            guard !text.isEmpty else { return nil }
            return "\(p.label): \(text)" + (p.unit.map { " \($0)" } ?? "")
        }.joined(separator: "; ")
    }

    /// Display text of a whole field value (samples joined by " | ", parts appended, flags applied).
    public static func display(_ field: Field, _ fv: FieldValue?, includeUnit: Bool = false, lookup: (String) -> FieldValue? = { _ in nil }) -> String {
        guard let fv else { return "" }
        if fv.flag == .notMeasurable { return "KĐĐ" + (fv.reason.map { $0.isEmpty ? "" : " (\($0))" } ?? "") }
        var main: String
        if let n = field.samples, field.type != .computed {
            let items = (0..<n).map { scalar(fv.sample($0), field: field, other: fv.other) }
            main = items.allSatisfy(\.isEmpty) ? "" : items.map { $0.isEmpty ? "–" : $0 }.joined(separator: " | ")
        } else {
            main = scalar(fv.v, field: field, other: fv.other)
        }
        if includeUnit, !main.isEmpty, let u = field.unit, !u.isEmpty, field.type != .gps { main += " \(u)" }
        let parts = partsText(field, fv, lookup: lookup)
        if !parts.isEmpty { main = main.isEmpty ? parts : "\(main); \(parts)" }
        if fv.flag == .estimate, !main.isEmpty { main = "~" + main }
        return main
    }

    /// Computed value display (rounded by `decimals`, samples joined).
    public static func displayComputed(_ field: Field, _ value: JSONValue?, includeUnit: Bool = false) -> String {
        guard let value else { return "" }
        var text: String
        if case .array(let a) = value {
            let items = a.map { scalar($0, field: field) }
            text = items.allSatisfy(\.isEmpty) ? "" : items.map { $0.isEmpty ? "–" : $0 }.joined(separator: " | ")
        } else if let b = value.bool { text = b ? "Có" : "Không" }
        else { text = scalar(value, field: field) }
        if includeUnit, !text.isEmpty, let u = field.unit { text += " \(u)" }
        return text
    }
}

public enum RecordNaming {
    private static let invalid = CharacterSet(charactersIn: "/\\:*?\"<>|").union(.whitespacesAndNewlines)

    /// Each run of invalid characters becomes one "-"; leading/trailing "-" are removed.
    public static func sanitize(_ s: String) -> String {
        var out = "", lastDash = false
        for u in s.unicodeScalars {
            if invalid.contains(u) {
                if !lastDash { out.append("-") }
                lastDash = true
            } else { out.unicodeScalars.append(u); lastDash = false }
        }
        return out.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    }

    /// Sanitized raw value (`v`) of the recordCode field; nil when empty (callers fall back to the record id).
    public static func recordCode(_ t: Template, _ r: Record) -> String? {
        guard let f = t.recordCodeField, let v = r.values[f.id], v.flag != .notMeasurable, let raw = v.v else { return nil }
        let text: String
        switch raw {
        case .string(let s): text = s
        case .number(let d): text = ValueFormatter.number(d)
        default: return nil
        }
        return sanitize(text).nilIfEmpty
    }

    private static let titleTrim = CharacterSet(charactersIn: " –-/·").union(.whitespacesAndNewlines)

    static func newRecordTitle(_ r: Record) -> String { "Phiếu mới · " + ISODate.format(r.createdAt, "dd/MM/yyyy HH:mm") }

    /// `recordTitle` with `{fieldId}` replaced and stray separators trimmed; "Phiếu mới · dd/MM/yyyy HH:mm" when nothing is filled.
    public static func title(_ t: Template, _ r: Record) -> String {
        guard let pattern = t.recordTitle else { return newRecordTitle(r) }
        var anyFilled = false
        let out = replace(pattern) { id in
            guard let f = t.field(id) else { return "" }
            let s = ValueFormatter.display(f, r.values[id])
            if !s.isEmpty { anyFilled = true }
            return s
        }
        let trimmed = out.trimmingCharacters(in: titleTrim)
        return anyFilled && !trimmed.isEmpty ? trimmed : newRecordTitle(r)
    }

    /// `{fieldId}` used in media names; table columns are `{tableId}-{columnId}`.
    public static func mediaFieldId(table: String?, column: String) -> String { table.map { "\($0)-\(column)" } ?? column }

    /// Smallest number >= 1 not in `taken`.
    public static func smallestFreeSeq(taken: Set<Int>) -> Int {
        var n = 1
        while taken.contains(n) { n += 1 }
        return n
    }

    /// Media file name (no extension) from `mediaNamePattern`.
    public static func mediaName(_ t: Template, _ r: Record, fieldId: String, seq: Int) -> String {
        let pattern = t.mediaNamePattern ?? "{recordCode}_{fieldId}_{seq}"
        let code = recordCode(t, r) ?? r.id
        return sanitize(replace(pattern) { key in
            switch key {
            case "recordCode": return code
            case "fieldId": return fieldId
            case "seq": return String(format: "%02d", seq)
            default: return ""
            }
        })
    }

    public static func exportBaseName(_ t: Template, _ r: Record, now: Date = Date()) -> String {
        "\(recordCode(t, r) ?? r.id)_\(ISODate.format(now, "yyyyMMdd-HHmm"))"
    }

    static func replace(_ pattern: String, _ resolve: (String) -> String) -> String {
        var out = "", key = "", inKey = false
        for ch in pattern {
            if ch == "{" && !inKey { inKey = true; key = "" }
            else if ch == "}" && inKey { out += resolve(key); inKey = false }
            else if inKey { key.append(ch) }
            else { out.append(ch) }
        }
        return inKey ? out + "{" + key : out
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
