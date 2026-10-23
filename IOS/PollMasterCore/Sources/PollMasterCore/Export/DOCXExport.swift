import Foundation

/// WordprocessingML report with the same layout as the PDF.
public enum DOCXExport {
    private static let totalWidth = 9638          // A4 minus 2 cm margins, in twips
    private static let ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""

    private final class Builder {
        var body = ""
        var images: [(name: String, image: EncodedImage)] = []
        let recordFolder: URL
        init(recordFolder: URL) { self.recordFolder = recordFolder }

        func run(_ text: String, bold: Bool = false, italic: Bool = false, size: Int = 20) -> String {
            "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>\(bold ? "<w:b/>" : "")\(italic ? "<w:i/>" : "")<w:sz w:val=\"\(size)\"/></w:rPr><w:t xml:space=\"preserve\">\(XML.escape(text))</w:t></w:r>"
        }

        func paragraph(_ text: String, bold: Bool = false, italic: Bool = false, size: Int = 20, center: Bool = false, keepNext: Bool = false, after: Int = 80) -> String {
            let lines = text.components(separatedBy: "\n")
            let runs = lines.enumerated().map { ($0 > 0 ? "<w:r><w:br/></w:r>" : "") + run($1, bold: bold, italic: italic, size: size) }.joined()
            return "<w:p><w:pPr>\(keepNext ? "<w:keepNext/>" : "")<w:spacing w:before=\"60\" w:after=\"\(after)\"/>\(center ? "<w:jc w:val=\"center\"/>" : "")</w:pPr>\(runs)</w:p>"
        }

        func cell(_ inner: String, width: Int, shade: Bool = false) -> String {
            "<w:tc><w:tcPr><w:tcW w:w=\"\(width)\" w:type=\"dxa\"/>\(shade ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E8EEF4\"/>" : "")</w:tcPr>\(inner)</w:tc>"
        }

        func table(headers: [String], rows: [[String]], widths: [Int], size: Int = 18) -> String {
            let border = ["top", "left", "bottom", "right", "insideH", "insideV"].map { "<w:\($0) w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>" }.joined()
            var x = "<w:tbl><w:tblPr><w:tblW w:w=\"\(widths.reduce(0, +))\" w:type=\"dxa\"/><w:tblBorders>\(border)</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:left w:w=\"70\" w:type=\"dxa\"/><w:right w:w=\"70\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>"
            x += widths.map { "<w:gridCol w:w=\"\($0)\"/>" }.joined() + "</w:tblGrid>"
            if !headers.isEmpty {
                x += "<w:tr><w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>" + zip(headers, widths).map { cell(paragraph($0, bold: true, size: size, after: 20), width: $1, shade: true) }.joined() + "</w:tr>"
            }
            for r in rows {
                x += "<w:tr><w:trPr><w:cantSplit/></w:trPr>" + zip(r, widths).map { cell(paragraph($0, size: size, after: 20), width: $1) }.joined() + "</w:tr>"
            }
            return x + "</w:tbl>" + paragraph("", size: 8, after: 40)
        }

        func boxed(_ inner: String) -> String {
            let border = ["top", "left", "bottom", "right"].map { "<w:\($0) w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>" }.joined()
            return "<w:tbl><w:tblPr><w:tblW w:w=\"\(totalWidth)\" w:type=\"dxa\"/><w:tblBorders>\(border)</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"\(totalWidth)\"/></w:tblGrid><w:tr><w:trPr><w:cantSplit/></w:trPr>\(cell(inner, width: totalWidth))</w:tr></w:tbl>" + paragraph("", size: 8, after: 40)
        }

        func drawing(_ img: EncodedImage, maxWidthTwips: Int, maxHeightTwips: Int) -> String {
            let n = images.count + 1
            let name = "image\(n).\(img.ext == "png" ? "png" : "jpeg")"
            images.append((name, img))
            let scale = min(Double(maxWidthTwips) / Double(img.width), Double(maxHeightTwips) / Double(img.height))
            let cx = Int(Double(img.width) * scale * 635), cy = Int(Double(img.height) * scale * 635)   // 1 twip = 635 EMU
            return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"\(cx)\" cy=\"\(cy)\"/><wp:docPr id=\"\(n)\" name=\"Picture \(n)\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"\(n)\" name=\"\(name)\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdImg\(n)\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"\(cx)\" cy=\"\(cy)\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
        }

        func photoGrid(_ photos: [ExportPhoto]) {
            let half = totalWidth / 2
            var rows = ""
            for pair in stride(from: 0, to: photos.count, by: 2).map({ Array(photos[$0..<min($0 + 2, photos.count)]) }) {
                var cells = ""
                for p in pair {
                    var inner = paragraph(p.caption, italic: true, size: 16, after: 20)
                    if let img = ImageTools.jpeg(at: recordFolder.appendingPathComponent(p.file)) {
                        inner = "<w:p><w:pPr><w:keepNext/></w:pPr>\(drawing(img, maxWidthTwips: half - 200, maxHeightTwips: 4200))</w:p>" + inner
                    }
                    cells += cell(inner, width: half)
                }
                if pair.count == 1 { cells += cell("<w:p/>", width: half) }
                rows += "<w:tr><w:trPr><w:cantSplit/></w:trPr>\(cells)</w:tr>"
            }
            body += "<w:tbl><w:tblPr><w:tblW w:w=\"\(totalWidth)\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"\(half)\"/><w:gridCol w:w=\"\(half)\"/></w:tblGrid>\(rows)</w:tbl>" + paragraph("", size: 8)
        }
    }

    public static func build(_ model: ExportModel, recordFolder: URL) -> [(path: String, data: Data)] {
        let b = Builder(recordFolder: recordFolder)
        b.body += b.paragraph(model.title, bold: true, size: 32, center: true)
        if let s = model.subtitle { b.body += b.paragraph(s, italic: true, size: 20, center: true) }
        if let p = model.project { b.body += b.paragraph("Dự án: \(p.label)", center: true, after: 80) }
        b.body += b.paragraph(model.recordTitle, bold: true, size: 22, center: true, after: 160)

        let entryWidths = [1100, 3500, 3438, 1600]
        for section in model.sections {
            b.body += b.paragraph(section.title, bold: true, size: 26, keepNext: true)
            if let n = section.note { b.body += b.paragraph(n, italic: true, size: 18) }
            var pending: [[String]] = []
            func flush() { if !pending.isEmpty { b.body += b.table(headers: reportEntryHeaders, rows: pending, widths: entryWidths); pending = [] } }
            for item in section.items {
                switch item {
                case .entry(let e): pending.append(e.reportCells)
                case .heading(let t, let n):
                    flush()
                    b.body += b.paragraph(t, bold: true, size: 22, keepNext: true)
                    if let n { b.body += b.paragraph(n, italic: true, size: 18) }
                case .table(let t):
                    flush()
                    b.body += b.paragraph(t.title, bold: true, size: 22, keepNext: true)
                    if let n = t.note { b.body += b.paragraph(n, italic: true, size: 18) }
                    let size = t.columns.count > 5 ? 14 : 16
                    if t.isFixed {
                        let w = totalWidth / max(t.columns.count, 1)
                        b.body += b.table(headers: t.columns.map(\.label), rows: t.rows.map { $0.map(\.text) },
                                          widths: Array(repeating: w, count: t.columns.count), size: size)
                    } else {
                        let first = 900, w = (totalWidth - first) / max(t.columns.count, 1)
                        let rows = zip(t.rowTitles, t.rows).map { [$0] + $1.map(\.text) }
                        b.body += b.table(headers: ["#"] + t.columns.map(\.label), rows: rows,
                                          widths: [first] + Array(repeating: w, count: t.columns.count), size: size)
                    }
                }
            }
            flush()
            if !section.photos.isEmpty {
                b.body += b.paragraph("Ảnh – \(section.title)", bold: true, size: 20, keepNext: true)
                b.photoGrid(section.photos)
            }
        }

        b.body += "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>" + b.paragraph("Chữ ký", bold: true, size: 26, keepNext: true)
        for s in model.signatures {
            var inner = b.paragraph(s.label, bold: true) + b.paragraph(s.name.isEmpty ? "(chưa ký)" : s.name)
            if let d = s.signedAt { inner += b.paragraph(ISODate.format(d, "dd/MM/yyyy HH:mm"), size: 16) }
            if let f = s.file, let img = ImageTools.original(at: recordFolder.appendingPathComponent(f)) {
                inner += "<w:p>\(b.drawing(img, maxWidthTwips: 3600, maxHeightTwips: 1800))</w:p>"
            }
            b.body += b.boxed(inner)
        }

        let sect = "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr>"
        let document = XML.header + "<w:document \(ns)><w:body>\(b.body)\(sect)</w:body></w:document>"
        let footer = XML.header + "<w:ftr \(ns)><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>\(b.run("Trang ", size: 16))<w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r><w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:t>1</w:t></w:r><w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:fldChar w:fldCharType=\"end\"/></w:r>\(b.run("/", size: 16))<w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:instrText xml:space=\"preserve\"> NUMPAGES </w:instrText></w:r><w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:t>1</w:t></w:r><w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:fldChar w:fldCharType=\"end\"/></w:r></w:p></w:ftr>"
        let styles = XML.header + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/><w:sz w:val=\"20\"/><w:lang w:val=\"vi-VN\"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>"

        let rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        let imageRels = b.images.enumerated().map { "<Relationship Id=\"rIdImg\($0 + 1)\" Type=\"\(rel)/image\" Target=\"media/\($1.name)\"/>" }.joined()
        let parts: [(String, Data)] = [
            ("[Content_Types].xml", Data((XML.header + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/><Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/></Types>").utf8)),
            ("_rels/.rels", Data((XML.header + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"\(rel)/officeDocument\" Target=\"word/document.xml\"/></Relationships>").utf8)),
            ("word/document.xml", Data(document.utf8)),
            ("word/styles.xml", Data(styles.utf8)),
            ("word/footer1.xml", Data(footer.utf8)),
            ("word/_rels/document.xml.rels", Data((XML.header + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rIdStyles\" Type=\"\(rel)/styles\" Target=\"styles.xml\"/><Relationship Id=\"rIdFooter\" Type=\"\(rel)/footer\" Target=\"footer1.xml\"/>\(imageRels)</Relationships>").utf8)),
        ] + b.images.map { ("word/media/\($0.name)", $0.image.data) }
        return parts
    }

    public static func write(_ model: ExportModel, recordFolder: URL, to url: URL) throws {
        var zip = ZipBuilder()
        for p in build(model, recordFolder: recordFolder) { zip.add(p.path, data: p.data) }
        try zip.write(to: url)
    }
}
