import Foundation
import PollMasterCore

/// Who may edit what, and the user's answers to a sync conflict (ux-spec N).
extension AppEnvironment {
    var currentUserId: String? { account?.userId }

    /// nil when the signed-in user may edit the record; else the banner text of its view-only state.
    func readOnlyNote(for r: Record) -> String? {
        let p = project(id: r.projectId)
        if RecordAccess.canEdit(r, me: currentUserId, project: p) { return nil }
        if p?.canPost == false { return "Bạn chỉ có quyền xem dự án này — phiếu chỉ xem" }
        return "Phiếu của \(r.createdByName ?? "người khác") — chỉ xem"
    }

    func canEdit(_ s: RecordSummary) -> Bool {
        RecordAccess.canEdit(createdBy: s.createdBy, me: currentUserId, canPost: project(id: s.projectId)?.canPost ?? true,
                             canManage: project(id: s.projectId)?.canManage ?? false)
    }

    func canPost(to projectId: String) -> Bool { project(id: projectId)?.canPost ?? true }

    /// Open editors follow permission changes that arrive with a sync.
    func refreshEditorAccess() {
        for m in editors.values { m.setReadOnly(readOnlyNote(for: m.record)) }
    }

    /// Projects whose rights differ from what the lists still show (a sync stored new ones).
    func projectRightsChanged() -> Bool {
        projectStore.list().contains { p in
            projects.first { $0.id == p.id }.map { $0.canPost != p.canPost || $0.canManage != p.canManage } ?? false
        }
    }

    // MARK: conflict

    /// What the dialog says; nil when the server could not be asked (offline).
    func conflictInfo(of recordId: String) async -> ConflictInfo? {
        guard let pid = recordStore.load(recordId)?.projectId else { return nil }
        return try? await makeSyncEngine().inspectConflict(projectId: pid, recordId: recordId)
    }

    private func syncFailureText(_ error: Error) -> String {
        error is URLError ? "Không kết nối được máy chủ. Thử lại khi có mạng." : error.localizedDescription
    }

    /// "Giữ bản của tôi". Returns an error sentence, nil when done.
    func keepMine(_ recordId: String) async -> String? {
        editors[recordId]?.flush()
        guard let pid = recordStore.load(recordId)?.projectId else { return "Không tìm thấy phiếu trên máy." }
        do { try await makeSyncEngine().keepMine(projectId: pid, recordId: recordId) } catch { return syncFailureText(error) }
        if let r = recordStore.load(recordId) { upsert(r) }
        return nil
    }

    /// "Lấy bản máy chủ": the device version is zipped first, then replaced. Returns an error sentence, nil when done.
    func takeServer(_ recordId: String) async -> String? {
        editors[recordId]?.flush()
        guard let local = recordStore.load(recordId), let pid = local.projectId,
              let t = templateStore.load(id: local.templateId, version: local.templateVersion) else { return "Không tìm thấy phiếu trên máy." }
        do { try await backups.writeConflictCopy(t, local, project: project(id: pid)) }
        catch { return "Không sao lưu được bản trên máy: \(error.localizedDescription). Bản máy chủ chưa được lấy." }
        do {
            let taken = try await makeSyncEngine().takeServer(projectId: pid, recordId: recordId)
            editors[recordId]?.reloadFromStore()
            closingEditors[recordId]?.reloadFromStore()
            upsert(taken)
        } catch { return syncFailureText(error) }
        return nil
    }

    /// "Khôi phục" a record the server lost: it goes up again as a new one.
    func restoreOnServer(_ recordId: String) {
        _ = updateRecord(recordId) { $0.serverRev = nil; $0.syncConflict = false; return true }
        if let pid = recordStore.load(recordId)?.projectId { requestRecordSync(project: pid) }
    }
}
