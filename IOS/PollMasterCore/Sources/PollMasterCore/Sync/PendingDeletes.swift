import Foundation

/// Records deleted on the device that the server still has: `<AppData>/sync/pending-deletes.json` (schema §10.5).
public struct PendingDelete: Codable, Equatable, Sendable {
    public var projectId: String
    public var recordId: String
    public init(projectId: String, recordId: String) { self.projectId = projectId; self.recordId = recordId }
}

public struct PendingDeletes: Sendable {
    public let file: URL

    public init(root: URL) { file = root.appendingPathComponent("sync/pending-deletes.json") }

    public func load() -> [PendingDelete] {
        guard let data = try? Data(contentsOf: file) else { return [] }
        return (try? JSONDecoder().decode([PendingDelete].self, from: data)) ?? []
    }

    private func write(_ list: [PendingDelete]) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(list).write(to: file, options: .atomic)
    }

    public func add(_ item: PendingDelete) throws {
        var list = load()
        if !list.contains(item) { list.append(item); try write(list) }
    }

    public func remove(_ item: PendingDelete) throws {
        let list = load()
        if list.contains(item) { try write(list.filter { $0 != item }) }
    }
}
