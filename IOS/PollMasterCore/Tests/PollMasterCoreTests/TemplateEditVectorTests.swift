import Testing
import Foundation
@testable import PollMasterCore

/// Reads `shared/template-edit-vectors.json`, the same file the Android tests run.
private func vectors() throws -> [String: JSONValue] {
    let data = try Data(contentsOf: Fixtures.sharedDir.appendingPathComponent("template-edit-vectors.json"))
    return try JSONDecoder().decode(JSONValue.self, from: data).object!
}

private func docOf(_ v: JSONValue?) throws -> TemplateDoc? {
    switch v {
    case .string(let file)?: return try TemplateDoc(text: String(contentsOf: Fixtures.sharedDir.appendingPathComponent(file), encoding: .utf8))
    case .object?: return TemplateDoc(root: v!)
    default: return nil
    }
}

private func path(_ v: JSONValue?) -> DocPath {
    (v?.array ?? []).map { $0.string.map(PathKey.key) ?? .index(Int($0.double!)) }
}

private func op(_ j: JSONValue) -> EditOp {
    let o = j.object!
    func int(_ k: String) -> Int { Int(o[k]!.double!) }
    switch o["op"]!.string! {
    case "set": return .set(path: path(o["path"]), key: o["key"]!.string!, value: o["value"])
    case "insert": return .insert(path: path(o["path"]), index: int("index"), node: o["node"]!)
    case "delete": return .delete(path: path(o["path"]), index: int("index"))
    case "move": return .move(path: path(o["path"]), from: int("from"), to: int("to"))
    case "moveItem": return .moveItem(fromSection: int("fromSection"), index: int("index"), toSection: int("toSection"))
    case "duplicate": return .duplicate(path: path(o["path"]), index: int("index"))
    case "replace": return o["json"] != nil ? .replaceText(path: path(o["path"]), text: o["json"]!.string!) : .replace(path: path(o["path"]), node: o["node"]!)
    case "newItem": return .newItem(path: path(o["path"]), index: o["index"]?.double.map { Int($0) }, kind: o["kind"]!.string!, type: o["type"]?.string)
    default: fatalError("unknown op \(o["op"]!)")
    }
}

private func patched(_ base: TemplateDoc, _ patches: [JSONValue]) throws -> TemplateDoc {
    try patches.reduce(base) { doc, p in
        let o = p.object!
        return try doc.updating(path(o["path"])) { v in
            guard var obj = v?.object else { throw TemplateEditError.badPath }
            obj[o["key"]!.string!] = o["value"]
            return .object(obj)
        }
    }
}

private func lintKeys(_ issues: [TemplateIssue]) -> [String] { issues.map { "\($0.code)|\($0.path)" }.sorted() }

@Test func editVectorCases() throws {
    let cases = try vectors()["cases"]!.array!
    #expect(cases.count >= 30)
    for c in cases {
        let o = c.object!, name = o["name"]!.string!
        let base = try docOf(o["base"])!
        let ops = o["ops"]!.array!.map(op)
        if let code = o["expect"]?.object?["error"]?.string {
            do { _ = try TemplateEdit.apply(base, ops); Issue.record("\(name): expected \(code)") }
            catch let e as TemplateEditError { #expect(e.code == code, "\(name): \(e.code)") }
            continue
        }
        let got = try TemplateEdit.apply(base, ops)
        let want: TemplateDoc
        if o["expect"]?.object?["unchanged"] != nil { want = base }
        else if let patches = o["expectPatch"]?.array { want = try patched(base, patches) }
        else { want = TemplateDoc(root: o["expect"]!) }
        #expect(got.semanticEquals(want), "\(name):\n\(got.pretty())\n---\n\(want.pretty())")
        #expect(try TemplateDoc(text: got.pretty()).semanticEquals(got), "\(name): pretty round trip")
        if let lint = o["expectLint"]?.array {
            let isNew = o["newTemplate"]?.bool ?? false
            let want = lint.map { "\($0.object!["code"]!.string!)|\($0.object!["path"]!.string!)" }.sorted()
            #expect(lintKeys(got.lint(base: base, isNewTemplate: isNew)) == want, "\(name) lint")
        } else {
            #expect(TemplateLoader.problems(got.data()).isEmpty, "\(name): \(TemplateLoader.problems(got.data()))")
        }
    }
}

@Test func editVectorVersionsIdsAndKinds() throws {
    let v = try vectors()
    for c in v["versions"]!.array! {
        let known = c.object!["known"]!.array!.map { $0.string! }
        #expect(TemplatePublishRules.nextVersion(known: known) == c.object!["expect"]!.string!, "\(known)")
    }
    for c in v["ids"]!.array! {
        let o = c.object!, base = TemplateDoc(root: o["base"]!), p = path(o["path"])
        let got = try TemplateEdit.apply(base, .newItem(path: p, index: nil, kind: o["kind"]!.string!, type: nil))
        #expect(got.node(at: p)!.array!.last!.object!["id"]!.string! == o["expectId"]!.string!, "\(o["name"]!.string!)")
    }
    for c in v["optionValues"]!.array! {
        let o = c.object!
        #expect(TemplateIds.optionValue(label: o["label"]!.string!, taken: Set(o["taken"]!.array!.map { $0.string! })) == o["expect"]!.string!, "\(o["name"]!.string!)")
    }
    for c in v["templateIds"]!.array! {
        let o = c.object!
        var randoms = o["randoms"]!.array!.map { $0.string! }
        let id = TemplateIds.templateId(shortName: o["shortName"]?.string, title: o["title"]!.string!, taken: Set(o["taken"]!.array!.map { $0.string! }), random: { randoms.removeFirst() })
        #expect(id == o["expect"]!.string!, "\(o["name"]!.string!)")
    }
    for c in v["publishKinds"]!.array! {
        let o = c.object!
        let base = try docOf(o["base"])
        let draft = try TemplateEdit.apply(base ?? TemplateDoc(root: .object([:])), o["ops"]!.array!.map(op))
        #expect(TemplatePublishRules.publishKind(base: base, draft: draft).rawValue == o["expect"]!.string!, "\(o["name"]!.string!)")
    }
    for c in v["pretty"]!.array! {
        let o = c.object!
        #expect(TemplateDoc(root: o["doc"]!).pretty() == o["expect"]!.string!, "\(o["name"]!.string!)")
    }
}

@Test func packagedTemplatesSurvivePrettyAndLint() throws {
    for file in ["bb-tdp-01.json", "ks-vf-swap-01.json"] {
        let text = try String(contentsOf: Fixtures.sharedDir.appendingPathComponent("templates/\(file)"), encoding: .utf8)
        let doc = try TemplateDoc(text: text)
        #expect(try TemplateDoc(text: doc.pretty()).semanticEquals(doc), "\(file)")
        #expect(TemplateLoader.problems(doc.data()).isEmpty, "\(file)")
        #expect(doc.lint(base: doc).isEmpty, "\(file): \(doc.lint(base: doc))")
    }
}

@Test func pathTextRoundTrip() {
    let p: DocPath = ["sections", 2, "items", 5, "options"]
    #expect(p.text == "sections[2].items[5].options")
    #expect(DocPath.parse(p.text) == p)
    #expect(DocPath.parse("header[0]") == ["header", 0])
    #expect(DocPath.parse("a[x]") == nil)
    #expect(DocPath.parse("") == [])
}
