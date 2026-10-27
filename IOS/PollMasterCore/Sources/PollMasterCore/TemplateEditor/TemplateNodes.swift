import Foundation

/// Minimal default nodes for "add" and the new code of a copy.
enum TemplateNodes {
    static let choiceTypes: Set<String> = ["singleChoice", "multiChoice"]
    static let defaultOptions: [JSONValue] = [
        .object(["value": "lua_chon_1", "label": "Lựa chọn 1"]), .object(["value": "lua_chon_2", "label": "Lựa chọn 2"]),
    ]

    /// What kind of list `path` names, with the section index for `items`.
    private enum Slot { case sections, header, items(Int), columns, options, signatures, rows }

    private static func slot(_ path: DocPath) throws -> Slot {
        guard case .key(let last)? = path.last else { throw TemplateEditError.badPath }
        switch last {
        case "sections": return .sections
        case "header": return .header
        case "columns": return .columns
        case "options": return .options
        case "signatures": return .signatures
        case "rows": return .rows
        case "items":
            guard path.count >= 2, case .index(let i) = path[path.count - 2] else { throw TemplateEditError.badPath }
            return .items(i)
        default: throw TemplateEditError.badPath
        }
    }

    private static func strings(_ root: JSONValue, _ path: DocPath, _ key: String) -> Set<String> {
        Set((TemplateDoc(root: root).node(at: path)?.array ?? []).compactMap { $0.object?[key]?.string })
    }

    private static func field(id: String, type: String) -> JSONValue {
        var o: [String: JSONValue] = ["id": .string(id), "label": "Trường mới", "type": .string(type)]
        if choiceTypes.contains(type) { o["options"] = .array(defaultOptions) }
        return .object(o)
    }

    static func make(_ root: JSONValue, _ path: DocPath, kind: String, type: String?) throws -> JSONValue {
        switch try slot(path) {
        case .sections: return .object(["id": .string(TemplateIds.nextSectionId(root)), "title": "Mục mới", "items": .array([])])
        case .header:
            guard kind == "field" else { throw TemplateEditError.badKind }
            return field(id: TemplateIds.nextItemId(root, section: nil, kind: "field"), type: type ?? "text")
        case .items(let s):
            let id = TemplateIds.nextItemId(root, section: s, kind: kind)
            switch kind {
            case "field": return field(id: id, type: type ?? "text")
            case "heading": return .object(["kind": "heading", "id": .string(id), "title": "Tiêu đề mới"])
            case "table":
                return .object(["kind": "table", "id": .string(id), "title": "Bảng mới",
                                "columns": .array([.object(["id": "c1", "label": "Cột 1", "type": "text"])])])
            default: throw TemplateEditError.badKind
            }
        case .columns:
            var o = field(id: TemplateIds.firstUnused(prefix: "c", taken: strings(root, path, "id")), type: type ?? "text").object!
            o["label"] = "Cột mới"
            return .object(o)
        case .options:
            return .object(["value": .string(TemplateIds.optionValue(label: "Lựa chọn mới", taken: strings(root, path, "value"))), "label": "Lựa chọn mới"])
        case .signatures: return .object(["id": .string(TemplateIds.firstUnused(prefix: "sig", taken: strings(root, path, "id"))), "label": "Chữ ký mới"])
        case .rows: return .object(["id": .string(TemplateIds.firstUnused(prefix: "r", taken: strings(root, path, "id"))), "fixed": .object([:])])
        }
    }

    /// `node` (an element of the list at `path`) with a fresh code. Sections are not copied (their items would repeat every code).
    static func copy(_ node: JSONValue, _ root: JSONValue, _ path: DocPath) throws -> JSONValue {
        guard var o = node.object else { throw TemplateEditError.badPath }
        switch try slot(path) {
        case .sections: throw TemplateEditError.notDuplicable
        case .header: o["id"] = .string(TemplateIds.nextItemId(root, section: nil, kind: "field"))
        case .items(let s):
            let kind = o["kind"]?.string ?? "field"
            o["id"] = .string(TemplateIds.nextItemId(root, section: s, kind: kind == "heading" || kind == "table" ? kind : "field"))
        case .columns: o["id"] = .string(TemplateIds.firstUnused(prefix: "c", taken: strings(root, path, "id")))
        case .options: o["value"] = .string(TemplateIds.optionValue(label: o["label"]?.string ?? "", taken: strings(root, path, "value")))
        case .signatures: o["id"] = .string(TemplateIds.firstUnused(prefix: "sig", taken: strings(root, path, "id")))
        case .rows: o["id"] = .string(TemplateIds.firstUnused(prefix: "r", taken: strings(root, path, "id")))
        }
        return .object(o)
    }
}
