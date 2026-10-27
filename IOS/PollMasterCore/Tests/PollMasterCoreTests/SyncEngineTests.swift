import Testing
import Foundation
@testable import PollMasterCore

/// In-memory ERP: one project, records with rev, media by name.
private final class FakeRemote: SyncRemote, @unchecked Sendable {
    var server: [String: SyncRecord] = [:]
    var blobs: [String: Data] = [:]                 // "recordId/name"
    var pages: [[SyncRecord]]?                      // forced pagination of the records list
    var conflictOnPut: SyncRecord?
    var canPost = true, canManage = false
    private(set) var puts: [SyncPutBody] = [], uploaded: [String] = [], deleted: [String] = [], downloaded: [String] = [], pulls: [(String?, String?)] = []
    var failOffline = false

    func pull(projectId: String, since: String?, cursor: String?) async throws -> SyncPage {
        pulls.append((since, cursor))
        if failOffline { throw URLError(.notConnectedToInternet) }
        let ids = server.values.map { SyncRef(recordId: $0.recordId, rev: $0.rev) }
        guard let pages else {
            return SyncPage(serverTime: "T1", canPost: canPost, canManage: canManage, records: Array(server.values), ids: ids)
        }
        let n = cursor.flatMap(Int.init) ?? 0
        return SyncPage(serverTime: "T\(n + 1)", canPost: canPost, canManage: canManage, records: pages[n],
                        ids: n == 0 ? ids : [], nextCursor: n + 1 < pages.count ? String(n + 1) : nil)
    }

    func put(projectId: String, body: SyncPutBody, recordId: String) async throws -> SyncPutResult {
        puts.append(body)
        if let c = conflictOnPut { return .conflict(c) }
        let missing = body.media.filter { blobs["\(recordId)/\($0.key)"] == nil }.keys.sorted()
        if !missing.isEmpty { return .mediaMissing(missing) }
        let rev = (server[recordId]?.rev ?? 0) + 1
        let s = SyncRecord(recordId: recordId, rev: rev, seq: 7, createdBy: "u1", createdByName: "An", data: body.data,
                           media: body.media.map { SyncMediaInfo(name: $0.key, sha256: $0.value) })
        server[recordId] = s
        return .ok(s)
    }

    func putMedia(projectId: String, recordId: String, name: String, file: URL, sha256: String) async throws {
        blobs["\(recordId)/media/\(name)"] = try Data(contentsOf: file); uploaded.append(name)
    }

    func getMedia(projectId: String, recordId: String, name: String, to dest: URL) async throws {
        downloaded.append(name)
        guard let d = blobs["\(recordId)/media/\(name)"] else { throw SyncRecordFailure("404") }
        try d.write(to: dest)
    }

    func putPDF(projectId: String, recordId: String, file: URL) async throws {}

    func delete(projectId: String, recordId: String) async throws {
        deleted.append(recordId); server[recordId] = nil
    }

    func changes(projectId: String, recordId: String) async throws -> [RecordChange] { [] }
}

@Suite struct SyncEngineTests {
    private let pid = "11111111-1111-1111-1111-111111111111"

    private func setup() throws -> (root: URL, template: Template, remote: FakeRemote) {
        let root = Fixtures.tempDir()
        let t = try Fixtures.template()
        _ = try TemplateStore(root: root).install(Fixtures.templateData())
        try ProjectStore(root: root).save(Project(id: pid, code: "P", name: "P"))
        return (root, t, FakeRemote())
    }

    private func local(_ root: URL, _ t: Template, id: String = UUID().uuidString) throws -> Record {
        var r = RecordFactory.make(template: t, projectId: pid)
        r = Record(id: id, templateId: r.templateId, templateVersion: r.templateVersion, createdAt: Date(timeIntervalSinceNow: -100), values: r.values, projectId: pid)
        r.seq = 1
        try RecordStore(root: root).save(r)
        return r
    }

    private func serverCopy(_ r: Record, rev: Int, by: String = "u2", media: [SyncMediaInfo] = []) throws -> SyncRecord {
        SyncRecord(recordId: r.id, rev: rev, seq: r.seq, createdBy: by, createdByName: "Bình", data: try SyncApply.pushData(r), media: media)
    }

    private func addMedia(_ root: URL, _ r: inout Record, name: String, bytes: String) throws {
        let store = RecordStore(root: root)
        try store.mediaDir(r.id).createDirectoryIfNeeded()
        try Data(bytes.utf8).write(to: store.fileURL(r.id, "media/\(name)"))
        r.values["P1"] = FieldValue(media: (r.values["P1"]?.media ?? []) + [Media(file: "media/\(name)")])
        try store.save(r)
    }

    @Test func newRecordIsPushedWithItsMediaAndTheAnswerIsRemembered() async throws {
        let (root, t, remote) = try setup()
        var r = try local(root, t)
        try addMedia(root, &r, name: "a.jpg", bytes: "photo")
        let rep = try await SyncEngine(root: root, remote: remote).sync(projects: [pid], me: "u1")[pid]!
        #expect(rep.pushed == 1 && rep.failures.isEmpty)
        #expect(remote.puts.count == 2 && remote.puts[0].baseRev == 0)           // first answer MEDIA_MISSING, then again
        #expect(remote.uploaded == ["a.jpg"])
        #expect(remote.puts[1].data.object?["serverRev"] == nil && remote.puts[1].data.object?["erpUploadedAt"] == nil)
        let saved = try #require(RecordStore(root: root).load(r.id))
        #expect(saved.serverRev == 1 && saved.seq == 7 && saved.createdBy == "u1" && saved.createdByName == "An")
        #expect(!saved.isSyncDirty && ISODate.string(saved.updatedAt) == ISODate.string(r.updatedAt))           // seq/bookkeeping never count as an edit
        #expect(ProjectStore(root: root).load(pid)?.recordsSyncedAt == "T1")
    }

    @Test func pullCreatesRecordWithPhotosAndLeavesVideosForWhenOpened() async throws {
        let (root, t, remote) = try setup()
        var r = try local(root, t)
        try addMedia(root, &r, name: "a.jpg", bytes: "photo"); try addMedia(root, &r, name: "v.mp4", bytes: "video")
        try RecordStore(root: root).delete(r.id)
        remote.server[r.id] = try serverCopy(r, rev: 3)
        remote.blobs["\(r.id)/media/a.jpg"] = Data("photo".utf8); remote.blobs["\(r.id)/media/v.mp4"] = Data("video".utf8)
        let engine = SyncEngine(root: root, remote: remote)
        let rep = try await engine.sync(projects: [pid], me: "u1")[pid]!
        let store = RecordStore(root: root)
        let got = try #require(store.load(r.id))
        #expect(rep.created == 1 && got.serverRev == 3 && got.createdBy == "u2" && got.createdByName == "Bình" && !got.isSyncDirty && got.projectId == pid)
        #expect(FileManager.default.fileExists(atPath: store.fileURL(r.id, "media/a.jpg").path))
        #expect(!FileManager.default.fileExists(atPath: store.fileURL(r.id, "media/v.mp4").path))
        await engine.downloadMedia(projectId: pid, recordId: r.id)
        #expect(FileManager.default.fileExists(atPath: store.fileURL(r.id, "media/v.mp4").path))
    }

    @Test func serverNewerOverwritesCleanButFlagsDirtyAsConflict() async throws {
        let (root, t, remote) = try setup()
        var clean = try local(root, t), dirty = try local(root, t)
        clean.serverRev = 1; clean.erpUploadedAt = clean.updatedAt; clean.createdBy = "u2"
        dirty.serverRev = 1; dirty.createdBy = "u1"; dirty.erpUploadedAt = dirty.updatedAt.addingTimeInterval(-10)
        let store = RecordStore(root: root)
        try store.save(clean); try store.save(dirty)
        var changed = clean; changed.values["P1"] = FieldValue(v: "server")
        changed.updatedAt = Date()
        remote.server[clean.id] = try serverCopy(changed, rev: 2)
        var other = dirty; other.values["P1"] = FieldValue(v: "theirs")
        remote.server[dirty.id] = try serverCopy(other, rev: 2, by: "u1")
        let rep = try await SyncEngine(root: root, remote: remote).sync(projects: [pid], me: "u1")[pid]!
        #expect(rep.overwritten == 1 && rep.conflicts == 1 && remote.puts.isEmpty)
        #expect(store.load(clean.id)?.values["P1"]?.v == "server" && store.load(clean.id)?.serverRev == 2)
        #expect(store.load(dirty.id)?.syncConflict == true && store.load(dirty.id)?.values["P1"]?.v != "theirs")
    }

    @Test func conflictOn409AndDeletedOnServer() async throws {
        let (root, t, remote) = try setup()
        var r = try local(root, t)
        r.serverRev = 1; r.createdBy = "u1"; r.erpUploadedAt = r.updatedAt.addingTimeInterval(-10)
        var gone = try local(root, t)
        gone.serverRev = 4; gone.erpUploadedAt = gone.updatedAt                    // clean, absent on the server
        let store = RecordStore(root: root)
        try store.save(r); try store.save(gone)
        remote.server[r.id] = try serverCopy(r, rev: 1, by: "u1")
        remote.conflictOnPut = try serverCopy(r, rev: 2, by: "u1")
        let rep = try await SyncEngine(root: root, remote: remote).sync(projects: [pid], me: "u1")[pid]!
        #expect(rep.conflicts == 1 && rep.deletedLocal == 1)
        #expect(store.load(r.id)?.syncConflict == true && store.load(r.id)?.serverRev == 1)
        #expect(store.load(gone.id) == nil)
    }

    @Test func recordSentByOlderBuildIsAdoptedOrReplaced() async throws {
        let (root, t, remote) = try setup()
        var same = try local(root, t), differs = try local(root, t)
        for i in [0, 1] {
            var r = i == 0 ? same : differs
            r.erpUploadedAt = r.updatedAt                                                   // sent by hand, nothing since
            try RecordStore(root: root).save(r)
        }
        remote.server[same.id] = try serverCopy(same, rev: 5)
        var theirs = differs; theirs.values["P1"] = FieldValue(v: "edited on the web"); theirs.updatedAt = Date()
        remote.server[differs.id] = try serverCopy(theirs, rev: 6)
        remote.conflictOnPut = nil
        // the fake answers 409 for every base_rev 0 put of an existing record
        let engine = SyncEngine(root: root, remote: Conflicting(remote), hooks: SyncHooks())
        let rep = try await engine.sync(projects: [pid], me: "u1")[pid]!
        let store = RecordStore(root: root)
        #expect(rep.pushed == 1 && rep.overwritten == 1 && rep.conflicts == 0)
        #expect(store.load(same.id)?.serverRev == 5 && store.load(same.id)?.syncConflict == false)
        #expect(store.load(differs.id)?.serverRev == 6 && store.load(differs.id)?.values["P1"]?.v == "edited on the web")
    }

    @Test func pendingDeleteIsSentAndRecordNotBroughtBack() async throws {
        let (root, t, remote) = try setup()
        let r = try local(root, t)
        try RecordStore(root: root).delete(r.id)
        remote.server[r.id] = try serverCopy(r, rev: 2, by: "u1")
        try PendingDeletes(root: root).add(PendingDelete(projectId: pid, recordId: r.id))
        let rep = try await SyncEngine(root: root, remote: remote).sync(projects: [pid], me: "u1")[pid]!
        #expect(remote.deleted == [r.id] && rep.deletedRemote == 1 && rep.created == 0)
        #expect(PendingDeletes(root: root).load().isEmpty && RecordStore(root: root).load(r.id) == nil)
    }

    @Test func allPagesAreReadAndTheCursorIsSavedOnlyAtTheEnd() async throws {
        let (root, t, remote) = try setup()
        let a = try local(root, t), b = try local(root, t)
        try RecordStore(root: root).delete(a.id); try RecordStore(root: root).delete(b.id)
        let sa = try serverCopy(a, rev: 1), sb = try serverCopy(b, rev: 1)
        remote.server = [a.id: sa, b.id: sb]
        remote.pages = [[sa], [sb]]
        _ = try await SyncEngine(root: root, remote: remote).sync(projects: [pid], me: "u1")
        #expect(RecordStore(root: root).load(a.id) != nil && RecordStore(root: root).load(b.id) != nil)
        #expect(remote.pulls.count == 2 && remote.pulls[1].1 == "1")
        #expect(ProjectStore(root: root).load(pid)?.recordsSyncedAt == "T1")
        // next run asks since the saved cursor
        _ = try await SyncEngine(root: root, remote: remote).sync(projects: [pid], me: "u1")
        #expect(remote.pulls[2].0 == "T1")
    }

    @Test func openRecordIsNotOverwrittenAndKeepsTheCursorBack() async throws {
        let (root, t, remote) = try setup()
        var r = try local(root, t)
        r.serverRev = 1; r.erpUploadedAt = r.updatedAt; r.createdBy = "u2"
        try RecordStore(root: root).save(r)
        var newer = r; newer.values["P1"] = FieldValue(v: "server")
        remote.server[r.id] = try serverCopy(newer, rev: 2)
        let engine = SyncEngine(root: root, remote: remote, hooks: SyncHooks(isOpen: { _ in true }))
        let rep = try await engine.sync(projects: [pid], me: "u1")[pid]!
        #expect(rep.incomplete && RecordStore(root: root).load(r.id)?.serverRev == 1)
        #expect(ProjectStore(root: root).load(pid)?.recordsSyncedAt == nil)
    }

    @Test func permissionsAreStoredAndOthersDirtyRecordsAreNotPushed() async throws {
        let (root, t, remote) = try setup()
        var theirs = try local(root, t)
        theirs.createdBy = "u2"; theirs.serverRev = 1
        try RecordStore(root: root).save(theirs)
        remote.server[theirs.id] = try serverCopy(theirs, rev: 1)
        remote.canPost = true; remote.canManage = false
        _ = try await SyncEngine(root: root, remote: remote).sync(projects: [pid], me: "u1")
        #expect(remote.puts.isEmpty)
        remote.canPost = false
        _ = try await SyncEngine(root: root, remote: remote).sync(projects: [pid], me: "u1")
        #expect(ProjectStore(root: root).load(pid)?.canPost == false)
    }

    @Test func offlineStopsTheRunAndKeepsCursor() async throws {
        let (root, t, remote) = try setup()
        _ = try local(root, t)
        remote.failOffline = true
        await #expect(throws: URLError.self) { try await SyncEngine(root: root, remote: remote).sync(projects: [pid], me: "u1") }
        #expect(ProjectStore(root: root).load(pid)?.recordsSyncedAt == nil)
    }

    @Test func pruneRemovesCleanRecordsOfOtherAccountAndLostProjects() async throws {
        let (root, t, remote) = try setup()
        var old = try local(root, t), mine = try local(root, t), dirtyOld = try local(root, t)
        old.createdBy = "u1"; old.erpUploadedAt = old.updatedAt
        mine.createdBy = "u2"; mine.erpUploadedAt = mine.updatedAt
        dirtyOld.createdBy = "u1"
        let store = RecordStore(root: root)
        for r in [old, mine, dirtyOld] { try store.save(r) }
        let removed = await SyncEngine(root: root, remote: remote).prune(me: "u2", lastUserId: "u1", lostProjects: [])
        #expect(removed == [old.id] && store.load(mine.id) != nil && store.load(dirtyOld.id) != nil)
        let lost = await SyncEngine(root: root, remote: remote).prune(me: "u2", lastUserId: "u2", lostProjects: [pid])
        #expect(lost == [mine.id] && store.load(dirtyOld.id) != nil)
    }
}

/// Wraps the fake so that a put with base_rev 0 for a record the server has answers 409 with the server copy (what the backend does).
private final class Conflicting: SyncRemote, @unchecked Sendable {
    let inner: FakeRemote
    init(_ inner: FakeRemote) { self.inner = inner }
    func pull(projectId: String, since: String?, cursor: String?) async throws -> SyncPage { try await inner.pull(projectId: projectId, since: since, cursor: cursor) }
    func put(projectId: String, body: SyncPutBody, recordId: String) async throws -> SyncPutResult {
        if body.baseRev == 0, let cur = inner.server[recordId] {
            return SyncPlanner.sameContent(body.data, cur.data) ? .ok(cur) : .conflict(cur)
        }
        return try await inner.put(projectId: projectId, body: body, recordId: recordId)
    }
    func putMedia(projectId: String, recordId: String, name: String, file: URL, sha256: String) async throws { try await inner.putMedia(projectId: projectId, recordId: recordId, name: name, file: file, sha256: sha256) }
    func getMedia(projectId: String, recordId: String, name: String, to dest: URL) async throws { try await inner.getMedia(projectId: projectId, recordId: recordId, name: name, to: dest) }
    func putPDF(projectId: String, recordId: String, file: URL) async throws {}
    func delete(projectId: String, recordId: String) async throws { try await inner.delete(projectId: projectId, recordId: recordId) }
    func changes(projectId: String, recordId: String) async throws -> [RecordChange] { [] }
}

private extension URL {
    func createDirectoryIfNeeded() throws { try FileManager.default.createDirectory(at: self, withIntermediateDirectories: true) }
}
