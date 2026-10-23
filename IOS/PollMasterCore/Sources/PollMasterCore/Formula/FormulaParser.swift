import Foundation

public struct FormulaError: Error, CustomStringConvertible, Equatable {
    public var message: String
    public var description: String { message }
}

indirect enum FormulaNode: Sendable, Equatable {
    case number(Double)
    case string(String)
    case bool(Bool)
    case null
    case ref(field: String, part: String?)
    case rowRef(String)
    case ident(String)
    case call(String, [FormulaNode])
    case unary(String, FormulaNode)
    case binary(String, FormulaNode, FormulaNode)
}

private enum Token: Equatable {
    case number(Double), string(String), ident(String), ref(String), op(String), lparen, rparen, comma
}

/// Parsed formula (immutable, cacheable).
public struct Formula: Sendable, Equatable {
    let root: FormulaNode

    public init(_ source: String) throws {
        var parser = FormulaParser(tokens: try FormulaParser.tokenize(source))
        root = try parser.parseAll()
    }
}

extension Formula {
    private static let arity: [String: ClosedRange<Int>] = [
        "abs": 1...1, "round": 1...2, "if": 3...3, "dewpoint": 2...2,
        "avg": 2...3, "sum": 2...3, "min": 2...3, "max": 2...3, "count": 1...2, "pct": 2...3,
    ]
    private static let tableFunctions: Set<String> = ["avg", "sum", "min", "max", "count", "pct"]

    /// First static problem (unknown function, wrong argument count, `{@x}` outside a table function,
    /// unknown table), or nil when the formula can be imported.
    public func problem(tables: Set<String>) -> String? {
        func check(_ n: FormulaNode, inTable: Bool) -> String? {
            switch n {
            case .rowRef(let c): return inTable ? nil : "{@\(c)} chỉ dùng được trong hàm bảng"
            case .unary(_, let e): return check(e, inTable: inTable)
            case .binary(_, let l, let r): return check(l, inTable: inTable) ?? check(r, inTable: inTable)
            case .call(let name, let args):
                guard let range = Self.arity[name] else { return "hàm không tồn tại: \(name)" }
                guard range.contains(args.count) else { return "\(name) sai số tham số (\(args.count))" }
                guard Self.tableFunctions.contains(name) else {
                    return args.lazy.compactMap { check($0, inTable: inTable) }.first
                }
                guard case .ident(let t) = args[0] else { return "\(name): tham số đầu phải là tên bảng" }
                guard tables.contains(t) else { return "bảng không tồn tại: \(t)" }
                return args.dropFirst().lazy.compactMap { check($0, inTable: true) }.first
            default: return nil
            }
        }
        return check(root, inTable: false)
    }
}

struct FormulaParser {
    private let tokens: [Token]
    private var pos = 0

    fileprivate init(tokens: [Token]) { self.tokens = tokens }

    fileprivate static func tokenize(_ s: String) throws -> [Token] {
        var out: [Token] = []
        let chars = Array(s)
        var i = 0
        while i < chars.count {
            let ch = chars[i]
            if ch.isWhitespace { i += 1; continue }
            if ch.isNumber || (ch == "." && i + 1 < chars.count && chars[i + 1].isNumber) {
                var j = i
                while j < chars.count, chars[j].isNumber || chars[j] == "." { j += 1 }
                guard let n = Double(String(chars[i..<j])) else { throw FormulaError(message: "Số không hợp lệ") }
                out.append(.number(n)); i = j; continue
            }
            if ch.isLetter || ch == "_" {
                var j = i
                while j < chars.count, chars[j].isLetter || chars[j].isNumber || chars[j] == "_" { j += 1 }
                out.append(.ident(String(chars[i..<j]))); i = j; continue
            }
            switch ch {
            case "\"":
                var j = i + 1; var str = ""
                while j < chars.count, chars[j] != "\"" {
                    if chars[j] == "\\", j + 1 < chars.count { j += 1 }
                    str.append(chars[j]); j += 1
                }
                guard j < chars.count else { throw FormulaError(message: "Chuỗi chưa đóng") }
                out.append(.string(str)); i = j + 1
            case "{":
                guard let close = chars[i...].firstIndex(of: "}") else { throw FormulaError(message: "Thiếu '}'") }
                out.append(.ref(String(chars[(i + 1)..<close]).trimmingCharacters(in: .whitespaces))); i = close + 1
            case "(": out.append(.lparen); i += 1
            case ")": out.append(.rparen); i += 1
            case ",": out.append(.comma); i += 1
            default:
                let two = i + 1 < chars.count ? String(chars[i...i + 1]) : ""
                if ["&&", "||", "==", "!=", "<=", ">="].contains(two) { out.append(.op(two)); i += 2 }
                else if "+-*/<>!".contains(ch) { out.append(.op(String(ch))); i += 1 }
                else { throw FormulaError(message: "Ký tự không hợp lệ: \(ch)") }
            }
        }
        return out
    }

    private var peek: Token? { pos < tokens.count ? tokens[pos] : nil }
    private mutating func take() -> Token? { defer { pos += 1 }; return peek }
    private func isOp(_ ops: [String]) -> String? { if case .op(let o)? = peek, ops.contains(o) { return o } else { return nil } }

    mutating func parseAll() throws -> FormulaNode {
        let n = try parseOr()
        if peek != nil { throw FormulaError(message: "Thừa ký tự cuối công thức") }
        return n
    }

    private mutating func parseOr() throws -> FormulaNode {
        var l = try parseAnd()
        while isOp(["||"]) != nil { pos += 1; l = .binary("||", l, try parseAnd()) }
        return l
    }

    private mutating func parseAnd() throws -> FormulaNode {
        var l = try parseCmp()
        while isOp(["&&"]) != nil { pos += 1; l = .binary("&&", l, try parseCmp()) }
        return l
    }

    private mutating func parseCmp() throws -> FormulaNode {
        let l = try parseAdd()
        if let o = isOp(["==", "!=", "<=", ">=", "<", ">"]) { pos += 1; return .binary(o, l, try parseAdd()) }
        return l
    }

    private mutating func parseAdd() throws -> FormulaNode {
        var l = try parseMul()
        while let o = isOp(["+", "-"]) { pos += 1; l = .binary(o, l, try parseMul()) }
        return l
    }

    private mutating func parseMul() throws -> FormulaNode {
        var l = try parseUnary()
        while let o = isOp(["*", "/"]) { pos += 1; l = .binary(o, l, try parseUnary()) }
        return l
    }

    private mutating func parseUnary() throws -> FormulaNode {
        if let o = isOp(["-", "!"]) { pos += 1; return .unary(o, try parseUnary()) }
        return try parsePrimary()
    }

    private mutating func parsePrimary() throws -> FormulaNode {
        guard let t = take() else { throw FormulaError(message: "Công thức kết thúc đột ngột") }
        switch t {
        case .number(let n): return .number(n)
        case .string(let s): return .string(s)
        case .ref(let r):
            if r.hasPrefix("@") { return .rowRef(String(r.dropFirst())) }
            let bits = r.split(separator: ".", maxSplits: 1).map(String.init)
            guard let f = bits.first, !f.isEmpty else { throw FormulaError(message: "Tham chiếu rỗng") }
            return .ref(field: f, part: bits.count > 1 ? bits[1] : nil)
        case .lparen:
            let n = try parseOr()
            guard take() == .rparen else { throw FormulaError(message: "Thiếu ')'") }
            return n
        case .ident(let name):
            switch name {
            case "true": return .bool(true)
            case "false": return .bool(false)
            case "null": return .null
            default: break
            }
            guard peek == .lparen else { return .ident(name) }
            pos += 1
            var args: [FormulaNode] = []
            if peek == .rparen { pos += 1; return .call(name, args) }
            while true {
                args.append(try parseOr())
                guard let n = take() else { throw FormulaError(message: "Thiếu ')'") }
                if n == .rparen { break }
                if n != .comma { throw FormulaError(message: "Thiếu ',' hoặc ')'") }
            }
            return .call(name, args)
        default: throw FormulaError(message: "Token không mong đợi")
        }
    }
}
