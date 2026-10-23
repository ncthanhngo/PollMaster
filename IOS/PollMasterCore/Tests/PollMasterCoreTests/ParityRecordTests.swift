import Testing
import Foundation
@testable import PollMasterCore

/// Cross-platform rules of docs/template-schema.md §6.1, §6.4.
@Suite struct ParityRecordTests {
    private func decode(_ json: String) throws -> Record {
        try JSONCoding.makeDecoder().decode(Record.self, from: Data(json.utf8))
    }

    @Test func androidStyleRecordWithMissingKeysDecodes() throws {
        let r = try decode(#"""
        {"id":"r1","values":{"A":{"v":"x"},"P":{"v":[{"file":"media/a.jpg"},"junk",{"file":"media/b.jpg","takenAt":"2026-10-23T10:00+07:00"}]}},
         "tables":{"E1":[{"id":"row1"},{"id":"row2","values":{"c":{"v":1}}}]},
         "signatures":{"s":{"file":"media/sig_s.png"}}}
        """#)
        #expect(r.status == .draft && r.schemaVersion == 1 && r.templateId == "")
        #expect(r.tables["E1"]?.count == 2 && r.tables["E1"]?[0].values.isEmpty == true)
        #expect(r.signatures["s"]?.name == "")
        #expect(r.value("P").mediaInV.map(\.file) == ["media/a.jpg", "media/b.jpg"])   // junk item must not drop the list
        let minimal = try decode(#"{"id":"r2"}"#)
        #expect(minimal.values.isEmpty && minimal.signatures.isEmpty)
    }

    @Test func brokenRecordIsListedWithError() throws {
        let store = RecordStore(root: Fixtures.tempDir())
        let good = Record(templateId: "T", templateVersion: "1")
        try store.save(good)
        let dir = store.folder("bad1")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("{not json".utf8).write(to: dir.appendingPathComponent("record.json"))
        let items = store.listItems()
        #expect(items.count == 2)
        let bad = try #require(items.first { $0.id == "bad1" })
        #expect(bad.record == nil && bad.error?.isEmpty == false)
        #expect(store.list().count == 1)
        try store.delete("bad1")
        #expect(store.listItems().count == 1)
    }

    @Test func isoDateForms() throws {
        let base = try #require(ISODate.parse("2026-10-23T10:00:00+07:00"))
        #expect(ISODate.parse("2026-10-23T10:00+07:00") == base)
        #expect(ISODate.parse("2026-10-23T03:00:00Z") == base)
        #expect(abs(try #require(ISODate.parse("2026-10-23T10:00:00.250+07:00")).timeIntervalSince(base) - 0.25) < 0.001)
        #expect(ISODate.parse("garbage") == nil)
        let tz = TimeZone(identifier: "Asia/Ho_Chi_Minh")!
        #expect(ISODate.string(base, timeZone: tz) == "2026-10-23T10:00:00+07:00")
        #expect(ISODate.string(base, timeZone: TimeZone(identifier: "UTC")!) == "2026-10-23T03:00:00Z")
    }

    @Test func gpsKeepsUnparsableCapturedAt() throws {
        let g = try #require(GPSValue(.object(["lat": 1, "lng": 2, "capturedAt": "bogus"])))
        #expect(g.capturedAt == nil)
        #expect(g.json.object?["capturedAt"] == .string("bogus"))
    }

    @Test func fixedTablesIterateTemplateRows() throws {
        let t = try Fixtures.template()
        let e2 = try #require(t.table("E2"))
        var r = Record(templateId: t.id, templateVersion: t.version)
        #expect(r.rows(of: e2).map(\.id) == e2.rows!.map(\.id))
        let second = e2.rows![1].id
        r.tables["E2"] = [Row(id: "unknown", values: ["entered": FieldValue(v: true)]),
                          Row(id: second, values: ["entered": FieldValue(v: true)])]
        let rows = r.rows(of: e2)
        #expect(rows.count == e2.rows!.count && !rows.contains { $0.id == "unknown" })
        #expect(rows[1].values["entered"]?.v == true)
        let model = ExportModelBuilder.build(t, r)
        #expect(model.tables.first { $0.id == "E2" }?.rows.count == e2.rows!.count)
        let json = try JSONSerialization.jsonObject(with: ExportJSON.build(t, r)) as! [String: Any]
        let out = (json["tables"] as! [String: Any])["E2"] as! [[String: Any]]
        #expect(out.map { $0["id"] as? String } == e2.rows!.map(\.id))
    }

    @Test func recordCodeUsesRawValueAndCollapsesInvalidRuns() throws {
        let t = try Fixtures.template()
        var r = Record(templateId: t.id, templateVersion: t.version)
        r.values["HD-code"] = FieldValue(v: "  a//b :: c ", flag: .estimate)
        #expect(RecordNaming.recordCode(t, r) == "a-b-c")
        r.values["HD-code"] = FieldValue(flag: .notMeasurable, reason: "x")
        #expect(RecordNaming.recordCode(t, r) == nil)
        #expect(RecordNaming.sanitize("-a  b-") == "a-b")
        #expect(RecordNaming.exportBaseName(t, r, now: Date(timeIntervalSince1970: 0)).hasPrefix(r.id))
    }

    @Test func mediaNamingForTableColumnsAndSeq() throws {
        let t = try Fixtures.template()
        var r = Record(templateId: t.id, templateVersion: t.version)
        r.values["HD-code"] = FieldValue(v: "X1")
        #expect(RecordNaming.mediaFieldId(table: "H1", column: "photo") == "H1-photo")
        #expect(RecordNaming.smallestFreeSeq(taken: [1, 2, 4]) == 3)
        #expect(RecordNaming.smallestFreeSeq(taken: []) == 1)
        #expect(RecordNaming.mediaName(t, r, fieldId: "H1-photo", seq: 3) == "X1_H1-photo_03")
    }
}
