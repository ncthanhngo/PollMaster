import Foundation
import ImageIO
import CoreGraphics

public struct EncodedImage: Sendable {
    public var data: Data
    public var ext: String
    public var width: Int
    public var height: Int
}

public enum ImageTools {
    /// Down-scaled CGImage honoring EXIF orientation.
    public static func cgImage(at url: URL, maxPixel: Int) -> CGImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }

    public static func encodeJPEG(_ image: CGImage, quality: Double) -> Data? {
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? data as Data : nil
    }

    /// Re-encoded JPEG no larger than `maxPixel` (for embedding in documents).
    public static func jpeg(at url: URL, maxPixel: Int = 1280, quality: Double = 0.8) -> EncodedImage? {
        guard let img = cgImage(at: url, maxPixel: maxPixel), let d = encodeJPEG(img, quality: quality) else { return nil }
        return EncodedImage(data: d, ext: "jpeg", width: img.width, height: img.height)
    }

    /// File bytes as-is (PNG signatures) with pixel size.
    public static func original(at url: URL) -> EncodedImage? {
        guard let data = try? Data(contentsOf: url), let src = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int else { return nil }
        return EncodedImage(data: data, ext: url.pathExtension.lowercased() == "png" ? "png" : "jpeg", width: w, height: h)
    }
}

extension ExportEntry {
    /// Report columns: Mã | Thông số | Giá trị | Ghi chú.
    public var reportCells: [String] {
        var value = display
        if !value.isEmpty, !unit.isEmpty, flag != "KĐĐ" { value += " \(unit)" }
        return [id, label, value, note]
    }
}

public let reportEntryHeaders = ["Mã", "Thông số", "Giá trị", "Ghi chú"]
