import Foundation
import UIKit
import PollMasterCore
import CoreLocation

enum MediaFailure: LocalizedError {
    case unreadable
    var errorDescription: String? { "Không đọc được ảnh/video đã chọn." }
}

/// Writes captured images and videos into a record's media folder using the template naming pattern.
/// A value type that can run off the main thread (image decoding, JPEG encoding and file copies are slow).
struct MediaService: Sendable {
    let store: RecordStore
    let recordId: String
    let namer: @Sendable (_ fieldId: String, _ seq: Int) -> String
    let quality: PhotoQuality

    /// Smallest sequence number >= 1 whose file name is still free.
    private func uniqueURL(fieldId: String, ext: String) -> (URL, String) {
        let dir = store.mediaDir(recordId)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var seq = 1
        while true {
            let name = "\(namer(fieldId, seq)).\(ext)"
            let url = dir.appendingPathComponent(name)
            if !FileManager.default.fileExists(atPath: url.path) { return (url, "media/\(name)") }
            seq += 1
        }
    }

    /// JPEG (quality per settings, long side capped), orientation baked in.
    func saveImage(_ data: Data, fieldId: String, location: CLLocation?) throws -> Media {
        guard let image = UIImage(data: data) else { throw MediaFailure.unreadable }
        return try saveImage(image, fieldId: fieldId, location: location)
    }

    func saveImage(_ image: UIImage, fieldId: String, location: CLLocation?) throws -> Media {
        let jpeg = try Self.encode(image, maxSide: quality.maxSide, quality: quality.jpegQuality)
        let (url, rel) = uniqueURL(fieldId: fieldId, ext: "jpg")
        try jpeg.write(to: url, options: .atomic)
        return Media(file: rel, takenAt: Date(), lat: location?.coordinate.latitude, lng: location?.coordinate.longitude)
    }

    func saveVideo(from source: URL, fieldId: String, location: CLLocation?) throws -> Media {
        let ext = source.pathExtension.isEmpty ? "mov" : source.pathExtension.lowercased()
        let (url, rel) = uniqueURL(fieldId: fieldId, ext: ext)
        try FileManager.default.copyItem(at: source, to: url)
        return Media(file: rel, takenAt: Date(), lat: location?.coordinate.latitude, lng: location?.coordinate.longitude)
    }

    /// Moves a finished recording (m4a) into the record's media folder.
    func saveAudio(from source: URL, fieldId: String, location: CLLocation?) throws -> Media {
        let (url, rel) = uniqueURL(fieldId: fieldId, ext: "m4a")
        try FileManager.default.copyItem(at: source, to: url)
        return Media(file: rel, takenAt: Date(), lat: location?.coordinate.latitude, lng: location?.coordinate.longitude)
    }

    func saveSignature(_ png: Data, id: String) throws -> String {
        let dir = store.mediaDir(recordId)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let name = "sig_\(RecordNaming.sanitize(id)).png"
        try png.write(to: dir.appendingPathComponent(name), options: .atomic)
        return "media/\(name)"
    }

    func remove(_ media: Media) {
        ThumbnailLoader.forget(url(media))
        try? FileManager.default.removeItem(at: store.fileURL(recordId, media.file))
    }

    func url(_ media: Media) -> URL { store.fileURL(recordId, media.file) }

    static func encode(_ image: UIImage, maxSide: CGFloat, quality: CGFloat) throws -> Data {
        let longest = max(image.size.width, image.size.height) * image.scale
        let scale = min(1, maxSide / max(longest, 1))
        let size = CGSize(width: (image.size.width * image.scale * scale).rounded(), height: (image.size.height * image.scale * scale).rounded())
        let fmt = UIGraphicsImageRendererFormat.default()
        fmt.scale = 1
        let flat = UIGraphicsImageRenderer(size: size, format: fmt).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = flat.jpegData(compressionQuality: quality) else { throw MediaFailure.unreadable }
        return data
    }
}
