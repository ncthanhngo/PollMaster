import Foundation
import PollMasterCore

/// Project templates through the ERP (schema §10.6, phase 07): read at every project sync; changes need the network and `canManage`
/// and reach the device only after the server confirmed them. Signed out (no account) the device list is edited locally as before.
extension AppEnvironment {
    static let templateNeedsManage = "Cần quyền quản lý dự án và có mạng"

    /// Newest bundled version of each bundled template (what the Phoenix rule uploads).
    private static let bundledRefs: [TemplateRef] = {
        let urls = Bundle.main.urls(forResourcesWithExtension: "json", subdirectory: "templates") ?? []
        return TemplateVersion.newest(urls.compactMap { (try? Data(contentsOf: $0)).flatMap { try? TemplateLoader.decode($0) } }).map(TemplateRef.init)
    }()

    func makeTemplateSync() -> TemplateSync { TemplateSync(root: root, remote: ERPTemplateRemote(client: erp)) }

    /// Templates of one project before its records are synced; true when the device's templates or list changed.
    func syncTemplates(projectId: String) async throws -> Bool {
        let out = try await makeTemplateSync().sync(projectId: projectId, library: newestTemplates.map(TemplateRef.init), bundled: Self.bundledRefs)
        return out.changed
    }

    /// May the signed-in user change this project's templates now? (nobody is signed in: the device list is free).
    func canChangeTemplates(in p: Project) -> Bool { account == nil || (p.canManage && network.online) }

    /// True when allowed; else tells the user why not.
    func guardTemplates(_ p: Project) -> Bool {
        if canChangeTemplates(in: p) { return true }
        infoMessage = Self.templateNeedsManage
        return false
    }

    /// Projects whose template list holds exactly this template version and that the user manages.
    func manageableProjects(using t: Template) -> [Project] { activeProjects.filter { $0.canManage && $0.templates.contains(TemplateRef(t)) } }

    func canEditTemplate(_ t: Template) -> Bool { account == nil || (network.online && !manageableProjects(using: t).isEmpty) }

    func templateAction(_ work: @escaping @Sendable (TemplateSync) async throws -> Void) {
        let gen = sessionGen
        Task {
            do { try await work(makeTemplateSync()) }
            catch ERPError.sessionExpired { if gen == sessionGen { setSessionExpired() } }
            catch is URLError { errorMessage = "Không kết nối được máy chủ ERP. Thử lại khi có mạng." }
            catch { errorMessage = error.localizedDescription }
            if gen == sessionGen { reload() }
        }
    }

    // MARK: project templates

    func addTemplates(_ list: [Template], to p: Project) {
        guard account != nil else {
            do { for t in list { try projectStore.addTemplate(TemplateRef(t), to: p.id) }; reload() } catch { errorMessage = error.localizedDescription }
            return
        }
        guard guardTemplates(p) else { return }
        let refs = list.map(TemplateRef.init), id = p.id
        templateAction { try await $0.assign(refs, to: id) }
    }

    func removeTemplate(_ ref: TemplateRef, from p: Project) {
        guard account != nil else {
            do { try projectStore.removeTemplate(ref, from: p.id); reload() } catch { errorMessage = error.localizedDescription }
            return
        }
        guard guardTemplates(p) else { return }
        let id = p.id
        templateAction { try await $0.unassign(ref, from: id) }
    }

    func useVersion(_ ref: TemplateRef, in p: Project) {
        guard account != nil else {
            do { try projectStore.useVersion(ref, in: p.id); reload() } catch { errorMessage = error.localizedDescription }
            return
        }
        guard guardTemplates(p) else { return }
        let id = p.id
        templateAction { try await $0.useVersion(ref, in: id) }
    }

    /// Name, short name and colour: through the server (whole organisation library), then back as content. Without an account: this device only.
    func editTemplate(_ t: Template, title: String, shortName: String?, color: String?) async throws {
        guard account != nil else { try editTemplateLocally(t, title: title, shortName: shortName, color: color); return }
        guard network.online, let project = manageableProjects(using: t).first else { throw TemplateSyncFailure(Self.templateNeedsManage + ".") }
        if let data = templateStore.data(id: t.id, version: t.version) { _ = try TemplateText.edited(data, title: title, shortName: shortName, color: color) }   // same checks as the server's
        let name = title.trimmingCharacters(in: .whitespacesAndNewlines), short = shortName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        do { try await makeTemplateSync().editMeta(of: TemplateRef(t), in: project.id, title: name, shortName: short, color: color ?? "") }
        catch ERPError.sessionExpired { setSessionExpired(); throw ERPError.sessionExpired }
        reload()
    }

    /// Sets name, short name and colour on every installed version of the template (records and projects keep their id+version).
    private func editTemplateLocally(_ t: Template, title: String, shortName: String?, color: String?) throws {
        for v in templates where v.id == t.id {
            guard let data = templateStore.data(id: v.id, version: v.version) else { continue }
            try overwriteTemplate(TemplateText.edited(data, title: title, shortName: shortName, color: color))
        }
    }
}
