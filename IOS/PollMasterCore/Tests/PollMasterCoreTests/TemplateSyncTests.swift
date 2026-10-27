import Testing
import Foundation
@testable import PollMasterCore

private struct Account: Decodable { var id: String?; var name: String? }
private struct Created: Decodable {
    struct Source: Decodable { var createdBy: String?; var createdByName: String? }
    struct Expect: Decodable { var createdBy: String?; var createdByName: String? }
    var name: String; var op: String; var me: Account?; var source: Source?; var expect: Expect
}
private struct TemplateCase: Decodable {
    var name: String; var local: [String]; var assigned: [String]; var removed: [String]
    var canManage: Bool; var isPhoenix: Bool; var library: [String]; var bundled: [String]
    var expectPost: [String]; var expectTemplates: [String]
}
private struct Vectors: Decodable { var created: [Created]; var templates: [TemplateCase] }

private func vectors() throws -> Vectors {
    try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: Fixtures.sharedDir.appendingPathComponent("sync-test-vectors.json")))
}

private func ref(_ s: String) -> TemplateRef {
    let p = s.split(separator: "@", maxSplits: 1).map(String.init)
    return TemplateRef(id: p[0], version: p[1])
}

/// In-memory ERP templates of one project.
private final class FakeTemplates: TemplateRemote, @unchecked Sendable {
    var info: ProjectTemplatesInfo?
    var library: [String: JSONValue] = [:]          // "id@version"
    var assigned: [String] = []                      // template ids in order
    var removed: Set<String> = []
    var canManage = true
    private(set) var posts: [String] = [], patches: [String] = [], deletes: [String] = []
    var refuse: String?
    var versionExists = false

    func list(projectId: String) async throws -> ProjectTemplatesInfo? {
        if let info { return info }
        let refs = assigned.enumerated().compactMap { i, id -> ServerTemplateRef? in
            guard let key = library.keys.sorted().last(where: { $0.hasPrefix(id + "@") }) else { return nil }
            return ServerTemplateRef(templateId: id, version: String(key.dropFirst(id.count + 1)), position: i)
        }
        return ProjectTemplatesInfo(canManage: canManage, assigned: refs, removedIds: removed.sorted(),
                                    templates: refs.compactMap { r in library["\(r.templateId)@\(r.version)"].map { ServerTemplate(templateId: r.templateId, version: r.version, content: $0) } })
    }
    func add(projectId: String, content: JSONValue) async throws {
        if let refuse { throw TemplateSyncFailure(refuse) }
        if versionExists { throw TemplateVersionExists() }
        let id = content.object?["id"]?.string ?? "", v = content.object?["version"]?.string ?? ""
        posts.append("\(id)@\(v)")
        library["\(id)@\(v)"] = content
        removed.remove(id)
        assigned.removeAll { $0 == id }
        assigned.append(id)
    }
    func patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?) async throws {
        if let refuse { throw TemplateSyncFailure(refuse) }
        patches.append("\(templateId)@\(version ?? "-")|\(title ?? "")|\(color ?? "")")
        guard assigned.contains(templateId), var key = library.keys.first(where: { $0.hasPrefix(templateId + "@") }) else { throw TemplateSyncFailure("404") }
        if let version { key = "\(templateId)@\(version)" }
        if var o = library[key]?.object, let title { o["title"] = .string(title); if let color, !color.isEmpty { o["color"] = .string(color) }; library[key] = .object(o) }
    }
    func remove(projectId: String, templateId: String) async throws -> Bool {
        deletes.append(templateId)
        guard assigned.contains(templateId) || removed.contains(templateId) else { return false }
        assigned.removeAll { $0 == templateId }; removed.insert(templateId)
        return true
    }
}

@Suite struct TemplateSyncTests {
    private let pid = "11111111-1111-1111-1111-111111111111"

    @Test func plannerVectors() throws {
        let v = try vectors()
        #expect(v.templates.count >= 12)
        for c in v.templates {
            let got = TemplateSyncPlanner.plan(local: c.local.map(ref), assigned: c.assigned.map(ref), removedIds: c.removed, canManage: c.canManage,
                                               isPhoenix: c.isPhoenix, library: c.library.map(ref), bundled: c.bundled.map(ref))
            #expect(got.post.map(\.key) == c.expectPost, "\(c.name): post")
            #expect(got.templates.map(\.key) == c.expectTemplates, "\(c.name): templates")
        }
    }

    @Test func createdByVectors() throws {
        let t = try Fixtures.template()
        for c in try vectors().created {
            let me = c.me.map { RecordFactory.Creator(id: $0.id, name: $0.name) }
            let r: Record
            if c.op == "make" { r = RecordFactory.make(template: t, projectId: "p", creator: me) }
            else {
                var source = RecordFactory.make(template: t, projectId: "p")
                source.createdBy = c.source?.createdBy; source.createdByName = c.source?.createdByName
                r = RecordFactory.duplicate(source, template: t, creator: me)
            }
            #expect(r.createdBy == c.expect.createdBy && r.createdByName == c.expect.createdByName, "\(c.name)")
        }
    }

    // MARK: engine

    private func setup(phoenix: Bool = false) throws -> (root: URL, remote: FakeTemplates, tpl: Data) {
        let root = Fixtures.tempDir()
        let data = try Fixtures.templateData()
        let t = try Fixtures.install(data, root: root)
        try ProjectStore(root: root).save(Project(id: pid, code: phoenix ? "PHOENIX" : "P", name: phoenix ? "Phoenix" : "P", templates: [TemplateRef(t)]))
        return (root, FakeTemplates(), data)
    }

    private func content(_ data: Data, title: String? = nil, version: String? = nil, id: String? = nil) throws -> JSONValue {
        var v = try JSONDecoder().decode(JSONValue.self, from: data)
        if case .object(var o) = v {
            if let title { o["title"] = .string(title) }
            if let version { o["version"] = .string(version) }
            if let id { o["id"] = .string(id) }
            v = .object(o)
        }
        return v
    }

    @Test func firstSyncOfAManagerUploadsTheDeviceList() async throws {
        let (root, remote, _) = try setup()
        let out = try await TemplateSync(root: root, remote: remote).sync(projectId: pid, library: [], bundled: [])
        #expect(remote.posts == ["BB-TDP-01@1.2"] && out.posted == 1 && out.failure == nil)
        #expect(ProjectStore(root: root).load(pid)?.templates == [TemplateRef(id: "BB-TDP-01", version: "1.2")])
    }

    @Test func serverWinsOnContentAndListAndRemembersRemoved() async throws {
        let (root, remote, data) = try setup()
        remote.canManage = false
        remote.library["BB-TDP-01@1.2"] = try content(data, title: "Tên của máy chủ")
        remote.assigned = ["BB-TDP-01"]; remote.removed = ["OLD-1"]
        let out = try await TemplateSync(root: root, remote: remote).sync(projectId: pid, library: [], bundled: [])
        #expect(out.changed && remote.posts.isEmpty)
        #expect(TemplateStore(root: root).load(id: "BB-TDP-01", version: "1.2")?.title == "Tên của máy chủ")
        #expect(ProjectStore(root: root).load(pid)?.removedTemplateIds == ["OLD-1"])
        let again = try await TemplateSync(root: root, remote: remote).sync(projectId: pid, library: [], bundled: [])
        #expect(!again.changed)                                   // same content: nothing rewritten
    }

    @Test func nonManagerKeepsTheDeviceListUntilTheServerHasData() async throws {
        let (root, remote, _) = try setup()
        remote.canManage = false
        _ = try await TemplateSync(root: root, remote: remote).sync(projectId: pid, library: [], bundled: [])
        #expect(remote.posts.isEmpty && ProjectStore(root: root).load(pid)?.templates.count == 1)
    }

    @Test func missingApiLeavesTheDeviceAlone() async throws {
        let (root, _, _) = try setup()
        struct Gone: TemplateRemote {
            func list(projectId: String) async throws -> ProjectTemplatesInfo? { nil }
            func add(projectId: String, content: JSONValue) async throws {}
            func patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?) async throws {}
            func remove(projectId: String, templateId: String) async throws -> Bool { true }
        }
        let out = try await TemplateSync(root: root, remote: Gone()).sync(projectId: pid, library: [], bundled: [])
        #expect(out.unavailable && ProjectStore(root: root).load(pid)?.templates.count == 1)
    }

    @Test func phoenixManagerUploadsNewBundledTemplateOnlyOnce() async throws {
        let (root, remote, data) = try setup(phoenix: true)
        let other = try Fixtures.install(try Fixtures.variantData(id: "KS-NEW-01", version: "1.0", title: "Mới"), root: root)
        remote.library["BB-TDP-01@1.2"] = try content(data); remote.assigned = ["BB-TDP-01"]
        let sync = TemplateSync(root: root, remote: remote)
        let bundled = [TemplateRef(id: "BB-TDP-01", version: "1.2"), TemplateRef(other)]
        _ = try await sync.sync(projectId: pid, library: bundled, bundled: bundled)
        #expect(remote.posts == ["KS-NEW-01@1.0"])
        try await sync.unassign(TemplateRef(other), from: pid)                    // the manager removes it: it stays removed
        _ = try await sync.sync(projectId: pid, library: bundled, bundled: bundled)
        #expect(remote.posts == ["KS-NEW-01@1.0"] && ProjectStore(root: root).load(pid)?.templates.map(\.id) == ["BB-TDP-01"])
        #expect(ProjectStore(root: root).load(pid)?.removedTemplateIds == ["KS-NEW-01"])
    }

    @Test func actionsChangeTheDeviceOnlyAfterTheServerAnswered() async throws {
        let (root, remote, data) = try setup()
        remote.library["BB-TDP-01@1.2"] = try content(data); remote.assigned = ["BB-TDP-01"]
        let sync = TemplateSync(root: root, remote: remote)
        let extra = try Fixtures.install(try Fixtures.variantData(id: "KS-NEW-01", version: "1.0", title: "Mới"), root: root)
        remote.refuse = "Bạn không có quyền quản lý dự án này."
        await #expect(throws: TemplateSyncFailure.self) { try await sync.assign([TemplateRef(extra)], to: pid) }
        #expect(ProjectStore(root: root).load(pid)?.templates.map(\.id) == ["BB-TDP-01"])
        remote.refuse = nil
        try await sync.assign([TemplateRef(extra)], to: pid)
        #expect(ProjectStore(root: root).load(pid)?.templates.map(\.id) == ["BB-TDP-01", "KS-NEW-01"])
        try await sync.unassign(TemplateRef(extra), from: pid)
        #expect(ProjectStore(root: root).load(pid)?.templates.map(\.id) == ["BB-TDP-01"])
    }

    @Test func removingATemplateTheServerNeverHadUploadsItFirst() async throws {
        let (root, remote, _) = try setup()
        remote.assigned = ["OTHER"]
        try await TemplateSync(root: root, remote: remote).unassign(TemplateRef(id: "BB-TDP-01", version: "1.2"), from: pid)
        #expect(remote.posts == ["BB-TDP-01@1.2"] && remote.deletes == ["BB-TDP-01", "BB-TDP-01"] && remote.removed == ["BB-TDP-01"])
    }

    @Test func newVersionFallsBackToPatchWhenTheLibraryHasIt() async throws {
        let (root, remote, data) = try setup()
        remote.library["BB-TDP-01@1.2"] = try content(data); remote.library["BB-TDP-01@1.1"] = try content(data, version: "1.1")
        remote.assigned = ["BB-TDP-01"]; remote.versionExists = true
        let newer = try Fixtures.install(try Fixtures.variantData(version: "1.1"), root: root)
        try await TemplateSync(root: root, remote: remote).useVersion(TemplateRef(newer), in: pid)
        #expect(remote.patches == ["BB-TDP-01@1.1||"])
    }

    @Test func editMetaGoesThroughTheServerAndComesBackAsContent() async throws {
        let (root, remote, data) = try setup()
        remote.library["BB-TDP-01@1.2"] = try content(data); remote.assigned = ["BB-TDP-01"]
        let sync = TemplateSync(root: root, remote: remote)
        try await sync.editMeta(of: TemplateRef(id: "BB-TDP-01", version: "1.2"), in: pid, title: "Tên mới", shortName: "TM", color: "#1B6B5A")
        #expect(TemplateStore(root: root).load(id: "BB-TDP-01", version: "1.2")?.title == "Tên mới")
        remote.refuse = "Không có quyền."
        await #expect(throws: TemplateSyncFailure.self) {
            try await sync.editMeta(of: TemplateRef(id: "BB-TDP-01", version: "1.2"), in: pid, title: "Khác", shortName: "", color: "")
        }
        #expect(TemplateStore(root: root).load(id: "BB-TDP-01", version: "1.2")?.title == "Tên mới")
    }
}
