import Foundation

/// First run of a build with projects (schema §9.2). Safe to run on every launch.
public struct ProjectMigration: Sendable {
    public let root: URL
    public init(root: URL) { self.root = root }

    public struct Result: Equatable, Sendable {
        public var general: Project?
        public var recordsAssigned = 0
        public var sitesMoved = false
    }

    /// Creates "Chung" when there is nothing yet, or when records without `projectId` / a legacy `sites.json` need a home;
    /// then moves the legacy sites into it and assigns the orphan records. Records are rewritten only to add `projectId`.
    @discardableResult
    public func run(now: Date = Date()) throws -> Result {
        let projects = ProjectStore(root: root), records = RecordStore(root: root), legacySites = SiteStore(root: root)
        let templates = TemplateStore(root: root).list()
        var result = Result()

        let hasLegacySites = FileManager.default.fileExists(atPath: legacySites.url.path)
        let existing = projects.list()
        var general = projects.load(Project.generalId) ?? projects.general()
        if let g = general, g.id != Project.generalId, !FileManager.default.fileExists(atPath: projects.folder(Project.generalId).path) {
            general = try adoptFixedId(g, projects: projects, records: records)
        }
        let orphans = records.list().filter { $0.projectId == nil }
        if general == nil, existing.isEmpty || !orphans.isEmpty || hasLegacySites {
            let code = ProjectCode.unique(Project.generalCode, taken: Set(existing.map(\.code)))
            let refs = TemplateVersion.newest(templates).map(TemplateRef.init)
            general = try projects.create(Project(id: Project.generalId, code: code, name: Project.generalName, color: Project.palette[0], templates: refs, now: now))
        }
        result.general = general
        guard let general else { return result }

        if hasLegacySites {
            let list = legacySites.load()
            if list.isEmpty { try legacySites.clear() }
            else if projects.sites(general.id).load().isEmpty {
                try projects.sites(general.id).save(list)
                try legacySites.clear()
                result.sitesMoved = true
            }
        }
        for var r in orphans {
            r.projectId = general.id
            try records.save(r)
            result.recordsAssigned += 1
        }
        return result
    }

    /// A "Chung" made before the id was fixed (random uuid): rename its folder and repoint its records.
    private func adoptFixedId(_ old: Project, projects: ProjectStore, records: RecordStore) throws -> Project {
        let oldId = old.id
        try FileManager.default.moveItem(at: projects.folder(oldId), to: projects.folder(Project.generalId))
        var p = old
        p.id = Project.generalId
        try projects.save(p)
        for var r in records.list() where r.projectId == oldId {
            r.projectId = Project.generalId
            try records.save(r)
        }
        return p
    }
}
