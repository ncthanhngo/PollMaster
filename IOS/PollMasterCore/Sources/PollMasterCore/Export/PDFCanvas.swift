import Foundation
import CoreGraphics
import CoreText

/// Flowing A4 page writer on top of a PDF CGContext. `y` is measured from the top of the page.
final class PDFCanvas {
    static let pageW: CGFloat = 595, pageH: CGFloat = 842, margin: CGFloat = 40
    static var contentW: CGFloat { pageW - 2 * margin }
    private static let bottomLimit: CGFloat = pageH - margin - 14

    private let ctx: CGContext
    private(set) var y: CGFloat = margin
    private(set) var page = 0
    private let totalPages: Int?

    /// `totalPages` known from a first pass gives "Trang n/N" footers; nil gives "Trang n".
    init?(url: URL, totalPages: Int? = nil) {
        self.totalPages = totalPages
        var box = CGRect(x: 0, y: 0, width: Self.pageW, height: Self.pageH)
        guard let c = CGContext(url as CFURL, mediaBox: &box, nil) else { return nil }
        ctx = c
        newPage()
    }

    func newPage() {
        if page > 0 { drawFooter(); ctx.endPDFPage() }
        ctx.beginPDFPage(nil)
        page += 1
        y = Self.margin
    }

    func close() {
        drawFooter()
        ctx.endPDFPage()
        ctx.closePDF()
    }

    private func drawFooter() {
        let s = attributed("Trang \(page)" + (totalPages.map { "/\($0)" } ?? ""), size: 8, bold: false, italic: false, align: .center)
        draw(s, in: CGRect(x: Self.margin, y: Self.pageH - Self.margin + 4, width: Self.contentW, height: 12))
    }

    func ensure(_ height: CGFloat) {
        if y + height > Self.bottomLimit { newPage() }
    }

    func space(_ h: CGFloat) { y += h }

    // MARK: text

    func attributed(_ text: String, size: CGFloat, bold: Bool, italic: Bool, align: CTTextAlignment = .left) -> CFAttributedString {
        let name = bold ? "Arial-BoldMT" : (italic ? "Arial-ItalicMT" : "ArialMT")
        let font = CTFontCreateWithName(name as CFString, size, nil)
        var alignment = align
        let setting = withUnsafeMutablePointer(to: &alignment) {
            CTParagraphStyleSetting(spec: .alignment, valueSize: MemoryLayout<CTTextAlignment>.size, value: $0)
        }
        let style = CTParagraphStyleCreate([setting], 1)
        let attrs: [CFString: Any] = [kCTFontAttributeName: font, kCTParagraphStyleAttributeName: style,
                                      kCTForegroundColorAttributeName: CGColor(gray: 0, alpha: 1)]
        return CFAttributedStringCreate(nil, text as CFString, attrs as CFDictionary)
    }

    func height(_ s: CFAttributedString, width: CGFloat) -> CGFloat {
        let fs = CTFramesetterCreateWithAttributedString(s)
        let size = CTFramesetterSuggestFrameSizeWithConstraints(fs, CFRange(location: 0, length: 0), nil,
                                                                CGSize(width: width, height: .greatestFiniteMagnitude), nil)
        return ceil(size.height) + 1
    }

    /// `rect` uses top-origin coordinates.
    func draw(_ s: CFAttributedString, in rect: CGRect) {
        let fs = CTFramesetterCreateWithAttributedString(s)
        let flipped = CGRect(x: rect.minX, y: Self.pageH - rect.maxY, width: rect.width, height: rect.height)
        let frame = CTFramesetterCreateFrame(fs, CFRange(location: 0, length: 0), CGPath(rect: flipped, transform: nil), nil)
        CTFrameDraw(frame, ctx)
    }

    func paragraph(_ text: String, size: CGFloat = 10, bold: Bool = false, italic: Bool = false,
                   align: CTTextAlignment = .left, after: CGFloat = 4) {
        guard !text.isEmpty else { return }
        let s = attributed(text, size: size, bold: bold, italic: italic, align: align)
        let h = height(s, width: Self.contentW)
        ensure(h)
        draw(s, in: CGRect(x: Self.margin, y: y, width: Self.contentW, height: h))
        y += h + after
    }

    // MARK: tables

    func table(headers: [String], rows: [[String]], fractions: [CGFloat], size: CGFloat = 9) {
        let widths = fractions.map { $0 * Self.contentW }
        let pad: CGFloat = 3
        func drawRow(_ cells: [String], header: Bool) {
            let strings = cells.map { attributed($0, size: size, bold: header, italic: false) }
            var h = zip(strings, widths).map { height($0, width: $1 - 2 * pad) }.max() ?? size
            h = min(h + 2 * pad, Self.bottomLimit - Self.margin)
            if y + h > Self.bottomLimit {
                newPage()
                if !header, !headers.isEmpty { drawRow(headers, header: true) }
            }
            var x = Self.margin
            for (s, w) in zip(strings, widths) {
                let box = CGRect(x: x, y: y, width: w, height: h)
                if header { ctx.setFillColor(CGColor(gray: 0.92, alpha: 1)); ctx.fill(flip(box)) }
                ctx.setStrokeColor(CGColor(gray: 0.5, alpha: 1)); ctx.setLineWidth(0.5); ctx.stroke(flip(box))
                draw(s, in: box.insetBy(dx: pad, dy: pad))
                x += w
            }
            y += h
        }
        if !headers.isEmpty { drawRow(headers, header: true) }
        rows.forEach { drawRow($0, header: false) }
        y += 8
    }

    private func flip(_ r: CGRect) -> CGRect { CGRect(x: r.minX, y: Self.pageH - r.maxY, width: r.width, height: r.height) }

    // MARK: images

    /// Draw `image` fitted in `box` (top-origin).
    func drawImage(_ image: CGImage, fitting box: CGRect) {
        let scale = min(box.width / CGFloat(image.width), box.height / CGFloat(image.height))
        let w = CGFloat(image.width) * scale, h = CGFloat(image.height) * scale
        ctx.draw(image, in: flip(CGRect(x: box.minX, y: box.minY, width: w, height: h)))
    }
}
