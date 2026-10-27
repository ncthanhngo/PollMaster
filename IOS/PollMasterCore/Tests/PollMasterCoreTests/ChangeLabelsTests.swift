import Testing
import Foundation
@testable import PollMasterCore

private struct LabelCase: Decodable {
    var name: String; var template: String?; var tableRows: [String: [String]]; var path: String; var serverLabel: String; var expect: String
}
private struct ValueCase: Decodable { var name: String; var template: String?; var path: String; var value: JSONValue; var expect: String }
private struct EditCase: Decodable { var name: String; var createdBy: String?; var me: String?; var canPost: Bool; var canManage: Bool; var expect: Bool }
private struct HiddenCase: Decodable { var name: String; var createdBy: String?; var me: String?; var dirty: Bool; var canManage: Bool; var expect: Bool }
private struct StateCase: Decodable { var name: String; var dirty: Bool; var conflict: Bool; var failure: String?; var syncedAt: String?; var expect: String; var text: String }
private struct Vectors: Decodable {
    var labels: [LabelCase]; var values: [ValueCase]; var canEdit: [EditCase]; var hidden: [HiddenCase]; var syncState: [StateCase]
}

private func vectors() throws -> Vectors {
    try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: Fixtures.sharedDir.appendingPathComponent("sync-test-vectors.json")))
}

private func template(_ file: String?) throws -> Template? {
    try file.map { try TemplateLoader.decode(Data(contentsOf: Fixtures.sharedDir.appendingPathComponent("templates/\($0)"))) }
}

@Suite struct ChangeLabelsTests {
    @Test func labelVectors() throws {
        let v = try vectors()
        #expect(v.labels.count >= 20)
        for c in v.labels {
            let t = try template(c.template)
            var r = t.map { RecordFactory.make(template: $0) }
            for (tid, ids) in c.tableRows { r?.tables[tid] = ids.map { Row(id: $0) } }
            #expect(ChangeLabels.label(path: c.path, serverLabel: c.serverLabel, template: t, record: r) == c.expect, "\(c.name)")
        }
    }

    @Test func valueVectors() throws {
        let v = try vectors()
        #expect(v.values.count >= 20)
        for c in v.values {
            #expect(ChangeLabels.valueText(path: c.path, c.value, template: try template(c.template)) == c.expect, "\(c.name)")
        }
    }

    @Test func canEditVectors() throws {
        for c in try vectors().canEdit {
            #expect(RecordAccess.canEdit(createdBy: c.createdBy, me: c.me, canPost: c.canPost, canManage: c.canManage) == c.expect, "\(c.name)")
        }
    }

    @Test func hiddenVectors() throws {
        for c in try vectors().hidden {
            #expect(RecordAccess.isHiddenLeftover(createdBy: c.createdBy, me: c.me, dirty: c.dirty, canManage: c.canManage) == c.expect, "\(c.name)")
        }
    }

    @Test func syncStateVectors() throws {
        let utc7 = TimeZone(secondsFromGMT: 7 * 3600)!
        for c in try vectors().syncState {
            let s = RecordSyncState.of(dirty: c.dirty, conflict: c.conflict, failure: c.failure, syncedAt: c.syncedAt.flatMap(ISODate.parse))
            #expect(s.kind == c.expect && s.text(timeZone: utc7) == c.text, "\(c.name)")
        }
    }

    @Test func recordWithoutCreatorIsEditableByItsDeviceUser() {
        let t = try! Fixtures.template()
        let r = RecordFactory.make(template: t, projectId: "p")
        #expect(RecordAccess.canEdit(r, me: "u1", project: Project(id: "p", code: "P", name: "P")))
        var other = r; other.createdBy = "u2"
        #expect(!RecordAccess.canEdit(other, me: "u1", project: Project(id: "p", code: "P", name: "P")))
    }
}

@Suite struct ConflictCopyNameTests {
    @Test func nameKeepsTheBackupPrefixAndAddsTheTime() throws {
        let t = try Fixtures.template()
        let r = RecordFactory.make(template: t, projectId: "p")
        let at = ISODate.parse("2026-10-27T09:05:07Z")!
        let name = RecordExporter.conflictCopyFileName(t, r, projectCode: "P1", at: at)
        let stem = String(RecordExporter.backupFileName(t, r, projectCode: "P1").dropLast("_backup.zip".count))
        #expect(name.hasPrefix(stem + "_ban-may_") && name.hasSuffix(".zip") && name.count == stem.count + "_ban-may_".count + 15 + 4)
    }
}
