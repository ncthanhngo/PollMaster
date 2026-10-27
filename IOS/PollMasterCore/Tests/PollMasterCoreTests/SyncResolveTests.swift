import Testing
import Foundation
@testable import PollMasterCore

/// A server that honours `base_rev`: stale revision -> 409 with the current copy; `base_rev` 0 on an existing record answers like the
/// real backend (same content -> 200, else 409).
private final class RevRemote: SyncRemote, @unchecked Sendable {
    var server: [String: SyncRecord] = [:]
    var log: [RecordChange] = []
    private(set) var puts: [SyncPutBody] = [], pdfs: [String] = []

    func pull(projectId: String, since: String?, cursor: String?) async throws -> SyncPage {
        SyncPage(serverTime: "T1", records: [], ids: server.values.map { SyncRef(recordId: $0.recordId, rev: $0.rev) })
    }
    func put(projectId: String, body: SyncPutBody, recordId: String) async throws -> SyncPutResult {
        puts.append(body)
        if let cur = server[recordId], body.baseRev != cur.rev {
            return SyncPlanner.sameContent(body.data, cur.data) ? .ok(cur) : .conflict(cur)
        }
        let s = SyncRecord(recordId: recordId, rev: (server[recordId]?.rev ?? 0) + 1, seq: 1, createdBy: "u1", createdByName: "An", data: body.data)
        server[recordId] = s
        return .ok(s)
    }
    func putMedia(projectId: String, recordId: String, name: String, file: URL, sha256: String) async throws {}
    func getMedia(projectId: String, recordId: String, name: String, to dest: URL) async throws { throw SyncRecordFailure("404") }
    func putPDF(projectId: String, recordId: String, file: URL) async throws { pdfs.append(recordId) }
    func delete(projectId: String, recordId: String) async throws {}
    func changes(projectId: String, recordId: String) async throws -> [RecordChange] { log }
}

private final class Counter: @unchecked Sendable { var n = 0 }

@Suite struct SyncResolveTests {
    private let pid = "11111111-1111-1111-1111-111111111111"

    private func setup() throws -> (root: URL, template: Template, remote: RevRemote, record: Record) {
        let root = Fixtures.tempDir()
        let t = try Fixtures.template()
        _ = try TemplateStore(root: root).install(Fixtures.templateData())
        try ProjectStore(root: root).save(Project(id: pid, code: "P", name: "P"))
        var r = RecordFactory.make(template: t, projectId: pid)
        r.seq = 1; r.createdBy = "u1"; r.serverRev = 1
        r.updatedAt = Date().addingTimeInterval(-30); r.erpUploadedAt = r.updatedAt.addingTimeInterval(-60)         // edited here since the last sync
        r.values["P1"] = FieldValue(v: "mine"); r.syncConflict = true
        try RecordStore(root: root).save(r)
        let remote = RevRemote()
        var theirs = r; theirs.values["P1"] = FieldValue(v: "theirs")
        remote.server[r.id] = SyncRecord(recordId: r.id, rev: 3, seq: 1, createdBy: "u1", createdByName: "An", data: try SyncApply.pushData(theirs),
                                         updatedByName: "Bình")
        return (root, t, remote, r)
    }

    @Test func keepMineOverwritesOnTopOfTheServerRevision() async throws {
        let (root, _, remote, r) = try setup()
        try await SyncEngine(root: root, remote: remote).keepMine(projectId: pid, recordId: r.id)
        #expect(remote.puts.map(\.baseRev) == [1, 3])
        #expect(remote.server[r.id]?.rev == 4 && remote.server[r.id]?.data.object?["values"]?.object?["P1"]?.object?["v"]?.string == "mine")
        let saved = try #require(RecordStore(root: root).load(r.id))
        #expect(saved.serverRev == 4 && !saved.syncConflict && !saved.isSyncDirty)
    }

    @Test func takeServerReplacesTheDeviceCopyAndKeepsItsPlace() async throws {
        let (root, _, remote, r) = try setup()
        var withPosition = r; withPosition.lastPosition = LastPosition(section: "A", field: "A-01")
        try RecordStore(root: root).save(withPosition)
        let got = try await SyncEngine(root: root, remote: remote).takeServer(projectId: pid, recordId: r.id)
        #expect(remote.puts.count == 1 && remote.puts[0].baseRev == 0 && remote.server[r.id]?.rev == 3)      // a probe, nothing written
        let saved = try #require(RecordStore(root: root).load(r.id))
        #expect(saved == got && saved.values["P1"]?.v == "theirs" && saved.serverRev == 3 && !saved.syncConflict && !saved.isSyncDirty)
        #expect(saved.lastPosition == withPosition.lastPosition)
    }

    @Test func takeServerWithIdenticalContentJustClearsTheFlag() async throws {
        let (root, _, remote, r) = try setup()
        remote.server[r.id] = SyncRecord(recordId: r.id, rev: 3, seq: 1, createdBy: "u1", createdByName: "An", data: try SyncApply.pushData(r))
        _ = try await SyncEngine(root: root, remote: remote).takeServer(projectId: pid, recordId: r.id)
        let saved = try #require(RecordStore(root: root).load(r.id))
        #expect(saved.serverRev == 3 && !saved.syncConflict && saved.values["P1"]?.v == "mine")
    }

    @Test func inspectTellsWhoEditedOrThatTheServerLostIt() async throws {
        let (root, _, remote, r) = try setup()
        remote.log = [RecordChange(rev: 3, changedByName: "Bình", changedAt: "2026-10-27T09:05:00Z")]
        let engine = SyncEngine(root: root, remote: remote)
        let info = try await engine.inspectConflict(projectId: pid, recordId: r.id)
        #expect(info == .edited(by: "Bình", at: ISODate.parse("2026-10-27T09:05:00Z")))
        remote.server[r.id] = nil
        #expect(try await engine.inspectConflict(projectId: pid, recordId: r.id) == .deleted)
    }

    @Test func pdfIsMadeAndSentOnlyForACompletedRecord() async throws {
        let (root, t, remote, r) = try setup()
        let made = Counter()
        let hooks = SyncHooks(makePDF: { _ in made.n += 1; return URL(fileURLWithPath: "/tmp/x.pdf") })
        var draft = r; draft.syncConflict = false; draft.serverRev = nil
        try RecordStore(root: root).save(draft)
        remote.server[r.id] = nil
        _ = try await SyncEngine(root: root, remote: remote, hooks: hooks).sync(projects: [pid], me: "u1")
        #expect(made.n == 0 && remote.pdfs.isEmpty && RecordStore(root: root).load(r.id)?.serverRev == 1)
        var done = try #require(RecordStore(root: root).load(r.id))
        done.status = .completed; done.completedAt = Date(); done.updatedAt = Date()
        done.erpUploadedAt = Date().addingTimeInterval(-30)
        try RecordStore(root: root).save(done)
        _ = try await SyncEngine(root: root, remote: remote, hooks: hooks).sync(projects: [pid], me: "u1")
        #expect(made.n == 1 && remote.pdfs == [r.id])
        _ = t
    }
}
