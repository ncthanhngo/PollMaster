import Foundation

public enum TemplateError: Error, LocalizedError, Equatable {
    case parse(String)
    case invalid([String])
    case inUse(count: Int)

    public var errorDescription: String? {
        switch self {
        case .parse(let m): return "Không đọc được mẫu: \(m)"
        case .invalid(let list): return "Mẫu không hợp lệ:\n" + list.prefix(8).joined(separator: "\n")
        case .inUse(let n): return "Còn \(n) phiếu đang dùng mẫu này."
        }
    }
}

public enum TemplateLoader {
    /// Decode + structural validation. Parse errors carry the JSON path ("sections[2].items[5].type").
    public static func decode(_ data: Data) throws -> Template {
        let t: Template
        do { t = try JSONCoding.makeDecoder().decode(Template.self, from: data) }
        catch let e as DecodingError { throw TemplateError.parse(describe(e)) }
        catch { throw TemplateError.parse(error.localizedDescription) }
        let problems = check(t)
        if !problems.isEmpty { throw TemplateError.invalid(problems) }
        return t
    }

    static func describe(_ e: DecodingError) -> String {
        func path(_ c: [CodingKey]) -> String {
            c.reduce("") { acc, k in
                if let i = k.intValue { return acc + "[\(i)]" }
                return acc.isEmpty ? k.stringValue : acc + "." + k.stringValue
            }
        }
        switch e {
        case .keyNotFound(let k, let ctx): return "thiếu khóa '\(k.stringValue)' tại \(path(ctx.codingPath))"
        case .typeMismatch(_, let ctx), .valueNotFound(_, let ctx), .dataCorrupted(let ctx):
            return "\(path(ctx.codingPath)): \(ctx.debugDescription)"
        @unknown default: return e.localizedDescription
        }
    }

    /// Unique ids, valid formulas, choice options, supported schema version. Problems start with the JSON path.
    public static func check(_ t: Template) -> [String] {
        var problems: [String] = []
        if t.schemaVersion != 1 { problems.append("schemaVersion \(t.schemaVersion) chưa được hỗ trợ") }
        let tableIds = Set(t.allTables.map(\.id))
        var seen = Set<String>()
        func unique(_ id: String) { if !seen.insert(id).inserted { problems.append("Trùng mã: \(id)") } }

        func checkField(_ f: Field, _ path: String, requireFormula: Bool) {
            func needsOptions(_ type: FieldType, _ options: [Option]?) -> Bool { [.singleChoice, .multiChoice].contains(type) && (options ?? []).isEmpty }
            if needsOptions(f.type, f.options) { problems.append("\(path).options: \(f.id) cần danh sách lựa chọn") }
            for (k, p) in (f.parts ?? []).enumerated() where needsOptions(p.type, p.options) {
                problems.append("\(path).parts[\(k)].options: \(p.id) cần danh sách lựa chọn")
            }
            guard f.isComputed || f.formula != nil else { return }
            guard let src = f.formula else { if requireFormula { problems.append("\(path).formula: \(f.id) thiếu formula") }; return }
            do {
                if let p = try Formula(src).problem(tables: tableIds) { problems.append("\(path).formula: công thức không hợp lệ – \(p)") }
            } catch { problems.append("\(path).formula: công thức lỗi – \(error)") }
        }

        for (i, f) in t.header.enumerated() { unique(f.id); checkField(f, "header[\(i)]", requireFormula: true) }
        for (i, s) in t.sections.enumerated() {
            for (j, item) in s.items.enumerated() {
                unique(item.id)
                let path = "sections[\(i)].items[\(j)]"
                switch item {
                case .field(let f): checkField(f, path, requireFormula: true)
                case .heading: break
                case .table(let tb):
                    var cols = Set<String>()
                    for (k, c) in tb.columns.enumerated() {
                        if !cols.insert(c.id).inserted { problems.append("\(path).columns[\(k)]: Trùng cột \(c.id) trong bảng \(tb.id)") }
                        checkField(c, "\(path).columns[\(k)]", requireFormula: false)
                    }
                }
            }
        }
        return problems
    }
}

public enum TemplateVersion {
    /// Dotted numeric compare ("1.10" > "1.9"); non-numeric parts compare as text.
    public static func isNewer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: "."), y = b.split(separator: ".")
        for i in 0..<max(x.count, y.count) {
            let l = i < x.count ? String(x[i]) : "0", r = i < y.count ? String(y[i]) : "0"
            if let ln = Int(l), let rn = Int(r) { if ln != rn { return ln > rn } }
            else if l != r { return l.compare(r, options: .numeric) == .orderedDescending }
        }
        return false
    }

    /// Newest version of every template id (what new records are made from).
    public static func newest(_ all: [Template]) -> [Template] {
        var best: [String: Template] = [:]
        for t in all where best[t.id].map({ isNewer(t.version, than: $0.version) }) ?? true { best[t.id] = t }
        return all.filter { best[$0.id]?.version == $0.version }
    }
}

/// Templates installed on device: `<root>/templates/<id>@<version>.json`.
public struct TemplateStore: Sendable {
    public let dir: URL

    public init(root: URL) {
        dir = root.appendingPathComponent("templates", isDirectory: true)
    }

    private func url(_ id: String, _ version: String) -> URL {
        dir.appendingPathComponent("\(RecordNaming.sanitize(id))@\(RecordNaming.sanitize(version)).json")
    }

    public func exists(id: String, version: String) -> Bool {
        FileManager.default.fileExists(atPath: url(id, version).path)
    }

    public func list() -> [Template] {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }
            .compactMap { try? TemplateLoader.decode(Data(contentsOf: $0)) }
            .sorted { ($0.title, $0.version) < ($1.title, $1.version) }
    }

    public func load(id: String, version: String) -> Template? {
        (try? Data(contentsOf: url(id, version))).flatMap { try? TemplateLoader.decode($0) }
    }

    /// File content as installed (what a project bundle carries).
    public func data(id: String, version: String) -> Data? { try? Data(contentsOf: url(id, version)) }

    /// Validate and write. Returns the parsed template.
    @discardableResult
    public func install(_ data: Data) throws -> Template {
        let t = try TemplateLoader.decode(data)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try data.write(to: url(t.id, t.version), options: .atomic)
        return t
    }

    public func delete(_ t: Template) throws {
        try FileManager.default.removeItem(at: url(t.id, t.version))
    }
}
