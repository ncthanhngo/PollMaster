import Testing
import Foundation
@testable import PollMasterCore

private struct ValidationCase {
    var name: String
    var data: Data
    var valid: Bool
    var problemPaths: [String]
    var warningPaths: [String]
}

private func loadCases() throws -> [ValidationCase] {
    let raw = try JSONSerialization.jsonObject(with: Data(contentsOf: Fixtures.sharedDir.appendingPathComponent("template-validation-vectors.json"))) as! [[String: Any]]
    return try raw.map { c in
        let template: Data
        if let file = c["template"] as? String { template = try Data(contentsOf: Fixtures.sharedDir.appendingPathComponent(file)) }
        else { template = try JSONSerialization.data(withJSONObject: c["template"]!) }
        return ValidationCase(name: c["name"] as! String, data: template, valid: c["valid"] as! Bool,
                              problemPaths: c["problemPaths"] as! [String], warningPaths: c["warningPaths"] as? [String] ?? [])
    }
}

private func path(_ problem: String) -> String { String(problem.components(separatedBy: ": ")[0]) }

/// Runs `shared/template-validation-vectors.json`, the same file the Android tests run.
@Test func templateValidationVectors() throws {
    let cases = try loadCases()
    #expect(cases.count >= 15)
    for c in cases {
        let problems = TemplateLoader.problems(c.data)
        #expect(problems.map(path).sorted() == c.problemPaths, "\(c.name): \(problems)")
        #expect(c.valid == problems.isEmpty, "\(c.name)")
        if problems.isEmpty {
            let t = try TemplateLoader.decode(c.data)
            #expect(TemplateLoader.warnings(t).map(path).sorted() == c.warningPaths, "\(c.name) warnings")
        }
    }
}
