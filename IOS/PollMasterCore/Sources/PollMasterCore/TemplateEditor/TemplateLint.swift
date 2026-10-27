import Foundation

/// One finding about a draft: `error` blocks publishing, `warning` does not.
public struct TemplateIssue: Equatable, Hashable, Sendable {
    public enum Severity: String, Sendable { case error, warning }
    public var severity: Severity
    public var code: String
    /// JSON path in the form of validator messages ("sections[0].items[2].options"); `DocPath.parse` turns it back into steps.
    public var path: String
    public var message: String
}

extension TemplateDoc {
    /// Which of `lint`'s findings apply: it needs the template the draft started from (nil for a new template).
    public func lint(base: TemplateDoc?, isNewTemplate: Bool? = nil) -> [TemplateIssue] { TemplateLint.check(self, base: base, isNewTemplate: isNewTemplate ?? (base == nil)) }
}

/// Validator problems (`INVALID`, schema 6.6) plus the checks only the editor needs (schema 11.5). Works on the raw tree, so it can still
/// point at things when the typed decode fails.
public enum TemplateLint {
    private static let idRegex = try! NSRegularExpression(pattern: "^[A-Z0-9-]{1,40}$")

    public static func check(_ doc: TemplateDoc, base: TemplateDoc?, isNewTemplate: Bool) -> [TemplateIssue] {
        var out: [TemplateIssue] = []
        for p in TemplateLoader.problems(doc.data()) {
            let parts = p.components(separatedBy: ": ")
            out.append(TemplateIssue(severity: .error, code: "INVALID", path: parts.count > 1 ? parts[0] : "$", message: parts.count > 1 ? parts.dropFirst().joined(separator: ": ") : p))
        }
        var c = Collector(root: doc.root)
        c.run(isNewTemplate: isNewTemplate)
        if let base, !isNewTemplate { c.removedIds(base.root) }
        return out + c.issues
    }

    private struct Collector {
        let root: JSONValue
        var issues: [TemplateIssue] = []
        let known: Set<String>

        init(root: JSONValue) {
            self.root = root
            var ids = TemplateIds.usedIds(root)
            for s in root.object?["sections"]?.array ?? [] { for i in s.object?["items"]?.array ?? [] { for col in i.object?["columns"]?.array ?? [] { if let id = col.object?["id"]?.string { ids.insert(id) } } } }
            known = ids
        }

        mutating func add(_ sev: TemplateIssue.Severity, _ code: String, _ path: String, _ msg: String) { issues.append(TemplateIssue(severity: sev, code: code, path: path, message: msg)) }
        mutating func err(_ code: String, _ path: String, _ msg: String) { add(.error, code, path, msg) }
        mutating func warn(_ code: String, _ path: String, _ msg: String) { add(.warning, code, path, msg) }

        static func blank(_ v: JSONValue?) -> Bool { (v?.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

        mutating func need(_ o: [String: JSONValue], _ key: String, _ path: String, _ code: String) {
            if Self.blank(o[key]) { err(code, path.isEmpty ? key : "\(path).\(key)", code == "EMPTY_ID" ? "Mã không được để trống." : code == "EMPTY_LABEL" ? "Nhãn không được để trống." : "Tên không được để trống.") }
        }

        mutating func run(isNewTemplate: Bool) {
            guard let o = root.object else { return }
            let id = o["id"]?.string ?? ""
            if Self.blank(o["id"]) { err("EMPTY_ID", "id", "Mã không được để trống.") }
            else if isNewTemplate, idRegex.firstMatch(in: id, range: NSRange(id.startIndex..., in: id)) == nil { err("BAD_TEMPLATE_ID", "id", "Mã mẫu chỉ gồm chữ in hoa, số và dấu gạch ngang (tối đa 40 ký tự).") }
            need(o, "title", "", "EMPTY_TITLE")
            if (o["sections"]?.array ?? []).isEmpty { err("NO_SECTIONS", "sections", "Mẫu cần ít nhất một mục.") }
            var fields: [(String, [String: JSONValue])] = []
            for (i, f) in (o["header"]?.array ?? []).enumerated() { if let fo = f.object { fields.append(("header[\(i)]", fo)); field(fo, "header[\(i)]") } }
            for (si, s) in (o["sections"]?.array ?? []).enumerated() {
                guard let so = s.object else { continue }
                let sp = "sections[\(si)]"
                need(so, "id", sp, "EMPTY_ID"); need(so, "title", sp, "EMPTY_TITLE")
                for (ii, item) in (so["items"]?.array ?? []).enumerated() {
                    guard let io = item.object else { continue }
                    let ip = "\(sp).items[\(ii)]"
                    switch io["kind"]?.string ?? "field" {
                    case "heading":
                        need(io, "id", ip, "EMPTY_ID")
                        if io["title"] != nil { need(io, "title", ip, "EMPTY_TITLE") }
                        ref(io["roundEntry"]?.object?["timeField"], "\(ip).roundEntry.timeField")
                    case "table": table(io, ip)
                    default: fields.append((ip, io)); field(io, ip)
                    }
                }
            }
            multi(fields)
            for (k, s) in (o["signatures"]?.array ?? []).enumerated() {
                guard let so = s.object else { continue }
                need(so, "id", "signatures[\(k)]", "EMPTY_ID"); need(so, "label", "signatures[\(k)]", "EMPTY_LABEL")
            }
            sectionDuplicates(o)
        }

        mutating func sectionDuplicates(_ o: [String: JSONValue]) {
            var seen = Set<String>()
            for (i, s) in (o["sections"]?.array ?? []).enumerated() {
                if let id = s.object?["id"]?.string, !id.isEmpty, !seen.insert(id).inserted { warn("DUP_SECTION_ID", "sections[\(i)]", "Trùng mã mục \"\(id)\".") }
            }
        }

        mutating func table(_ t: [String: JSONValue], _ path: String) {
            need(t, "id", path, "EMPTY_ID"); need(t, "title", path, "EMPTY_TITLE")
            let cols = (t["columns"]?.array ?? []).map { $0.object ?? [:] }
            for (k, c) in cols.enumerated() {
                let cp = "\(path).columns[\(k)]"
                field(c, cp)
                if let ty = c["type"]?.string, ty == "computed" || ty == "signature" { warn("COLUMN_TYPE", "\(cp).type", "Cột không dùng được kiểu \(ty).") }
            }
            for (k, r) in (t["rows"]?.array ?? []).enumerated() { if let ro = r.object { need(ro, "id", "\(path).rows[\(k)]", "EMPTY_ID") } }
            let sw = t["stopwatch"]?.object
            for (k, c) in (sw?["columns"]?.array ?? []).enumerated() {
                let id = c.string ?? ""
                if cols.first(where: { $0["id"]?.string == id })?["type"]?.string != "elapsed" {
                    err("STOPWATCH_COLUMN", "\(path).stopwatch.columns[\(k)]", "Cột bấm giờ \"\(id)\" không tồn tại hoặc không phải kiểu elapsed.")
                }
            }
            for (key, cue) in sw?["cues"]?.object ?? [:] { ref(cue.object?["field"], "\(path).stopwatch.cues.\(key).field") }
        }

        /// A field, column or part: codes, labels, options, ranges, references.
        mutating func field(_ f: [String: JSONValue], _ path: String) {
            need(f, "id", path, "EMPTY_ID"); need(f, "label", path, "EMPTY_LABEL")
            var values = Set<String>()
            for (k, op) in (f["options"]?.array ?? []).enumerated() {
                guard let oo = op.object else { continue }
                let p = "\(path).options[\(k)]"
                need(oo, "value", p, "EMPTY_ID"); need(oo, "label", p, "EMPTY_LABEL")
                if let v = oo["value"]?.string, !v.isEmpty, !values.insert(v).inserted { err("DUP_OPTION", p, "Trùng giá trị lựa chọn \"\(v)\".") }
            }
            for (lo, hi) in [("min", "max"), ("warnMin", "warnMax")] {
                if let a = f[lo]?.double, let b = f[hi]?.double, a > b { err("MIN_GT_MAX", "\(path).\(lo)", "Giá trị nhỏ nhất lớn hơn giá trị lớn nhất.") }
            }
            if let src = f["formula"]?.string, let refs = try? Formula(src).references() { for r in refs { ref(.string(r), "\(path).formula") } }
            ref(f["photoRef"], "\(path).photoRef")
            if let w = f["showWhen"]?.object?["field"]?.string, w != "$self" { ref(.string(w), "\(path).showWhen.field") }
            if let l = f["lookup"]?.object {
                for k in (l["fill"]?.object ?? [:]).keys { ref(.string(k), "\(path).lookup.fill.\(k)") }
                ref(l["gps"], "\(path).lookup.gps")
            }
            if let m = f["chipsRange"]?.object?["maxRef"]?.string { ref(.string(String(m.split(separator: ".", maxSplits: 1).first ?? "")), "\(path).chipsRange.maxRef") }
            for (k, p) in (f["parts"]?.array ?? []).enumerated() { if let po = p.object { field(po, "\(path).parts[\(k)]") } }
        }

        mutating func ref(_ v: JSONValue?, _ path: String) {
            guard let id = v?.string, !id.isEmpty, !known.contains(id) else { return }
            warn("DANGLING_REF", path, "\"\(id)\" không tồn tại (công thức, điều kiện hiện, ảnh tham chiếu…).")
        }

        mutating func multi(_ fields: [(String, [String: JSONValue])]) {
            for (code, key, value, label) in [("MULTI_RECORD_CODE", "role", "recordCode", "recordCode"), ("MULTI_SESSION_TIME", "type", "sessionTime", "sessionTime")] {
                for (p, f) in fields.filter({ $0.1[key]?.string == value }).dropFirst() { warn(code, "\(p).\(key)", "Có nhiều hơn một trường \(label).") }
            }
        }

        /// Fields and tables the draft's base had that are gone; columns of tables that are still there.
        mutating func removedIds(_ base: JSONValue) {
            func codes(_ r: JSONValue) -> (header: Set<String>, items: Set<String>, cols: [String: Set<String>]) {
                var h = Set<String>(), items = Set<String>(), cols: [String: Set<String>] = [:]
                for f in r.object?["header"]?.array ?? [] { if let id = f.object?["id"]?.string { h.insert(id) } }
                for s in r.object?["sections"]?.array ?? [] {
                    for i in s.object?["items"]?.array ?? [] {
                        guard let o = i.object, o["kind"]?.string != "heading", let id = o["id"]?.string else { continue }
                        items.insert(id)
                        if o["kind"]?.string == "table" { cols[id] = Set((o["columns"]?.array ?? []).compactMap { $0.object?["id"]?.string }) }
                    }
                }
                return (h, items, cols)
            }
            let old = codes(base), now = codes(root)
            let msg = { (id: String) in "Mã \"\(id)\" có ở bản gốc nhưng không còn. Phiếu cũ giữ dữ liệu; xuất tổng hợp sẽ có cột riêng." }
            for id in old.header.sorted() where !now.header.contains(id) && !now.items.contains(id) { warn("ID_REMOVED", "header", msg(id)) }
            for id in old.items.sorted() where !now.items.contains(id) && !now.header.contains(id) { warn("ID_REMOVED", "sections", msg(id)) }
            for (t, cols) in old.cols.sorted(by: { $0.key < $1.key }) {
                guard let kept = now.cols[t] else { continue }
                var tablePath = "sections"
                for (si, s) in (root.object?["sections"]?.array ?? []).enumerated() {
                    for (ii, i) in (s.object?["items"]?.array ?? []).enumerated() where i.object?["id"]?.string == t { tablePath = "sections[\(si)].items[\(ii)].columns" }
                }
                for id in cols.sorted() where !kept.contains(id) { warn("ID_REMOVED", tablePath, msg(id)) }
            }
        }
    }
}

extension Formula {
    /// Field codes the formula reads (`{A-01}`, `{B-06.rows}` -> "B-06"); `{@col}` and table names are not fields.
    public func references() -> [String] {
        func walk(_ n: FormulaNode) -> [String] {
            switch n {
            case .ref(let f, _): return [f]
            case .unary(_, let e): return walk(e)
            case .binary(_, let l, let r): return walk(l) + walk(r)
            case .call(_, let args): return args.flatMap(walk)
            default: return []
            }
        }
        return walk(root)
    }
}
