import Foundation

public enum ExportJSON {
    /// `export.json` per schema §5.1.
    public static func build(_ t: Template, _ r: Record) throws -> Data {
        let computed = ComputedEngine(template: t, record: r).computeAll()
        /// Stored text copied verbatim (not re-serialized in the device time zone) while it still matches the date.
        func dateJSON(_ d: Date?, raw: String?) -> JSONValue {
            guard let d else { return .null }
            if let raw, let parsed = ISODate.parse(raw), abs(parsed.timeIntervalSince(d)) < 1 { return .string(raw) }
            return .string(ISODate.string(d))
        }

        /// Empty values (including an empty photo list) are `null`.
        func rawValue(_ f: Field, _ fv: FieldValue?) -> JSONValue {
            guard let v = fv?.v, !v.isEmpty else { return .null }
            if f.isMedia {
                let names = fv?.mediaInV.map { JSONValue.string($0.fileName) } ?? []
                return names.isEmpty ? .null : .array(names)
            }
            return v
        }

        func cell(_ f: Field, _ fv: FieldValue) -> JSONValue { rawValue(f, fv) }

        var fields: [String: JSONValue] = [:]
        for f in t.allFields where !f.isComputed {
            let fv = r.values[f.id]
            var o: [String: JSONValue] = ["label": .string(f.label), "value": rawValue(f, fv)]
            if let u = f.unit { o["unit"] = .string(u) }
            if let flag = fv?.flag { o["flag"] = .string(flag.rawValue) }
            if let s = fv?.reason { o["reason"] = .string(s) }
            if let s = fv?.note { o["note"] = .string(s) }
            if let s = fv?.review { o["review"] = .string(s) }
            if let other = fv?.other { o["other"] = .object(other.mapValues { .string($0) }) }
            if let p = fv?.parts { o["parts"] = .object(p) }
            if let m = fv?.media, !m.isEmpty { o["media"] = .array(m.map { .string($0.fileName) }) }
            fields[f.id] = .object(o)
        }

        var tables: [String: JSONValue] = [:]
        for tb in t.allTables {
            let fixed = Dictionary((tb.rows ?? []).map { ($0.id, $0.fixed) }, uniquingKeysWith: { a, _ in a })
            tables[tb.id] = .array(r.rows(of: tb).map { row in
                var o: [String: JSONValue] = ["id": .string(row.id)]
                for col in tb.columns {
                    if let fv = row.values[col.id], !fv.isBlank { o[col.id] = cell(col, fv) }
                    else if let fx = fixed[row.id]?[col.id] { o[col.id] = fx }
                }
                return .object(o)
            })
        }

        let root: JSONValue = .object([
            "template": .object(["id": .string(t.id), "version": .string(t.version), "title": .string(t.title)]),
            "record": .object(["id": .string(r.id), "status": .string(r.status.rawValue), "createdAt": dateJSON(r.createdAt, raw: r.rawTimes.created),
                               "updatedAt": dateJSON(r.updatedAt, raw: r.rawTimes.updated),
                               "completedAt": dateJSON(r.completedAt, raw: r.rawTimes.completed)]),
            "fields": .object(fields),
            "computed": .object(computed),
            "tables": .object(tables),
        ])
        return try JSONCoding.makeEncoder().encode(root)
    }
}
