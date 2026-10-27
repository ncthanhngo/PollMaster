import Testing
import Foundation
@testable import PollMasterCore

private struct LocalIn: Decodable {
    var id: String; var createdBy: String?; var serverRev: Int?; var conflict: Bool; var dirty: Bool
    var value: SyncPlanner.Local { .init(id: id, createdBy: createdBy, serverRev: serverRev, conflict: conflict, dirty: dirty) }
}
private struct RefIn: Decodable { var id: String; var rev: Int; var value: SyncRef { SyncRef(recordId: id, rev: rev) } }
private struct PlanCase: Decodable {
    var name: String; var me: String; var canPost: Bool; var canManage: Bool
    var local: [LocalIn]; var changed: [RefIn]; var ids: [RefIn]; var deleted: [String]; var expect: [String: String]
}
private struct AccountCase: Decodable { var name: String; var me: String; var local: [LocalIn]; var expect: [String] }
private struct LostCase: Decodable { var name: String; var local: [LocalIn]; var expect: [String] }
private struct SameCase: Decodable { var name: String; var a: JSONValue; var b: JSONValue; var expect: Bool }
private struct VideoCase: Decodable { var name: String; var expect: Bool }
private struct Vectors: Decodable {
    var plan: [PlanCase]; var accountSwitch: [AccountCase]; var lostProject: [LostCase]; var sameContent: [SameCase]; var isVideo: [VideoCase]
}

private func vectors() throws -> Vectors {
    try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: Fixtures.sharedDir.appendingPathComponent("sync-test-vectors.json")))
}

@Suite struct SyncPlannerTests {
    @Test func planVectors() throws {
        let v = try vectors()
        #expect(v.plan.count >= 12)
        for c in v.plan {
            let got = SyncPlanner.plan(local: c.local.map(\.value), changed: c.changed.map(\.value), ids: c.ids.map(\.value), deleted: Set(c.deleted),
                                       me: c.me, canPost: c.canPost, canManage: c.canManage)
            #expect(got.mapValues(\.rawValue) == c.expect, "\(c.name)")
        }
    }

    @Test func accountSwitchVectors() throws {
        for c in try vectors().accountSwitch {
            #expect(Set(SyncPlanner.staleAfterAccountSwitch(local: c.local.map(\.value), me: c.me)) == Set(c.expect), "\(c.name)")
        }
    }

    @Test func lostProjectVectors() throws {
        for c in try vectors().lostProject { #expect(Set(SyncPlanner.lostWithProject(local: c.local.map(\.value))) == Set(c.expect), "\(c.name)") }
    }

    @Test func sameContentVectors() throws {
        for c in try vectors().sameContent { #expect(SyncPlanner.sameContent(c.a, c.b) == c.expect, "\(c.name)") }
    }

    @Test func videoVectors() throws {
        for c in try vectors().isVideo { #expect(SyncPlanner.isVideo(c.name) == c.expect, "\(c.name)") }
    }

    @Test func syncKeysRoundTripAndOldRecordsStillRead() throws {
        var r = Record(templateId: "T", templateVersion: "1")
        r.createdBy = "u1"; r.createdByName = "An"; r.serverRev = 4; r.syncConflict = true
        let back = try JSONCoding.makeDecoder().decode(Record.self, from: JSONCoding.makeEncoder().encode(r))
        #expect(back.createdBy == "u1" && back.createdByName == "An" && back.serverRev == 4 && back.syncConflict)
        let old = try JSONCoding.makeDecoder().decode(Record.self, from: Data(#"{"id":"x","templateId":"T"}"#.utf8))
        #expect(old.createdBy == nil && old.serverRev == nil && !old.syncConflict)
        let plain = try JSONCoding.makeEncoder().encode(Record(templateId: "T", templateVersion: "1"))
        #expect(!String(decoding: plain, as: UTF8.self).contains("serverRev") && !String(decoding: plain, as: UTF8.self).contains("syncConflict"))
    }

    @Test func dirtyMeansNeverUploadedOrEditedSince() {
        var r = Record(templateId: "T", templateVersion: "1")
        #expect(r.isSyncDirty)
        r.erpUploadedAt = r.updatedAt
        #expect(!r.isSyncDirty)
        r.updatedAt = r.updatedAt.addingTimeInterval(5)
        #expect(r.isSyncDirty)
    }

    @Test func projectKeepsPermissionsAndCursorAndReadsOldFiles() throws {
        var p = Project(id: "p", code: "P", name: "P")
        #expect(p.canPost && !p.canManage && p.recordsSyncedAt == nil)
        p.canPost = false; p.canManage = true; p.recordsSyncedAt = "2026-10-27T10:00:00.123456Z"
        let back = try JSONCoding.makeDecoder().decode(Project.self, from: JSONCoding.makeEncoder().encode(p))
        #expect(!back.canPost && back.canManage && back.recordsSyncedAt == p.recordsSyncedAt)
        let old = try JSONCoding.makeDecoder().decode(Project.self, from: Data(#"{"id":"p","code":"P"}"#.utf8))
        #expect(old.canPost && !old.canManage)
    }

    @Test func pendingDeletesPersistWithoutDuplicates() throws {
        let root = Fixtures.tempDir()
        let d = PendingDeletes(root: root)
        #expect(d.load().isEmpty)
        let a = PendingDelete(projectId: "p", recordId: "a")
        try d.add(a); try d.add(a); try d.add(PendingDelete(projectId: "p", recordId: "b"))
        #expect(d.load().map(\.recordId) == ["a", "b"])
        try d.remove(a)
        #expect(d.load().map(\.recordId) == ["b"])
    }
}
