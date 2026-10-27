import Foundation

/// Who may do what with a record, and how its sync state reads (schema §10.4, ux-spec N). Pure; shared vectors on both platforms.
public enum RecordAccess {
    /// `canPost ∧ (createdBy absent ∨ mine ∨ canManage)`. A record without `createdBy` was made on this device by the signed-in user.
    public static func canEdit(createdBy: String?, me: String?, canPost: Bool, canManage: Bool) -> Bool {
        canPost && (createdBy == nil || createdBy == me || canManage)
    }

    public static func canEdit(_ r: Record, me: String?, project: Project?) -> Bool {
        canEdit(createdBy: r.createdBy, me: me, canPost: project?.canPost ?? true, canManage: project?.canManage ?? false)
    }

    /// A record with unsent changes of another account (left on a shared device): hidden from the list unless the signed-in user
    /// manages the project.
    public static func isHiddenLeftover(createdBy: String?, me: String?, dirty: Bool, canManage: Bool) -> Bool {
        dirty && createdBy != nil && createdBy != me && !canManage
    }
}

/// The sync label of a record card (ux-spec N): conflict, then error, then waiting, else synced.
public enum RecordSyncState: Equatable, Sendable {
    case synced(Date?)
    case pending
    case conflict
    case failed(String)

    public static func of(dirty: Bool, conflict: Bool, failure: String?, syncedAt: Date?) -> RecordSyncState {
        if conflict { return .conflict }
        if let failure { return .failed(failure) }
        return dirty ? .pending : .synced(syncedAt)
    }

    public static func of(_ r: Record, failure: String?) -> RecordSyncState {
        of(dirty: r.isSyncDirty, conflict: r.syncConflict, failure: failure, syncedAt: r.erpUploadedAt)
    }

    /// "Đã đồng bộ dd-MM HH:mm" · "Chờ đồng bộ" · "Xung đột" · "Lỗi: …"
    public func text(timeZone: TimeZone = .current) -> String {
        switch self {
        case .synced(let at): at.map { "Đã đồng bộ " + ISODate.format($0, "dd-MM HH:mm", timeZone: timeZone) } ?? "Đã đồng bộ"
        case .pending: "Chờ đồng bộ"
        case .conflict: "Xung đột"
        case .failed(let m): "Lỗi: " + m
        }
    }

    /// Stable name used by the shared vectors.
    public var kind: String {
        switch self { case .synced: "synced"; case .pending: "pending"; case .conflict: "conflict"; case .failed: "failed" }
    }
}
