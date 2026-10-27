import Testing
import Foundation
@testable import PollMasterCore

@Suite struct ERPUploadTests {
    private func fieldMap(_ t: Template, _ r: Record) -> [String: String] {
        Dictionary(ERPUpload.fields(t, r).map { ($0.name, $0.value) }, uniquingKeysWith: { a, _ in a })
    }

    @Test func fieldsForDraftOmitEmptyOptionalKeys() throws {
        let t = try Fixtures.template()
        var r = RecordFactory.make(template: t)
        r.values["HD-code"] = FieldValue(v: " HD 01/A ")
        let f = fieldMap(t, r)
        #expect(f["record_id"] == r.id)
        #expect(f["record_code"] == "HD 01/A")      // raw, not the file-name form
        #expect(f["template_id"] == t.id)
        #expect(f["template_version"] == t.version)
        #expect(f["template_title"] == t.title)
        #expect(f["status"] == "draft")
        #expect(f["recorded_at"] == ISODate.string(r.createdAt))
        #expect(f["completed_at"] == nil)
        #expect(f["title"] == RecordNaming.title(t, r))
        #expect(ERPUpload.fields(t, r).first?.name == "record_id")
    }

    @Test func fieldsForCompletedCarryCompletedAtAndNoCodeWhenEmpty() throws {
        let t = try Fixtures.template()
        var r = RecordFactory.make(template: t)
        r.values["HD-code"] = nil
        RecordFactory.complete(&r, template: t)
        let f = fieldMap(t, r)
        #expect(f["status"] == "completed")
        #expect(f["completed_at"] == ISODate.string(try #require(r.completedAt)))
        #expect(f["record_code"] == nil)
    }

    @Test func changedSinceUploadRule() {
        var r = Record(templateId: "T", templateVersion: "1")
        #expect(!r.hasUnsentErpChanges)             // never sent
        r.erpUploadedAt = r.updatedAt
        #expect(!r.hasUnsentErpChanges)
        r.updatedAt = r.updatedAt.addingTimeInterval(5)
        #expect(r.hasUnsentErpChanges)
    }

    @Test func uploadedAtRoundTripsAndIsOptional() throws {
        var r = Record(templateId: "T", templateVersion: "1")
        #expect(try JSONCoding.makeDecoder().decode(Record.self, from: Data(#"{"id":"x"}"#.utf8)).erpUploadedAt == nil)
        let json = try #require(JSONSerialization.jsonObject(with: try JSONCoding.makeEncoder().encode(r)) as? [String: Any])
        #expect(json["erpUploadedAt"] == nil)
        r.erpUploadedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let back = try JSONCoding.makeDecoder().decode(Record.self, from: try JSONCoding.makeEncoder().encode(r))
        #expect(back.erpUploadedAt == r.erpUploadedAt)
    }

    @Test func stampSkippedWhenMovedOrDeleted() {
        let r = Record(templateId: "T", templateVersion: "1", projectId: "p1")
        let at = Date(timeIntervalSince1970: 1_700_000_000)
        #expect(ERPUpload.stamped(snapshot: r, current: r, at: at)?.erpUploadedAt == at)
        var moved = r; moved.projectId = "p2"
        #expect(ERPUpload.stamped(snapshot: r, current: moved, at: at) == nil)
        #expect(ERPUpload.stamped(snapshot: r, current: nil, at: at) == nil)
        var edited = r; edited.updatedAt = at.addingTimeInterval(9)       // edited during the upload: stamped, shows as unsent
        #expect(ERPUpload.stamped(snapshot: r, current: edited, at: at)?.hasUnsentErpChanges == true)
    }

    @Test func markerTextUsesDashes() throws {
        let tz = try #require(TimeZone(secondsFromGMT: 7 * 3600))
        let at = Date(timeIntervalSince1970: 1_767_409_200)      // 2026-01-03 03:00 UTC
        #expect(ERPUpload.marker(uploadedAt: nil, hasUnsentChanges: false) == nil)
        #expect(ERPUpload.marker(uploadedAt: at, hasUnsentChanges: true) == "Có thay đổi chưa gửi")
        #expect(ERPUpload.marker(uploadedAt: at, hasUnsentChanges: false, timeZone: tz) == "Đã gửi ERP 03-01-2026 10:00")
    }

    @Test func multipartBodyHasFieldsAndStreamedFiles() throws {
        let dir = Fixtures.tempDir()
        let pdf = dir.appendingPathComponent("a.pdf"), zip = dir.appendingPathComponent("a.zip"), out = dir.appendingPathComponent("body")
        try Data("PDFDATA".utf8).write(to: pdf)
        try Data(repeating: 7, count: 3 << 20).write(to: zip)          // larger than one copy chunk
        try ERPUpload.writeBody(fields: [("record_id", "r1"), ("title", "Trạm 7")], files: [("pdf", pdf, "application/pdf"), ("zip", zip, "application/zip")],
                                boundary: "BB", to: out)
        let body = try Data(contentsOf: out)
        let head = String(decoding: body.prefix(400), as: UTF8.self)
        #expect(head.hasPrefix("--BB\r\nContent-Disposition: form-data; name=\"record_id\"\r\n\r\nr1\r\n"))
        #expect(head.contains("name=\"title\"\r\n\r\nTrạm 7\r\n"))
        #expect(head.contains("name=\"pdf\"; filename=\"a.pdf\"\r\nContent-Type: application/pdf\r\n\r\nPDFDATA\r\n"))
        #expect(String(decoding: body.suffix(10), as: UTF8.self) == "\r\n--BB--\r\n")
        #expect(body.count > 3 << 20)
    }
}
