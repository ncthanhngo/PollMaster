import Testing
import Foundation
import PDFKit
@testable import PollMasterCore

/// PDF / DOCX layout rules of docs/template-schema.md §6.5.
@Suite struct ParityExportTests {
    let t: Template
    let record: Record
    init() throws {
        t = try Fixtures.template()
        var r = Fixtures.filledRecord(t)
        r.tables["E1"] = [Row(values: ["mode": FieldValue(v: "O")]), Row(values: ["mode": FieldValue(v: "O")])]
        record = r
    }

    @Test func tableRowTitles() {
        let m = ExportModelBuilder.build(t, record)
        let e1 = m.tables.first { $0.id == "E1" }!, e2 = m.tables.first { $0.id == "E2" }!
        #expect(!e1.isFixed && e1.rowTitles == ["Lượt 1", "Lượt 2"])
        #expect(e2.isFixed && e2.rowTitles.isEmpty)
    }

    @Test func docxHasRepeatColumnAndPageFields() throws {
        let url = Fixtures.tempDir().appendingPathComponent("a.docx")
        try DOCXExport.write(ExportModelBuilder.build(t, record), recordFolder: Fixtures.tempDir(), to: url)
        let doc = String(decoding: try ZipBuilder.read(url, path: "word/document.xml"), as: UTF8.self)
        #expect(doc.contains(">#</w:t>") && doc.contains(">Lượt 1</w:t>"))
        let footer = String(decoding: try ZipBuilder.read(url, path: "word/footer1.xml"), as: UTF8.self)
        #expect(footer.contains(" PAGE ") && footer.contains(" NUMPAGES "))
    }

    @Test func pdfFooterHasTotal() throws {
        let url = Fixtures.tempDir().appendingPathComponent("a.pdf")
        try PDFExport.write(ExportModelBuilder.build(t, record), recordFolder: Fixtures.tempDir(), to: url)
        let pdf = try #require(PDFDocument(url: url))
        let n = pdf.pageCount
        #expect(n >= 2)
        #expect(pdf.page(at: 0)?.string?.contains("Trang 1/\(n)") == true)
        #expect(pdf.page(at: n - 1)?.string?.contains("Trang \(n)/\(n)") == true)
    }
}
