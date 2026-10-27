import Foundation
import PollMasterCore

/// Record sync with the ERP (schema §10, phase 05): triggers, one run at a time, and the glue to open editors and the PDF maker.
extension AppEnvironment {
    private static let lastUserKey = "lastSyncUserId"

    /// Asks for a sync run of one project, or of all. Requests made during a run are folded into the next one; offline or signed out: ignored.
    func requestRecordSync(project: String? = nil) {
        guard account != nil else { return }
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "pmAccount") { return }
        #endif
        if let project { queuedSyncProjects.insert(project) } else { queuedSyncAll = true }
        guard recordSyncTask == nil else { return }
        let gen = sessionGen
        recordSyncTask = Task { await runRecordSync(gen) }
    }

    func cancelRecordSync() {
        recordSyncTask?.cancel(); recordSyncTask = nil
        queuedSyncProjects = []; queuedSyncAll = false
        syncProgress = nil
    }

    private func runRecordSync(_ gen: Int) async {
        while gen == sessionGen, !Task.isCancelled, queuedSyncAll || !queuedSyncProjects.isEmpty {
            let only: Set<String>? = queuedSyncAll ? nil : queuedSyncProjects
            queuedSyncAll = false; queuedSyncProjects = []
            await syncRecordsOnce(gen, only: only)
        }
        guard gen == sessionGen else { return }
        recordSyncTask = nil
        syncProgress = nil
    }

    private func syncRecordsOnce(_ gen: Int, only: Set<String>?) async {
        guard var acct = account, network.online else { return }
        flushEditors()
        do {
            if acct.userId == nil {
                acct.userId = try await erp.me()
                guard gen == sessionGen else { return }
                updateAccountDetails(acct)
                reload()                       // the list hides other accounts' leftovers by user id
            }
            guard let me = acct.userId else { return }
            let engine = makeSyncEngine()
            let all = projectStore.list()
            let removed = await engine.prune(me: me, lastUserId: UserDefaults.standard.string(forKey: Self.lastUserKey),
                                             lostProjects: Set(all.filter(\.archived).map(\.id)))
            UserDefaults.standard.set(me, forKey: Self.lastUserKey)
            let ids = all.filter { !$0.archived && (only?.contains($0.id) ?? true) }.map(\.id)
            let reports = try await engine.sync(projects: ids, me: me)
            guard gen == sessionGen else { return }
            var issues = recordSyncIssues
            for (pid, rep) in reports {
                let mine = Set(recordStore.list().filter { $0.projectId == pid }.map(\.id))
                issues = issues.filter { !mine.contains($0.key) }
                issues.merge(rep.failures) { _, new in new }
            }
            recordSyncIssues = issues
            if !removed.isEmpty || projectRightsChanged()
                || reports.values.contains(where: { $0.created + $0.overwritten + $0.deletedLocal + $0.pushed + $0.conflicts > 0 }) { reload() }
        } catch ERPError.sessionExpired {
            guard gen == sessionGen else { return }
            setSessionExpired()
        } catch is CancellationError {
        } catch is URLError {          // offline or timed out: the next trigger tries again
        } catch {
            guard gen == sessionGen else { return }
            syncError = "Chưa đồng bộ được phiếu: \(error.localizedDescription)"
        }
    }

    func makeSyncEngine() -> SyncEngine {
        let hooks = SyncHooks(
            isOpen: { [weak self] id in await self?.isEditorOpen(id) ?? false },
            update: { [weak self] id, change in await self?.updateRecord(id, change) },
            makePDF: { [weak self] record in await self?.makePDF(record) },
            progress: { [weak self] done, total in
                Task { @MainActor in self?.syncProgress = done < total ? SyncProgress(done: done, total: total) : nil }
            })
        return SyncEngine(root: root, remote: ERPSyncRemote(client: erp), hooks: hooks)
    }

    func isEditorOpen(_ id: String) -> Bool { editors[id] != nil || closingEditors[id] != nil }

    /// Bookkeeping change to the current copy of a record: the open editor's, else the stored file (list refreshed).
    func updateRecord(_ id: String, _ change: @Sendable (inout Record) -> Bool) -> Record? {
        if let m = editors[id] ?? closingEditors[id], !m.discarded { return m.applySync(change) }
        guard var r = recordStore.load(id) else { return nil }
        if change(&r) {
            guard (try? recordStore.save(r)) != nil else { return r }
            upsert(r)
        }
        return r
    }

    /// PDF for the web view of a pushed record; nil when it cannot be made (the push itself is not affected).
    private func makePDF(_ record: Record) async -> URL? {
        guard let template = templateStore.load(id: record.templateId, version: record.templateVersion) else { return nil }
        let exporter = RecordExporter(store: recordStore), project = self.project(of: record)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("erp-send/\(UUID().uuidString)")
        let files = try? await Task.detached(priority: .utility) {
            try exporter.export(template, record, formats: [.pdf], into: dir, project: project)
        }.value
        return files?.first { $0.pathExtension == "pdf" }
    }

    /// A record opened in the editor: whatever is still missing from the server copy (videos included) is fetched.
    func fetchMediaOnOpen(_ record: Record) {
        guard account != nil, network.online, record.serverRev != nil, let pid = record.projectId else { return }
        let engine = makeSyncEngine(), id = record.id
        Task { await engine.downloadMedia(projectId: pid, recordId: id) }
    }

    /// The device copy was deleted: the server copy goes too (queued until there is a network).
    func queueRemoteDelete(_ r: Record) {
        guard r.serverRev != nil || r.erpUploadedAt != nil, let pid = r.projectId else { return }
        try? PendingDeletes(root: root).add(PendingDelete(projectId: pid, recordId: r.id))
        requestRecordSync(project: pid)
    }
}
