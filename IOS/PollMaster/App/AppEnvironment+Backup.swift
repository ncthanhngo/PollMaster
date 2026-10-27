import Foundation
import PollMasterCore

extension AppEnvironment {
    /// Backs up records now (open editors through their model so no edit is lost); `ids` nil = every readable record of the list.
    /// A record changed, moved or deleted while its zip was written is not stamped (see `BackupReconcile`), so it stays "Chưa sao lưu".
    func backupAll(ids: [String]? = nil) async -> (done: Int, failed: Int) {
        let ids = ids ?? summaries.filter { $0.loadError == nil }.map(\.id)
        var done = 0, failed = 0
        let (records, templates, projects) = (recordStore, templateStore, projectStore)
        for id in ids {
            if let m = editors[id] { if await m.backup(force: true) { done += 1 } else { failed += 1 }; continue }
            let loaded = await Task.detached { () -> (Record, Template, Project?)? in
                guard let r = records.load(id), let t = templates.load(id: r.templateId, version: r.templateVersion) else { return nil }
                return (r, t, r.projectId.flatMap(projects.load))
            }.value
            guard let (snapshot, template, project) = loaded else { failed += 1; continue }
            do { try await backups.write(template, snapshot, project: project) } catch { failed += 1; continue }
            done += 1
            let current = recordStore.load(id)      // no await until the save: nothing can edit the record in between
            if let stamped = BackupReconcile.stamped(snapshot: snapshot, current: current, editorOpen: editors[id] != nil) {
                if (try? recordStore.save(stamped)) != nil { upsert(stamped) }
            } else if current != nil, current?.projectId != snapshot.projectId {
                BackupService.removeBackup(template, snapshot, project: project)       // moved meanwhile: the copy sits in the old folder
            }
        }
        return (done, failed)
    }

    /// App going to the background: save and back up whatever is open and changed.
    func backupOpenEditors() {
        flushEditors()
        guard settings.autoBackup else { return }
        let open = Array(editors.values)
        BackupService.whileBackgrounded {
            for m in open { await m.backup() }
        }
    }
}
