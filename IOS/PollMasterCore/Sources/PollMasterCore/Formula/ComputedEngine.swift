import Foundation

extension FormulaEnvironment {
    /// Environment over stored values. `computed(id, sample)` resolves computed fields (nil = not computed);
    /// `hasSamples(id)` tells whether `v` arrays are per-sample values.
    public static func make(
        values: [String: FieldValue],
        tables: [String: [(row: Row, fixed: [String: JSONValue])]],
        hasSamples: @escaping (String) -> Bool = { _ in true },
        computed: @escaping (String, Int?) -> JSONValue? = { _, _ in nil }
    ) -> FormulaEnvironment {
        func clean(_ v: JSONValue?) -> JSONValue {
            guard let v, !v.isEmpty else { return .null }
            return v
        }
        return FormulaEnvironment(
            field: { id, part, sample in
                if let c = computed(id, sample) { return c }
                guard let fv = values[id], fv.flag != .notMeasurable else { return .null }
                if let part { return clean(fv.parts?[part]) }
                if case .array(let a)? = fv.v, hasSamples(id) {
                    guard let s = sample, s < a.count else { return .null }
                    return clean(a[s])
                }
                return clean(fv.v)
            },
            rows: { table in
                (tables[table] ?? []).map { entry in
                    FormulaRow(isEmpty: entry.row.isEmpty) { col in
                        if let fv = entry.row.values[col], fv.flag != .notMeasurable, let v = fv.v, !v.isEmpty { return v }
                        return entry.fixed[col] ?? .null
                    }
                }
            })
    }
}

/// Computes every `computed` field of a record.
public final class ComputedEngine {
    private let template: Template
    private let record: Record
    private var formulas: [String: Formula] = [:]
    private var memo: [String: JSONValue] = [:]
    private var inProgress: Set<String> = []
    private var env: FormulaEnvironment!

    public init(template: Template, record: Record) {
        self.template = template
        self.record = record
        for f in template.allFields where f.isComputed {
            if let src = f.formula, let parsed = try? Formula(src) { formulas[f.id] = parsed }
        }
        let tables = Dictionary(uniqueKeysWithValues: template.allTables.map { def -> (String, [(row: Row, fixed: [String: JSONValue])]) in
            let fixed = Dictionary((def.rows ?? []).map { ($0.id, $0.fixed) }, uniquingKeysWith: { a, _ in a })
            return (def.id, record.rows(of: def).map { ($0, fixed[$0.id] ?? [:]) })
        })
        let byId = Dictionary(template.allFields.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        env = FormulaEnvironment.make(
            values: record.values, tables: tables,
            hasSamples: { byId[$0]?.samples != nil },
            computed: { [unowned self] id, sample in
                guard byId[id]?.isComputed == true else { return nil }
                let r = self.value(of: id)
                if case .array(let a) = r { return sample.map { $0 < a.count ? a[$0] : .null } ?? .null }
                return r
            })
    }

    /// Raw (unrounded) result: number/bool/string/null, or array when the field has `samples`.
    public func value(of id: String) -> JSONValue {
        if let m = memo[id] { return m }
        guard let field = template.field(id), field.isComputed, let formula = formulas[id],
              !inProgress.contains(id) else { return .null }
        inProgress.insert(id)
        defer { inProgress.remove(id) }
        let ev = FormulaEvaluator(env: env)
        let result = field.samples.map { ev.evaluateSamples(formula, count: $0) } ?? ev.evaluate(formula)
        memo[id] = result
        return result
    }

    public func computeAll() -> [String: JSONValue] {
        var out: [String: JSONValue] = [:]
        for f in template.allFields where f.isComputed { out[f.id] = value(of: f.id) }
        return out
    }
}
