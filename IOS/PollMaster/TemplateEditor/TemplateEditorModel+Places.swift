import Foundation
import PollMasterCore

/// Where a lint path lives: the screens to push to reach it, and a readable name for it.
extension TemplateEditorModel {
    func jump(toIssue p: String) {
        path = DocPath.parse(p).map(Self.routes(for:)) ?? []
    }

    /// `sections[1].items[3].columns[0].min` -> section 1, item, column.
    static func routes(for p: DocPath) -> [EditorRoute] {
        guard case .key(let first)? = p.first else { return [] }
        func idx(_ i: Int) -> Int? { if p.indices.contains(i), case .index(let n) = p[i] { return n }; return nil }
        func key(_ i: Int) -> String? { if p.indices.contains(i), case .key(let s) = p[i] { return s }; return nil }
        switch first {
        case "sections":
            guard let s = idx(1) else { return [] }
            var out: [EditorRoute] = [.section(s)]
            if key(2) == "items", idx(3) != nil {
                out.append(.item(Array(p[0..<4])))
                if key(4) == "columns", idx(5) != nil { out.append(.item(Array(p[0..<6]))) }
            }
            return out
        case "header":
            return idx(1) == nil ? [.header] : [.header, .item(Array(p[0..<2]))]
        case "signatures": return [.signatures]
        default: return [.info]
        }
    }

    /// "Chiều cao (B-02) · công thức": the nearest named node above `p` plus the key it ends in.
    func place(of text: String) -> String {
        guard let p = DocPath.parse(text), !p.isEmpty else { return "Cả mẫu" }
        var cut = p.count
        while cut > 0 {
            let o = object(Array(p[0..<cut]))
            if let name = o["label"]?.string ?? o["title"]?.string, !name.isEmpty {
                let id = o["id"]?.string.map { " (\($0))" } ?? ""
                if cut == p.count { return name + id }
                return name + id + " · " + EditorLabels.keyName(p[cut...])
            }
            cut -= 1
        }
        return EditorLabels.keyName(p[...])
    }
}
