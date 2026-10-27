import Foundation
import UIKit
import PollMasterCore

/// Safety copies in `Documents/PollMaster Backup/`, visible in the Files app (UIFileSharingEnabled).
struct BackupService: Sendable {
    let store: RecordStore

    static var directory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("PollMaster Backup", isDirectory: true)
    }

    /// `PollMaster Backup/{projectCode}/` (the root folder when the project is unknown).
    static func directory(for project: Project?) -> URL {
        project.map { directory.appendingPathComponent($0.code, isDirectory: true) } ?? directory
    }

    /// Writes `{projectCode}_{base}_backup.zip` off the main thread.
    func write(_ t: Template, _ r: Record, project: Project?) async throws {
        let store = store
        try await Task.detached(priority: .utility) {
            _ = try RecordExporter(store: store).writeBackup(t, r, into: Self.directory(for: project), project: project)
        }.value
    }

    /// The device version kept next to the safety copies when the user takes the server's (a file of its own, never replaced).
    func writeConflictCopy(_ t: Template, _ r: Record, project: Project?) async throws {
        let store = store
        try await Task.detached(priority: .userInitiated) {
            _ = try RecordExporter(store: store).writeBackup(t, r, into: Self.directory(for: project), project: project,
                                                             fileName: RecordExporter.conflictCopyFileName(t, r, projectCode: project?.code))
        }.value
    }

    /// Drops the copy of a record from its project's folder (the record moved to another project).
    static func removeBackup(_ t: Template, _ r: Record, project: Project?) {
        let file = directory(for: project).appendingPathComponent(RecordExporter.backupFileName(t, r, projectCode: project?.code))
        try? FileManager.default.removeItem(at: file)
    }

    /// `backupAt` for a copy made from a snapshot: the snapshot's edit time when the record changed meanwhile,
    /// so the record still counts as "Chưa sao lưu".
    static func backupTime(snapshot: Date, currentUpdatedAt: Date, now: Date = Date()) -> Date {
        currentUpdatedAt <= snapshot ? max(now, snapshot) : snapshot
    }

    /// Keeps the process alive a little while the app moves to the background.
    @MainActor static func whileBackgrounded(_ work: @escaping @MainActor () async -> Void) {
        let box = TaskBox()
        box.id = UIApplication.shared.beginBackgroundTask(withName: "backup") {
            MainActor.assumeIsolated { box.end() }
        }
        Task {
            await work()
            box.end()
        }
    }
}

/// Background task id shared between the expiration handler and the finishing task.
@MainActor private final class TaskBox {
    var id = UIBackgroundTaskIdentifier.invalid
    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
