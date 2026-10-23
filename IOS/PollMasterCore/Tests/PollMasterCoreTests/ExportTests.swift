import Testing
import Foundation
import CoreGraphics
import ImageIO
@testable import PollMasterCore

private final class WellFormed: NSObject, XMLParserDelegate {
    var error: Error?
    func parser(_ parser: XMLParser, parseErrorOccurred parseError: Error) { error = parseError }
}

private func assertWellFormed(_ data: Data, _ name: String) {
    let p = XMLParser(data: data)
    let d = WellFormed()
    p.delegate = d
    #expect(p.parse(), "\(name) not well-formed: \(String(describing: d.error))")
}

/// Writes a small JPEG with CoreGraphics.
private func writeJPEG(to url: URL, width: Int = 120, height: Int = 80) throws {
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    ctx.setFillColor(CGColor(red: 0.2, green: 0.5, blue: 0.8, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    let data = try #require(ImageTools.encodeJPEG(ctx.makeImage()!, quality: 0.8))
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try data.write(to: url)
}

@Suite struct ExportTests {
    let t: Template
    var record: Record
    let store: RecordStore

    init() throws {
        t = try Fixtures.template()
        store = RecordStore(root: Fixtures.tempDir())
        var r = Fixtures.filledRecord(t)
        r.values["HD-code"] = FieldValue(v: "001")
        r.values["A-01"] = FieldValue(v: "K<1> & \"x\"")
        r.values["A-05"] = FieldValue(v: "other", other: ["other": "Chợ"])
        r.values["B-02"] = FieldValue(v: 1500, flag: .estimate)
        r.values["B-07"] = FieldValue(flag: .notMeasurable, reason: "Khóa")
        r.values["B-16"] = FieldValue(v: true, parts: ["overhang": 120])
        r.values["G-22"] = FieldValue(v: [30, 25, nil])
        r.values["G-23"] = FieldValue(v: [60, 80, 50])
        r.tables["E1"] = [Row(id: "row-1", values: ["mode": FieldValue(v: "O"), "hand": FieldValue(v: "2T"), "attempts": FieldValue(v: 2)])]
        try writeJPEG(to: store.fileURL(r.id, "media/001_P05_01.jpg"))
        try writeJPEG(to: store.fileURL(r.id, "media/sig_surveyor.png"))
        var photo = FieldValue(); photo.mediaInV = [Media(file: "media/001_P05_01.jpg")]
        r.values["P05"] = photo
        r.signatures["surveyor"] = SignatureEntry(file: "media/sig_surveyor.png", name: "Nguyễn Văn A")
        try store.save(r)
        record = r
    }

    @Test func modelCellsAndFlags() throws {
        let m = ExportModelBuilder.build(t, record)
        let byId = Dictionary(m.entries.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        #expect(byId["B-02"]?.display == "~1500" && byId["B-02"]?.number == nil && byId["B-02"]?.flag == "~")
        #expect(byId["B-07"]?.display == "KĐĐ (Khóa)" && byId["B-07"]?.flag == "KĐĐ")
        #expect(byId["A-05"]?.display == "Khác: Chợ")
        #expect(byId["B-16"]?.display == "Có; Độ vươn: 120 mm")
        #expect(byId["G-22"]?.display == "30 | 25 | –")
        #expect(byId["G-24"]?.display == "21.4 | 21.3 | –" && byId["G-24"]?.isComputed == true)
        #expect(byId["A-06"]?.number != nil)
        let e1 = try #require(m.tables.first { $0.id == "E1" })
        let attemptsIdx = try #require(e1.columns.firstIndex { $0.id == "attempts" })
        #expect(e1.rows[0][attemptsIdx].number == 2 && e1.rows[0][attemptsIdx].text == "2")
        #expect(e1.rowTitles == ["Lượt 1"])
        #expect(m.baseName.hasPrefix("001_"))
    }

    @Test func exportJSONContents() throws {
        let data = try ExportJSON.build(t, record)
        let o = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect((o["template"] as? [String: Any])?["id"] as? String == "BB-TDP-01")
        #expect((o["record"] as? [String: Any])?["status"] as? String == "draft")
        let fields = try #require(o["fields"] as? [String: Any])
        let b02 = try #require(fields["B-02"] as? [String: Any])
        #expect(b02["value"] as? Double == 1500 && b02["flag"] as? String == "estimate" && b02["label"] != nil && b02["unit"] as? String == "mm")
        #expect((fields["P05"] as? [String: Any])?["value"] as? [String] == ["001_P05_01.jpg"])
        #expect((fields["B-16"] as? [String: Any])?["parts"] != nil)
        #expect(fields["G-24"] == nil)
        let computed = try #require(o["computed"] as? [String: Any])
        #expect(computed.count == 9)
        let g24 = try #require(computed["G-24"] as? [Any])
        #expect(abs((g24[0] as? Double ?? 0) - 21.385418967) < 1e-6 && g24[2] is NSNull)
        let tables = try #require(o["tables"] as? [String: [[String: Any]]])
        #expect(tables["E1"]?.first?["id"] as? String == "row-1")
        #expect(tables["E1"]?.first?["attempts"] as? Double == 2)
        #expect(tables["E2"]?.first?["kb"] as? String == "S04")
    }

    @Test func csvHasBomAndEscaping() throws {
        let m = ExportModelBuilder.build(t, record)
        let d = CSVExport.info(m)
        #expect(Array(d.prefix(3)) == [0xEF, 0xBB, 0xBF])
        let text = String(decoding: d.dropFirst(3), as: UTF8.self)
        #expect(text.hasPrefix("Mục,Mã,Thông số,Giá trị,Đơn vị,Cờ,Lý do,Ghi chú,Kiểm tra\r\n"))
        #expect(text.contains("\"K<1> & \"\"x\"\"\""))
    }

    @Test func xlsxPartsWellFormed() throws {
        let m = ExportModelBuilder.build(t, record)
        let url = Fixtures.tempDir().appendingPathComponent("a.xlsx")
        try XLSXExport.write(m, to: url)
        let names = try ZipBuilder.entryNames(url)
        #expect(names.first == "[Content_Types].xml")
        for n in ["_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet5.xml"] {
            #expect(names.contains(n), "missing \(n)")
        }
        for n in names { assertWellFormed(try ZipBuilder.read(url, path: n), n) }
        let wb = String(decoding: try ZipBuilder.read(url, path: "xl/workbook.xml"), as: UTF8.self)
        #expect(wb.contains("name=\"Thông tin\"") && wb.contains("name=\"E1\"") && wb.contains("name=\"F1\""))
        let s1 = String(decoding: try ZipBuilder.read(url, path: "xl/worksheets/sheet1.xml"), as: UTF8.self)
        #expect(s1.contains("K&lt;1&gt; &amp; &quot;x&quot;"))
        #expect(s1.contains("<v>"))
    }

    @Test func docxPartsWellFormed() throws {
        let m = ExportModelBuilder.build(t, record)
        let url = Fixtures.tempDir().appendingPathComponent("a.docx")
        try DOCXExport.write(m, recordFolder: store.folder(record.id), to: url)
        let names = try ZipBuilder.entryNames(url)
        for n in ["[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels", "word/footer1.xml"] {
            #expect(names.contains(n), "missing \(n)")
        }
        #expect(names.contains { $0.hasPrefix("word/media/") })
        for n in names where n.hasSuffix(".xml") || n.hasSuffix(".rels") { assertWellFormed(try ZipBuilder.read(url, path: n), n) }
        let doc = String(decoding: try ZipBuilder.read(url, path: "word/document.xml"), as: UTF8.self)
        #expect(doc.contains("Biên bản khảo sát thông số kỹ thuật") && doc.contains("<w:tbl>") && doc.contains("<w:drawing>"))
        #expect(doc.contains("Nguyễn Văn A"))
    }

    @Test func pdfIsWritten() throws {
        let m = ExportModelBuilder.build(t, record)
        let url = Fixtures.tempDir().appendingPathComponent("a.pdf")
        try PDFExport.write(m, recordFolder: store.folder(record.id), to: url)
        let data = try Data(contentsOf: url)
        #expect(String(decoding: data.prefix(5), as: UTF8.self) == "%PDF-")
        let doc = try #require(CGPDFDocument(url as CFURL))
        #expect(doc.numberOfPages >= 5)
        // Set PM_EXPORT_DIR to keep the PDF for visual inspection.
        if let out = ProcessInfo.processInfo.environment["PM_EXPORT_DIR"] {
            try? FileManager.default.removeItem(atPath: out + "/sample.pdf")
            try FileManager.default.copyItem(at: url, to: URL(fileURLWithPath: out + "/sample.pdf"))
        }
    }

    @Test func zipBundle() throws {
        let dir = Fixtures.tempDir()
        let urls = try RecordExporter(store: store).export(t, record, formats: ExportFormat.allCases, into: dir)
        #expect(urls.count == 5)
        let zip = try #require(urls.first { $0.pathExtension == "zip" })
        let names = try ZipBuilder.entryNames(zip)
        #expect(Set(names) == ["record.json", "export.json", "media/001_P05_01.jpg", "media/sig_surveyor.png"])
        let rec = try JSONCoding.makeDecoder().decode(Record.self, from: try ZipBuilder.read(zip, path: "record.json"))
        #expect(rec.id == record.id)
        #expect(urls.allSatisfy { $0.lastPathComponent.hasPrefix("001_") })
    }
}
