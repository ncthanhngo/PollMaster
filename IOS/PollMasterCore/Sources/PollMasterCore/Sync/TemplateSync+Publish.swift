import Foundation

/// A project that did not take the new version (the main release stands).
public struct PublishFailure: Equatable, Sendable {
    public var projectId: String
    public var message: String
}

public enum PublishOutcome: Equatable, Sendable {
    /// `version` is the one now assigned (the base's for a name/colour-only change).
    case published(version: String, failedProjects: [PublishFailure])
    /// The target project holds another version than the draft started from: ask, then call again with `force`.
    case staleBase(serverVersion: String)
    /// Errors that block publishing.
    case invalid([TemplateIssue])
    /// A new template whose code the organisation already has.
    case idTaken
}

extension TemplateSync {
    static let maxVersionTries = 5

    /// Releases a draft into `projectId` through the server (`template-schema.md` 11.2); `also` are other managed projects moved to the new version.
    /// Throws what the network or the server refuses (the draft keeps its `publishVersion`, so sending again repeats the same release).
    public func publish(_ draft: TemplateDraft, to projectId: String, also: [String] = [], force: Bool = false) async throws -> PublishOutcome {
        let doc = draft.doc, base = baseDoc(of: draft)
        let errors = doc.lint(base: base, isNewTemplate: draft.baseId == nil).filter { $0.severity == .error }
        if !errors.isEmpty { return .invalid(errors) }
        let id = doc.id ?? draft.templateId
        guard let info = try await remote.list(projectId: projectId) else { throw TemplateSyncFailure("Máy chủ chưa hỗ trợ mẫu theo dự án.") }
        guard info.canManage else { throw TemplateSyncFailure("Cần quyền quản lý dự án và có mạng.") }
        let assigned = info.assigned.first { $0.templateId == id }?.version
        if draft.baseId != nil, !force, draft.publishVersion == nil, let assigned, assigned != draft.baseVersion { return .staleBase(serverVersion: assigned) }
        let kind = draft.baseId == nil ? .newTemplate : TemplatePublishRules.publishKind(base: base, draft: doc)

        var d = draft, version = draft.baseVersion ?? "1.0"
        switch kind {
        case .meta:
            let o = doc.root.object
            try await remote.patch(projectId: projectId, templateId: id, version: nil, title: o?["title"]?.string ?? "", shortName: o?["shortName"]?.string ?? "", color: o?["color"]?.string ?? "")
        case .newTemplate:
            version = "1.0"
            do { try await remote.add(projectId: projectId, content: try withVersion(doc, version).root) } catch is TemplateVersionExists { return .idTaken }
        case .newVersion:
            var known = templates.list().filter { $0.id == id }.map(\.version) + info.assigned.filter { $0.templateId == id }.map(\.version)
            known += info.templates.filter { $0.templateId == id }.map(\.version)
            version = draft.publishVersion ?? TemplatePublishRules.nextVersion(known: known)
            var posted = false
            for _ in 0..<Self.maxVersionTries where !posted {
                d.publishVersion = version
                try drafts.save(d)
                do { try await remote.add(projectId: projectId, content: try withVersion(doc, version).root); posted = true }
                catch is TemplateVersionExists { version = TemplatePublishRules.bumpMinor(version) }
            }
            guard posted else { throw TemplateSyncFailure("Không cấp được số phiên bản mới cho mẫu. Thử lại sau.") }
        }
        try? drafts.delete(draft.draftId)

        var failed: [PublishFailure] = [], touched = [projectId]
        if kind != .meta {
            for pid in also where pid != projectId {
                do { try await remote.patch(projectId: pid, templateId: id, version: version, title: nil, shortName: nil, color: nil); touched.append(pid) }
                catch is CancellationError { throw CancellationError() }
                catch { failed.append(PublishFailure(projectId: pid, message: error.localizedDescription)) }
            }
        }
        for pid in touched { _ = try? await refresh(projectId: pid) }       // the library follows the server
        return .published(version: version, failedProjects: failed)
    }

    /// Signed out: the release goes into this device's library and `projectId` (no network).
    public func publishLocally(_ draft: TemplateDraft, to projectId: String) throws -> PublishOutcome {
        let doc = draft.doc, base = baseDoc(of: draft)
        let errors = doc.lint(base: base, isNewTemplate: draft.baseId == nil).filter { $0.severity == .error }
        if !errors.isEmpty { return .invalid(errors) }
        let id = doc.id ?? draft.templateId
        let kind = draft.baseId == nil ? .newTemplate : TemplatePublishRules.publishKind(base: base, draft: doc)
        var version = draft.baseVersion ?? "1.0"
        switch kind {
        case .meta:
            let o = doc.root.object
            for t in templates.list() where t.id == id {
                guard let data = templates.data(id: id, version: t.version) else { continue }
                try templates.install(TemplateText.edited(data, title: o?["title"]?.string ?? "", shortName: o?["shortName"]?.string, color: o?["color"]?.string))
            }
        case .newTemplate, .newVersion:
            version = kind == .newTemplate ? "1.0" : TemplatePublishRules.nextVersion(known: templates.list().filter { $0.id == id }.map(\.version))
            try templates.install(try withVersion(doc, version).data())
            let ref = TemplateRef(id: id, version: version)
            if kind == .newTemplate { try projects.addTemplate(ref, to: projectId) } else { try projects.useVersion(ref, in: projectId) }
        }
        try? drafts.delete(draft.draftId)
        return .published(version: version, failedProjects: [])
    }

    /// The newest version older than `ref` that this device has ("Quay lại bản trước"); nil when none.
    public func previousVersion(of ref: TemplateRef) -> TemplateRef? {
        TemplatePublishRules.previousVersion(of: ref.version, among: templates.list().filter { $0.id == ref.id }.map(\.version)).map { TemplateRef(id: ref.id, version: $0) }
    }

    /// Switches `projectId` back to the previous version of a template (`useVersion` of `previousVersion`).
    public func rollBack(_ ref: TemplateRef, in projectId: String) async throws {
        guard let previous = previousVersion(of: ref) else { throw TemplateSyncFailure("Mẫu này chưa có bản trước trên máy.") }
        try await useVersion(previous, in: projectId)
    }

    private func baseDoc(of d: TemplateDraft) -> TemplateDoc? {
        guard let id = d.baseId, let v = d.baseVersion, let data = templates.data(id: id, version: v), let text = String(data: data, encoding: .utf8) else { return nil }
        return try? TemplateDoc(text: text)
    }

    private func withVersion(_ doc: TemplateDoc, _ version: String) throws -> TemplateDoc {
        try TemplateEdit.apply(doc, .set(path: [], key: "version", value: .string(version)))
    }
}
