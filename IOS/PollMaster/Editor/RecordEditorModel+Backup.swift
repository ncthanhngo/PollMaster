import Foundation
import PollMasterCore

extension RecordEditorModel {
    /// Writes the folder backup of the current state; `force` also when nothing changed since the last one (and waits for one already
    /// running). True when a copy was written; false when none was needed or it failed (`backupError` says why).
    @discardableResult
    func backup(force: Bool = false) async -> Bool {
        guard !isPreview else { return false }
        if let running = backupTask {
            guard force else { return false }
            _ = await running.value
        }
        guard force || (settings.autoBackup && record.needsBackup) else { return false }
        flush()
        backupError = nil
        let snapshot = record, template = template, project = project(snapshot.projectId), backups = backups
        let task = Task { () -> Bool in
            do { try await backups.write(template, snapshot, project: project); return true } catch {
                self.backupError = "Không sao lưu được: \(error.localizedDescription)"
                return false
            }
        }
        backupTask = task
        let ok = await task.value
        if backupTask == task { backupTask = nil }
        if ok { markBackedUp(snapshot: snapshot) }
        return ok
    }
}
