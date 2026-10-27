import Foundation

/// One step of a path into the template tree: `["sections", 0, "items"]`.
public enum PathKey: Equatable, Hashable, Sendable, ExpressibleByStringLiteral, ExpressibleByIntegerLiteral {
    case key(String)
    case index(Int)

    public init(stringLiteral value: String) { self = .key(value) }
    public init(integerLiteral value: Int) { self = .index(value) }
}

public typealias DocPath = [PathKey]

extension Array where Element == PathKey {
    /// "sections[2].items[5].options" (the form validator and lint messages use).
    public var text: String {
        reduce("") { acc, k in
            switch k {
            case .index(let i): return acc + "[\(i)]"
            case .key(let s): return acc.isEmpty ? s : acc + "." + s
            }
        }
    }

    /// Inverse of `text`; nil when `s` is not made of `name` and `[n]` steps.
    public static func parse(_ s: String) -> DocPath? {
        var out: DocPath = [], name = "", digits = "", inIndex = false
        func flushName() { if !name.isEmpty { out.append(.key(name)); name = "" } }
        for ch in s {
            if inIndex {
                if ch == "]" { guard let i = Int(digits) else { return nil }; out.append(.index(i)); digits = ""; inIndex = false }
                else if ch.isNumber { digits.append(ch) } else { return nil }
            } else if ch == "[" { flushName(); inIndex = true }
            else if ch == "." { flushName() }
            else { name.append(ch) }
        }
        guard !inIndex else { return nil }
        flushName()
        return out
    }
}

public enum TemplateEditError: Error, Equatable, LocalizedError {
    case outOfRange, badPath, notObject, jsonParse(String), notDuplicable, badKind

    /// Code shared with `shared/template-edit-vectors.json`.
    public var code: String {
        switch self {
        case .outOfRange: return "OUT_OF_RANGE"
        case .badPath: return "BAD_PATH"
        case .notObject: return "NOT_OBJECT"
        case .jsonParse: return "JSON_PARSE"
        case .notDuplicable: return "NOT_DUPLICABLE"
        case .badKind: return "BAD_KIND"
        }
    }

    public var errorDescription: String? {
        switch self {
        case .outOfRange: return "Vị trí nằm ngoài danh sách."
        case .badPath: return "Không tìm thấy phần tử cần sửa."
        case .notObject: return "Nội dung phải là một đối tượng JSON { … }."
        case .jsonParse(let m): return "JSON không hợp lệ: \(m)"
        case .notDuplicable: return "Không nhân bản được phần tử này."
        case .badKind: return "Không thêm được loại phần tử này vào đây."
        }
    }
}

/// A template as a raw JSON tree. Edits never go through the typed `Template` model, so keys the app does not know survive.
public struct TemplateDoc: Equatable, Sendable {
    public var root: JSONValue

    public init(root: JSONValue) { self.root = root }

    /// Parses `text`, which must be a JSON object.
    public init(text: String) throws {
        let v = try Self.parse(text)
        guard v.object != nil else { throw TemplateEditError.notObject }
        root = v
    }

    /// Parses any JSON text (the Advanced JSON box reports its error with this).
    public static func parse(_ text: String) throws -> JSONValue {
        guard let data = text.data(using: .utf8) else { throw TemplateEditError.jsonParse("không đọc được chuỗi") }
        do { return try JSONDecoder().decode(JSONValue.self, from: data) }
        catch let e as DecodingError {
            if case .dataCorrupted(let c) = e, let u = c.underlyingError as NSError?, let d = u.userInfo[NSDebugDescriptionErrorKey] as? String { throw TemplateEditError.jsonParse(d) }
            throw TemplateEditError.jsonParse(describe(e))
        } catch { throw TemplateEditError.jsonParse(error.localizedDescription) }
    }

    private static func describe(_ e: DecodingError) -> String {
        switch e {
        case .dataCorrupted(let c), .typeMismatch(_, let c), .valueNotFound(_, let c), .keyNotFound(_, let c): return c.debugDescription
        @unknown default: return e.localizedDescription
        }
    }

    public func node(at path: DocPath) -> JSONValue? {
        var cur = root
        for k in path {
            switch (k, cur) {
            case (.key(let s), .object(let o)): guard let n = o[s] else { return nil }; cur = n
            case (.index(let i), .array(let a)): guard a.indices.contains(i) else { return nil }; cur = a[i]
            default: return nil
            }
        }
        return cur
    }

    /// Same content as `other`: key order and `1` vs `1.0` do not matter (the server compares the same way).
    public func semanticEquals(_ other: TemplateDoc) -> Bool { root == other.root }

    public var id: String? { root.object?["id"]?.string }
    public var version: String? { root.object?["version"]?.string }

    /// Text in the shared key order (`template-schema.md` 11.4).
    public func pretty() -> String { TemplateJSONWriter.write(root) }

    public func data() -> Data { Data(pretty().utf8) }

    /// Rebuilds the tree along `path`, handing the node there (nil when its last key is missing) to `transform`.
    func updating(_ path: DocPath, _ transform: (JSONValue?) throws -> JSONValue) throws -> TemplateDoc {
        func go(_ v: JSONValue, _ rest: ArraySlice<PathKey>) throws -> JSONValue {
            guard let head = rest.first else { return try transform(v) }
            switch (head, v) {
            case (.key(let k), .object(var o)):
                if rest.count == 1 { o[k] = try transform(o[k]); return .object(o) }
                guard let child = o[k] else { throw TemplateEditError.badPath }
                o[k] = try go(child, rest.dropFirst())
                return .object(o)
            case (.index(let i), .array(var a)):
                guard a.indices.contains(i) else { throw TemplateEditError.outOfRange }
                a[i] = try go(a[i], rest.dropFirst())
                return .array(a)
            default: throw TemplateEditError.badPath
            }
        }
        return TemplateDoc(root: try go(root, path[...]))
    }
}
