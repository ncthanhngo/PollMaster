import Foundation

public enum ProjectError: Error, LocalizedError, Equatable {
    case emptyName
    case invalidCode(String)
    case codeTaken(String)
    case notFound

    public var errorDescription: String? {
        switch self {
        case .emptyName: return "Cần nhập tên dự án."
        case .invalidCode: return "Mã dự án chỉ gồm chữ A-Z, số và dấu gạch ngang, tối đa \(ProjectCode.maxLength) ký tự."
        case .codeTaken(let c): return "Mã \(c) đã có dự án khác dùng."
        case .notFound: return "Không tìm thấy dự án."
        }
    }
}

/// Projects on device: `<root>/projects/<id>/project.json` + `sites.json`.
public struct ProjectStore: Sendable {
    public let dir: URL

    public init(root: URL) { dir = root.appendingPathComponent("projects", isDirectory: true) }

    public func folder(_ id: String) -> URL { dir.appendingPathComponent(id, isDirectory: true) }
    public func sites(_ id: String) -> SiteStore { SiteStore(file: folder(id).appendingPathComponent("sites.json")) }

    /// Optional photo avatar (`avatar.jpg` next to project.json); nil when the project uses its initials.
    public func avatarURL(_ id: String) -> URL? {
        let url = folder(id).appendingPathComponent("avatar.jpg")
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    public func setAvatar(_ jpeg: Data, for id: String) throws {
        try FileManager.default.createDirectory(at: folder(id), withIntermediateDirectories: true)
        try jpeg.write(to: folder(id).appendingPathComponent("avatar.jpg"), options: .atomic)
    }

    public func removeAvatar(_ id: String) throws {
        guard let url = avatarURL(id) else { return }
        try FileManager.default.removeItem(at: url)
    }

    /// Newest first.
    public func list() -> [Project] {
        let ids = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return ids.filter { !$0.hasPrefix(".") }.compactMap(load).sorted { ($0.createdAt, $0.id) > ($1.createdAt, $1.id) }
    }

    public func load(_ id: String) -> Project? {
        guard let d = try? Data(contentsOf: folder(id).appendingPathComponent("project.json")) else { return nil }
        return try? JSONCoding.makeDecoder().decode(Project.self, from: d)
    }

    public func codes(excluding id: String? = nil) -> Set<String> { Set(list().filter { $0.id != id }.map(\.code)) }

    /// Atomic write; no validation (see `create` / `update`).
    public func save(_ p: Project) throws {
        try FileManager.default.createDirectory(at: folder(p.id), withIntermediateDirectories: true)
        try JSONCoding.makeEncoder().encode(p).write(to: folder(p.id).appendingPathComponent("project.json"), options: .atomic)
    }

    /// Trims, validates (name, code format, code unique ignoring case) and returns the cleaned project.
    private func validated(_ p: Project) throws -> Project {
        var p = p
        p.name = p.name.trimmingCharacters(in: .whitespacesAndNewlines)
        p.code = p.code.trimmingCharacters(in: .whitespaces).uppercased()
        p.client = p.client?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        p.description = p.description?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        guard !p.name.isEmpty else { throw ProjectError.emptyName }
        guard ProjectCode.isValid(p.code) else { throw ProjectError.invalidCode(p.code) }
        if codes(excluding: p.id).contains(where: { $0.uppercased() == p.code }) { throw ProjectError.codeTaken(p.code) }
        return p
    }

    @discardableResult
    public func create(_ p: Project) throws -> Project {
        let v = try validated(p)
        try save(v)
        return v
    }

    @discardableResult
    public func update(_ p: Project, now: Date = Date()) throws -> Project {
        guard load(p.id) != nil else { throw ProjectError.notFound }
        var v = try validated(p)
        v.updatedAt = now
        try save(v)
        return v
    }

    public func setArchived(_ id: String, _ archived: Bool, now: Date = Date()) throws {
        guard var p = load(id) else { throw ProjectError.notFound }
        p.archived = archived; p.updatedAt = now
        try save(p)
    }

    public func addTemplate(_ ref: TemplateRef, to id: String, now: Date = Date()) throws {
        guard var p = load(id) else { throw ProjectError.notFound }
        guard !p.templates.contains(ref) else { return }
        p.templates.append(ref); p.updatedAt = now
        try save(p)
    }

    public func removeTemplate(_ ref: TemplateRef, from id: String, now: Date = Date()) throws {
        guard var p = load(id) else { throw ProjectError.notFound }
        p.templates.removeAll { $0 == ref }; p.updatedAt = now
        try save(p)
    }

    /// Swap the version a project uses for a template id (newer-version suggestion accepted).
    public func useVersion(_ ref: TemplateRef, in id: String, now: Date = Date()) throws {
        guard var p = load(id) else { throw ProjectError.notFound }
        p.templates = p.templates.map { $0.id == ref.id ? ref : $0 }; p.updatedAt = now
        try save(p)
    }
}

public enum ProjectTemplates {
    /// Newer library versions of the templates a project uses, one per template id.
    public static func newerVersions(of p: Project, library: [Template]) -> [TemplateRef] {
        let newest = Dictionary(TemplateVersion.newest(library).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        return p.templates.compactMap { ref in
            guard let n = newest[ref.id], TemplateVersion.isNewer(n.version, than: ref.version) else { return nil }
            return TemplateRef(n)
        }
    }

    /// Projects that use template `id` (any version), for the library's "Dùng trong: …".
    public static func projects(using templateId: String, in all: [Project]) -> [Project] {
        all.filter { p in p.templates.contains { $0.id == templateId } }
    }
}

/// Moving a record to another project (ux-spec M).
public enum ProjectMove {
    /// Active projects other than the current one that use the record's template (any version).
    public static func targets(for r: Record, in all: [Project], currentId: String?) -> [Project] {
        all.filter { !$0.archived && $0.id != currentId && $0.templates.contains { $0.id == r.templateId } }
    }
}

/// Counts shown on project cards and the detail screen.
public struct ProjectStats: Equatable, Sendable {
    public var draft = 0
    public var completed = 0
    public var returned = 0
    public var latest: Date?
    public var total: Int { draft + completed + returned }
    public init() {}

    /// "Cần sửa" records are drafts handed back, counted apart from plain drafts.
    public mutating func add(_ r: Record) {
        if r.status == .completed { completed += 1 } else if r.returned { returned += 1 } else { draft += 1 }
        if latest.map({ r.updatedAt > $0 }) ?? true { latest = r.updatedAt }
    }
}
