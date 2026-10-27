import Testing
import Foundation
@testable import PollMasterCore

@Suite struct BackupReconcileTests {
    private func record() -> Record {
        var r = Record(templateId: "T", templateVersion: "1", projectId: "p1")
        r.updatedAt = Date(timeIntervalSince1970: 1_000)
        return r
    }

    @Test func untouchedRecordGetsStamped() throws {
        let snap = record()
        let now = Date(timeIntervalSince1970: 2_000)
        let out = try #require(BackupReconcile.stamped(snapshot: snap, current: snap, editorOpen: false, now: now))
        #expect(out.backupAt == now && !out.needsBackup)
    }

    @Test func editedMovedDeletedOrOpenedStaysUntouched() {
        let snap = record()
        var edited = snap; edited.updatedAt = snap.updatedAt.addingTimeInterval(5)
        var moved = snap; moved.projectId = "p2"
        #expect(BackupReconcile.stamped(snapshot: snap, current: edited, editorOpen: false) == nil)
        #expect(BackupReconcile.stamped(snapshot: snap, current: moved, editorOpen: false) == nil)
        #expect(BackupReconcile.stamped(snapshot: snap, current: nil, editorOpen: false) == nil)
        #expect(BackupReconcile.stamped(snapshot: snap, current: snap, editorOpen: true) == nil)
    }
}

@Suite struct ERPSafetyTests {
    @Test func projectIdMustBeAFolderSafeToken() {
        for ok in ["a", "abc-123", "0f8fad5b-d9cb-469f-a165-70867728950e", String(repeating: "x", count: 64)] { #expect(ERPProjectSync.isValidId(ok)) }
        for bad in ["", "../x", "a/b", "a b", ".", "..", "Đ1", "a_b", String(repeating: "x", count: 65)] { #expect(!ERPProjectSync.isValidId(bad)) }
    }

    @Test func applySkipsRowsWithUnsafeIds() throws {
        let store = ProjectStore(root: Fixtures.tempDir())
        let good = ERPProject(id: "ok-1", code: "A", name: "A", type: "company")
        let evil = ERPProject(id: "../escape", code: "B", name: "B", type: "company")
        _ = try ERPProjectSync.apply([good, evil], to: store)
        #expect(store.list().map(\.id) == ["ok-1"])
    }

    @Test func localDataCountsFolders() throws {
        let root = Fixtures.tempDir()
        #expect(ERPProjectSync.localDataCounts(root: root) == (0, 0))
        try RecordStore(root: root).save(Record(templateId: "T", templateVersion: "1", projectId: "x"))
        try ProjectStore(root: root).save(Project(code: "OLD", name: "Old"))
        #expect(ERPProjectSync.localDataCounts(root: root) == (1, 1))
        try ERPProjectSync.resetLocalData(root: root)
        #expect(ERPProjectSync.localDataCounts(root: root) == (0, 0))
    }

    @Test func onlyVietnameseSentencesAreShownToPeople() {
        #expect(ERPMessage.readable("Tổ chức chưa được bật dùng app di động.") != nil)
        #expect(ERPMessage.readable("  Tài khoản bị khóa  ") == "Tài khoản bị khóa")
        #expect(ERPMessage.readable("INVALID_CREDENTIALS") == nil)
        #expect(ERPMessage.readable("Invalid credentials") == nil)
        #expect(ERPMessage.readable("Email: required") == nil)
        #expect(ERPMessage.readable(nil) == nil && ERPMessage.readable("") == nil)
    }
}

@Suite struct RecordStoreEntryTests {
    @Test func entryAndIdsMatchTheListing() throws {
        let store = RecordStore(root: Fixtures.tempDir())
        let r = Record(templateId: "T", templateVersion: "1", projectId: "p")
        try store.save(r)
        try FileManager.default.createDirectory(at: store.folder("broken"), withIntermediateDirectories: true)
        try Data("{".utf8).write(to: store.folder("broken").appendingPathComponent("record.json"))
        #expect(Set(store.ids()) == [r.id, "broken"])
        #expect(store.entry(r.id)?.record?.id == r.id)
        #expect(store.entry("broken")?.error != nil && store.entry("broken")?.record == nil)
        #expect(store.entry("missing") == nil)
        #expect(store.listItems().count == 2)
    }

    @Test func statsAddByFields() {
        var s = ProjectStats()
        s.add(status: .draft, returned: true, updatedAt: Date(timeIntervalSince1970: 5))
        s.add(status: .completed, returned: false, updatedAt: Date(timeIntervalSince1970: 9))
        #expect(s.returned == 1 && s.completed == 1 && s.draft == 0 && s.latest == Date(timeIntervalSince1970: 9))
    }
}

@Suite struct ISODateCacheTests {
    @Test func formatsAndParsesUnchanged() throws {
        let tz = try #require(TimeZone(identifier: "Asia/Ho_Chi_Minh"))
        let d = Date(timeIntervalSince1970: 1_780_000_000)
        #expect(ISODate.format(d, "dd/MM/yyyy HH:mm", timeZone: tz) == ISODate.format(d, "dd/MM/yyyy HH:mm", timeZone: tz))
        #expect(ISODate.string(d, timeZone: tz).hasSuffix("+07:00"))
        let round = try #require(ISODate.parse(ISODate.string(d, timeZone: tz)))
        #expect(round == d)
        #expect(ISODate.format(d, "HH:mm", timeZone: TimeZone(identifier: "UTC")!) != ISODate.format(d, "HH:mm", timeZone: tz))
    }

    @Test func concurrentUseIsSafe() async {
        let d = Date(timeIntervalSince1970: 1_780_000_000)
        let expected = ISODate.string(d)
        await withTaskGroup(of: Bool.self) { g in
            for _ in 0..<50 { g.addTask { (0..<50).allSatisfy { _ in ISODate.string(d) == expected && ISODate.parse(expected) == d } } }
            for await ok in g { #expect(ok) }
        }
    }
}

@Suite struct MoveTargetsByTemplateTests {
    @Test func templateIdOverloadMatchesTheRecordOne() {
        let ref = TemplateRef(id: "BB-TDP-01", version: "1.2")
        var a = Project(code: "A", name: "A", templates: [ref]), b = Project(code: "B", name: "B", templates: [ref]), c = Project(code: "C", name: "C")
        c.templates = [TemplateRef(id: "OTHER", version: "1")]
        b.archived = false; a.archived = false
        let r = Record(templateId: "BB-TDP-01", templateVersion: "1.2", projectId: a.id)
        let byRecord = ProjectMove.targets(for: r, in: [a, b, c], currentId: a.id).map(\.id)
        #expect(byRecord == [b.id])
        #expect(ProjectMove.targets(templateId: "BB-TDP-01", in: [a, b, c], currentId: a.id).map(\.id) == byRecord)
    }
}
