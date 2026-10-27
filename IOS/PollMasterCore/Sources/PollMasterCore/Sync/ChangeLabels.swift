import Foundation

/// Shows a change-log entry (schema §10.3): the label of a `path` and the text of an old / new value. Pure; same rules on iOS and
/// Android (`shared/sync-test-vectors.json`, sections `labels` and `values`). Unknown template or id -> the raw `path`.
public enum ChangeLabels {
    public static let empty = "(trống)"

    private static let subLabels = ["note": "Ghi chú", "review": "Kiểm tra", "flag": "Cờ", "reason": "Lý do", "other": "Khác", "media": "Tệp"]
    private static let rootLabels = ["status": "Trạng thái", "returned": "Trả lại để sửa", "completedAt": "Hoàn thành lúc"]

    /// Where a path points. `sub` = the key after the field / column id (`v` is the plain value, `parts.<id>` a sub-cell).
    struct Target {
        enum Kind { case root, value, table, signature }
        var kind: Kind
        var id = ""              // field, table or signature id
        var row: String?         // `[rowId]`
        var column: String?
        var sub: String?
    }

    static func parse(_ path: String) -> Target? {
        if rootLabels[path] != nil { return Target(kind: .root, id: path) }
        if path.hasPrefix("signatures.") { return Target(kind: .signature, id: String(path.dropFirst("signatures.".count))) }
        if path.hasPrefix("values.") {
            let rest = path.dropFirst("values.".count)
            guard let dot = rest.firstIndex(of: ".") else { return Target(kind: .value, id: String(rest)) }
            return Target(kind: .value, id: String(rest[..<dot]), sub: String(rest[rest.index(after: dot)...]))
        }
        guard path.hasPrefix("tables."), let open = path.firstIndex(of: "["), let close = path.firstIndex(of: "]"), open < close else { return nil }
        var t = Target(kind: .table, id: String(path[path.index(path.startIndex, offsetBy: "tables.".count)..<open]),
                       row: String(path[path.index(after: open)..<close]))
        let rest = path[path.index(after: close)...]
        guard rest.hasPrefix(".") else { return rest.isEmpty ? t : nil }
        let cell = rest.dropFirst()
        if let dot = cell.firstIndex(of: ".") {
            t.column = String(cell[..<dot]); t.sub = String(cell[cell.index(after: dot)...])
        } else { t.column = String(cell) }
        return t
    }

    /// The field (or table column) a target points at.
    private static func field(_ t: Target, _ template: Template) -> Field? {
        switch t.kind {
        case .value: return template.field(t.id)
        case .table: return t.column.flatMap { c in template.table(t.id)?.columns.first { $0.id == c } }
        default: return nil
        }
    }

    /// Suffix for the key after the field id: "" for the plain value, nil when the key is unknown.
    private static func subLabel(_ sub: String?, _ f: Field) -> String? {
        guard let sub, sub != "v" else { return "" }
        if let l = subLabels[sub] { return l }
        guard sub.hasPrefix("parts.") else { return nil }
        return f.parts?.first { $0.id == String(sub.dropFirst("parts.".count)) }?.label
    }

    /// Label of a change. `serverLabel` (filled by the server when sure) wins; `record` supplies the row positions of tables.
    public static func label(path: String, serverLabel: String = "", template: Template?, record: Record?) -> String {
        if !serverLabel.isEmpty { return serverLabel }
        guard let template, let t = parse(path) else { return path }
        switch t.kind {
        case .root: return rootLabels[t.id] ?? path
        case .signature: return template.signatures.first { $0.id == t.id }?.label ?? path
        case .value:
            guard let f = field(t, template), let sub = subLabel(t.sub, f) else { return path }
            return [f.label, sub].filter { !$0.isEmpty }.joined(separator: " › ")
        case .table:
            guard let table = template.table(t.id), let record, let rowId = t.row,
                  let n = record.rows(of: table).firstIndex(where: { $0.id == rowId }) else { return path }
            let row = "\(table.title) (\(table.rowLabel ?? "Dòng") \(n + 1))"
            guard t.column != nil else { return row }
            guard let f = field(t, template), let sub = subLabel(t.sub, f) else { return path }
            return [row, f.label, sub].filter { !$0.isEmpty }.joined(separator: " › ")
        }
    }

    /// Display text of an old / new value of `path`, built with the shared display function (§6.5). Blank -> "(trống)".
    public static func valueText(path: String, _ value: JSONValue, template: Template?) -> String {
        if path == "returned" { return value.bool == true ? "Có" : "Không" }
        if value.isEmpty { return empty }
        let text = format(path: path, value, template: template)
        return text.isEmpty ? empty : text
    }

    private static func format(path: String, _ value: JSONValue, template: Template?) -> String {
        guard let t = parse(path) else { return raw(value) }
        switch t.kind {
        case .root:
            if t.id == "status" { return value.string == "completed" ? "Hoàn thành" : "Nháp" }
            if t.id == "completedAt", let d = value.string.flatMap(ISODate.parse) { return ISODate.format(d, "dd/MM/yyyy HH:mm") }
            return raw(value)
        case .signature:
            let o = value.object
            return o?["name"]?.string?.nilIfEmpty ?? o?["file"]?.string.map { ($0 as NSString).lastPathComponent } ?? raw(value)
        case .value, .table:
            guard let template else { return raw(value) }
            if t.kind == .table, t.column == nil { return rowText(value, template.table(t.id)) }
            guard let f = field(t, template) else { return raw(value) }
            return cell(t.sub ?? "v", value, f)
        }
    }

    private static func cell(_ sub: String, _ value: JSONValue, _ f: Field) -> String {
        switch sub {
        case "v": return ValueFormatter.display(f, FieldValue(v: value))
        case "flag": return value.string == "notMeasurable" ? "KĐĐ" : value.string == "estimate" ? "Ước lượng" : raw(value)
        case "other":
            return (value.object ?? [:]).sorted { $0.key < $1.key }.compactMap { k, v in
                v.string?.nilIfEmpty.map { "\(f.optionLabel(k)): \($0)" }
            }.joined(separator: "; ")
        case "media": return FieldValue(v: value).mediaInV.map(\.fileName).joined(separator: ", ")
        case "note", "reason", "review": return value.string ?? raw(value)
        default:
            guard sub.hasPrefix("parts."), let part = f.parts?.first(where: { $0.id == String(sub.dropFirst("parts.".count)) }) else { return raw(value) }
            return ValueFormatter.scalar(value, field: part.asField)
        }
    }

    /// A whole table row (added or removed): `column: value` of the cells that hold something.
    private static func rowText(_ value: JSONValue, _ table: TableDef?) -> String {
        guard let table, let data = try? JSONCoding.makeEncoder(pretty: false).encode(value),
              let row = try? JSONCoding.makeDecoder().decode(Row.self, from: data) else { return raw(value) }
        return table.columns.compactMap { c in
            let text = ValueFormatter.display(c, row.values[c.id])
            return text.isEmpty ? nil : "\(c.label): \(text)"
        }.joined(separator: "; ")
    }

    private static func raw(_ value: JSONValue) -> String {
        if let s = value.string { return s }
        if let d = value.double { return ValueFormatter.number(d) }
        if let b = value.bool { return b ? "Có" : "Không" }
        guard let data = try? JSONCoding.makeEncoder(pretty: false).encode(value), let s = String(data: data, encoding: .utf8) else { return "" }
        return s
    }
}
