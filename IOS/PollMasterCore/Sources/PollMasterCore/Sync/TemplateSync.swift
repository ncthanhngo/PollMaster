import Foundation

/// A template the server assigned to a project (`assigned` of `GET poll-templates`).
public struct ServerTemplateRef: Decodable, Equatable, Sendable {
    public var templateId: String
    public var version: String
    public var position: Int

    public init(templateId: String, version: String, position: Int = 0) { self.templateId = templateId; self.version = version; self.position = position }

    enum CodingKeys: String, CodingKey { case templateId = "template_id", version, position }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        templateId = try c.decode(String.self, forKey: .templateId)
        version = try c.decode(String.self, forKey: .version)
        position = c.lenient(Int.self, .position) ?? 0
    }

    public var ref: TemplateRef { TemplateRef(id: templateId, version: version) }
}

/// Library entry with its full template JSON.
public struct ServerTemplate: Decodable, Equatable, Sendable {
    public var templateId: String
    public var version: String
    public var content: JSONValue

    public init(templateId: String, version: String, content: JSONValue) { self.templateId = templateId; self.version = version; self.content = content }

    enum CodingKeys: String, CodingKey { case templateId = "template_id", version, content }
}

/// `data` of `GET /projects/:id/poll-templates` (template-schema §10.6).
public struct ProjectTemplatesInfo: Decodable, Equatable, Sendable {
    public var canManage: Bool
    public var assigned: [ServerTemplateRef]
    public var removedIds: [String]
    public var templates: [ServerTemplate]

    public init(canManage: Bool = false, assigned: [ServerTemplateRef] = [], removedIds: [String] = [], templates: [ServerTemplate] = []) {
        self.canManage = canManage; self.assigned = assigned; self.removedIds = removedIds; self.templates = templates
    }

    enum CodingKeys: String, CodingKey { case canManage = "can_manage", assigned, removedIds = "removed_ids", templates }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        canManage = c.lenient(Bool.self, .canManage) ?? false
        assigned = (c.lenient([ServerTemplateRef].self, .assigned) ?? []).sorted { $0.position < $1.position }
        removedIds = c.lenient([String].self, .removedIds) ?? []
        templates = c.lenient([ServerTemplate].self, .templates) ?? []
    }
}

/// A refusal or problem worth a sentence for the user (permission, bad template, server message). Network errors are thrown as they are.
public struct TemplateSyncFailure: Error, Equatable, LocalizedError, Sendable {
    public var message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

/// The template calls of the ERP backend (`project-poll-templates.md`). The app implements it over HTTP; tests use a fake.
public protocol TemplateRemote: Sendable {
    /// nil when the server has no such API or the project is not visible (404): the device keeps its own list.
    func list(projectId: String) async throws -> ProjectTemplatesInfo?
    /// POST: adds `content` (a template JSON object) to the library and assigns it.
    func add(projectId: String, content: JSONValue) async throws
    func patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?) async throws
    /// DELETE: false when the server never had the template in this project (404).
    func remove(projectId: String, templateId: String) async throws -> Bool
}

/// What the server must be told after reading a project's templates (phase 07). Pure: both platforms run the shared vectors.
public enum TemplateSyncPlanner {
    public struct Result: Equatable, Sendable {
        /// Templates to POST to the project.
        public var post: [TemplateRef]
        /// What `project.templates` becomes once those POSTs succeed.
        public var templates: [TemplateRef]
    }

    /// - `local`: the project's list on the device. `assigned`/`removedIds`: the server's. `library`: newest version of every template on
    ///   the device. `bundled`: newest bundled versions. A server with no row for the project (both lists empty) is "not initialised":
    ///   the manager's device uploads its list (a Phoenix project without templates gets the whole library); others keep their own list.
    ///   A manager of a Phoenix project also uploads bundled templates the server neither assigns nor remembers as removed.
    public static func plan(local: [TemplateRef], assigned: [TemplateRef], removedIds: [String], canManage: Bool, isPhoenix: Bool,
                            library: [TemplateRef], bundled: [TemplateRef]) -> Result {
        let initial = assigned.isEmpty && removedIds.isEmpty
        guard canManage else { return Result(post: [], templates: initial ? local : assigned) }
        var post = initial ? (isPhoenix && local.isEmpty ? library : local) : []
        if isPhoenix {
            let known = Set(assigned.map(\.id)).union(removedIds).union(post.map(\.id))
            post += bundled.filter { !known.contains($0.id) }
        }
        return Result(post: post, templates: initial ? post : assigned + post)
    }
}

/// Reads and writes a project's templates through the server; the device copy follows only what the server confirmed (no offline queue).
public struct TemplateSync: Sendable {
    let projects: ProjectStore
    let templates: TemplateStore
    let remote: any TemplateRemote

    public init(root: URL, remote: any TemplateRemote) {
        projects = ProjectStore(root: root); templates = TemplateStore(root: root); self.remote = remote
    }

    public struct Outcome: Equatable, Sendable {
        /// The device's templates or project list changed (reload the screens).
        public var changed = false
        /// The server has no template API for this project: nothing was done.
        public var unavailable = false
        public var posted = 0
        /// First POST the server refused (the rest of the run is not affected).
        public var failure: String?
    }

    /// Per-project step before the records (phase 07): read, install, set `project.templates`, upload what the plan says, read again.
    public func sync(projectId: String, library: [TemplateRef], bundled: [TemplateRef]) async throws -> Outcome {
        var out = Outcome()
        guard let project = projects.load(projectId), !project.archived else { return out }
        guard let info = try await remote.list(projectId: projectId) else { out.unavailable = true; return out }
        let initial = info.assigned.isEmpty && info.removedIds.isEmpty
        out.changed = try apply(info, to: projectId, keepList: initial)
        let plan = TemplateSyncPlanner.plan(local: project.templates, assigned: info.assigned.map(\.ref), removedIds: info.removedIds,
                                            canManage: info.canManage, isPhoenix: project.startsWithLibrary, library: library, bundled: bundled)
        for ref in plan.post {
            guard let content = content(of: ref) else { continue }
            do { try await remote.add(projectId: projectId, content: content); out.posted += 1 }
            catch let f as TemplateSyncFailure { out.failure = out.failure ?? f.message }
            catch is TemplateVersionExists { continue }            // the library holds other content for that id@version: not for this run to fix
        }
        if out.posted > 0, let again = try await remote.list(projectId: projectId), try apply(again, to: projectId, keepList: false) { out.changed = true }
        return out
    }

    /// Re-reads the project's templates and applies them (after an action the server confirmed).
    @discardableResult
    public func refresh(projectId: String) async throws -> Bool {
        guard let info = try await remote.list(projectId: projectId) else { throw TemplateSyncFailure("Máy chủ chưa hỗ trợ mẫu theo dự án.") }
        return try apply(info, to: projectId, keepList: info.assigned.isEmpty && info.removedIds.isEmpty)
    }

    /// Adds templates (from the device library) to the project.
    public func assign(_ refs: [TemplateRef], to projectId: String) async throws {
        var failure: Error?
        for ref in refs {
            do { try await remote.add(projectId: projectId, content: try requireContent(ref)) } catch { failure = error; break }
        }
        _ = try? await refresh(projectId: projectId)          // whatever the server took is shown even when a later one failed
        if let failure { throw failure }
    }

    /// Removes a template from the project. A template the server never had is uploaded first so the removal is remembered.
    public func unassign(_ ref: TemplateRef, from projectId: String) async throws {
        if try await !remote.remove(projectId: projectId, templateId: ref.id) {
            try await remote.add(projectId: projectId, content: try requireContent(ref))
            _ = try await remote.remove(projectId: projectId, templateId: ref.id)
        }
        try await refresh(projectId: projectId)
    }

    /// Switches the project to another version of a template (a newer one for new records). The version may exist only on this device.
    public func useVersion(_ ref: TemplateRef, in projectId: String) async throws {
        do { try await remote.add(projectId: projectId, content: try requireContent(ref)) }
        catch is TemplateVersionExists { try await remote.patch(projectId: projectId, templateId: ref.id, version: ref.version, title: nil, shortName: nil, color: nil) }
        try await refresh(projectId: projectId)
    }

    /// Name, short name, colour of the template version assigned in `projectId` (applies to the whole organisation library).
    public func editMeta(of ref: TemplateRef, in projectId: String, title: String, shortName: String, color: String) async throws {
        try await remote.patch(projectId: projectId, templateId: ref.id, version: nil, title: title, shortName: shortName, color: color)
        try await refresh(projectId: projectId)
    }

    // MARK: internals

    private func content(of ref: TemplateRef) -> JSONValue? {
        templates.data(id: ref.id, version: ref.version).flatMap { try? JSONDecoder().decode(JSONValue.self, from: $0) }
    }

    private func requireContent(_ ref: TemplateRef) throws -> JSONValue {
        guard let c = content(of: ref) else { throw TemplateSyncFailure("Mẫu \(ref.id) v\(ref.version) không có trong thư viện trên máy.") }
        return c
    }

    /// Installs the server's templates (server wins on id@version), then the project's list and removed ids. Returns whether anything changed.
    private func apply(_ info: ProjectTemplatesInfo, to projectId: String, keepList: Bool) throws -> Bool {
        var changed = false
        for t in info.templates {
            guard let data = try? JSONEncoder().encode(t.content), let theirs = try? TemplateLoader.decode(data),
                  theirs.id == t.templateId, theirs.version == t.version else { continue }          // not a usable template: skip it
            if let mine = templates.data(id: t.templateId, version: t.version), (try? JSONDecoder().decode(JSONValue.self, from: mine)) == t.content { continue }
            try templates.install(data)
            changed = true
        }
        guard var p = projects.load(projectId) else { return changed }
        let list = keepList ? p.templates : info.assigned.map(\.ref)
        if p.templates != list || p.removedTemplateIds != info.removedIds {
            p.templates = list; p.removedTemplateIds = info.removedIds
            try projects.save(p)
            changed = true
        }
        return changed
    }
}

/// `TEMPLATE_VERSION_EXISTS` (409): the id@version is in the organisation library with other content.
public struct TemplateVersionExists: Error, LocalizedError, Sendable {
    public init() {}
    public var errorDescription: String? { "Máy chủ đã có mẫu này (cùng mã và phiên bản) với nội dung khác. Đổi số phiên bản của mẫu rồi thử lại." }
}
