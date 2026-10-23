import Foundation

/// Loosely typed JSON value used for field values, defaults and formula results.
public enum JSONValue: Codable, Equatable, Sendable, Hashable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): try c.encode(n)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    public var double: Double? { if case .number(let n) = self { return n } else { return nil } }
    public var string: String? { if case .string(let s) = self { return s } else { return nil } }
    public var bool: Bool? { if case .bool(let b) = self { return b } else { return nil } }
    public var array: [JSONValue]? { if case .array(let a) = self { return a } else { return nil } }
    public var object: [String: JSONValue]? { if case .object(let o) = self { return o } else { return nil } }
    public var isNull: Bool { if case .null = self { return true } else { return false } }

    /// Empty per schema: null, "", [] (arrays holding only empty elements count as empty).
    public var isEmpty: Bool {
        switch self {
        case .null: return true
        case .string(let s): return s.isEmpty
        case .array(let a): return a.allSatisfy { $0.isEmpty }
        default: return false
        }
    }

    /// Like `isEmpty`, but whitespace-only strings count as empty too (validation, schema §6.3).
    public var isBlank: Bool {
        switch self {
        case .null: return true
        case .string(let s): return s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .array(let a): return a.allSatisfy { $0.isBlank }
        default: return false
        }
    }

    public static func int(_ i: Int) -> JSONValue { .number(Double(i)) }
}

extension JSONValue: ExpressibleByStringLiteral, ExpressibleByFloatLiteral, ExpressibleByIntegerLiteral,
    ExpressibleByBooleanLiteral, ExpressibleByNilLiteral, ExpressibleByArrayLiteral {
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    public init(stringLiteral value: String) { self = .string(value) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(nilLiteral: ()) { self = .null }
}
