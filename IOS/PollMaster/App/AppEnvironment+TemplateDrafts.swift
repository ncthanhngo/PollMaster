import Foundation
import PollMasterCore

/// Editing templates on the device (schema §11): drafts live in `template-drafts/`; releasing goes through `TemplateSync.publish`.
extension AppEnvironment {
    var draftStore: TemplateDraftStore { TemplateDraftStore(root: root) }

    private static func now() -> String { ISO8601DateFormatter().string(from: Date()) }

    func reloadDrafts() {
        let store = draftStore
        Task { templateDrafts = await Task.detached { store.list() }.value }
    }

    /// Projects the user manages that use template `id` (nil = any template): where an edit can be released.
    func managedProjects(using id: String?) -> [Project] { TemplatePublishRules.manageable(using: id, in: activeProjects) }

    /// "Sửa nội dung": signed in = manages a project using it (offline is fine, release needs the network); signed out = this device's list is free.
    func canDraft(_ t: Template) -> Bool { account == nil || !managedProjects(using: t.id).isEmpty }

    var canCreateTemplate: Bool { account == nil || !managedProjects(using: nil).isEmpty }

    func draft(forTemplateId id: String) -> TemplateDraft? { templateDrafts.first { $0.templateId == id } }

    /// Draft the release of an edit goes to: the open project when it qualifies, else the first managed project using the template.
    private func defaultProject(using id: String?) -> Project? {
        let pool = account == nil ? activeProjects.filter { id == nil || $0.templates.contains { $0.id == id } } + activeProjects : managedProjects(using: id)
        return pool.first { $0.id == currentProjectId } ?? pool.first
    }

    /// The device's draft for this template, or a new one from the installed version.
    @discardableResult
    func startDraft(from t: Template) throws -> TemplateDraft {
        if let d = draft(forTemplateId: t.id) ?? draftStore.existing(forTemplateId: t.id) { return d }
        guard let project = defaultProject(using: t.id) else { throw TemplateSyncFailure("Cần có ít nhất một dự án để sửa mẫu.") }
        guard let data = templateStore.data(id: t.id, version: t.version), let text = String(data: data, encoding: .utf8) else { throw TemplateSyncFailure("Mẫu không có trên máy.") }
        let d = TemplateDraft.editing(id: t.id, version: t.version, content: try TemplateDoc(text: text).root, projectId: project.id, now: Self.now())
        try draftStore.save(d)
        templateDrafts.insert(d, at: 0)
        return d
    }

    /// "Tạo mẫu mới": empty, or a copy of `source`; `project` is where it will be released.
    @discardableResult
    func newDraft(cloning source: Template? = nil, project: Project) throws -> TemplateDraft {
        let taken = Set(templates.map(\.id))
        var d = TemplateDraft.blank(projectId: project.id, taken: taken, now: Self.now())
        if let source, let data = templateStore.data(id: source.id, version: source.version), let text = String(data: data, encoding: .utf8) {
            d = TemplateDraft.clone(of: try TemplateDoc(text: text).root, projectId: project.id, taken: taken, now: Self.now())
        }
        try draftStore.save(d)
        templateDrafts.insert(d, at: 0)
        return d
    }

    /// Writes the draft (the editor calls it after every change, debounced).
    func saveDraft(_ d: TemplateDraft) {
        var d = d
        d.updatedAt = Self.now()
        do { try draftStore.save(d) } catch { errorMessage = error.localizedDescription; return }
        templateDrafts.removeAll { $0.draftId == d.draftId }
        templateDrafts.insert(d, at: 0)
    }

    func discardDraft(_ draftId: String) {
        do { try draftStore.delete(draftId) } catch { errorMessage = error.localizedDescription }
        templateDrafts.removeAll { $0.draftId == draftId }
    }

    /// Managed projects a new template can go to.
    var publishTargets: [Project] { account == nil ? activeProjects : managedProjects(using: nil) }

    /// Other managed projects on the same template (ticked by default in the release sheet).
    func otherProjects(for d: TemplateDraft, excluding target: Project) -> [Project] {
        TemplatePublishRules.otherProjects(using: d.templateId, excluding: target.id, in: activeProjects)
    }

    /// Releases the draft into `target` (and moves `also` along). Needs the network and `canManage` when signed in; signed out it is local.
    func publishDraft(_ d: TemplateDraft, to target: Project, also: [String], force: Bool) async throws -> PublishOutcome {
        let gen = sessionGen
        defer { if gen == sessionGen { reload() } }
        if account == nil { return try makeTemplateSync().publishLocally(d, to: target.id) }
        guard canChangeTemplates(in: target) else { throw TemplateSyncFailure(Self.templateNeedsManage + ".") }
        do { return try await makeTemplateSync().publish(d, to: target.id, also: also, force: force) }
        catch ERPError.sessionExpired { setSessionExpired(); throw ERPError.sessionExpired }
        catch is URLError { throw TemplateSyncFailure("Không kết nối được máy chủ ERP. Thử lại khi có mạng.") }
    }

    /// Version older than `ref` on this device ("Quay lại bản trước"); nil hides the button.
    func previousVersion(of ref: TemplateRef) -> TemplateRef? {
        TemplatePublishRules.previousVersion(of: ref.version, among: templates.filter { $0.id == ref.id }.map(\.version)).map { TemplateRef(id: ref.id, version: $0) }
    }

    /// Switches project `p` back to the previous version of `ref` (through the server when signed in).
    func rollBackTemplate(_ ref: TemplateRef, in p: Project) {
        guard let previous = previousVersion(of: ref) else { infoMessage = "Mẫu này chưa có bản trước trên máy."; return }
        useVersion(previous, in: p)
    }
}
