import Foundation

/// One edit of the template tree. Lists are named by their path (`["sections", 0, "items"]`, `["header"]`, `["sections", 1, "items", 3, "columns"]`…).
public enum EditOp: Equatable, Sendable {
    /// Sets or removes one key of the object at `path` (`nil`/null removes; "" removes a key that may be absent).
    case set(path: DocPath, key: String, value: JSONValue?)
    case insert(path: DocPath, index: Int, node: JSONValue)
    case delete(path: DocPath, index: Int)
    case move(path: DocPath, from: Int, to: Int)
    /// Moves an item to the end of another section.
    case moveItem(fromSection: Int, index: Int, toSection: Int)
    /// Copies the node after itself with a new code.
    case duplicate(path: DocPath, index: Int)
    /// Replaces the object at `path` ([] = the whole template) — Advanced JSON.
    case replace(path: DocPath, node: JSONValue)
    /// Same, from text typed by the user: bad JSON -> `.jsonParse`, not an object -> `.notObject`.
    case replaceText(path: DocPath, text: String)
    /// Inserts a minimal new node of `kind` ("field", "heading", "table"; ignored outside `items`) and `type` (fields, columns); `index` nil = end.
    case newItem(path: DocPath, index: Int?, kind: String, type: String?)
}

/// Pure edits: `(doc, op) -> doc`. Keys not touched stay as they are.
public enum TemplateEdit {
    /// Keys that keep an empty string (lint reports them) instead of disappearing.
    static let requiredKeys: Set<String> = ["id", "label", "title", "type", "kind", "value", "version"]

    public static func apply(_ doc: TemplateDoc, _ ops: [EditOp]) throws -> TemplateDoc { try ops.reduce(doc) { try apply($0, $1) } }

    public static func apply(_ doc: TemplateDoc, _ op: EditOp) throws -> TemplateDoc {
        switch op {
        case .set(let path, let key, let value): return try set(doc, path, key, value)
        case .insert(let path, let index, let node):
            guard node.object != nil else { throw TemplateEditError.notObject }
            return try list(doc, path) { guard (0...$0.count).contains(index) else { throw TemplateEditError.outOfRange }; $0.insert(node, at: index) }
        case .delete(let path, let index):
            return try list(doc, path) { guard $0.indices.contains(index) else { throw TemplateEditError.outOfRange }; $0.remove(at: index) }
        case .move(let path, let from, let to):
            return try list(doc, path) {
                guard $0.indices.contains(from), $0.indices.contains(to) else { throw TemplateEditError.outOfRange }
                $0.insert($0.remove(at: from), at: to)
            }
        case .moveItem(let from, let index, let to): return try moveItem(doc, from, index, to)
        case .duplicate(let path, let index): return try duplicate(doc, path, index)
        case .replace(let path, let node): return try replace(doc, path, node)
        case .replaceText(let path, let text): return try replace(doc, path, try TemplateDoc.parse(text))
        case .newItem(let path, let index, let kind, let type):
            let node = try TemplateNodes.make(doc.root, path, kind: kind, type: type)
            return try list(doc, path) {
                let at = index ?? $0.count
                guard (0...$0.count).contains(at) else { throw TemplateEditError.outOfRange }
                $0.insert(node, at: at)
            }
        }
    }

    /// Edits the array at `path` (a missing array counts as empty).
    private static func list(_ doc: TemplateDoc, _ path: DocPath, _ body: (inout [JSONValue]) throws -> Void) throws -> TemplateDoc {
        try doc.updating(path) { v in
            var a: [JSONValue]
            switch v { case nil: a = []; case .array(let x)?: a = x; default: throw TemplateEditError.badPath }
            try body(&a)
            return .array(a)
        }
    }

    private static func set(_ doc: TemplateDoc, _ path: DocPath, _ key: String, _ value: JSONValue?) throws -> TemplateDoc {
        try doc.updating(path) { v in
            guard case .object(var o)? = v else { throw TemplateEditError.badPath }
            switch value {
            case nil, .null?: o[key] = nil
            case .string(let s)?: o[key] = s.isEmpty && !requiredKeys.contains(key) ? nil : .string(s)
            case let x?: o[key] = x
            }
            if key == "type", let t = o["type"]?.string, TemplateNodes.choiceTypes.contains(t), (o["options"]?.array ?? []).isEmpty {
                o["options"] = .array(TemplateNodes.defaultOptions)
            }
            return .object(o)
        }
    }

    private static func replace(_ doc: TemplateDoc, _ path: DocPath, _ node: JSONValue) throws -> TemplateDoc {
        guard node.object != nil else { throw TemplateEditError.notObject }
        if path.isEmpty { return TemplateDoc(root: node) }
        return try doc.updating(path) { v in
            guard v != nil else { throw TemplateEditError.badPath }
            return node
        }
    }

    private static func moveItem(_ doc: TemplateDoc, _ from: Int, _ index: Int, _ to: Int) throws -> TemplateDoc {
        let sections = doc.root.object?["sections"]?.array ?? []
        guard sections.indices.contains(from), sections.indices.contains(to) else { throw TemplateEditError.outOfRange }
        guard let item = doc.node(at: ["sections", .index(from), "items", .index(index)]) else { throw TemplateEditError.outOfRange }
        let removed = try apply(doc, .delete(path: ["sections", .index(from), "items"], index: index))
        return try list(removed, ["sections", .index(to), "items"]) { $0.append(item) }
    }

    private static func duplicate(_ doc: TemplateDoc, _ path: DocPath, _ index: Int) throws -> TemplateDoc {
        guard case .array(let a)? = doc.node(at: path) else { throw TemplateEditError.badPath }
        guard a.indices.contains(index) else { throw TemplateEditError.outOfRange }
        let copy = try TemplateNodes.copy(a[index], doc.root, path)
        return try list(doc, path) { $0.insert(copy, at: index + 1) }
    }
}
