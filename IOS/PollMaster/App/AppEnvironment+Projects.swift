import Foundation
import UIKit
import PollMasterCore

/// A `.pmproject` read from disk: what the preview sheet shows, or why it cannot be imported.
struct IncomingBundle: Identifiable {
    let id = UUID()
    var bundle: ProjectBundle?
    var plan: ProjectImporter.Plan?
    var errors: [String] = []
}

extension AppEnvironment {
    // MARK: lookups

    var activeProjects: [Project] { projects.filter { !$0.archived } }
    var currentProject: Project? { currentProjectId.flatMap { id in projects.first { $0.id == id } } }

    /// Project by id; a missing id means "Chung".
    func project(id: String?) -> Project? {
        if let id { return projects.first { $0.id == id } }
        return projects.last(where: \.isGeneral)
    }

    func project(of r: Record) -> Project? { project(id: r.projectId) }

    /// Planned sites of the current project (all active projects, codes de-duplicated, for "Tất cả dự án").
    var sites: [Site] { sites(forProject: currentProjectId) }
    var visitedSiteCodes: Set<String> { visitedCodes(forProject: currentProjectId) }

    func sites(forProject id: String?) -> [Site] {
        if let id { return siteCache[id] ?? [] }
        var seen = Set<String>()
        return activeProjects.flatMap { siteCache[$0.id] ?? [] }.filter { seen.insert($0.code).inserted }
    }

    func visitedCodes(forProject id: String?) -> Set<String> {
        if let id { return visitedByProject[id] ?? [] }
        return visitedByProject.values.reduce(into: Set<String>()) { $0.formUnion($1) }
    }

    /// Records of the current project (everything for "Tất cả dự án").
    var projectSummaries: [RecordSummary] {
        guard let id = currentProjectId else { return summaries }
        return summaries.filter { $0.projectId == id }
    }

    func stats(of p: Project) -> ProjectStats { projectStats[p.id] ?? ProjectStats() }

    func template(_ ref: TemplateRef) -> Template? { templates.first { $0.id == ref.id && $0.version == ref.version } }
    func templates(of p: Project) -> [Template] { p.templates.compactMap(template) }

    // MARK: project CRUD

    @discardableResult
    func saveProject(_ p: Project, isNew: Bool) -> Project? {
        do {
            let saved = isNew ? try projectStore.create(p) : try projectStore.update(p)
            reload()
            return saved
        } catch { errorMessage = error.localizedDescription; return nil }
    }

    func setArchived(_ p: Project, _ archived: Bool) {
        do { try projectStore.setArchived(p.id, archived); reload() } catch { errorMessage = error.localizedDescription }
    }

    /// Only an empty project can be deleted; otherwise explains what to do instead.
    @discardableResult
    /// Square-cropped, 256 px JPEG of the chosen photo; nil removes it (back to initials).
    func setAvatar(_ image: UIImage?, for p: Project) {
        do {
            if let image, let jpeg = image.squareThumbnail(256).jpegData(compressionQuality: 0.85) {
                try projectStore.setAvatar(jpeg, for: p.id)
            } else {
                try projectStore.removeAvatar(p.id)
            }
            reload()
        } catch { errorMessage = error.localizedDescription }
    }

    func deleteProject(_ p: Project) -> Bool {
        do {
            try projectStore.delete(p.id, recordCount: stats(of: p).total)
            if currentProjectId == p.id { currentProjectId = nil }
            reload()
            return true
        } catch { errorMessage = error.localizedDescription; return false }
    }

    func addTemplates(_ list: [Template], to p: Project) {
        do { for t in list { try projectStore.addTemplate(TemplateRef(t), to: p.id) }; reload() } catch { errorMessage = error.localizedDescription }
    }

    func removeTemplate(_ ref: TemplateRef, from p: Project) {
        do { try projectStore.removeTemplate(ref, from: p.id); reload() } catch { errorMessage = error.localizedDescription }
    }

    func useVersion(_ ref: TemplateRef, in p: Project) {
        do { try projectStore.useVersion(ref, in: p.id); reload() } catch { errorMessage = error.localizedDescription }
    }

    func newerVersions(for p: Project) -> [TemplateRef] { ProjectTemplates.newerVersions(of: p, library: templates) }
    func projects(using t: Template) -> [Project] { ProjectTemplates.projects(using: t.id, in: projects) }

    // MARK: sites

    /// Replaces the project's planned-site list with a CSV picked by the user (security-scoped URL). Returns the number of sites.
    func importSites(from url: URL, projectId: String?) throws -> Int {
        guard let target = project(id: projectId) ?? currentProject else { throw ProjectError.notFound }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let list = try SitesCSV.parse(Data(contentsOf: url))
        try projectStore.sites(target.id).save(list)
        reload()
        return list.count
    }

    func clearSites(of p: Project) throws {
        try projectStore.sites(p.id).clear()
        reload()
    }

    // MARK: move

    /// Another active project of the same template, for "Chuyển dự án".
    func moveTargets(for summary: RecordSummary) -> [Project] {
        guard let r = recordStore.load(summary.id) else { return [] }
        return ProjectMove.targets(for: r, in: projects, currentId: summary.projectId)
    }

    func move(_ recordId: String, to target: Project) {
        editors[recordId]?.flush()
        guard var r = recordStore.load(recordId) else { return }
        let old = project(of: r)
        editors[recordId] = nil
        r.projectId = target.id; r.updatedAt = Date(); r.backupAt = nil
        do {
            try recordStore.save(r)
            if let t = templateStore.load(id: r.templateId, version: r.templateVersion) { BackupService.removeBackup(t, r, project: old) }
            reload()
            infoMessage = "Đã chuyển phiếu sang dự án \(target.name)."
        } catch { errorMessage = "Không chuyển được phiếu: \(error.localizedDescription)" }
    }

    // MARK: bundles

    /// Builds `<CODE>.pmproject` in a temporary folder, ready to share.
    func bundleFile(for p: Project) throws -> URL {
        let bundle = try ProjectBundle.make(p, templates: templateStore, sites: projectStore.sites(p.id).load())
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exports/\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(ProjectBundle.fileName(for: p))
        try bundle.write(to: url)
        return url
    }

    /// A `.pmproject` handed to the app (opened from Files/Zalo/Mail or picked): read it and show the preview.
    func openIncoming(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let bundle = try ProjectBundle.read(from: url)
            incomingBundle = IncomingBundle(bundle: bundle, plan: ProjectImporter(root: root).plan(bundle))
        } catch {
            incomingBundle = IncomingBundle(errors: TemplateText.errorLines(error).flatMap { $0.components(separatedBy: "\n") }.filter { !$0.isEmpty })
        }
        selectedTab = .projects
    }

    func importIncoming(overwriteTemplates: Bool) {
        guard let bundle = incomingBundle?.bundle else { return }
        do {
            let r = try ProjectImporter(root: root).apply(bundle, overwriteTemplates: overwriteTemplates)
            incomingBundle = nil
            reload()
            currentProjectId = r.project.id
            var note = r.updated ? "Đã cập nhật dự án \(r.project.name)." : "Đã nhập dự án \(r.project.name) (\(r.project.code))."
            if !r.skippedConflicts.isEmpty { note += " Giữ nguyên \(r.skippedConflicts.count) mẫu đã có trên máy." }
            if !r.droppedRefs.isEmpty { note += " Bỏ \(r.droppedRefs.count) mẫu không có trong gói." }
            infoMessage = note
        } catch { incomingBundle = IncomingBundle(errors: [error.localizedDescription]) }
    }
}

extension UIImage {
    /// Centre square crop scaled to `side` points (scale 1).
    func squareThumbnail(_ side: CGFloat) -> UIImage {
        let edge = min(size.width, size.height)
        let crop = CGRect(x: (size.width - edge) / 2, y: (size.height - edge) / 2, width: edge, height: edge)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            let k = side / edge
            draw(in: CGRect(x: -crop.minX * k, y: -crop.minY * k, width: size.width * k, height: size.height * k))
        }
    }
}
