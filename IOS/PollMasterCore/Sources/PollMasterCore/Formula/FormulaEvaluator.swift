import Foundation

/// One table row as seen by a formula.
public struct FormulaRow {
    public var lookup: (String) -> JSONValue
    public var isEmpty: Bool
    public init(isEmpty: Bool, lookup: @escaping (String) -> JSONValue) { self.isEmpty = isEmpty; self.lookup = lookup }
}

/// Data access used by the evaluator. `field` gets `(id, part, sampleIndex)`.
public struct FormulaEnvironment {
    public var field: (String, String?, Int?) -> JSONValue
    public var rows: (String) -> [FormulaRow]
    public init(field: @escaping (String, String?, Int?) -> JSONValue, rows: @escaping (String) -> [FormulaRow]) {
        self.field = field; self.rows = rows
    }
}

public enum FormulaMath {
    /// Round half away from zero using the decimal representation (2.345 -> 2.35).
    public static func round(_ x: Double, _ digits: Int) -> Double {
        guard x.isFinite else { return x }
        var src = Decimal(string: "\(x)") ?? Decimal(x)
        var out = Decimal()
        NSDecimalRound(&out, &src, digits, .plain)
        // Via text: Decimal -> Double conversion directly is not exact (0.33 would not equal the literal 0.33).
        return Double("\(out)") ?? NSDecimalNumber(decimal: out).doubleValue
    }

    public static func dewpoint(_ t: Double, _ rh: Double) -> Double? {
        guard rh > 0 else { return nil }
        let a = 17.62, b = 243.12
        let g = log(rh / 100) + a * t / (b + t)
        return b * g / (a - g)
    }
}

public struct FormulaEvaluator {
    let env: FormulaEnvironment
    let sample: Int?
    let row: FormulaRow?

    public init(env: FormulaEnvironment, sample: Int? = nil) { self.env = env; self.sample = sample; self.row = nil }
    private init(env: FormulaEnvironment, sample: Int?, row: FormulaRow?) { self.env = env; self.sample = sample; self.row = row }

    /// Evaluate to a scalar (null/bool/number/string).
    public func evaluate(_ f: Formula) -> JSONValue { eval(f.root) }

    /// Evaluate `n` times for samples.
    public func evaluateSamples(_ f: Formula, count n: Int) -> JSONValue {
        .array((0..<n).map { FormulaEvaluator(env: env, sample: $0, row: row).evaluate(f) })
    }

    private func truthy(_ v: JSONValue) -> Bool { v.bool == true }

    private func eval(_ n: FormulaNode) -> JSONValue {
        switch n {
        case .number(let d): return .number(d)
        case .string(let s): return .string(s)
        case .bool(let b): return .bool(b)
        case .null, .ident: return .null
        case .ref(let f, let p): return env.field(f, p, sample)
        case .rowRef(let c): return row?.lookup(c) ?? .null
        case .unary(let op, let e):
            let v = eval(e)
            if op == "!" { return .bool(!truthy(v)) }
            return v.double.map { .number(-$0) } ?? .null
        case .binary(let op, let l, let r): return binary(op, l, r)
        case .call(let name, let args): return call(name, args)
        }
    }

    private func binary(_ op: String, _ l: FormulaNode, _ r: FormulaNode) -> JSONValue {
        if op == "&&" { return .bool(truthy(eval(l)) && truthy(eval(r))) }
        if op == "||" { return .bool(truthy(eval(l)) || truthy(eval(r))) }
        let a = eval(l), b = eval(r)
        switch op {
        case "==": return .bool(a == b)
        case "!=": return .bool(a != b)
        default: break
        }
        if let x = a.double, let y = b.double {
            switch op {
            case "+": return .number(x + y)
            case "-": return .number(x - y)
            case "*": return .number(x * y)
            case "/": return y == 0 ? .null : .number(x / y)
            case "<": return .bool(x < y)
            case "<=": return .bool(x <= y)
            case ">": return .bool(x > y)
            case ">=": return .bool(x >= y)
            default: return .null
            }
        }
        if let x = a.string, let y = b.string {
            switch op {
            case "<": return .bool(x < y)
            case "<=": return .bool(x <= y)
            case ">": return .bool(x > y)
            case ">=": return .bool(x >= y)
            default: break
            }
        }
        return .null
    }

    private func call(_ name: String, _ args: [FormulaNode]) -> JSONValue {
        func num(_ i: Int) -> Double? { i < args.count ? eval(args[i]).double : nil }
        switch name {
        case "abs": return num(0).map { .number(Swift.abs($0)) } ?? .null
        case "round":
            guard let x = num(0) else { return .null }
            return .number(FormulaMath.round(x, Int(num(1) ?? 0)))
        case "if":
            guard args.count == 3 else { return .null }
            return truthy(eval(args[0])) ? eval(args[1]) : eval(args[2])
        case "dewpoint":
            guard let t = num(0), let rh = num(1), let d = FormulaMath.dewpoint(t, rh) else { return .null }
            return .number(d)
        case "avg", "sum", "min", "max": return aggregate(name, args)
        case "count", "pct": return counting(name, args)
        default: return .null
        }
    }

    private func tableRows(_ args: [FormulaNode]) -> [FormulaRow] {
        guard case .ident(let t)? = args.first else { return [] }
        return env.rows(t)
    }

    private func scoped(_ r: FormulaRow) -> FormulaEvaluator { FormulaEvaluator(env: env, sample: sample, row: r) }

    private func aggregate(_ name: String, _ args: [FormulaNode]) -> JSONValue {
        guard args.count >= 2 else { return .null }
        var nums: [Double] = []
        for r in tableRows(args) where !r.isEmpty {
            let ev = scoped(r)
            if args.count > 2, !truthy(ev.eval(args[2])) { continue }
            if let d = ev.eval(args[1]).double { nums.append(d) }
        }
        guard !nums.isEmpty else { return .null }
        switch name {
        case "sum": return .number(nums.reduce(0, +))
        case "avg": return .number(nums.reduce(0, +) / Double(nums.count))
        case "min": return .number(nums.min()!)
        default: return .number(nums.max()!)
        }
    }

    private func counting(_ name: String, _ args: [FormulaNode]) -> JSONValue {
        let isPct = name == "pct"
        let filterIdx = isPct ? 2 : 1
        var total = 0, hits = 0
        for r in tableRows(args) where !r.isEmpty {
            let ev = scoped(r)
            if args.count > filterIdx, !truthy(ev.eval(args[filterIdx])) { continue }
            total += 1
            if isPct, args.count > 1, truthy(ev.eval(args[1])) { hits += 1 }
        }
        if !isPct { return .number(Double(total)) }
        return total == 0 ? .null : .number(100 * Double(hits) / Double(total))
    }
}
