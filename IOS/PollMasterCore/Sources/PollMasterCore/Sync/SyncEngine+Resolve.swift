import Foundation

/// What is known about a record flagged `syncConflict` (shown in the conflict dialog).
public enum ConflictInfo: Equatable, Sendable {
    /// Someone saved a newer revision; who and when are nil when the log could not be read.
    case edited(by: String?, at: Date?)
    /// The server no longer has the record.
    case deleted
}

/// The user's answer to a conflict (schema §10.5): keep the device copy, or take the server's.
extension SyncEngine {
    private func projectOf(_ id: String) throws -> Project {
        guard let p = projects.load(id), !p.archived else { throw SyncRecordFailure("Không tìm thấy dự án trên máy.") }
        return p
    }

    public func inspectConflict(projectId: String, recordId: String) async throws -> ConflictInfo {
        let project = try projectOf(projectId)
        let page = try await remote.pull(projectId: projectId, since: project.recordsSyncedAt, cursor: nil)
        guard page.ids.contains(where: { $0.recordId == recordId }) else { return .deleted }
        let latest = try? await remote.changes(projectId: projectId, recordId: recordId).first
        return .edited(by: latest?.changedByName.nilIfEmpty, at: latest.flatMap { ISODate.parse($0.changedAt) })
    }

    /// "Giữ bản của tôi": pushes the device copy over the server's (logged as a normal save). The stored copy must be current.
    public func keepMine(projectId: String, recordId: String) async throws {
        let project = try projectOf(projectId)
        switch try await push(recordId, project: project, force: true) {
        case .pushed, .replaced: return
        case .conflict: throw SyncRecordFailure("Phiếu vừa được sửa lại trên máy chủ. Thử lại.")
        case .skipped: throw SyncRecordFailure("Không tìm thấy phiếu trên máy.")
        }
    }

    /// "Lấy bản máy chủ": replaces the device copy with the server's. The caller backs the device copy up first.
    /// The record not being on the server any more (404) is the "deleted" case of the conflict dialog.
    @discardableResult
    public func takeServer(projectId: String, recordId: String) async throws -> Record {
        _ = try projectOf(projectId)
        guard let local = records.load(recordId) else { throw SyncRecordFailure("Không tìm thấy phiếu trên máy.") }
        guard let server = try await remote.get(projectId: projectId, recordId: recordId) else { throw SyncRecordFailure("Phiếu đã bị xoá trên máy chủ.") }
        let r = SyncApply.overwrite(local, with: try SyncApply.record(from: server, projectId: projectId))
        try records.save(r)
        records.pruneMedia(r)
        await fetchMedia(projectId, r, info: server.media, includeVideo: false)
        return r
    }
}
