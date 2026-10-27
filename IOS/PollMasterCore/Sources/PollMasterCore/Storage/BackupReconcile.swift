import Foundation

/// A backup zip is written from a snapshot while the user may keep working: what to do with the stored record afterwards.
public enum BackupReconcile {
    /// The record to save with `backupAt` stamped, or nil when it must stay untouched (and so keeps counting as "Chưa sao lưu"):
    /// deleted meanwhile, edited (`updatedAt`), moved to another project, or an editor session opened it (it holds its own copy).
    public static func stamped(snapshot: Record, current: Record?, editorOpen: Bool, now: Date = Date()) -> Record? {
        guard !editorOpen, var r = current, r.updatedAt == snapshot.updatedAt, r.projectId == snapshot.projectId else { return nil }
        r.backupAt = max(now, snapshot.updatedAt)
        return r
    }
}
