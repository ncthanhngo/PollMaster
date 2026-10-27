import Testing
import Foundation
@testable import PollMasterCore

private func template(id: String = "T-1", version: String, label: String = "Tên", title: String = "Mẫu") -> JSONValue {
    .object(["schemaVersion": 1, "id": .string(id), "version": .string(version), "title": .string(title),
             "sections": [.object(["id": "A", "title": "Chung", "items": [.object(["id": "A-01", "label": .string(label), "type": "text"])]])]])
}

/// One project's template calls on a fake server; every POST/PATCH is logged.
private final class PublishFake: TemplateRemote, @unchecked Sendable {
    var assigned: String?
    var responses: [String] = []
    var failPatch: Set<String> = []
    var canManage = true
    private(set) var calls: [String] = []

    func list(projectId: String) async throws -> ProjectTemplatesInfo? {
        guard let v = assigned else { return ProjectTemplatesInfo(canManage: canManage) }
        return ProjectTemplatesInfo(canManage: canManage, assigned: [ServerTemplateRef(templateId: "T-1", version: v)],
                                    templates: [ServerTemplate(templateId: "T-1", version: v, content: template(version: v))])
    }
    func add(projectId: String, content: JSONValue) async throws {
        let id = content.object?["id"]?.string ?? "", v = content.object?["version"]?.string ?? ""
        calls.append("add:\(projectId):\(id)@\(v)")
        switch responses.isEmpty ? "200" : responses.removeFirst() {
        case "409": throw TemplateVersionExists()
        case "400": throw TemplateSyncFailure("Mẫu không hợp lệ.")
        case "net": throw URLError(.notConnectedToInternet)
        default: if id == "T-1" { assigned = v }
        }
    }
    func patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?) async throws {
        calls.append("patch:\(projectId):\(templateId)@\(version ?? "meta")")
        if failPatch.contains(projectId) { throw TemplateSyncFailure("Không đổi được \(projectId).") }
    }
    func remove(projectId: String, templateId: String) async throws -> Bool { false }
}

private struct Rig {
    let root = Fixtures.tempDir()
    let fake = PublishFake()
    var sync: TemplateSync { TemplateSync(root: root, remote: fake) }
    var drafts: TemplateDraftStore { TemplateDraftStore(root: root) }

    func install(_ version: String) throws { try TemplateStore(root: root).install(Data(TemplateDoc(root: template(version: version)).pretty().utf8)) }

    func draft(baseVersion: String?, content: JSONValue, publishVersion: String? = nil) throws -> TemplateDraft {
        let d = TemplateDraft(baseId: baseVersion == nil ? nil : "T-1", baseVersion: baseVersion, projectId: "P1", createdAt: "t0", updatedAt: "t1", publishVersion: publishVersion, content: content)
        try drafts.save(d)
        return d
    }
}

private func vectorCases() throws -> [JSONValue] {
    let data = try Data(contentsOf: Fixtures.sharedDir.appendingPathComponent("sync-test-vectors.json"))
    return try JSONDecoder().decode(JSONValue.self, from: data).object!["publish"]!.array!
}

@Test func publishVectors() async throws {
    let cases = try vectorCases()
    #expect(cases.count >= 15)
    for c in cases {
        let o = c.object!, name = o["name"]!.string!, want = o["expect"]!.object!
        let rig = Rig()
        let baseVersion = o["baseVersion"]?.string
        for v in o["library"]!.array! { try rig.install(v.string!) }
        rig.fake.assigned = o["assigned"]?.string
        rig.fake.responses = o["responses"]!.array!.map { $0.string! }
        rig.fake.failPatch = Set(o["failPatch"]!.array!.map { $0.string! })
        let content: JSONValue
        switch o["draft"]!.string! {
        case "newVersion": content = template(version: baseVersion!, label: "Tên khác")
        case "meta": content = template(version: baseVersion!, title: "Tên mới")
        case "invalid": content = template(version: baseVersion!, label: "")
        default: content = template(id: "NEW-1", version: "1.0")
        }
        let draft = try rig.draft(baseVersion: baseVersion, content: content, publishVersion: o["publishVersion"]?.string)
        var outcome = "throws", result: PublishOutcome?
        do { result = try await rig.sync.publish(draft, to: "P1", also: o["also"]!.array!.map { $0.string! }, force: o["force"]!.bool!) } catch {}
        switch result {
        case .published(let v, let failed)?:
            outcome = "published"
            #expect(v == want["version"]?.string, "\(name): version")
            #expect(failed.map(\.projectId) == (want["failed"]?.array ?? []).map { $0.string! }, "\(name): failed")
        case .staleBase(let v)?: outcome = "staleBase"; #expect(v == want["serverVersion"]?.string, "\(name)")
        case .invalid(let issues)?: outcome = "invalid"; #expect(issues.allSatisfy { $0.severity == .error } && !issues.isEmpty, "\(name)")
        case .idTaken?: outcome = "idTaken"
        case nil: break
        }
        #expect(outcome == want["outcome"]!.string!, "\(name): \(outcome)")
        #expect(rig.fake.calls == want["calls"]!.array!.map { $0.string! }, "\(name): \(rig.fake.calls)")
        let kept = rig.drafts.load(draft.draftId)
        #expect((kept != nil) == want["draftKept"]!.bool!, "\(name): draft kept")
        if let pv = want["publishVersion"]?.string { #expect(kept?.publishVersion == pv, "\(name): publishVersion") }
    }
}

@Test func publishedVersionReachesTheDeviceLibrary() async throws {
    let rig = Rig()
    try rig.install("1.0")
    rig.fake.assigned = "1.0"
    let d = try rig.draft(baseVersion: "1.0", content: template(version: "1.0", label: "Tên khác"))
    _ = try await rig.sync.publish(d, to: "P1")
    // the fake server now holds 1.1 as assigned; refresh installed what it returned
    #expect(TemplateStore(root: rig.root).exists(id: "T-1", version: "1.1"))
}

@Test func publishNeedsManage() async throws {
    let rig = Rig()
    rig.fake.canManage = false
    let d = try rig.draft(baseVersion: "1.0", content: template(version: "1.0", label: "Khác"))
    await #expect(throws: TemplateSyncFailure.self) { _ = try await rig.sync.publish(d, to: "P1") }
    #expect(rig.fake.calls.isEmpty)
}

@Test func publishLocallyInstallsAndSwitchesTheProject() throws {
    let rig = Rig()
    let projects = ProjectStore(root: rig.root)
    try rig.install("1.0")
    let p = try projects.create(Project(code: "PX", name: "Dự án", kind: "company", templates: [TemplateRef(id: "T-1", version: "1.0")]))
    let d = try rig.draft(baseVersion: "1.0", content: template(version: "1.0", label: "Tên khác"))
    let out = try rig.sync.publishLocally(d, to: p.id)
    #expect(out == .published(version: "1.1", failedProjects: []))
    #expect(TemplateStore(root: rig.root).exists(id: "T-1", version: "1.1"))
    #expect(projects.load(p.id)?.templates == [TemplateRef(id: "T-1", version: "1.1")])
    #expect(rig.drafts.load(d.draftId) == nil)
}

@Test func publishLocallyNewTemplateAndMeta() throws {
    let rig = Rig()
    let projects = ProjectStore(root: rig.root)
    try rig.install("1.0")
    let p = try projects.create(Project(code: "PX", name: "Dự án", kind: "company"))
    let n = try rig.draft(baseVersion: nil, content: template(id: "NEW-1", version: "1.0"))
    #expect(try rig.sync.publishLocally(n, to: p.id) == .published(version: "1.0", failedProjects: []))
    #expect(projects.load(p.id)?.templates == [TemplateRef(id: "NEW-1", version: "1.0")])
    let m = try rig.draft(baseVersion: "1.0", content: template(version: "1.0", title: "Tên mới"))
    #expect(try rig.sync.publishLocally(m, to: p.id) == .published(version: "1.0", failedProjects: []))
    #expect(TemplateStore(root: rig.root).load(id: "T-1", version: "1.0")?.title == "Tên mới")
    #expect(!TemplateStore(root: rig.root).exists(id: "T-1", version: "1.1"))
}

@Test func rollBackUsesThePreviousVersionOnThisDevice() async throws {
    let rig = Rig()
    try rig.install("1.0"); try rig.install("1.2"); try rig.install("1.10")
    #expect(rig.sync.previousVersion(of: TemplateRef(id: "T-1", version: "1.10"))?.version == "1.2")
    #expect(rig.sync.previousVersion(of: TemplateRef(id: "T-1", version: "1.0")) == nil)
    await #expect(throws: TemplateSyncFailure.self) { try await rig.sync.rollBack(TemplateRef(id: "T-1", version: "1.0"), in: "P1") }
    rig.fake.assigned = "1.10"
    try await rig.sync.rollBack(TemplateRef(id: "T-1", version: "1.10"), in: "P1")
    #expect(rig.fake.calls == ["add:P1:T-1@1.2"])          // the server answers 200 for a version it already has; 409 would PATCH instead
}

@Test func previousVersionVectors() {
    #expect(TemplatePublishRules.previousVersion(of: "1.10", among: ["1.9", "1.10", "1.2"]) == "1.9")
    #expect(TemplatePublishRules.previousVersion(of: "1.0", among: ["1.0"]) == nil)
    #expect(TemplatePublishRules.previousVersion(of: "2", among: ["1.5", "1.0"]) == "1.5")
}

@Test func otherProjectsAreManagedActiveAndUseTheTemplate() {
    func project(_ id: String, manage: Bool, archived: Bool = false, uses: String? = "T-1") -> Project {
        var p = Project(id: id, code: id, name: id, kind: "company", templates: uses.map { [TemplateRef(id: $0, version: "1.0")] } ?? [])
        p.canManage = manage; p.archived = archived
        return p
    }
    let all = [project("P1", manage: true), project("P2", manage: true), project("P3", manage: false), project("P4", manage: true, archived: true), project("P5", manage: true, uses: "X")]
    #expect(TemplatePublishRules.otherProjects(using: "T-1", excluding: "P1", in: all).map(\.id) == ["P2"])
    #expect(TemplatePublishRules.manageable(using: "T-1", in: all).map(\.id) == ["P1", "P2"])
    #expect(TemplatePublishRules.manageable(using: nil, in: all).map(\.id) == ["P1", "P2", "P5"])
}
