import Foundation
import ZIPFoundation

public enum ZipError: Error { case create(String) }

/// Collects in-memory parts and files, then writes a zip (entries keep insertion order).
public struct ZipBuilder {
    private enum Source { case data(Data), file(URL) }
    private var entries: [(path: String, source: Source)] = []

    public init() {}

    public mutating func add(_ path: String, data: Data) { entries.append((path, .data(data))) }
    public mutating func add(_ path: String, file: URL) { entries.append((path, .file(file))) }

    public func write(to url: URL) throws {
        try? FileManager.default.removeItem(at: url)
        let archive = try Archive(url: url, accessMode: .create)
        for e in entries {
            switch e.source {
            case .data(let d):
                try archive.addEntry(with: e.path, type: .file, uncompressedSize: Int64(d.count), compressionMethod: .deflate) { pos, size in
                    d.subdata(in: Int(pos)..<Int(pos) + size)
                }
            case .file(let f):
                try archive.addEntry(with: e.path, fileURL: f, compressionMethod: .deflate)
            }
        }
    }

    /// Names of the entries in an existing archive (used by tests and sanity checks).
    public static func entryNames(_ url: URL) throws -> [String] {
        let archive = try Archive(url: url, accessMode: .read)
        return archive.map(\.path)
    }

    public static func read(_ url: URL, path: String) throws -> Data {
        let archive = try Archive(url: url, accessMode: .read)
        guard let entry = archive[path] else { throw ZipError.create("missing \(path)") }
        var out = Data()
        _ = try archive.extract(entry) { out.append($0) }
        return out
    }
}
