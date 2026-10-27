import Testing
import Foundation
@testable import PollMasterCore

@Suite struct TemplateDraftStoreTests {
    private func draft(_ id: String, base: String? = "T-1", updated: String = "2026-10-27T10:00:00Z") -> TemplateDraft {
        TemplateDraft(draftId: id, baseId: base, baseVersion: base == nil ? nil : "1.0", projectId: "P1", createdAt: "2026-10-27T09:00:00Z", updatedAt: updated,
                      content: .object(["id": .string(base ?? "NEW-1"), "version": "1.0", "title": "Mẫu", "x-custom": .number(2)]))
    }

    @Test func saveLoadListDelete() throws {
        let store = TemplateDraftStore(root: Fixtures.tempDir())
        #expect(store.list().isEmpty)
        var a = draft("d1"); a.publishVersion = "1.1"
        try store.save(a)
        try store.save(draft("d2", base: nil, updated: "2026-10-27T11:00:00Z"))
        #expect(store.load("d1") == a)
        #expect(store.list().map(\.draftId) == ["d2", "d1"])
        try store.delete("d1")
        #expect(store.load("d1") == nil)
        try store.delete("d1")
        #expect(store.list().map(\.draftId) == ["d2"])
    }

    @Test func overwriteKeepsOneFileAndNullsRoundTrip() throws {
        let store = TemplateDraftStore(root: Fixtures.tempDir())
        var d = draft("d1", base: nil)
        try store.save(d)
        d.content = .object(["id": "NEW-2", "title": "Đổi"])
        try store.save(d)
        let back = try #require(store.load("d1"))
        #expect(back.baseId == nil && back.baseVersion == nil && back.publishVersion == nil)
        #expect(back.templateId == "NEW-2")
        #expect(store.list().count == 1)
    }

    @Test func oneDraftPerTemplateId() throws {
        let store = TemplateDraftStore(root: Fixtures.tempDir())
        try store.save(draft("d1", base: "T-1"))
        try store.save(draft("d2", base: nil))
        #expect(store.existing(forTemplateId: "T-1")?.draftId == "d1")
        #expect(store.existing(forTemplateId: "NEW-1")?.draftId == "d2")
        #expect(store.existing(forTemplateId: "T-9") == nil)
    }

    @Test func brokenFilesAreSkipped() throws {
        let root = Fixtures.tempDir(), store = TemplateDraftStore(root: root)
        try store.save(draft("d1"))
        try Data("{not json".utf8).write(to: store.dir.appendingPathComponent("bad.json"))
        try Data("{\"draftId\":\"x\"}".utf8).write(to: store.dir.appendingPathComponent("partial.json"))
        #expect(store.list().map(\.draftId) == ["d1"])
    }
}

@Suite struct TemplateDraftFactoryTests {
    @Test func blankDraftIsAValidNewTemplate() throws {
        let d = TemplateDraft.blank(projectId: "P1", taken: [], now: "t0", random: { "ab12" })
        #expect(d.baseId == nil && d.templateId == "MAUMOI-AB12" && d.publishVersion == nil)
        #expect(d.doc.lint(base: nil).filter { $0.severity == .error }.isEmpty)
        #expect(TemplateLoader.problems(d.doc.data()).isEmpty)
    }

    @Test func cloneGetsNewCodeVersionAndTitle() throws {
        let source = try TemplateDoc(text: String(contentsOf: Fixtures.sharedDir.appendingPathComponent("templates/bb-tdp-01.json"), encoding: .utf8))
        let d = TemplateDraft.clone(of: source.root, projectId: "P1", taken: ["BBTDP-AB12"], now: "t0", random: { "ab12" })
        let o = d.content.object!
        #expect(o["id"]?.string != "BB-TDP-01" && d.baseId == nil && o["version"]?.string == "1.0")
        #expect(o["title"]?.string?.hasSuffix("(bản sao)") == true)
        #expect(o["sections"] == source.root.object?["sections"])
        #expect(d.doc.lint(base: nil).filter { $0.severity == .error }.isEmpty)
    }
}
