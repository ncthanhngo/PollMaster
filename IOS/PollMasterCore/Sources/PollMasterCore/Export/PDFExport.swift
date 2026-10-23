import Foundation
import CoreGraphics

/// A4 portrait report: header, section tables, data grids, thumbnails, signature page, page numbers.
public enum PDFExport {
    public enum PDFError: Error { case cannotCreate }

    /// Two passes: the first counts pages so every footer can read "Trang n/N".
    public static func write(_ model: ExportModel, recordFolder: URL, to url: URL) throws {
        let probe = FileManager.default.temporaryDirectory.appendingPathComponent("pm-count-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: probe) }
        let pages = try render(model, recordFolder: recordFolder, to: probe, totalPages: nil)
        _ = try render(model, recordFolder: recordFolder, to: url, totalPages: pages)
    }

    @discardableResult
    private static func render(_ model: ExportModel, recordFolder: URL, to url: URL, totalPages: Int?) throws -> Int {
        try? FileManager.default.removeItem(at: url)
        guard let pdf = PDFCanvas(url: url, totalPages: totalPages) else { throw PDFError.cannotCreate }
        pdf.paragraph(model.title, size: 16, bold: true, align: .center, after: 4)
        if let s = model.subtitle { pdf.paragraph(s, size: 10, italic: true, align: .center, after: 4) }
        if let p = model.project { pdf.paragraph("Dự án: \(p.label)", size: 10, align: .center, after: 4) }
        pdf.paragraph(model.recordTitle, size: 12, bold: true, align: .center, after: 12)

        for section in model.sections {
            pdf.ensure(60)
            pdf.paragraph(section.title, size: 13, bold: true, after: 4)
            if let n = section.note { pdf.paragraph(n, size: 8, italic: true) }
            var pending: [[String]] = []
            func flush() {
                guard !pending.isEmpty else { return }
                pdf.table(headers: reportEntryHeaders, rows: pending, fractions: [0.12, 0.36, 0.34, 0.18])
                pending = []
            }
            for item in section.items {
                switch item {
                case .entry(let e): pending.append(e.reportCells)
                case .heading(let t, let n):
                    flush(); pdf.ensure(40)
                    pdf.paragraph(t, size: 11, bold: true)
                    if let n { pdf.paragraph(n, size: 8, italic: true) }
                case .table(let t):
                    flush(); pdf.ensure(60)
                    pdf.paragraph(t.title, size: 11, bold: true)
                    if let n = t.note { pdf.paragraph(n, size: 8, italic: true) }
                    let n = CGFloat(max(t.columns.count, 1)), first: CGFloat = 0.1
                    let size: CGFloat = t.columns.count > 5 ? 7 : 8.5
                    if t.isFixed {
                        pdf.table(headers: t.columns.map(\.label), rows: t.rows.map { $0.map(\.text) },
                                  fractions: Array(repeating: 1 / n, count: t.columns.count), size: size)
                    } else {
                        pdf.table(headers: ["#"] + t.columns.map(\.label), rows: zip(t.rowTitles, t.rows).map { [$0] + $1.map(\.text) },
                                  fractions: [first] + Array(repeating: (1 - first) / n, count: t.columns.count), size: size)
                    }
                }
            }
            flush()
            photos(section.photos, folder: recordFolder, pdf: pdf)
        }

        pdf.newPage()
        pdf.paragraph("Chữ ký", size: 13, bold: true, after: 8)
        for s in model.signatures {
            pdf.ensure(150)
            pdf.paragraph(s.label, size: 11, bold: true)
            pdf.paragraph(s.name.isEmpty ? "(chưa ký)" : s.name, size: 10)
            if let d = s.signedAt { pdf.paragraph(ISODate.format(d, "dd/MM/yyyy HH:mm"), size: 8) }
            if let f = s.file, let img = ImageTools.cgImage(at: recordFolder.appendingPathComponent(f), maxPixel: 900) {
                let box = CGRect(x: PDFCanvas.margin, y: pdf.y, width: 220, height: 90)
                pdf.drawImage(img, fitting: box); pdf.space(96)
            }
            pdf.space(10)
        }
        let count = pdf.page
        pdf.close()
        return count
    }

    private static func photos(_ list: [ExportPhoto], folder: URL, pdf: PDFCanvas) {
        guard !list.isEmpty else { return }
        pdf.ensure(180)
        pdf.paragraph("Ảnh", size: 10, bold: true)
        let gap: CGFloat = 10, w = (PDFCanvas.contentW - gap) / 2, imgH: CGFloat = 170
        for i in stride(from: 0, to: list.count, by: 2) {
            pdf.ensure(imgH + 24)
            for (k, p) in list[i..<min(i + 2, list.count)].enumerated() {
                let x = PDFCanvas.margin + CGFloat(k) * (w + gap)
                if let img = ImageTools.cgImage(at: folder.appendingPathComponent(p.file), maxPixel: 900) {
                    pdf.drawImage(img, fitting: CGRect(x: x, y: pdf.y, width: w, height: imgH))
                }
                pdf.draw(pdf.attributed(p.caption, size: 7, bold: false, italic: true), in: CGRect(x: x, y: pdf.y + imgH + 2, width: w, height: 12))
            }
            pdf.space(imgH + 18)
        }
    }
}
