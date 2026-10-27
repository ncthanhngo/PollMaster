import Foundation

/// How a record changes when a server copy arrives or a push succeeds. Pure; the engine and the open editor share it.
public enum SyncApply {
    /// Keys that describe the device, not the survey: left out of what is pushed.
    private static let deviceOnly = ["erpUploadedAt", "serverRev", "syncConflict", "createdBy", "createdByName", "backupAt", "lastPosition"]

    /// A clean local record from a server copy (`erpUploadedAt = updatedAt`).
    public static func record(from s: SyncRecord, projectId: String) throws -> Record {
        let bytes = try JSONCoding.makeEncoder(pretty: false).encode(s.data)
        var r = try JSONCoding.makeDecoder().decode(Record.self, from: bytes)
        guard r.id == s.recordId else { throw SyncRecordFailure("Phiếu trên máy chủ có mã không khớp.") }
        r.projectId = projectId
        r.seq = s.seq ?? r.seq
        r.serverRev = s.rev
        r.createdBy = s.createdBy; r.createdByName = s.createdByName
        r.syncConflict = false
        r.backupAt = nil; r.lastPosition = nil
        r.erpUploadedAt = r.updatedAt
        return r
    }

    /// The server copy replaces a clean local record; where the user last worked and the backup stamp stay.
    public static func overwrite(_ local: Record, with server: Record) -> Record {
        var r = server
        r.lastPosition = local.lastPosition
        r.backupAt = local.backupAt
        return r
    }

    /// record.json as sent to the server.
    public static func pushData(_ r: Record) throws -> JSONValue {
        let bytes = try JSONCoding.makeEncoder(pretty: false).encode(r)
        guard case .object(var o) = try JSONCoding.makeDecoder().decode(JSONValue.self, from: bytes) else { throw SyncRecordFailure("Không đọc được phiếu.") }
        deviceOnly.forEach { o[$0] = nil }
        return .object(o)
    }

    /// After the server took a push that started at `at`: remember rev, number and creator. The stamp is a second early because
    /// `updatedAt` is stored to the second: an edit made during the push must still count as newer.
    /// Returns false (record untouched) when it was moved to another project meanwhile.
    @discardableResult
    public static func pushed(_ r: inout Record, projectId: String, response s: SyncRecord, at: Date) -> Bool {
        guard r.projectId == projectId else { return false }
        r.serverRev = s.rev
        if let seq = s.seq { r.seq = seq }
        r.createdBy = s.createdBy ?? r.createdBy
        r.createdByName = s.createdByName ?? r.createdByName
        r.syncConflict = false
        r.erpUploadedAt = at.addingTimeInterval(-1)
        return true
    }
}
