import Testing
import Foundation
@testable import PollMasterCore

/// Cell texts of a SpreadsheetML sheet (inline strings and numbers), row by row; also fails on malformed XML.
private final class SheetReader: NSObject, XMLParserDelegate {
    var rows: [[String]] = []
    private var row: [String] = [], text = "", inCell = false
    func parser(_ p: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        if name == "row" { row = [] } else if name == "c" { inCell = true; text = "" }
    }
    func parser(_ p: XMLParser, foundCharacters s: String) { if inCell { text += s } }
    func parser(_ p: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
        if name == "c" { row.append(text); inCell = false } else if name == "row" { rows.append(row) }
    }
    static func read(_ data: Data) throws -> [[String]] {
        let p = XMLParser(data: data), r = SheetReader()
        p.delegate = r
        #expect(p.parse(), "not well-formed: \(String(describing: p.parserError))")
        return r.rows
    }
}

@Suite struct ProjectSummaryTests {
    let t1: Template
    let t2: Template
    let project: Project
    var done: Record, draft: Record, second: Record
    let library: [Template]

    init() throws {
        t1 = try Fixtures.template()
        t2 = try TemplateLoader.decode(Fixtures.variantData(id: "BB-TDP-02", version: "1.0", title: "Mẫu hai"))
        project = Project(code: "PRJ", name: "Dự án", templates: [TemplateRef(t1), TemplateRef(t2)])
        library = [t1, t2]
        done = Fixtures.filledRecord(t1)
        done.values["HD-code"] = FieldValue(v: "001")
        done.values["G-22"] = FieldValue(v: [30, 25, 28]); done.values["G-23"] = FieldValue(v: [60, 80, 50])
        done.tables["E1"] = [Row(id: "r1", values: ["mode": FieldValue(v: "O"), "hand": FieldValue(v: "2T")]),
                             Row(id: "r2", values: ["mode": FieldValue(v: "O"), "hand": FieldValue(v: "1T")])]
        RecordFactory.complete(&done, template: t1)
        draft = Fixtures.filledRecord(t1)
        draft.values["HD-code"] = FieldValue(v: "002")
        draft.createdAt = done.createdAt.addingTimeInterval(60)
        second = Fixtures.filledRecord(t2)
        second.values["HD-code"] = FieldValue(v: "X-1")
        second.createdAt = done.createdAt.addingTimeInterval(120)
    }

    private func sheets(_ out: ProjectSummaryExport.Output) throws -> [String: [[String]]] {
        let wb = String(decoding: try #require(out.parts.first { $0.path == "xl/workbook.xml" }).data, as: UTF8.self)
        let names = wb.components(separatedBy: "<sheet name=\"").dropFirst().map { String($0.prefix(while: { $0 != "\"" })) }
        var result: [String: [[String]]] = [:]
        for (i, n) in names.enumerated() { result[n] = try SheetReader.read(try #require(out.parts.first { $0.path == "xl/worksheets/sheet\(i + 1).xml" }).data) }
        return result
    }

    @Test func oneSheetPerTemplateOneRowPerRecord() throws {
        let out = ProjectSummaryExport.build(project: project, records: [draft, second, done], library: library)
        let s = try sheets(out)
        #expect(out.recordCount == 3 && out.skipped == 0)
        let main = try #require(s["BB-TDP-01"]), other = try #require(s["BB-TDP-02"])
        #expect(main.count == 4 && other.count == 3)                     // 2 header rows + records
        #expect(Array(main[1].prefix(6)) == ProjectSummaryExport.fixedHeaders)
        #expect(main[2].first == "001" && main[3].first == "002")        // oldest first
        #expect(other[2].first == "X-1")
        #expect(s.keys.contains("BB-TDP-01-E1") && s.keys.contains("BB-TDP-02-E2"))
    }

    @Test func headerRowsHaveCodesThenLabels() throws {
        let s = try sheets(ProjectSummaryExport.build(project: project, records: [done], library: library))
        let main = try #require(s["BB-TDP-01"])
        let codes = main[0], labels = main[1]
        #expect(codes.count == labels.count && codes.count == 6 + t1.allFields.count)
        #expect(Array(codes.dropFirst(6).prefix(2)) == ["HD-code", "HD-plan"] && labels[6] == "Mã biên bản")
        #expect(Array(codes.prefix(6)) == ProjectSummaryExport.fixedKeys)
        #expect(Array(codes.dropFirst(6)) == t1.allFields.map(\.id))
    }

    @Test func rowHasStatusDatesAndComputedValues() throws {
        let s = try sheets(ProjectSummaryExport.build(project: project, records: [done, draft], library: library))
        let main = try #require(s["BB-TDP-01"])
        let codes = main[0]
        let row = main[2]
        #expect(row[2] == "Hoàn thành" && main[3][2] == "Nháp")
        #expect(!row[3].isEmpty && !row[4].isEmpty && !row[5].isEmpty && main[3][5].isEmpty)
        let codeCol = try #require(codes.firstIndex(of: "HD-code")), dewCol = try #require(codes.firstIndex(of: "G-24"))
        let pctCol = try #require(codes.firstIndex(of: "E-03"))
        #expect(row[0] == "001" && row[codeCol] == "001")
        #expect(!row[dewCol].isEmpty && !row[pctCol].isEmpty)       // computed fields are included
    }

    @Test func tableSheetsCarryRecordCode() throws {
        let s = try sheets(ProjectSummaryExport.build(project: project, records: [done, draft], library: library))
        let e1 = try #require(s["BB-TDP-01-E1"])
        #expect(e1[1].first == "Mã phiếu" && e1[0].dropFirst().first == "kb" && e1[0].count == 12)
        #expect(e1.count == 4)                                          // 2 headers + 2 rows of record 001
        #expect(e1[2].first == "001" && e1[3].first == "001")
        let hand = try #require(e1[0].firstIndex(of: "hand"))
        let mode = try #require(e1[0].firstIndex(of: "mode"))
        #expect(!e1[2][mode].isEmpty && !e1[2][hand].isEmpty && e1[2][hand] != e1[3][hand])
        let e2 = try #require(s["BB-TDP-01-E2"])
        #expect(e2.count == 2 + 12 * 2 && e2.dropFirst(2).allSatisfy { $0.first == "001" || $0.first == "002" })
    }

    @Test func completedFilter() throws {
        let out = ProjectSummaryExport.build(project: project, records: [done, draft, second], library: library, filter: .completed)
        let s = try sheets(out)
        #expect(out.recordCount == 1)
        let main = try #require(s["BB-TDP-01"]), other = try #require(s["BB-TDP-02"]), e2 = try #require(s["BB-TDP-01-E2"])
        #expect(main.count == 3 && other.count == 2 && e2.count == 2 + 12)
    }

    @Test func numbersStayNumeric() throws {
        let out = ProjectSummaryExport.build(project: project, records: [done], library: library)
        let xml = String(decoding: try #require(out.parts.first { $0.path == "xl/worksheets/sheet1.xml" }).data, as: UTF8.self)
        #expect(xml.contains("<v>") && xml.contains("pane ySplit=\"2\""))
    }

    @Test func templateVersionsMergeColumns() throws {
        var old = try TemplateLoader.decode(Fixtures.variantData(version: "1.1"))
        old.header.append(Field(id: "OLD-ONLY", label: "Chỉ bản cũ", type: .text))
        var oldRec = Fixtures.filledRecord(old); oldRec.templateVersion = "1.1"
        oldRec.values["OLD-ONLY"] = FieldValue(v: "cũ"); oldRec.values["HD-code"] = FieldValue(v: "OLD")
        let s = try sheets(ProjectSummaryExport.build(project: project, records: [done, oldRec], library: library + [old]))
        let main = try #require(s["BB-TDP-01"])
        #expect(main[0].contains("OLD-ONLY") && main.count == 4)
        let col = try #require(main[0].firstIndex(of: "OLD-ONLY"))
        #expect(main.dropFirst(2).map { $0[col] }.sorted() == ["", "cũ"])
    }

    @Test func recordsOfMissingTemplatesAreCounted() throws {
        let ghost = Record(templateId: "GHOST", templateVersion: "1")
        let out = ProjectSummaryExport.build(project: project, records: [done, ghost], library: library)
        #expect(out.recordCount == 1 && out.skipped == 1)
    }

    @Test func emptyProjectStillYieldsValidWorkbook() throws {
        let out = ProjectSummaryExport.build(project: Project(code: "E", name: "E"), records: [], library: library)
        let s = try sheets(out)
        #expect(out.recordCount == 0 && s.count == 1)
    }

    @Test func allPartsWellFormedAndWrittenZip() throws {
        let out = ProjectSummaryExport.build(project: project, records: [done, draft, second], library: library)
        for p in out.parts { #expect(XMLParser(data: p.data).parse(), "\(p.path) malformed") }
        let url = Fixtures.tempDir().appendingPathComponent(ProjectSummaryExport.fileName(project))
        let written = try ProjectSummaryExport.write(project: project, records: [done], library: library, to: url)
        #expect(written.recordCount == 1)
        let names = try ZipBuilder.entryNames(url)
        #expect(names.contains("[Content_Types].xml") && names.contains("xl/workbook.xml") && names.contains("xl/worksheets/sheet1.xml"))
    }

    @Test func recordsZipHasJsonAndMedia() throws {
        let store = RecordStore(root: Fixtures.tempDir())
        var a = done
        a.projectId = project.id
        let media = store.fileURL(a.id, "media/p.jpg")
        try FileManager.default.createDirectory(at: media.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data([1, 2, 3]).write(to: media)
        var photo = FieldValue(); photo.mediaInV = [Media(file: "media/p.jpg")]
        a.values["P05"] = photo
        try store.save(a); try store.save(draft)
        let url = Fixtures.tempDir().appendingPathComponent("z.zip")
        try ProjectSummaryExport.writeRecordsZip(project: project, records: [a, draft], library: library, store: store, to: url)
        let names = Set(try ZipBuilder.entryNames(url))
        #expect(names.isSuperset(of: ["001/record.json", "001/export.json", "001/media/p.jpg", "002/record.json"]))
        let export = try JSONSerialization.jsonObject(with: ZipBuilder.read(url, path: "001/export.json")) as? [String: Any]
        #expect((export?["project"] as? [String: Any])?["code"] as? String == "PRJ")
    }

    /// `PM_EXPORT_DIR=<dir> swift test` keeps a real summary xlsx and .pmproject for manual inspection.
    @Test func keepSamplesWhenAsked() throws {
        guard let dir = ProcessInfo.processInfo.environment["PM_EXPORT_DIR"] else { return }
        let out = URL(fileURLWithPath: dir)
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let p = Project(code: "KHAO-SAT-TU-DOI", name: "Khảo sát tủ đổi pin Hà Nội", client: "V-Green", color: "#1F5FA8",
                        templates: [TemplateRef(t1), TemplateRef(t2)])
        _ = try ProjectSummaryExport.write(project: p, records: [done, draft, second], library: library, to: out.appendingPathComponent("tong-hop.xlsx"))
        let root = Fixtures.tempDir()
        _ = try Fixtures.install(Fixtures.templateData(), root: root)
        _ = try Fixtures.install(Fixtures.variantData(id: "BB-TDP-02", version: "1.0", title: "Mẫu hai"), root: root)
        try ProjectBundle.make(p, templates: TemplateStore(root: root), sites: [Site(code: "TDP-001", address: "1 Hàng Bài", lat: 21.02, lng: 105.85), Site(code: "TDP-002")])
            .write(to: out.appendingPathComponent(ProjectBundle.fileName(for: p)))
    }
}
