import Foundation

/// A template being edited on this device (`template-schema.md` 11.1). Never synced.
public struct TemplateDraft: Equatable, Sendable {
    public var draftId: String
    /// nil for a new template.
    public var baseId: String?
    public var baseVersion: String?
    /// Project the release goes to.
    public var projectId: String
    public var createdAt: String
    public var updatedAt: String
    /// Version handed out for the release in progress; sending again after a lost reply repeats it.
    public var publishVersion: String?
    public var content: JSONValue

    public init(draftId: String = UUID().uuidString, baseId: String?, baseVersion: String?, projectId: String, createdAt: String, updatedAt: String,
                publishVersion: String? = nil, content: JSONValue) {
        self.draftId = draftId; self.baseId = baseId; self.baseVersion = baseVersion; self.projectId = projectId
        self.createdAt = createdAt; self.updatedAt = updatedAt; self.publishVersion = publishVersion; self.content = content
    }

    /// The template's id (the base's, or the typed one of a new template).
    public var templateId: String { baseId ?? content.object?["id"]?.string ?? "" }

    public var doc: TemplateDoc {
        get { TemplateDoc(root: content) }
        set { content = newValue.root }
    }

    var json: JSONValue {
        func opt(_ s: String?) -> JSONValue { s.map(JSONValue.string) ?? .null }
        return .object(["draftId": .string(draftId), "baseId": opt(baseId), "baseVersion": opt(baseVersion), "projectId": .string(projectId),
                        "createdAt": .string(createdAt), "updatedAt": .string(updatedAt), "publishVersion": opt(publishVersion), "content": content])
    }

    init?(json: JSONValue) {
        guard let o = json.object, let id = o["draftId"]?.string, let pid = o["projectId"]?.string, let content = o["content"], content.object != nil else { return nil }
        self.init(draftId: id, baseId: o["baseId"]?.string, baseVersion: o["baseVersion"]?.string, projectId: pid, createdAt: o["createdAt"]?.string ?? "",
                  updatedAt: o["updatedAt"]?.string ?? "", publishVersion: o["publishVersion"]?.string, content: content)
    }
}

/// `<root>/template-drafts/<draftId>.json`, written atomically.
public struct TemplateDraftStore: Sendable {
    public let dir: URL

    public init(root: URL) { dir = root.appendingPathComponent("template-drafts", isDirectory: true) }

    private func url(_ id: String) -> URL { dir.appendingPathComponent("\(RecordNaming.sanitize(id)).json") }

    public func save(_ d: TemplateDraft) throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data(TemplateJSONWriter.write(d.json).utf8).write(to: url(d.draftId), options: .atomic)
    }

    public func load(_ draftId: String) -> TemplateDraft? {
        guard let data = try? Data(contentsOf: url(draftId)), let v = try? JSONDecoder().decode(JSONValue.self, from: data) else { return nil }
        return TemplateDraft(json: v)
    }

    /// Newest edit first; unreadable files are skipped.
    public func list() -> [TemplateDraft] {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }
            .compactMap { (try? Data(contentsOf: $0)).flatMap { try? JSONDecoder().decode(JSONValue.self, from: $0) }.flatMap(TemplateDraft.init(json:)) }
            .sorted { ($0.updatedAt, $0.draftId) > ($1.updatedAt, $1.draftId) }
    }

    public func delete(_ draftId: String) throws {
        let u = url(draftId)
        if FileManager.default.fileExists(atPath: u.path) { try FileManager.default.removeItem(at: u) }
    }

    /// The one draft this device keeps for a template id (nil when none).
    public func existing(forTemplateId id: String) -> TemplateDraft? { list().first { $0.templateId == id } }
}
