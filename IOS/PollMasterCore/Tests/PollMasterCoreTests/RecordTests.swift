import Testing
import Foundation
@testable import PollMasterCore

@Suite struct RecordTests {
    @Test func factoryAppliesDefaults() throws {
        let t = try Fixtures.template()
        let r = RecordFactory.make(template: t, surveyorName: "An")
        #expect(r.values["HD-plan"]?.v == "KS-TDP-01")
        #expect(r.values["HD-date"]?.v?.string?.count == 10)
        #expect(SessionTime(r.values["A-02"]?.v) != nil)
        #expect(r.tables["E2"]?.count == 15 || r.tables["E2"]?.isEmpty == false)
        #expect(r.tables["E1"]?.isEmpty == true)
        #expect(r.status == .draft)
    }

    @Test func roundTripAndEmptyKeysDropped() throws {
        let t = try Fixtures.template()
        var r = Fixtures.filledRecord(t)
        r.values["A-05"] = FieldValue(v: "other", other: ["other": "Chợ"])
        r.values["B-02"] = FieldValue(v: 1500, flag: .estimate, note: "ghi chú")
        r.values["A-01"] = FieldValue(v: "")
        var m = FieldValue(); m.mediaInV = [Media(file: "media/x.jpg", lat: 10.5, lng: 106.5)]
        r.values["P05"] = m
        r.tables["E1"] = [Row(values: ["mode": FieldValue(v: "O")], stopwatchStart: Date(timeIntervalSince1970: 1_700_000_000))]
        r.signatures["surveyor"] = SignatureEntry(file: "media/sig_surveyor.png", name: "Nguyễn Văn A")
        let data = try JSONCoding.makeEncoder().encode(r)
        let json = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(json["schemaVersion"] as? Int == 1)
        #expect(json["status"] as? String == "draft")
        let values = try #require(json["values"] as? [String: Any])
        let b02 = try #require(values["B-02"] as? [String: Any])
        #expect(Set(b02.keys) == ["v", "flag", "note"])
        let a01 = values["A-01"] as? [String: Any]
        #expect(a01?.isEmpty ?? true)
        let back = try JSONCoding.makeDecoder().decode(Record.self, from: data)
        var expected = r
        expected.values["A-01"]?.v = nil
        #expect(back.values["B-02"] == expected.values["B-02"])
        #expect(back.values["P05"]?.mediaInV == m.mediaInV)
        #expect(back.tables == r.tables)
        #expect(back.signatures["surveyor"]?.name == "Nguyễn Văn A")
        #expect(abs(back.createdAt.timeIntervalSince(r.createdAt)) < 1)
        #expect(String(decoding: data, as: UTF8.self).contains("\"takenAt\" : \""))
    }

    @Test func storeSaveLoadDeleteDuplicate() throws {
        let t = try Fixtures.template()
        let store = RecordStore(root: Fixtures.tempDir())
        var r = Fixtures.filledRecord(t)
        var m = FieldValue(); m.mediaInV = [Media(file: "media/x.jpg")]
        r.values["P05"] = m
        try store.save(r)
        #expect(store.load(r.id)?.values["HD-code"] == r.values["HD-code"])
        try Data("jpg".utf8).write(to: store.fileURL(r.id, "media/x.jpg"))
        try Data("old".utf8).write(to: store.fileURL(r.id, "media/orphan.jpg"))
        store.pruneMedia(r)
        #expect(FileManager.default.fileExists(atPath: store.fileURL(r.id, "media/x.jpg").path))
        #expect(!FileManager.default.fileExists(atPath: store.fileURL(r.id, "media/orphan.jpg").path))

        r.status = .completed
        let copy = RecordFactory.duplicate(r)
        #expect(copy.id != r.id && copy.status == .draft && copy.completedAt == nil)
        #expect(copy.values["HD-code"] == r.values["HD-code"])
        #expect(copy.values["P05"] == nil)
        #expect(copy.signatures.isEmpty)
        try store.save(copy)
        #expect(store.list().count == 2)
        try store.delete(r.id)
        #expect(store.list().count == 1)
    }

    @Test func titleAndNaming() throws {
        let t = try Fixtures.template()
        var r = RecordFactory.make(template: t)
        #expect(RecordNaming.title(t, r) == "Phiếu mới · " + ISODate.format(r.createdAt, "dd/MM/yyyy HH:mm"))
        r.values["HD-code"] = FieldValue(v: "001/A")
        r.values["A-01"] = FieldValue(v: "K1")
        r.values["A-03"] = FieldValue(v: "12 Lê Lợi")
        #expect(RecordNaming.title(t, r) == "K1 · 12 Lê Lợi")
        #expect(RecordNaming.recordCode(t, r) == "001-A")
        #expect(RecordNaming.mediaName(t, r, fieldId: "P05", seq: 3) == "001-A_P05_03")
        #expect(RecordNaming.exportBaseName(t, r, now: Date(timeIntervalSince1970: 0)).hasPrefix("001-A_19"))
    }

    @Test func mediaFilesCollected() throws {
        let t = try Fixtures.template()
        var r = RecordFactory.make(template: t)
        var m = FieldValue(media: [Media(file: "media/a.jpg")]); m.mediaInV = [Media(file: "media/b.jpg")]
        r.values["P05"] = m
        r.signatures["surveyor"] = SignatureEntry(file: "media/sig_surveyor.png", name: "A")
        #expect(Set(RecordStore.mediaFiles(of: r)) == ["media/a.jpg", "media/b.jpg", "media/sig_surveyor.png"])
    }
}
