import Foundation

/// Installs a `.pmproject` (schema §9.3): templates like a normal import, project created or updated, sites replaced.
public struct ProjectImporter: Sendable {
    public let projects: ProjectStore
    public let templates: TemplateStore

    public init(root: URL) {
        projects = ProjectStore(root: root); templates = TemplateStore(root: root)
    }

    public struct Plan: Sendable {
        /// Project with the same id already on the device: the import updates it.
        public var existing: Project?
        /// Same id+version already installed with different content (needs "Ghi đè?").
        public var conflicts: [Template]
        public var newTemplates: [Template]
    }

    public struct Result: Sendable {
        public var project: Project
        public var updated: Bool
        public var installed: [Template]
        public var skippedConflicts: [Template]
        /// References neither in the bundle nor in the library.
        public var droppedRefs: [TemplateRef]
        public var siteCount: Int
    }

    private func sameContent(_ a: Data, _ b: Data) -> Bool {
        guard let x = try? JSONDecoder().decode(JSONValue.self, from: a), let y = try? JSONDecoder().decode(JSONValue.self, from: b) else { return a == b }
        return x == y
    }

    public func plan(_ bundle: ProjectBundle) -> Plan {
        var conflicts: [Template] = [], fresh: [Template] = []
        for e in bundle.templates {
            let t = e.template
            if !templates.exists(id: t.id, version: t.version) { fresh.append(t) }
            else if let old = templates.data(id: t.id, version: t.version), !sameContent(old, e.data) { conflicts.append(t) }
        }
        return Plan(existing: projects.load(bundle.project.id), conflicts: conflicts, newTemplates: fresh)
    }

    public func apply(_ bundle: ProjectBundle, overwriteTemplates: Bool, now: Date = Date()) throws -> Result {
        let plan = plan(bundle)
        let conflictKeys = Set(plan.conflicts.map(\.key))
        var installed: [Template] = [], skipped: [Template] = []
        for e in bundle.templates {
            if conflictKeys.contains(e.template.key) && !overwriteTemplates { skipped.append(e.template); continue }
            if templates.exists(id: e.template.id, version: e.template.version) && !conflictKeys.contains(e.template.key) { continue }
            installed.append(try templates.install(e.data))
        }
        let bundled = Set(bundle.templates.map { TemplateRef($0.template) })
        let refs = bundle.project.templates.filter { bundled.contains($0) || templates.exists(id: $0.id, version: $0.version) }
        let dropped = bundle.project.templates.filter { !refs.contains($0) }

        var p: Project
        if var current = plan.existing {
            current.name = bundle.project.name; current.client = bundle.project.client
            current.description = bundle.project.description; current.color = bundle.project.color
            current.templates = refs
            p = try projects.update(current, now: now)
        } else {
            p = bundle.project
            p.templates = refs; p.archived = false; p.createdAt = now; p.updatedAt = now
            p.code = ProjectCode.unique(p.code, taken: projects.codes())
            p = try projects.create(p)
        }
        if let sites = bundle.sites { try projects.sites(p.id).save(sites) }
        return Result(project: p, updated: plan.existing != nil, installed: installed, skippedConflicts: skipped,
                      droppedRefs: dropped, siteCount: bundle.sites?.count ?? projects.sites(p.id).load().count)
    }
}
