import Foundation

/// Prints JSON with keys in the shared order (`template-schema.md` 11.4): known keys first, the rest a-z. Whole numbers print without `.0`.
enum TemplateJSONWriter {
    static let priority = ["schemaVersion", "id", "version", "value", "kind", "title", "label", "type", "required", "shortName", "color", "subtitle",
                           "instructions", "recordTitle", "mediaNamePattern", "note", "unit", "hint", "method", "min", "max", "decimals", "warnMin",
                           "warnMax", "options", "parts", "formula", "columns", "rows", "items", "header", "sections", "signatures"]
    private static let rank = Dictionary(uniqueKeysWithValues: priority.enumerated().map { ($1, $0) })

    static func write(_ v: JSONValue) -> String {
        var out = ""
        emit(v, 0, &out)
        return out
    }

    private static func ordered(_ keys: [String]) -> [String] {
        keys.sorted { a, b in
            switch (rank[a], rank[b]) {
            case (let x?, let y?): return x < y
            case (_?, nil): return true
            case (nil, _?): return false
            default: return a < b
            }
        }
    }

    private static func emit(_ v: JSONValue, _ depth: Int, _ out: inout String) {
        switch v {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .number(let d): out += number(d)
        case .string(let s): out += quote(s)
        case .array(let a):
            if a.isEmpty { out += "[]"; return }
            let pad = String(repeating: " ", count: (depth + 1) * 2)
            out += "[\n"
            for (i, e) in a.enumerated() {
                out += pad
                emit(e, depth + 1, &out)
                out += i < a.count - 1 ? ",\n" : "\n"
            }
            out += String(repeating: " ", count: depth * 2) + "]"
        case .object(let o):
            if o.isEmpty { out += "{}"; return }
            let pad = String(repeating: " ", count: (depth + 1) * 2)
            let keys = ordered(Array(o.keys))
            out += "{\n"
            for (i, k) in keys.enumerated() {
                out += pad + quote(k) + ": "
                emit(o[k]!, depth + 1, &out)
                out += i < keys.count - 1 ? ",\n" : "\n"
            }
            out += String(repeating: " ", count: depth * 2) + "}"
        }
    }

    static func number(_ d: Double) -> String {
        guard d.isFinite else { return "null" }
        if d == d.rounded(), abs(d) < 1e15 { return String(Int64(d)) }
        return "\(d)"
    }

    static func quote(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
            }
        }
        return out + "\""
    }
}
