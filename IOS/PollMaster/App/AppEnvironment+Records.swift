import Foundation
import PollMasterCore

extension AppEnvironment {
    // MARK: records

    /// Entry point of every "create record" button: asks for the surveyor name once when Cài đặt has none, then creates.
    func startCreate(from t: Template, in project: Project) {
        guard project.canPost else { infoMessage = "Bạn chỉ có quyền xem dự án \(project.name), không tạo được phiếu."; return }
        if settings.surveyorName.isEmpty, !settings.surveyorAsked, t.field("HD-surveyor") != nil { askSurveyorFor = PendingCreate(template: t, project: project) }
        else { createRecord(from: t, in: project) }
    }

    func answerSurveyor(_ name: String) {
        guard let pending = askSurveyorFor else { return }
        askSurveyorFor = nil
        settings.surveyorAsked = true
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { settings.surveyorName = trimmed }
        createRecord(from: pending.template, in: pending.project)
    }

    /// Who a record made now belongs to: the signed-in account (its user id may still be unknown on an old session).
    var creator: RecordFactory.Creator { RecordFactory.Creator(id: account?.userId, name: account?.name) }

    /// The record's own project is scanned once (off the main actor) for the next record number and code.
    func createRecord(from t: Template, in project: Project) {
        guard !creatingRecord else { return }
        creatingRecord = true
        var r = RecordFactory.make(template: t, surveyorName: settings.surveyorName, projectId: project.id, creator: creator)
        let store = recordStore, pid = project.id
        Task {
            defer { creatingRecord = false }
            let mine = await Task.detached { store.list().filter { ($0.projectId ?? "") == pid } }.value
            r.seq = RecordNaming.nextSeq(among: mine, projectId: pid, templateId: t.id)
            if let code = t.recordCodeField, r.values[code.id]?.v?.isBlank != false,
               let proposed = RecordCodeProposal.propose(for: t, among: mine) { r.values[code.id] = FieldValue(v: .string(proposed)) }
            do {
                try recordStore.save(r)
                upsert(r)
                open(r.id)
                requestRecordSync(project: project.id)
            } catch { errorMessage = "Không tạo được phiếu: \(error.localizedDescription)" }
        }
    }

    func open(_ recordId: String) {
        selectedTab = .records
        recordsPath = [.editor(recordId)]
    }

    func duplicate(_ id: String) {
        guard let r = recordStore.load(id), canPost(to: r.projectId ?? "") else { return }
        let t = templateStore.load(id: r.templateId, version: r.templateVersion)
        var copy = RecordFactory.duplicate(r, template: t, creator: creator)
        copy.seq = RecordNaming.nextSeq(among: recordStore.list(), projectId: copy.projectId, templateId: copy.templateId)
        do { try recordStore.save(copy); upsert(copy) }
        catch { errorMessage = "Không nhân bản được: \(error.localizedDescription)" }
    }

    func delete(_ id: String) {
        if let r = recordStore.load(id), readOnlyNote(for: r) != nil { return }        // someone else's record: only its owner or a manager deletes
        editors[id]?.discard(); closingEditors[id]?.discard()      // a pending autosave must not bring the folder back
        editors[id] = nil; closingEditors[id] = nil
        let old = recordStore.load(id)
        do {
            try recordStore.delete(id); removeSummary(id)
            if let old { queueRemoteDelete(old) }
        } catch { errorMessage = "Không xóa được: \(error.localizedDescription)" }
    }

    func editor(for id: String) -> RecordEditorModel? {
        if let m = editors[id] { return m }
        let onStack = recordsPath.contains { $0.recordId == id }
        if let m = closingEditors[id] {
            // a screen sliding out keeps its (closed) model; opening the record again reuses it, so there is never a second model
            guard onStack, !m.discarded else { return m }
            m.closed = false
            closingEditors[id] = nil; editors[id] = m
            return m
        }
        guard onStack else { return nil }     // a screen that is leaving must not reopen it
        guard let r = recordStore.load(id), let t = templateStore.load(id: r.templateId, version: r.templateVersion) else { return nil }
        let m = RecordEditorModel(template: t, record: r, store: recordStore, location: location, settings: settings, backups: backups,
                              sites: { [unowned self] in self.sites(forProject: r.projectId) },
                              project: { [unowned self] pid in self.project(id: pid) })
        m.onSaved = { [weak self] saved in self?.upsert(saved) }
        m.setReadOnly(readOnlyNote(for: r))
        editors[id] = m
        fetchMediaOnOpen(r)
        return m
    }

    /// Editor models live while any route of their record is on the stack; the rest are saved and dropped.
    func releaseClosedEditors() {
        let open = Set(recordsPath.map(\.recordId))
        closingEditors = [:]
        for (id, m) in editors where !open.contains(id) {
            m.close()
            editors[id] = nil
            closingEditors[id] = m
            requestRecordSync(project: m.record.projectId)
        }
    }
}
