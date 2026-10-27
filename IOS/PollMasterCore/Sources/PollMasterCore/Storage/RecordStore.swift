import Foundation

/// One folder in the records directory: the record, or why it could not be read.
public struct RecordListEntry: Identifiable, Sendable {
    public var id: String
    public var record: Record?
    public var error: String?
    public var modifiedAt: Date
}

/// Records on device: `<root>/records/<id>/record.json` + `media/`.
public struct RecordStore: Sendable {
    public let dir: URL

    public init(root: URL) {
        dir = root.appendingPathComponent("records", isDirectory: true)
    }

    public func folder(_ id: String) -> URL { dir.appendingPathComponent(id, isDirectory: true) }
    public func mediaDir(_ id: String) -> URL { folder(id).appendingPathComponent("media", isDirectory: true) }
    public func fileURL(_ recordId: String, _ relative: String) -> URL { folder(recordId).appendingPathComponent(relative) }

    /// Readable records only, newest first.
    public func list() -> [Record] { listItems().compactMap(\.record) }

    /// Every record folder, newest first; unreadable ones carry the error so they can be shown and deleted.
    public func listItems() -> [RecordListEntry] {
        ids().compactMap(entry).sorted { $0.modifiedAt > $1.modifiedAt }
    }

    /// Names of the record folders (readable or not).
    public func ids() -> [String] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return names.filter { !$0.hasPrefix(".") && isFolder($0) }
    }

    private func isFolder(_ id: String) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: folder(id).path, isDirectory: &isDir) && isDir.boolValue
    }

    /// One record folder as `listItems` reports it; nil when there is no such folder.
    public func entry(_ id: String) -> RecordListEntry? {
        guard isFolder(id) else { return nil }
        let file = folder(id).appendingPathComponent("record.json")
        let modified = (try? FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate] as? Date) ?? Date()
        do {
            let r = try read(id)
            return RecordListEntry(id: id, record: r, error: nil, modifiedAt: r.updatedAt)
        } catch {
            return RecordListEntry(id: id, record: nil, error: Self.describe(error), modifiedAt: modified)
        }
    }

    private func read(_ id: String) throws -> Record {
        let data = try Data(contentsOf: folder(id).appendingPathComponent("record.json"))
        return try JSONCoding.makeDecoder().decode(Record.self, from: data)
    }

    static func describe(_ error: Error) -> String {
        if let e = error as? DecodingError { return TemplateLoader.describe(e) }
        return error.localizedDescription
    }

    public func load(_ id: String) -> Record? { try? read(id) }

    /// Atomic write (temp file + rename).
    public func save(_ record: Record) throws {
        let f = folder(record.id)
        try FileManager.default.createDirectory(at: f.appendingPathComponent("media", isDirectory: true), withIntermediateDirectories: true)
        let data = try JSONCoding.makeEncoder().encode(record)
        try data.write(to: f.appendingPathComponent("record.json"), options: .atomic)
    }

    public func delete(_ id: String) throws {
        try FileManager.default.removeItem(at: folder(id))
    }

    /// Remove media files no longer referenced by the record.
    public func pruneMedia(_ record: Record) {
        let used = Set(Self.mediaFiles(of: record).map { ($0 as NSString).lastPathComponent })
        let files = (try? FileManager.default.contentsOfDirectory(atPath: mediaDir(record.id).path)) ?? []
        for f in files where !used.contains(f) { try? FileManager.default.removeItem(at: mediaDir(record.id).appendingPathComponent(f)) }
    }

    /// Relative paths of every media/signature file referenced by a record.
    public static func mediaFiles(of r: Record) -> [String] {
        func files(_ v: FieldValue) -> [String] {
            var out = (v.media ?? []).map(\.file) + v.mediaInV.map(\.file)
            if let f = v.v?.object?["file"]?.string { out.append(f) }
            return out
        }
        return r.values.values.flatMap(files) + r.tables.values.flatMap { $0.flatMap { $0.values.values.flatMap(files) } }
            + r.signatures.values.map(\.file)
    }

    /// Bytes used by all records and templates.
    public static func directorySize(_ url: URL) -> Int64 {
        guard let en = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        return en.compactMap { ($0 as? URL).flatMap { try? $0.resourceValues(forKeys: [.fileSizeKey]).fileSize } }.reduce(0) { $0 + Int64($1) }
    }
}
