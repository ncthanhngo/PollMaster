import Testing
import Foundation
@testable import PollMasterCore

private struct VectorRow: Decodable {
    var id: String
    var fixed: [String: JSONValue]?
    var values: [String: FieldValue]?
}
private struct VectorCase: Decodable {
    var name: String
    var formula: String
    var expected: JSONValue
    var samples: Int?
}
private struct Vectors: Decodable {
    var values: [String: FieldValue]
    var tables: [String: [VectorRow]]
    var cases: [VectorCase]
}

private func load() throws -> Vectors {
    let data = try Data(contentsOf: Fixtures.sharedDir.appendingPathComponent("formula-test-vectors.json"))
    return try JSONCoding.makeDecoder().decode(Vectors.self, from: data)
}

private func matches(_ a: JSONValue, _ b: JSONValue) -> Bool {
    switch (a, b) {
    case (.number(let x), .number(let y)): return abs(x - y) < 1e-6
    case (.array(let x), .array(let y)): return x.count == y.count && zip(x, y).allSatisfy { matches($0, $1) }
    default: return a == b
    }
}

@Suite struct FormulaTests {
    @Test func allSharedVectors() throws {
        let v = try load()
        let tables = v.tables.mapValues { rows in rows.map { (row: Row(id: $0.id, values: $0.values ?? [:]), fixed: $0.fixed ?? [:]) } }
        let env = FormulaEnvironment.make(values: v.values, tables: tables)
        #expect(v.cases.count >= 43)
        for c in v.cases {
            let f = try Formula(c.formula)
            let ev = FormulaEvaluator(env: env)
            let got = c.samples.map { ev.evaluateSamples(f, count: $0) } ?? ev.evaluate(f)
            #expect(matches(got, c.expected), "\(c.name): \(c.formula) -> \(got), expected \(c.expected)")
        }
    }

    @Test func parseErrors() {
        for bad in ["1 +", "(1", "{A", "\"abc", "1 2", "max(", "#"] {
            #expect(throws: FormulaError.self) { try Formula(bad) }
        }
    }

    @Test func roundingHalfAwayFromZero() {
        #expect(FormulaMath.round(2.345, 2) == 2.35)
        #expect(FormulaMath.round(-2.5, 0) == -3)
        #expect(FormulaMath.round(1.005, 2) == 1.01)
        #expect(ValueFormatter.number(12.50, decimals: 2) == "12.5")
        #expect(ValueFormatter.number(3, decimals: 2) == "3")
    }

    @Test func templateComputedFields() throws {
        let t = try Fixtures.template()
        var r = RecordFactory.make(template: t)
        r.values["G-22"] = FieldValue(v: [30, 25, nil])
        r.values["G-23"] = FieldValue(v: [60, 80, 50])
        r.tables["E1"] = [
            Row(values: ["mode": FieldValue(v: "O"), "hand": FieldValue(v: "2T")]),
            Row(values: ["mode": FieldValue(v: "O"), "hand": FieldValue(v: "1T-P")]),
        ]
        r.tables["E2"] = r.tables["E2"]!.map { row in
            var row = row
            if row.id == "S04-5" || row.id == "S04-10" { row.values["entered"] = FieldValue(v: row.id == "S04-5" ? true : false) }
            return row
        }
        let c = ComputedEngine(template: t, record: r).computeAll()
        #expect(c["E-03"] == .number(50))
        #expect(c["E-01"] == .number(5))
        guard case .array(let dp)? = c["G-24"] else { Issue.record("G-24 not array"); return }
        #expect(abs((dp[0].double ?? 0) - 21.385418967) < 1e-6)
        #expect(dp[2].isNull)
        let g24 = t.field("G-24")!
        #expect(ValueFormatter.displayComputed(g24, c["G-24"]) == "21.4 | 21.3 | –")
    }
}
