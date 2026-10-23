import Testing
import Foundation
@testable import PollMasterCore

/// Schema §7: field-work extensions.
@Suite struct FieldWorkTests {
    let t: Template
    init() throws { t = try Fixtures.template() }

    private func record() -> Record { RecordFactory.make(template: t) }
    private var e1: TableDef { t.table("E1")! }
    private var f1: TableDef { t.table("F1")! }

    // MARK: template

    @Test func templateParsesExtensions() throws {
        #expect(t.version == "1.1")
        #expect(t.field("A-01")?.lookup == Lookup(source: "sites", fill: ["A-03": "address"], gps: "A-04"))
        #expect(t.field("A-04")?.maxAccuracy == 15)
        #expect(t.allFields.contains { $0.photoRef == "P08" })
        #expect(e1.pairedWith == "F1" && e1.quickEntry && t.pairedTable(of: f1)?.id == "E1")
        #expect(e1.column("kb")?.carryOver == true)
        #expect(t.table("H1")?.column("audio")?.type == .audio)
        #expect(t.photoRefUsers(of: "P08").count >= 2)
        // survives an encode/decode round trip
        let back = try JSONCoding.makeDecoder().decode(Template.self, from: JSONCoding.makeEncoder().encode(t))
        #expect(back == t)
    }

    // MARK: warnings

    @Test func rangeWarningIsNotAnError() throws {
        let f = try #require(t.allFields.first { $0.warnMin != nil && $0.warnMax != nil && $0.type == .number && $0.samples == nil && $0.parts == nil })
        var r = record()
        r.values[f.id] = FieldValue(v: .number(f.warnMax! + 1))
        let w = Warnings.messages(for: f, r.values[f.id])
        #expect(w.count == 1 && w[0].hasPrefix("Ngoài khoảng thường gặp \(ValueFormatter.number(f.warnMin!))–\(ValueFormatter.number(f.warnMax!))"))
        r.values[f.id] = FieldValue(v: .number((f.warnMin! + f.warnMax!) / 2))
        #expect(Warnings.messages(for: f, r.values[f.id]).isEmpty)
        r.values[f.id] = FieldValue(v: .number(f.warnMax! + 1))
        #expect(Validator.validate(t, r).allSatisfy { $0.fieldId != f.id })
        #expect(Warnings.all(t, r).contains { $0.fieldId == f.id && $0.kind == .warning })
    }

    @Test func warningMessageHasUnitAndCoversSamplesAndParts() {
        var f = Field(id: "X", label: "x", type: .number)
        f.unit = "mm"; f.warnMin = 1000; f.warnMax = 2500; f.samples = 3
        #expect(Warnings.messages(for: f, FieldValue(v: [1500, 3000, nil])) == ["Ngoài khoảng thường gặp 1000–2500 mm"])
        #expect(Warnings.messages(for: f, FieldValue(v: [1500, nil, nil])).isEmpty)
        var c = Field(id: "C", label: "c", type: .composite)
        c.parts = [Part(id: "w", label: "Rộng", type: .number, unit: "cm", warnMin: 5, warnMax: 20)]
        #expect(Warnings.messages(for: c, FieldValue(parts: ["w": 50])) == ["Ngoài khoảng thường gặp 5–20 cm"])
        #expect(Warnings.messages(for: c, FieldValue(flag: .notMeasurable, reason: "x", parts: ["w": 50])).isEmpty)
    }

    @Test func gpsAccuracyAndPlannedDistance() throws {
        var r = record()
        let gps = try #require(t.field("A-04"))
        r.values["A-04"] = FieldValue(v: GPSValue(lat: 10.5, lng: 106.25, accuracy: 40).json)
        #expect(Warnings.messages(for: gps, r.values["A-04"]) == ["Độ chính xác GPS kém (±40 m)"])
        r.values["A-04"] = FieldValue(v: GPSValue(lat: 10.5, lng: 106.25, accuracy: 15).json)
        #expect(Warnings.messages(for: gps, r.values["A-04"]).isEmpty)
        r.values["A-04"] = FieldValue(v: GPSValue(lat: 10.5, lng: 106.25, accuracy: nil).json)
        #expect(Warnings.messages(for: gps, r.values["A-04"]).isEmpty)

        let sites = [Site(code: "S1", address: "a", lat: 10.5, lng: 106.25)]
        r.values["A-01"] = FieldValue(v: "S1")
        #expect(Warnings.all(t, r, sites: sites).allSatisfy { $0.fieldId != "A-04" })
        r.values["A-04"] = FieldValue(v: GPSValue(lat: 10.502, lng: 106.25, accuracy: 5).json)   // ~222 m
        let near = Warnings.all(t, r, sites: sites).filter { $0.fieldId == "A-04" }
        #expect(near.count == 1 && near[0].message.hasPrefix("Cách điểm kế hoạch 22"))
        #expect(Geo.distance(lat1: 10, lng1: 106, lat2: 10, lng2: 106.0009) < 100)
    }

    // MARK: sites

    @Test func sitesCSVParsing() throws {
        let csv = "\u{FEFF}Code,Address,lat,lng,extra\r\nS1,\"12 Lê Lợi, Q1\",10.5,106.25,x\r\nS2,Hà Nội,,,\r\n\r\nS1,dup,1,1\r\n,noCode,1,1\r\nS3,\"Có \"\"ngoặc\"\"\",abc,106\r\n"
        let sites = try SitesCSV.parse(Data(csv.utf8))
        #expect(sites.map(\.code) == ["S1", "S2", "S3"])
        #expect(sites[0] == Site(code: "S1", address: "12 Lê Lợi, Q1", lat: 10.5, lng: 106.25))
        #expect(sites[1].lat == nil && sites[1].address == "Hà Nội")
        #expect(sites[2].address == "Có \"ngoặc\"" && sites[2].lat == nil && sites[2].lng == 106)
        #expect(throws: SitesCSVError.missingCodeColumn) { try SitesCSV.parse(Data("a,b\n1,2".utf8)) }
        #expect(throws: SitesCSVError.empty) { try SitesCSV.parse(Data("code,address\n".utf8)) }
        #expect(throws: SitesCSVError.notText) { try SitesCSV.parse(Data([0xFF, 0xFE, 0x00])) }
    }

    @Test func siteStoreRoundTripAndSearch() throws {
        let store = SiteStore(root: Fixtures.tempDir())
        #expect(store.load().isEmpty)
        let sites = [Site(code: "TDP-01", address: "Đường Nguyễn Huệ", lat: 10.7, lng: 106.7), Site(code: "TDP-02")]
        try store.save(sites)
        #expect(store.load() == sites)
        #expect(sites[0].matches("nguyen hue") && sites[0].matches("tdp-01") && !sites[1].matches("hue"))
        try store.clear()
        #expect(store.load().isEmpty)
    }

    @Test func lookupFillsOnlyEmptyTargetsUnlessOverwrite() throws {
        var r = record()
        let lk = try #require(t.field("A-01")?.lookup)
        let site = Site(code: "S1", address: "12 Lê Lợi", lat: 10.5, lng: 106.25)
        let fill = SiteLookup.fillValues(lk, site: site)
        #expect(Set(fill.keys) == ["A-03"])
        r.values["A-03"] = FieldValue(v: "đã nhập")
        let occupied = SiteLookup.apply(fill, to: &r, overwrite: false)
        #expect(occupied == ["A-03"])
        #expect(r.values["A-03"]?.v == "đã nhập")
        #expect(r.values["A-04"] == nil)  // planned coordinates never count as a GPS reading
        SiteLookup.apply(fill, to: &r, overwrite: true)
        #expect(r.values["A-03"]?.v == "12 Lê Lợi")
        // a site without coordinates leaves the GPS field alone
        #expect(SiteLookup.fillValues(lk, site: Site(code: "S2", address: "x"))["A-04"] == nil)
    }

    // MARK: paired tables, carryOver

    @Test func addRowCreatesTwinWithSameIdAndCarriesOver() throws {
        var r = record()
        let id1 = TableOps.addRow(&r, t, table: e1)
        #expect(r.tables["E1"]?.map(\.id) == [id1] && r.tables["F1"]?.map(\.id) == [id1])
        r.tables["E1"]![0].values["kb"] = FieldValue(v: "S04")
        r.tables["E1"]![0].values["attempts"] = FieldValue(v: 2)
        r.tables["F1"]![0].values["mode"] = FieldValue(v: "O")
        r.tables["F1"]![0].values["batteries"] = FieldValue(v: 1)
        let id2 = TableOps.addRow(&r, t, table: f1)           // added from the other side
        #expect(r.tables["E1"]?.map(\.id) == [id1, id2] && r.tables["F1"]?.map(\.id) == [id1, id2])
        #expect(r.tables["E1"]![1].values["kb"]?.v == "S04")          // carryOver column
        #expect(r.tables["E1"]![1].values["attempts"] == nil)         // not carried
        #expect(r.tables["F1"]![1].values["mode"]?.v == "O")
        #expect(r.tables["F1"]![1].values["batteries"] == nil)
    }

    @Test func duplicateAndDeleteKeepPairedIdsInSync() throws {
        var r = record()
        let a = TableOps.addRow(&r, t, table: e1)
        r.tables["E1"]![0].values["kb"] = FieldValue(v: "S01")
        r.tables["E1"]![0].values["video"] = FieldValue(v: [.object(["file": "media/v.mov"])])
        r.tables["F1"]![0].values["T1"] = FieldValue(v: 12)
        let b = TableOps.addRow(&r, t, table: e1)
        let copy = try #require(TableOps.duplicateRow(&r, t, table: f1, rowId: a))
        #expect(r.tables["E1"]?.map(\.id) == [a, copy, b] && r.tables["F1"]?.map(\.id) == [a, copy, b])
        #expect(r.tables["E1"]![1].values["kb"]?.v == "S01" && r.tables["E1"]![1].values["video"] == nil)
        #expect(r.tables["F1"]![1].values["T1"]?.v == 12)
        let removed = TableOps.deleteRow(&r, t, table: e1, rowId: copy)
        #expect(removed.count == 2)
        #expect(r.tables["E1"]?.map(\.id) == [a, b] && r.tables["F1"]?.map(\.id) == [a, b])
        #expect(TableOps.duplicateRow(&r, t, table: e1, rowId: "nope") == nil)
    }

    @Test func ensureRowRepairsMissingTwin() {
        var r = record()
        r.tables["E1"] = [Row(id: "x")]
        TableOps.ensureRow(&r, t, table: e1, rowId: "x")
        #expect(r.tables["E1"]?.count == 1 && r.tables["F1"]?.map(\.id) == ["x"])
        TableOps.ensureRow(&r, t, table: t.table("E2")!, rowId: "zz")        // fixed table: untouched
        #expect(r.tables["E2"]?.contains { $0.id == "zz" } != true)
    }

    // MARK: review & returned

    @Test func returnForFixTransitions() {
        var r = record()
        #expect(!RecordFactory.returnForFix(&r))                // draft cannot be returned
        RecordFactory.complete(&r, template: t)
        #expect(r.status == .completed && !r.returned)
        #expect(RecordFactory.returnForFix(&r))
        #expect(r.status == .draft && r.returned && r.completedAt == nil)
        RecordFactory.reopen(&r)
        #expect(r.returned)                                      // still marked until completed again
        RecordFactory.complete(&r, template: t)
        #expect(!r.returned)
    }

    @Test func reviewNotesStoredListedExportedAndCleared() throws {
        var r = Fixtures.filledRecord(t)
        r.values["HD-code"] = FieldValue(v: "001")
        RecordFactory.setReview(&r, fieldId: "B-02", text: "Đo lại, sai số lớn")
        #expect(r.values["B-02"]?.review == "Đo lại, sai số lớn" && r.values["B-02"]?.v != nil)
        let notes = Warnings.reviewNotes(t, r)
        #expect(notes.map(\.fieldId) == ["B-02"] && notes[0].kind == .review)
        #expect(Validator.validate(t, r).allSatisfy { $0.fieldId != "B-02" || $0.kind == .required })
        // json round trip keeps it
        let back = try JSONCoding.makeDecoder().decode(Record.self, from: JSONCoding.makeEncoder().encode(r))
        #expect(back.values["B-02"]?.review == "Đo lại, sai số lớn")
        // export.json, xlsx/csv column
        let o = try #require(JSONSerialization.jsonObject(with: ExportJSON.build(t, r)) as? [String: Any])
        #expect(((o["fields"] as? [String: Any])?["B-02"] as? [String: Any])?["review"] as? String == "Đo lại, sai số lớn")
        let model = ExportModelBuilder.build(t, r)
        #expect(model.entries.first { $0.id == "B-02" }?.review == "Đo lại, sai số lớn")
        let csv = String(decoding: CSVExport.info(model).dropFirst(3), as: UTF8.self)
        #expect(csv.contains("Đo lại, sai số lớn") || csv.contains("\"Đo lại, sai số lớn\""))
        let sheet = try #require(XLSXExport.build(model).first { $0.path == "xl/worksheets/sheet1.xml" })
        #expect(String(decoding: sheet.data, as: UTF8.self).contains("Đo lại, sai số lớn"))
        RecordFactory.setReview(&r, fieldId: "B-02", text: "  ")
        #expect(r.values["B-02"]?.review == nil && Warnings.reviewNotes(t, r).isEmpty)
        RecordFactory.setReview(&r, fieldId: "B-99", text: "x")
        RecordFactory.setReview(&r, fieldId: "B-99", text: nil)
        #expect(r.values["B-99"] == nil)
    }

    // MARK: resume / backup

    @Test func lastPositionBackupAtAndNeedsBackupRoundTrip() throws {
        var r = record()
        #expect(r.needsBackup)                                   // never backed up
        r.lastPosition = LastPosition(section: "B", field: "B-03")
        r.backupAt = Date(timeIntervalSince1970: 2_000_000_000)
        r.updatedAt = Date(timeIntervalSince1970: 1_999_999_000)
        #expect(!r.needsBackup)
        r.updatedAt = Date(timeIntervalSince1970: 2_000_000_100)
        #expect(r.needsBackup)
        let data = try JSONCoding.makeEncoder().encode(r)
        let json = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect((json["lastPosition"] as? [String: String]) == ["section": "B", "field": "B-03"])
        #expect(json["backupAt"] is String && json["returned"] == nil)
        let back = try JSONCoding.makeDecoder().decode(Record.self, from: data)
        #expect(back.lastPosition == r.lastPosition && back.backupAt != nil && !back.returned)
        // tolerant of garbage
        let bad = try JSONCoding.makeDecoder().decode(Record.self, from: Data(#"{"id":"1","lastPosition":5,"backupAt":"zz","returned":"x"}"#.utf8))
        #expect(bad.lastPosition == nil && bad.backupAt == nil && !bad.returned)
    }

    @Test func backupZipIsReplacedPerRecord() throws {
        let store = RecordStore(root: Fixtures.tempDir())
        var r = Fixtures.filledRecord(t)
        r.values["HD-code"] = FieldValue(v: "001/A")
        try store.save(r)
        let dir = Fixtures.tempDir()
        let ex = RecordExporter(store: store)
        let url = try ex.writeBackup(t, r, into: dir)
        #expect(url.lastPathComponent == "001-A_\(r.id.prefix(8))_backup.zip")
        r.values["HD-surveyor"] = FieldValue(v: "Bình")
        let again = try ex.writeBackup(t, r, into: dir)
        #expect(again == url)
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path)
        #expect(files == [url.lastPathComponent])               // no temp leftovers, old copy replaced
        let names = try ZipBuilder.entryNames(url)
        #expect(names.contains("record.json") && names.contains("export.json"))
        let rec = try JSONCoding.makeDecoder().decode(Record.self, from: ZipBuilder.read(url, path: "record.json"))
        #expect(rec.values["HD-surveyor"]?.v == "Bình")
        r.values["HD-code"] = nil
        #expect(RecordExporter.backupFileName(t, r) == "\(r.id)_backup.zip")
    }

    // MARK: section operations

    @Test func markUnmeasurableOnlyEmptyFlaggableFields() throws {
        var r = record()
        let sec = try #require(t.sections.first { $0.id == "B" })
        r.values["B-02"] = FieldValue(v: 1500)
        let before = SectionOps.emptyFlaggableFields(t, r, sectionId: "B")
        #expect(!before.isEmpty && !before.contains { $0.id == "B-02" || $0.isComputed || !$0.flagsEnabled })
        #expect(SectionOps.markUnmeasurable(t, &r, sectionId: "B", reason: " ") == 0)
        let n = SectionOps.markUnmeasurable(t, &r, sectionId: "B", reason: "Khóa cửa")
        #expect(n == before.count)
        #expect(r.values["B-02"]?.flag == nil && r.values["B-02"]?.v == 1500)
        let f = try #require(before.first)
        #expect(r.values[f.id]?.flag == .notMeasurable && r.values[f.id]?.reason == "Khóa cửa")
        #expect(SectionOps.emptyFlaggableFields(t, r, sectionId: "B").isEmpty)
        // other sections untouched
        #expect(r.values.keys.allSatisfy { id in id == "B-02" || sec.fields.contains { $0.id == id } || r.values[id]?.flag != .notMeasurable })
    }

    @Test func copySectionFromOtherRecord() throws {
        var src = record()
        src.values["B-02"] = FieldValue(v: 1700, flag: .estimate, note: "ghi chú")
        var photo = FieldValue(); photo.mediaInV = [Media(file: "media/a.jpg")]
        src.values["P05"] = photo
        _ = TableOps.addRow(&src, t, table: e1)
        src.tables["E1"]![0].values["kb"] = FieldValue(v: "S03")
        src.tables["F1"]![0].values["T1"] = FieldValue(v: 5)
        var dst = record()
        dst.values["B-03"] = FieldValue(v: 900)
        #expect(!SectionOps.hasData(t, dst, sectionId: "E"))
        #expect(SectionOps.hasData(t, dst, sectionId: "B"))
        #expect(SectionOps.copySection(t, from: src, into: &dst, sectionId: "B") == 1)
        #expect(dst.values["B-02"]?.v == 1700 && dst.values["B-02"]?.flag == .estimate)
        #expect(dst.values["B-03"]?.v == 900)                     // source blank: left alone
        #expect(SectionOps.copySection(t, from: src, into: &dst, sectionId: "I") == 0)
        #expect(dst.values["P05"] == nil)                         // no media
        #expect(SectionOps.copySection(t, from: src, into: &dst, sectionId: "E") >= 1)
        #expect(dst.tables["E1"]?.first?.values["kb"]?.v == "S03")
        #expect(dst.tables["F1"]?.first?.id == dst.tables["E1"]?.first?.id)     // partner copied together
        #expect(dst.tables["F1"]?.first?.values["T1"]?.v == 5)
        let others = SectionOps.candidates(for: dst, among: [src, dst, Record(templateId: "x", templateVersion: "1")])
        #expect(others.map(\.id) == [src.id])
    }

    // MARK: audio, progress, list info, versions

    @Test func audioDisplayedAndExportedAsFileNames() throws {
        var r = record()
        var fv = FieldValue(); fv.mediaInV = [Media(file: "media/001_H1-audio_01.m4a"), Media(file: "media/001_H1-audio_02.m4a")]
        r.tables["H1"] = [Row(id: "h", values: ["audio": fv])]
        let col = try #require(t.table("H1")?.column("audio"))
        #expect(ValueFormatter.display(col, fv) == "001_H1-audio_01.m4a, 001_H1-audio_02.m4a")
        #expect(col.isMedia)
        #expect(RecordStore.mediaFiles(of: r).contains("media/001_H1-audio_02.m4a"))
        let o = try #require(JSONSerialization.jsonObject(with: ExportJSON.build(t, r)) as? [String: Any])
        let rows = try #require((o["tables"] as? [String: [[String: Any]]])?["H1"])
        #expect(rows.first?["audio"] as? [String] == ["001_H1-audio_01.m4a", "001_H1-audio_02.m4a"])
        #expect(ExportModelBuilder.build(t, r).sections.allSatisfy { $0.photos.isEmpty })   // not an image
    }

    @Test func sectionProgressMatchesTotals() throws {
        var r = record()
        let per = Validator.sectionProgress(t, r)
        #expect(per["header"] != nil && t.sections.allSatisfy { per[$0.id] != nil })
        let total = Validator.progress(t, r)
        #expect(per.values.map(\.total).reduce(0, +) == total.total && per.values.map(\.done).reduce(0, +) == total.done)
        let filled = Fixtures.filledRecord(t)
        let all = Validator.sectionProgress(t, filled)
        #expect(all.values.allSatisfy { $0.done == $0.total } || Validator.validate(t, filled).count > 0)
        r.values["HD-code"] = FieldValue(v: "1")
        #expect(Validator.sectionProgress(t, r)["header"]!.done == per["header"]!.done + 1)
    }

    @Test func listInfoFirstPhotoAndSubtitle() throws {
        var r = record()
        #expect(RecordInfo.subtitle(t, r) == "BB-TDP-01")
        r.values["HD-code"] = FieldValue(v: "001/A")
        #expect(RecordInfo.subtitle(t, r) == "BB-TDP-01 · 001/A")
        #expect(RecordInfo.firstPhoto(t, r) == nil)
        var a = FieldValue(); a.mediaInV = [Media(file: "media/clip.mov"), Media(file: "media/p05.jpg")]
        var b = FieldValue(); b.mediaInV = [Media(file: "media/p01.jpg")]
        r.values["P05"] = a; r.values["P01-04"] = b
        #expect(RecordInfo.firstPhoto(t, r)?.file == "media/p01.jpg")      // first photo field in template order
    }

    @Test func newestTemplateVersionAndCoexistingInstall() throws {
        #expect(TemplateVersion.isNewer("1.10", than: "1.9") && TemplateVersion.isNewer("2", than: "1.9.9") && !TemplateVersion.isNewer("1.0", than: "1.0.0"))
        let store = TemplateStore(root: Fixtures.tempDir())
        var old = try JSONSerialization.jsonObject(with: Fixtures.templateData()) as! [String: Any]
        old["version"] = "1.0"
        try store.install(JSONSerialization.data(withJSONObject: old))
        try store.install(Fixtures.templateData())
        #expect(store.list().map(\.version).sorted() == ["1.0", "1.1"])
        #expect(TemplateVersion.newest(store.list()).map(\.version) == ["1.1"])
        // a record keeps resolving its own version
        #expect(store.load(id: "BB-TDP-01", version: "1.0")?.version == "1.0")
    }
}
